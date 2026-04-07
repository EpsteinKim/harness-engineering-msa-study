# Redis 학습 문서

> 이 프로젝트(harness-back)의 대기열 시스템에서 Redis를 활용하기 위한 학습 문서.

---

## 1. Redis란?

**Remote Dictionary Server** — 메모리 기반 key-value 데이터베이스.

- 모든 데이터를 메모리에 저장 → **sub-millisecond 응답**
- 단순한 캐시가 아니라 다양한 자료구조를 지원하는 데이터 서버
- 싱글 스레드 설계지만, 메모리 기반이라 매우 빠름

### 왜 대기열 시스템에 Redis?

| 요구사항 | Redis가 적합한 이유 |
|---------|-------------------|
| 실시간 순번 관리 | 메모리 기반, 즉각 응답 |
| 동시 접속 처리 | 원자적(atomic) 연산으로 경합 없음 |
| 순서 보장 | List, Sorted Set 등 순서 자료구조 제공 |
| 임시 데이터 | TTL 지원, 자동 만료 |

---

## 2. 핵심 자료구조

### String — 단순 값, 카운터

```redis
SET key value              # 저장
GET key                    # 조회
DEL key                    # 삭제
INCR counter               # 1 증가 (원자적)
EXPIRE key 3600            # 3600초 후 자동 삭제
TTL key                    # 남은 시간 확인
```

**활용**: 대기열 총 인원수, Rate Limiting 카운터

### List — 큐(FIFO)

```redis
LPUSH queue job1           # 왼쪽에 추가 (enqueue)
RPOP queue                 # 오른쪽에서 꺼냄 (dequeue)
BRPOP queue 5              # 블로킹 pop (5초 대기, 없으면 nil)
LRANGE queue 0 -1          # 전체 조회
LLEN queue                 # 큐 길이
```

**활용**: 단순 FIFO 대기열, 작업 큐

```
LPUSH →  [job3, job2, job1]  → RPOP
          왼쪽 삽입              오른쪽 추출 (선입선출)
```

### Hash — 객체 저장

```redis
HSET user:1 name "Alice" age 25   # 필드 저장
HGET user:1 name                   # 필드 조회
HGETALL user:1                     # 전체 조회
HDEL user:1 age                    # 필드 삭제
```

**활용**: 대기열 항목의 상태 추적 (status, enqueued_at, retry_count)

### Set — 중복 없는 집합

```redis
SADD tags redis nodejs     # 추가
SMEMBERS tags              # 전체 조회
SISMEMBER tags redis       # 멤버 여부 (1 or 0)
SREM tags nodejs           # 삭제
```

**활용**: 중복 참여 방지, 고유 사용자 추적

### Sorted Set — 정렬된 집합 (점수 기반)

```redis
ZADD queue 1000 user:1     # 점수(score)와 함께 추가
ZADD queue 1001 user:2
ZRANGE queue 0 -1          # 점수 오름차순 조회
ZREVRANGE queue 0 -1       # 점수 내림차순 조회
ZSCORE queue user:1        # 점수 조회
ZRANK queue user:1         # 순위 조회 (0부터)
ZREM queue user:1          # 삭제
```

**활용**: 대기열 순번 관리(score=타임스탬프), 우선순위 큐, 리더보드

```
ZADD waiting_queue <timestamp> <userId>
→ 먼저 들어온 사람이 낮은 score → ZRANGE로 순서대로 조회
→ ZRANK로 "내 앞에 몇 명?" 즉시 확인
```

### Stream — 내구성 있는 메시지 큐

```redis
XADD mystream * field value           # 메시지 추가 (*=자동 ID)
XREAD COUNT 1 STREAMS mystream 0      # 메시지 읽기
XGROUP CREATE mystream group 0        # 컨슈머 그룹 생성
XREADGROUP GROUP group consumer COUNT 1 STREAMS mystream >  # 그룹에서 소비
XACK mystream group <message-id>      # 처리 완료 확인
```

