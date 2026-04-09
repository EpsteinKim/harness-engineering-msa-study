# Mockito 학습 문서

> 이 프로젝트(harness-back)의 단위 테스트에서 Mockito를 활용하기 위한 학습 문서.

---

## 1. Mockito란?

**Mock 객체를 만들어주는 테스트 프레임워크.** 테스트 대상이 의존하는 객체를 가짜(mock)로 대체해서, 테스트 대상의 로직만 검증할 수 있게 해준다.

### 왜 Mock이 필요한가?

```
[ReservationScheduler] → [ReservationQueueService] → [Redis]
                       → [SeatReservationService]  → [JPA/DB]
```

`ReservationScheduler`를 테스트하고 싶은데, Redis와 DB가 실제로 돌아가야 한다면?

| 문제 | Mock 없이 | Mock 사용 |
|------|----------|----------|
| 외부 의존성 | Redis, PostgreSQL 실행 필요 | 불필요 |
| 테스트 속도 | 느림 (네트워크, I/O) | 빠름 (메모리만) |
| 테스트 격리 | 다른 테스트/데이터에 영향 | 완전 격리 |
| 실패 원인 추적 | DB 문제? 코드 문제? | 코드 문제만 |

**핵심**: Mock으로 의존성을 대체하면, **테스트 대상 클래스의 로직만 정확히 검증**할 수 있다.

### 단위 테스트 vs 통합 테스트

| | 단위 테스트 (Mockito) | 통합 테스트 (@SpringBootTest) |
|---|---|---|
| 범위 | 클래스 1개 | 여러 클래스 + 인프라 |
| 의존성 | Mock으로 대체 | 실제 Bean 사용 |
| 속도 | 매우 빠름 (ms) | 느림 (Spring 로딩) |
| 목적 | 로직 검증 | 통합 동작 검증 |

---

## 2. 기본 설정

### 의존성

Spring Boot Starter Test에 Mockito가 포함되어 있어서, 별도 의존성 추가 불필요.

```kotlin
// build.gradle.kts
testImplementation("org.springframework.boot:spring-boot-starter-test")
// ↑ 이 안에 mockito-core, mockito-junit-jupiter 등이 포함됨
```

### JUnit 5 + Mockito 연동

```kotlin
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)  // Mockito가 @Mock 어노테이션을 처리하도록 연결
class MyServiceTest {
    // ...
}
```

`@ExtendWith(MockitoExtension::class)` — JUnit 5에 Mockito를 붙이는 표준 방법. 이게 없으면 `@Mock`이 동작하지 않는다.

---

## 3. Mock 객체 생성

### @Mock — 가짜 객체 만들기

```kotlin
@Mock
lateinit var seatRepository: SeatRepository
```

이렇게 선언하면 `seatRepository`는 모든 메서드가 **기본값을 반환하는 가짜 객체**가 된다.

| 반환 타입 | 기본 반환값 |
|----------|-----------|
| 객체 | `null` |
| 숫자 | `0` |
| Boolean | `false` |
| Collection | 빈 컬렉션 |

### @InjectMocks — 테스트 대상에 Mock 주입

```kotlin
@Mock
lateinit var seatRepository: SeatRepository

@InjectMocks
lateinit var seatReservationService: SeatReservationService
```

`@InjectMocks`는 `SeatReservationService`의 생성자 파라미터에 맞는 `@Mock` 객체를 자동으로 넣어서 인스턴스를 만들어준다.

**주의**: 생성자 파라미터가 Mock과 정확히 매칭되지 않으면 `null`이 들어갈 수 있다. 이 경우 수동 생성이 더 안전하다.

### 수동 생성 — 생성자를 직접 호출

```kotlin
// 이 프로젝트의 ReservationQueueServiceTest에서 사용하는 방식
@Mock
lateinit var redis: StringRedisTemplate

@Mock
lateinit var seatRepository: SeatRepository

@Mock
lateinit var seatQueryRepository: SeatQueryRepository

private lateinit var queueService: ReservationQueueService

@BeforeEach
fun setUp() {
    // 수동으로 Mock을 주입해서 생성
    queueService = ReservationQueueService(redis, seatRepository, seatQueryRepository)
}
```

#### @InjectMocks vs 수동 생성

| | @InjectMocks | 수동 생성 |
|---|---|---|
| 코드량 | 적음 | 많음 |
| 명시성 | 자동 매칭 (암묵적) | 어떤 Mock이 들어가는지 명확 |
| 초기 설정 | 불가 (생성 후 바로 사용) | @BeforeEach에서 추가 설정 가능 |
| 추천 | 의존성이 단순한 경우 | 생성 전 Mock 설정이 필요한 경우 |

