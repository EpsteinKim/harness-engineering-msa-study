# Event-Driven 전환: Kafka Queue + Payment Saga + HOLD 만료 + core-service 분리

- **시작일**: 2026-04-16
- **완료일**: 2026-04-16
- **담당**: operator + claude
- **상태**: Phase A/B/C/D/E 모두 완료. Docker Compose E2E 검증 대기

## 배경

Phase 3(K8s) 이관을 앞두고 `DynamicScheduler`의 pod별 in-memory 스케줄러가 multi-pod 환경에서 큐 처리를 중복 실행시킨다. 동시에 "대용량 트래픽 실습" 목표로 pod 수평 확장이 의미 있는 구조가 필요하다. 여기에 더해 설계 미비 세 가지를 함께 정리한다:

- HOLD 상태에서 Payment 엔티티 미생성 → `paymentStatus` 항상 null (이전에 `seatStatus` 필드로 우회, 근본 해결 아님)
- HOLD 만료 미구현(Known Gap) → 결제 안 한 유저의 좌석이 영구 잠김
- Control plane(스케줄러) 책임이 reserve-service 핫패스와 섞여 있음. user-service 검증은 enqueue마다 호출돼 스케일 민감

## 방향

- 상태 전이를 Kafka 이벤트로 구동. HOLD 순간 Payment(PENDING) 생성. 이벤트 구독으로 HOLD 만료 시 Payment 까지 일관 처리 (Phase A~D)
- 신규 `core-service` 분리 — control plane + 저트래픽 도메인(user) 흡수. user 정보는 Redis 캐싱으로 핫패스 제거 (Phase E)
- 조회(payment.listByUser 등) API는 HTTP 유지

## 결정 사항

| 항목 | 결정 |
|------|------|
| 메시지 브로커 | Kafka KRaft (Zookeeper 없음) |
| 인프라 도입 시점 | Docker Compose에 지금 추가 |
| 파티션 키 (enqueue) | `"${eventId}:${userId % K}"` (K=10) |
| API → Kafka 범위 | 상태 전이만. 검증·조회는 HTTP 유지 |
| HOLD 시 Payment 생성 | Kafka `SeatHeld` → payment-service가 consume해 생성 |
| HOLD 만료 | 본 계획 포함. Payment `EXPIRED` 전이까지 |
| `/pay` 시맨틱 | 202 Accepted + 프론트 폴링 |
| `DynamicScheduler` | 완전 제거 (완료) |
| 직렬화 | JSON (Jackson) |
| spring-kafka 버전 | 4.0.4 (Spring Boot 4.0.5 BOM 관리) ✅ 호환 확인 |
| 신규 core-service | replicas=1 singleton. EventScheduler + HoldExpiryScheduler + user 도메인 흡수 |
| user 정보 Redis 캐싱 | write-through (core-service 쓰기 시), reserve-service는 읽기만 |
| user-service | core-service로 흡수, 독립 모듈 제거 |
| 스케줄러 tick 전달 | `system.ticks` Kafka 토픽 파티션 1 (중복 실행 방지) |

## 토픽 설계

### `reserve.queue` (enqueue 대기열, 내부)
- Producer: reserve-service `ReservationService.enqueue`
- Consumer: reserve-service `QueueConsumer`
- 파티션 10, key: `eventId:userId%10`
- 메시지: `EnqueueMessage(eventId, userId, seatId?, section?, joinedAt)`

### `seat.events` (좌석 도메인 이벤트)
- Producer: reserve-service
- Consumer: reserve-service(self) + payment-service
- 파티션 10, key: `seatId`
- 타입: `SeatHeld`, `SeatReserved`, `SeatReleased(reason)`, `HoldExpired`

### `payment.events` (결제 도메인 이벤트)
- Producer: payment-service + reserve-service(PaymentRequested 발행)
- Consumer: payment-service + reserve-service
- 파티션 10, key: `seatId`
- 타입: `PaymentRequested`, `PaymentSucceeded`, `PaymentFailed`, `PaymentExpired`, `PaymentCancelled`

### `system.ticks` (제어 평면 tick, Phase E)
- Producer: core-service (EventScheduler, HoldExpiryScheduler)
- Consumer: reserve-service (`TickConsumer`)
- 파티션 **1**, key: null
- 타입: `EventLifecycleTick(phase=OPEN|CLOSE|SYNC)`, `HoldExpiryTick`

## 실행 순서

### Phase A — Kafka 인프라 + Queue Consumer (DynamicScheduler 제거)

