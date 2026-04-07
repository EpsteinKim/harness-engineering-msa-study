# Queue Service 명세

> 범용 요청 대기열 서비스. 도메인에 무관하게 요청을 큐잉하고, 스로틀링하여 콜백 URL로 전달한다.

---

## 1. 개요

| 항목 | 내용 |
|------|------|
| 서비스명 | queue-service |
| 포트 | 8080 (로컬, Docker 동일) |
| 기술 | Spring Boot 4.0.5 / MVC / Redis / Kotlin |
| 저장소 | Redis (Sorted Set + Hash) |
| 상태 | 운영중 |

---

## 2. 도메인 책임

- 요청 대기열 등록 (enqueue) — callbackUrl + payload 저장
- 스로틀링 기반 자동 dequeue (초당 N건, 설정 가능)
- 콜백 URL로 payload HTTP 전달
- 처리 타임아웃 관리 (10분 초과 시 재등록)
- 대기열 취소 (cancel)

---

## 3. API

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/queues/enqueue` | 대기열 등록 |
| DELETE | `/api/v1/queues/{userId}` | 대기열 취소 |
| POST | `/api/v1/queues/dequeue` | 수동 dequeue (디버깅용) |

### POST /api/v1/queues/enqueue

```json
// Request
{
  "userId": "user-1",
  "callbackUrl": "http://reserve-service:8080/api/v1/reservations",
  "payload": { "userId": "user-1", "eventId": 1, "seatId": 42 }
}

// Response
{
  "status": "success",
  "data": { "userId": "user-1", "position": 0 }
}
```

---

## 4. Redis 데이터 모델

| Key | Type | Score/Fields | 용도 |
|-----|------|-------------|------|
| `waiting-queue` | Sorted Set | score = 등록 시각 | 대기 중 요청 |
| `processing-queue` | Sorted Set | score = 처리 시작 시각 | 처리 중 요청 |
| `queue-request:{userId}` | Hash | callbackUrl, payload(JSON) | 요청 메타데이터 |

---

## 5. 스케줄러

| 주기 | 동작 |
|------|------|
| 1초 | waiting-queue에서 `queue.throttle.rate`건 dequeue → processing-queue 이동 → callbackUrl로 POST |
| 30초 | processing-queue에서 10분 초과 항목 → waiting-queue 맨 뒤로 재등록 |

---

## 6. 의존 관계

```
queue-service
├──► Redis (대기열 상태, 메타데이터)
└──► HTTP callback → 대상 서비스 (reserve-service 등)
```

---

## 7. 설정

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `queue.throttle.rate` | 10 | 초당 dequeue 건수 |
| `queue.callback.allowed-hosts` | (환경별) | 허용된 콜백 호스트 목록 |

---

## 변경 이력

| 날짜 | 변경 내용 |
|------|-----------|
| 2026-04-06 | 최초 작성 (초안) |
| 2026-04-07 | 실제 구현 반영: Redis 기반 범용 큐, 콜백 기반 처리, 스로틀링 |