이 프로젝트에서는 `ReservationQueueService`가 `redis.opsForZSet()`, `redis.opsForHash()`를 생성자 시점에 호출하기 때문에, `@BeforeEach`에서 `lenient().when(...)`으로 먼저 설정한 뒤 수동 생성한다.

---

## 4. Stubbing — 가짜 동작 정의

"이 메서드가 호출되면, 이 값을 반환해라"라고 정의하는 것.

### when().thenReturn() — 기본 스터빙

```kotlin
`when`(seatRepository.findByEventIdAndId(1L, 10L)).thenReturn(availableSeat)
```

> Kotlin에서 `when`은 예약어이므로 백틱(`` ` ``)으로 감싼다.

**의미**: `seatRepository.findByEventIdAndId(1L, 10L)`가 호출되면 `availableSeat`를 반환하라.

### when().thenThrow() — 예외 발생

```kotlin
`when`(seatRepository.save(any(Seat::class.java)))
    .thenThrow(ObjectOptimisticLockingFailureException(Seat::class.java, 10L))
```

**의미**: `seatRepository.save(...)`가 호출되면 낙관적 락 예외를 던져라.

### when().thenReturn() 체이닝 — 호출마다 다른 값

```kotlin
`when`(mock.someMethod())
    .thenReturn("first")   // 첫 번째 호출
    .thenReturn("second")  // 두 번째 호출
    .thenReturn("third")   // 세 번째 이후 호출
```

### doReturn().when() — 대안 문법

```kotlin
// Mockito.doReturn 방식 (void 메서드나 spy에서 사용)
Mockito.doReturn(listOf(event)).`when`(eventRepository)
    .findEventsToOpen(any() ?: EventStatus.CLOSED, any() ?: LocalDateTime.MIN)
```

`when().thenReturn()` vs `doReturn().when()` 차이:

| | when().thenReturn() | doReturn().when() |
|---|---|---|
| 실제 메서드 호출 | 한 번 호출됨 (stubbing 시) | 호출 안 됨 |
| 용도 | 일반적인 경우 | Spy 객체, void 메서드 |
| 타입 안전성 | 컴파일 타임 체크 | 런타임 체크 |

### lenient() — 느슨한 스터빙

```kotlin
lenient().`when`(redis.opsForZSet()).thenReturn(zSetOps)
lenient().`when`(redis.opsForHash<String, String>()).thenReturn(hashOps)
```

기본적으로 Mockito는 **사용되지 않는 스터빙을 에러로 처리**한다 (Strict Stubbing). `lenient()`는 이 검사를 비활성화한다.

**언제 사용하나?**
- `@BeforeEach`에서 공통 설정하지만, 일부 테스트에서만 쓰는 경우
- 예: `redis.opsForZSet()` 설정은 모든 테스트에 공통이지만, ZSet을 안 쓰는 테스트도 있을 때

---

## 5. Argument Matcher — 파라미터 매칭

스터빙이나 검증에서 "아무 값이나 OK"라고 지정할 때 사용한다.

### 기본 Matcher

```kotlin
import org.mockito.ArgumentMatchers.*

anyString()        // 아무 String
anyLong()          // 아무 Long
anyDouble()        // 아무 Double
anyInt()           // 아무 Int
anyBoolean()       // 아무 Boolean
anyList()          // 아무 List
any()              // 아무 객체 (null 포함)
any(Seat::class.java)  // 특정 타입의 아무 객체
```

### eq() — 특정 값과 일치

```kotlin
verify(zSetOps).add(eq("reservation:waiting"), eq("user-1"), anyDouble())
//                   ↑ 정확히 이 값              ↑ 정확히 이 값     ↑ 아무 값이나
```

**중요 규칙**: Matcher를 하나라도 쓰면, **모든 인자에 Matcher를 써야 한다.**

```kotlin
// 잘못된 코드 — 컴파일은 되지만 런타임 에러
verify(zSetOps).add("reservation:waiting", eq("user-1"), anyDouble())
//                  ↑ 리터럴                ↑ Matcher      ↑ Matcher → 에러!

// 올바른 코드
verify(zSetOps).add(eq("reservation:waiting"), eq("user-1"), anyDouble())
```

### argThat() — 커스텀 조건

인자가 특정 조건을 만족하는지 검증할 때 사용한다.

```kotlin
verify(hashOps).putAll(eq("reservation:request:user-1"), Mockito.argThat { map ->
    map["eventId"] == "1" && map["seatId"] == "10"
})
```

**의미**: 두 번째 인자(Map)에 `eventId=1`과 `seatId=10`이 들어있는지 검증.

```kotlin
// 다른 예: 람다로 복잡한 조건 표현
verify(repository).save(argThat { seat ->
    seat.status == SeatStatus.RESERVED && seat.reservedBy == "user-1"
})
```

---

## 6. Verify — 호출 검증

Mock 메서드가 **예상대로 호출되었는지** 검증한다.

### 기본 검증

```kotlin
verify(seatRepository).save(availableSeat)
// "seatRepository.save(availableSeat)가 1번 호출되었는가?"
```

### 호출 횟수 검증

```kotlin
verify(seatRepository, times(1)).save(any())   // 정확히 1번
verify(seatRepository, times(2)).save(any())   // 정확히 2번
verify(seatRepository, atLeast(1)).save(any()) // 1번 이상
verify(seatRepository, atMost(3)).save(any())  // 3번 이하
```

### never() — 호출되지 않음을 검증

```kotlin
verify(seatRepository, never()).save(any(Seat::class.java))
// "seatRepository.save()가 한 번도 호출되지 않았는가?"
```

이 프로젝트에서 많이 사용하는 패턴:

```kotlin
// 좌석을 못 찾았으면 save가 호출되면 안 된다
@Test
fun reserveSeatNotFound() {
    `when`(seatRepository.findByEventIdAndId(1L, 999L)).thenReturn(null)

    val result = seatReservationService.reserveSeat(1L, 999L, "user-1")

    assertFalse(result.success)
    verify(seatRepository, never()).save(any(Seat::class.java))  // save 안 됨!
}
```

### verifyNoMoreInteractions() — 추가 호출 없음 확인

```kotlin
verify(mock).methodA()
verify(mock).methodB()
verifyNoMoreInteractions(mock)  // 위 두 개 외에 다른 호출이 없었는지 확인
```

---

## 7. @Nested — 테스트 구조화

JUnit 5의 기능으로, 테스트를 논리적 그룹으로 묶는다. Mockito와 직접 관련은 없지만, 이 프로젝트의 모든 테스트에서 사용한다.

```kotlin
@ExtendWith(MockitoExtension::class)
class ReservationQueueServiceTest {

    // 공통 Mock 선언
    @Mock lateinit var redis: StringRedisTemplate

    @Nested
    @DisplayName("enqueue - 대기열에 추가")
    inner class Enqueue {

        @Test
        @DisplayName("이벤트가 열려있으면 대기열에 추가한다")
        fun enqueueBySeatId() { /* ... */ }

        @Test
        @DisplayName("이벤트가 열리지 않았으면 예외를 발생시킨다")
        fun enqueueEventNotOpen() { /* ... */ }
    }

    @Nested
    @DisplayName("cancel - 예약 취소")
    inner class Cancel {
        // ...
    }
}
```

**구조**:
```
ReservationQueueServiceTest
├── Enqueue
│   ├── 이벤트가 열려있으면 대기열에 추가한다
│   └── 이벤트가 열리지 않았으면 예외를 발생시킨다
├── Cancel
│   ├── 취소 성공 시 true를 반환한다
│   └── 대기열에 없는 유저면 false를 반환한다
└── ...
```

**포인트**:
- `@Nested` 클래스는 바깥 클래스의 `@Mock` 필드를 공유한다
- `inner class`여야 바깥 클래스에 접근 가능 (Kotlin 특성)
- `@DisplayName`으로 한글 설명을 달면 테스트 리포트가 읽기 쉬워진다

---

## 8. MockMvc — Controller 테스트

Spring MVC 컨트롤러를 HTTP 요청/응답 수준에서 테스트한다. 실제 서버를 띄우지 않고 요청을 시뮬레이션한다.

### 설정

```kotlin
@ExtendWith(MockitoExtension::class)
class ReservationControllerTest {

    @Mock
    lateinit var queueService: ReservationQueueService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        // standaloneSetup: 스프링 컨텍스트 없이 특정 Controller만 테스트
        mockMvc = MockMvcBuilders
            .standaloneSetup(ReservationController(queueService))
            .build()
    }
}
```

### 요청 보내기

```kotlin
// POST 요청
mockMvc.perform(
    post("/api/v1/reservations")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"userId":"user-1","eventId":1,"seatId":10}""")
)

// GET 요청
mockMvc.perform(get("/api/v1/reservations/seats/1"))

// DELETE 요청
mockMvc.perform(delete("/api/v1/reservations/queue/user-1"))
```

### 응답 검증

```kotlin
mockMvc.perform(post("/api/v1/reservations")
    .contentType(MediaType.APPLICATION_JSON)
    .content("""{"userId":"user-1","eventId":1,"seatId":10}"""))
    .andExpect(status().isOk)                              // HTTP 200
    .andExpect(jsonPath("$.status").value("success"))       // JSON 필드 검증
    .andExpect(jsonPath("$.data.userId").value("user-1"))
    .andExpect(jsonPath("$.data.position").value(0))
```

### jsonPath 문법

```kotlin
jsonPath("$.status")              // 루트의 status 필드
jsonPath("$.data.userId")         // data 객체의 userId
jsonPath("$.data[0].seatNumber")  // data 배열의 첫 번째 요소의 seatNumber
jsonPath("$.data.length()")       // data 배열의 길이
jsonPath("$.data").isEmpty         // data가 비어있는지
```

### MockMvc + Mockito 조합

Controller 테스트에서 Service를 Mock하고, 요청→응답 흐름을 검증한다.

```kotlin
@Test
fun enqueueBySeatId() {
    // 1. Service Mock 설정
    `when`(queueService.getPosition("user-1")).thenReturn(0L)

    // 2. HTTP 요청 & 응답 검증
    mockMvc.perform(
        post("/api/v1/reservations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":"user-1","eventId":1,"seatId":10}""")
    )
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.data.position").value(0))

    // 3. Service 호출 검증
    verify(queueService).enqueue("user-1", 1L, seatId = 10L)
}
```

---

## 9. 테스트 패턴 정리

### 패턴 1: 성공 케이스

```kotlin
@Test
fun reserveSeatSuccess() {
    // Given — 스터빙
    `when`(seatRepository.findByEventIdAndId(1L, 10L)).thenReturn(availableSeat)
    `when`(seatRepository.save(any(Seat::class.java))).thenReturn(availableSeat)

    // When — 실행
    val result = seatReservationService.reserveSeat(1L, 10L, "user-1")

    // Then — 검증
    assertTrue(result.success)
    assertEquals("Reservation successful", result.message)
    verify(seatRepository).save(availableSeat)
}
```

### 패턴 2: 실패 케이스 (결과값 검증)

```kotlin
@Test
fun reserveSeatNotFound() {
    `when`(seatRepository.findByEventIdAndId(1L, 999L)).thenReturn(null)

    val result = seatReservationService.reserveSeat(1L, 999L, "user-1")

    assertFalse(result.success)
    assertEquals("Seat not found", result.message)
    verify(seatRepository, never()).save(any(Seat::class.java))  // 저장 안 됨
}
```

### 패턴 3: 예외 발생 검증

```kotlin
@Test
fun enqueueEventNotOpen() {
    `when`(redis.hasKey("event:1")).thenReturn(false)

    val exception = assertThrows(IllegalStateException::class.java) {
        queueService.enqueue("user-1", 1L, seatId = 10L)
    }
    assertEquals("Event is not open for reservations", exception.message)
    verify(zSetOps, never()).add(anyString(), anyString(), anyDouble())
}
```

### 패턴 4: 예외를 던지는 Mock

```kotlin
@Test
fun reserveSeatOptimisticLockConflict() {
    `when`(seatRepository.findByEventIdAndId(1L, 10L)).thenReturn(availableSeat)
    `when`(seatRepository.save(any(Seat::class.java)))
        .thenThrow(ObjectOptimisticLockingFailureException(Seat::class.java, 10L))

    val result = seatReservationService.reserveSeat(1L, 10L, "user-1")

    assertFalse(result.success)
    assertEquals("Seat was taken by another user", result.message)
}
```

### 패턴 5: null 반환 처리

```kotlin
@Test
fun peekWaitingNull() {
    `when`(zSetOps.range("reservation:waiting", 0, 9)).thenReturn(null)

    assertTrue(queueService.peekWaiting(10).isEmpty())
}
```

---

## 10. Kotlin에서의 Mockito 주의사항

### 백틱 when

Kotlin의 `when`은 예약어이므로 백틱으로 감싸야 한다.

```kotlin
`when`(mock.method()).thenReturn(value)
```

### lateinit var

Mock 객체는 `lateinit var`로 선언한다. Mockito가 테스트 실행 전에 초기화해준다.

```kotlin
@Mock
lateinit var repository: SeatRepository  // val 아님, lateinit var
```

### inner class

`@Nested` 테스트 클래스는 반드시 `inner class`로 선언해야 바깥 클래스의 필드에 접근할 수 있다.

```kotlin
@Nested
inner class Enqueue {  // inner 필수!
    @Test
    fun test() {
        // 바깥의 @Mock 필드 사용 가능
    }
}
```

### any()와 Kotlin non-null

Kotlin의 non-null 파라미터에 `any()`를 쓰면 `null`이 전달되어 에러가 날 수 있다. 이 경우 `any() ?: defaultValue`로 우회한다.

```kotlin
// Kotlin non-null 파라미터에 any() 사용 시
Mockito.doReturn(listOf(event)).`when`(eventRepository)
    .findEventsToOpen(any() ?: EventStatus.CLOSED, any() ?: LocalDateTime.MIN)
