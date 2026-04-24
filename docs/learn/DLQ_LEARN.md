# Dead Letter Queue (DLQ) 학습 문서

> 이 프로젝트(harness-back)의 Kafka consumer에서 처리 실패한 메시지를 격리하기 위한 학습 문서.
> Outbox(유실 방지) + 멱등성(중복 방지) + DLQ(실패 격리) = 이벤트 구동 안정성 완성.

---

## 1. DLQ란?

**처리할 수 없는 메시지를 별도 토픽으로 격리**하는 패턴.

```
정상 메시지: reserve.queue → consumer → 처리 성공 ✅
독 메시지:   reserve.queue → consumer → 실패 → 재시도 → 실패 → 재시도 → 실패
                                                                        ↓
                                                            reserve.queue.DLT (격리)
                                                                        ↓
                                                             나중에 수동 확인/재처리
```

DLT = Dead Letter Topic. Spring Kafka에서는 원본 토픽 이름에 `.DLT` 접미사를 붙인다.

---

## 2. DLQ가 없으면 생기는 문제

### 독 메시지 (Poison Message)

```
메시지: {"seatId": "abc", "userId": 1}  ← seatId가 문자열 (숫자여야 함)
→ 역직렬화 실패 → 예외 → 재시도 → 또 예외 → 무한 반복
→ 이 파티션의 다른 메시지도 전부 블로킹 ❌
```

### 비즈니스 로직 예외

```
메시지: CreatePaymentCommand(sagaId=999, seatId=100, ...)
→ sagaId=999가 DB에 없음 → NullPointerException
→ 재시도해도 계속 실패 (데이터가 없으니까)
→ 파티션 블로킹 ❌
```

### 외부 시스템 장애

```
메시지: ProcessPaymentCommand → PG사 API 호출 → 타임아웃
→ 재시도 → 타임아웃 → 재시도 → 타임아웃
→ 일시적 장애인데 무한 재시도 → 리소스 낭비
```

**핵심:** DLQ 없이는 한 개의 불량 메시지가 **파티션 전체를 마비**시킨다.

---

## 3. 재시도 가능 vs 불가능 구분

| 분류 | 예시 | 재시도 의미 |
|------|------|-----------|
| **재시도 불가 (non-retryable)** | 역직렬화 실패, 잘못된 데이터, 비즈니스 규칙 위반 | 몇 번을 해도 결과 같음 → 즉시 DLQ |
| **재시도 가능 (retryable)** | DB 타임아웃, 네트워크 일시 장애, 외부 API 장애 | 잠시 후 성공할 수 있음 → N번 재시도 후 DLQ |

---

## 4. Spring Kafka DLQ 구현

### 기본 설정

```kotlin
@Bean
fun kafkaListenerContainerFactory(
    consumerFactory: ConsumerFactory<String, Any>,
    kafkaTemplate: KafkaTemplate<String, Any>,
): ConcurrentKafkaListenerContainerFactory<String, Any> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, Any>()
    factory.setConsumerFactory(consumerFactory)
    factory.setCommonErrorHandler(
        DefaultErrorHandler(
            DeadLetterPublishingRecoverer(kafkaTemplate),  // 실패 시 .DLT로 발행
            FixedBackOff(1000L, 3)                         // 1초 간격 3회 재시도
        )
    )
    return factory
}
```

### 동작 흐름

```
메시지 수신 → 처리 시도
  성공 → offset 커밋 → 끝
  실패 → 1초 대기 → 재시도 1
  실패 → 1초 대기 → 재시도 2
  실패 → 1초 대기 → 재시도 3
  실패 → DeadLetterPublishingRecoverer
           → 원본 메시지를 {topic}.DLT로 발행
           → 원본 offset 커밋 (다음 메시지로 넘어감)
```

### 생성되는 DLT 토픽

| 원본 토픽 | DLT 토픽 |
|----------|---------|
| reserve.queue | reserve.queue.DLT |
| seat.events | seat.events.DLT |
| payment.events | payment.events.DLT |
| payment.commands | payment.commands.DLT |
| system.ticks | system.ticks.DLT |
| event.lifecycle | event.lifecycle.DLT |

---

## 5. 백오프 전략

### FixedBackOff (고정 간격)

```kotlin
FixedBackOff(1000L, 3)  // 1초 간격, 3회
// 실패 → 1초 → 실패 → 1초 → 실패 → 1초 → DLQ
```

### ExponentialBackOff (지수 백오프)

