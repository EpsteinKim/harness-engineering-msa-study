# 좌석/섹션별 가격 도입 + EventCache 가격 캐싱

> 시작일: 2026-04-15 / 완료일: 2026-04-15

## 배경

`PaymentRequest`에 클라이언트가 `amount`를 직접 보내는 구조라 위변조 위험. 또한 좌석/섹션별 가격 차등이 없어 실제 티켓팅과 거리감. SEAT_PICK은 좌석별, SECTION_SELECT는 섹션별 가격 차등을 도입하고, EventCache에 가격을 미리 캐싱해 좌석맵/섹션 잔여석 조회 응답에서 DB 히트 없이 가격을 반환.

## 결정 사항

- **DB**: `seat.price_amount BIGINT NOT NULL DEFAULT 0` 한 컬럼으로 두 모드 통합. SECTION_SELECT는 같은 섹션 동가 denormalized
- **Cache 구조**: 별도 hash 안 만들고 기존 `event:{eventId}` 해시에 필드 추가
  - SECTION_SELECT: `section:{section}:price` 필드
  - SEAT_PICK: `seat_price:{seatId}` 필드 prefix
  - 단일 해시로 TTL/삭제/관리 일관성 확보
- **Lua 스크립트 변경 없음** — 가격은 별도 필드로 분리, 기존 `event:{eventId}:seats` 해시 값 포맷 유지
- **PaymentRequest.amount 제거** — 서버가 `seat.priceAmount`로 결정해 위변조 차단
- **시드 가격**: 섹션별 고정 (A=200,000 / B=150,000 / C=100,000 / D=50,000), 두 이벤트 동일

## 완료된 작업

- [x] DB: `seat.price_amount` 컬럼 추가 + 800행 섹션별 UPDATE (MCP)
- [x] `Seat` entity에 `priceAmount` 필드
- [x] `constant.kt`: `sectionPriceField`, `seatPriceField`, `SEAT_PRICE_FIELD_PREFIX`
- [x] `EventCacheRepository`: `getSectionPrice`, `setSeatPrices`, `getSeatPrice`, `getAllSeatPrices`
- [x] `EventService.cacheEvent`: 섹션 가격 채우기, SEAT_PICK이면 `cacheSeatPrices` 호출
- [x] `SeatRepository.findSectionPrices` (interface projection) 신규
- [x] `SectionAvailabilityResponse` / `SeatMapEntry`에 `priceAmount` 필드 추가
- [x] `SeatService.getSeatMap` / `getSectionAvailability` 응답에 가격 포함
- [x] `PaymentRequest`에서 `amount` 필드 제거
- [x] `PaymentOrchestrator.pay`: `seat.priceAmount`를 `paymentClient.processPayment`에 전달
- [x] `ReservationController.pay` 호출 시그니처 정리
- [x] `SeatQueryRepository`의 QueryDSL projection에 4번째 인자(`Expressions.constant(0L)`) 추가 (DTO 시그니처 일치)
- [x] 테스트 업데이트: SeatServiceTest, PaymentOrchestratorTest, EventServiceTest, SeatControllerTest
- [x] 신규 테스트: SeatServiceTest.GetSeatMap에 `seatPriceIncluded`, GetSectionAvailability에 가격 검증
- [x] 문서 최신화 (README, ARCHITECTURE v11)

## 참고

- `SectionAvailabilityResponse`가 QueryDSL projection + API 응답 DTO 양쪽으로 쓰이는 형태라 priceAmount 추가 시 projection 수정 필요. 향후 cleanup으로 분리 권장
- 가격 변경 admin API는 out of scope — 변경 시 DB UPDATE + warmupCache 수동 재실행
