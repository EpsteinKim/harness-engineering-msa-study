# user-service

사용자 조회/수정 API.

## 시드 데이터 (10,000명) 주입

테이블이 생성된 뒤(=서비스 1회 기동 후) 실행:

```bash
psql "$USER_DB_URL" -f user-service/src/main/resources/db/seed.sql
```

`ON CONFLICT DO NOTHING`이라 여러 번 실행해도 안전.

## API

- `GET /api/v1/users/{id}` — 사용자 조회
- `PATCH /api/v1/users/{id}` — 이름/이메일 수정 (`{ "name": "...", "email": "..." }`)
- `GET /api/v1/users/health` — 헬스체크
