# Redis 대기열 잔재(Stale Entry) 이슈

## 현상

QueueConsumer가 좌석 배정 + Saga 시작까지 완료했지만, Redis 대기열(`reservation:waiting:{eventId}`)에서 해당 유저가 제거되지 않는 케이스 발생.

유저 관점: Saga는 COMPLETED인데 대기열 조회 시 `inQueue: true` 반환.

## 원인

`removeFromWaiting()`이 DB 트랜잭션 밖에서 실행되기 때문.

```
QueueConsumer.onMessage()
  → 좌석 배정 (DB, @Transactional)
  → removeFromWaiting()       ← Redis ZREM (트랜잭션 밖)
  → sagaOrchestrator.startSaga() (DB + Outbox)
```

DB 커밋 성공 후 Redis ZREM이 실패하면 (네트워크 순간 끊김, 타임아웃, pod 재시작 등) 대기열에 잔재가 남는다.

Redis 작업은 트랜잭션에 포함시킬 수 없으므로 구조적으로 불일치가 발생할 수 있다.

## 영향

- 유저가 대기열에 잔류 → 재enqueue 시 `ALREADY_IN_QUEUE` 거부
- 프론트에서 대기열 상태를 잘못 표시
- 데이터 정합성 위반 (Redis ↔ DB 불일치)

## 발생 빈도

부하 테스트 중 드물게 발생. 정상 운영에서는 거의 발생하지 않으나, pod 롤링 업데이트 중 발생 가능성 있음.

## 해결 방안

### 방안 1: 보정 스케줄러 (권장)

주기적으로 대기열을 스캔하여 이미 Saga가 종료된 유저를 제거.

```
StaleQueueCleanupScheduler (10~30초 주기)
  → OPEN 이벤트별 대기열 userId 목록 조회 (Redis ZRANGE)
  → DB에서 해당 userId의 최신 Saga 상태 확인
  → COMPLETED/FAILED/EXPIRED/CANCELLED이면 Redis ZREM
```

- 장점: 기존 구조 변경 없음, 콜드패스라 DB 부하 미미
- 단점: 정리까지 최대 스케줄러 주기만큼 지연

### 방안 2: Redis 큐 직접 소비 (ZPOPMIN)

Kafka 대신 Redis ZSET에서 직접 pop하여 큐 제거와 처리를 원자적으로 수행.

- 장점: 큐 제거와 처리가 원자적
- 단점: Kafka 파티션 분배 + consumer group 이점 상실, 아키텍처 대폭 변경

### 방안 3: enqueue 시 대기열 + Saga 이중 검증

`hasActiveSaga()` 체크가 이미 있으므로, 대기열에 잔재가 있어도 Saga 상태로 판단.

- 장점: 추가 구현 없음
- 단점: `isInQueue` 체크가 먼저 실행되어 `ALREADY_IN_QUEUE`로 거부됨 → 근본 해결 안 됨

## 결정

미정. 방안 1(보정 스케줄러) 유력.
