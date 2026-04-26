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
   ├─ payment.events (10 파티션)    ← 결제 상태 전이 (Saga 응답)
   ├─ payment.commands (10 파티션)  ← Saga 커맨드 (Orchestrator → payment)
   ├─ event.lifecycle (1 파티션)    ← 이벤트 OPEN/CLOSE 알림
   └─ system.ticks (1 파티션)       ← 스케줄러 tick (SYNC, HOLD_EXPIRY)

[Redis]      ← 이벤트/좌석 캐시, 대기열 상태, HOLD 관리
[PostgreSQL] ← 서비스별 독립 DB (Database per Service, Docker 환경은 로컬 컨테이너 / 로컬 개발은 NeonDB)
```

### Phase 3 - Kubernetes (minikube)

> Phase 2 구조를 Kubernetes 클러스터로 전환. `docker` 프로파일 재사용 (DNS 이름 동일).

```
kubernetes/
├── config/          # ConfigMap (SPRING_PROFILES_ACTIVE=docker) + Secret (NeonDB 크레덴셜)
├── infrastructure/  # Redis Deployment + Kafka StatefulSet (KRaft)
├── apps/            # 4개 서비스 Deployment + Service + HPA
├── ingress/         # harness.local → gateway
└── scripts/         # deploy.sh / teardown.sh
```

| 서비스 | replicas | 스케일링 | strategy | probe |
|--------|----------|---------|----------|-------|
| gateway | 1 | - | RollingUpdate | /actuator/health |
| reserve-service | 2~5 | HPA (CPU 70%) | RollingUpdate | /actuator/health |
| core-service | 1 (고정) | 없음 | Recreate | /actuator/health |
| payment-service | 1~3 | HPA (CPU 70%) | RollingUpdate | /actuator/health |

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
| `reserve.queue` | reserve-service (QueueDispatchScheduler) | reserve-service (QueueConsumer) | EnqueueMessage |
| `payment.commands` | reserve-service (Orchestrator, Outbox) | payment-service (PaymentCommandConsumer) | CreatePaymentCommand, ProcessPaymentCommand |
| `payment.events` | payment-service (Outbox) | reserve-service (SagaResponseConsumer) | PaymentCreated, Succeeded, Failed, Expired, Cancelled |
| `event.lifecycle` | core-service | reserve-service (EventLifecycleConsumer) | EventOpenedRequest, EventClosedRequest |
| `system.ticks` | core-service | reserve-service (TickConsumer) | EventLifecycleTick(SYNC), HoldExpiryTick |

### 동기 (HTTP)

- reserve-service → payment-service: 결제 이력 조회 (`GET /payments?userId=X`)

### 응답 표준

```json
{ "status": "success|error", "data": {}, "message": "", "code": "" }
```

---

## 5. 예약 처리 흐름

```
1. Client → POST /api/v1/reservations (userId, eventId, seatId 또는 section)

2. validateEnqueue (Lua 통합 검증, 1 RTT):
   - Redis: 이벤트 존재 확인, 대기열 중복 확인, seatSelectionType 조회
   - DB: 기존 예약(PAYMENT_PENDING/RESERVED) 존재 여부 확인

3. SEAT_PICK이면 → Lua tryHoldSeat (HOLD 획득) + seat_held:{eventId} 기록

4. enqueue.lua (원자적, 1 RTT):
   - remainingSeats 선차감 + section:X:available 선차감
   - ZSET ZADD NX (대기열 등록)
   - dispatch 데이터 HSET

5. QueueDispatchScheduler (200ms 주기, 분산 락):
   - ZPOPMIN으로 대기열 POP → Kafka reserve.queue 발행

6. QueueConsumer (concurrency=10, 파티션별 분배):
   → SECTION_SELECT: FOR UPDATE SKIP LOCKED
   → SEAT_PICK: 낙관적 락 (@Version)
   → 성공: Seat.status=PAYMENT_PENDING → Saga 시작 (CreatePaymentCommand via Outbox)
   → 실패: remainingSeats +1 보상 복원

