# API Specification (Generated)

> 이 문서는 빌드 시 자동 생성됩니다. 수동 편집하지 마세요.
> OpenAPI(Swagger) 스펙 기반으로 갱신됩니다.

---

## 현재 API

> springdoc-openapi 연동 전까지 수동 기록.

### reserve-service

| Method | Path | 설명 | 상태 |
|--------|------|------|------|
| POST | `/api/v1/reservations` | 예약 요청 (seatId 또는 section) | 구현 완료 |
| GET | `/api/v1/reservations/seats/{eventId}/sections` | 구역별 잔여석 조회 | 구현 완료 |
| DELETE | `/api/v1/reservations/queue/{eventId}/{userId}` | 대기열 취소 | 구현 완료 |

### user-service

| Method | Path | 설명 | 상태 |
|--------|------|------|------|
| GET | `/api/v1/users` | 헬스체크 | 구현 완료 |
