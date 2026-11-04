---
주제: ObjectMapper와 Jackson 핵심 애노테이션 (@JsonCreator, @JsonProperty, @JsonIgnore)
분류: Spring / Web / JSON
난이도: 이론
관련버전: Java 8~21, Spring Boot 3.x (Jackson 2.14~2.19)
작성일: 2026-11-04
선행지식: [Jackson 직렬화, 리플렉션, JavaBeans getter 규칙]
---

# ObjectMapper와 Jackson 핵심 애노테이션

관련 문서: [Jackson 직렬화 이론](./Jackson직렬화-이론.md) · [예제](./Jackson직렬화-예제.md)

> 이 문서는 `ObjectMapper`가 객체와 JSON을 변환하는 흐름과, 그 흐름에 개입하는 애노테이션 3개를 정리한다.
> Spring에서의 호출 지점, Boot 설정, 실무 함정은 [Jackson 직렬화 이론](./Jackson직렬화-이론.md)에서 다룬다.

---

## 1. ObjectMapper란

**Jackson의 핵심 클래스로, Java 객체와 JSON을 서로 변환한다.**

```
Java 객체  ──(직렬화, serialize)──▶  JSON 문자열
Java 객체  ◀──(역직렬화, deserialize)──  JSON 문자열
```

### 1.1 기본 사용

```java
ObjectMapper mapper = new ObjectMapper();

// 객체 → JSON
User user = new User("jihoon", 27);
String json = mapper.writeValueAsString(user);
// {"name":"jihoon","age":27}

// JSON → 객체
User parsed = mapper.readValue(json, User.class);
```

그 밖의 주요 메서드:
- `readTree(json)`: JSON을 `JsonNode` 트리로 읽는다. 클래스를 정의하지 않고 값만 꺼낼 때 쓴다.
- `convertValue(obj, Target.class)`: 객체를 다른 타입으로 바꾼다. 예: `Map` → DTO
- `TypeReference`: `List<User>`처럼 제네릭 타입으로 역직렬화할 때 쓴다.

### 1.2 인트로스펙션과 리플렉션

Jackson은 클래스를 분석해서 "이 객체의 프로퍼티는 무엇이고, 어떻게 읽고 쓰는가"를 결정한다. 이 분석이 **인트로스펙션**이다.

| 구분 | 역할 |
|---|---|
| 리플렉션 (Reflection) | **수단**. `Class.getDeclaredFields()`, `Method.invoke()` 같은 런타임 클래스 조회·조작 API |
| 인트로스펙션 (Introspection) | 리플렉션을 써서 하는 **분석**. 필드·getter·setter·생성자 파라미터를 논리적 프로퍼티로 묶는다 |

Jackson 안에서의 흐름:
1. **ClassIntrospector**(`BasicClassIntrospector`)가 클래스를 분석해 `BeanDescription`을 만든다.
2. **POJOPropertiesCollector**가 필드, getter/setter, 생성자 파라미터를 모아 `name`, `age` 같은 논리적 프로퍼티로 묶는다.
3. **AnnotationIntrospector**(`JacksonAnnotationIntrospector`)가 `@JsonProperty`, `@JsonIgnore` 같은 애노테이션을 해석해 이름 변경과 제외를 반영한다.
4. 분석 결과로 Serializer/Deserializer를 만들고 **캐시**한다.
5. 실제로 값을 읽고 쓸 때는 리플렉션(`Field.get`, `Method.invoke`)을 쓴다.

> Java 표준의 `java.beans.Introspector`도 있지만, Jackson은 이것을 쓰지 않고 위의 자체 인트로스펙션 체계를 쓴다.

### 1.3 `writeValueAsString(obj)`: 객체 → JSON

