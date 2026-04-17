# Event 도메인 core-service 이관

완료일: 2026-04-17

## 요약

Event 엔티티 + 라이프사이�� + 조회 API를 reserve-service에서 core-service로 이관.
대용량 트래픽 관점에서 핫패스(Seat 예약)와 콜드패스(Event 관리)를 분리.

## 주요 ��경

- **Seat.event (@ManyToOne)** → **Seat.eventId (Long)** — FK 제거, Database per Service 원칙 준수
- **Event entity/repository** → core-service로 이동
- **EventLifecycleService** → core-service에서 직접 OPEN/CLOSE 실행 (tick 패턴 불필요)
- **EventService + EventController** → core-service `GET /api/v1/events`
- **SeatSyncService** 신규 — reserve-service에서 좌석 카운트 동기화 (SYNC tick 유지)
- **EventLifecycleConsumer** 신규 — `event.lifecycle` 토픽으로 EventOpened/EventClosed 수신
- **Redis 캐시 분담** — core-service: event 메타데이터, reserve-service: 좌석 카운트
- **Gateway** — `/api/v1/events/**` → core-service 라우팅 추가
- **CLAUDE.md** — 대용량 트래픽 관점 설계 원칙 추가
- **common/cache/EventCacheKeys.kt** — Redis 캐시 키 함수 공유 모듈로 이동
