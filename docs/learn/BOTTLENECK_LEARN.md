# 대기열 시스템 병목 분석 및 최적화 학습 문서

> 이 프로젝트(harness-back)의 enqueue 부하테스트에서 발견된 병목 현상을 분석하고,
> 20,000+ TPS 목표를 달성하기 위한 최적화 전략을 학습하는 문서.

---

## 1. 문제 정의

부하테스트에서 **잔여 좌석(`remainingSeats`)이 사라지는 속도가 느리다.**

20,000명이 동시에 enqueue하면, 좌석이 즉시 줄어야 하는데 1-2초간 그대로 유지된다.
그 사이에 이미 매진된 좌석에 사람이 계속 몰린다.

---

## 2. 현재 파이프라인 추적

문제를 이해하려면 "enqueue 요청이 실제로 좌석 수를 줄이기까지" 거치는 경로를 정확히 알아야 한다.

### 2.1 요청 경로 전체 그림

```
[Client] POST /api/v1/reservations
    │
    ▼
[ReservationController.enqueue()]
    │  ① validateEnqueue()     ← Redis 5-6회 + HTTP 1회
    │  ② holdSeatIfNeeded()    ← Redis Lua 1회 (SEAT_PICK만)
    │  ③ queueCache.enqueue()  ← Redis Lua 1회 (ZADD + HSET)
    │  ④ getPosition()         ← Redis 1회 (ZRANK)
    │
    ▼ remainingSeats: 변화 없음 ❌
    
[QueueDispatchScheduler] (1초 주기)
    │  ⑤ popForDispatch()  ← Redis Lua (ZPOPMIN)
    │  ⑥ kafkaTemplate.send() × N건 → future.get() 블로킹
    │
    ▼ remainingSeats: 여전히 변화 없음 ❌

[QueueConsumer] (Kafka consumer)
    │  ⑦ seatService.reserveBySeatId/Section()  ← DB 트랜잭션
    │  ⑧ eventCache.adjustSeatCounts(-1)        ← ⭐ 여기서만 감소
    │  ⑨ sagaOrchestrator.startSaga()           ← DB INSERT (saga + outbox)
    │
    ▼ remainingSeats: 드디어 -1 ✅
```

### 2.2 타임라인으로 보는 지연

```
T+0ms        20,000명 동시 enqueue
             → Redis ZADD만 실행
             → remainingSeats = 10,000 (변화 없음)

T+0~1000ms   QueueDispatchScheduler 대기 중 (1초 주기)
             → 아직 Kafka로 발송 안 됨
             → remainingSeats = 10,000 (변화 없음)

T+1000ms     스케줄러 기동 → 20,000건 Kafka 발송
             → Consumer가 메시지 수신 시작

T+1000ms~    Consumer가 1건씩 처리 (concurrency=1!)
             → DB 트랜잭션 5ms × 20,000건 = 100초
             → remainingSeats가 극히 천천히 감소
```

**핵심 발견:** `remainingSeats` 감소가 파이프라인의 **맨 마지막 단계**에서만 일어난다.

---

## 3. 병목 지점별 분석

### 3.1 병목 ①: 후차감 구조 (가장 치명적)

```kotlin
// QueueConsumer.kt:58-59 — 여기가 유일한 감소 지점
if (result.success) {
    eventCache.adjustSeatCounts(eventId, -1, result.section)  // ← 여기서만!
}
```

```kotlin
// EventCacheRepository.kt:57-59
fun adjustSeatCounts(eventId: Long, delta: Long, section: String? = null) {
    hashOps.increment(eventCacheKey(eventId), "remainingSeats", delta)
    section?.let { hashOps.increment(eventCacheKey(eventId), sectionAvailableField(it), delta) }
}
```

**왜 문제인가?**

| 시점 | remainingSeats | 실제 남은 좌석 | 괴리 |
|------|---------------|--------------|------|
| enqueue 직후 | 10,000 | 10,000 (아직 안 빠짐) | 0 |
| 20K enqueue 완료 | 10,000 | ~10,000 (아직 queue에만 있음) | 0 |
| consumer 1,000건 처리 | 9,000 | 9,000 | 0 |

