# Kafka 학습 문서

> 이 프로젝트(harness-back)의 이벤트 구동 아키텍처에서 Kafka를 활용하기 위한 학습 문서.
> 대기열 처리·Payment Saga·HOLD 만료 등의 상태 전이를 Kafka 토픽 기반으로 전환하면서 정리.

---

## 1. Kafka란?

**Apache Kafka** — 분산 로그 기반 메시징 플랫폼.

- 메시지(이벤트)를 **파일로 저장하는 분산 로그**. 단순한 메시지 브로커가 아니라 "append-only 로그 저장소"
- **Producer**가 쓰고 **Consumer**가 읽는다. 읽는다고 사라지지 않음 (로그가 보존됨)
- **파티션**이라는 단위로 쪼개 수평 확장. **Consumer Group**으로 읽기도 분산
- 높은 처리량(초당 수십만~수백만 메시지), 밀리초 단위 지연

### 왜 이 프로젝트에 Kafka?

| 요구사항 | Kafka가 적합한 이유 |
|---------|-------------------|
| 대기열 병렬 처리 | Consumer Group으로 여러 pod에 파티션 자동 분배 |
| 서비스 간 이벤트 전달 | 발행자·구독자 디커플링, 장애 격리 |
| 순서 보장 (이벤트 내 FIFO) | 파티션 내 메시지 순서 유지 |
| 처리 실패 재시도 | offset 재설정으로 재소비 가능 |
| 이력 보존 | 메시지가 소비돼도 retention 기간 동안 남음 |

### 비슷한 대안과의 차이

| 항목 | Kafka | RabbitMQ | Redis Streams | SQS |
|------|-------|----------|---------------|-----|
| 모델 | 로그 (pull) | 큐 (push) | 로그 (pull) | 큐 (pull) |
| 처리량 | 매우 높음 | 중간 | 높음 | 중간 |
| 순서 보장 | 파티션 내 | 큐 내 | 스트림 내 | FIFO 큐만 |
| 메시지 보존 | retention 기간 | consume 즉시 제거 | maxlen 기반 | consume 즉시 |
| 생태계 | 큼 (Streams, Connect) | AMQP 표준 | Redis 내장 | AWS 종속 |
| 운영 복잡도 | 높음 | 낮음 | 낮음 | 관리형 |

---

## 2. 핵심 개념

### Topic — 메시지의 논리적 채널

```
topic: reserve.queue
topic: seat.events
topic: payment.events
```

- 이름만 붙인 "로그 스트림". 쓰레드처럼 주제별로 나눔
- producer는 topic에 append, consumer는 topic을 subscribe
- 이 프로젝트에선 도메인별 3개 토픽: 큐 처리(`reserve.queue`), 좌석 이벤트(`seat.events`), 결제 이벤트(`payment.events`)

### Partition — 병렬 처리의 단위

```
topic: reserve.queue
  ├─ partition 0: [msg1, msg2, msg6, ...]
  ├─ partition 1: [msg3, msg4, ...]
  └─ partition 2: [msg5, ...]
```

- 한 토픽은 N개 파티션으로 쪼개짐 (생성 시 지정, 나중에 늘리기만 가능 줄이기 불가)
- 각 파티션은 **append-only 로그 파일**
- 파티션 내에서는 **메시지 순서 보장**. 파티션 간에는 순서 없음
- 한 파티션은 **한 consumer instance**만 읽음 (같은 group 내에서)

**→ 파티션 수 = consumer group 내 최대 병렬도**

### Partition Key — 어느 파티션에 들어갈지 결정

```kotlin
kafkaTemplate.send(topic, key, message)
```

- key의 hash로 파티션 결정: `partition = hash(key) % partitionCount`
- **같은 key는 항상 같은 파티션** → 같은 key끼리는 순서 보장
- key가 null이면 round-robin

**이 프로젝트**: `key = "${eventId}:${userId % 10}"` — 이벤트 단위 FIFO 유지 + 이벤트 내 하위 파티셔닝

### Broker — Kafka 서버 한 대

