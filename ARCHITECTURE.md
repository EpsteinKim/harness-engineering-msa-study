# harness-back Architecture

> 이 문서는 프로젝트의 **실제 구조**를 기술합니다.
> "실제로 어떻게 생겼는가"에 대한 답을 담으며, "왜 이렇게 하는가"는 [CONSTITUTION.md](./CONSTITUTION.md)를 참고합니다.

---

## 1. 서비스 구성

### 현재 (Phase 2 - MSA + Docker Compose + Kafka)

```
[Client]
   │
   ▼ :8988 → :8080 (gateway)
[API Gateway]  ← Spring Cloud Gateway (WebFlux)
   ├──► [Reserve Service]  ← 좌석 예약 + 대기열 (핫패스, HPA 대상)
   ├──► [Core Service]     ← 이벤트 라이프사이클 + 유저 관리 (콜드패스, 싱글톤)
   └──► [Payment Service]  ← 결제 처리 (Kafka Saga)
         
[Kafka]  ← 서비스 간 비동기 통신 (KRaft, 단일 브로커)
   ├─ reserve.queue (10 파티션)     ← 대기열 → 좌석 배정
   ├─ seat.events (10 파티션)       ← 좌석 상태 전이
   ├─ payment.events (10 파티션)    ← 결제 상태 전이
   ├─ event.lifecycle (1 파티션)    ← 이벤트 OPEN/CLOSE 알림
   └─ system.ticks (1 파티션)       ← 스케줄러 tick (SYNC, HOLD_EXPIRY)

[Redis]  ← 이벤트/좌석 캐시, 대기열 상태, HOLD 관리
[NeonDB] ← 서비스별 독립 DB (Database per Service)
```

### 목표 (Phase 3 - K8s)

> Phase 2 구조를 Kubernetes 클러스터로 전환. reserve-service는 HPA, core-service는 replicas=1.

---

## 2. 서비스 상세

| 서비스 | 스케일링 | 포트 (로컬/Docker) | 역할 | 기술 |
|--------|---------|-------------------|------|------|
| gateway | HPA | 8080 / 8080 | 라우팅, 단일 진입점 | Spring Cloud Gateway (WebFlux) |
| reserve-service | HPA | 8082 / 8080 | 좌석 예약 + 대기열 (핫패스) | Spring MVC, JPA, QueryDSL, Redis, Kafka |
| core-service | replicas=1 | 8084 / 8080 | 이벤트 라이프사이클 + 유저 관리 (콜드패스) | Spring MVC, JPA, Redis, Kafka |
| payment-service | HPA | 8083 / 8080 | 결제 처리 (Kafka Saga) | Spring MVC, JPA, Kafka |
| kafka | 1 | 9092 / 9094 | 메시지 브로커 (KRaft) | Apache Kafka 4.1.0 |
| redis | 1 | 6379 / 6379 | 캐시 + 대기열 상태 | Redis 7 Alpine |

---

## 3. 라우팅

### Gateway 라우트

| Path | 대상 서비스 |
|------|------------|
| `/api/v1/reservations/**` | reserve-service |
| `/api/v1/events/**` | core-service |
| `/api/v1/users/**` | core-service |
| `/api/v1/payments/**` | payment-service |

---

## 4. 통신 패턴

### 비동기 (Kafka 이벤트)

| 토픽 | Producer | Consumer | 이벤트 |
|------|----------|----------|--------|
| `reserve.queue` | reserve-service | reserve-service (QueueConsumer) | EnqueueMessage |
| `seat.events` | reserve-service | reserve-service + payment-service | SeatHeld, SeatReserved, SeatReleased, HoldExpired |
| `payment.events` | reserve-service + payment-service | reserve-service + payment-service | PaymentRequested, Succeeded, Failed, Expired, Cancelled |
| `event.lifecycle` | core-service | reserve-service | EventOpenedRequest, EventClosedRequest |
| `system.ticks` | core-service | reserve-service | EventLifecycleTick(SYNC), HoldExpiryTick |

### 동기 (HTTP)

- reserve-service → core-service: 유저 존재 검증 (Redis 캐시 우선, miss 시 HTTP fallback)
- reserve-service → payment-service: 결제 이력 조회 (`GET /payments?userId=X`)

### 응답 표준

```json
{ "status": "success|error", "data": {}, "message": "", "code": "" }
```

---

## 5. 예약 처리 흐름

```
1. Client → POST /api/v1/reservations (userId, eventId, seatId 또는 section)
2. reserve-service → Redis user:{id} 캐시 확인 (miss 시 core-service HTTP fallback)
3. Redis 캐시 검증 (이벤트 존재, 대기열 중복, 잔여석, 섹션 소진)
4. SEAT_PICK이면 → Lua tryHoldSeat (HOLD 획득) + seat_held:{eventId} 에 hold 기록
5. Redis ZSET 대기열 등록 → Kafka reserve.queue 발행

6. QueueConsumer (Kafka consumer group, 파티션별 분배)
   → SECTION_SELECT: FOR UPDATE SKIP LOCKED
   → SEAT_PICK: 낙관적 락 (@Version)
   → 성공: Seat.status=PAYMENT_PENDING, SeatHeld 이벤트 발행

7. payment-service SeatEventConsumer → SeatHeld 수신 → Payment(PENDING) 생성

8. Client → POST /api/v1/reservations/pay (202 Accepted)
   → PaymentRequested 이벤트 발행 (비동기)

9. payment-service PaymentEventConsumer → 결제 처리 (랜덤 성공/실패)
   → PaymentSucceeded: reserve-service → Seat.status=RESERVED
   → PaymentFailed: reserve-service → Seat.status=AVAILABLE + SeatReleased 발행

10. HOLD 만료: core-service HoldExpiryScheduler → HoldExpiryTick
    → reserve-service TickConsumer → HoldExpired 발행
    → 좌석 AVAILABLE 복구 + Payment EXPIRED
```