**활용**: 이벤트 로그, 신뢰성 있는 메시지 큐 (재시작해도 데이터 보존)

### 자료구조 선택 가이드

| 자료구조 | FIFO | 우선순위 | 내구성 | 컨슈머 그룹 | 복잡도 |
|---------|------|---------|--------|-----------|-------|
| **List** | O | X | X | X | 낮음 |
| **Sorted Set** | X | O | X | X | 중간 |
| **Stream** | O | O | O | O | 중간 |

이 프로젝트에서는 **Sorted Set**(순번 관리) + **Hash**(상태 추적) 조합이 적합.

---

## 3. Docker에서의 Redis

### 현재 프로젝트 구성

```yaml
# docker-compose.yml
redis:
  image: redis:7-alpine          # 경량 이미지 (~15MB)
  volumes:
    - redis-data:/data            # 데이터 영속화
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 10s                 # 10초마다 체크
    timeout: 5s                   # 5초 내 응답 없으면 실패
    retries: 3                    # 3번 실패하면 unhealthy
  networks:
    - harness-net                 # 내부 네트워크만
```

### 핵심 포인트

| 설정 | 설명 |
|------|------|
| `redis:7-alpine` | Alpine 기반 경량 이미지, 프로덕션에서도 사용 |
| `volumes: redis-data:/data` | Named volume — 컨테이너 삭제해도 데이터 유지 |
| `healthcheck` | 다른 서비스가 `depends_on: condition: service_healthy`로 대기 가능 |
| 포트 미노출 | 외부에서 접근 불가, Docker 내부 네트워크로만 통신 |

### 서비스에서 접근하는 법

Docker 내부에서는 **컨테이너명이 DNS**로 동작:

```yaml
# queue-service의 environment
SPRING_DATA_REDIS_HOST=redis    # "redis"가 곧 Redis 컨테이너 IP로 해석됨
```

### Docker에서 Redis CLI 사용

```bash
# Redis CLI 접속
docker compose exec redis redis-cli

# 직접 명령 실행
docker compose exec redis redis-cli PING
docker compose exec redis redis-cli INFO memory
docker compose exec redis redis-cli DBSIZE
docker compose exec redis redis-cli KEYS "*"

# 실시간 명령 모니터링 (디버깅용)
docker compose exec redis redis-cli MONITOR
```

---

## 4. Redis 영속화 (Persistence)

메모리 DB지만, 디스크에 저장하는 두 가지 방법이 있음:

### RDB (스냅샷)

```yaml
command: redis-server --save 60 1000
# 60초 동안 1000개 이상 키가 변경되면 스냅샷 저장
```

- 주기적으로 전체 메모리를 디스크에 덤프
- 복구가 빠름, 파일이 작음
- **단점**: 스냅샷 사이 데이터 유실 가능

### AOF (Append-Only File)

```yaml
command: redis-server --appendonly yes --appendfsync everysec
# 모든 쓰기 명령을 로그로 기록, 1초마다 디스크 동기화
```

- 모든 쓰기 연산을 기록
- 데이터 유실 최소화 (최대 1초)
- **대기열 시스템에 권장** — 메시지 유실 방지

### 현재 프로젝트 설정

기본 Redis 설정(RDB만 활성화). 대기열 데이터 보호가 필요하면:

```yaml
redis:
  image: redis:7-alpine
  command: redis-server --appendonly yes --appendfsync everysec
  volumes:
    - redis-data:/data
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 10s
    timeout: 5s
    retries: 3
  networks:
    - harness-net
```

---

## 5. Spring Boot + Redis 연동

### 의존성

```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

### 설정

```yaml
# application.yml
spring:
  data:
    redis:
      host: localhost       # Docker에서는 "redis"
      port: 6379
      database: 0           # Redis DB 인덱스 (0~15)
