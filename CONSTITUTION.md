# harness-back Constitution

> 이 문서는 프로젝트의 **원칙, 컨벤션, 배포 규칙**을 정의합니다.
> "왜 이렇게 하는가"에 대한 답을 담으며, "실제로 어떻게 생겼는가"는 [ARCHITECTURE.md](./ARCHITECTURE.md)를 참고합니다.
> 모든 기여자는 이 문서를 숙지하고 준수해야 합니다.

---

## 1. 프로젝트 개요

| 항목 | 내용 |
|------|------|
| 프로젝트명 | harness-back |
| 아키텍처 | MSA (Microservice Architecture) |
| 주요 기능 | 대기열(Queue) 시스템 |
| 언어/프레임워크 | Kotlin 2.2.21 / Spring Boot 4.0.5 / Spring MVC + Cloud Gateway |
| 런타임 | Java 21 |
| 컨테이너 | Docker Compose (Phase 2) + Kubernetes (Phase 3 병행) |
| 환경 | dev / staging / prod |

---

## 2. 핵심 원칙

- **단일 책임**: 각 서비스는 하나의 도메인만 담당한다.
- **느슨한 결합**: 서비스 간 직접 의존을 최소화하고, 인터페이스를 통해 통신한다.
- **높은 응집도**: 관련 로직은 같은 서비스 내에 위치한다.
- **실패 격리**: 한 서비스의 장애가 전체 시스템에 전파되지 않아야 한다.
- **Database per Service**: 각 서비스는 자체 데이터베이스를 소유하며, 서비스 간 직접 DB 접근은 금지한다. 서비스 간 참조는 ID로만 (FK 금지).
- **Redis-first**: 핫패스 데이터는 Redis를 1차 저장소로 사용하고, DB는 영속 백업 역할로 둔다.
- **대용량 트래픽 기준 설계**: 모든 설계/분석은 대용량 트래픽 관점을 기준으로 한다.
  - 핫패스(높은 동시성) → 수평 확장 가능한 서비스에 배치
  - 콜드패스(낮은 빈도) → 싱글톤 서비스에 배치

---

## 3. 브랜치 전략

```
main          # 프로덕션 배포 브랜치
└── staging   # 스테이징 검증 브랜치
    └── dev   # 개발 통합 브랜치
        └── feature/{issue}-{name}   # 기능 개발
        └── fix/{issue}-{name}       # 버그 수정
        └── hotfix/{issue}-{name}    # 긴급 수정
```

### 브랜치 규칙

- `main` 직접 push 금지. PR + 승인 필수.
- PR은 최소 1명 이상 승인 후 머지.
- 머지 전 CI 파이프라인 통과 필수.

---

## 4. 코딩 컨벤션

### 4.1 커밋 메시지

```
{type}({scope}): {short_description}

예시)
feat(queue): 대기열 등록 API 추가
fix(gateway): 라우팅 누락 수정
chore(ci): 파이프라인 스크립트 수정
```

| 타입 | 설명 |
|------|------|
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `hotfix` | 긴급 버그 수정 |
| `refactor` | 리팩토링 |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드, 설정, CI 관련 |
| `docs` | 문서 수정 |

### 4.2 API 설계 원칙

- RESTful 설계 원칙을 따른다.
- API 버전은 URI에 명시한다. (예: `/api/v1/...`)
- 리소스명 복수형, kebab-case
- 응답 형식 표준:

```json
{
  "status": "success | error",
  "data": {},
  "message": "",
  "code": ""
}
```

### 4.3 Kotlin 컨벤션

- [Kotlin 공식 코딩 컨벤션](https://kotlinlang.org/docs/coding-conventions.html)을 따른다.
- Nullable 타입은 최소화하고, 불가피한 경우 early return 패턴을 사용한다.
- Compiler flags: `-Xjsr305=strict` (null-safety 엄격 모드 적용됨).

### 4.4 메시지/로깅 컨벤션

- **사용자 노출 메시지는 한글**로 작성한다.
- **로그 메시지는 영문** 유지 (관측/검색 편의).
- **예외 코드는 영문 대문자 스네이크 케이스** (예: `ALREADY_RESERVED`, `NO_REMAINING_SEATS`).

### 4.5 설정 관리 컨벤션

- 설정은 가능한 한 **코드에서 명시적으로 정의**한다.
- `application.properties`는 환경별로 달라지는 값(호스트, 포트, 크레덴셜 등)만 둔다.
- 어노테이션에 직접 작성할 수 있으면 별도 상수로 추출하지 않는다.

---

## 5. 배포 원칙

### 5.1 환경별 원칙

| 환경 | 배포 조건 | 승인 |
|------|-----------|------|
| dev | PR 머지 시 자동 배포 | 불필요 |
| staging | dev 검증 후 수동 트리거 | QA 승인 |
| prod | staging 검증 후 수동 트리거 | CEO 최종 승인 |

### 5.2 롤백 원칙

- 배포 후 오류 감지 시 즉시 롤백한다.
- 롤백은 Developer가 담당한다. (AGENTS.md 참고)

---

## 6. 문서 생성 정책

| 대상 | 정책 | 비고 |
|------|------|------|
| DB 스키마 변경 | **사용자 승인 필수** | 승인 없이 자동 생성/변경 금지 |
| API 스펙 | 자동 생성 | 빌드 시 OpenAPI 스펙 자동 갱신 |
| 서비스 의존 관계 | 자동 생성 | CI에서 자동 갱신 |

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 2026-04-06 | v1.0.0 | 최초 작성 (원칙/구조 분리) | - |
| 2026-04-29 | v1.1.0 | CLAUDE.md에서 프로젝트 원칙 이관 (redis-first, 트래픽 설계, 메시지/설정 컨벤션), API 응답 code 필드 추가, Coroutine 제거, Phase 현행화 | - |