1. `docker-compose.yml`에 Kafka KRaft single-broker 서비스 추가
2. `reserve-service/build.gradle.kts`에 `spring-kafka` 의존성 추가
3. `application.properties`/`application-docker.properties`에 Kafka 설정
4. `KafkaConfig.kt` 신규 — 토픽 정의, ProducerFactory/ConsumerFactory 커스텀(필요 시)
5. `ReservationService.enqueue`: Redis ZADD 유지 + Kafka publish 추가
6. `QueueConsumer.kt` 신규 — `DynamicScheduler.processEvent` 루프 로직 이식
7. `DynamicScheduler.kt`, `SchedulerConfig.kt` 삭제. 호출 지점 6곳 정리
8. 테스트: `QueueConsumerTest` 신규, `ReservationServiceTest` enqueue 보강, `DynamicSchedulerTest` 삭제

**검증**: 기존 enqueue → seat 배정 흐름 동등 동작. `docker compose up -d --scale reserve-service=3` 에서 중복 처리 없음.

### Phase B — SeatHeld → Payment(PENDING) 생성

1. `QueueConsumer` 성공 시 `seat.events`로 `SeatHeld` 발행
2. payment-service `SeatEventConsumer` 신규 — `SeatHeld` 구독 → `Payment(PENDING)` 생성
3. payment-service Payment 테이블: `method` 컬럼 nullable화 (pending 생성 시 method 미정) — **DB 스키마 승인 필요**
4. 멱등성: 동일 seatId PENDING 이미 존재 시 skip (`findBySeatIdAndStatus`)
5. 테스트: `SeatEventConsumerTest`(payment-service)

**검증**: enqueue → SeatHeld 관측 → Payment(PENDING) 생성 확인 → `/my`에서 `paymentStatus="PENDING"` 확인

### Phase C — 비동기 `/pay` (Kafka Saga)

1. `ReservationController.pay`: 202 Accepted + `PaymentRequested` 발행
2. payment-service `PaymentEventConsumer`: `PaymentRequested` → Payment 상태 전이 → `PaymentSucceeded`/`PaymentFailed` 발행
3. reserve-service `PaymentEventConsumer`: `PaymentSucceeded`(seat→RESERVED), `PaymentFailed`(seat→AVAILABLE + SeatReleased 발행)
4. `PaymentOrchestrator.pay()` 삭제, `PaymentClient.processPayment()` 삭제
5. 프론트 `/pay` 응답 202 수용 정렬 — 별도 프론트 exec-plan
6. 테스트: 두 consumer 단위 테스트, `PaymentOrchestratorTest` 삭제

**검증**: `/pay` → 202 → 폴링으로 최종 `seatStatus=RESERVED, paymentStatus=SUCCEEDED`. 실패 시 좌석 복구.

### Phase D — HOLD 만료 처리

1. `PaymentStatus` enum 확장: `EXPIRED`, `CANCELLED` 추가 — **schema-adjacent 승인 필요**
2. reserve-service `HoldExpiryScheduler`: `@Scheduled(fixedDelay=10s)` — 만료 seat 조회 후 `HoldExpired` 발행
3. reserve-service `SeatEventConsumer`(self): `HoldExpired` 구독 → seat AVAILABLE, count 조정
4. payment-service `SeatEventConsumer`: `HoldExpired` 구독 → Payment EXPIRED
5. 사용자 취소 시 `SeatReleased(reason=CANCELLED)` 발행 → payment: Payment CANCELLED
6. multi-pod 중복 실행: 이벤트 발행 멱등으로 수용. ShedLock은 후속 작업
7. 테스트: `HoldExpirySchedulerTest`, consumer 만료 처리

**검증**: TTL 경과 seat AVAILABLE 복구 + Payment EXPIRED 확인

### Phase E — core-service 분리 + user 캐싱 + user-service 흡수

**E-1. core-service 모듈 생성**
- `settings.gradle.kts`에 `include("core-service")` 추가
- `core-service/build.gradle.kts`: spring-boot-starter-web, data-jpa, data-redis, spring-kafka, springdoc
- `CoreServiceApplication.kt` (`@EnableScheduling`), application.properties/application-docker.properties
- `docker-compose.yml`에 `core-service` 서비스 추가

**E-2. EventScheduler 이관 + tick 패턴**
- `reserve-service/scheduler/EventScheduler.kt` → core-service로 이동. 직접 호출 대신 Kafka `system.ticks` 발행
- reserve-service에 신규 `TickConsumer`: `system.ticks` 구독 → tick 종류별로 `EventLifecycleService` 메서드 디스패치
- `EventLifecycleService`는 reserve-service에 그대로 유지 (Event 엔티티 소유권 = DB per Service)
- `ApplicationReadyEvent` 기반 warmup은 reserve-service 자체에 유지 (pod 기동 시 Redis miss 대비)

