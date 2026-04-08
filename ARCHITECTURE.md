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
   │         └──► [Redis]  ← 예약 대기열 상태
   ├──► [User Service :8080]     ← 사용자 관리 (MVC + JPA)
   │         └──► [NeonDB (PostgreSQL)]
   └──► [Redis]                  ← 대기열 상태, 요청 메타데이터
```

- Gateway만 외부 포트(8080) 노출, 나머지 서비스는 Docker 내부 네트워크로만 통신
- 서비스 간 통신은 Docker DNS(컨테이너명)를 통해 라우팅
- 대기열 로직은 reserve-service 내부에서 Redis로 관리 (서비스 간 HTTP 호출 없음)

### 목표 (Phase 3 - K8s)

> Phase 2 구조를 Kubernetes 클러스터로 전환. 상세 설계는 해당 시점에 작성.

---

## 2. 서비스 상세

| 서비스 | 상태 | 포트 (로컬/Docker) | 역할 | 기술 |
|--------|------|-------------------|------|------|
| gateway | **운영중** | 8080 / 8080 | 라우팅, 단일 진입점 | Spring Cloud Gateway (WebFlux) |
| reserve-service | **운영중** | 8082 / 8080 | 좌석 예약 + 대기열/스로틀링 (낙관적 락 + SKIP LOCKED) | Spring Boot MVC, JPA, QueryDSL, Redis, Kotlin |
| user-service | **개발중** | 8081 / 8080 | 사용자 CRUD | Spring Boot MVC, JPA, Kotlin |
| redis | **운영중** | 6379 / 6379 | reserve-service 대기열 상태 관리 | Redis 7 Alpine |

---

## 3. 라우팅

### Gateway 라우트 설정

| Path | 대상 서비스 |
|------|------------|
| `/api/v1/reservations/**` | reserve-service |
| `/api/v1/users/**` | user-service |

### 환경별 라우팅

- **로컬**: `localhost` + 서비스별 포트 (reserve: 8082, user: 8081)
- **Docker**: 컨테이너명 + 포트 8080 (`SPRING_PROFILES_ACTIVE=docker`)

---

## 4. 통신 패턴

### 동기 (현재)

- **프로토콜**: REST (JSON)
- **외부 → 내부**: Gateway를 통한 HTTP 라우팅
- **서비스 간 직접 통신 없음**: 각 서비스가 독립적으로 동작
- **응답 표준**: `ApiResponse<T>` (common 모듈에서 공유)
  ```json
  { "status": "success|error", "data": {}, "message": "", "code": "" }
  ```

### 예약 요청 처리 흐름

```
1. Client → POST /api/v1/reservations/section (userId, eventId, section)
2. reserve-service → Redis waiting ZSET에 등록 + 메타데이터 Hash 저장 (eventId, section)
3. 스케줄러 (1초 간격) → waiting에서 N건 dequeue → processing으로 이동
4. 스케줄러 → section 기반: reserveSeatBySection() (FOR UPDATE SKIP LOCKED)
                seatId 기반: reserveSeat() (낙관적 락, 하위 호환)
5. 성공 시 complete (processing + Hash 삭제)
6. 실패 시 fail (processing + Hash 삭제, 재등록 안 함)
7. 10분 초과 시 → waiting 맨 뒤로 재등록
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
events (id, name, event_time, created_at)
seats  (id, event_id FK, seat_number, section, status, reserved_by, reserved_at, version)
```

- `seats.section`: 구역 (A~Z), VARCHAR(1)
- `seats.version`: 낙관적 락 (@Version) — 동시 예약 충돌 방지
- `seats.status`: AVAILABLE | RESERVED
- 인덱스: `idx_seats_event_section(event_id, section)`, `idx_seats_event_status(event_id, status)`

### Redis 구조 (reserve-service)

| Key | Type | 용도 |
|-----|------|------|
| `reservation:waiting` | Sorted Set (score=timestamp) | 대기 중 예약 요청 |
| `reservation:processing` | Sorted Set (score=timestamp) | 처리 중 예약 요청 |
| `reservation:request:{userId}` | Hash (eventId, seatId/section) | 예약 요청 메타데이터 |

### DB 연결

- **드라이버**: JDBC (JPA/Hibernate) — reserve-service, user-service
- **접속 정보**: `.env` 파일로 관리 (gitignore 처리됨)
- **환경변수**: `NEONDB_URL`, `NEONDB_USERNAME`, `NEONDB_PASSWORD`

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
