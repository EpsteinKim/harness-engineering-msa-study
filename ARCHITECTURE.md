# harness-back Architecture

> 이 문서는 프로젝트의 **실제 구조**를 기술합니다.
> "실제로 어떻게 생겼는가"에 대한 답을 담으며, "왜 이렇게 하는가"는 [CONSTITUTION.md](./CONSTITUTION.md)를 참고합니다.

---

## 1. 서비스 구성

### 현재 (Phase 2 - MSA + Docker Compose)

```
[Client]
   │
   ▼ :8080 (유일한 외부 포트)
[API Gateway]  ← Spring Cloud Gateway (WebFlux)
   ├──► [Reserve Service :8080]  ← 좌석 예약 + 대기열 (MVC + JPA + Redis, 낙관적 락)
   │         ├──► [NeonDB (PostgreSQL)]
   │         ├──► [Redis]  ← 예약 대기열 상태, 좌석 HOLD 상태
   │         └──► [User Service]  ← enqueue 시 userId 존재 검증 (RestClient)
   ├──► [User Service :8080]     ← 사용자 조회/수정 (MVC + JPA)
   │         └──► [NeonDB (PostgreSQL)]
   └──► [Redis]                  ← 대기열 상태, 요청 메타데이터
```

- Gateway만 외부 포트(8080) 노출, 나머지 서비스는 Docker 내부 네트워크로만 통신
- 서비스 간 통신은 Docker DNS(컨테이너명)를 통해 라우팅
- reserve-service → user-service 동기 HTTP 호출 (예약 enqueue 시 userId 검증)

### 목표 (Phase 3 - K8s)

> Phase 2 구조를 Kubernetes 클러스터로 전환. 상세 설계는 해당 시점에 작성.

---

## 2. 서비스 상세

| 서비스 | 상태 | 포트 (로컬/Docker) | 역할 | 기술 |
|--------|------|-------------------|------|------|
| gateway | **운영중** | 8080 / 8080 | 라우팅, 단일 진입점 | Spring Cloud Gateway (WebFlux) |
| reserve-service | **운영중** | 8082 / 8080 | 좌석 예약 + 대기열/스로틀링 (낙관적 락 + SKIP LOCKED) | Spring Boot MVC, JPA, QueryDSL, Redis, Kotlin |
| user-service | **운영중** | 8081 / 8080 | 사용자 조회/수정 (인증 미도입, 학습 우선) | Spring Boot MVC, JPA, Kotlin |
| payment-service | **운영중** | 8083 / 8080 | 결제 처리 (학습용 랜덤 성공/실패) | Spring Boot MVC, JPA, Kotlin |
| redis | **운영중** | 6379 / 6379 | reserve-service 대기열 상태 관리 | Redis 7 Alpine |

---

## 3. 라우팅

### Gateway 라우트 설정

| Path | 대상 서비스 |
|------|------------|
| `/api/v1/reservations/**` | reserve-service |
| `/api/v1/reservations/seats/**` | reserve-service (좌석 맵/구역 잔여석) |
| `/api/v1/reservations/pay` | reserve-service (Saga 오케스트레이터) |
| `/api/v1/users/**` | user-service |
| `/api/v1/payments/**` | payment-service |

### 환경별 라우팅

- **로컬**: `localhost` + 서비스별 포트 (reserve: 8082, user: 8081)
- **Docker**: 컨테이너명 + 포트 8080 (`SPRING_PROFILES_ACTIVE=docker`)

---

## 4. 통신 패턴

### 동기 (현재)

- **프로토콜**: REST (JSON)
- **외부 → 내부**: Gateway를 통한 HTTP 라우팅
- **서비스 간 통신**:
  - reserve-service → user-service (RestClient, enqueue 시 `GET /api/v1/users/{id}`로 userId 검증)
  - reserve-service → payment-service (RestClient, `POST /pay` Saga step, 성공/실패에 따라 Seat 상태 확정 or 보상)
- **응답 표준**: `ApiResponse<T>` (common 모듈에서 공유)
  ```json
  { "status": "success|error", "data": {}, "message": "", "code": "" }
  ```

