# Saga 오케스트레이션 학습 문서

> 이 프로젝트(harness-back)의 분산 트랜잭션 흐름을 추적하고 보상하기 위한 학습 문서.
> 현재 코레오그래피 방식의 한계와 오케스트레이션 전환 방향을 정리.

---

## 1. 분산 트랜잭션 문제

MSA에서는 하나의 비즈니스 흐름이 **여러 서비스의 DB**에 걸쳐 있다.

```
좌석 예약 흐름:
  reserve-service DB: seat.status = PAYMENT_PENDING
  payment-service DB: payment.status = PENDING
  → 둘 다 성공해야 "예약 완료"
  → 하나만 실패하면 나머지도 되돌려야 함
```

RDBMS의 `@Transactional`은 **단일 DB 내**에서만 동작한다. 서비스 간 트랜잭션은 보장할 수 없다. 이 문제를 해결하는 패턴이 **Saga**.

---

## 2. Saga란?

긴 비즈니스 흐름을 **단계(step)별 로컬 트랜잭션**으로 쪼개고, 실패 시 이전 단계를 **보상 트랜잭션**으로 되돌리는 패턴.

```
정상 흐름:
  T1 (좌석 배정) → T2 (결제 생성) → T3 (결제 처리) → 완료

실패 시:
  T1 → T2 → T3 실패 → C2 (결제 취소) → C1 (좌석 복구)
```

**핵심:** 분산 환경에서 all-or-nothing은 불가능. 대신 **eventually consistent** — 실패하면 보상으로 되돌린다.

---

## 3. 코레오그래피 vs 오케스트레이션

### 코레오그래피 (현재 프로젝트)

각 서비스가 이벤트를 발행하고, 다른 서비스가 **자율적으로 반응**한다.

```
reserve-service: SeatHeld 발행 (사실 전달)
  → payment-service가 듣고 스스로 Payment 생성
    → PaymentSucceeded 발행 (사실 전달)
      → reserve-service가 듣고 스스로 seat RESERVED
```

| 장점 | 단점 |
|------|------|
| 서비스 간 결합도 낮음 | 흐름 파악이 어려움 (이벤트 스파게티) |
| 단순한 흐름에 적합 | 보상 로직이 서비스마다 분산 |
| 중앙 병목 없음 | "지금 어디까지 진행됐는지" 추적 불가 |

### 오케스트레이션

**Orchestrator**가 중앙에서 흐름을 제어하고, 각 서비스에 **커맨드를 지시**한다.

```
Orchestrator: "결제 만들어" (커맨드)
  → payment-service: Payment 생성 → "만들었다" (응답)
Orchestrator: "결제 처리해" (커맨드)
  → payment-service: 처리 → "성공했다" (응답)
Orchestrator: seat RESERVED, saga COMPLETED
```

| 장점 | 단점 |
|------|------|
| 흐름이 한 곳에 명시적 | Orchestrator가 단일 장애점 |
| 보상 로직 집중 | 서비스 간 결합도 증가 |
| 상태 추적 가능 (Saga 테이블) | Orchestrator가 비대해질 수 있음 |

### 판단 기준

```
보상 트랜잭션이 필요한가? → 오케스트레이션
실행 순서가 중요한가?     → 오케스트레이션
단순 이벤트 반응인가?     → 코레오그래피
서비스 3개 이상 참여?     → 오케스트레이션 권장
```

---

## 4. 이벤트 vs 커맨드

Saga 패턴에서 가장 중요한 구분.

| | 이벤트 (Event) | 커맨드 (Command) |
|--|---|---|
| 성격 | "이런 일이 일어났다" (사실) | "이걸 해라" (지시) |
| 발신자 | 발행하고 끝, 누가 듣든 상관없음 | 특정 서비스에 명시적 지시 |
| 결합도 | 낮음 (pub/sub) | 높음 (point-to-point) |
| 예시 | SeatHeld, PaymentSucceeded | CreatePaymentCommand, ProcessPaymentCommand |

```
코레오그래피: "좌석이 잡혔다" (SeatHeld) → 누가 듣든 말든
오케스트레이션: "결제를 만들어라" (CreatePaymentCommand) → payment-service에 직접 지시
```

---

## 5. Saga 테이블 — 흐름 추적의 핵심

