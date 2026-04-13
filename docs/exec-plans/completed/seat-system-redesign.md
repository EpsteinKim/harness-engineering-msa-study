# 좌석 시스템 리디자인

> 시작일: 2026-04-08

## 배경

- 이벤트당 좌석 5만석 (총 10만 Row) 규모
- 프론트에 전체 좌석 목록을 내려주면 Entity 생성 + JSON 직렬화 + 네트워크 전송에서 병목
- 5만석 규모에서는 사용자가 직접 좌석을 고르는 것보다 자동 배정이 현실적

## 결정 사항

### 1. 좌석 자동 배정 방식 채택
- 구역(A~Z) 선택 → 서버에서 빈 좌석 자동 배정
- VIP/지정석만 예외적으로 직접 선택 가능 (추후)
- 프론트에는 잔여석 수 또는 구역별 요약만 제공

### 2. 좌석번호 규칙
- 형식: `{구역알파벳}{번호}` (예: A1, A2, ..., A100000, B1, ..., Z100000)
- 구역은 좌석번호 앞 알파벳으로 판별

### 3. DB 스키마 변경 (승인됨 ✅)
- `seats` 테이블에 `section VARCHAR(1) NOT NULL DEFAULT 'A'` 컬럼 추가
- `idx_seats_event_section(event_id, section)` 인덱스 추가
- 구역별 조회 시 `LIKE 'A%'` 대신 `section = 'A'`로 인덱스 활용

### 4. 자동 배정 쿼리 패턴
```sql
SELECT id, seat_number FROM seats
WHERE event_id = ? AND section = ? AND status = 'AVAILABLE'
ORDER BY id
LIMIT 1
FOR UPDATE SKIP LOCKED;
```
- `SKIP LOCKED`로 동시 요청 시 충돌 없이 처리 (비관적 락)

## 완료된 작업

- [x] 테스트용 시드 데이터 SQL 작성 (events 2개 + seats 10만 Row)
- [x] `ReservationQueueService.kt` 컴파일 에러 수정 (잘못된 import 제거, 쉼표 누락)
- [x] reserve-service Docker 빌드 & 기동 확인
- [x] PostgreSQL MCP 서버 추가 (local scope, @bytebase/dbhub)
- [x] `seats` 테이블 스키마 변경 승인 → 적용 (section 컬럼 + 인덱스)
- [x] Seat Entity에 `section` 필드 추가 + SeatDTO 갱신
- [x] 시드 데이터 ApplicationRunner 컴포넌트 생성 (`@Profile("seed")`)
- [x] 구역별 잔여석 조회 API 구현 (`GET /seats/{eventId}/sections`)
- [x] 자동 배정 예약 API 구현 (`POST /section`, SKIP LOCKED 패턴)
- [x] 프론트 연동을 위한 응답 DTO 정리 + 문서 갱신
- [x] QA 빌드 검증 통과
- [x] QueryDSL 도입 (구역별 잔여석 조회 → `SeatQueryRepository`)
- [x] DTO 통합 (`Requests.kt`, `Responses.kt`)
- [x] `ServerException` + `GlobalExceptionHandler` 공통 모듈 추가
- [x] springdoc-openapi 3.0.2 (Swagger UI) 추가
- [x] Redis 포트 호스트 매핑 (로컬 개발용)
- [x] `application-local.properties` 로컬 개발 프로필 추가
- [x] DB에 section 컬럼 + 인덱스 적용 (MCP로 직접 실행)
- [x] 시드 데이터 재삽입 (이벤트 2개 × 구역 A~D × 100석 = 800석)
- [x] 클래스 리네임 완성 (ReservationQueueService → ReservationService, SeatReservationService → SeatService)
- [x] 이벤트별 큐 분리 (WAITING_KEY(eventId)) — 모든 메서드에 eventId 적용
- [x] 중복 키 함수 통합 (constant.kt의 metadataKey, eventCacheKey 사용)
- [x] cancel/getPosition에서 메타데이터 기반 eventId 조회
- [x] 동적 스케줄링 (DynamicScheduler) — 이벤트별 독립 타이머
- [x] SchedulerConfig (ThreadPoolTaskScheduler, poolSize=20)
- [x] remainingSeats Redis 캐시 실시간 관리 (HINCRBY)
- [x] 5분 보정 스케줄 (syncRemainingSeats)
- [x] enqueue 시 잔여석 체크 (조기 거부)
- [x] processEvent 시 잔여석 체크 (DB 호출 차단)
- [x] 구역별 잔여석 Redis 캐싱 (section:$section:available/total)
- [x] getSectionAvailability를 Redis 조회로 전환 (DB 호출 제거)
- [x] seatSelectionType 추가 (SECTION_SELECT, SEAT_PICK)
- [x] SEAT_PICK 좌석별 캐싱 (event:$eventId:seats)
- [x] enqueue 타입별 검증 (SEAT_PICK은 seatId 필수, SECTION_SELECT은 section 필수)
- [x] 동시성 이슈 수정: cancel-processEvent 경합 (ZSCORE 재확인)
- [x] 동시성 이슈 수정: 동일 유저 중복 enqueue 거부
- [x] 동시성 이슈 수정: sync 시 큐 처리 일시 중지
- [x] Controller "both null" 가드 (INVALID_REQUEST)
- [x] JPQL enum FQN 수정
- [x] 전체 테스트 코드 업데이트 (28개 테스트 스위트)

- [x] Controller 에러 처리 통일: ApiResponse.error → ServerException 전환, ErrorCode 상수화
- [x] 에러코드 분리 (EVENT_NOT_OPEN, NO_REMAINING_SEATS, ALREADY_IN_QUEUE 등 8종)
- [x] enqueue 엔드포인트 통합 (POST /reservations/section 제거, POST /reservations 하나로)
- [x] SectionReservationRequest DTO 제거
- [x] metadata 키에 eventId 포함 (reservation:metadata:{eventId}:{userId})
- [x] cancel API에 eventId 추가 (DELETE /queue/{eventId}/{userId})
- [x] getPosition/getRequestData/cancel 시그니처에 eventId 파라미터 추가
- [x] metadata에서 eventId 필드 제거 (키에 이미 포함)
- [x] ReservationService에서 미사용 seatRepository 의존성 제거
- [x] SeatController 경로 변경 (/api/v1/seats → /api/v1/reservations/seats)
- [x] 스로틀 레이트 증가 (10 → 20)
- [x] ServerException에 code 필드 추가, GlobalExceptionHandler에 code 포함
- [x] DynamicScheduler 안정성: userId.toLongOrNull() + 전체 try-catch
- [x] SeatService.reserveBySection에 ObjectOptimisticLockingFailureException 처리 추가
- [x] Locust 부하테스트 코드 수정: userId 매 요청 새로 생성, 이벤트 분리 (1=SEAT_PICK, 2=SECTION_SELECT), 400 에러 필터링, 최대 요청 수 제한
- [x] 동시성 부하 테스트 실행 및 결과 분석 (930 RPS 달성)

## 남은 작업

- [ ] Docker Compose 환경에서 E2E 테스트

## 참고

- 낙관적 락(`@Version`)은 기존 유지, 자동 배정에는 `FOR UPDATE SKIP LOCKED` 비관적 락 병행
- 결제 연동 시 PENDING 상태 추가 고려 (현재는 결제 서비스 없음)
- 외부 API 호출(결제 등)은 DB 트랜잭션 밖에서 처리해야 함