### 예약 요청 처리 흐름

```
1. Client → POST /api/v1/reservations (userId, eventId, seatId 또는 section)
2. reserve-service → user-service GET /api/v1/users/{id} (RestClient, USER_NOT_FOUND 사전 거부)
3. reserve-service → Redis 캐시 검증 (이벤트 열림, 잔여석, 섹션 소진 여부)
4. SEAT_PICK이면 → Lua tryHoldSeat (HOLD 획득, 실패 시 SEAT_UNAVAILABLE)
5. reserve-service → Redis waiting ZSET 등록 + 메타데이터 Hash 저장
6. DynamicScheduler (이벤트별 독립 스레드, 1초 간격) → waiting에서 N건 peek
7. isInQueue 재확인 → 예약 실행:
   - SECTION_SELECT: reserveBySection() (FOR UPDATE SKIP LOCKED)
   - SEAT_PICK: reserveBySeatId() (낙관적 락) → 성공 시 markSeatReserved
8. 성공 시 → 큐에서 제거, Seat.status = PAYMENT_PENDING, Redis 캐시 갱신 (remainingSeats, 구역별 available, 좌석 상태는 RESERVED 마킹)
9. 실패/낙관적 락 충돌 시 → SEAT_PICK이면 releaseHold, 큐에서 제거
10. 잔여석 0 또는 이벤트 종료 시 → 큐 정리 + 스케줄러 중단 (cancel/결제실패로 잔여석 복구되면 재시작)
11. 유저가 POST /reservations/pay 호출 → PaymentOrchestrator (Saga)
    → payment-service 호출 → 성공: Seat.status=RESERVED / 실패: Seat.status=AVAILABLE + adjustSeatCounts(+1) + markSeatAvailable + scheduler.startProcessing
```

### 비동기 (계획)

- **메시지 브로커**: Redis Pub/Sub 또는 Redis Stream (예정)
- **사용 시나리오**: 대기열 상태 변경 이벤트, 알림 발송

---

## 5. 데이터 아키텍처

### 원칙

- Database per Service (CONSTITUTION.md 참조)
- 서비스 간 데이터 공유는 HTTP API를 통해서만 허용
- DB 스키마 변경 시 **반드시 사용자 승인 필요**

### 저장소 구성

| 서비스 | DB | 용도 |
|--------|-----|------|
| reserve-service | NeonDB (PostgreSQL) + Redis | 행사/좌석(PostgreSQL), 예약 대기열(Redis) |
| user-service | NeonDB (PostgreSQL) | 사용자 정보 |

### DB 스키마 (reserve-service)

```sql
events (id, name, event_time, status, ticket_open_time, ticket_close_time, seat_selection_type, created_at)
seats  (id, event_id FK, seat_number, section, status, reserved_by, reserved_at, version)
```

- `seats.section`: 구역 (A~Z), VARCHAR(1)
- `seats.version`: 낙관적 락 (@Version) — 동시 예약 충돌 방지
- `seats.status`: AVAILABLE | PAYMENT_PENDING | RESERVED
- `seats.price_amount`: 좌석 가격 (BIGINT, KRW). SECTION_SELECT는 같은 섹션 동가, SEAT_PICK은 좌석별 차등 가능
- 인덱스: `idx_seats_event_section(event_id, section)`, `idx_seats_event_status(event_id, status)`

### Redis 구조 (reserve-service)

