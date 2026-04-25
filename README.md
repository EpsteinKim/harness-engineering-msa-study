# harness-engineering-msa-study

MSA(Microservice Architecture) + 대용량 트래픽 실습을 위한 티케팅 대기열 시스템 백엔드 프로젝트.

## Tech Stack

- **Kotlin 2.2.21** / **Java 21**
- **Spring Boot 4.0.5** / **Spring MVC** + **Spring Cloud Gateway**
- **Apache Kafka 4.1.0** (KRaft) — 서비스 간 비동기 통신, Saga 오케스트레이션
- **Kubernetes** (OrbStack) — 컨테이너 오케스트레이션, HPA, Ingress
- **Redis** (캐시, 대기열, HOLD, 분산 락) / **PostgreSQL - NeonDB** (영속 데이터)
- **Prometheus + Grafana** — 메트릭 수집 + 시각화
- **Docker Compose** — 로컬 개발 환경

## Architecture

단계적으로 복잡도를 높여가며 MSA를 학습합니다.

| Phase | 구성 | 상태 |
|-------|------|------|
| Phase 1 | Queue Service 단독 | 완료 |
| Phase 2 | Gateway + Reserve + Core + Payment + Kafka + Redis | 완료 |
| Phase 3 | Kubernetes + Prometheus + Grafana | 완료 |

### 서비스 구성

| 서비스 | 역할 | 스케일링 | 포트 |
|--------|------|---------|------|
| gateway | API 라우팅 | HPA | 8080 |
| reserve-service | 좌석 예약 + 대기열 (핫패스) | HPA (2~10) | 8082 |
| core-service | 이벤트 라이프사이클 + 유저 관리 (콜드패스) | replicas=1 | 8084 |
| payment-service | 결제 처리 (Kafka Saga) | HPA (1~3) | 8083 |

### 예약 처리 흐름 (Saga 오케스트레이션)

```
Client → POST /reservations (enqueue)
  → Redis 캐시 검증 (이벤트/잔여석/중복/활성 Saga)
  → SEAT_PICK이면 Lua HOLD
  → Outbox → Kafka reserve.queue 발행

QueueConsumer (consumer group, 파티션 분배)
  → 좌석 배정 (SKIP LOCKED / 낙관적 락)
  → Saga 시작 → CreatePaymentCommand (Orchestrator)
  → payment-service: Payment(PENDING) 생성 → PaymentCreated 응답

Client → POST /reservations/pay (202 Accepted, sagaId 반환)
  → ProcessPaymentCommand (Orchestrator → payment-service)
  → 성공: Saga COMPLETED, 좌석 RESERVED
  → 실패: Saga 보상 → 좌석 AVAILABLE 복구
  → 타임아웃: SagaTimeoutScheduler (분산 락) → 자동 보상

Client → GET /reservations/saga/{sagaId} (결제 결과 폴링)
```

### 장애 대응 패턴

| 패턴 | 역할 |
|------|------|
| **Outbox** | DB-Kafka 원자적 발행 (메시지 유실 방지) |
| **Saga 오케스트레이션** | 분산 트랜잭션 흐름 추적 + 보상 + 타임아웃 |
| **멱등성** | Saga 상태 체크 + Redis 캐시 (중복 처리 방지) |
| **DLQ** | 처리 불가 메시지 격리 (Dead Letter Queue) |
| **분산 락** | Redis SETNX + Lua (SagaTimeoutScheduler 중복 방지) |

### 동시성 보호

| 패턴 | 적용 대상 |
|------|----------|
| 낙관적 락 (`@Version`) | SEAT_PICK (직접 좌석 선택) |
| 비관적 락 (`FOR UPDATE SKIP LOCKED`) | SECTION_SELECT (구역 자동 배정) |
| Redis Lua 원자성 | SEAT_PICK HOLD 획득/해제 |
| Kafka consumer group | 다중 pod에서 메시지 분배 |

### 관측성

| 도구 | 역할 |
|------|------|
| Prometheus | 메트릭 수집 (JVM, HTTP, Kafka, 커스텀) |
| Grafana | 대시보드 시각화 |
| Micrometer | 애플리케이션 메트릭 노출 (/actuator/prometheus) |
| Redis Exporter | Redis 메트릭 수집 |
| 커스텀 메트릭 | saga.started, saga.completed, saga.failed 등 |

## Quick Start

### Docker Compose

```bash
./gradlew build
docker compose up -d --build
# → http://localhost:8988
```

### Kubernetes (OrbStack)

```bash
./gradlew build
bash kubernetes/scripts/deploy.sh
# → http://localhost:8080/api/v1/events
# Grafana: kubectl port-forward svc/grafana 3000:3000 → http://localhost:3000
# 또는: http://localhost:8080/grafana
```

### 부하 테스트 (Locust)

```bash
cd reserve-service/loadtest
source .venv/bin/activate
locust -f enqueue_burst.py --host http://localhost:8080
# → http://localhost:8089
```

## Project Structure

```
├── common/            # 공유 라이브러리 (이벤트 DTO, Outbox, 캐시 키, 예외 처리)
├── gateway/           # API Gateway
├── reserve-service/   # 좌석 예약 + 대기열 (핫패스)
│   └── loadtest/      # Locust 부하 테스트
├── core-service/      # 이벤트 라이프사이클 + 유저 관리 (콜드패스)
├── payment-service/   # 결제 처리
├── kubernetes/        # K8s 매니페스트
│   ├── apps/          # 서비스 Deployment + HPA
│   ├── config/        # ConfigMap, Secret
│   ├── infrastructure/# Redis, Kafka
│   ├── monitoring/    # Prometheus, Grafana, Redis Exporter
│   ├── ingress/       # Ingress
│   └── scripts/       # deploy.sh, teardown.sh
└── docs/
    ├── learn/         # 학습 문서 (Kafka, Outbox, Saga, DLQ, 멱등성)
    ├── exec-plans/    # 실행 계획
    ├── design-docs/   # 설계 문서
    └── service-specs/ # 서비스 명세
```

## 학습 문서

| 문서 | 내용 |
|------|------|
| [KAFKA_LEARN.md](docs/learn/KAFKA_LEARN.md) | Kafka 핵심 개념, 파티션, consumer group |
| [OUTBOX_LEARN.md](docs/learn/OUTBOX_LEARN.md) | Outbox 패턴, 이중 쓰기 문제, 폴링 vs CDC |
| [SAGA_LEARN.md](docs/learn/SAGA_LEARN.md) | Saga 오케스트레이션 vs 코레오그래피 |
| [IDEMPOTENCY_LEARN.md](docs/learn/IDEMPOTENCY_LEARN.md) | 멱등성 구현 방법, Outbox + 멱등성 조합 |
| [DLQ_LEARN.md](docs/learn/DLQ_LEARN.md) | Dead Letter Queue, 재시도 전략 |

## Harness Engineering

이 프로젝트는 **하네스 엔지니어링** 방법론을 적용합니다.

- **CONSTITUTION.md**: 프로젝트의 원칙과 규칙
- **ARCHITECTURE.md**: 실제 시스템 구조
- **AGENTS.md**: 역할 정의와 권한
- **CLAUDE.md**: AI 어시스턴트 가이드

## License

This project is for study purposes.