Orchestrator가 "이 예약이 어디까지 진행됐는지"를 DB에 기록한다.

```sql
reservation_saga (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    seat_id     BIGINT NOT NULL,
    step        VARCHAR(30) NOT NULL,   -- 현재 단계
    status      VARCHAR(30) NOT NULL,   -- 진행 상태
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
)
```

### Step (단계)

```
SEAT_HELD → PAYMENT_CREATED → PAYMENT_PROCESSING → COMPLETED
                                                  ↘ COMPENSATING → COMPENSATED
```

| Step | 의미 |
|------|------|
| SEAT_HELD | 좌석 배정 완료, 결제 생성 대기 |
| PAYMENT_CREATED | Payment(PENDING) 생성됨, 사용자 결제 대기 |
| PAYMENT_PROCESSING | 결제 처리 중 |
| COMPLETED | 결제 성공, 좌석 확정 |
| COMPENSATING | 보상 실행 중 |
| COMPENSATED | 보상 완료 (좌석 복구) |

### Status (상태)

| Status | 의미 |
|--------|------|
| IN_PROGRESS | 진행 중 |
| COMPLETED | 성공 |
| FAILED | 결제 실패 → 보상 완료 |
| EXPIRED | 타임아웃 → 보상 완료 |
| CANCELLED | 사용자 취소 → 보상 완료 |

### 조회 예시

```sql
-- 사용자 42의 예약 진행 상태
SELECT * FROM reservation_saga WHERE user_id = 42;
-- → step=PAYMENT_PROCESSING, status=IN_PROGRESS → "결제 처리 중"

-- 멈춘 Saga 찾기 (타임아웃 대상)
SELECT * FROM reservation_saga
WHERE status = 'IN_PROGRESS'
  AND step IN ('SEAT_HELD', 'PAYMENT_CREATED')
  AND updated_at < now() - INTERVAL '10 minutes';
```

---

## 6. Orchestrator의 역할

Orchestrator는 **상태 머신(State Machine)**이다. 현재 step과 수신한 응답에 따라 다음 행동을 결정한다.

```
수신: PaymentCreated 응답
  현재 step이 SEAT_HELD? → step = PAYMENT_CREATED
  현재 step이 PAYMENT_CREATED? → 이미 처리됨, 무시 (멱등)
  현재 status가 EXPIRED? → 이미 만료됨, 무시

수신: PaymentSucceeded 응답
  현재 step이 PAYMENT_PROCESSING? → seat RESERVED, status = COMPLETED
  현재 status가 COMPENSATED? → 이미 보상됨, 무시
```

### 보상 트랜잭션

```kotlin
fun compensate(saga: ReservationSaga) {
    // 1. 좌석 복구
    seatService.releaseSeat(saga.eventId, saga.userId)
    eventCache.adjustSeatCounts(saga.eventId, 1, section)
    
    // 2. 결제 취소 커맨드
    outboxService.save("payment.commands", null, CancelPaymentCommand(saga.id, saga.seatId))
    
    // 3. Saga 상태 전이
    saga.step = COMPENSATED
    saga.status = FAILED  // 또는 EXPIRED, CANCELLED
    sagaRepository.save(saga)
}
```

**한 곳에서 전부 처리.** 코레오그래피에서 3곳에 분산되어 있던 보상 로직이 Orchestrator에 집중된다.

---

## 7. 타임아웃 처리

### 코레오그래피 (현재)

```
core-service: HoldExpiryScheduler (10초마다) → HoldExpiryTick 발행
  → reserve-service TickConsumer → 만료된 좌석 찾기 → HoldExpired 발행
    → reserve-service SeatEventConsumer → 좌석 AVAILABLE 복구
    → payment-service SeatEventConsumer → Payment EXPIRED
→ 3개 컴포넌트가 관여, 순서 보장 없음
```

### 오케스트레이션

```
SagaTimeoutScheduler (10초마다):
  SELECT * FROM reservation_saga
  WHERE status = 'IN_PROGRESS'
    AND step IN ('SEAT_HELD', 'PAYMENT_CREATED')
    AND updated_at < now() - INTERVAL '10 minutes';

  → 결과 있으면: orchestrator.compensate(saga)
  → 한 곳에서 보상 실행, Saga 테이블에 EXPIRED 기록
```

---

## 8. 장애 복구

### 코레오그래피에서의 문제