```
writeValueAsString(user)
 ├─ 1) 출력 버퍼(SegmentedStringWriter) + JsonGenerator 생성
 ├─ 2) 호출마다 SerializerProvider 인스턴스 생성 (SerializationConfig를 담음)
 ├─ 3) 루트 Serializer 조회: user.getClass() → User.class
 │      ├─ 캐시 hit → 바로 사용
 │      └─ 캐시 miss → BeanSerializerFactory가 인트로스펙션
 │                    → BeanSerializer(프로퍼티별 BeanPropertyWriter 목록) 생성 → 캐시 저장
 ├─ 4) BeanSerializer.serialize()
 │      gen.writeStartObject()            → {
 │      for (BeanPropertyWriter p : props)
 │          값 = p의 getter/필드로 읽기 (리플렉션)
 │          gen.writeFieldName("name")    → "name":
 │          값 타입의 Serializer로 위임     → "jihoon"   (중첩 객체면 재귀)
 │      gen.writeEndObject()              → }
 └─ 5) 버퍼 내용을 String으로 반환
```

- Serializer는 **타입별로** 있다(`StringSerializer`, `IntegerSerializer`, `CollectionSerializer` 등). BeanSerializer는 각 필드 값을 그 타입의 Serializer에 넘기기만 하는 **재귀 위임 구조**다.
- `JsonGenerator`는 JSON 문법(따옴표, 이스케이프, 콤마)만 책임지고, 객체가 무엇인지는 모른다.

### 1.4 `readValue(json, User.class)`: JSON → 객체

```
readValue(json, User.class)
 ├─ 1) JsonParser 생성 (JSON 문자열을 토큰 단위로 쪼개는 스트리밍 파서)
 ├─ 2) User.class → JavaType 변환 (TypeFactory, 제네릭 정보 포함)
 ├─ 3) 호출마다 DeserializationContext 생성
 ├─ 4) 루트 Deserializer 조회
 │      ├─ 캐시 hit → 사용
 │      └─ miss → BeanDeserializerFactory가 인트로스펙션
 │               → BeanDeserializer 생성
 │                  - ValueInstantiator (객체 생성 방법: 기본 생성자 / @JsonCreator)
 │                  - BeanPropertyMap (JSON 키 → SettableBeanProperty)
 ├─ 5) parser.nextToken() → START_OBJECT 확인
 ├─ 6) BeanDeserializer.deserialize()
 │      obj = new User()                  (기본 생성자, 리플렉션)
 │      while (토큰 == FIELD_NAME)
 │          prop = BeanPropertyMap.find("name")
 │          ├─ 있음 → 다음 토큰 읽고, 값 타입의 Deserializer로 변환
 │          │        → setter/필드로 obj에 주입 (리플렉션)
 │          └─ 없음 → FAIL_ON_UNKNOWN_PROPERTIES=true면 예외 (Jackson 기본값 true, Boot는 false)
 │      END_OBJECT → 종료
 └─ 7) obj 반환
```

- **스트리밍 방식**이다. 전체 트리를 만들지 않고 토큰을 하나씩 읽으며 바로 객체에 채운다. 그래서 `readTree`보다 빠르고 메모리를 덜 쓴다.
- `@JsonCreator`나 record처럼 생성자로 값을 받는 경우에는 객체를 먼저 만들 수 없다. 그래서 `PropertyBasedCreator`가 값을 버퍼에 모아 두었다가 다 읽은 뒤 생성자를 호출한다(2장).
- `List<User>`처럼 제네릭 타입은 `List.class`만으로는 요소 타입을 알 수 없다. 그래서 `TypeReference`로 넘겨야 2번 단계의 JavaType에 요소 타입 정보가 담긴다.

### 1.5 두 메서드 비교

| 구분 | writeValueAsString | readValue |
|---|---|---|
| 스트리밍 도구 | JsonGenerator (토큰 쓰기) | JsonParser (토큰 읽기) |
| 호출마다 생성 | SerializerProvider | DeserializationContext |
| 캐시되는 것 | BeanSerializer | BeanDeserializer |
| 인트로스펙션 결과 | BeanPropertyWriter 목록 | ValueInstantiator + BeanPropertyMap |
| 리플렉션 용도 | getter/필드로 값 **읽기** | 생성자 호출 + setter/필드로 값 **쓰기** |