//                    ↑ any()가 null 반환 시 기본값으로 대체
```

---

## 11. Assertion 메서드 레퍼런스

JUnit 5의 `Assertions` 클래스에서 제공하는 검증 메서드들.

| 메서드 | 설명 | 예시 |
|--------|------|------|
| `assertEquals(expected, actual)` | 값 동일 | `assertEquals("A", result.section)` |
| `assertNotEquals(a, b)` | 값 다름 | `assertNotEquals(0L, result.seatId)` |
| `assertTrue(condition)` | true인지 | `assertTrue(result.success)` |
| `assertFalse(condition)` | false인지 | `assertFalse(result.success)` |
| `assertNull(value)` | null인지 | `assertNull(data.seatId)` |
| `assertNotNull(value)` | null 아닌지 | `assertNotNull(data)` |
| `assertThrows(type) { ... }` | 예외 발생 | `assertThrows(IllegalStateException::class.java) { ... }` |

---

## 12. 전체 흐름 요약

```
1. @ExtendWith(MockitoExtension::class) → Mockito 활성화
2. @Mock → 의존성을 가짜 객체로 생성
3. @InjectMocks 또는 수동 생성 → 테스트 대상에 Mock 주입
4. when().thenReturn() → Mock의 동작 정의 (스터빙)
5. 테스트 대상 메서드 호출 → 실제 로직 실행
6. assertEquals / assertTrue 등 → 반환값 검증
7. verify() → Mock 메서드 호출 여부 검증
```

### 이 프로젝트의 테스트 구성

```
reserve-service/src/test/kotlin/
└── com/epstein/practice/reserveservice/
    ├── controller/
    │   └── ReservationControllerTest.kt   ← MockMvc + Mockito
    ├── scheduler/
    │   └── ReservationSchedulerTest.kt    ← 순수 Mockito
    └── service/
        ├── ReservationQueueServiceTest.kt ← 순수 Mockito (Redis Mock)
        ├── SeatReservationServiceTest.kt  ← 순수 Mockito (JPA Mock)
        └── EventServiceTest.kt           ← 순수 Mockito (JPA + Redis Mock)
