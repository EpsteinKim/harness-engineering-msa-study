# Outbox 비동기화 + Producer 압축 (Kafka 파이프라인 2차)

> 1차(`dispatch-distribution.md`) 완료 후 진행. dispatch 자체는 분산됐으니 그 다음 단계는 발행(publish) 측 직렬 대기·압축·신뢰성.

## 진단 정정 (이전 버전 메모)

이 plan의 초기 가정은 "토픽 파티션이 명시되지 않아 broker 기본=1로 생성됐을 가능성"이었으나 **코드 확인 결과 NewTopic bean으로 이미 명시되어 있음** (reserve.queue=10, payment.events=10, payment.commands=10, system.ticks=1, event.lifecycle=1). 1차에서 락·컨슈머 동시성을 정상화했으니 이 plan은 발행 측에 집중.

## 목적

`OutboxPublisher.relay()`가 한 트랜잭션 안에서 SELECT FOR UPDATE → Kafka send → DB delete를 직렬로 수행. broker 지연이 길어지면 DB 락 점유 ↑. `QueueDispatchScheduler`의 `future.get()`도 동일 패턴. 이를 비동기 콜백으로 분리하면 dispatch tick·outbox polling이 broker 응답 시간에 묶이지 않음.

다만 **트랜잭션 분리·중복 발행 가능성 ↑ → 컨슈머 멱등성(known_gap #5) 선결 또는 동시 도입 필수**. 이 plan은 멱등성 묶음과 같이 진행.

## 변경 범위

- `common/.../outbox/OutboxPublisher.kt`
- `reserve-service/.../scheduler/QueueDispatchScheduler.kt` (1차에서 일부 수정됨, future.get() 부분 추가 변경)
- 3개 서비스 KafkaConfig (compression)
- (멱등성) reserve/payment-service Saga·Payment 컨슈머에 dedup 로직 추가
- (멱등성) Idempotency-Key 발행·검증 또는 saga 상태 기반 dedup

## 핵심 결정

1. **OutboxPublisher 트랜잭션 분리**:
   - SELECT FOR UPDATE는 짧게 — `published` 컬럼 추가로 마킹 후 별도 cleanup. 또는 `WHERE published_at IS NULL`로 SELECT 후 send 결과 콜백에서 update.
   - 멀티 파드 폴링 충돌 회피를 위해 분산 락 또는 단일 폴러 패턴 검토.
2. **Outbox 비동기 발행**:
   - `kafkaTemplate.send(...)` 결과를 `whenComplete` 콜백으로 처리.
   - 폴링 한 회차 안에서 N건을 비동기 dispatch 후 `allOf`로 마감 또는 별도 워커.
3. **QueueDispatchScheduler future.get() 제거**:
   - 콜백에서 `removeFromProcessing` 호출.
   - 실패 시 ZADD 복원 보장 (`QueueRecoveryScheduler`와 일관성).
   - 콜백 누적 메모리 폭증 방지를 위한 max-in-flight cap 또는 throttle.
4. **compression.type=lz4**: 3개 서비스 producer 모두.
5. **acks 정책 재검토**: 현재 acks=1. Saga DLQ 보강돼 있으니 유지. 단, 신뢰성 우려 시 acks=all + min.insync.replicas (broker 다중화 후).
6. **컨슈머 멱등성**:
   - reserve `SagaResponseConsumer`/`QueueConsumer`: saga 상태 기반 dedup (이미 IN_PROGRESS 또는 COMPLETED Saga면 skip).
   - payment `PaymentCommandConsumer`: Idempotency-Key 또는 sagaId 기반 dedup.

## 검증 (KPI)

- dispatch 처리량(events/sec) 1차 baseline 대비 N배 (목표: 3~5배).
- Saga 시작·완료 latency p95 감소 (Outbox polling floor 제거 효과).
- 부하 시 consumer lag 회복 시간 단축.
- 멱등성: 같은 commandId 중복 송신 시 Payment row 1개만 생성되는지 회귀 테스트.

## 롤백 절차

- 비동기화는 코드 분기 없이 직전 커밋 revert로 단일 롤백.
- 멱등성 변경은 컨슈머 측 dedup 추가라 회귀 위험 적음.

## 의존성

- 1차(`dispatch-distribution.md`) 완료
- P4(`loadtest-rebuild.md`) baseline 갱신 권장

## 예상 작업 분량

- Outbox 비동기·트랜잭션 분리: 1.5일
- 멱등성 dedup: 1일
- compression: 0.5일
- 부하 재실행: 0.5일