```

### RedisTemplate 사용

```kotlin
@Service
class QueueRedisService(
    private val redisTemplate: StringRedisTemplate
) {
    // String 연산
    fun increment(key: String): Long? =
        redisTemplate.opsForValue().increment(key)

    // List 연산 (FIFO 큐)
    fun enqueue(queue: String, value: String) {
        redisTemplate.opsForList().leftPush(queue, value)
    }

    fun dequeue(queue: String): String? =
        redisTemplate.opsForList().rightPop(queue)

    // Hash 연산 (상태 추적)
    fun setJobStatus(jobId: String, status: String) {
        redisTemplate.opsForHash<String, String>()
            .put("job:$jobId", "status", status)
    }

    // Sorted Set 연산 (순번 관리)
    fun addToWaitingQueue(userId: String, timestamp: Double) {
        redisTemplate.opsForZSet().add("waiting-queue", userId, timestamp)
    }

    fun getPosition(userId: String): Long? =
        redisTemplate.opsForZSet().rank("waiting-queue", userId)

    // TTL
    fun setWithExpiry(key: String, value: String, minutes: Long) {
        redisTemplate.opsForValue()
            .set(key, value, Duration.ofMinutes(minutes))
    }
}
```

### StringRedisTemplate vs RedisTemplate

| | StringRedisTemplate | RedisTemplate<K, V> |
|---|---|---|
| 직렬화 | String 전용 | 커스텀 가능 (JSON 등) |
| 용도 | 단순 문자열 | 객체 저장 |
| 가독성 | redis-cli에서 바로 읽힘 | 기본 JDK 직렬화는 바이너리 |

**권장**: `StringRedisTemplate` + JSON 직접 변환 (Jackson)

---

## 6. 대기열 시스템에서의 Redis 활용 패턴

### 패턴 1: Sorted Set 기반 대기열 (이 프로젝트에 적합)

```kotlin
@Service
class WaitingQueueService(
    private val redis: StringRedisTemplate
) {
    private val queueKey = "waiting-queue"

    // 대기열 등록 (score = 등록 시각)
    fun enqueue(userId: String): Long? {
        val score = System.currentTimeMillis().toDouble()
        redis.opsForZSet().add(queueKey, userId, score)
        return getPosition(userId)
    }

    // 내 순번 조회 (0부터 시작)
    fun getPosition(userId: String): Long? =
        redis.opsForZSet().rank(queueKey, userId)

    // 앞에서 N명 입장 처리
    fun dequeueTop(count: Long): Set<String>? {
        val users = redis.opsForZSet().range(queueKey, 0, count - 1)
        users?.forEach { redis.opsForZSet().remove(queueKey, it) }
        return users
    }

    // 전체 대기 인원
    fun totalWaiting(): Long =
        redis.opsForZSet().size(queueKey) ?: 0
}
```

### 패턴 2: Hash로 작업 상태 추적

```kotlin
fun trackJob(jobId: String, userId: String) {
    val key = "job:$jobId"
    redis.opsForHash<String, String>().putAll(key, mapOf(
        "userId" to userId,
        "status" to "pending",
        "enqueuedAt" to Instant.now().toString(),
        "retryCount" to "0"
    ))
    redis.expire(key, Duration.ofHours(24))  // 24시간 후 자동 정리
}

fun updateStatus(jobId: String, status: String) {
    redis.opsForHash<String, String>()
        .put("job:$jobId", "status", status)
}
```

### 패턴 3: Rate Limiting

```kotlin
fun isAllowed(userId: String, maxRequests: Long): Boolean {
    val key = "rate:$userId"
    val count = redis.opsForValue().increment(key) ?: 0

    if (count == 1L) {
        redis.expire(key, Duration.ofMinutes(1))  // 1분 윈도우
    }

    return count <= maxRequests
}
```

### 패턴 4: 분산 락 (중복 처리 방지)

```kotlin
fun acquireLock(resource: String, ttlSeconds: Long): String? {
    val lockKey = "lock:$resource"
    val lockValue = UUID.randomUUID().toString()

    val acquired = redis.opsForValue()
        .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(ttlSeconds))

    return if (acquired == true) lockValue else null
}

