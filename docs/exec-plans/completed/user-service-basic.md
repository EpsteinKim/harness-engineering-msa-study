# user-service 기본 기능 + reserve-service 인터서비스 호출

> 시작일: 2026-04-13 / 완료일: 2026-04-13

## 배경

`user-service`는 헬스 엔드포인트만 있는 빈 껍데기였음. Phase 2 마무리 단계에서 user-service에 최소 기능을 채우고, **MSA 학습용 인터서비스 호출 패턴**을 reserve-service에 한 군데 도입.

학습 우선이라 회원가입/JWT/Spring Security는 도입하지 않음. 비밀번호는 plain text. 사용자 데이터는 SQL 시드로 사전 주입.

## 결정 사항

- API는 `GET /api/v1/users/{id}` 조회 + `PATCH /api/v1/users/{id}` 수정만
- 시드 데이터: `generate_series` 1~10,000 + 한국식 이름(성50 × 음절50 × 음절50)
- DB 테이블명: `user_account` (Postgres `user` 예약어 회피)
- reserve-service `enqueue` 시작에 `userClient.exists(userId)` 검증 → `USER_NOT_FOUND`
- 인증/권한 없음 — 추후 도입 시 Gateway 헤더 패턴 유력

## 완료된 작업

- [x] User entity (`user_account` 테이블, BIGSERIAL PK + email UNIQUE)
- [x] `UserRepository`, `UserService.getById/update`
- [x] `UserController` GET/PATCH + 헬스
- [x] DTO 분리 (`Requests.kt`, `Responses.kt`) — 응답에 password 미노출
- [x] `UserServiceApplication` `scanBasePackages` 추가 (GlobalExceptionHandler 등록)
- [x] `kotlin("plugin.jpa")`, `kotlin("kapt")` 플러그인 추가
- [x] `seed.sql` — 한국식 이름 10,000명 (125,000 조합 풀)
- [x] reserve-service `RestClient` 빈 + `UserClient.exists(userId)`
- [x] `ReservationService.enqueue`에 userId 형식/존재 검증
- [x] `application.properties` / `application-local.properties`에 `user-service.base-url`
- [x] `USER_NOT_FOUND` ErrorCode (양쪽 서비스에 추가)
- [x] 단위 테스트 (`UserServiceTest`, `UserControllerTest`, `ReservationServiceTest` 신규 케이스)

## 참고

- IntelliJ에서 user-service가 별도 프로젝트로 등록되어 classpath 에러 발생 — `.idea` 삭제 후 backend 루트로 재오픈하여 해결
- DB 스키마 변경(테이블 신규 생성)은 사전 승인됨
- CLAUDE.md 규칙대로 사용자 노출 메시지는 한글, 로그·내부 객체는 영문
