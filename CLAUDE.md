# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MSA 실습용 대기열(Queue) 시스템 백엔드. Kotlin + Spring Boot WebFlux 기반 리액티브 서비스.
1인 실습 프로젝트로, 역할 분리는 프로세스 학습 목적. 상세 역할 정의는 AGENTS.md 참조.

## Build & Run

```bash
./gradlew build        # 빌드 (테스트 포함)
./gradlew bootRun      # 애플리케이션 실행 (8080)
./gradlew test         # 전체 테스트
./gradlew test --tests "*.ClassName.methodName"  # 단일 테스트
./gradlew clean build  # 클린 빌드
```

## Tech Stack

- **Kotlin 2.2.21** / **Java 21** / **Spring Boot 4.0.5**
- **WebFlux** (리액티브, non-blocking) + **Kotlin Coroutines**
- WebClient (서비스 간 HTTP 통신), Jackson Kotlin
- JUnit 5 + kotlinx-coroutines-test

## Architecture

Phase 단계별 진행. 현재 Phase 1 (단일 Docker 컨테이너).

- **Phase 1**: queue-service 단독 (현재)
- **Phase 2**: API Gateway + Queue + User + Redis (Docker Compose)
- **Phase 3**: Kubernetes 전환

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

### 브랜치

`main` ← `staging` ← `dev` ← `feature/{issue}-{name}`, `fix/`, `hotfix/`

## Critical Rules

- **DB 스키마 변경은 반드시 사용자 승인 필요.** 마이그레이션, Entity, DDL 등 DB에 영향을 주는 변경은 먼저 변경안을 제시하고 승인받은 후 구현.
- **의존성(build.gradle.kts) 추가/변경 시 사용자 승인 필요.**
- 서비스 간 직접 DB 접근 금지 (Database per Service 원칙).
- Kotlin nullable 최소화, early return 패턴 사용.
- Coroutine 사용 시 structured concurrency 준수.
- Compiler flags: `-Xjsr305=strict` (null-safety 엄격 모드 적용됨).

## Documentation Structure

프로젝트 원칙/규칙은 CONSTITUTION.md, 실제 구조는 ARCHITECTURE.md에서 관리.
서비스별 상세 명세는 `docs/service-specs/`, ADR은 `docs/design-docs/`에서 관리.
`docs/generated/` 내 문서 중 DB 스키마는 승인 필수, API 스펙은 자동 생성.
