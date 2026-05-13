# Event Listing & My Reservations API — 프론트엔드 연동용 조회 API 확장

- **시작일**: 2026-04-15
- **완료일**: 2026-04-15
- **담당**: operator + claude
- **상태**: Completed — reserve-service `EventController` + `EventQueryService`(Redis-first, cache miss 시 DB fallback), payment-service `GET /payments?userId=X` 목록 API, `events:open` ZSET 인덱스 도입
- **관련 프론트엔드 계획**: [../../../../frontend/docs/exec-plans/active/reservation-ui-migration.md](../../../../frontend/docs/exec-plans/active/reservation-ui-migration.md)

## 배경

프론트엔드가 백엔드 이벤트 예약 도메인 UI로 재설계되면서(`reservation-ui-migration.md` 참고), 랜딩/이력 화면이 요구하는 조회 API가 부족하다. 현재는 좌석 맵과 예약 등록/결제 API만 존재하며, **이벤트 목록**과 **사용자별 예약·결제 이력**을 노출하는 엔드포인트가 없다.

## 목표

프론트가 기동 가능한 최소 조회 API 2종을 reserve-service에 추가한다. 기존 쓰기 API는 그대로 유지한다.

## 추가 엔드포인트

### 1. GET `/api/v1/reservations/events`

진행 중/예정 이벤트 카드 목록 용도.

- 쿼리 파라미터: `status` (선택, 기본 `OPEN`)
- 응답 `data`: `List<EventSummaryResponse>`
  - `id: Long`
  - `name: String`
  - `eventTime: LocalDateTime`
  - `status: OPEN | CLOSED`
  - `ticketOpenTime: LocalDateTime`
  - `ticketCloseTime: LocalDateTime`
  - `seatSelectionType: SECTION_SELECT | SEAT_PICK`
  - `remainingSeats: Int` (전체 섹션 합계, AVAILABLE 기준)
- 정렬: `ticketOpenTime ASC`

### 2. GET `/api/v1/reservations/events/{eventId}`

상세 화면 헤더용. 좌석 맵 조회와 별개로 이벤트 메타데이터만 반환.

- 응답 `data`: 위 `EventSummaryResponse`와 동일한 스키마

### 3. GET `/api/v1/reservations/my`

사용자별 예약/결제 이력. 인증 미도입이므로 `userId`를 쿼리 파라미터로 받는다.

- 쿼리 파라미터(필수): `userId: Long`
- 응답 `data`: `List<MyReservationItem>`
  - `eventId: Long`
  - `eventName: String`
  - `eventTime: LocalDateTime`
  - `seatId: Long`
  - `seatNumber: String`
  - `section: String`
  - `priceAmount: Long`
  - `paymentId: Long?`
  - `paymentStatus: PENDING | SUCCEEDED | FAILED | null`
  - `reservedAt: LocalDateTime?`
- 구현 힌트: `Seat`에서 `userId` 필터 → `Event`/`Payment`를 조인. payment-service 호출이 필요하면 RestClient 경유.

## 작업 항목

> **크로스-프로젝트 조율 (2026-04-15)**: 루트 관리자 결정에 따라 본 계획은 프론트 `reservation-ui-migration.md`와 **병렬** 진행한다. 프론트는 DTO 동결 스키마(본 문서 16–57행) 기준 mock으로 선행 구현하므로, 백엔드는 응답 필드명·타입이 문서와 **정확히 일치**하도록 구현한다. 머지 이후 프론트가 mock→apiFetch 교체 커밋으로 연결한다.

1. **DTO 추가** — `reserve-service` 내 `EventSummaryResponse`, `MyReservationItem` 정의
2. **Repository 쿼리** — `EventRepository.findByStatusOrderByTicketOpenTimeAsc(...)`, `SeatRepository` 섹션 합계 집계, `SeatRepository.findByUserId(...)`
3. **Service 구현** — 기존 `reserve-service` 패키지 컨벤션(Service/QueryService 분리) 준수
4. **Controller 추가** — `ReserveController` 또는 `EventQueryController` 신설
5. **Gateway 라우팅 확인** — `/api/v1/reservations/**`은 이미 reserve-service로 라우팅되므로 추가 작업 불필요
6. **Swagger 반영** — springdoc 자동 스캔으로 `reserve-service` 그룹에 노출됨을 확인 (`?urls.primaryName=reserve-service`)
7. **서비스 간 호출(선택)** — `/my` 구현 시 payment 정보가 필요하면 기존 `reserve → payment` RestClient 재사용
8. **테스트** — Service 단위 테스트 + Controller MockMvc 테스트

## 승인 필요 항목 (AGENTS.md 기준)

- **DB 스키마 변경 없음** (기존 `Event`, `Seat`, `Payment` 테이블 조회만 사용) → 승인 불필요
- **의존성 추가 없음** → 승인 불필요
- 신규 쿼리 인덱스가 필요한 경우(예: `seats(user_id)`)는 별도 ADR/승인 절차

## 검증 방법

- `./gradlew :reserve-service:test` 통과
- `./gradlew bootRun` 후 Swagger UI(`?urls.primaryName=reserve-service`)에서 3개 엔드포인트 표시 확인
- 수동 호출:
  - `GET /api/v1/reservations/events` → OPEN 상태 이벤트 배열
  - `GET /api/v1/reservations/events/1` → 단일 이벤트
  - `GET /api/v1/reservations/my?userId=1` → 해당 사용자의 예약 이력
- 프론트 `reservation-ui-migration.md`의 Events / MyReservations 페이지가 mock 없이 실제 데이터로 렌더되는지 교차 검증

## 완료 처리

작업 완료 시 본 파일을 `backend/docs/exec-plans/completed/`로 이동하고 `completed/index.md` 표에 요약 1줄을 추가한다.
