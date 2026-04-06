# harness-engineering-msa-study

MSA(Microservice Architecture) 실습을 위한 대기열(Queue) 시스템 백엔드 프로젝트.

## Tech Stack

- **Kotlin 2.2.21** / **Java 21**
- **Spring Boot 4.0.5** / **WebFlux** (Reactive)
- **Docker** (현재) → **Kubernetes** (예정)
- **Redis** (예정) / **Spring Cloud Gateway** (예정)

## Architecture

단계적으로 복잡도를 높여가며 MSA를 학습합니다.

| Phase | 구성 | 상태 |
|-------|------|------|
| Phase 1 | Queue Service 단독 (Docker) | 진행중 |
| Phase 2 | Gateway + Queue + User + Redis (Docker Compose) | 계획 |
| Phase 3 | Kubernetes 전환 | 계획 |

## Quick Start

```bash
# 빌드
./gradlew build

# 실행
./gradlew bootRun

# 테스트
./gradlew test
```

## Project Structure

```
├── AGENTS.md          # 에이전트 역할/권한 정의
├── ARCHITECTURE.md    # 서비스 토폴로지, 인프라 구조
├── CONSTITUTION.md    # 원칙, 컨벤션, 배포 규칙
├── CLAUDE.md          # AI 어시스턴트 가이드
├── docs/
│   ├── design-docs/   # ADR (아키텍처 결정 기록)
│   ├── exec-plans/    # 실행 계획
│   ├── generated/     # 자동 생성 문서 (API 스펙, DB 스키마)
│   ├── service-specs/ # 서비스별 상세 명세
│   └── references/    # 외부 참조 문서
└── src/               # 소스 코드
```

## Harness Engineering

이 프로젝트는 **하네스 엔지니어링** 방법론을 적용합니다.

- **CONSTITUTION.md**: 프로젝트의 원칙과 규칙 (왜 이렇게 하는가)
- **ARCHITECTURE.md**: 실제 시스템 구조 (어떻게 생겼는가)
- **AGENTS.md**: 역할 정의와 CI/CD 권한 (누가 무엇을 하는가)

## License

This project is for study purposes.