---

## 6. 데이터 아키텍처

### 원칙

- Database per Service
- 서비스 간 참조는 ID로만 (FK 금지)

### 저장소 구성

| 서비스 | DB 테이블 | Redis 용도 |
|--------|----------|-----------|
| reserve-service | seat | 이벤트/좌석 캐시, 대기열, HOLD |
| core-service | event, user_account | 유저 캐시 (write-through) |
| payment-service | payment | - |

### DB 스키마

**reserve-service: seat**
```sql
seat (id, event_id BIGINT, seat_number, section, status, user_id, reserved_at, price_amount, version)
-- event_id는 FK가 아닌 ID 참조 (core-service DB에 event 테이블 존재)
-- status: AVAILABLE | PAYMENT_PENDING | RESERVED
```

**core-service: event**
```sql
event (id, name, event_time, status, ticket_open_time, ticket_close_time, seat_selection_type, created_at)
-- status: OPEN | CLOSED | DELETED
```

**core-service: user_account**
```sql
user_account (id, email, name, password, created_at, version)
```

**payment-service: payment**
```sql
payment (id, seat_id, user_id, event_id, amount, method, status, created_at, completed_at)
-- status: PENDING | SUCCEEDED | FAILED | EXPIRED | CANCELLED
```

### Redis 구조

| Key | Type | 관리 주체 | 용도 |
|-----|------|----------|------|
| `event:{eventId}` | Hash | core-service(메타데이터) + reserve-service(좌석 카운트) | 이벤트 캐시 |
| `events:open` | Sorted Set | core-service | OPEN 이벤트 인덱스 |
| `event:{eventId}:seats` | Hash | reserve-service | 좌석별 상태 (SEAT_PICK만) |
| `reservation:waiting:{eventId}` | Sorted Set | reserve-service | 대기열 |
| `seat_held:{eventId}` | Hash | reserve-service | SEAT_PICK HOLD 기록 (userId → seatId) |
| `user:{userId}` | Hash | core-service | 유저 캐시 (write-through) |

---

## 7. 스케줄러 (core-service, 싱글톤)

| 스케줄러 | 주기 | 동작 |
|----------|------|------|
| EventScheduler | 매시 정각 | 이벤트 OPEN/CLOSE 직접 실행 + EventOpenedRequest/EventClosedRequest 발행 |
| SeatSyncScheduler | 5분 | system.ticks에 SYNC tick 발행 → reserve-service가 좌석 카운트 보정 |
| HoldExpiryScheduler | 10초 | system.ticks에 HoldExpiryTick 발행 → reserve-service가 만료 HOLD 처리 |

---

## 8. 인프라 토폴로지

### Docker Compose 구성

```
┌──────────────────────────────────────────────────────┐
│                  harness-net (bridge)                 │
│                                                      │
│  ┌──────────┐  ┌─────────────────┐  ┌─────────────┐ │
│  │ gateway  │  │ reserve-service │  │core-service │ │
│  │  :8080   │  │     :8080       │  │   :8080     │ │
│  └──────────┘  └─────────────────┘  └─────────────┘ │
│       │        ┌─────────────────┐                   │
│       │        │payment-service  │                   │
│       └────────│    :8080        │                   │
│                └─────────────────┘                   │
│  ┌──────────┐  ┌─────────────────┐                   │
│  │  redis   │  │     kafka       │                   │
│  │  :6379   │  │  :9092/:9094    │                   │
│  └──────────┘  └─────────────────┘                   │
└──────────────────────────────────────────────────────┘
    ports: 8988→8080 (gateway), 6379 (redis), 9092 (kafka)
```

---

## 9. 프로젝트 디렉토리 구조

```
harness-back/
├── common/            # 공유 라이브러리 (ApiResponse, ServerException, Kafka 이벤트 DTO, 캐시 키)
├── gateway/           # API Gateway
├── reserve-service/   # 좌석 예약 + 대기열 (핫패스)
├── core-service/      # 이벤트 라이프사이클 + 유저 관리 (콜드패스)
├── payment-service/   # 결제 처리
└── docs/              # 문서 (설계, 명세, 학습)
```

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|------|------|-----------|
| 2026-04-06 | v1.0.0 | 최초 작성 |
| 2026-04-17 | v12.0.0 | Event-Driven 전환 (Kafka + Saga), core-service 분리 (user-service 흡수 + Event 도메인 이관), 구조 전면 재작성 |