표면적으로 정합성은 맞다. 하지만 **사용자 경험 관점에서** 문제다:
- 20K명이 enqueue했는데 잔여석은 10K로 표시
- 이미 10K명이 큐에서 대기 중이니 나머지 10K명은 사실상 좌석을 못 받음
- 그런데 잔여석이 10K로 보이니까 **계속 새로운 사람이 enqueue**

이걸 **"Ghost Availability" (유령 잔여석)** 문제라고 부를 수 있다.

### 3.2 병목 ②: Kafka Consumer concurrency = 1

```kotlin
// KafkaConfig.kt:62-76
fun kafkaListenerContainerFactory(...): ConcurrentKafkaListenerContainerFactory<String, Any> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, Any>()
    factory.setConsumerFactory(consumerFactory)
    // setConcurrency() 호출 없음! → 기본값 1
    ...
}
```

**`reserve.queue` 토픽은 10개 파티션**인데, consumer 스레드는 **1개**.

```
파티션 0 ─┐
파티션 1 ─┤
파티션 2 ─┤
...       ├──→ [스레드 1개] → DB 트랜잭션 → 순차 처리
파티션 8 ─┤
파티션 9 ─┘
```

1 스레드가 10개 파티션을 돌아가며 소비. DB 트랜잭션 1건에 ~5ms라면:

```
단일 스레드: 1000ms / 5ms = 200 TPS (Consumer 처리량)
10 스레드:   200 × 10 = 2,000 TPS
```

**Consumer가 200 TPS밖에 못 처리하는데 20,000 TPS로 들어오면?** → Kafka consumer lag 폭증.

#### ConcurrentKafkaListenerContainerFactory의 동작 원리

```
setConcurrency(N) 호출 시:

N개의 KafkaMessageListenerContainer 생성
  → 각 컨테이너가 독립 Consumer 인스턴스 보유
  → Kafka Consumer Group Protocol로 파티션 분배
  
concurrency=10, partitions=10인 경우:
  Container-0 → Partition-0
  Container-1 → Partition-1
  ...
  Container-9 → Partition-9
  
각 Container는 별도 스레드에서 poll() → process 반복
```

**주의:** `concurrency`는 파티션 수를 초과해도 의미 없다. 파티션이 10개면 최대 10개 스레드만 활성.

### 3.3 병목 ③: validateEnqueue의 과도한 Redis RTT

```kotlin
// ReservationService.kt:38-62
private fun validateEnqueue(...) {
    if (!userClient.exists(userIdLong))                    // RTT 1: Redis hasKey (+ HTTP fallback)
    if (!eventCache.exists(eventId))                       // RTT 2: Redis hasKey
    if (queueCache.isInQueue(eventId, userId))             // RTT 3: Redis ZSCORE
    if (eventCache.getRemainingSeats(eventId) <= 0)        // RTT 4: Redis HGET
    val selectionType = eventCache.getSeatSelectionType(eventId)  // RTT 5: Redis HGET
    // SECTION_SELECT인 경우:
    if (eventCache.getSectionAvailable(eventId, section!!) <= 0)  // RTT 6: Redis HGET
}
```

**6회 Redis 왕복.** Redis RTT가 0.1ms라도, 20K TPS에서는:

```
20,000 × 6 × 0.1ms = 12,000ms = 12초 분량의 Redis 시간
```

#### Redis Lua 스크립트로 RTT를 줄이는 원리

Redis는 **단일 스레드**다. 명령을 하나씩 처리한다.

```
[Client]                    [Redis]
  HGET event:1 remainingSeats  →  처리  →  응답
  HGET event:1 seatSelectionType  →  처리  →  응답
  ZSCORE reservation:waiting:1 user1  →  처리  →  응답
```

각 명령마다 네트워크 왕복(RTT)이 발생한다. RTT가 0.1ms여도 6회면 0.6ms.