- Kafka 클러스터는 여러 broker로 구성 (실습은 1개)
- 각 broker가 **일부 파티션의 leader** 역할
- producer/consumer는 broker에 연결해 데이터 주고받음

### Offset — 파티션 내 메시지의 위치

```
partition 0: offset 0 → offset 1 → offset 2 → ...
                 msg1      msg2      msg3
```

- 파티션 내 메시지의 **일련번호** (0부터 시작, 단조 증가)
- consumer는 자신이 어디까지 읽었는지 offset을 기억 (commit)
- 재시작해도 이어서 읽을 수 있음
- offset을 과거로 되돌리면 **재소비(reprocess)** 가능

### Consumer Group — 작업 분배 단위

```
consumer group: reserve-service
  ├─ pod A → partition 0, 1, 2
  ├─ pod B → partition 3, 4, 5
  └─ pod C → partition 6, 7, 8, 9
```

- 같은 `group.id`를 가진 consumer들이 하나의 그룹
- Kafka가 파티션을 그룹 내 consumer에게 자동 분배
- **한 파티션 = 한 consumer** (같은 그룹 내)
- consumer 추가/삭제 시 **rebalance**로 재분배
- 다른 `group.id`로 같은 토픽을 읽으면 **각자 독립적으로 모든 메시지 수신** → 한 토픽을 여러 서비스가 공유 구독 가능

### Replication — 내구성

```
topic: reserve.queue, replication-factor=3
partition 0:
  broker 1 → leader
  broker 2 → follower (replica)
  broker 3 → follower (replica)
```

- 각 파티션은 N개 복제본을 여러 broker에 분산
- **leader**가 읽기·쓰기 담당, **follower**는 복제만
- leader 장애 시 follower 중 하나가 승격
- 실습은 1 broker라 replication=1 (손실 가능, 학습용 OK)

### KRaft vs Zookeeper

- Kafka는 클러스터 메타데이터(파티션 리더, config, ACL 등) 저장소가 필요
- **Zookeeper 모드 (구)**: 외부 Zookeeper 앙상블 사용. 별도 운영 부담
- **KRaft 모드 (신)**: Kafka가 Raft 프로토콜로 자체 관리. Kafka 3.5+부터 기본, 3.3에서 GA
- 이 프로젝트: **KRaft single broker** — Zookeeper 없이 Kafka 컨테이너 하나

---

## 3. Producer 동작

### 기본 send

```kotlin
kafkaTemplate.send("reserve.queue", "1:5", enqueueMessage)
```

내부 흐름:
1. key/value를 직렬화 (이 프로젝트는 JSON)
2. 파티션 결정 (key hash)
3. 해당 파티션의 leader broker에 전송
4. broker가 로컬 로그에 append
5. replica에 복제 (동기 또는 비동기)
6. ack 반환

### 주요 설정

| 설정 | 값 | 의미 |
|------|-----|------|
| `acks` | `0` / `1` / `all` | 0: 보내고 끝. 1: leader 저장만 확인(기본). all: 모든 ISR 확인 (최고 내구성) |
| `retries` | 3~Integer.MAX_VALUE | 실패 시 자동 재시도 |
| `batch.size` | 16KB~ | 배치로 모아서 보내기 |
| `linger.ms` | 0~N | 배치를 채우기 위해 기다리는 시간 |
| `compression.type` | `none` / `gzip` / `snappy` / `lz4` / `zstd` | 압축 |
| `enable.idempotence` | `true` | 중복 전송 방지 (재시도 시 한 번만 기록) |

**이 프로젝트**: `acks=1` (속도·내구성 균형), 기본 batching, JSON 압축 없음.

### 비동기 send와 콜백

```kotlin
kafkaTemplate.send(topic, key, message).whenComplete { result, ex ->
    if (ex != null) logger.error("Send failed", ex)
    else logger.debug("Sent to offset ${result.recordMetadata.offset()}")
}
```

`KafkaTemplate.send()`는 `CompletableFuture` 반환. 기본은 비동기 fire-and-forget.

