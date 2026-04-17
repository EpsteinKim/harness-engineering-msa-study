# Payment / Seat 상태 전이 보강 + 대기열 순번 GET API

- **시작일**: 2026-04-16
- **완료일**: 2026-04-16
- **담당**: operator + claude
- **상태**: Completed (#1만 진행, #2~#5는 드롭)
- **관련 프론트엔드 계획**: [../../../../frontend/docs/exec-plans/active/seat-pick-queue-polling.md](../../../../frontend/docs/exec-plans/active/seat-pick-queue-polling.md)

## 최종 결과

- **#1 대기열 순번 GET 엔드포인트** — 완료. `GET /api/v1/reservations/queue/{eventId}/{userId}` 신설, `QueuePositionResponse(position, inQueue)` 반환, 기존 `ReservationService.getPosition()` 재사용. 컨트롤러 단위 테스트 2건 추가.
- **#2 `reservedAt` 시점 정정** — 드롭 (운영자 판단).
- **#3 실패 분기 `save()` 명시** — 드롭.
- **#4 트랜잭션 경계 축소** — 드롭. 필요 시 별도 exec-plan으로 재개.
- **#5 `PAYMENT_PENDING` → `HELD` 리네이밍** — 드롭 (미승인).

## 배경

프론트엔드의 SEAT_PICK 자동 진행 플로우(관련 계획 참조)가 동작하려면 백엔드에 현재 순번 조회 GET API가 필요하다. 동시에 `reserve-service` 결제 오케스트레이션 로직을 훑는 중 다음 이슈들이 포착되어 묶어서 정리한다.

### 확인된 사실 (코드 근거)

| # | 위치 | 현상 |
|---|------|------|
| F1 | `PaymentOrchestrator.kt:29-77` | `pay()` 전체가 `@Transactional`로 묶여 있고 내부에서 `paymentClient.processPayment()` 동기 HTTP 호출 수행 |
| F2 | `PaymentOrchestrator.kt:60-67` 실패 분기 | `seat.status/userId/reservedAt`을 변경하지만 `seatRepository.save()` 명시 호출 없음. 관리 엔티티이므로 JPA dirty checking으로 커밋 시 반영되긴 함 |
| F3 | `PaymentOrchestrator.kt:63-67` | Redis 캐시 조작(`adjustSeatCounts`, `markSeatAvailable`) + `dynamicScheduler.startProcessing()`이 `@Transactional` 안에서 실행. DB 롤백 시 Redis/스케줄러는 되돌아가지 않음 |
| F4 | `SeatService.kt:32, 45` | `reserveBySeatId()` / `reserveBySection()`이 HOLD(`PAYMENT_PENDING`) 전환 시점에 `reservedAt = now()` 설정. 의미상 "예약 확정 시각"이 아닌 "좌석 점유 시각" |
| F5 | `PaymentOrchestrator.kt:49` | 결제 성공 시 `reservedAt`을 다시 `now()`로 덮어씀. 즉 `reservedAt` 값의 의미가 상태에 따라 달라짐 |
| F6 | 엔티티 `Seat.kt` | enum `SeatStatus.PAYMENT_PENDING`. payment-service의 `PaymentStatus.PENDING`과 이름이 충돌해 로그·지표 해석이 혼란스러움 |
| F7 | `ReservationService.getPosition()` 존재 | 현재 순번 계산 로직은 이미 구현되어 있으나 GET 엔드포인트로 노출되지 않음 (enqueue 응답에서만 반환) |
| F8 | `EventController.kt:33` | `GET /my` 이미 구현 완료. 본 계획에서는 추가 작업 없음 |

## 목표

프론트 폴링 API 제공 + 결제/좌석 상태 전이의 의미·원자성을 개선한다. DB 스키마 변경이 필요한 항목(F6 리네이밍)은 별도 승인 절차를 거친다.

## 작업 범위

### 1. [필수] 대기열 순번 GET 엔드포인트 신설

- 경로: `GET /api/v1/reservations/queue/{eventId}/{userId}`
- 컨트롤러: `ReservationController`에 추가 (또는 적절한 컨트롤러)
- 응답 DTO(신규): `QueuePositionResponse(position: Long?, inQueue: Boolean)`
  - 대기열에 존재: `position = rank`, `inQueue = true`
  - 내 차례 처리 완료(=큐에서 제거됨): `position = null`, `inQueue = false`
  - 만료/취소 등 어떤 이유로든 큐에 없음: 동일하게 `inQueue = false`
- 구현: 기존 `ReservationService.getPosition(eventId, userId)` 재사용. 반환값이 null/음수인 경우 `inQueue=false`로 매핑
- Swagger: springdoc 자동 스캔으로 `?urls.primaryName=reserve-service` 그룹에 노출 확인

### 2. [필수] `reservedAt` 설정 시점 정정 (F4, F5)

- `SeatService.reserveBySeatId()` / `reserveBySection()`에서 `seat.reservedAt = LocalDateTime.now()` **제거**
- `PaymentOrchestrator.pay()` 성공 분기(line 49)에서만 `reservedAt`을 설정 — 현재 그대로 유지
- 실패 분기(line 62)에서 `reservedAt = null`은 그대로 유지 (이미 null이어서 no-op)
- 영향: `reservedAt`은 오직 `RESERVED` 상태에서만 의미를 갖는다. `MyReservationItem.reservedAt` 프론트 표시가 변경될 수 있으므로(HOLD 단계에서 null) 프론트 계획과 타이밍 정렬

### 3. [필수] 실패 분기 방어적 `save()` 호출 (F2)

- `PaymentOrchestrator.pay()` 실패 분기의 `seat` 변경 직후 `seatRepository.save(seat)` 명시 호출
- 이유: dirty checking에 의존하면 후속 리팩터링(예: seat 객체를 detach하거나 별도 서비스로 추출) 시 실수로 flush가 누락될 여지가 생긴다. 성공 분기도 동일 처리로 일관성 유지
- 단위 테스트: 결제 실패 → 같은 좌석 재시도 가능(`AVAILABLE` 상태로 돌아갔는지) 회귀 테스트 추가

### 4. [선택 / 후속 exec-plan 권장] 트랜잭션 경계 축소 (F1, F3)

현 구조에서는 다음 엣지 케이스가 존재한다.
- 타임아웃으로 `paymentClient.processPayment()`가 실패 응답을 만들었지만 원격 payment-service는 SUCCEEDED로 커밋 → 원격 Payment는 남고 reserve 측은 좌석 복구
- Redis 캐시/스케줄러 변경은 `@Transactional` 안에서 일어나지만 DB 커밋 실패 시 Redis는 롤백 안 됨

본 exec-plan에서는 이슈만 기록하고 별도 exec-plan(`payment-tx-boundary-review.md`)으로 분리한다. 이유: 범위가 커지고 재시도/멱등성/보상 재실행 설계가 동반되어야 함.

### 5. [선택 / 승인 필요] `SeatStatus.PAYMENT_PENDING` → `HELD` 리네이밍 (F6)

- 엔티티 enum, 쿼리(JPQL/QueryDSL), 서비스/오케스트레이터 참조, `dto/Responses.kt`의 노출 문자열 모두 동시 치환
- DB 컬럼값 마이그레이션: 기존 `PAYMENT_PENDING` 로우 → `HELD`로 UPDATE (단일 트랜잭션, Flyway 또는 수동 SQL)
- 프론트 `SeatStatus` 유니언(`src/api/types.ts`) 동기 변경 — 프론트 계획 완료 후 **동일 머지 윈도**에서 정렬 필요 (단독 배포 시 프론트가 새 값을 인식 못함)
- **DB 스키마 값 변경은 운영자 승인 필요** — 본 항목은 승인 전까지 보류. 승인 후 본 파일에 승인 타임스탬프 기록.

## 승인 필요 항목 (AGENTS.md 기준)

| 항목 | 사유 | 현 상태 |
|------|------|--------|
| #5 enum 문자열 리네이밍 | DB 값 마이그레이션 수반 | **운영자 승인 대기** |
| #1, #2, #3 | 스키마/의존성 변경 없음 | 즉시 진행 가능 |

## 작업 순서

1. #1 큐 포지션 GET 구현 + 단위 테스트 (프론트 폴링 차단 해제 우선)
2. #2 `reservedAt` 시점 정정
3. #3 실패 분기 `save()` + 회귀 테스트
4. (승인 후) #5 enum 리네이밍 + 마이그레이션 + 프론트 동기화
5. #4 트랜잭션 경계 이슈는 신규 exec-plan으로 분리

## 검증 방법

- `./gradlew :reserve-service:test` 통과 (신규 단위 테스트 포함)
- Swagger UI `http://localhost:8080/swagger-ui/index.html?urls.primaryName=reserve-service`에 신규 GET 엔드포인트 노출 확인
- `curl 'http://localhost:8080/api/v1/reservations/queue/1/1'` → 대기 중일 때 `{position, inQueue: true}`, 처리 후 `{position: null, inQueue: false}`
- 수동 시나리오: SEAT_PICK 이벤트에서 enqueue → pay 성공 경로에서 `reservedAt`이 `RESERVED` 시점에만 설정되는지 DB 직접 조회로 확인
- 결제 실패 시나리오: payment-service 랜덤 실패 반복 호출 → 같은 좌석 재시도 시 에러 없이 재진행 가능해야 함

## 완료 처리

- 모든 체크 통과 시 본 파일을 `backend/docs/exec-plans/completed/`로 이동하고 `completed/index.md` 표에 요약 1행 추가
- #4, #5가 미완이면 각각 신규 exec-plan으로 active에 분리한 뒤 본 파일은 "#1~#3 완료" 상태로 마감
