# Test Guide

## 테스트 라이브러리

- 테스트 코드에서 외부 라이브러리(mockito-kotlin 등) 사용 금지.
- `spring-boot-starter-test`에 포함된 plain Mockito만 사용할 것 (`Mockito.\`when\`()`, `Mockito.verify()` 등).

## KAPT + 테스트 주의사항

- KAPT 사용 서비스(QueryDSL 등)에서 `@WebMvcTest`, `@MockitoBean` 등 Spring Boot Test 어노테이션 사용 금지.
- KAPT가 테스트 소스도 annotation processing 대상으로 잡아, `testImplementation` 전용 어노테이션을 못 찾는 문제 발생.
- 컨트롤러 테스트는 `@ExtendWith(MockitoExtension::class)` + `MockMvcBuilders.standaloneSetup()` 방식 사용.

## Mockito 사용 규칙

- `@InjectMocks`는 `@Value` 파라미터가 있는 클래스에 사용 불가. `@BeforeEach`에서 수동 생성할 것.
- `@BeforeEach`에서 모든 테스트가 사용하지 않는 스텁은 `lenient()` 처리. Mockito strict mode에서 `UnnecessaryStubbingException` 발생 방지.
