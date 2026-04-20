# harness-engineering-msa-study

MSA(Microservice Architecture) + 대용량 트래픽 실습을 위한 대기열(Queue) 시스템 백엔드 프로젝트.

## Tech Stack

- **Kotlin 2.2.21** / **Java 21**
- **Spring Boot 4.0.5** / **Spring MVC** + **Spring Cloud Gateway**
- **Apache Kafka 4.1.0** (KRaft) — 서비스 간 비동기 통신, Saga 오케스트레이션
- **Docker Compose** (현재) → **Kubernetes** (예정)
- **Redis** (캐시, 대기열, HOLD) / **PostgreSQL - NeonDB** (영속 데이터)

## Architecture

단계적으로 복잡도를 높여가며 MSA를 학습합니다.

| Phase | 구성 | 상태 |
|-------|------|------|
| Phase 1 | Queue Service 단독 | 완료 |
| Phase 2 | Gateway + Reserve + Core + Payment + Kafka + Redis | 완료 |
| Phase 3 | Kubernetes 전환 (minikube, HPA, Ingress) | 진행중 |

### 서비스 구성 (Phase 2)

| 서비스 | 역할 | 스케일링 | 포트 |
|--------|------|---------|------|
| gateway | API 라우팅 | HPA | 8080 |
| reserve-service | 좌석 예약 + 대기열 (핫패스) | HPA | 8082 |
| core-service | 이벤트 라이프사이클 + 유저 관리 (콜드패스) | replicas=1 | 8084 |
| payment-service | 결제 처리 (Kafka Saga) | HPA | 8083 |

### 예약 처리 흐름

```
Client → POST /reservations (enqueue)
  → Redis 캐시 검증 (이벤트/잔여석/중복)
  → SEAT_PICK이면 Lua HOLD
  → Kafka reserve.queue 발행

QueueConsumer (consumer group, 파티션 분배)
  → 좌석 배정 (SKIP LOCKED / 낙관적 락)
  → SeatHeld 이벤트 → payment-service가 Payment(PENDING) 생성

Client → POST /reservations/pay (202 Accepted)
  → PaymentRequested 이벤트 (비동기 Saga)
  → 성공: RESERVED / 실패: AVAILABLE 복구 (보상)
```

### 동시성 보호

| 패턴 | 적용 대상 |
|------|----------|
| 낙관적 락 (`@Version`) | SEAT_PICK (직접 좌석 선택) |
| 비관적 락 (`FOR UPDATE SKIP LOCKED`) | SECTION_SELECT (구역 자동 배정) |
| Redis Lua 원자성 | SEAT_PICK HOLD 획득/해제 |
| Kafka consumer group | 다중 pod에서 메시지 분배 (중복 소비 방지) |

## Quick Start

```bash
# 빌드
./gradlew build

# Docker Compose로 전체 실행
docker compose up -d --build

# 로그 확인
docker compose logs -f reserve-service

# 테스트
./gradlew test

# Kubernetes (minikube) 배포
minikube start --cpus=4 --memory=8192
minikube addons enable ingress
minikube addons enable metrics-server
./kubernetes/scripts/deploy.sh
```

## Project Structure

```
├── common/            # 공유 라이브러리 (이벤트 DTO, 캐시 키, 예외 처리)
├── gateway/           # API Gateway
├── reserve-service/   # 좌석 예약 + 대기열 (핫패스)
├── core-service/      # 이벤트 라이프사이클 + 유저 관리 (콜드패스)
├── payment-service/   # 결제 처리
├── kubernetes/        # K8s 매니페스트 (Deployment, Service, HPA, Ingress)
└── docs/              # 문서 (설계, 명세, 학습)
```

## Harness Engineering

이 프로젝트는 **하네스 엔지니어링** 방법론을 적용합니다.

- **CONSTITUTION.md**: 프로젝트의 원칙과 규칙
- **ARCHITECTURE.md**: 실제 시스템 구조
- **AGENTS.md**: 역할 정의와 권한

## License

This project is for study purposes.
