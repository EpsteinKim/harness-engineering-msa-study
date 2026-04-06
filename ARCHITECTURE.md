# harness-back Architecture

> 이 문서는 프로젝트의 **실제 구조**를 기술합니다.
> "실제로 어떻게 생겼는가"에 대한 답을 담으며, "왜 이렇게 하는가"는 [CONSTITUTION.md](./CONSTITUTION.md)를 참고합니다.

---

## 1. 서비스 구성

### 현재 (Phase 1 - Docker)

```
[Client]
   │
   ▼
[Queue Service]  ← 현재 harness-back (Spring Boot WebFlux)
   │
   ▼
[DB - 미정]
```

### 목표 (Phase 2 - MSA + Docker Compose)

```
[Client]
   │
   ▼
[API Gateway]  ← Spring Cloud Gateway
   ├──► [Queue Service]   ← 대기열 핵심 로직
   ├──► [User Service]    ← 사용자 관리/인증
   └──► [Notification Service]  ← 알림 (선택)
          │
   [Redis] ← 세션, 캐시, 대기열 상태 공유
```

### 목표 (Phase 3 - K8s)

> Phase 2 구조를 Kubernetes 클러스터로 전환. 상세 설계는 해당 시점에 작성.

---

## 2. 서비스 상세

| 서비스 | 상태 | 포트 | 역할 | 기술 |
|--------|------|------|------|------|
| queue-service | **개발중** | 8080 | 대기열 등록/조회/관리 | Spring Boot WebFlux, Kotlin Coroutines |
| api-gateway | 계획 | 8000 | 라우팅, 인증 필터, Rate Limiting | Spring Cloud Gateway |
| user-service | 계획 | - | 사용자 CRUD, 인증/인가 | Spring Boot WebFlux |
| notification-service | 미정 | - | 대기열 상태 알림 | 미정 |

---

## 3. 통신 패턴

### 동기 (현재)

- **프로토콜**: REST (JSON)
- **서비스 간**: Gateway를 통한 HTTP 통신
- **내부 통신**: WebClient (WebFlux 기본 클라이언트)

### 비동기 (계획)

- **메시지 브로커**: Redis Pub/Sub 또는 Redis Stream (예정)
- **사용 시나리오**: 대기열 상태 변경 이벤트, 알림 발송

---

## 4. 데이터 아키텍처

### 원칙

- Database per Service (CONSTITUTION.md 참조)
- 서비스 간 데이터 공유는 Redis를 통해서만 허용
- DB 스키마 변경 시 **반드시 사용자 승인 필요**

### 저장소 구성 (계획)

| 서비스 | DB | 용도 |
|--------|-----|------|
| queue-service | 미정 (RDB) | 대기열 메타데이터, 이력 |
| queue-service | Redis | 실시간 대기열 상태, 순번 관리 |
| user-service | 미정 (RDB) | 사용자 정보 |

---

## 5. 인프라 토폴로지

### 현재: Docker 단일 컨테이너

```yaml
# docker-compose.yml (예정)
services:
  queue-service:
    build: .
    ports:
      - "8080:8080"
```

### Phase 2: Docker Compose

```yaml
# docker-compose.yml (예정)
services:
  gateway:
    image: gateway-service
    ports:
      - "8000:8000"
  queue:
    image: queue-service
    ports:
      - "8080:8080"
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
  # user-service, db 등 추가 예정
```

### Phase 3: Kubernetes

> Docker Compose 기반 검증 완료 후 전환. 별도 exec-plan으로 관리.

---

## 6. 프로젝트 디렉토리 구조

```
harness-back/
├── AGENTS.md                    # 에이전트 역할/권한
├── ARCHITECTURE.md              # 이 문서 (구조)
├── CONSTITUTION.md              # 원칙/컨벤션
├── docs/
│   ├── design-docs/             # ADR (아키텍처 결정 기록)
│   ├── exec-plans/              # 실행 계획
│   │   ├── active/
│   │   └── completed/
│   ├── generated/               # 자동 생성 문서
│   ├── service-specs/           # 서비스별 상세 명세
│   └── references/              # 외부 참조 문서
├── src/                         # 소스 코드
├── build.gradle.kts
└── docker-compose.yml           # (예정)
```

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 2026-04-06 | v1.0.0 | 최초 작성 (CONSTITUTION에서 구조 분리) | - |