**Lua 스크립트는 여러 명령을 서버 내부에서 실행한다:**

```
[Client]                    [Redis]
  EVAL lua_script keys args  →  (서버 내부에서)
                                  HGET
                                  ZSCORE
                                  HGET
                                →  결과 한 번에 응답
```

RTT 1회로 끝난다. 대신 Redis가 Lua를 실행하는 동안 다른 클라이언트를 블로킹하므로, 스크립트는 짧고 빠르게 작성해야 한다.

### 3.4 병목 ④: Kafka Producer 배치 미설정

```kotlin
// KafkaConfig.kt:33-38
val props = mapOf<String, Any>(
    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JacksonJsonSerializer::class.java,
    ProducerConfig.ACKS_CONFIG to "1",
    // batch.size, linger.ms 설정 없음!
)
```

#### Kafka Producer의 배치 메커니즘

```
linger.ms = 0 (기본값):
  메시지 도착 즉시 전송 → 메시지 1건 = 네트워크 요청 1회

  send(msg1) → [네트워크] → broker
  send(msg2) → [네트워크] → broker
  send(msg3) → [네트워크] → broker
  
linger.ms = 5:
  5ms 동안 도착한 메시지를 모아서 한 번에 전송

  send(msg1) ─┐
  send(msg2) ─┤ 5ms 대기
  send(msg3) ─┘ → [네트워크 1회] → broker
```

| 설정 | 기본값 | 의미 |
|------|--------|------|
| `linger.ms` | 0 | 배치 대기 시간 (ms). 0이면 즉시 전송 |
| `batch.size` | 16384 (16KB) | 배치 최대 크기. 꽉 차면 linger 안 기다리고 즉시 전송 |
| `buffer.memory` | 33554432 (32MB) | 전체 전송 버퍼. 꽉 차면 send() 블로킹 |

**QueueDispatchScheduler에서 10만 건을 보내는데 배치가 없으면?**

```
100,000 × (직렬화 + 네트워크 RTT) = 수십 초
```

배치를 켜면 같은 파티션 대상 메시지가 묶여서 네트워크 효율이 3-5배 향상된다.

### 3.5 병목 ⑤: HikariCP 커넥션 풀 부족

```properties
# application-docker.properties
spring.datasource.hikari.maximum-pool-size=15
spring.datasource.hikari.minimum-idle=5
```

#### HikariCP 풀 사이즈 산정 공식

```
필요 커넥션 = (Kafka Consumer 스레드) + (Outbox Publisher) + (스케줄러) + (HTTP 요청 여유분)
```

현재: Consumer 1 + Outbox 1 + Scheduler 2 + HTTP 5 = **9** → 15 풀이면 여유 있음.

B1 적용 후 (concurrency=10): Consumer 10 + Outbox 1 + Scheduler 2 + HTTP 5 = **18** → 15 풀이면 **부족!**

```
Consumer 스레드 10개가 동시에 DB 트랜잭션 요청
  → HikariCP에 15개 커넥션
  → 나머지 3개로 Outbox + Scheduler + HTTP 처리
  → 커넥션 고갈 시 대기(blocking) → 전체 처리량 저하
```

#### 풀 사이즈 과다도 문제

PostgreSQL은 `max_connections=200`으로 설정되어 있다.
서비스 3개 × 풀 30 = 90 < 200이므로 안전하다.

하지만 풀을 너무 크게 잡으면:
- PostgreSQL 서버의 메모리 소비 증가 (커넥션당 ~10MB)
- 컨텍스트 스위칭 비용 증가
- 일반적으로 `CPU 코어 × 2 + 디스크 수`가 최적이라는 경험 법칙이 있다

### 3.6 병목 ⑥: Lettuce 커넥션 풀 미설정

Spring Data Redis의 기본 Lettuce 클라이언트는 **단일 커넥션**을 공유한다.

