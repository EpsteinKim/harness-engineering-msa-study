# Outbox 패턴 학습 문서

> 이 프로젝트(harness-back)의 이벤트 구동 아키텍처에서 DB-Kafka 정합성을 보장하기 위한 학습 문서.
> 현재 직접 Kafka 발행 방식의 문제점과 Outbox 패턴 적용 방향을 정리.

---

## 1. 문제: 이중 쓰기 (Dual Write)

DB와 Kafka는 서로 다른 시스템이라 **하나의 트랜잭션으로 묶을 수 없다.**

```kotlin
@Transactional
fun assignSeat() {
    seat.status = PAYMENT_PENDING
    seatRepository.save(seat)                    // ① DB 쓰기
    kafkaTemplate.send(SeatHeld(...))            // ② Kafka 쓰기
}
```

| 시나리오 | 결과 |
|----------|------|
| ① 성공 → ② 실패 (네트워크) | DB는 바뀜, 이벤트 안 나감 → 고아 좌석 |
| ① 성공 → ② 성공 → TX 롤백 | 이벤트는 나갔는데 DB는 원복 → 유령 이벤트 |
| ① 성공 → 서버 죽음 → ② 미실행 | 이벤트 영구 유실 |

**핵심:** `kafkaTemplate.send()`는 DB 트랜잭션 밖에 있다. 둘의 성공/실패를 동기화할 방법이 없다.

---

## 2. 해결: Outbox 패턴

### 핵심 아이디어

Kafka에 직접 보내지 않는다. 같은 DB 트랜잭션에 "보낼 메시지"를 저장한다. 별도 프로세스가 그 테이블을 읽어서 Kafka에 발행한다.

```
비즈니스 로직 (@Transactional)
  ├─ DB: seat 저장
  └─ DB: outbox INSERT     ← 같은 TX → 원자적
  
OutboxPublisher (별도 프로세스)
  └─ SELECT → Kafka 발행 → DELETE
```

### Outbox 테이블

```sql
outbox (
    id         BIGSERIAL PRIMARY KEY,
    topic      VARCHAR(100),    -- "seat.events"
    key        VARCHAR(100),    -- 파티션 키 (seatId 등)
    payload    JSONB,           -- 이벤트 본문
    created_at TIMESTAMP DEFAULT now()
)
```

- PK(id) auto increment → `ORDER BY id LIMIT N`으로 순차 스캔
- 발행 완료 시 DELETE → 테이블 항상 작게 유지
- 별도 인덱스 불필요 (PK clustered index로 충분)

### 비즈니스 로직 (Producer 쪽)

```kotlin
@Transactional
fun assignSeat() {
    seat.status = PAYMENT_PENDING
    seatRepository.save(seat)
    
    // Kafka 직접 발행 ❌
    outboxRepository.save(
        OutboxEvent(
            topic = "seat.events",
            key = seat.id.toString(),
            payload = objectMapper.writeValueAsString(SeatHeld(...))
        )
    )
}
// TX 커밋 → seat + outbox 둘 다 저장 (원자적)
// TX 롤백 → seat + outbox 둘 다 롤백 (원자적)
```

### OutboxPublisher (Relay)

```kotlin
@Scheduled(fixedDelay = 1000)
fun relay() {
    val pending = outboxRepository.findAllOrderByIdLimit(100)
    for (event in pending) {
        kafkaTemplate.send(event.topic, event.key, event.payload)
    }
    outboxRepository.deleteAll(pending)
}
```

---

## 3. 안전성 분석

### 모든 장애 시나리오

| 시나리오 | 결과 | 안전? |
|----------|------|-------|
| TX 커밋 전 서버 죽음 | seat + outbox 둘 다 롤백 | ✅ 일관성 유지 |
| TX 커밋 후, Kafka 발행 전 죽음 | outbox에 미발행 행 남아있음 → 복구 후 발행 | ✅ 유실 없음 |
| Kafka 발행 후, DELETE 전 죽음 | 다음 폴링에서 재발행 (중복) | ⚠️ 중복 → 멱등성으로 해결 |

**최악의 경우에도 메시지가 유실되지 않는다.** 중복은 있을 수 있지만 유실은 없다. 그래서 Outbox + 멱등성이 세트.

### 트랜잭션 격리 수준과의 관계

OutboxPublisher는 비즈니스 로직과 **별도 TX**로 SELECT한다.

- **READ COMMITTED (PostgreSQL 기본)**: 커밋된 행만 읽음 → ✅ 안전
- READ UNCOMMITTED: 커밋 안 된 행도 읽음 → ❌ 위험 (유령 메시지)
- REPEATABLE READ, SERIALIZABLE: 커밋된 것만 → ✅ 안전

