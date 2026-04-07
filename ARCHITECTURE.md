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
   ├──► [Queue Service :8080]    ← 범용 요청 대기열 + 스로틀링 (MVC + Redis)
   │         │
   │         └──► HTTP callback ──► [Reserve Service :8080]
   │                                  └──► [NeonDB (PostgreSQL)]
   ├──► [Reserve Service :8080]  ← 좌석 예약 (MVC + JPA, 낙관적 락)
   ├──► [User Service :8080]     ← 사용자 관리 (MVC + JPA)
   │         └──► [NeonDB (PostgreSQL)]
   └──► [Redis]                  ← 대기열 상태, 요청 메타데이터
```

- Gateway만 외부 포트(8080) 노출, 나머지 서비스는 Docker 내부 네트워크로만 통신
- 서비스 간 통신은 Docker DNS(컨테이너명)를 통해 라우팅
- queue-service → reserve-service 통신은 callback URL 기반 HTTP 호출

### 목표 (Phase 3 - K8s)

> Phase 2 구조를 Kubernetes 클러스터로 전환. 상세 설계는 해당 시점에 작성.

---

## 2. 서비스 상세

| 서비스 | 상태 | 포트 (로컬/Docker) | 역할 | 기술 |
|--------|------|-------------------|------|------|
| gateway | **운영중** | 8080 / 8080 | 라우팅, 단일 진입점 | Spring Cloud Gateway (WebFlux) |
| queue-service | **운영중** | 8080 / 8080 | 범용 요청 대기열, 스로틀링, 콜백 | Spring Boot MVC, Redis, Kotlin |
| reserve-service | **운영중** | 8082 / 8080 | 좌석 예약 (낙관적 락) | Spring Boot MVC, JPA, Kotlin |
| user-service | **개발중** | 8081 / 8080 | 사용자 CRUD | Spring Boot MVC, JPA, Kotlin |
| redis | **운영중** | - / 6379 (내부) | 대기열 상태 관리 | Redis 7 Alpine |

---

## 3. 라우팅

### Gateway 라우트 설정

| Path | 대상 서비스 |
|------|------------|
| `/api/v1/queues/**` | queue-service |
| `/api/v1/reservations/**` | reserve-service |
| `/api/v1/users/**` | user-service |

### 환경별 라우팅

- **로컬**: `localhost` + 서비스별 포트 (queue: 8080, reserve: 8082, user: 8081)
- **Docker**: 컨테이너명 + 포트 8080 (`SPRING_PROFILES_ACTIVE=docker`)

---

## 4. 통신 패턴

### 동기 (현재)

- **프로토콜**: REST (JSON)
- **외부 → 내부**: Gateway를 통한 HTTP 라우팅
- **내부 → 내부**: queue-service → reserve-service (RestClient, callback URL 기반)
- **응답 표준**: `ApiResponse<T>` (common 모듈에서 공유)
  ```json
  { "status": "success|error", "data": {}, "message": "", "code": "" }
  ```

### 요청 처리 흐름

```
1. Client → POST /api/v1/queues/enqueue (userId, callbackUrl, payload)
2. queue-service → Redis waiting-queue에 등록 + 메타데이터 Hash 저장
3. 스케줄러 (1초 간격) → waiting-queue에서 N건 dequeue → processing-queue로 이동
4. 스케줄러 → callbackUrl로 payload HTTP POST (예: reserve-service)
5. 성공 시 complete (processing-queue + Hash 삭제)
6. 실패 시 fail (processing-queue + Hash 삭제, 재등록 안 함)
7. 10분 초과 시 → waiting-queue 맨 뒤로 재등록
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
| queue-service | Redis | 대기열 상태 (Sorted Set), 요청 메타데이터 (Hash) |
| reserve-service | NeonDB (PostgreSQL) | 행사(events), 좌석(seats) |
| user-service | NeonDB (PostgreSQL) | 사용자 정보 |

### DB 스키마 (reserve-service)

```sql
events (id, name, event_time, created_at)
seats  (id, event_id FK, seat_number, status, reserved_by, reserved_at, version)
```

- `seats.version`: 낙관적 락 (@Version) — 동시 예약 충돌 방지
- `seats.status`: AVAILABLE | RESERVED

### Redis 구조 (queue-service)

| Key | Type | 용도 |
|-----|------|------|
| `waiting-queue` | Sorted Set (score=timestamp) | 대기 중 요청 |
| `processing-queue` | Sorted Set (score=timestamp) | 처리 중 요청 |
| `queue-request:{userId}` | Hash (callbackUrl, payload) | 요청 메타데이터 |

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
│  │ gateway  │  │  queue-service   │          │
│  │  :8080   │──│     :8080        │──┐       │
│  └──────────┘  └──────────────────┘  │       │
│       │        ┌──────────────────┐  │       │
│       │        │ reserve-service  │◄─┘       │
│       ├────────│     :8080        │          │
│       │        └──────────────────┘          │
│       │        ┌──────────────────┐          │
│       │        │  user-service    │          │
│       └────────│     :8080        │          │
│                └──────────────────┘          │
│                ┌──────────────────┐          │
│                │     redis        │          │
│                │     :6379        │          │
│                └──────────────────┘          │
└──────────────────────────────────────────────┘
         │
    ports: 8080:8080 (gateway만 외부 노출)
```

### 환경 분리

| 설정 | 로컬 | Docker |
|------|------|--------|
| Profile | default | `docker` |
| DB 접속 | `.env` 환경변수 | docker-compose `.env` 전달 |
| 서비스 주소 | localhost + 서비스별 포트 | 컨테이너명 (Docker DNS) |

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
├── common/                      # 공유 모듈 (ApiResponse 등)
│   ├── build.gradle.kts
│   └── src/
├── gateway/                     # API Gateway (Spring Cloud Gateway)
│   ├── build.gradle.kts
│   └── src/
├── queue-service/               # 범용 요청 대기열 (MVC + Redis)
│   ├── build.gradle.kts
│   └── src/
├── reserve-service/             # 좌석 예약 서비스 (MVC + JPA)
│   ├── build.gradle.kts
│   └── src/
├── user-service/                # 사용자 서비스 (MVC + JPA)
│   ├── build.gradle.kts
│   └── src/
└── docs/
    ├── design-docs/
    ├── generated/
    ├── learn/
    ├── service-specs/
    └── references/
```

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 2026-04-06 | v1.0.0 | 최초 작성 (CONSTITUTION에서 구조 분리) | - |
| 2026-04-07 | v2.0.0 | Phase 2 실제 구현 반영: MVC+JPA 전환, Docker Compose, NeonDB, 라우팅 구성 | - |
| 2026-04-07 | v3.0.0 | reserve-service 분리, queue-service 범용화 (callback 기반), 스로틀링 구현 | - |