fun releaseLock(resource: String, lockValue: String) {
    val lockKey = "lock:$resource"
    val current = redis.opsForValue().get(lockKey)
    if (current == lockValue) {
        redis.delete(lockKey)
    }
}
```

### 패턴 5: Pub/Sub (실시간 알림)

```kotlin
// 발행
redis.convertAndSend("queue-events", "user:123:entered")

// 구독 (리스너 설정)
@Configuration
class RedisListenerConfig {
    @Bean
    fun redisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        container.addMessageListener(
            { message, _ -> println("Received: ${String(message.body)}") },
            ChannelTopic("queue-events")
        )
        return container
    }
}
```

---

## 7. 메모리 관리

Redis는 메모리 DB이므로 용량 관리가 중요:

```yaml
command: redis-server --maxmemory 512mb --maxmemory-policy allkeys-lru
```

### Eviction 정책

| 정책 | 설명 |
|------|------|
| `noeviction` | 메모리 가득 차면 쓰기 거부 (기본값) |
| `allkeys-lru` | 가장 오래 안 쓴 키 삭제 |
| `volatile-lru` | TTL이 있는 키 중 LRU 삭제 |
| `allkeys-random` | 무작위 삭제 |

**대기열 시스템 권장**: `noeviction` — 대기열 데이터가 임의로 삭제되면 안 됨. 대신 TTL로 만료 관리.

---

## 8. 모니터링

### 주요 명령어

```bash
# 메모리 사용량
docker compose exec redis redis-cli INFO memory

# 키 개수
docker compose exec redis redis-cli DBSIZE

# 실시간 통계
docker compose exec redis redis-cli --stat

# 느린 쿼리 확인
docker compose exec redis redis-cli SLOWLOG GET 10

# 전체 키 목록 (소규모에서만)
docker compose exec redis redis-cli KEYS "*"
```

### Redis Insight (GUI 모니터링)

docker-compose.yml에 추가하면 웹 UI로 모니터링 가능:

```yaml
redis-insight:
  image: redis/redisinsight:latest
  ports:
    - "5540:5540"
  networks:
    - harness-net
```

`http://localhost:5540`에서 키 탐색, 메모리 분석, CLI 사용 가능.

---

## 9. Spring Data Redis 메서드 레퍼런스

> `StringRedisTemplate`(또는 `RedisTemplate`) 기준. redis-cli 명령과 1:1 대응.

### 접근 방법

```kotlin
@Service
class MyService(private val redis: StringRedisTemplate) {
    // 자료구조별로 opsFor___() 로 접근
    redis.opsForValue()   // String
    redis.opsForList()    // List
    redis.opsForHash<String, String>()  // Hash
    redis.opsForSet()     // Set
    redis.opsForZSet()    // Sorted Set
}
```

### opsForValue() — String 연산

| 메서드 | redis-cli | 반환 | 설명 |
|--------|-----------|------|------|
| `set(key, value)` | `SET key value` | `Unit` | 저장 |
| `set(key, value, Duration)` | `SET key value EX n` | `Unit` | TTL 포함 저장 |
| `get(key)` | `GET key` | `String?` | 조회 |
| `increment(key)` | `INCR key` | `Long?` | 1 증가 |
| `increment(key, delta)` | `INCRBY key delta` | `Long?` | delta만큼 증가 |
| `decrement(key)` | `DECR key` | `Long?` | 1 감소 |
| `setIfAbsent(key, value)` | `SETNX key value` | `Boolean?` | 없을 때만 저장 (락에 사용) |
| `setIfAbsent(key, value, Duration)` | `SET key value NX EX n` | `Boolean?` | TTL 포함, 없을 때만 |
| `multiGet(keys)` | `MGET key1 key2` | `List<String?>?` | 여러 키 한번에 조회 |

