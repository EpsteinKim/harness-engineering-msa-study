# harness-engineering-msa-study

MSA(Microservice Architecture) 실습을 위한 대기열(Queue) 시스템 백엔드 프로젝트.

## Tech Stack

- **Kotlin 2.2.21** / **Java 21**
- **Spring Boot 4.0.5** / **Spring MVC** + **Spring Cloud Gateway**
- **Docker Compose** (현재) → **Kubernetes** (예정)
- **Redis** (대기열 상태, 캐시) / **PostgreSQL - NeonDB** (영속 데이터)
- **Locust** (부하 테스트)

## Architecture

단계적으로 복잡도를 높여가며 MSA를 학습합니다.

| Phase | 구성 | 상태 |
|-------|------|------|
| Phase 1 | Queue Service 단독 (Docker) | 완료 |
| Phase 2 | Gateway + Reserve + User + Redis (Docker Compose) | 진행중 |
| Phase 3 | Kubernetes + 서비스 간 통신 | 계획 |
| Phase 4 | 대용량 트래픽 (대기실, Redis 선점, Kafka) | 계획 |

### 서비스 구성 (Phase 2)

| 서비스 | 역할 | 포트 (로컬) |
|--------|------|------------|
| gateway | API 라우팅 (단일 진입점) | 8080 |
| reserve-service | 좌석 예약 + 이벤트별 대기열 + Redis 캐싱 + SEAT_PICK HOLD | 8082 |
| user-service | 사용자 조회/수정 (인증 미도입) | 8081 |

### API

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/reservations` | 예약 요청 (seatId 또는 section) — userId 존재 검증 후 큐 진입 |
| GET | `/api/v1/reservations/seats/{eventId}` | 좌석 맵 조회 (만료된 HELD는 AVAILABLE로 변환) |
| GET | `/api/v1/reservations/seats/{eventId}/sections` | 구역별 잔여석 조회 |
| DELETE | `/api/v1/reservations/queue/{eventId}/{userId}` | 대기열 취소 + HOLD 해제 |
| GET | `/api/v1/users/{id}` | 사용자 조회 |
| PATCH | `/api/v1/users/{id}` | 사용자 정보 수정 (name/email) |

### 예약 처리 흐름

```
Client → POST /reservations (enqueue)
  → user-service GET /users/{id} (RestClient, USER_NOT_FOUND 사전 거부)
  → Redis 캐시 검증 (이벤트 열림, 잔여석, 섹션 소진 여부)
  → SEAT_PICK이면 Lua tryHoldSeat (HOLD 획득, 실패 시 SEAT_UNAVAILABLE)
  → Redis ZSET 대기열 등록 + metadata 저장
  → DynamicScheduler (1초 간격, 20건/cycle, 이벤트별 독립 ScheduledFuture)
    → SEAT_PICK: 낙관적 락 (@Version) → 성공 시 markSeatReserved, 실패/충돌 시 releaseHold
    → SECTION_SELECT: FOR UPDATE SKIP LOCKED
  → 잔여석 0 시 큐 정리 + 스케줄러 중단 (cancel로 잔여석 복구되면 자동 재시작)