### 1.6 재사용해야 하는 이유와 Spring에서의 사용

- 무거운 인트로스펙션은 **타입당 한 번** 하고, 결과를 `ObjectMapper` 인스턴스에 캐시한다. 매번 `new`로 만들면 캐시를 계속 버리게 된다.
- 설정을 마친 뒤에는 **스레드 안전**하므로 하나를 만들어 공유한다.
- Spring Boot는 `JacksonAutoConfiguration`으로 `ObjectMapper`를 **빈으로 등록**한다. `@RestController`의 반환값과 `@RequestBody` 변환은 `MappingJackson2HttpMessageConverter`가 이 빈으로 처리한다. 그래서 직접 쓸 때도 주입받아 쓴다.

```java
@Service
@RequiredArgsConstructor
public class SomeService {
    private final ObjectMapper objectMapper; // Boot가 구성한 빈
}
```

---

## 2. @JsonCreator란?

**역직렬화할 때 "이 생성자(또는 정적 팩토리 메서드)로 객체를 만들어라"라고 Jackson에 지정하는 애노테이션이다.**

### 2.1 왜 필요한가

Jackson의 기본 역직렬화 방식은 이렇다.
```
기본 생성자로 new User() → setter/필드로 값 주입
```
**불변 객체**는 이 방식을 쓸 수 없다.

```java
public class User {
    private final String name;   // final이라 나중에 값을 넣을 수 없음
    private final int age;

    public User(String name, int age) { ... }  // 기본 생성자 없음
}
```

Jackson은 객체를 만들 방법을 몰라서 이런 예외를 던진다.
`InvalidDefinitionException: Cannot construct instance of User (no Creators, like default constructor, exist)`

### 2.2 사용법

```java
public class User {
    private final String name;
    private final int age;

    @JsonCreator
    public User(@JsonProperty("name") String name,
                @JsonProperty("age") int age) {
        this.name = name;
        this.age = age;
    }
}
```

- `@JsonCreator`: 이 생성자를 쓰라는 표시
- `@JsonProperty("name")`: JSON의 `name` 키 값을 이 파라미터에 넣으라는 매핑

정적 팩토리 메서드에도 붙일 수 있다.

```java
@JsonCreator
public static User of(@JsonProperty("name") String name,
                      @JsonProperty("age") int age) {
    return new User(name, age);
}
```

### 2.3 내부 동작

생성자로 만들어야 하므로 **값을 다 읽기 전에는 객체를 생성할 수 없다.** 그래서 `PropertyBasedCreator`가 이렇게 처리한다.

```
토큰을 읽으며 "name", "age" 값을 버퍼에 모음
→ JSON을 끝까지 읽으면 생성자 호출 new User(name, age)
→ 생성자에 없는 나머지 프로퍼티는 setter/필드로 주입
```

### 2.4 두 가지 모드

| 모드 | 의미 | 예 |
|---|---|---|
| `PROPERTIES` | JSON 객체의 각 키를 파라미터에 매핑 | `{"name":"a","age":1}` → `User(name, age)` |
| `DELEGATING` | JSON 값 **전체**를 파라미터 하나로 받음 | `"jihoon"` → `UserName(String value)` |

```java
// 값 객체(Value Object)를 JSON 문자열 하나로 받을 때
@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
public UserName(String value) { this.value = value; }
```

### 2.5 생략해도 되는 경우

- **record**: Jackson 2.12+는 정식 생성자를 자동으로 인식한다.
- **Spring Boot + `-parameters` 컴파일 옵션**: Boot가 `ParameterNamesModule`을 자동 등록하므로 파라미터 이름으로 매핑된다. 생성자가 하나뿐이면 `@JsonProperty`도 생략할 수 있다. 다만 상황에 따라 동작이 애매할 수 있으므로, 명시하는 편이 안전하다.