```

---

## 13. 자주 하는 실수

### 실수 1: Matcher 섞어 쓰기

```kotlin
// 에러: Matcher와 리터럴을 섞으면 안 됨
verify(mock).method("literal", anyLong())

// 수정: 모든 인자에 Matcher 사용
verify(mock).method(eq("literal"), anyLong())
```

### 실수 2: 불필요한 스터빙 (Strict Stubbing 에러)

```kotlin
@BeforeEach
fun setUp() {
    // 모든 테스트에서 쓰지 않는 스터빙 → UnnecessaryStubbingException
    `when`(mock.methodA()).thenReturn("a")
    `when`(mock.methodB()).thenReturn("b")  // 일부 테스트에서만 사용
}
```

해결: `lenient()`로 감싸거나, 각 테스트 메서드 안에서 스터빙한다.

### 실수 3: verify 위치

```kotlin
// 잘못: verify를 메서드 호출 전에 배치
verify(mock).save(any())
service.doSomething()  // 아직 호출 안 됨 → 실패

// 올바름: 메서드 호출 후에 verify
service.doSomething()
verify(mock).save(any())
```

### 실수 4: thenReturn에 null

```kotlin
// Kotlin non-null 반환 타입에 null을 반환하면 NPE
`when`(mock.getCount()).thenReturn(null)  // getCount()가 Long 반환이면 위험

// 안전: 실제 값을 반환
`when`(mock.getCount()).thenReturn(0L)
```
