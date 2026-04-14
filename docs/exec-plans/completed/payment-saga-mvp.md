# payment-service + Saga 오케스트레이션 MVP

> 시작일: 2026-04-14 / 완료일: 2026-04-14

## 배경

Phase 2 핵심(Gateway/Reserve/User + HOLD + 인터서비스 호출) 완료 후, K8s(Phase 3)로 넘어가기 전에 **분산 트랜잭션 = Saga** 개념을 Docker Compose 위에서 구현. payment-service를 신설하고 reserve-service가 오케스트레이터로 결제 성공/실패에 따라 좌석 상태를 확정하거나 보상.

`scheduler 좌석 배정 = RESERVED 확정` 구조를 **좌석 배정 ≠ 결제 완료**로 분리. 배정된 좌석은 `PAYMENT_PENDING` 중간 상태, 유저가 별도 결제 API 호출 시 성공하면 `RESERVED`로, 실패하면 Saga 보상으로 `AVAILABLE`로 되돌림.

## 결정 사항

- **오케스트레이션** 스타일 (reserve-service가 코디네이터)
- **동기 HTTP** (RestClient) — 코레오그래피는 Phase 4 Kafka 연계 시 리팩토링
- **Database per Service**: payment-service 자체 NeonDB
- **Seat.SeatStatus**: AVAILABLE ↔ PAYMENT_PENDING ↔ RESERVED (DDL 변경 없음, enum만 확장)
- **학습용 랜덤 성공률** 기본 0.7 (`payment.success-rate` 설정)

## 완료된 작업

- [x] `payment-service` 신규 모듈 (entity/repo/service/controller/dto/constant, plugin.jpa + kapt 포함)
- [x] `Payment` entity + PaymentStatus enum (PENDING/SUCCEEDED/FAILED), indexes on (user_id, status), (seat_id, status)
- [x] `PaymentService.processPayment` 랜덤 판정 (testable via Random 주입)
- [x] `PaymentController` POST/GET + health
- [x] reserve-service: `Seat.SeatStatus.PAYMENT_PENDING` 추가
- [x] `SeatService.reserveBySeatId/reserveBySection` 성공 시 PAYMENT_PENDING으로 전이
- [x] `SeatRepository.findByEventIdAndUserIdAndStatus`
- [x] `PaymentClient` (RestClient, 404/네트워크 에러 매핑)
- [x] `PaymentOrchestrator` (saga 코디네이터: 성공 → RESERVED, 실패 → AVAILABLE + adjustSeatCounts(+1) + markSeatAvailable + scheduler 재시작)
- [x] `EventCacheRepository.markSeatAvailable` 신규
- [x] `POST /api/v1/reservations/pay` 엔드포인트
- [x] 신규 ErrorCode: `PAYMENT_PENDING_NOT_FOUND`, `PAYMENT_FAILED` (reserve), `PAYMENT_NOT_FOUND`, `INVALID_METHOD` (payment)
- [x] gateway 라우팅: `/api/v1/payments/**`
- [x] docker-compose: payment-service 추가 + env var 키 정리 (NEONDB_URL → RESERVE_DB_URL/USER_DB_URL/PAYMENT_DB_URL 매핑)
- [x] `.env.sample`에 PAYMENT_DB_* 3개 추가
- [x] 테스트: `PaymentOrchestratorTest` (성공/실패 보상/pending 없음/이벤트 종료), `PaymentServiceTest` (랜덤 주입), `PaymentControllerTest`, 기존 `SeatServiceTest`/`ReservationControllerTest` 업데이트
- [x] user-service DB에 한국식 이름 10,000명 시드 주입 (dockerized psql 이용)

## 참고

- Saga 재시도/타임아웃/회로차단, 환불 플로우, 자동 만료 sweeper는 Out of Scope
- `PaymentOrchestrator.pay`는 외부 HTTP를 @Transactional 안에서 호출 — Phase 4 리팩토링 예정 (트랜잭션 쪼개기 또는 아웃박스 패턴)
