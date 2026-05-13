# Reliability

> 서비스 안정성과 장애 대응.

---

## 1. 장애 격리

- 서비스별 독립 프로세스/컨테이너
- Circuit Breaker 패턴 적용 (Phase 2, Resilience4j 예정)
- Timeout 설정 필수 (WebClient 기본 timeout)

## 2. 대기열 서비스 특수 고려

- Redis 장애 시: 대기열 조회 불가 → 적절한 에러 응답 반환
- RDB 장애 시: 신규 생성 불가, 기존 Redis 기반 대기열은 유지
- 서비스 재시작 시: Redis 기반 대기열 상태는 유지됨 (서비스는 stateless)

## 3. 롤백

- 이전 Docker 이미지 태그로 즉시 롤백
- DB 마이그레이션 롤백 스크립트 항상 함께 작성
- 롤백 절차는 CONSTITUTION.md 배포 원칙 참조

## 4. 헬스체크

```
GET /actuator/health         # Spring Boot Actuator
GET /actuator/health/liveness
GET /actuator/health/readiness
```
