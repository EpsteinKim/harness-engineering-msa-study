# 멱등성(Idempotency) 학습 문서

> 이 프로젝트(harness-back)의 이벤트 구동 아키텍처에서 중복 메시지를 안전하게 처리하기 위한 학습 문서.
> Outbox 패턴과 함께 "유실 없음 + 중복 무해"를 보장하는 핵심 패턴.

---

## 1. 멱등성이란?

**같은 연산을 여러 번 실행해도 결과가 한 번 실행한 것과 같은 성질.**

```
멱등한 연산:
  DELETE FROM payment WHERE id = 100  → 1번 실행하든 10번 실행하든 결과 같음
  seat.status = RESERVED              → 몇 번이든 RESERVED

멱등하지 않은 연산:
  INSERT INTO payment (...)           → 실행할 때마다 새 행 생성
  balance = balance - 100000          → 실행할 때마다 10만원 차감
```

---

## 2. 왜 필요한가?

### Kafka at-least-once delivery

Kafka consumer는 기본적으로 **at-least-once** — 메시지를 최소 1번 전달하지만, 2번 이상 전달될 수 있다.

```
Consumer: 메시지 처리 완료 → offset 커밋 시도 → pod 죽음
→ 복구 후 같은 offset부터 재소비 → 같은 메시지 2번 처리
```

### Outbox 중복 발행

```
OutboxPublisher: Kafka 발행 성공 → DELETE 시도 → pod 죽음
→ 복구 후 같은 outbox 행 다시 발행 → consumer가 같은 메시지 2번 수신
```

### 네트워크 재시도

```
Client → POST /pay → 서버 처리 성공 → 응답 전에 네트워크 끊김
→ 클라이언트가 타임아웃 → 재시도 → 서버가 2번째 요청도 처리
→ 이중 결제
```

**Outbox가 "유실 없음"을 보장하고, 멱등성이 "중복 무해"를 보장한다. 둘의 조합 = effectively-once.**

---

## 3. 멱등성 구현 방법

### 방법 1: 상태 체크 (가장 간단)

이미 처리된 상태이면 스킵.

```kotlin
fun onPaymentSucceeded(sagaId: Long, paymentId: Long) {
    val saga = sagaRepository.findById(sagaId).orElse(null) ?: return
    if (saga.status != SagaStatus.IN_PROGRESS) return  // 이미 완료/실패 → 무시
    if (saga.step == SagaStep.COMPLETED) return         // 이미 처리됨 → 무시
    
    // 실제 처리
    saga.step = SagaStep.COMPLETED
    saga.status = SagaStatus.COMPLETED
}
```

**장점:** 추가 테이블 불필요, Saga step이 자연스러운 멱등성 체크
**한계:** 상태 전이가 있는 경우에만 적용 가능. 단순 INSERT는 방어 못함

### 방법 2: Processed 테이블 (범용)

처리한 메시지 ID를 DB에 기록. 중복이면 스킵.

```sql
processed_events (
    event_id    VARCHAR(100) PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT now()
)
```

```kotlin
@Transactional
fun onCreatePayment(command: CreatePaymentCommand) {
    val eventId = "create-payment:${command.sagaId}"
    if (processedRepository.existsById(eventId)) return  // 이미 처리됨
    
    // 실제 처리
    paymentService.createPendingForSeat(...)
    outboxService.save(PaymentCreated(...))
    
    // 같은 TX에서 기록
    processedRepository.save(ProcessedEvent(eventId))
}
```

**장점:** 모든 메시지 타입에 범용 적용
**한계:** 테이블 무한 증가 → 주기적 정리 필요 (30일 지난 레코드 삭제 등)

### 방법 3: Idempotency Key (API 레벨)

클라이언트가 고유 키를 생성, 서버가 중복 체크.

```
Client → POST /pay
         Header: Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000

Server:
  if (idempotencyStore.exists(key)) return cachedResponse
  result = processPayment()
  idempotencyStore.save(key, result)  // TTL 24시간
  return result
```

**장점:** 클라이언트 재시도에도 안전, PG사 연동 시 필수
**한계:** 클라이언트 협조 필요 (key 생성 + 전송)

### 방법 4: DB Unique Constraint

데이터베이스 레벨에서 중복 방지.

```sql
ALTER TABLE payment ADD CONSTRAINT uq_payment_saga UNIQUE (saga_id);
-- 같은 saga_id로 2번 INSERT 시도 → 2번째는 constraint violation → 무시
```

```kotlin
fun createPendingForSeat(sagaId: Long, ...) {
    try {
        paymentRepository.save(Payment(sagaId = sagaId, ...))
    } catch (e: DataIntegrityViolationException) {
        // 이미 존재 → 멱등하게 무시
        return paymentRepository.findBySagaId(sagaId)!!
    }
}
```

**장점:** DB가 보장, 코드 실수에 안전
**한계:** 예외 기반 제어 흐름 (안티패턴 논란)

---

## 4. 현재 프로젝트 분석

### 이미 멱등한 곳 (Saga 상태 체크)

