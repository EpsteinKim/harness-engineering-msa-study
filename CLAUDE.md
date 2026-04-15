# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MSA 실습용 대기열(Queue) 시스템 백엔드. Kotlin + Spring Boot MVC 기반 서비스.
1인 실습 프로젝트로, 역할 분리는 프로세스 학습 목적. 상세 역할 정의는 AGENTS.md 참조.
코드는 초보자도 알아보기 쉽도록 작성할 것

## Build & Run

```bash
./gradlew build        # 빌드 (테스트 포함)
./gradlew bootRun      # 애플리케이션 실행 (8080)
./gradlew test         # 전체 테스트
./gradlew test --tests "*.ClassName.methodName"  # 단일 테스트
./gradlew clean build  # 클린 빌드

# Docker Compose
docker compose up -d --build          # 전체 실행
docker compose up -d --build <서비스>  # 부분 실행
docker compose logs -f <서비스>        # 로그 확인
```

## Tech Stack

- **Kotlin 2.2.21** / **Java 21** / **Spring Boot 4.0.5**
- **Spring MVC** (queue-service, reserve-service, user-service)
- **Spring Cloud Gateway** (WebFlux, gateway만)
- **Redis** (대기열 상태 관리), **PostgreSQL/NeonDB** (영속 데이터)
- RestClient (서비스 간 HTTP 통신), Jackson Kotlin 3.x
- JUnit 5

## Architecture

Phase 단계별 진행. 현재 Phase 2 (Docker Compose MSA).

- **Phase 1**: queue-service 단독 ✅
- **Phase 2**: Gateway + Queue + Reserve + User + Redis (Docker Compose) ← **현재**
- **Phase 3**: Kubernetes 전환

### 서비스 구성

| 서비스 | 역할 | 기술 | 저장소 |
|--------|------|------|--------|
| gateway | API 라우팅 (단일 진입점) | Spring Cloud Gateway (WebFlux) | - |
| reserve-service | 좌석 예약 (낙관적 락) + 대기열/스로틀링 | Spring MVC + JPA + Redis | PostgreSQL (NeonDB) + Redis |
| user-service | 사용자 관리 | Spring MVC + JPA | PostgreSQL (NeonDB) |

상세 서비스 토폴로지와 인프라 구성은 ARCHITECTURE.md 참조.

## Key Conventions

### API

- RESTful, 버전 URI: `/api/v1/{resource}`
- 응답 표준: `{ "status": "success|error", "data": {}, "message": "", "code": "" }`
- 리소스명 복수형, kebab-case

### 커밋 메시지

```
{type}({scope}): {description}
# type: feat, fix, hotfix, refactor, test, chore, docs
```
- **Co-Authored-By 트레일러를 넣지 않는다.**

### 브랜치

`main` ← `staging` ← `dev` ← `feature/{issue}-{name}`, `fix/`, `hotfix/`

## Execution Plans

실행 계획은 `docs/exec-plans/`에서 관리.
- 진행 중인 계획: `docs/exec-plans/active/`에 마크다운 파일로 작성하고, `docs/exec-plans/active/index.md`에 기록할 것.
- **완료된 계획은 반드시 `docs/exec-plans/completed/`로 이동하고, `docs/exec-plans/completed/index.md`에 기록할 것.**

## API 탐색 (Swagger UI)

애플리케이션이 기동 중이면 Gateway의 Swagger UI에서 서비스별 API를 확인할 수 있다.

- 통합 UI: `http://localhost:8080/swagger-ui/index.html`
- 서비스별 직접 조회: `http://localhost:8080/swagger-ui/index.html?urls.primaryName={서비스명}`
  - `reserve-service`: `?urls.primaryName=reserve-service`
  - `user-service`: `?urls.primaryName=user-service`
  - `payment-service`: `?urls.primaryName=payment-service`

API 시그니처, 요청/응답 스키마, 실제 경로(`/api/v1/...`)가 필요할 때는 코드 탐색보다 Swagger UI를 먼저 참조한다. (단, 서버가 기동 중이어야 하며, 계약상 최종 진실은 여전히 소스코드의 컨트롤러 시그니처다.)

## Testing

테스트 작성 지침은 `docs/TEST_GUIDE.md` 참조.

## Critical Rules

- **모든 요청에서 의도를 먼저 파악할 것.** 코드 변경, 버그 리포트, 리팩토링 등 어떤 작업이든 "왜 이렇게 작성했는가"를 먼저 이해할 것. 의도를 파악한 뒤 더 나은 방향이 있다면 적극적으로 제안할 것.

- **DB 스키마 변경은 반드시 사용자 승인 필요.** 마이그레이션, Entity, DDL 등 DB에 영향을 주는 변경은 먼저 변경안을 제시하고 승인받은 후 구현.
- **의존성(build.gradle.kts) 추가/변경 시 사용자 승인 필요.** 추가 전 반드시 현재 Spring Boot 버전(4.x)과의 호환성을 웹 검색으로 확인하고, 호환되는 버전을 특정한 후 제안할 것.
- 서비스 간 직접 DB 접근 금지 (Database per Service 원칙).
- Kotlin nullable 최소화, early return 패턴 사용.
- Compiler flags: `-Xjsr305=strict` (null-safety 엄격 모드 적용됨).
- **사용자 노출 메시지(`ServerException`, `ApiResponse.message`, API 응답 등)는 한글로 작성할 것.** 로그 메시지는 영문 유지(관측/검색 편의). 예외 코드(ErrorCode)는 영문 대문자 스네이크 케이스 그대로.

## Documentation Structure

프로젝트 원칙/규칙은 CONSTITUTION.md, 실제 구조는 ARCHITECTURE.md에서 관리.
서비스별 상세 명세는 `docs/service-specs/`, ADR은 `docs/design-docs/`에서 관리.
`docs/generated/` 내 문서 중 DB 스키마는 승인 필수, API 스펙은 자동 생성.
