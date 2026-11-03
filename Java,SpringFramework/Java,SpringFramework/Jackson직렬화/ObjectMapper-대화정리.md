---
주제: ObjectMapper 대화 정리
분류: Spring / Web / JSON
관련버전: Spring Boot 3.x / Jackson 2.x
작성일: 2026-11-03
---

# ObjectMapper 대화 정리

`ObjectMapper`의 역할, 프로퍼티를 찾는 방법, 캐시와 getter 호출, Spring Boot의 자동 설정을 정리한다.

이 문서의 설정과 예제는 **Spring Boot 3.x / Jackson 2.x 기준**이다. Boot 4는 Jackson 3의 `JsonMapper`가 기본이므로 그대로 적용하지 않는다.

관련 학습 문서: [Jackson 직렬화 이론](./Jackson직렬화-이론.md), [Jackson 직렬화 예제](./Jackson직렬화-예제.md)

## 1. ObjectMapper와 직렬화·역직렬화

`ObjectMapper`는 Jackson에서 Java 객체와 JSON을 서로 변환하는 핵심 클래스다.

| 방향 | 이름 | 대표 메서드 |
|---|---|---|
| Java 객체 → JSON 문자열 | 직렬화(Serialization) | `writeValueAsString()` |
| JSON 문자열 → Java 객체 | 역직렬화(Deserialization) | `readValue()` |

```java
// objectMapper는 주입받은 ObjectMapper라고 가정한다.
String json = objectMapper.writeValueAsString(member); // 직렬화
MemberRequest restored = objectMapper.readValue(json, MemberRequest.class); // 역직렬화
```

직렬화는 객체의 데이터를 저장·전송할 수 있는 형태로 바꾸는 것이고, 역직렬화는 그 데이터로 객체를 복원하는 것이다. Jackson의 JSON 직렬화에는 Java 기본 직렬화의 `Serializable` 구현이 필요하지 않다.

## 2. 필드가 없어도 getter가 있으면 JSON에 포함되는가?

**기본 설정에서는 public getter만 있어도 직렬화할 프로퍼티로 인식한다.**

```java
public class MemberResponse {
    private Long id;
    private String name;
    private String password; // getter 없음
    private LocalDateTime joinedAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public LocalDateTime getJoinedAt() { return joinedAt; }
    public String getDisplayName() { return name + "님"; } // 대응하는 필드 없음
    public boolean isVip() { return false; } // 대응하는 필드 없음
}
```

| 멤버 | JSON 프로퍼티 | 기본 직렬화 결과 |
|---|---|---|
| `getId()` | `id` | 포함 |
| `getName()` | `name` | 포함 |
| private `password`, getter 없음 | `password` | 제외 |
| `getJoinedAt()` | `joinedAt` | 포함 |
| `getDisplayName()` | `displayName` | 포함 |
| `isVip()` | `vip` | 포함 |

필드 값이 설정되어 있고 Boot의 기본 날짜 처리를 사용한다면 다음과 같은 JSON이 만들어질 수 있다. 키 순서는 별도 설정이 없다면 이 예제와 같다고 가정하지 않는다.

```json
{
  "id": 1,
  "name": "철수",
  "joinedAt": "2026-11-03T10:15:30",
  "displayName": "철수님",
  "vip": false
}
```

Jackson은 `getDisplayName()`과 `isVip()`를 실제로 호출하고, 반환된 값을 JSON에 쓴다. 필드에 저장되어 있지 않은 계산 결과도 응답에 포함될 수 있다.

단, **Jackson이 getter만 보는 것은 아니다.** 기본적으로 public 필드도 감지하며, `@JsonProperty`, `@JsonIgnore`, 가시성 설정 등에 따라 포함 대상이 달라진다.

getter를 응답에서 제외하려면 다음처럼 지정할 수 있다.

```java
@JsonIgnore
public String getDisplayName() {
    return name + "님";
}
```

## 3. 인트로스펙션은 무엇인가?

**인트로스펙션(Introspection)은 클래스의 구조를 살펴보고, JSON으로 처리할 프로퍼티와 접근 방법을 알아내는 과정이다.**

Jackson은 필드, 메서드, 어노테이션 등을 분석해서 다음과 같은 정보를 결정한다.

```text
JSON 이름       값을 읽는 방법
id              getId()
name            getName()
joinedAt        getJoinedAt()
displayName     getDisplayName()
vip             isVip()
```