**정리:** `@JsonCreator`는 **기본 생성자 + setter 대신 지정한 생성자로 객체를 만들게 하는 지시**다. 주로 final 필드를 쓰는 불변 DTO나 값 객체에 쓴다.

---

## 3. @JsonProperty란?

**Java 프로퍼티와 JSON 키를 연결하는 애노테이션이다.** 역할은 두 가지다.
1. **JSON 키 이름을 지정한다.**
2. **이 멤버가 프로퍼티라고 Jackson에 알린다.** getter가 없는 private 필드도 대상에 포함된다.

### 3.1 이름 매핑

```java
public class User {
    @JsonProperty("user_name")
    private String userName;
}
```
```json
{"user_name": "jihoon"}
```
직렬화와 역직렬화 **양쪽에 모두** 적용된다.

### 3.2 프로퍼티로 인식시키기

기본 가시성에서는 **public 필드나 getter/setter**만 프로퍼티가 된다.

```java
private String secret;   // getter 없음 → 무시됨

@JsonProperty
private String secret;   // 포함됨 (이름은 필드명 그대로 "secret")
```

### 3.3 생성자 파라미터 매핑 (@JsonCreator와 함께)

```java
@JsonCreator
public User(@JsonProperty("name") String name) { ... }
```

`-parameters` 옵션 없이 컴파일하면 바이트코드에 파라미터 이름이 남지 않는다. 그래서 **어떤 JSON 키를 어느 파라미터에 넣을지** 이 애노테이션으로 알려 줘야 한다.

### 3.4 주요 속성

| 속성 | 의미 |
|---|---|
| `value` | JSON 키 이름 |
| `access` | `READ_ONLY`: 직렬화(출력)만, `WRITE_ONLY`: 역직렬화(입력)만 |
| `required` | 필수 여부. 주로 `@JsonCreator` 파라미터에서 검사된다 |
| `index` | 프로퍼티 순서 힌트 |
| `defaultValue` | 메타데이터용 기본값. 실제로 값을 채워 주지는 않는다 |

```java
@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
private String password;   // 요청으로는 받지만 응답 JSON에는 안 나감
```

### 3.5 자주 만나는 사례: boolean `is` 접두사

```java
private boolean isActive;
public boolean isActive() { ... }   // Jackson은 프로퍼티명을 "active"로 해석
```

JSON에는 `"active"`로 나간다. `"isActive"`가 필요하면 명시한다.

```java
@JsonProperty("isActive")
private boolean isActive;
```

자세한 원인은 [Jackson 직렬화 이론](./Jackson직렬화-이론.md) 8.4에 있다.

### 3.6 내부 동작

인트로스펙션 단계에서 `POJOPropertiesCollector`가 필드·getter·setter·생성자 파라미터를 모은다. 이때 `AnnotationIntrospector`가 `@JsonProperty`를 읽어 다음 두 가지를 결정한다.
- 이 멤버를 프로퍼티로 포함할지
- 논리적 이름을 무엇으로 할지

이렇게 정해진 이름이 BeanPropertyWriter(직렬화)와 BeanPropertyMap(역직렬화)의 키가 된다.

### 3.7 전역 규칙으로 바꾸기

모든 필드를 snake_case로 바꿀 때는 필드마다 붙이지 말고 전역 설정을 쓴다.

```java
mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
```
```yaml
# Spring Boot
spring.jackson.property-naming-strategy: SNAKE_CASE
```

**정리:** `@JsonProperty`는 **"이 멤버는 JSON 프로퍼티이고, 키 이름은 이것이다"**라는 선언이다. 이름 변경, private 필드 노출, 생성자 파라미터 매핑, 읽기·쓰기 방향 제어에 쓴다.

---

## 4. @JsonIgnore란?

**이 프로퍼티를 JSON 변환에서 완전히 빼라는 애노테이션이다.** 직렬화와 역직렬화 **양쪽에서** 제외된다.

```java
public class User {
    private String name;

    @JsonIgnore
    private String password;
}
```