7. payment-service PaymentCommandConsumer → CreatePaymentCommand → Payment(PENDING) 생성
   → PaymentCreated ��벤트 → reserve-service SagaOrchestrator.onPaymentCreated

8. Client → POST /api/v1/reservations/pay (202 Accepted)
   → SagaOrchestrator.requestPayment → ProcessPaymentCommand via Outbox

9. payment-service → 결제 처리 (랜덤 성공/실패)
   → PaymentSucceeded: Seat.status=RESERVED, Saga COMPLETED
   → PaymentFailed: 보상 (Seat AVAILABLE 복구 + Redis 카운트 복원, afterCommit 보장)

10. SagaTimeoutScheduler (10초, 분산 락):
    → IN_PROGRESS Saga 타임아웃 검사 → 보상 처리
```

---

## 6. 데이터 아키텍처

### 원칙

- Database per Service
- 서비스 간 참조는 ID로만 (FK 금지)

### 저장소 구성

| 서비스 | DB 테이블 | Redis 용도 |
|--------|----------|-----------|
| reserve-service | seat, reservation_saga, outbox_event | 이벤트/좌석 캐시, 대기열, HOLD, 분산 락 |
| core-service | event, user_account, outbox_event | 유저 캐시 (write-through) |
| payment-service | payment, outbox_event | - |

### DB 스키마

**reserve-service: seat**
```sql
seat (id, event_id BIGINT, seat_number, section, status, user_id, reserved_at, price_amount, version)
-- event_id는 FK가 아닌 ID 참조 (core-service DB에 event 테이블 존재)
-- status: AVAILABLE | PAYMENT_PENDING | RESERVED
```

**reserve-service: reservation_saga**
```sql
reservation_saga (id, event_id, user_id, seat_id, payment_id, step, status, created_at, updated_at)
-- step: SEAT_HELD | PAYMENT_CREATED | PAYMENT_PROCESSING | COMPENSATING | COMPENSATED | COMPLETED
-- status: IN_PROGRESS | COMPLETED | FAILED | EXPIRED | CANCELLED
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
| `event:{eventId}` | Hash | core-service(메타데이터) + reserve-service(좌석 카운트/가격) | 이벤트 캐시 (remainingSeats, section:X:available/total/price, seat_price:N) |
| `events:open` | Sorted Set | core-service | OPEN 이벤트 인덱스 |
| `event:{eventId}:seats` | Hash | reserve-service | 좌석별 상태 (SEAT_PICK만) |
| `reservation:waiting:{eventId}` | Sorted Set | reserve-service | 대기열 (enqueue.lua로 원자적 등록) |
| `reservation:dispatch:{eventId}` | Hash | reserve-service | dispatch 데이터 (userId → seatId/section) |
| `seat_held:{eventId}` | Hash | reserve-service | SEAT_PICK HOLD 기록 (userId → seatId) |
| `user:{userId}` | Hash | core-service | 유저 캐시 (write-through) |
| `lock:queue-dispatch:{eventId}` | String | reserve-service | QueueDispatchScheduler 분산 락 |
| `lock:saga-timeout` | String | reserve-service | SagaTimeoutScheduler 분산 락 |

---

## 7. 스케줄러

### core-service (싱글톤)

| 스케줄러 | 주기 | 동작 |
|----------|------|------|
| EventScheduler | 매시 정각 | 이벤트 OPEN/CLOSE 직접 실행 + EventOpenedRequest/EventClosedRequest 발행 |
| SeatSyncScheduler | 5분 | system.ticks에 SYNC tick 발행 → reserve-service가 좌석 카운트/가격 보정 |
| HoldExpiryScheduler | 10초 | system.ticks에 HoldExpiryTick 발행 → reserve-service가 만료 HOLD 처리 |

### reserve-service (분산 락 기반, 수평 확장 안전)