리플렉션(Reflection)은 클래스 정보를 조회하거나 메서드를 호출하는 수단이다. 인트로스펙션은 이런 수단으로 클래스의 프로퍼티와 구조를 분석하는 작업을 가리킨다.

**getter를 발견하는 것과 getter를 호출하는 것은 서로 다른 작업이다.**

## 4. 무엇을 캐시하며, getter는 언제 호출하는가?

Jackson은 분석으로 얻은 프로퍼티 정보와 이를 바탕으로 만든 직렬화 처리기(serializer) 등을 캐시해서 재사용한다.

**캐시되는 것은 JSON 응답값이 아니다.**

| 재사용하는 정보 | 매번 읽는 데이터 |
|---|---|
| JSON 프로퍼티 이름 | 현재 객체의 실제 값 |
| 어떤 getter나 필드로 값을 읽을지 | getter의 현재 반환값 |
| 해당 타입을 처리할 직렬화 처리기 | 이번에 만들어지는 JSON 결과 |

일반적인 POJO의 직렬화 흐름은 다음과 같다.

```text
처음 해당 타입을 직렬화할 때
    클래스 구조 분석 → 직렬화 처리기 생성·캐시
    → 해당 객체의 getter 호출 → JSON 생성

이후 같은 타입을 직렬화할 때
    캐시된 직렬화 처리기 재사용
    → 해당 객체의 getter 호출 → JSON 생성
```

따라서 다음 두 호출은 분석 정보를 재사용하더라도 각 객체의 값을 따로 읽는다.

```java
objectMapper.writeValueAsString(memberA);
objectMapper.writeValueAsString(memberB);
```

`getDisplayName()` 안의 계산도 직렬화할 때마다 실행된다. getter가 예외를 던지거나 다른 작업을 수행하면 그 영향도 직렬화 시점에 나타날 수 있다.

대화에서 말한 “처음 한 번만 분석한다”는 표현은 **같은 설정의 매퍼에서 캐시를 재사용하는 일반적인 경우를 단순화한 설명**이다. 매퍼를 새로 만들거나 캐시가 제거되는 경우까지 포함해서, 애플리케이션 전체에서 정확히 한 번만 분석된다는 보장은 아니다.

## 5. 캐시는 요청·스레드 단위인가?

**일반적으로 ObjectMapper 인스턴스에서 유지·재사용하는 캐시다. 요청이나 스레드마다 별도로 만드는 캐시가 아니다.**

Spring Boot가 등록한 싱글톤 매퍼를 공유하면 여러 요청과 스레드가 캐시의 이점을 얻는다.

```text
요청 A ─┐
요청 B ─┼─→ 공유 ObjectMapper → 타입별 직렬화 처리기 캐시 재사용
요청 C ─┘
```

`ObjectMapper`는 **사용 전에 설정을 마치면 여러 스레드에서 공유할 수 있다.** 요청을 처리하는 도중 공유 매퍼의 설정을 바꾸는 방식은 피한다.

요청마다 `new ObjectMapper()`를 만들면 이전 매퍼가 쌓은 캐시를 활용하지 못하고 설정도 반복해야 한다.

## 6. Spring Boot는 ObjectMapper를 어떻게 설정하는가?

**Boot는 Jackson의 ObjectMapper에 기본 옵션, 확장 모듈, 사용자 설정을 적용하고 빈으로 등록한다.** Boot 전용의 별개 JSON 엔진을 만드는 것은 아니다.

일반적인 자동 설정 흐름은 다음과 같다.

```text
애플리케이션 시작
    ↓
Jackson 라이브러리 존재 확인
    ↓
개발자가 등록한 ObjectMapper 빈이 없는지 확인
    ↓
Jackson2ObjectMapperBuilder 준비
    ↓
기본 옵션·모듈·사용자 설정 적용
    ↓
설정된 ObjectMapper를 싱글톤 빈으로 등록
    ↓
Spring MVC의 JSON 메시지 컨버터와 애플리케이션 코드에서 사용
```

이 과정을 담당하는 자동 설정 클래스가 `JacksonAutoConfiguration`이다. 아래 옵션과 모듈 적용은 Builder와 Boot의 자동 설정이 함께 수행한다.

### 6.1 대표적인 기본 옵션