| 메서드 | 멱등성 보장 방식 |
|--------|----------------|
| `SagaOrchestrator.onPaymentCreated()` | `saga.step != SEAT_HELD → return` |
| `SagaOrchestrator.onPaymentSucceeded()` | `saga.status != IN_PROGRESS → return` |
| `SagaOrchestrator.onPaymentFailed()` | `saga.status != IN_PROGRESS → return` |
| `SagaOrchestrator.onTimeout()` | `saga.status != IN_PROGRESS → return` |
| `SagaOrchestrator.compensate()` | `seat.status != PAYMENT_PENDING → skip` |
| `PaymentService.createPendingForSeat()` | 기존 PENDING 체크 → 존재하면 기존 반환 |
| `PaymentProcessingService.process()` | `findBySeatIdAndStatus(PENDING) = null → return` |
| `PaymentTerminationService.expire/cancel()` | `findBySeatIdAndStatus(PENDING) = null → return` |

### 멱등하지 않은 곳 (위험)

| 위치 | 문제 | 영향 |
|------|------|------|
| `PaymentCommandConsumer.onCreatePayment()` | PaymentCreated 이벤트 중복 발행 가능 | Orchestrator가 step 체크로 무시 (현재 안전) |
| `QueueConsumer.onMessage()` | 같은 EnqueueMessage 2번 소비 시 좌석 2번 배정 시도 | 낙관적/비관적 락이 방어하지만 불필요한 DB 부하 |
| `ReservationService.enqueue()` | 네트워크 재시도 시 같은 사용자 2번 enqueue | Redis 큐 중복 체크(isInQueue)로 방어 |

### 현재 안전도 종합

```
API 레벨 (HTTP 재시도):     △ Redis 큐 체크로 부분 방어
Consumer 레벨 (Kafka 중복): ○ Saga 상태 체크로 대부분 방어
DB 레벨 (constraint):       △ unique constraint 없음
PG사 연동 (미래):           ✗ idempotency key 미구현
```

---

## 5. 실무에서의 멱등성 계층

```
   ┌─────────────────────────────────────┐
   │  API Gateway (Idempotency-Key)      │ ← 클라이언트 재시도 방어
   ├─────────────────────────────────────┤
   │  Consumer (processed 테이블 체크)    │ ← Kafka 중복 소비 방어
   ├─────────────────────────────────────┤
   │  Service (상태 체크, step 체크)      │ ← 비즈니스 로직 레벨 방어
   ├─────────────────────────────────────┤
   │  DB (unique constraint)             │ ← 최후의 방어선
   └─────────────────────────────────────┘
```

계층별로 겹겹이 방어하는 게 실무 패턴. 하나가 뚫려도 다음 계층이 막는다.

---

## 6. Outbox + 멱등성 = effectively-once

| 패턴 | 보장 | 단독 사용 시 문제 |
|------|------|------------------|
| Outbox | 메시지 유실 없음 (at-least-once) | 중복 발행 가능 |
| 멱등성 | 중복 처리해도 결과 같음 | 유실은 못 막음 |
| **Outbox + 멱등성** | **유실 없음 + 중복 무해 = effectively-once** | - |

```
Producer (Outbox):
  "반드시 한 번 이상 발행한다" (유실 방지)
  
Consumer (멱등성):
  "여러 번 받아도 한 번만 처리한다" (중복 방지)

조합:
  "정확히 한 번 처리된 것과 같은 효과"
```

---

## 7. Saga 오케스트레이션과 멱등성의 관계

Saga 테이블의 step/status가 **자연스러운 멱등성 체크** 역할을 한다.

```
Saga: step=SEAT_HELD, status=IN_PROGRESS

CreatePaymentCommand 1번째:
  → step == SEAT_HELD? YES → 처리 → step = PAYMENT_CREATED

CreatePaymentCommand 2번째 (중복):
  → step == SEAT_HELD? NO (이미 PAYMENT_CREATED) → return (무시)
```

**Saga 오케스트레이션 = 상태 머신**이고, 상태 머신은 **동일 입력에 대해 동일 전이를 보장**한다. 이미 전이된 상태에서 같은 입력이 오면 무시. 이게 멱등성의 핵심.

---

## 8. 언제 추가 구현이 필요한가?

| 상황 | Saga 상태 체크로 충분? | 추가 필요 |
|------|---------------------|----------|
| Kafka 중복 소비 | ✅ step/status 체크 | 불필요 |
| Outbox 중복 발행 | ✅ consumer가 상태 체크 | 불필요 |
| HTTP 클라이언트 재시도 | △ Redis 큐 체크 | Idempotency-Key (API 레벨) |
| 외부 PG사 연동 | ✗ | 필수 (이중 청구 위험) |
| INSERT 기반 연산 | △ | unique constraint 또는 processed 테이블 |

**현재 프로젝트에서는 Saga 상태 체크 + 기존 방어 코드로 대부분 안전.** PG사 연동이나 HTTP API 멱등성은 해당 기능 구현 시 추가.

---

## 참고 자료

- [Idempotent Consumer (Enterprise Integration Patterns)](https://www.enterpriseintegrationpatterns.com/IdempotentReceiver.html)
- [Stripe Idempotency Keys](https://stripe.com/docs/api/idempotent_requests)
- [Kafka Exactly-Once Semantics](https://www.confluent.io/blog/exactly-once-semantics-are-possible-heres-how-apache-kafka-does-it/)