**E-3. HoldExpiryScheduler**
- core-service에 배치 (Phase D가 먼저면 이관, 아니면 처음부터 여기에)
- `@Scheduled(fixedDelay=10s)` → `HoldExpiryTick` 발행
- reserve-service `TickConsumer`가 받아서 만료 후보 조회 → `HoldExpired` 이벤트 발행

**E-4. user-service → core-service 흡수**
- user-service `controller/`, `service/`, `repository/`, `entity/`, `dto/` 이동
- `settings.gradle.kts`에서 `include("user-service")` 제거, 디렉토리 삭제
- `docker-compose.yml`에서 user-service 제거
- gateway `application.yml`: `/api/v1/users/**` 라우트를 core-service로 변경. `/api-docs/user` 동일

**E-5. User 캐시 도입**
- User 엔티티에 `@Version` 필드 확인/추가 — **DB 스키마 승인 필요**
- core-service UserService 쓰기 경로: DB commit 후 Redis `user:{userId}` Hash 갱신, DELETE 시 `DEL`
- reserve-service 신규 `UserCacheRepository`: `redis.hasKey("user:$userId")`
- `UserClient.exists` → `UserCacheRepository.exists`로 교체. Cache miss 시 core-service HTTP fallback (하이드레이션)

**E-6. Kafka 설정 분배**
- core-service application.properties에 Kafka producer
- reserve-service consumer에 `system.ticks` 그룹 추가
- `system.ticks` 토픽 정의 (`NewTopic`, 파티션 1)

**E-7. 테스트**
- user-service 테스트 → core-service로 이동
- 신규 `UserCacheRepositoryTest`, `TickConsumerTest`, core-service scheduler 테스트
- E2E: docker compose → user 생성 → Redis 캐시 확인 → enqueue 성공

**검증**:
- `docker compose ps`: core-service 존재, user-service 제거됨
- `GET /api/v1/users/{id}` gateway → core-service 라우팅
- user CRUD 후 `redis-cli HGETALL user:{id}` 확인
- enqueue 로그에서 user-service HTTP 호출 사라짐
- core-service replicas=1, reserve-service scale 3 정상

## 승인 항목

| 항목 | Phase | 상태 |
|------|-------|------|
| Kafka(KRaft) Docker Compose 추가 | A | 완료 |
| spring-kafka 4.0.4 의존성 (reserve, payment) | A/B/C | A 완료. payment는 Phase B에서 |
| Payment.method nullable화 | B | DB 스키마 승인 필요 |
| PaymentStatus enum EXPIRED/CANCELLED 추가 | D | schema-adjacent 승인 필요 |
| core-service 모듈·docker-compose 추가 | E | 인프라/빌드 승인 필요 |
| user-service 제거 | E | 서비스 제거 승인 필요 |
| gateway `/api/v1/users/**` → core-service 라우트 변경 | E | 라우트 승인 필요 |
| User 엔티티 `@Version` 필드 | E | DB 스키마 승인 필요 (없을 경우) |

## 영향 없는 항목

- 조회 API `/my`, `/events`, `/seats`, `/queue`, `/api/v1/users/{id}` 등 외부 경로·응답 스키마 유지
- Gateway 외부 노출 경로 유지 (내부 라우팅만 변경)
- Frontend는 `/pay` 202 수용만 별도 정렬
- payment-service 외부 API 계약 유지

## 검증 방법 (종합)

1. Phase별 단위/통합 테스트 통과
2. Docker Compose 기동 후 E2E:
   - `kafka-topics.sh --list`로 토픽 존재 확인
   - enqueue → consumer lag 관찰
   - 성공 결제 / 실패 결제 / HOLD 만료 / 취소 4 시나리오
3. `docker compose up -d --scale reserve-service=3` 에서 중복 처리 없음 확인
4. 부하 테스트: pod 1/3/5개로 throughput 비교

## 완료 처리

- 모든 Phase 완료 + 검증 통과 시 본 파일을 `completed/`로 이동 + `completed/index.md` 업데이트
- Phase 단위로 마감하고 싶으면 본 파일을 유지하며 phase별 상태 표기 갱신

## 후속 작업 (본 계획 밖)

- HoldExpiryScheduler multi-pod 중복 최적화 (ShedLock)
- DLT(Dead Letter Topic) / 재처리 전략
- 프론트 `/pay` 비동기 UX 계획 분리
- Kafka SASL/TLS (운영 범위, 실습 밖)
- Prometheus 기반 PENDING 지연 관측