```kotlin
// 예시
redis.opsForValue().set("counter", "0")
redis.opsForValue().increment("counter")          // 1
redis.opsForValue().increment("counter", 5)       // 6
redis.opsForValue().get("counter")                 // "6"

// TTL 포함
redis.opsForValue().set("session:abc", "data", Duration.ofMinutes(30))

// 락 획득 (없을 때만 저장)
val locked = redis.opsForValue().setIfAbsent("lock:resource", "owner", Duration.ofSeconds(10))
```

### opsForList() — List 연산

| 메서드 | redis-cli | 반환 | 설명 |
|--------|-----------|------|------|
| `leftPush(key, value)` | `LPUSH key value` | `Long?` | 왼쪽에 추가 |
| `rightPush(key, value)` | `RPUSH key value` | `Long?` | 오른쪽에 추가 |
| `leftPop(key)` | `LPOP key` | `String?` | 왼쪽에서 꺼냄 |
| `rightPop(key)` | `RPOP key` | `String?` | 오른쪽에서 꺼냄 |
| `rightPop(key, Duration)` | `BRPOP key timeout` | `String?` | 블로킹 pop |
| `range(key, start, end)` | `LRANGE key start end` | `List<String>?` | 범위 조회 |
| `size(key)` | `LLEN key` | `Long?` | 리스트 길이 |
| `index(key, index)` | `LINDEX key index` | `String?` | 인덱스로 조회 |
| `remove(key, count, value)` | `LREM key count value` | `Long?` | 값 삭제 |

```kotlin
// FIFO 큐: 왼쪽에 넣고 오른쪽에서 꺼냄
redis.opsForList().leftPush("jobs", "job-1")
redis.opsForList().leftPush("jobs", "job-2")
redis.opsForList().rightPop("jobs")               // "job-1" (선입선출)

// 블로킹 pop (큐가 비어있으면 5초 대기)
redis.opsForList().rightPop("jobs", Duration.ofSeconds(5))

// 전체 조회
redis.opsForList().range("jobs", 0, -1)           // 전체
redis.opsForList().range("jobs", 0, 9)            // 앞에서 10개
```

### opsForHash() — Hash 연산

| 메서드 | redis-cli | 반환 | 설명 |
|--------|-----------|------|------|
| `put(key, hashKey, value)` | `HSET key field value` | `Unit` | 필드 저장 |
| `putAll(key, map)` | `HMSET key f1 v1 f2 v2` | `Unit` | 여러 필드 한번에 |
| `get(key, hashKey)` | `HGET key field` | `Any?` | 필드 조회 |
| `entries(key)` | `HGETALL key` | `Map<Any, Any>` | 전체 조회 |
| `delete(key, *hashKeys)` | `HDEL key field1 field2` | `Long` | 필드 삭제 |
| `hasKey(key, hashKey)` | `HEXISTS key field` | `Boolean` | 필드 존재 여부 |
| `increment(key, hashKey, delta)` | `HINCRBY key field delta` | `Long` | 필드 값 증가 |
| `keys(key)` | `HKEYS key` | `Set<Any>` | 모든 필드명 |
| `values(key)` | `HVALS key` | `List<Any>` | 모든 값 |
| `size(key)` | `HLEN key` | `Long` | 필드 개수 |

```kotlin
val hashOps = redis.opsForHash<String, String>()

// 객체 저장
hashOps.putAll("job:123", mapOf(
    "userId" to "user:1",
    "status" to "pending",
    "createdAt" to Instant.now().toString()
))

// 필드 하나만 수정
hashOps.put("job:123", "status", "processing")

// 조회
hashOps.get("job:123", "status")                  // "processing"
hashOps.entries("job:123")                         // {userId=user:1, status=processing, ...}

// 카운터
hashOps.increment("job:123", "retryCount", 1)     // 1, 2, 3...
```

