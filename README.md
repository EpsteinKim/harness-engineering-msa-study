# harness-engineering-msa-study

MSA(Microservice Architecture) 실습을 위한 대기열(Queue) 시스템 백엔드 프로젝트.

## Tech Stack

- **Kotlin 2.2.21** / **Java 21**
- **Spring Boot 4.0.5** / **Spring MVC** + **Spring Cloud Gateway**
- **Docker Compose** (현재) → **Kubernetes** (예정)
- **Redis** (대기열 상태) / **PostgreSQL - NeonDB** (영속 데이터)

## Architecture

단계적으로 복잡도를 높여가며 MSA를 학습합니다.

| Phase | 구성 | 상태 |
|-------|------|------|
| Phase 1 | Queue Service 단독 (Docker) | 완료 |
| Phase 2 | Gateway + Reserve + User + Redis (Docker Compose) | 진행중 |
| Phase 3 | Kubernetes 전환 | 계획 |

### 서비스 구성 (Phase 2)

| 서비스 | 역할 | 포트 (로컬) |
|--------|------|------------|
| gateway | API 라우팅 (단일 진입점) | 8080 |
| reserve-service | 좌석 예약 + 이벤트별 대기열 + Redis 캐싱 | 8082 |
| user-service | 사용자 관리 | 8081 |

## Progress

### Phase 2 진척사항

**reserve-service** (주요 완료 항목):
- 이벤트별 동적 스케줄링 (DynamicScheduler) — 이벤트 열림/닫힘에 따라 독립 타이머 관리
- Redis 캐시 전략 — remainingSeats, 구역별 잔여석, 좌석별 상태를 Redis에서 실시간 관리
- 예약 모드 분리 — SECTION_SELECT (구역 자동 배정), SEAT_PICK (좌석 직접 선택)
- 동시성 보호 — ZSCORE 재확인, 중복 enqueue 거부, sync 시 큐 일시 중지
- 5분 주기 DB-Redis 보정 (drift 방지)
- 단위 테스트 전면 구축 (Mockito, MockMvc)

**다음 작업 예정**:
- Controller 에러 처리 통일 (ServerException 활용)
- Docker Compose E2E 테스트
- 동시성 부하 테스트

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

## Project Structure

```
├── AGENTS.md          # 에이전트 역할/권한 정의
├── ARCHITECTURE.md    # 서비스 토폴로지, 인프라 구조
├── CONSTITUTION.md    # 원칙, 컨벤션, 배포 규칙
├── CLAUDE.md          # AI 어시스턴트 가이드
├── common/            # 공유 모듈 (ApiResponse)
├── gateway/           # API Gateway
├── reserve-service/   # 좌석 예약 + 대기열 서비스
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
