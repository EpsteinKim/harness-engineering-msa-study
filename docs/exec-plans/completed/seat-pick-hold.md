# SEAT_PICK 임시예약(HOLD) + 섹션 소진 차단 + 좌석 맵 조회

> 시작일: 2026-04-11 / 완료일: 2026-04-12

## 배경

SEAT_PICK 이벤트에서 좌석은 **AVAILABLE ↔ RESERVED** 2-state로만 관리되어, 프론트가 좌석 목록을 본 뒤 사용자가 선택해 `POST /reservations`를 보내는 시점까지 gap이 있었음. 여러 유저가 같은 좌석을 동시에 큐에 넣으면 늦게 처리된 쪽이 "이미 예약됨" 실패를 받는 경합이 빈번 (부하테스트 seatId 기반 요청 실패의 주원인).

## 결정 사항

- **HOLD 상태는 Redis만, DB는 유지** — DB는 여전히 `AVAILABLE ↔ RESERVED` 2-state. HOLD 정보는 Redis seat hash 값에 인라인.
- **상태 전이는 Lua 스크립트로 원자성 보장** — `try_hold_seat.lua`, `release_hold.lua`.
- **lazy expiry** — 별도 sweeper 없이 `tryHoldSeat` Lua와 응답 변환에서 만료 체크.
- **SECTION_SELECT는 HOLD 미적용** (자동배정), 대신 **소진된 섹션 거부**.
- **좌석 맵 조회 신규 API** (`GET /api/v1/reservations/seats/{eventId}?section=`).

### Cache Value Format

`event:{eventId}:seats` hash 필드 값:
- `"{section}:{num}:AVAILABLE"`
- `"{section}:{num}:HELD:{userId}:{heldUntilEpochMillis}"`
- `"{section}:{num}:RESERVED"`

## 완료된 작업

- [x] `EventCacheRepository.tryHoldSeat / releaseHold / getSectionAvailable / getAllSeatFields` 추가
- [x] Lua 스크립트 두 개 (`resources/redis/try_hold_seat.lua`, `release_hold.lua`)
- [x] `ReservationService.enqueue`에 SEAT_PICK HOLD 획득 + SECTION_SELECT 섹션 소진 체크
- [x] `DynamicScheduler` 실패 경로에서 HELD 복원, 성공 시 markSeatReserved
- [x] `ReservationService.cancel`에서 HELD 잔존 복원
- [x] `SeatService.getSeatMap` + `SeatController GET /seats/{eventId}` 엔드포인트
- [x] `parseSeatValue` 유틸 + `ParsedSeat.effectiveStatus(now)` (만료 HELD → AVAILABLE 변환)
- [x] 신규 ErrorCode (`SEAT_UNAVAILABLE`, `SECTION_FULL`)
- [x] `application.properties`에 `reserve.hold.ttl-millis=600000` (10분)
- [x] `ScheduledFuture` 관리 + cancel 시 `isTicketingStillOpen` 체크 후 스케줄러 재시작
- [x] `DynamicScheduler`에 `ObjectOptimisticLockingFailureException` 전용 catch (info 로그)
- [x] `GlobalExceptionHandler` 미등록 이슈 발견 → `ReserveApplication`에 `scanBasePackages` 추가
- [x] 사용자 노출 메시지 한글화, 내부 메시지/로그 영문 통일 (CLAUDE.md 규칙 추가)
- [x] 전체 테스트 통과 (Mockito + MockMvc)

## 참고

- 결제 단계 도입(DB `PAYMENT_PENDING`)은 별도 PR로 분리
- 만료 HELD 백그라운드 sweeper는 미도입 (lazy expiry로 충분)
- 시간 기반 조건은 캐시 존재 여부가 아닌 entity 시간 필드와 `now()` 비교로 판단 (원칙 합의)