---

## 4. Consumer 동작

### 기본 subscribe

```kotlin
@KafkaListener(topics = ["reserve.queue"])
fun onMessage(message: EnqueueMessage) {
    // 한 건씩 처리
}
```

내부 흐름:
1. consumer가 broker에 "나 partition X의 다음 메시지 줘" poll
2. broker가 여러 메시지를 batch로 반환
3. Spring Kafka가 각 메시지를 listener 메서드에 전달
4. 처리 완료되면 offset commit (기본 auto-commit)

### Offset commit 전략

| 방식 | 설정 | 의미 |
|------|------|------|
| **Auto commit** | `enable.auto.commit=true` (기본) | N초마다 자동 커밋. 간단하지만 처리 중 장애 시 재처리 가능 |
| **Manual commit** | `AckMode.MANUAL` + `acknowledgment.acknowledge()` | 처리 후 명시적 커밋. 정확한 "at-least-once" 제어 |
| **Manual immediate** | `AckMode.MANUAL_IMMEDIATE` | 커밋 즉시 실행 (배치 안 함) |

**이 프로젝트**: 현재는 auto-commit. 이벤트 처리가 짧고 멱등성 확보 가능하면 충분.

### At-most-once / At-least-once / Exactly-once

| 시맨틱 | 설명 | Kafka에서 |
|--------|------|----------|
| **At-most-once** | 최대 한 번 (유실 가능) | 받자마자 commit → 처리 중 장애 시 유실 |
| **At-least-once** | 최소 한 번 (중복 가능) | 처리 후 commit → 장애 시 재처리 (멱등성 필요) |
| **Exactly-once** | 정확히 한 번 | transactional producer + consume-transform-produce 패턴 |

**이 프로젝트**: at-least-once + 멱등성 확보(동일 seatId PENDING 중복 생성 방지 등).

### Rebalance — Consumer 추가/삭제 시

```
group: reserve-service
pod A, B 운영 중 → pod C 조인

Kafka가 자동으로:
1. 모든 consumer에게 "멈춰" 신호
2. 파티션 재분배 계산
3. 새 할당 전달
4. consumer들 재시작
```

- 이 rebalance 중에는 **그룹 전체가 일시 정지**
- pod scale-out/scale-in 빈번하면 rebalance 빈도 증가 → 처리 끊김
- 해결: **static membership** (`group.instance.id` 고정) 또는 **cooperative rebalancing**(Incremental rebalance protocol)

---

## 5. 직렬화

Kafka의 메시지는 **바이트 배열**이다. key/value를 직렬화·역직렬화하는 건 클라이언트 책임.

### 주요 포맷

| 포맷 | 장점 | 단점 | 언제 |
|------|------|------|------|
| **String** | 단순 | 스키마 없음 | 키 (ID 등 단순 값) |
| **JSON** | 가독성, 스키마 없이 유연 | 크기 큼, 스키마 drift 위험 | 학습·MVP |
| **Avro + Schema Registry** | 스키마 강제, 작음, 진화 가능 | Registry 인프라 필요 | 운영 |
| **Protobuf** | 작고 빠름, 스키마 진화 가능 | Schema Registry 권장 | 성능 중요 |

**이 프로젝트**: Spring Kafka의 `JsonSerializer` / `JsonDeserializer` 사용. Jackson 자동 스캔.

### Trusted Packages (JSON 역직렬화 보안)

```properties
spring.kafka.consumer.properties.spring.json.trusted.packages=com.epstein.practice.reserveservice.event
```

- JsonDeserializer는 메시지 헤더의 `__TypeId__`로 역직렬화 대상 클래스를 찾음
- 악의적 클래스 역직렬화(RCE) 방지를 위해 신뢰 패키지를 명시
- 모든 패키지 허용: `*` (프로덕션 비권장)

---

## 6. Spring Kafka 사용법

### Producer

```kotlin
@Service
class SomeProducer(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {
    fun publish(event: MyEvent) {
        kafkaTemplate.send("topic.name", event.key, event)
    }
}
```

