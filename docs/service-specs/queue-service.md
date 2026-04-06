# Queue Service 명세

> 대기열 시스템의 핵심 서비스. 대기열 생성, 참가, 순번 조회, 상태 관리를 담당한다.

---

## 1. 개요

| 항목 | 내용 |
|------|------|
| 서비스명 | queue-service |
| 포트 | 8080 |
| 기술 | Spring Boot 4.0.5 / WebFlux / Kotlin Coroutines |
| DB | 미정 (RDB + Redis) |
| 상태 | 개발중 |

---

## 2. 도메인 책임

- 대기열(Queue) 생성 및 관리
- 대기열 참가(Enqueue) 및 이탈(Dequeue)
- 현재 순번 및 대기 인원 조회
- 대기열 상태 변경 (열림/닫힘/일시정지)

---

## 3. API 설계 (초안)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v1/queues` | 대기열 생성 |
| GET | `/api/v1/queues/{id}` | 대기열 정보 조회 |
| POST | `/api/v1/queues/{id}/join` | 대기열 참가 |
| DELETE | `/api/v1/queues/{id}/leave` | 대기열 이탈 |
| GET | `/api/v1/queues/{id}/position` | 내 순번 조회 |
| PATCH | `/api/v1/queues/{id}/status` | 대기열 상태 변경 |

> 상세 요청/응답 스펙은 구현 시 `docs/generated/api-spec.md`에 자동 생성.

---

## 4. 데이터 모델 (초안)

> DB 스키마 확정 시 반드시 사용자 승인 필요. (CONSTITUTION.md 문서 생성 정책 참조)

```
Queue
├── id: UUID
├── name: String
├── status: OPEN | CLOSED | PAUSED
├── maxSize: Int?
├── createdAt: Timestamp
└── updatedAt: Timestamp

QueueEntry
├── id: UUID
├── queueId: UUID (FK)
├── userId: String
├── position: Int
├── joinedAt: Timestamp
└── status: WAITING | PROCESSING | DONE | LEFT
```

---

## 5. 의존 관계

```
queue-service
├──► Redis (대기열 실시간 상태)
└──► RDB (대기열 메타데이터, 이력)
```

---

## 변경 이력

| 날짜 | 변경 내용 |
|------|-----------|
| 2026-04-06 | 최초 작성 (초안) |