```
Lettuce 기본 동작:
  [Virtual Thread 1] ─┐
  [Virtual Thread 2] ─┤
  [Virtual Thread 3] ─┼──→ [단일 TCP 커넥션] ──→ [Redis]
  ...                 │
  [Virtual Thread N] ─┘
```

Lettuce는 Netty 기반 비동기 I/O라서 단일 커넥션으로도 높은 처리량이 가능하다.
하지만 20K TPS에서는 단일 TCP 소켓의 대역폭이 병목이 될 수 있다.

**커넥션 풀 활성화 시:**

```
[Thread 1] → [Connection 1] ─┐
[Thread 2] → [Connection 2] ─┼──→ [Redis]
[Thread 3] → [Connection 3] ─┘
```

여러 TCP 소켓으로 분산하여 네트워크 대역폭을 확보한다.

```properties
spring.data.redis.lettuce.pool.enabled=true
spring.data.redis.lettuce.pool.max-active=32   # 동시 활성 커넥션
spring.data.redis.lettuce.pool.max-idle=16     # 유휴 커넥션 유지
spring.data.redis.lettuce.pool.min-idle=8      # 최소 유휴 커넥션
```

**주의:** 풀을 사용하려면 `commons-pool2` 라이브러리가 필요하다. `spring-boot-starter-data-redis`에 transitive로 포함되는 경우가 많지만 확인 필요.

---

## 4. 해결 전략: 선차감 (Pre-Decrement)

### 4.1 핵심 아이디어

**"좌석 수를 Kafka Consumer가 아닌 enqueue 시점에서 미리 줄인다."**

```
Before (후차감):
  enqueue → [queue] → dispatch → consumer → DB 예약 → adjustSeatCounts(-1)
  
After (선차감):
  enqueue → adjustSeatCounts(-1) + [queue] → dispatch → consumer → DB 예약
```

enqueue.lua 안에서 ZADD와 remainingSeats 감소를 **원자적으로** 실행한다.

### 4.2 Lua 스크립트 설계

```lua
-- 수정 후 enqueue.lua
-- KEYS[1]: reservation:waiting:{eventId}   (대기열 ZSET)
-- KEYS[2]: reservation:dispatch:{eventId}  (디스패치 데이터 HASH)
-- KEYS[3]: event:{eventId}                 (이벤트 캐시 HASH) ← 새로 추가
-- ARGV[1]: userId
-- ARGV[2]: score (timestamp)
-- ARGV[3]: dispatchValue ("seatId|section")
-- ARGV[4]: section (빈 문자열이면 없음)

-- 1단계: 잔여석 확인
local remaining = tonumber(redis.call('HGET', KEYS[3], 'remainingSeats') or '0')
if remaining <= 0 then return -1 end  -- SOLD_OUT

-- 2단계: 섹션별 잔여석 확인 (SECTION_SELECT인 경우)
if ARGV[4] ~= '' then
    local secKey = 'section:' .. ARGV[4] .. ':available'
    local secAvail = tonumber(redis.call('HGET', KEYS[3], secKey) or '0')
    if secAvail <= 0 then return -2 end  -- SECTION_FULL
end

-- 3단계: 대기열 등록 (NX = 이미 있으면 실패)
local added = redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
if added == 0 then return 0 end  -- ALREADY_IN_QUEUE (선차감 안 했으므로 롤백 불필요)

-- 4단계: 잔여석 감소 (선차감)
redis.call('HINCRBY', KEYS[3], 'remainingSeats', -1)
if ARGV[4] ~= '' then
    redis.call('HINCRBY', KEYS[3], 'section:' .. ARGV[4] .. ':available', -1)
end

-- 5단계: 디스패치 데이터 저장
redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
return 1  -- SUCCESS
```

**왜 원자적인가?**

Redis Lua 스크립트는 Redis의 단일 스레드에서 중단 없이 실행된다.
스크립트 실행 중 다른 명령이 끼어들 수 없다.

```
Thread A: EVAL enqueue.lua ...  → HGET remainingSeats(10) → ZADD → HINCRBY(-1) → 완료
Thread B: EVAL enqueue.lua ...  → (Thread A 완료까지 대기) → HGET remainingSeats(9) → ...
```