| 옵션 | Boot 기본값 | 의미 |
|---|---|---|
| `FAIL_ON_UNKNOWN_PROPERTIES` | `false` | 역직렬화 시 대상 클래스에 없는 JSON 속성은 무시 |
| `WRITE_DATES_AS_TIMESTAMPS` | `false` | 지원되는 날짜·시간 타입을 문자열 형태로 출력하도록 설정 |
| `WRITE_DURATIONS_AS_TIMESTAMPS` | `false` | `Duration`을 숫자 대신 문자열 형태로 출력하도록 설정 |
| `DEFAULT_VIEW_INCLUSION` | `false` | JSON View가 활성화된 경우 View 지정이 없는 프로퍼티는 기본 포함하지 않음 |

### 6.2 확장 모듈 등록

Spring의 Builder는 클래스패스에 있는 알려진 모듈을 자동 감지한다.

| 모듈 | 역할 |
|---|---|
| `JavaTimeModule` (`jackson-datatype-jsr310`) | `LocalDateTime`, `LocalDate` 등 날짜·시간 타입 지원 |
| `Jdk8Module` (`jackson-datatype-jdk8`) | `Optional` 등 타입 지원 |

Spring 컨텍스트에 등록된 Jackson `Module` 빈도 자동 설정된 Builder가 만드는 매퍼에 적용된다.

**모듈은 타입을 처리하는 방법을 제공하고, 옵션은 처리 방식과 출력 형태 등을 조절한다.** 예를 들어 `LocalDateTime`의 처리는 날짜 모듈이 제공하고, 문자열 출력은 날짜 관련 옵션의 영향도 받는다.

### 6.3 사용자 설정 반영

`application.yml`의 `spring.jackson.*` 설정을 반영한다.

```yaml
spring:
  jackson:
    default-property-inclusion: non_null
```

이 예제는 값이 `null`인 프로퍼티를 JSON에서 제외하도록 **기본 동작을 변경**한다. `null` 제외가 Boot의 기본값이라는 뜻은 아니다.

코드로 추가 설정이 필요하면 `Jackson2ObjectMapperBuilderCustomizer` 빈을 사용할 수 있다. Boot의 기본 구성을 유지하면서 원하는 옵션을 추가하는 방법이다.

개발자가 직접 `ObjectMapper` 빈을 등록하면 Boot의 기본 매퍼 생성은 생략된다. 이때 직접 만든 매퍼가 Boot 설정을 자동으로 모두 받는다고 가정하면 안 된다.

## 7. 직접 생성과 생성자 주입은 다르다

| 방식 | 예시 | 의미 |
|---|---|---|
| 직접 생성 | `new ObjectMapper()` | Jackson 기본 설정의 새 객체 생성 |
| 생성자 주입 | 생성자 인자로 `ObjectMapper`를 받음 | Spring이 관리하는 매퍼를 전달받음 |

```java
@Service
public class MemberJsonService {
    private final ObjectMapper objectMapper;

    public MemberJsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(MemberResponse member)
            throws JsonProcessingException {
        return objectMapper.writeValueAsString(member);
    }
}
```

Boot 자동 설정을 그대로 쓰고 있다면, 위 생성자는 Boot가 설정한 매퍼를 받는다. 이 코드에서 개발자가 직접 새 매퍼를 만드는 것은 아니다.

### 7.1 LocalDateTime을 직접 만든 매퍼로 직렬화하면?

Jackson 2에서 날짜 모듈 없이 다음 코드를 실행하면 일반적으로 `InvalidDefinitionException`이 발생한다.

```java
ObjectMapper mapper = new ObjectMapper();
mapper.writeValueAsString(LocalDateTime.now());
```

직렬화 대상 객체의 `LocalDateTime` 프로퍼티에 실제 값이 있을 때도 동일한 문제가 발생한다. 단순히 필드 선언만 존재하고 직렬화에서 제외되었거나 값이 `null`이라면 그 값의 날짜 직렬화기가 실행되지 않을 수 있다.

직접 설정하려면 관련 의존성을 추가하고 모듈을 등록한다. Boot와 유사하게 날짜를 문자열로 출력하려면 옵션도 지정한다.

```java
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new JavaTimeModule());
mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
```

이 설정만으로 Boot의 모든 설정을 재현하는 것은 아니다. 애플리케이션과 같은 JSON 규칙이 필요하다면 보통 기존 매퍼를 주입받는다.

## 8. ObjectMapper를 직접 주입받는 상황

내 코드에서 직접 JSON 변환이 필요할 때 사용한다.