### Consumer

```kotlin
@Component
class SomeConsumer {
    @KafkaListener(topics = ["topic.name"], groupId = "my-service")
    fun onMessage(event: MyEvent) {
        // 처리
    }
}
```

타입을 자동 추론하기 위해 Spring Kafka는 `__TypeId__` 헤더를 본다. 필요 시 `@Payload`, `@Header` 등으로 제어.

### 설정 예시 (이 프로젝트)

```properties
# application.properties
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
spring.kafka.producer.acks=1
spring.kafka.consumer.group-id=reserve-service
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.properties.spring.json.trusted.packages=com.epstein.practice.reserveservice.event
```

### 토픽 선언

```kotlin
@Configuration
@EnableKafka
class KafkaConfig {
    @Bean
    fun reserveQueueTopic(): NewTopic =
        TopicBuilder.name("reserve.queue")
            .partitions(10)
            .replicas(1)
            .build()
}
```

Spring이 `KafkaAdmin`으로 토픽을 자동 생성. 이미 있으면 skip.

### auto-offset-reset

| 값 | 의미 |
|----|------|
| `earliest` | consumer group 처음 합류 시 토픽의 가장 오래된 메시지부터 |
| `latest` | 합류 이후 새 메시지만 |
| `none` | offset이 없으면 예외 |

**이 프로젝트**: `earliest` — 서비스 재시작 시 처리 못한 메시지 유실 방지.

### Listener 컨테이너 설정

```kotlin
@Bean
fun kafkaListenerContainerFactory(
    consumerFactory: ConsumerFactory<String, Any>
): ConcurrentKafkaListenerContainerFactory<String, Any> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, Any>()
    factory.consumerFactory = consumerFactory
    factory.setConcurrency(3)  // listener thread 수
    factory.containerProperties.ackMode = AckMode.MANUAL  // 수동 커밋 모드
    return factory
}
```

`setConcurrency(N)` = 내부적으로 N개의 consumer instance를 만듦. 파티션 수보다 많으면 남는 consumer는 놀음.

---

## 7. 실전 고려사항

### 멱등성 (Idempotency)

at-least-once + 재시도 환경에서는 **같은 메시지가 두 번 처리될 수 있음**. 이걸 막는 책임은 consumer에게 있음.

**패턴**:
1. **Upsert**: "이미 있으면 무시" (e.g., `INSERT ... ON CONFLICT DO NOTHING`)
2. **중복 감지 테이블**: `processed_message_ids` 테이블에 seen 기록
3. **상태 기반**: 이미 도달한 상태라면 다시 수행 안 함 (`if seat.status == RESERVED return`)

**이 프로젝트 예시** (Phase B에서 추가 예정):
```kotlin
// payment-service: SeatHeld 메시지 처리
fun onSeatHeld(event: SeatHeld) {
    val existing = paymentRepository.findBySeatIdAndStatus(event.seatId, PENDING)
    if (existing != null) return  // 이미 생성됨, skip
    paymentRepository.save(Payment(seatId = event.seatId, status = PENDING, ...))
}
```

### Dead Letter Topic (DLT)

처리 실패를 계속 반복하면 다른 메시지 처리가 막힘("poison pill"). 해결:
- N번 실패한 메시지를 **DLT(dead-letter-topic)**로 이동
- DLT는 별도로 조사·재처리

```kotlin
@KafkaListener(topics = ["reserve.queue"])
@RetryableTopic(
    attempts = "3",
    backoff = Backoff(delay = 1000, multiplier = 2.0),
    dltTopicSuffix = ".dlt"
)
fun onMessage(event: EnqueueMessage) { ... }
```

이 프로젝트는 아직 DLT 미도입 (후속 작업).

### Backpressure

consumer가 producer보다 느리면 lag이 쌓임. 모니터링은:
```bash
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group reserve-service --describe
```

lag가 계속 증가하면: pod 증설, 배치 크기 조정, 처리 로직 최적화.

### 트랜잭션 (Exactly-once)