```kotlin
ExponentialBackOffWithMaxRetries(5).apply {
    initialInterval = 1000L    // 1초
    multiplier = 2.0           // 2배씩 증가
    maxInterval = 30_000L      // 최대 30초
}
// 실패 → 1초 → 실패 → 2초 → 실패 → 4초 → 실패 → 8초 → 실패 → DLQ
```

| 전략 | 적합한 경우 |
|------|-----------|
| 고정 간격 | 단순, 빠른 실패 격리 |
| 지수 백오프 | 외부 시스템 장애 (점진적 부하 감소) |

---

## 6. DLT 메시지 처리

DLT에 들어간 메시지는 **자동으로 처리되지 않는다.** 수동 또는 별도 프로세스로 처리해야 한다.

### 수동 확인

```bash
# DLT 토픽 메시지 확인
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic payment.commands.DLT \
  --from-beginning \
  --property print.headers=true
```

### DLT Consumer (자동 재처리)

```kotlin
@KafkaListener(topics = ["payment.commands.DLT"])
fun onDeadLetter(record: ConsumerRecord<String, Any>) {
    logger.error("Dead letter received: topic={}, key={}, value={}",
        record.topic(), record.key(), record.value())
    // 알림 발송 (Slack, 이메일 등)
    // 또는 수정 후 원본 토픽에 재발행
}
```

### 실무 운영 패턴

```
DLT 메시지 → 모니터링 알림 (Grafana/PagerDuty)
  → 운영자 확인 → 원인 분석
    → 코드 버그: 핫픽스 후 DLT 메시지 재처리
    → 데이터 오류: 데이터 수정 후 재발행
    → 일시 장애: 원본 토픽에 재발행 (재시도)
```

---

## 7. DLQ에 들어가는 메시지의 메타데이터

Spring Kafka의 `DeadLetterPublishingRecoverer`는 원본 메시지에 헤더를 추가한다:

| 헤더 | 내용 |
|------|------|
| `kafka_dlt-original-topic` | 원본 토픽 이름 |
| `kafka_dlt-original-partition` | 원본 파티션 |
| `kafka_dlt-original-offset` | 원본 offset |
| `kafka_dlt-exception-fqcn` | 예외 클래스명 |
| `kafka_dlt-exception-message` | 예외 메시지 |
| `kafka_dlt-exception-stacktrace` | 스택트레이스 |

이 정보로 **왜 실패했는지, 어디서 왔는지** 추적 가능.

---

## 8. 현재 프로젝트에 적용하면

### 적용 대상 (DLQ 설정이 필요한 서비스)

| 서비스 | KafkaConfig 위치 | consumer 수 |
|--------|-----------------|-------------|
| reserve-service | `config/KafkaConfig.kt` | 5개 (Queue, Seat, Saga, Tick, Lifecycle) |
| payment-service | `config/KafkaConfig.kt` | 3개 (Command, Event, Seat) |

core-service는 consumer가 없으므로 DLQ 불필요.

### 구현 변경점

```
변경 전: kafkaListenerContainerFactory에 ErrorHandler 없음
변경 후: DefaultErrorHandler + DeadLetterPublishingRecoverer 추가
```

각 서비스의 `KafkaConfig.kt`에서 `kafkaListenerContainerFactory` 빈에 ErrorHandler만 추가하면 된다. Consumer 코드 변경 없음.

---

## 9. DLQ vs 재시도 vs 무시 — 판단 기준

```
메시지 처리 실패 시:

이 메시지가 재시도하면 성공할 수 있는가?
  YES → 재시도 (backoff)
    N번 재시도 후에도 실패?
      YES → DLQ (격리)
      NO  → 성공 → 정상 처리
  NO  → 즉시 DLQ (재시도 의미 없음)

DLQ의 메시지를 무시해도 되는가?
  YES → 로그만 남기고 삭제
  NO  → 알림 + 수동 처리
```

---

## 10. 전체 이벤트 안정성 계층

```
Producer:
  Outbox 패턴 → "반드시 발행한다" (유실 방지)

Broker:
  Kafka 로그 보존 → "발행된 메시지는 retention 동안 보존"

Consumer:
  멱등성 → "중복 수신해도 결과가 같다" (중복 방지)
  DLQ → "처리 불가 메시지는 격리한다" (블로킹 방지)
  
Orchestrator:
  Saga 상태 추적 → "어디서 멈췄는지 안다" (장애 복구)
  타임아웃 → "멈춘 흐름을 자동 보상한다" (자가 치유)
```

**4개 패턴이 합쳐져야 이벤트 구동 아키텍처의 안정성이 완성된다.**

---

## 참고 자료

- [Spring Kafka Error Handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
- [Dead Letter Queue Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/dead-letter-queue)