PostgreSQL 기본(READ COMMITTED)에서 별도 설정 없이 안전하게 동작한다.

팬텀 리드도 무관하다. OutboxPublisher는 SELECT 한 번 → 처리 → DELETE 패턴이라 같은 TX 내 반복 조회가 없다.

---

## 4. 발행 방식: 폴링 vs CDC

| 방식 | 동작 | 지연 | 복잡도 |
|------|------|------|--------|
| **폴링** | 주기적으로 outbox SELECT | 1~5초 | 낮음 |
| **CDC** (Debezium) | DB WAL(변경 로그)을 직접 읽어서 Kafka 발행 | 실시간 | 높음 (인프라 추가) |

### 폴링 방식 (학습 프로젝트에 적합)

```
장점: 구현 간단, 인프라 추가 없음
단점: 폴링 주기만큼 지연 (1초), DB 부하 (SELECT 반복)
```

### CDC 방식 (실무 대규모)

```
장점: 실시간, DB 부하 없음 (WAL 읽기)
단점: Debezium + Kafka Connect 인프라 필요
```

---

## 5. 설계 결정: Outbox 위치

| 선택지 | 장점 | 단점 |
|--------|------|------|
| **각 서비스에 outbox 테이블** | Database per Service 원칙 준수 | 서비스마다 publisher 필요 |
| **core-service에 중앙 outbox** | publisher 1곳 관리 | 다른 서비스가 core-service DB에 의존 → MSA 위반 |

**Database per Service 원칙에 따라 각 서비스에 outbox 테이블을 두는 것이 맞다.**

단, OutboxPublisher(폴링 스케줄러)는 콜드패스인 core-service에서 돌리고, 각 서비스의 outbox 테이블은 해당 서비스 DB에 둘 수도 있다. 이 경우 core-service가 다른 서비스 DB를 읽게 되므로 **MSA 원칙에 맞지 않는다.**

**결론: 각 서비스가 자체 outbox 테이블 + 자체 OutboxPublisher를 가진다.**

```
reserve-service:
  DB: seat + outbox 테이블
  OutboxPublisher: @Scheduled로 outbox 폴링 → Kafka 발행

payment-service:
  DB: payment + outbox 테이블
  OutboxPublisher: 동일 패턴

core-service:
  DB: event + user + outbox 테이블
  OutboxPublisher: 동일 패턴
```

---

## 6. 현재 프로젝트 적용 대상

현재 `kafkaTemplate.send()`를 직접 호출하는 모든 곳이 Outbox 전환 대상:

### reserve-service

| 클래스 | 발행 토픽 | 이벤트 |
|--------|----------|--------|
| QueueConsumer | seat.events | SeatHeld |
| ReservationService | reserve.queue | EnqueueMessage |
| ReservationService | seat.events | SeatReleased |
| PaymentInitiator | payment.events | PaymentRequested |
| PaymentEventConsumer | seat.events | SeatReleased (보상) |
| TickConsumer | seat.events | HoldExpired |

### payment-service

| 클래스 | 발행 토픽 | 이벤트 |
|--------|----------|--------|
| PaymentProcessingService | payment.events | PaymentSucceeded/Failed |
| PaymentTerminationService | payment.events | PaymentExpired/Cancelled |

### core-service

| 클래스 | 발행 토픽 | 이벤트 |
|--------|----------|--------|
| EventLifecycleService | event.lifecycle | EventOpenedRequest/ClosedRequest |
| SeatSyncScheduler | system.ticks | EventLifecycleTick |
| HoldExpiryScheduler | system.ticks | HoldExpiryTick |

---

## 7. Outbox + 멱등성 = 세트

Outbox는 **"유실 없음"**을 보장한다. 하지만 중복 발행 가능성이 있다.

```
Publisher: Kafka 발행 성공 → DELETE 전에 죽음
→ 복구 후 같은 메시지 재발행 → consumer가 2번 처리
```

이걸 consumer 쪽에서 **멱등성**으로 방어한다:

```kotlin
fun onPaymentSucceeded(event: PaymentSucceeded) {
    val seat = seatRepository.findById(event.seatId) ?: return
    if (seat.status == RESERVED) return  // 이미 처리됨 (멱등)
    
    seat.status = RESERVED
    seatRepository.save(seat)
}
```

**Outbox = at-least-once delivery 보장**
**멱등성 = 중복 수신해도 결과가 같음 보장**
**둘의 조합 = effectively-once**

---

## 참고 자료

- [Microservices Patterns (Chris Richardson)](https://microservices.io/patterns/data/transactional-outbox.html)
- [Debezium Outbox Event Router](https://debezium.io/documentation/reference/transformations/outbox-event-router.html)