### opsForSet() — Set 연산

| 메서드 | redis-cli | 반환 | 설명 |
|--------|-----------|------|------|
| `add(key, *values)` | `SADD key v1 v2` | `Long?` | 추가 |
| `remove(key, *values)` | `SREM key v1 v2` | `Long?` | 삭제 |
| `members(key)` | `SMEMBERS key` | `Set<String>?` | 전체 조회 |
| `isMember(key, value)` | `SISMEMBER key value` | `Boolean?` | 멤버 여부 |
| `size(key)` | `SCARD key` | `Long?` | 크기 |
| `intersect(key1, key2)` | `SINTER key1 key2` | `Set<String>?` | 교집합 |
| `union(key1, key2)` | `SUNION key1 key2` | `Set<String>?` | 합집합 |
| `difference(key1, key2)` | `SDIFF key1 key2` | `Set<String>?` | 차집합 |
| `pop(key)` | `SPOP key` | `String?` | 랜덤 1개 꺼냄 |

```kotlin
// 중복 참여 방지
val isNew = redis.opsForSet().add("entered-users", "user:1")  // 1 (새로 추가됨)
val isDup = redis.opsForSet().add("entered-users", "user:1")  // 0 (이미 존재)

// 멤버 확인
redis.opsForSet().isMember("entered-users", "user:1")         // true
redis.opsForSet().size("entered-users")                        // 1
```

### opsForZSet() — Sorted Set 연산 (대기열 핵심)

| 메서드 | redis-cli | 반환 | 설명 |
|--------|-----------|------|------|
| `add(key, value, score)` | `ZADD key score value` | `Boolean?` | 추가 |
| `remove(key, *values)` | `ZREM key v1 v2` | `Long?` | 삭제 |
| `rank(key, value)` | `ZRANK key value` | `Long?` | 순위 (0부터, 오름차순) |
| `reverseRank(key, value)` | `ZREVRANK key value` | `Long?` | 역순위 |
| `score(key, value)` | `ZSCORE key value` | `Double?` | 점수 조회 |
| `range(key, start, end)` | `ZRANGE key start end` | `Set<String>?` | 범위 조회 (오름차순) |
| `reverseRange(key, start, end)` | `ZREVRANGE key start end` | `Set<String>?` | 범위 조회 (내림차순) |
| `rangeWithScores(key, start, end)` | `ZRANGE key start end WITHSCORES` | `Set<TypedTuple>?` | 점수 포함 조회 |
| `size(key)` | `ZCARD key` | `Long?` | 전체 개수 |
| `count(key, min, max)` | `ZCOUNT key min max` | `Long?` | 점수 범위 내 개수 |
| `incrementScore(key, value, delta)` | `ZINCRBY key delta value` | `Double?` | 점수 증가 |
| `rangeByScore(key, min, max)` | `ZRANGEBYSCORE key min max` | `Set<String>?` | 점수 범위로 조회 |
| `removeRange(key, start, end)` | `ZREMRANGEBYRANK key start end` | `Long?` | 순위 범위로 삭제 |
| `removeRangeByScore(key, min, max)` | `ZREMRANGEBYSCORE key min max` | `Long?` | 점수 범위로 삭제 |