두 요청이 동시에 `remainingSeats=10`을 보고 둘 다 통과하는 경쟁 조건은 불가능하다.

### 4.3 반환 코드 설계

| 반환값 | 의미 | Kotlin 처리 |
|--------|------|------------|
| 1 | 성공 (enqueue + 선차감 완료) | 정상 응답 |
| 0 | 이미 대기열에 있음 | ALREADY_IN_QUEUE 예외 |
| -1 | 잔여석 없음 | NO_REMAINING_SEATS 예외 |
| -2 | 해당 섹션 매진 | SECTION_FULL 예외 |

### 4.4 보상 (Compensation) 전략

선차감의 단점: **enqueue는 했지만 실제 DB 예약에 실패하면?** 좌석 수를 복원해야 한다.

#### 보상이 필요한 경로

```
경로 1: Consumer 예약 실패 (좌석 이미 점유, 낙관적 락 충돌)
  → adjustSeatCounts(eventId, +1, section)  ← 복원

경로 2: 사용자 취소 (cancel)
  → adjustSeatCounts(eventId, +1, section)  ← 복원

경로 3: Saga 보상 (결제 실패, 타임아웃)
  → 이미 SagaOrchestrator.compensate()에서 +1 하고 있음 ← 유지

경로 4: Consumer 예약 성공
  → 기존: adjustSeatCounts(-1) 제거 (이미 선차감됨)
  → 아무것도 안 함 ← 올바름
```

#### 정합성 분석

| 시나리오 | 선차감 | 최종 결과 | 정합성 |
|----------|--------|-----------|--------|
| enqueue → 예약 성공 | -1 | 좌석 점유 | ✅ |
| enqueue → 예약 실패 | -1 → +1 보상 | 좌석 복원 | ✅ |
| enqueue → 사용자 취소 | -1 → +1 보상 | 좌석 복원 | ✅ |
| enqueue → 서버 크래시 → 복구 | -1 (큐에 남아있음) | QueueRecoveryScheduler가 재큐잉, 재처리 | ✅ |
| enqueue → 큐에 영구 잔류 | -1 (미복원) | SeatSyncScheduler가 5분마다 DB 기준 보정 | ✅ (최종 정합) |

**최종 안전망:** `SeatSyncScheduler`가 5분 주기로 DB의 실제 AVAILABLE 좌석 수를 Redis에 덮어쓴다.
일시적 불일치가 있어도 결국 보정된다. 이것이 **최종 일관성 (eventual consistency)** 패턴이다.

---

## 5. ConcurrentKafkaListenerContainerFactory 심화

### 5.1 파티션 분배와 순서 보장

```
파티션 키: "eventId:userId % 10"
→ 같은 userId는 항상 같은 파티션으로 간다
→ 파티션 내에서는 순서가 보장된다

concurrency=10이면:
  Thread-0 → Partition-0 (userId % 10 == 0인 사용자들)
  Thread-1 → Partition-1 (userId % 10 == 1인 사용자들)
  ...
  Thread-9 → Partition-9 (userId % 10 == 9인 사용자들)
```

**같은 사용자의 메시지는 같은 스레드에서 순서대로 처리된다.** 순서 보장이 깨지지 않는다.

### 5.2 FOR UPDATE SKIP LOCKED와의 상호작용

```sql
SELECT * FROM seat
WHERE event_id = :eventId AND section = :section AND status = 'AVAILABLE'
ORDER BY id LIMIT 1
FOR UPDATE SKIP LOCKED
```

`SKIP LOCKED`는 다른 트랜잭션이 잠근 행을 건너뛰고 다음 행을 반환한다.

```
Thread-0: SELECT ... FOR UPDATE SKIP LOCKED → seat_id=1 잠금
Thread-1: SELECT ... FOR UPDATE SKIP LOCKED → seat_id=1 잠김 → skip → seat_id=2 잠금
Thread-2: SELECT ... FOR UPDATE SKIP LOCKED → seat_id=1,2 잠김 → skip → seat_id=3 잠금
```

