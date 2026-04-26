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
| 2026-04-16 | 대기열 순번 GET API (+ 상태 전이 보강 이슈 정리) | [payment-seat-state-fix.md](payment-seat-state-fix.md) | GET /queue/{eventId}/{userId} → QueuePositionResponse(position, inQueue), ReservationService.getPosition 재사용, 컨트롤러 테스트 2건. #2~#5(reservedAt/save 명시/TX 경계/enum 리네이밍)는 드롭 |
| 2026-04-16 | Event-Driven 전환 (Kafka Queue + Payment Saga + HOLD 만료 + core-service 분리) | [event-driven-saga.md](event-driven-saga.md) | 5 Phases 전체 완료: A(Kafka+QueueConsumer) B(SeatHeld→Payment PENDING) C(/pay 202+Saga) D(HoldExpiry+PaymentStatus EXPIRED/CANCELLED) E(core-service+user 캐싱+user-service 흡수). Docker Compose E2E 검증 대기 |
| 2026-04-17 | Event 도메인 core-service 이관 | [event-domain-migration.md](event-domain-migration.md) | Event entity/lifecycle/query를 core-service로 이관, Seat FK 제거(eventId: Long), EventOpened/Closed Kafka 이벤트, SeatSyncService, CLAUDE.md 대용량 트래픽 원칙 추가 |
| 2026-04-17 | 구조 정비 | - | Kafka 토픽/파티션 Config 클래스로 이관, Jackson 3.x JacksonJsonSerializer/Deserializer 전환, core-service 패키지 평탄화, metadata→seat_held 리팩토링, StartupWarmer 제거(event-driven 대체), 데드 코드 정리, ARCHITECTURE.md/README.md 재작성 |
| 2026-04-20 | Phase 3: Kubernetes 전환 | [kubernetes-migration.md](kubernetes-migration.md) | Actuator 추가, K8s 매니페스트 전체 작성 (Deployment/Service/HPA/Ingress/ConfigMap/Secret), docker 프로파일 재사용, deploy/teardown 스크립트 |
| 2026-04-25 | Saga 오케스트레이션 전환 | - | 중앙 Orchestrator + 보상 + 타임아웃, CreatePaymentCommand/ProcessPaymentCommand, SagaTimeoutScheduler(분산 락), PaymentCommandConsumer, SagaResponseConsumer |
| 2026-04-25 | Outbox 패턴 도입 | - | OutboxEvent/Repository/Publisher/Service, 14곳 kafkaTemplate.send → outboxService.save, StringSerializer + __TypeId__ 헤더 |
| 2026-04-25 | DLQ 구현 | - | DefaultErrorHandler + DeadLetterPublishingRecoverer, DeadLetterConsumer (reserve/payment) |
| 2026-04-25 | 관측성 (Prometheus + Grafana) | - | micrometer-registry-prometheus, Prometheus/Grafana/Redis Exporter K8s 배포, Saga 커스텀 메트릭 |
| 2026-04-25 | 패키지 재구조화 | - | type/main/consumer/producer 경로 분리 (3개 서비스) |
| 2026-04-26 | 대기열 선차감 + 처리량 최적화 | - | enqueue.lua 원자적 선차감, validate_enqueue.lua RTT 통합, Kafka Consumer concurrency=10, HikariCP 30/Lettuce 풀, Producer 배치, Dispatch 200ms, SagaOrchestrator afterCommit 보상, SagaTimeoutScheduler 분산 락 보강 |
| 2026-04-26 | seat.events 토픽 제거 + 버그 수정 | - | SeatEventConsumer(no-op) 양쪽 서비스에서 제거, 중복 예약 방지(ALREADY_RESERVED), syncAllRemainingSeats 섹션 total/price 동기화 누락 수정, Grafana 대시보드 프로비저닝(Docker/K8s) |