```kotlin
// 대기열 등록 (score = 타임스탬프)
redis.opsForZSet().add("waiting-queue", "user:1", System.currentTimeMillis().toDouble())

// 내 순번
redis.opsForZSet().rank("waiting-queue", "user:1")     // 0 (첫 번째)

// 앞에서 3명 조회
redis.opsForZSet().range("waiting-queue", 0, 2)        // [user:1, user:2, user:3]

// 점수 포함 조회
redis.opsForZSet().rangeWithScores("waiting-queue", 0, 2)
    ?.forEach { println("${it.value} : ${it.score}") }

// 특정 유저 제거 (취소)
redis.opsForZSet().remove("waiting-queue", "user:2")

// 전체 대기 인원
redis.opsForZSet().size("waiting-queue")

// 점수(시간) 범위로 조회: 최근 1분 내 등록자
val now = System.currentTimeMillis().toDouble()
redis.opsForZSet().rangeByScore("waiting-queue", now - 60000, now)

// 순위 범위로 일괄 삭제 (앞에서 10명 입장 처리)
redis.opsForZSet().removeRange("waiting-queue", 0, 9)
```

### 공통 — 키 관리 (RedisTemplate 직접)

| 메서드 | redis-cli | 반환 | 설명 |
|--------|-----------|------|------|
| `delete(key)` | `DEL key` | `Boolean` | 키 삭제 |
| `delete(keys)` | `DEL key1 key2` | `Long` | 여러 키 삭제 |
| `hasKey(key)` | `EXISTS key` | `Boolean` | 존재 여부 |
| `expire(key, Duration)` | `EXPIRE key seconds` | `Boolean` | TTL 설정 |
| `getExpire(key)` | `TTL key` | `Long` | 남은 TTL (초) |
| `keys(pattern)` | `KEYS pattern` | `Set<String>` | 패턴으로 키 검색 |
| `type(key)` | `TYPE key` | `DataType` | 키 타입 |
| `rename(oldKey, newKey)` | `RENAME old new` | `Unit` | 키 이름 변경 |

```kotlin
// TTL 설정
redis.expire("job:123", Duration.ofHours(24))

// 키 존재 여부
redis.hasKey("waiting-queue")                     // true

// 키 삭제
redis.delete("waiting-queue")

// 패턴 검색 (주의: 프로덕션에서 KEYS는 느림, SCAN 사용 권장)
redis.keys("job:*")                                // [job:1, job:2, ...]

// 키 타입 확인
redis.type("waiting-queue")                        // ZSET
```

### 실전 조합 예시: 이 프로젝트의 QueueService

```kotlin
@Service
class QueueService(private val redis: StringRedisTemplate) {
    private val queueKey = "waiting-queue"

    // 등록
    fun enqueue(userId: String): Long {
        redis.opsForZSet().add(queueKey, userId, System.currentTimeMillis().toDouble())
        return redis.opsForZSet().rank(queueKey, userId) ?: -1
    }

    // 입장 (앞에서 N명)
    fun dequeue(count: Long): Set<String> {
        val users = redis.opsForZSet().range(queueKey, 0, count - 1) ?: emptySet()
        if (users.isNotEmpty()) {
            redis.opsForZSet().remove(queueKey, *users.toTypedArray())
        }
        return users
    }

    // 취소
    fun cancel(userId: String): Boolean =
        (redis.opsForZSet().remove(queueKey, userId) ?: 0) > 0

    // 순번 조회
    fun getPosition(userId: String): Long =
        redis.opsForZSet().rank(queueKey, userId) ?: -1

    // 전체 대기 인원
    fun totalWaiting(): Long =
        redis.opsForZSet().size(queueKey) ?: 0
}
```

---

## 정리: 이 프로젝트에서의 Redis 역할

```
[Client] → [Gateway] → [Queue Service] → [Redis]
                                            ├─ Sorted Set: 대기열 순번
                                            ├─ Hash: 작업 상태 추적
                                            ├─ String: 카운터, Rate Limit
                                            └─ Pub/Sub: 상태 변경 알림

[Queue Service] → [PostgreSQL (NeonDB)]
                    └─ 대기열 이력, 사용자 정보 등 영구 데이터
```

- **Redis**: 실시간 상태 (순번, 대기 인원, 임시 데이터)
- **PostgreSQL**: 영구 데이터 (이력, 통계, 사용자 정보)