10개 스레드가 동시에 같은 섹션에 접근해도 **서로 다른 좌석을 잡는다.**
대기(blocking) 없이 바로 다음 좌석으로 넘어가므로 처리량이 유지된다.

이것이 `FOR UPDATE`와 `FOR UPDATE SKIP LOCKED`의 결정적 차이다:

| | FOR UPDATE | FOR UPDATE SKIP LOCKED |
|---|---|---|
| 잠긴 행 만남 | 대기 (블로킹) | 건너뛰기 |
| 동시성 | 낮음 (직렬화) | 높음 (병렬) |
| 순서 보장 | 엄격한 FIFO | 느슨한 순서 (앞 행 건너뜀) |
| 적합한 상황 | 금융 거래 (정확한 순서) | 대기열 처리 (처리량 우선) |

---

## 6. Kafka Producer 배치 메커니즘 심화

### 6.1 내부 동작 흐름

```
send(record) 호출
    │
    ▼
RecordAccumulator (메모리 버퍼)
    │
    ├─ 파티션별 배치(ProducerBatch)에 record 추가
    │
    ├─ 배치가 batch.size에 도달? → 즉시 전송 트리거
    │
    └─ linger.ms 경과? → 전송 트리거
    │
    ▼
NetworkClient (Sender 스레드)
    │
    └─ Broker에 ProduceRequest 전송 (배치 포함)
```

### 6.2 QueueDispatchScheduler의 경우

현재 코드:

```kotlin
val futures = entries.mapNotNull { entry ->
    Triple(entry, key, kafkaTemplate.send(TOPIC_RESERVE_QUEUE, key, message))
}
for ((entry, _, future) in futures) {
    future.get()  // 브로커 ACK 대기
    queueCache.removeFromProcessing(eventId, entry.userId)
}
```

`send()`는 비동기다. RecordAccumulator에 넣고 바로 반환한다.
`future.get()`이 실제 네트워크 I/O를 기다린다.

**현재 (linger=0):**
```
send(msg1) → 즉시 전송 → get() 대기 → ACK
send(msg2) → 즉시 전송 → get() 대기 → ACK
...
```

**개선 (linger=5ms):**
```
send(msg1) ─┐
send(msg2) ─┤ RecordAccumulator에 축적
...         │
send(msgN) ─┘ → 5ms 후 또는 batch.size 도달 시 한 번에 전송
get() × N  → 이미 전송 완료된 것들이므로 즉시 반환
```

배치가 없을 때 10만 건은 10만 번 네트워크 왕복이지만,
배치가 있으면 **파티션당 1번** × 10개 파티션 = **수백 번**으로 줄어든다.

### 6.3 acks 설정과의 관계

| acks | 의미 | 지연 | 안전성 |
|------|------|------|--------|
| 0 | 발송만 (ACK 안 기다림) | 최소 | 유실 가능 |
| 1 | 리더만 확인 | 중간 | 리더 장애 시 유실 가능 |
| all | 모든 ISR 확인 | 최대 | 유실 없음 |

현재 `acks=1`. 배치와 함께 사용하면 배치 내 모든 메시지의 ACK를 한 번에 받으므로, 메시지당 ACK 비용이 분산된다.

---

## 7. Virtual Thread와 병목의 관계

```properties
spring.threads.virtual.enabled=true
```

이 프로젝트는 Virtual Thread를 사용 중이다. 이것이 병목에 어떤 영향을 주는가?

### 7.1 Virtual Thread의 이점

```
기존 (Platform Thread):
  Thread Pool = 200개 → 동시 요청 200개 한계
  I/O 대기 중에도 스레드 점유 → 스레드 고갈

Virtual Thread:
  OS 스레드 풀 (캐리어) 위에 수백만 개의 경량 스레드 생성 가능
  I/O 대기 시 캐리어 스레드를 양보(unmount) → 다른 VT가 사용
```