- 외부에서 받은 JSON 문자열을 Java 객체로 변환할 때
- 객체를 JSON 문자열로 만들어 DB나 Redis 등에 저장할 때
- 메시지 큐에 보낼 JSON을 만들거나 받은 JSON을 해석할 때

다만 해당 저장소나 메시징 라이브러리가 이미 JSON 변환을 담당한다면 직접 호출할 필요는 없다.

컨트롤러에서 요청·응답 객체를 사용하는 일반적인 상황도 마찬가지다.

```java
@PostMapping("/members")
public MemberResponse create(@RequestBody MemberRequest request) {
    return memberService.create(request);
}
```

기본 Spring MVC 구성에서는 메시지 컨버터가 Jackson을 이용해 변환한다.

```text
요청 JSON → 메시지 컨버터·Jackson → MemberRequest
MemberResponse → 메시지 컨버터·Jackson → 응답 JSON
```

따라서 위 컨트롤러는 직접 `ObjectMapper`를 주입받거나 호출하지 않아도 된다.

## 9. ObjectMapper와 깊은 복사

객체를 JSON으로 직렬화한 뒤 다시 객체로 역직렬화하면, JSON에 표현된 데이터를 가진 새 객체를 만들 수 있다.

```java
String json = objectMapper.writeValueAsString(original);
MemberRequest copy = objectMapper.readValue(json, MemberRequest.class);
```

중첩 객체도 새로 만들어질 수 있어서 깊은 복사에 활용할 수 있지만, **모든 Java 객체를 원래 구조 그대로 복제하는 전용 도구는 아니다.**

- `@JsonIgnore` 등으로 JSON에서 빠진 데이터는 복원되지 않는다.
- 계산 getter로 만든 프로퍼티는 역직렬화할 때 대응하는 접근자가 없어 문제가 될 수 있다.
- 생성자, setter, 타입 정보, 사용자 정의 직렬화·역직렬화 설정 등에 따라 결과가 달라진다.
- 원래의 객체 참조 관계나 실제 하위 타입이 자동으로 그대로 보존된다고 가정하면 안 된다.

즉, JSON 변환에 따른 데이터 복원과 임의 객체의 완전한 복제는 구분해야 한다.

## 10. 헷갈렸던 내용 확인

| 질문 | 정리 |
|---|---|
| 객체 → JSON은 역직렬화인가? | 직렬화다. JSON → 객체가 역직렬화다. |
| 필드가 없으면 응답에 안 나가는가? | 기본 설정에서는 public getter만 있어도 나갈 수 있다. |
| 인트로스펙션은 getter 호출 때마다 일어나는가? | 보통 분석 결과와 직렬화 처리기를 캐시하고, getter는 직렬화할 때마다 호출한다. |
| 캐시된 값은 이전 응답의 JSON인가? | 아니다. 타입을 어떻게 처리할지에 대한 정보다. |
| 캐시는 요청·스레드마다 따로 생기는가? | 일반적으로 매퍼 인스턴스에서 유지하고 여러 요청이 재사용한다. |
| Boot가 시작할 때 모든 DTO를 미리 분석하는가? | 일반적으로 실제 타입을 처리하며 필요한 정보를 만들고 캐시한다. |
| 생성자 주입도 직접 새 매퍼를 만드는 것인가? | 아니다. Spring이 관리하는 객체를 받는 것이다. |
| `new ObjectMapper()`에 Boot 설정이 자동 적용되는가? | 아니다. |
| Boot 자동 설정 매퍼는 무조건 존재하는가? | 관련 라이브러리와 자동 설정 조건이 충족되어야 하며, 사용자 매퍼 빈이 있으면 기본 매퍼 생성은 생략한다. |

## 참고 문서

- [Spring Boot 3: ObjectMapper 사용자 설정과 기본 옵션](https://docs.spring.io/spring-boot/docs/3.0.x/reference/html/howto.html#howto.spring-mvc.customize-jackson-objectmapper)
- [Spring Boot 3: JacksonAutoConfiguration](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/autoconfigure/jackson/JacksonAutoConfiguration.html)
- [Spring Framework: Jackson2ObjectMapperBuilder와 모듈 자동 감지](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/http/converter/json/Jackson2ObjectMapperBuilder.html)
- [Jackson 2: LocalDateTime 미지원 오류 사례](https://github.com/FasterXML/jackson-databind/issues/3262)
- [Spring Boot 4: Jackson 3 기반 JSON 지원](https://docs.spring.io/spring-boot/4.0/reference/features/json.html)