consume-transform-produce 패턴에서 atomicity 필요 시:
```kotlin
@Transactional("kafkaTransactionManager")
fun process(event: InputEvent) {
    val result = transform(event)
    kafkaTemplate.send("output.topic", result)  // input offset commit + output produce가 원자적
}
```

producer에 `enable.idempotence=true` + `transactional.id` 필요. 복잡도 높아짐 → 실습엔 미도입.

### Retention

메시지를 얼마나 보관할까:
- `log.retention.hours` (기본 168 = 7일)
- `log.retention.bytes` — 파티션당 최대 크기

짧게 두면 디스크 절약 / 길게 두면 재처리 여지. 이 프로젝트 `reserve.queue`는 처리 후 의미 없음 → 1시간 정도로 짧게 설정 가능.

### 파티션 수 결정

- 너무 적으면: 병렬도 부족
- 너무 많으면: broker 부담, rebalance 느려짐, ZK/KRaft 메타데이터 증가
- 일반적 권장: **"예상 피크 처리량 / 단일 consumer 처리량" 약간 여유**
- **한 번 정하면 줄이기 불가**. 늘리기는 가능하지만 기존 파티션 hash 분배 깨짐

**이 프로젝트**: 10 파티션 (학습 기준). 실제 운영은 부하테스트 후 조정.

---

## 8. 로컬 CLI로 확인하기

Docker Compose의 kafka 컨테이너에 들어가 CLI 사용.

### 토픽 목록

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --list --bootstrap-server localhost:9092
```

### 토픽 상세

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh --describe \
  --topic reserve.queue \
  --bootstrap-server localhost:9092
```

출력 예:
```
Topic: reserve.queue PartitionCount: 10 ReplicationFactor: 1
  Topic: reserve.queue Partition: 0 Leader: 1 Replicas: 1 Isr: 1
  ...
```

### 메시지 보기 (consume)

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --topic reserve.queue \
  --from-beginning \
  --property print.key=true \
  --property print.headers=true \
  --bootstrap-server localhost:9092
```

### 메시지 넣기 (produce, 수동)

```bash
docker compose exec -it kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --topic reserve.queue \
  --property parse.key=true --property key.separator=: \
  --bootstrap-server localhost:9092

> 1:5:{"eventId":1,"userId":"5","seatId":null,"section":"A","joinedAt":1700000000000}
```

### Consumer Group 상태

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --group reserve-service \
  --describe \
  --bootstrap-server localhost:9092
```

출력:
```
GROUP           TOPIC         PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
reserve-service reserve.queue 0          105             120             15
reserve-service reserve.queue 1          99              99              0
...
```

`LAG` 값이 커지면 consumer가 밀리고 있는 것.

### Offset 되돌리기 (재처리)

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --group reserve-service \
  --topic reserve.queue \
  --reset-offsets --to-earliest --execute \
  --bootstrap-server localhost:9092