20K 동시 요청이 들어와도 Virtual Thread 자체는 문제없다.
**하지만 Virtual Thread가 해결하지 못하는 것:**

```
① Redis/DB 커넥션 풀 크기  → VT가 많아도 커넥션이 15개면 15개만 동시 실행
② Redis 단일 스레드        → VT가 아무리 많아도 Redis는 명령을 하나씩 처리
③ 파이프라인 구조 문제     → VT는 "대기를 효율적으로" 하지, 대기 자체를 없애지 않음
```

Virtual Thread는 **스레드 풀 고갈 문제**를 해결하지만, **I/O 리소스 병목**은 해결하지 않는다.

---

## 8. 최적화 효과 예측

### Before (현재)

```
Enqueue TPS:        ~20,000 (Redis만이라 빠름)
remainingSeats 반영: 1-2초 후 (Consumer 처리 후)
Consumer 처리량:     ~200 TPS (concurrency=1, DB 5ms/건)
전체 파이프라인:     20K enqueue → 1초 대기 → 200 TPS 소화 → 100초 소요
```

### After (최적화 적용 후)

```
Phase A 적용 (선차감):
  remainingSeats 반영: 즉시 (0ms)
  → 매진 시 즉시 NO_REMAINING_SEATS 반환
  → 불필요한 enqueue 자체가 차단됨

Phase B 적용 (Consumer concurrency=10 + HikariCP 30 + Kafka batch):
  Consumer 처리량: ~2,000 TPS (10스레드 × 200 TPS)
  전체 파이프라인: 20K enqueue → 200ms 대기 → 2,000 TPS 소화 → 10초 소요

Phase C 적용 (Redis 풀 + 스케줄러 튜닝):
  Enqueue RTT 절반, 스케줄러 지연 200ms로 단축
```

---

## 9. 핵심 교훈 요약

### 9.1 "어디서 세는가"가 사용자 경험을 결정한다

잔여석 감소 로직의 위치가 파이프라인 앞이냐 뒤냐에 따라 사용자가 보는 세계가 완전히 달라진다.
후차감은 정합성 측면에서 안전하지만, 대용량 트래픽에서는 유령 잔여석 문제를 만든다.

### 9.2 Lua 스크립트 = Redis의 트랜잭션

Redis에는 전통적인 RDBMS 트랜잭션이 없다.
Lua 스크립트가 그 역할을 한다: 여러 명령을 원자적으로 실행하고, 중간에 조건 분기도 가능하다.

### 9.3 처리량 = min(각 단계의 처리량)

```
enqueue(Redis): 100K TPS
dispatch(스케줄러): 100K/초 (1초 주기, 10만 건 팝)
consumer(Kafka): 200 TPS ← 병목!
```

파이프라인의 처리량은 **가장 느린 단계**에 의해 결정된다. Consumer가 200 TPS면 전체가 200 TPS.

### 9.4 설정 하나가 10배 차이를 만든다

`setConcurrency(10)` 한 줄이 Consumer 처리량을 10배로 늘린다.
`linger.ms=5` 한 줄이 네트워크 요청을 수백 배 줄인다.
인프라 설정을 기본값으로 두지 말고, **자신의 워크로드에 맞게 튜닝**해야 한다.

### 9.5 최종 일관성은 안전망이다

선차감으로 일시적 불일치가 생길 수 있지만, `SeatSyncScheduler`가 주기적으로 DB 기준 보정한다.
실시간 정확성과 처리량은 트레이드오프 관계이며, **보정 메커니즘이 있으면 즉시 일관성을 완화**할 수 있다.

---

## 참고 자료

- [Redis Lua Scripting](https://redis.io/docs/interact/programmability/eval-intro/)
- [Kafka Producer Configuration](https://kafka.apache.org/documentation/#producerconfigs)
- [HikariCP Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [PostgreSQL SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
- [Spring Kafka Concurrency](https://docs.spring.io/spring-kafka/reference/)
- [Virtual Threads (JEP 444)](https://openjdk.org/jeps/444)