- **직렬화**: `{"name":"jihoon"}`처럼 password가 나가지 않는다.
- **역직렬화**: JSON에 `"password"`가 있어도 **조용히 무시하고 값을 넣지 않는다.** 예외도 나지 않는다.

### 4.1 언제 쓰나

1. **민감 정보 숨기기**: 비밀번호, 내부 토큰
2. **순환 참조 끊기**: JPA 양방향 연관관계의 무한 재귀
   ```java
   class Team   { List<Member> members; }
   class Member { @JsonIgnore Team team; }   // Member → Team → Member … 를 끊음
   ```
3. **계산용 getter 숨기기**: `getFullName()`, `isEmpty()`처럼 프로퍼티로 오인되는 메서드
4. **JPA 지연 로딩 필드**: 프록시 직렬화 오류 회피

> 엔티티에 `@JsonIgnore`를 붙이는 것은 증상만 막는 방법이다. 기본은 응답 전용 DTO를 쓰는 것이다([Jackson 직렬화 이론](./Jackson직렬화-이론.md) 7.2, 8.1).

### 4.2 내부 동작

인트로스펙션 단계에서 이렇게 처리된다.
- `AnnotationIntrospector`가 `@JsonIgnore`를 발견한다.
- `POJOPropertiesCollector`가 그 프로퍼티를 **목록에서 제거**한다.
- 제거된 이름은 **무시 대상으로 따로 등록**된다.

그래서 BeanPropertyWriter(직렬화)에도 BeanPropertyMap(역직렬화)에도 들어가지 않는다. 역직렬화 중에 그 키를 만나면 "모르는 키"가 아니라 **"무시하기로 한 키"**로 처리되므로 `FAIL_ON_UNKNOWN_PROPERTIES` 예외가 나지 않는다.

### 4.3 주의: 프로퍼티 단위로 적용된다

필드, getter, setter는 **하나의 논리적 프로퍼티로 묶인다.** 그래서 이 중 하나에만 붙여도 **프로퍼티 전체가 제외된다.**

```java
@JsonIgnore
private String password;
public String getPassword() { ... }   // 이것도 같이 무시됨
```

한 방향만 막으려면 `access`를 쓴다(3.4).

```java
@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
private String password;   // 요청으로는 받고, 응답에는 안 나감
```

### 4.4 비슷한 애노테이션

| 애노테이션 | 위치 | 용도 |
|---|---|---|
| `@JsonIgnore` | 필드/메서드 | 프로퍼티 하나 제외 |
| `@JsonIgnoreProperties({"a","b"})` | 클래스 | 여러 프로퍼티를 이름으로 제외 |
| `@JsonIgnoreProperties(ignoreUnknown = true)` | 클래스 | JSON에 모르는 키가 와도 예외 없이 무시 |
| `@JsonIgnoreType` | 클래스 | 이 **타입**의 프로퍼티는 어디에 있든 전부 제외 |
| `@JsonInclude(NON_NULL)` | 필드/클래스 | 값이 null일 때만 출력에서 제외 (조건부) |

**정리:** `@JsonIgnore`는 **"이 프로퍼티는 JSON에 없는 것으로 취급하라"**는 지시다. 인트로스펙션 단계에서 프로퍼티 목록에서 빠지므로 직렬화·역직렬화 양쪽에 적용된다. 한 방향만 제어하려면 `@JsonProperty(access = ...)`를 쓴다.

---

## 5. 한눈에 비교

| 대상 | 개입 시점 | 역할 |
|---|---|---|
| `ObjectMapper` | 전체 | 인트로스펙션 → (역)직렬화기 생성·캐시 → 스트리밍 변환 |
| `@JsonCreator` | 역직렬화의 객체 생성 | 기본 생성자 대신 지정한 생성자·팩토리로 생성 |
| `@JsonProperty` | 인트로스펙션 | 프로퍼티로 포함 + JSON 키 이름 지정 + 방향 제어 |
| `@JsonIgnore` | 인트로스펙션 | 프로퍼티를 목록에서 제거 (양방향) |
