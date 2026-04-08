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

## 남은 작업

- [ ] Docker Compose 환경에서 E2E 테스트
- [ ] 동시성 부하 테스트 (SKIP LOCKED 검증)
- [ ] enqueue 통합 후 기존 seatId 기반 플로우 deprecation 검토

## 참고

- 낙관적 락(`@Version`)은 기존 유지, 자동 배정에는 `FOR UPDATE SKIP LOCKED` 비관적 락 병행
- 결제 연동 시 PENDING 상태 추가 고려 (현재는 결제 서비스 없음)
- 외부 API 호출(결제 등)은 DB 트랜잭션 밖에서 처리해야 함