| Key | Type | 용도 |
|-----|------|------|
| `event:{eventId}` | Hash | 이벤트 캐시 (id, name, status, eventTime, ticketOpenTime, ticketCloseTime, remainingSeats, seatSelectionType, 구역별 available/total/**price**, **SEAT_PICK은 seat_price:{seatId}** 추가, TTL=ticketCloseTime) |
| `events:open` | Sorted Set (score=ticketOpenTime epoch sec) | OPEN 이벤트 ID 인덱스. `/events` 목록 조회 시 ZRANGE로 정렬된 ID 배열 획득 후 각 event hash HGETALL 조립 (DB 히트 없이) |
| `event:{eventId}:seats` | Hash | 좌석별 상태 캐시 (SEAT_PICK만, field=seatId, value=`section:num:STATUS[:userId:heldUntilMs]`) |
| `reservation:waiting:{eventId}` | Sorted Set (score=timestamp) | 이벤트별 대기열 |
| `reservation:metadata:{eventId}:{userId}` | Hash (seatId/section) | 예약 요청 메타데이터 (이벤트별 독립) |

좌석 캐시 값 포맷:
- `"A:A-1:AVAILABLE"`
- `"A:A-1:HELD:42:1762000000000"` (HOLD: userId, 만료 ms epoch)
- `"A:A-1:RESERVED"`

HOLD 상태 전이는 Lua 스크립트로 원자성 보장 (`try_hold_seat.lua`, `release_hold.lua`). 만료 처리는 lazy (조회/HOLD 시도 시점에 판정).

### DB 스키마 (user-service)

```sql
user_account (id BIGSERIAL PK, email VARCHAR(255) UNIQUE, name VARCHAR(100), password VARCHAR(255), created_at TIMESTAMP)
```

- 학습용으로 password는 plain text, 인증 미도입
- 시드 데이터: 한국식 이름 10,000명 (`db/seed.sql`)

### DB 스키마 (payment-service)

```sql
payment (id BIGSERIAL PK, seat_id BIGINT, user_id BIGINT, event_id BIGINT,
         amount BIGINT, method VARCHAR(20), status VARCHAR(20),
         created_at TIMESTAMP, completed_at TIMESTAMP)
```

- `status`: PENDING | SUCCEEDED | FAILED
- 인덱스: `(user_id, status)`, `(seat_id, status)`
- 학습용 설정: `payment.success-rate` (기본 0.7)

### DB 연결

- **드라이버**: JDBC (JPA/Hibernate) — reserve-service, user-service
- **접속 정보**: `.env` 파일로 관리 (gitignore 처리됨)
- **환경변수** (서비스별 분리): `RESERVE_DB_URL/USERNAME/PASSWORD`, `USER_DB_URL/USERNAME/PASSWORD`, `PAYMENT_DB_URL/USERNAME/PASSWORD`

---

## 6. 인프라 토폴로지

### Docker Compose 구성

```
┌──────────────────────────────────────────────┐
│              harness-net (bridge)             │
│                                              │
│  ┌──────────┐  ┌──────────────────┐          │
│  │ gateway  │  │ reserve-service  │          │
│  │  :8080   │──│     :8080        │──┐       │
│  └──────────┘  └──────────────────┘  │       │
│       │        ┌──────────────────┐  │       │
│       │        │  user-service    │  │       │
│       └────────│     :8080        │  │       │
│                └──────────────────┘  │       │
│                ┌──────────────────┐  │       │
│                │     redis        │◄─┘       │
│                │     :6379        │          │
│                └──────────────────┘          │
└──────────────────────────────────────────────┘
         │
    ports: 8080:8080 (gateway), 6379:6379 (redis)
```

### 환경 분리

| 설정 | 로컬 | Docker |
|------|------|--------|
| Profile | `local` | `docker` |
| DB 접속 | `application-local.properties` (gitignore) | docker-compose `.env` 전달 |
| Redis | localhost:6379 (Docker 포트 매핑) | 컨테이너명 `redis:6379` |
| 서비스 주소 | localhost + 서비스별 포트 | 컨테이너명 (Docker DNS) |
| API 문서 | `localhost:{port}/swagger-ui.html` | - |

---

## 7. 프로젝트 디렉토리 구조

```
harness-back/
├── AGENTS.md
├── ARCHITECTURE.md              # 이 문서
├── CONSTITUTION.md
├── CLAUDE.md
├── Dockerfile                   # 공용 (ARG SERVICE_NAME으로 서비스 지정)
├── docker-compose.yml
├── .env                         # DB 접속 정보 (gitignore)
├── .env.sample                  # 환경변수 템플릿
├── build.gradle.kts             # 루트 빌드 설정
├── settings.gradle.kts          # 모듈 등록
├── common/                      # 공유 모듈 (ApiResponse, ServerException, GlobalExceptionHandler)
│   ├── build.gradle.kts
│   └── src/
├── gateway/                     # API Gateway (Spring Cloud Gateway)
│   ├── build.gradle.kts
│   └── src/
├── reserve-service/             # 좌석 예약 + 대기열 서비스 (MVC + JPA + Redis)
│   ├── build.gradle.kts
│   └── src/
├── user-service/                # 사용자 서비스 (MVC + JPA)
│   ├── build.gradle.kts
│   └── src/
└── docs/
    ├── design-docs/
    ├── exec-plans/
    ├── generated/
    ├── learn/
    └── service-specs/
```

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 2026-04-06 | v1.0.0 | 최초 작성 (CONSTITUTION에서 구조 분리) | - |
| 2026-04-07 | v2.0.0 | Phase 2 실제 구현 반영: MVC+JPA 전환, Docker Compose, NeonDB, 라우팅 구성 | - |
| 2026-04-07 | v3.0.0 | reserve-service 분리, queue-service 범용화 (callback 기반), 스로틀링 구현 | - |
| 2026-04-08 | v4.0.0 | queue-service 제거, 대기열/스로틀링 로직을 reserve-service 내부로 흡수 | - |
| 2026-04-08 | v5.0.0 | 좌석 시스템 리디자인: section 기반 자동 배정(SKIP LOCKED), QueryDSL 도입, 공통 예외 처리, springdoc, 로컬 개발 환경 정비 | - |
| 2026-04-09 | v6.0.0 | reserve-service 리팩토링: 이벤트별 동적 스케줄링, Redis 캐시 전략(remainingSeats/구역별/좌석별), seatSelectionType 추가, 동시성 이슈 수정 | - |
| 2026-04-10 | v7.0.0 | 에러 처리 통일(ServerException+ErrorCode), API 통합(enqueue 1개, cancel에 eventId), metadata 키 이벤트별 분리, 스케줄러 안정성 강화, 부하테스트 구축 | - |
| 2026-04-12 | v8.0.0 | SEAT_PICK HOLD(Lua 원자성, lazy expiry), SECTION_FULL 거부, 좌석 맵 조회 API, 스케줄러 ScheduledFuture 관리 + 잔여석 0 시 큐 정리, GlobalExceptionHandler scanBasePackages 수정 | - |
| 2026-04-13 | v9.0.0 | user-service 기본 기능(read/update + 한국식 이름 10,000명 시드), reserve→user RestClient 인터서비스 호출(USER_NOT_FOUND), DB 환경변수 서비스별 분리 | - |
| 2026-04-14 | v10.0.0 | payment-service 신설 + Saga 오케스트레이션 MVP: Seat에 PAYMENT_PENDING 상태 추가, POST /reservations/pay → payment-service 동기 호출, 실패 시 좌석 AVAILABLE 복구 + 스케줄러 재시작 보상 | - |
| 2026-04-15 | v11.0.0 | 좌석/섹션별 가격(seat.price_amount) 도입, EventCache에 가격 캐싱(섹션:price, seat_price:{seatId}), 응답 DTO에 priceAmount 노출, PaymentRequest amount 제거 — 서버 source-of-truth로 위변조 차단 | - |
| 2026-04-15 | v11.1.0 | 프론트 연동용 조회 API 추가: reserve-service에 EventController(`/events`, `/events/{id}`, `/my`), EventQueryService; payment-service에 `GET /payments?userId=X` 목록 API; PaymentClient에 listByUser 메서드 | - |
| 2026-04-15 | v11.2.0 | `/events` / `/events/{id}` Redis-first로 전환: `events:open` ZSET 인덱스 + event hash HGETALL로 DB 히트 없이 조회. cache miss 시 DB fallback. openEvents/closeEvents에서 ZSET 유지. event hash에 id/status/ticketOpenTime 필드 추가 | - |
