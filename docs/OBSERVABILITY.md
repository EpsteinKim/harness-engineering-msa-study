# Observability

> 로깅, 메트릭, 트레이싱 전략.

---

## 1. 로깅

### 구조화된 로그

- JSON 포맷 (logback-encoder 사용 예정)
- 필수 필드: `timestamp`, `level`, `service`, `traceId`, `message`

### 로그 레벨 기준

| 레벨 | 용도 |
|------|------|
| ERROR | 서비스 장애, 처리 불가 오류 |
| WARN | 예상 가능한 문제 (timeout, retry) |
| INFO | 비즈니스 이벤트 (대기열 생성, 참가, 상태 변경) |
| DEBUG | 개발/디버깅용 (prod에서는 비활성) |

## 2. 메트릭

> Spring Boot Actuator + Micrometer 기반.

### 핵심 메트릭

- `queue.active.count` - 활성 대기열 수
- `queue.entry.count` - 전체 대기 인원
- `queue.join.rate` - 초당 참가 요청 수
- `http.server.requests` - API 응답 시간/상태 코드

### 도구

- Phase 1: Actuator `/actuator/metrics` 엔드포인트
- Phase 2+: Prometheus + Grafana (예정)

## 3. 분산 트레이싱

> Phase 2에서 서비스가 분리되면 도입.

- Micrometer Tracing (구 Spring Cloud Sleuth)
- Trace ID를 모든 서비스 로그에 전파
- Zipkin 또는 Jaeger (Phase 2+)