```

consumer group이 멈춘 상태에서만 실행 가능.

---

## 9. 이 프로젝트에서의 Kafka 활용

### Phase A (완료)

**토픽**: `reserve.queue` — 대기열 enqueue 메시지

**Producer**: `ReservationService.enqueue`
```kotlin
val key = "$eventId:${userIdLong % 10}"
kafkaTemplate.send(queueTopic, key, EnqueueMessage(...))
```

**Consumer**: `QueueConsumer`
```kotlin
@KafkaListener(topics = ["\${reserve.kafka.topic.queue}"])
fun onMessage(message: EnqueueMessage) {
    // 기존 DynamicScheduler.processEvent 로직 이식
}
```

**파티션 키 전략**: `eventId:userId%10`
- 같은 이벤트 + 같은 bucket = 같은 파티션 → 이벤트 내에서 "hash bucket 단위 FIFO"
- pod 늘려도 파티션 단위로 분배 → 병렬 처리

### Phase B (예정) — `seat.events`

좌석 상태 전이 이벤트:
```kotlin
data class SeatHeld(val seatId: Long, val userId: Long, val eventId: Long, val amount: Long, val heldUntilMs: Long)
data class SeatReleased(val seatId: Long, val userId: Long, val eventId: Long, val reason: String)
data class HoldExpired(val seatId: Long, val userId: Long, val eventId: Long)
```

- reserve-service가 발행
- payment-service가 SeatHeld 구독 → Payment(PENDING) 생성
- reserve-service 자신도 HoldExpired 구독 → 로컬 seat 상태 정리

### Phase C (예정) — `payment.events`

```kotlin
data class PaymentRequested(val seatId: Long, val userId: Long, val method: String)
data class PaymentSucceeded(val seatId: Long, val userId: Long, val paymentId: Long)
data class PaymentFailed(val seatId: Long, val userId: Long, val reason: String)
```

- `/pay` API가 PaymentRequested 발행 후 202 즉시 응답
- payment-service가 consume해 결제 처리, 결과 발행
- reserve-service가 결과 구독 → seat 상태 최종 전이

---

## 10. 이 프로젝트 전체 아키텍처에서 Kafka 위치

```
[Client] → [Gateway] → [reserve-service API pod]
                          │
                          ├─ Redis (seat state, event cache, position)
                          └─ Kafka producer (send to reserve.queue, seat.events, payment.events)

[Kafka cluster]
  ├─ reserve.queue    ← producer: reserve-service API
  │                   → consumer: reserve-service QueueConsumer
  ├─ seat.events      ← producer: reserve-service (QueueConsumer, 취소 등)
  │                   → consumer: payment-service (Payment 생성/EXPIRED)
  │                               reserve-service (HoldExpired self-consume)
  └─ payment.events   ← producer: reserve-service (/pay 호출 시)
                                  payment-service (결과 발행)
                      → consumer: payment-service (요청 처리)
                                  reserve-service (결과 받아 seat 상태 전이)

[reserve-service Worker pod] (같은 pod일수도 있음)
  └─ Kafka consumer (QueueConsumer, SeatEventConsumer, PaymentEventConsumer)
```

---

## 11. 학습 체크리스트

구현 중 아래 개념들이 코드에서 어떻게 드러나는지 찾아보면 이해가 빨라진다.

- [ ] `KafkaTemplate.send(topic, key, message)` — key 선택이 파티션에 어떻게 영향?
- [ ] `@KafkaListener(topics = ..., groupId = ...)` — groupId를 바꾸면 어떤 일이?
- [ ] 파티션 수보다 consumer instance 수가 많으면 어떻게 되는가?
- [ ] auto-commit 상태에서 consumer가 처리 중 crash하면 메시지는 어떻게 되나?
- [ ] 같은 key로 2개 메시지를 보냈는데 순서가 바뀔 수 있을까?
- [ ] `spring.json.trusted.packages`를 비워두면 어떤 오류가 나나?
- [ ] DB 커밋과 Kafka 발행이 모두 성공해야 하는 경우 어떻게 보장? (transactional outbox 패턴)

---

## 12. 더 읽어볼 만한 주제 (이 프로젝트 밖)

- **Kafka Streams** — 토픽 간 실시간 변환/집계
- **Kafka Connect** — DB·외부 시스템과 양방향 연동 (CDC 등)
- **Schema Registry + Avro** — 스키마 진화 관리
- **Exactly-once semantics** — Kafka Streams API, 트랜잭션
- **Transactional Outbox 패턴** — DB 커밋과 Kafka 발행 원자성
- **MirrorMaker 2** — 클러스터 간 복제
- **Strimzi** — K8s 위의 Kafka 오퍼레이터 (Phase 3에서 사용 예정)

---

## 정리: 이 프로젝트에서 Kafka 핵심 3가지

1. **파티션이 병렬성의 단위**. pod 수가 아니라 파티션 수가 상한.
2. **key가 순서·분배를 결정**. `eventId` 단독보다 `eventId:userId%K`가 이벤트 내 병렬성을 뽑아낸다.
3. **멱등성은 consumer가 책임**. at-least-once에서 재처리되어도 망가지지 않게 설계.
