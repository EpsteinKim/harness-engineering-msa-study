# System Design

> 시스템 설계 관점에서의 핵심 결정과 패턴.

---

## 1. 대기열 설계

### 핵심 요구사항

- FIFO 순서 보장
- 실시간 순번 조회
- 동시 접속 처리 (WebFlux 리액티브)
- 대기열 상태 관리 (OPEN / CLOSED / PAUSED)

### 설계 방향

```
[요청] → [API Layer] → [Service Layer] → [Redis (실시간)] + [RDB (이력)]
```

- **Redis**: 실시간 대기열 상태 (Sorted Set 또는 List)
- **RDB**: 대기열 메타데이터, 참가 이력, 통계

### 동시성 처리

- WebFlux 기반 non-blocking I/O
- Redis 원자적 연산으로 순번 충돌 방지
- Kotlin Coroutines로 비동기 흐름 관리

---

## 2. 인증/인가 (계획)

> Phase 2에서 user-service 분리 시 구체화.

- Gateway 레벨에서 JWT 검증
- 서비스 간 통신은 내부 네트워크 신뢰 기반 (Phase 2)
- K8s 전환 시 Service Mesh mTLS 검토 (Phase 3)

---

## 3. 확장성 고려

- 대기열당 독립 처리 가능한 구조
- Redis 클러스터링으로 수평 확장 (필요 시)
- Stateless 서비스 설계로 인스턴스 수평 확장 용이