| 스케줄러 | 주기 | 분산 락 | 동작 |
|----------|------|---------|------|
| QueueDispatchScheduler | 200ms | lock:queue-dispatch:{eventId} (2초 TTL) | ZPOPMIN으로 대기열 POP → Kafka reserve.queue 발행 |
| SagaTimeoutScheduler | 10초 | lock:saga-timeout (120초 TTL) | IN_PROGRESS Saga 만료 검사 → 보상 처리 |
| OutboxPublisher | 1초 | 없음 (서비스 내부) | outbox_event 미발행 건 Kafka 전송 |

---

## 8. 인프라 토폴로지

### Docker Compose 구성

```
┌──────────────────────────────────────────────────────────┐
│                    harness-net (bridge)                   │
│                                                          │
│  ┌──────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │ gateway  │  │ reserve-service │  │  core-service   │ │
│  │  :8080   │  │     :8080       │  │     :8080       │ │
│  └──────────┘  └─────────────────┘  └─────────────────┘ │
│       │        ┌─────────────────┐                       │
│       │        │payment-service  │                       │
│       └────────│    :8080        │                       │
│                └─────────────────┘                       │
│  ┌──────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │ postgres │  │     redis       │  │     kafka       │ │
│  │  :5432   │  │     :6379       │  │  :9092/:9094    │ │
│  └──────────┘  └─────────────────┘  └─────────────────┘ │
└──────────────────────────────────────────────────────────┘
    ports: 8988→8080 (gateway), 5432 (postgres), 6379 (redis), 9092 (kafka)
```

---

## 9. 프로젝트 디렉토리 구조

```
harness-back/
├── common/            # 공유 라이브러리 (ApiResponse, ServerException, Kafka 이벤트 DTO, 캐시 키, Outbox)
├── gateway/           # API Gateway
├── reserve-service/   # 좌석 예약 + 대기열 (핫패스)
├── core-service/      # 이벤트 라이프사이클 + 유저 관리 (콜드패스)
├── payment-service/   # 결제 처리
├── db/                # DB 초기화 (init.sql: 서비스별 독립 DB/유저 생성)
├── monitoring/        # Docker Compose 관측성 설정
│   ├── prometheus.yml            # Prometheus 수집 설정
│   └── grafana/                  # Grafana 대시보드 + 프로비저닝
├── kubernetes/        # K8s 매니페스트
│   ├── config/        # ConfigMap, Secret
│   ├── infrastructure/# Redis, Kafka
│   ├── apps/          # 서비스 Deployment + HPA
│   ├── monitoring/    # Prometheus, Grafana, Redis Exporter
│   ├── ingress/       # Ingress
│   └── scripts/       # deploy.sh, teardown.sh, redeploy.sh
└── docs/              # 문서 (설계, 명세, 학습)
```

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|------|------|-----------|
| 2026-04-06 | v1.0.0 | 최초 작성 |
| 2026-04-17 | v12.0.0 | Event-Driven 전환 (Kafka + Saga), core-service 분리 (user-service 흡수 + Event 도메인 이관), 구조 전면 재작성 |
| 2026-04-20 | v13.0.0 | Phase 3 Kubernetes 전환: 매니페스트 전체 작성, Actuator 추가, docker 프로파일 재사용 |
| 2026-04-25 | v14.0.0 | Saga 오케스트레이션, Outbox 패턴, DLQ, 멱등성, Prometheus + Grafana, 분산 락, 커스텀 메트릭, 패키지 재구조화, ZonedDateTime 통일 |
| 2026-04-26 | v15.0.0 | NeonDB → 로컬 PostgreSQL 컨테이너 전환 (Docker Compose), 서비스별 독립 DB (reserve_db, core_db, payment_db) |
| 2026-04-26 | v16.0.0 | 대기열 선차감(enqueue.lua 원자적), Kafka Consumer concurrency=10, HikariCP/Lettuce 튜닝, validate_enqueue.lua RTT 통합, SagaOrchestrator afterCommit 보상, SagaTimeoutScheduler 락 TTL 보강, seat.events 토픽 제거, 중복 예약 방지(ALREADY_RESERVED), syncAllRemainingSeats 섹션 total/price 동기화, Grafana 대시보드 프로비저닝 |