```
payment-service 다운 (5분간):
  SeatHeld 이벤트 → Kafka에 쌓임
  HoldExpiryTick → 좌석 AVAILABLE 복구
  payment-service 복구 → 밀린 SeatHeld 소비 → Payment 생성
  → 이미 복구된 좌석에 대한 Payment → 정합성 불일치 ❌
```

### 오케스트레이션에서의 해결

```
payment-service 다운 (5분간):
  Saga 테이블: step=SEAT_HELD, status=IN_PROGRESS
  SagaTimeoutScheduler: 10분 경과 → compensate() → status=EXPIRED
  payment-service 복구 → CreatePaymentCommand 수신
  → sagaId로 Saga 조회 → status=EXPIRED → 무시 ✅
```

**sagaId가 모든 메시지에 포함**되어 있어서, 서비스 복구 후에도 Saga 상태를 확인하고 이미 만료/보상된 흐름은 무시할 수 있다.

---

## 9. 현재 프로젝트에 적용하면

### 변경되는 것

```
현재 (코레오그래피):
  SeatHeld (이벤트) → payment-service가 자율 반응
  PaymentSucceeded (이벤트) → reserve-service가 자율 반응

오케스트레이션:
  CreatePaymentCommand (커맨드) → payment-service가 실행 → 응답
  ProcessPaymentCommand (커맨드) → payment-service가 실행 → 응답
  → Orchestrator가 응답 보고 다음 단계 결정
```

### 변경되지 않는 것

| 흐름 | 방식 | 이유 |
|------|------|------|
| EventOpened → 캐시 워밍업 | 코레오그래피 유지 | 보상 불필요, 단순 반응 |
| SyncTick → 좌석 카운트 보정 | 코레오그래피 유지 | 자가 치유, 순서 무관 |
| HoldExpiryTick | Saga 타임아웃으로 대체 | Orchestrator가 직접 DB 조회 |

### Outbox와의 관계

Orchestrator가 커맨드를 발행할 때도 **Outbox를 경유**한다:

```kotlin
@Transactional
fun startSaga(eventId: Long, userId: Long, seatId: Long, amount: Long) {
    // 같은 TX에서:
    val saga = sagaRepository.save(ReservationSaga(..., step=SEAT_HELD, status=IN_PROGRESS))
    outboxService.save("payment.commands", seatId.toString(), CreatePaymentCommand(saga.id, ...))
}
// TX 커밋 → Saga 생성 + 커맨드 발행이 원자적
```

---

## 10. Saga 오케스트레이션이 필요한 경우 vs 아닌 경우

| 상황 | 추천 |
|------|------|
| 서비스 2개, 보상 단순 | 코레오그래피 + 멱등성으로 충분 |
| 서비스 3개 이상 참여 | 오케스트레이션 권장 |
| 보상 경로가 복잡 | 오케스트레이션 |
| 흐름 추적/모니터링 필요 | 오케스트레이션 (Saga 테이블) |
| 타임아웃 관리 중요 | 오케스트레이션 |
| 단순 이벤트 전파 | 코레오그래피 |

현재 프로젝트는 reserve ↔ payment 2개 서비스로, 코레오그래피 + 멱등성으로도 운영 가능하다. 오케스트레이션은 **학습 가치**와 **흐름 추적 가시성** 측면에서 도입 의미가 있다.

---

## 11. 실무에서의 Saga 구현체

| 프레임워크 | 특징 |
|-----------|------|
| **Axon Framework** | Event Sourcing + Saga 지원, 러닝 커브 높음 |
| **Eventuate Tram** | Chris Richardson(MSA 패턴 저자), Outbox + Saga 내장 |
| **Spring State Machine** | 상태 머신 기반, Saga에 활용 가능 |
| **직접 구현** | 가장 교육적, 프로젝트 맞춤 설계 가능 |

이 프로젝트에서는 **직접 구현**이 학습에 가장 적합하다. Saga 테이블 + Orchestrator 클래스 + Outbox 연동을 직접 만들면서 패턴을 체화할 수 있다.

---

## 참고 자료

- [Microservices Patterns - Chris Richardson, Chapter 4: Managing transactions with sagas](https://microservices.io/patterns/data/saga.html)
- [Saga Orchestration vs Choreography](https://microservices.io/patterns/data/saga.html)