```

### 동시성 보호

| 패턴 | 적용 대상 |
|------|----------|
| 낙관적 락 (`@Version`) | SEAT_PICK (직접 좌석 선택) |
| 비관적 락 (`FOR UPDATE SKIP LOCKED`) | SECTION_SELECT (구역 자동 배정) |
| Redis Lua 원자성 | SEAT_PICK HOLD 획득/해제 (`try_hold_seat.lua`, `release_hold.lua`) |
| HOLD lazy expiry | 만료된 HELD는 조회/HOLD 시점에 AVAILABLE로 처리 (sweeper 없음) |
| Redis isInQueue 재확인 | 스케줄러 처리 전 취소 여부 검증 |
| 중복 enqueue 거부 | isInQueue 체크 |
| 잔여석 0 시 스케줄러 중단 | 큐 정리 + ScheduledFuture cancel, cancel로 재개 |
| 5분 주기 DB-Redis 보정 | 캐시 drift 방지 |

### 에러 처리

비즈니스 에러는 `ServerException` + `ErrorCode` 상수로 관리, `GlobalExceptionHandler`에서 HTTP 400으로 응답.

| 코드 | 의미 |
|------|------|
| EVENT_NOT_OPEN | 이벤트 미오픈 |
| NO_REMAINING_SEATS | 잔여석 없음 |
| SECTION_FULL | 해당 섹션 매진 |
| SEAT_NOT_FOUND | 좌석 없음 |
| SEAT_ALREADY_RESERVED | 좌석 이미 예약됨 |
| SEAT_UNAVAILABLE | 좌석 HOLD 실패 (다른 유저가 선점 중 또는 RESERVED) |
| ALREADY_IN_QUEUE | 이미 대기열에 있음 |
| INVALID_REQUEST | 잘못된 요청 파라미터 |
| INVALID_SECTION | 잘못된 구역 (A-Z만 허용) |
| QUEUE_NOT_FOUND | 대기열에 없는 유저 취소 시도 |
| USER_NOT_FOUND | user-service에 등록되지 않은 userId |

## Progress

### Phase 2 완료 항목

- 이벤트별 동적 스케줄링 (DynamicScheduler) — 이벤트 열림/닫힘에 따라 독립 ScheduledFuture 관리
- Redis 캐시 전략 — remainingSeats, 구역별 잔여석, 좌석별 상태를 Redis에서 실시간 관리
- 예약 모드 분리 — SECTION_SELECT (구역 자동 배정), SEAT_PICK (좌석 직접 선택)
- 동시성 보호 — Lua 원자 HOLD, isInQueue 재확인, 중복 enqueue 거부, sync 시 큐 일시 중지
- SEAT_PICK HOLD — Lua 기반 atomic HOLD/release, lazy expiry, 10분 TTL
- 좌석 맵 조회 API — `GET /seats/{eventId}` (만료 HELD는 AVAILABLE로 변환)
- 섹션 소진 거부 — SECTION_FULL ErrorCode
- 에러 처리 통일 — ServerException + ErrorCode 상수, GlobalExceptionHandler (scanBasePackages 적용)
- API 통합 — enqueue 엔드포인트 1개로 통합, cancel에 eventId 포함
- metadata 이벤트별 분리 — `reservation:metadata:{eventId}:{userId}` 키 구조
- 스케줄러 안정성 — userId 변환 보호, ObjectOptimisticLockingFailureException 전용 catch, 잔여석 0 시 큐 정리/중단, cancel 시 재시작
- user-service 기본 기능 — User 조회/수정 + 한국식 이름 10,000명 시드
- 인터서비스 호출 — reserve→user RestClient 검증 (USER_NOT_FOUND)
- 부하 테스트 — Locust 기반, 이벤트별 시나리오 분리, 비즈니스 에러 필터링
- 5분 주기 DB-Redis 보정 (drift 방지)
- 단위 테스트 전면 구축 (Mockito, MockMvc)

### 다음 작업

- Docker Compose E2E 테스트
- 부하테스트 재실행 (HOLD/USER_NOT_FOUND 도입 후 실패율 재측정)

## Quick Start

```bash
# 빌드
./gradlew build

# Docker Compose로 전체 실행
docker compose up -d --build

# 로그 확인
docker compose logs -f reserve-service

# 개별 서비스 실행
./gradlew :reserve-service:bootRun

# 테스트
./gradlew test
```

### 부하 테스트

```bash
cd reserve-service/loadtest
pip install locust
locust                              # 웹 UI (http://localhost:8089)
locust --headless -u 100 -r 10 -t 30s  # headless 모드
```

## Project Structure

```
├── AGENTS.md          # 에이전트 역할/권한 정의
├── ARCHITECTURE.md    # 서비스 토폴로지, 인프라 구조
├── CONSTITUTION.md    # 원칙, 컨벤션, 배포 규칙
├── CLAUDE.md          # AI 어시스턴트 가이드
├── common/            # 공유 모듈 (ApiResponse, ServerException, ErrorCode)
├── gateway/           # API Gateway
├── reserve-service/   # 좌석 예약 + 대기열 서비스
│   └── loadtest/      # Locust 부하 테스트
├── user-service/      # 사용자 서비스
└── docs/              # 문서 (설계, 명세, 학습)
```

## Harness Engineering

이 프로젝트는 **하네스 엔지니어링** 방법론을 적용합니다.

- **CONSTITUTION.md**: 프로젝트의 원칙과 규칙 (왜 이렇게 하는가)
- **ARCHITECTURE.md**: 실제 시스템 구조 (어떻게 생겼는가)
- **AGENTS.md**: 역할 정의와 CI/CD 권한 (누가 무엇을 하는가)

## License

This project is for study purposes.
