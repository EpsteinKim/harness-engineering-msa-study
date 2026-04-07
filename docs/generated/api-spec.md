# API Specification (Generated)

> 이 문서는 빌드 시 자동 생성됩니다. 수동 편집하지 마세요.
> OpenAPI(Swagger) 스펙 기반으로 갱신됩니다.

---

## 현재 API

> springdoc-openapi 연동 전까지 수동 기록.

### queue-service

| Method | Path | 설명 | 상태 |
|--------|------|------|------|
| POST | `/api/v1/queues/enqueue` | 대기열 등록 (callbackUrl + payload) | 구현 완료 |
| DELETE | `/api/v1/queues/{userId}` | 대기열 취소 | 구현 완료 |
| POST | `/api/v1/queues/dequeue` | 수동 dequeue (디버깅용) | 구현 완료 |

### reserve-service

| Method | Path | 설명 | 상태 |
|--------|------|------|------|
| POST | `/api/v1/reservations` | 좌석 예약 (낙관적 락) | 구현 완료 |

### user-service

| Method | Path | 설명 | 상태 |
|--------|------|------|------|
| GET | `/api/v1/users` | 헬스체크 | 구현 완료 |
