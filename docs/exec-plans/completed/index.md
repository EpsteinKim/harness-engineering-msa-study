# Completed Execution Plans

완료된 실행 계획 목록.

| 완료일 | 계획명 | 파일 | 요약 |
|--------|--------|------|------|
| 2026-04-10 | 좌석 시스템 리디자인 | [seat-system-redesign.md](seat-system-redesign.md) | section 자동 배정(SKIP LOCKED), QueryDSL, 동적 스케줄링, Redis 캐시 전략, 에러 처리 통일, 부하테스트 |
| 2026-04-12 | SEAT_PICK 임시예약(HOLD) + 좌석 맵 조회 | [seat-pick-hold.md](seat-pick-hold.md) | Lua 기반 HOLD 원자성, lazy expiry, 섹션 소진 거부, 좌석 맵 조회 API, GlobalExceptionHandler 스캔 수정 |
| 2026-04-13 | user-service 기본 + 인터서비스 호출 | [user-service-basic.md](user-service-basic.md) | User CRUD(read/update), 한국식 이름 10,000명 시드, reserve→user RestClient 검증, USER_NOT_FOUND |
| 2026-04-14 | payment-service + Saga 오케스트레이션 MVP | [payment-saga-mvp.md](payment-saga-mvp.md) | PAYMENT_PENDING 중간 상태, POST /pay 엔드포인트, 결제 실패 시 Seat AVAILABLE 복구 + scheduler 재시작 보상 |
| 2026-04-15 | 좌석/섹션별 가격 + EventCache 가격 캐싱 | [seat-section-pricing.md](seat-section-pricing.md) | seat.price_amount 컬럼, EventCache section/seat_price 필드, 응답 DTO에 priceAmount, PaymentRequest amount 제거 (위변조 차단) |
| 2026-04-15 | 이벤트 목록 & 내 예약 조회 API | [event-listing-api.md](event-listing-api.md) | reserve-service EventController(/events, /events/{id}, /my), EventQueryService, payment-service GET /payments?userId=X, PaymentClient.listByUser |
