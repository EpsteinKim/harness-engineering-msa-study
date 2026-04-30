# Dispatch 분산 + payment 컨슈머 정상화 + Kafka 토폴로지 강건화

> Kafka 파이프라인 1차 튜닝. 원래 dispatch 락·payment concurrency 범위로 시작했으나, 진단 과정에서 토픽 파티션 미스매치·KafkaAdmin race 발견하여 토폴로지 강건화까지 함께 진행.

## 목적

원래 plan(`kafka-partitioning.md`)의 "토픽 파티션이 명시되지 않아 broker 기본=1"이라는 진단을 코드 확인으로 정정하려다, 더 깊은 문제를 발견:

1. 처음 식별한 1차 병목 셋:
   - `QueueDispatchScheduler.kt:42`의 분산 락이 글로벌 키 1개 → reserve 5 파드 중 1 파드만 dispatch 활성
   - `payment-service/.../KafkaConfig.kt`에 `setConcurrency` 누락 → payment.commands 처리량 1/10
   - `popForDispatch(eventId, 100000)` 한 번에 10만 건 pop → head-of-line

2. **부하 측정 중 발견한 심층 문제**:
   - reserve.queue 토픽이 **broker에 1 파티션으로 박혀 있음** (NewTopic bean이 10으로 명시했음에도)
   - 원인: Spring Kafka `KafkaAdmin`이 부팅 시 broker 미준비 race로 silent skip → publish가 broker auto-create 발동 → default 1 파티션
   - `fatalIfBrokerNotAvailable` default가 `false`라 silent skip 후 잘못된 토픽이 박혀버림

3. **추가 부하 측정 통찰**:
   - 단일 맥북 + 부하기 동거 조건에서 박스 CPU 100% 도달
   - gateway 1 → 3 replicas 변경이 RPS 1,200 → 3,032의 큰 부분 (HPA 못 쓰니 manifest 직접 수정)
   - per-event lock·payment concurrency 변경의 순수 효과는 다음 측정 환경 개선 후 분리 측정 필요

## 변경 범위

### 코드
- `reserve-service/.../scheduler/QueueDispatchScheduler.kt`: 락 키·오프셋·pop 상한 변경
- `reserve-service/.../config/KafkaConfig.kt`: 명시적 KafkaAdmin bean 추가
- `core-service/.../config/KafkaConfig.kt`: 명시적 KafkaAdmin bean 추가
- `payment-service/.../config/KafkaConfig.kt`: KafkaAdmin bean + setConcurrency(10) + setMissingTopicsFatal(false) + producer linger/batch 정렬
- `payment-service/src/main/resources/application.properties`: HikariCP 명시

### 매니페스트
- `kubernetes/apps/reserve-deployment.yaml`: initContainer wait-kafka 추가
- `kubernetes/apps/core-deployment.yaml`: initContainer wait-kafka 추가
- `kubernetes/apps/payment-deployment.yaml`: initContainer wait-kafka 추가
- `kubernetes/apps/gateway-deployment.yaml`: replicas 1 → 3 (HPA 미동작 보완)
- `kubernetes/infrastructure/kafka-statefulset.yaml`, `docker-compose.yml`: `KAFKA_NUM_PARTITIONS` 추가했다 제거 (default=1로 복귀)

## 핵심 결정과 결과

### 1. QueueDispatchScheduler — 락 키 이벤트별 + 오프셋 회전
- 글로벌 `lock:queue-dispatch` 단일 락 제거
- 이벤트 순회 안에서 `lock:queue-dispatch:{eventId}` 시도, 실패 시 `continue`
- 파드별 시작 오프셋: `podId.hashCode() % openEventIds.size` 만큼 회전
- 한 이벤트당 처리량 상한: `popForDispatch(eventId, 100000)` → `popForDispatch(eventId, 1000)`
- 부팅 시 podId·podHash INFO 로그 (운영 가시성)

### 2. payment-service 컨슈머 정상화
- `setConcurrency(10)` 추가 (10 파티션 활용)
- `setMissingTopicsFatal(false)` (reserve와 정렬)
- producer `LINGER_MS=5`, `BATCH_SIZE=65536` 정렬

### 3. payment-service HikariCP 명시
- `maximum-pool-size=20`, `minimum-idle=5`, `connection-timeout=5000`
- 컨슈머 10 동시성에 처리 여유 포함

### 4. KafkaAdmin 명시 bean (3개 서비스)
```kotlin
@Bean
fun kafkaAdmin(...) = KafkaAdmin(...).apply {
    setFatalIfBrokerNotAvailable(true)  // broker 못 붙으면 부팅 자체 실패
    setModifyTopicConfigs(true)         // config mismatch 자동 수정
}
```

### 5. initContainer로 broker readiness 대기
- `wait-kafka` busybox container가 `nc -z kafka 9092` 폴링
- main container는 broker 9092 열린 후에야 시작
- KafkaAdmin race 1차 방어선

### 6. KAFKA_NUM_PARTITIONS=10 워크어라운드 제거
- KafkaAdmin이 정상 동작하면 NewTopic bean이 권위 → broker default는 안전한 1로 충분
- DLT 등 NewTopic 없는 토픽이 우연히 10 파티션으로 만들어지는 문제 차단

## 최종 토픽 토폴로지 (검증됨)

```
reserve.queue:    10 (NewTopic 적용)
payment.commands: 10
payment.events:   10
system.ticks:      1
event.lifecycle:   1
모든 .DLT:         1 (default, NewTopic 없음 → 1 파티션, 메시지 양 적어 영향 미미)
```

## 학습 포인트

1. **Spring Kafka KafkaAdmin은 default가 silent skip**. broker 미준비 시 그냥 넘어가고 부팅 성공 → 잘못된 설정이 운영에 누적됨. `fatalIfBrokerNotAvailable=true`로 시끄럽게 fail해야 안전.
2. **initContainer만으론 부족**. 9092 포트 열림은 보장하지만 controller election·admin API 응답까지는 보장 안 함. KafkaAdmin 측 강건성 필요.
3. **단일 맥북·부하기 동거 조건에서는 측정값이 박스 CPU saturation으로 수렴**. 진정한 변경 효과 측정은 부하기 분리 후에야 가능.
4. **HPA가 OrbStack에서 동작 안 함** (metrics-server CPU 미관측). manifest replicas로 수동 관리 — 단일 인프라 진입점(gateway·ingress) 늘려야 진입 layer 병목 해소.

## 검증 스크립트

부하 중 락 검증:
```bash
bash reserve-service/loadtest/verify_lock.sh 30 0.3
```

토픽 파티션 검증:
```bash
kubectl exec kafka-0 -- /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe 2>&1 | \
  awk '/^Topic:/ && $2 !~ /^__/ {print $2": partitions="$6}'
```

## 미완·다음 묶음으로 미룬 항목

- **부하기 분리한 환경에서 변경 순수 효과 재측정** — 단일 박스 한계 때문에 이번엔 분리 측정 어려움
- **OutboxPublisher 트랜잭션 분리·비동기화** — 컨슈머 멱등성과 함께 (`kafka-partitioning.md`로 이관)
- **Producer compression=lz4** — 효과 측정 분리 위해 단독 묶음
- **JVM heap·container limits·tomcat threads** — P3 영역
