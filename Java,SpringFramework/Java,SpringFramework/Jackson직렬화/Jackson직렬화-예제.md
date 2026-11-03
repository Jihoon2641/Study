---
주제: Jackson 직렬화 (객체 → JSON)
분류: Spring / Web / JSON
난이도: 이론
관련버전: Java 21 (Java 8 대응 코드 병기), Spring Boot 3.4.x (Jackson 2.18)
작성일: 2026-11-02
선행지식: [Spring MVC @RestController, JavaBeans getter 규칙, JPA 지연 로딩, JUnit 5]
---

# Jackson 직렬화 — 예제

관련 문서: [이론](./Jackson직렬화-이론.md)

---

## 1. 예제 개요

회원·팀·주문·이벤트 응답을 만들면서 Jackson이 **무엇을 프로퍼티로 보는지**, **언제 getter를 호출하는지**, **Boot의 `ObjectMapper`가 무엇이 다른지**를 재현하고 고친다. JPA와 DB 없이 일반 객체로 재현할 수 있는 것은 재현하고(양방향 참조, 지연 로딩 흉내), Hibernate 프록시는 8장 실습으로 남긴다.

| 예제 | 검증하는 개념 | 이론 문서 |
|---|---|---|
| `Member`(엔티티 흉내)를 그대로 직렬화 | 계산 getter·비밀번호 노출 | 5.2, 8.3 |
| `NoGetterResponse` | getter 없으면 `No serializer found` | 5.2, 6, 8.2 |
| `FlagResponse` → `FlagResponseFixed` | `is` 접두사·대문자 접두사 이름 변환 | 5.2, 8.4 |
| `Team` ↔ `Player` → `TeamResponse` | 양방향 참조 무한 재귀, DTO 변환 | 6, 8.1 |
| `LazyOrder` | 직렬화 = getter 호출 → 지연 로딩·N+1 | 5.3, 8.1 |
| `EventResponse` + 세 가지 `ObjectMapper` | 모듈·날짜 플래그, Boot 빈과 `new`의 차이 | 5.4, 8.5, 8.6 |
| `MemberRequest` | 모르는 필드 처리 차이 (역직렬화) | 5.4, 5.5, 8.5 |
| `MemberResponse` record / `MemberResponseV8` | 개선된 응답 DTO | 7.2, 7.3 |

---

## 2. 환경

- Java 21 (record 사용 — Java 8 대응 클래스 병기)
- Spring Boot 3.4.x → Jackson 2.18.x, `spring-boot-starter-json`
- 설정 방식: **Spring Boot 자동 구성**(`JacksonAutoConfiguration`)을 그대로 쓴다. 별도 Java Config 없음. 비교를 위해 `new ObjectMapper()`를 직접 만드는 코드는 일반 JUnit 테스트에 둔다.
- 테스트: JUnit 5 + AssertJ, Boot 매퍼 검증은 `@JsonTest` 슬라이스
- 빌드: Gradle

### `build.gradle`

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.4.1'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'study'
version = '0.0.1'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // starter-json = jackson-databind + jdk8 + jsr310 + parameter-names 모듈
    implementation 'org.springframework.boot:spring-boot-starter-json'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

### 패키지 구조

```
src/main/java/study/jackson
├── JacksonStudyApplication.java    (@JsonTest가 찾을 @SpringBootConfiguration)
├── JacksonDemo.java                (6장 출력용 main — new ObjectMapper 기준)
├── domain
│   └── Member.java                 (엔티티 흉내: password, isNew())
├── bad
│   ├── NoGetterResponse.java
│   ├── FlagResponse.java
│   ├── Team.java / Player.java     (양방향 참조)
│   ├── LazyOrder.java              (getter에서 "쿼리" 카운트)
│   └── EventResponse.java          (LocalDateTime / OffsetDateTime)
└── good
    ├── MemberResponse.java         (record, Java 16+)
    ├── MemberResponseV8.java       (Java 8 대응)
    ├── FlagResponseFixed.java
    ├── TeamResponse.java           (record) / TeamResponseV8.java
    └── MemberRequest.java          (요청 DTO, Java 8 스타일)
src/test/java/study/jackson
├── PlainObjectMapperTest.java      (new ObjectMapper 기준 동작)
└── BootObjectMapperTest.java       (@JsonTest — Boot 빈 기준 동작)
```

---

## 3. 잘못된 코드 먼저

### 3-1. 엔티티(도메인 객체)를 그대로 반환

```java
@GetMapping("/members/{id}")
public Member get(@PathVariable Long id) {
    return memberRepository.findById(id).orElseThrow();
}
```

`Member`에는 `getPassword()`와 편의 메서드 `isNew()`가 있다(5장 전체 코드).

```json
{"id":1,"name":"kim","password":"$2a$10$hash","tags":["java"],"new":false}
```

**문제**: `get`·`is`로 시작하는 public 메서드는 전부 프로퍼티가 된다(이론 5.2). 비밀번호 해시와 `new`라는 의미 없는 키가 API 스펙이 된다. 실제 JPA 엔티티였다면 여기에 지연 로딩·프록시·N+1 문제가 겹친다(이론 8.1). 이 부분은 3-4, 3-5에서 일반 객체로 재현한다.

### 3-2. getter 없는 응답 DTO

```java
public class NoGetterResponse {
    private final Long id;
    private final String name;
    // 생성자만 있고 getter 없음
}
```

**문제**: 보이는 프로퍼티가 하나도 없어서 `InvalidDefinitionException: No serializer found for class ...NoGetterResponse and no properties discovered to create BeanSerializer`로 500이 난다. getter가 일부만 없으면 에러 없이 **그 필드만 빠진다**. 이쪽이 더 위험하다.

### 3-3. boolean·대문자 접두사 필드 이름

```java
@Getter   // Lombok
public class FlagResponse {
    private final boolean isActive;   // → isActive()   → "active"
    private final Boolean isDeleted;  // → getIsDeleted() → "isDeleted"
    private final int xPos;           // → getXPos()    → "xpos"
}
```

**문제**: 세 필드가 모두 프론트가 기대하는 이름과 다르게 나가거나, 타입을 바꾸면 이름이 바뀐다. 키는 필드명이 아니라 **getter 이름**에서 만들어지기 때문이다(이론 8.4).

### 3-4. 양방향 참조

```java
team.getPlayers()   → [player1, player2]
player1.getTeam()   → team   → getPlayers() → player1 → getTeam() → ...
```

**문제**: `BeanSerializer`는 방문한 객체를 기억하지 않는다. `Infinite recursion (StackOverflowError)`, 또는 Jackson 2.16+에서는 중첩 깊이 초과 예외로 끝난다(이론 8.1).

### 3-5. getter에 부수효과가 있는 객체

```java
public List<String> getItems() {
    queryCounter.incrementAndGet();      // JPA 지연 로딩이라면 여기서 SELECT
    return ...;
}
```

**문제**: 주문 3개를 직렬화하면 "쿼리"가 3번 나간다. 직렬화가 곧 getter 호출이기 때문이다(이론 5.3). OSIV가 켜진 Boot 기본 설정에서 엔티티 목록을 반환하면 이것이 실제 N+1이 된다.

### 3-6. `new ObjectMapper()`로 날짜 직렬화

```java
String json = new ObjectMapper().writeValueAsString(event);   // LocalDateTime 필드
```

**문제**: `Java 8 date/time type 'java.time.LocalDateTime' not supported by default` 예외가 난다. 모듈만 등록해도 `[2026,11,2,10,15,30]` 배열로 나가서 API 응답(Boot 매퍼)과 형식이 다르다(이론 5.4, 8.5).

---

## 4. 개선된 코드

### 4-1. 응답 DTO로 변환해서 반환 (3-1, 3-4, 3-5 해결)

```java
// 서비스: 트랜잭션 안에서 필요한 연관관계를 읽고 DTO로 변환을 끝낸다
@Transactional(readOnly = true)
public MemberResponse find(Long id) {
    Member member = memberRepository.findById(id).orElseThrow();
    return MemberResponse.from(member);
}

// 컨트롤러: DTO만 반환
@GetMapping("/members/{id}")
public MemberResponse get(@PathVariable Long id) {
    return memberService.find(id);
}
```

**좋아진 점**:
- 노출 필드가 DTO에 명시되어 비밀번호가 나갈 수 없다.
- 직렬화 시점에 getter가 순수 값만 돌려주므로 지연 로딩과 N+1이 생기지 않는다.
- 응답이 커밋된 뒤 예외가 나서 200에 잘린 JSON이 가는 일(이론 8.7)도 생기지 않는다.
- 양방향 참조는 DTO에서 한쪽 방향만 남긴다(`TeamResponse`는 선수 이름만 가진다).

### 4-2. getter는 record나 Lombok으로 보장 (3-2 해결)

record(Java 16+)는 컴포넌트마다 접근자가 자동으로 생긴다. Java 8 코드베이스에서는 `final` 필드 + getter 클래스를 쓴다(`MemberResponseV8`).

### 4-3. API 키는 `@JsonProperty`로 고정 (3-3 해결)

```java
public class FlagResponseFixed {
    private final boolean active;     // 필드명에서 is 제거
    private final boolean deleted;
    private final int xPos;

    @JsonProperty("isActive")  public boolean isActive()  { return active; }
    @JsonProperty("isDeleted") public boolean isDeleted() { return deleted; }
    @JsonProperty("xPos")      public int getXPos()       { return xPos; }
}
```

**좋아진 점**: 필드 타입이나 Lombok 버전과 상관없이 키가 고정된다. 테스트로 키 이름을 단언해 두면 프론트 계약이 깨지는 것을 빌드에서 잡을 수 있다.

### 4-4. `ObjectMapper`는 Boot 빈을 주입받는다 (3-6 해결)

```java
@Component
public class EventPublisher {
    private final ObjectMapper objectMapper;   // Boot가 만든 빈

    public EventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(EventResponse event) throws JsonProcessingException {
        return objectMapper.writeValueAsString(event);   // API 응답과 같은 형식
    }
}
```

전역 설정을 바꿔야 하면 `application.yml`이나 커스터마이저 빈을 쓴다. 이 예제의 테스트는 기본 설정만 검증하므로 아래 설정은 참고용이다.

```yaml
spring:
  jackson:
    # 예: null 필드 생략. 프론트에서는 null이 아니라 undefined가 되므로 팀 합의 후 사용
    default-property-inclusion: non_null
```

```java
@Bean
Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
    // Boot 기본 설정 위에 덧붙인다. new ObjectMapper()로 갈아끼우지 않는다
    return builder -> builder.featuresToEnable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
}
```

### 4-5. 시각에는 오프셋을 포함한다 (이론 8.6)

`EventResponse`에 `OffsetDateTime`을 함께 두고 결과를 비교한다(6장, 7장). 오프셋이 있는 `"2026-11-02T10:15:30+09:00"`는 브라우저 타임존과 상관없이 같은 순간으로 해석된다.

---

## 5. 전체 코드

### `JacksonStudyApplication.java`

```java
package study.jackson;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @JsonTest 슬라이스가 이 클래스를 설정 기준점으로 찾는다
@SpringBootApplication
public class JacksonStudyApplication {

    public static void main(String[] args) {
        SpringApplication.run(JacksonStudyApplication.class, args);
    }
}
```

### `domain/Member.java`

```java
package study.jackson.domain;

import java.util.ArrayList;
import java.util.List;

/** JPA 엔티티를 흉내 낸 도메인 객체. 응답으로 직접 내보내면 안 되는 것들을 일부러 갖고 있다. */
public class Member {

    private final Long id;
    private final String name;
    private final String password;
    private final List<String> tags;

    public Member(Long id, String name, String password, List<String> tags) {
        this.id = id;
        this.name = name;
        this.password = password;
        this.tags = new ArrayList<>(tags);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    // 인증 로직에서 필요해서 만든 getter — Jackson도 이걸 프로퍼티로 본다
    public String getPassword() {
        return password;
    }

    public List<String> getTags() {
        return tags;
    }

    // 도메인 편의 메서드 — is 접두사 때문에 "new" 키가 된다
    public boolean isNew() {
        return id == null;
    }
}
```

### `bad/NoGetterResponse.java`

```java
package study.jackson.bad;

public class NoGetterResponse {

    private final Long id;
    private final String name;

    public NoGetterResponse(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
```

### `bad/FlagResponse.java`

```java
package study.jackson.bad;

/**
 * Lombok @Getter를 붙였을 때 생성되는 메서드를 그대로 손으로 적었다.
 *   boolean isActive  → isActive()      (Lombok은 boolean의 is 접두사를 중복하지 않는다)
 *   Boolean isDeleted → getIsDeleted()  (래퍼 타입은 get 접두사)
 *   int     xPos      → getXPos()
 */
public class FlagResponse {

    private final boolean isActive;
    private final Boolean isDeleted;
    private final int xPos;

    public FlagResponse(boolean isActive, Boolean isDeleted, int xPos) {
        this.isActive = isActive;
        this.isDeleted = isDeleted;
        this.xPos = xPos;
    }

    public boolean isActive() {
        return isActive;
    }

    public Boolean getIsDeleted() {
        return isDeleted;
    }

    public int getXPos() {
        return xPos;
    }
}
```

### `bad/Team.java`

```java
package study.jackson.bad;

import java.util.ArrayList;
import java.util.List;

public class Team {

    private final String name;
    private final List<Player> players = new ArrayList<>();

    public Team(String name) {
        this.name = name;
    }

    // JPA 양방향 연관관계의 "연관관계 편의 메서드"와 같은 모양
    public void addPlayer(Player player) {
        players.add(player);
        player.setTeam(this);
    }

    public String getName() {
        return name;
    }

    public List<Player> getPlayers() {
        return players;
    }
}
```

### `bad/Player.java`

```java
package study.jackson.bad;

public class Player {

    private final String name;
    private Team team;

    public Player(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    // Team → players → Player → team → Team ... 직렬화가 끝없이 따라간다
    public Team getTeam() {
        return team;
    }

    void setTeam(Team team) {
        this.team = team;
    }
}
```

### `bad/LazyOrder.java`

```java
package study.jackson.bad;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** getItems()가 JPA 지연 로딩처럼 "쿼리"를 실행한다고 가정하고 횟수만 센다. */
public class LazyOrder {

    private final Long id;
    // getter가 없으므로 직렬화되지 않는다 (8.2의 규칙을 역으로 이용)
    private final AtomicInteger queryCounter;

    public LazyOrder(Long id, AtomicInteger queryCounter) {
        this.id = id;
        this.queryCounter = queryCounter;
    }

    public Long getId() {
        return id;
    }

    public List<String> getItems() {
        queryCounter.incrementAndGet();   // 실제 엔티티라면: select * from order_item where order_id = ?
        return Collections.singletonList("book-" + id);
    }
}
```

### `bad/EventResponse.java`

```java
package study.jackson.bad;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

public class EventResponse {

    private final LocalDateTime localStartAt;     // 오프셋 없음
    private final OffsetDateTime offsetStartAt;   // 오프셋 포함 (개선 비교용)

    public EventResponse(LocalDateTime localStartAt, OffsetDateTime offsetStartAt) {
        this.localStartAt = localStartAt;
        this.offsetStartAt = offsetStartAt;
    }

    public LocalDateTime getLocalStartAt() {
        return localStartAt;
    }

    public OffsetDateTime getOffsetStartAt() {
        return offsetStartAt;
    }
}
```

### `good/MemberResponse.java` (Java 16+)

```java
package study.jackson.good;

import study.jackson.domain.Member;

import java.util.List;

// 노출할 필드만 컴포넌트로 둔다. 접근자 id(), name(), tags()가 자동 생성되고 Jackson 2.12+가 이를 프로퍼티로 쓴다
public record MemberResponse(Long id, String name, List<String> tags) {

    public static MemberResponse from(Member member) {
        // List.copyOf: 불변 복사본 — 응답 객체가 도메인 컬렉션을 공유하지 않게 한다 (Java 10+)
        return new MemberResponse(member.getId(), member.getName(), List.copyOf(member.getTags()));
    }
}
```

### `good/MemberResponseV8.java` (Java 8 대응)

```java
package study.jackson.good;

import study.jackson.domain.Member;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MemberResponseV8 {

    private final Long id;
    private final String name;
    private final List<String> tags;

    public MemberResponseV8(Long id, String name, List<String> tags) {
        this.id = id;
        this.name = name;
        this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
    }

    public static MemberResponseV8 from(Member member) {
        return new MemberResponseV8(member.getId(), member.getName(), member.getTags());
    }

    // getter가 곧 API 스펙이다. Lombok을 쓴다면 @Getter가 아래 세 메서드를 만든다
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getTags() {
        return tags;
    }
}
```

### `good/FlagResponseFixed.java`

```java
package study.jackson.good;

import com.fasterxml.jackson.annotation.JsonProperty;

public class FlagResponseFixed {

    private final boolean active;
    private final boolean deleted;
    private final int xPos;

    public FlagResponseFixed(boolean active, boolean deleted, int xPos) {
        this.active = active;
        this.deleted = deleted;
        this.xPos = xPos;
    }

    // 키를 명시적으로 고정: 필드 타입·Lombok 버전과 무관하게 프론트 계약이 유지된다
    @JsonProperty("isActive")
    public boolean isActive() {
        return active;
    }

    @JsonProperty("isDeleted")
    public boolean isDeleted() {
        return deleted;
    }

    @JsonProperty("xPos")
    public int getXPos() {
        return xPos;
    }
}
```

### `good/TeamResponse.java` (Java 16+) / `good/TeamResponseV8.java`

```java
package study.jackson.good;

import study.jackson.bad.Player;
import study.jackson.bad.Team;

import java.util.List;

// 양방향 중 "팀 → 선수 이름" 한 방향만 남긴다. 순환이 구조적으로 불가능하다
public record TeamResponse(String name, List<String> playerNames) {

    public static TeamResponse from(Team team) {
        return new TeamResponse(
                team.getName(),
                team.getPlayers().stream().map(Player::getName).toList());   // Stream#toList: Java 16+
    }
}
```

```java
package study.jackson.good;

import study.jackson.bad.Player;
import study.jackson.bad.Team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TeamResponseV8 {

    private final String name;
    private final List<String> playerNames;

    public TeamResponseV8(String name, List<String> playerNames) {
        this.name = name;
        this.playerNames = Collections.unmodifiableList(new ArrayList<>(playerNames));
    }

    public static TeamResponseV8 from(Team team) {
        List<String> names = new ArrayList<>();
        for (Player p : team.getPlayers()) {
            names.add(p.getName());
        }
        return new TeamResponseV8(team.getName(), names);
    }

    public String getName() {
        return name;
    }

    public List<String> getPlayerNames() {
        return playerNames;
    }
}
```

### `good/MemberRequest.java`

```java
package study.jackson.good;

/** 요청 DTO. 역직렬화는 기본 생성자 + setter 경로를 탄다 (이론 5.5). */
public class MemberRequest {

    private String name;

    public MemberRequest() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
```

### `JacksonDemo.java`

```java
package study.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import study.jackson.bad.EventResponse;
import study.jackson.bad.FlagResponse;
import study.jackson.bad.LazyOrder;
import study.jackson.bad.NoGetterResponse;
import study.jackson.bad.Player;
import study.jackson.bad.Team;
import study.jackson.domain.Member;
import study.jackson.good.FlagResponseFixed;
import study.jackson.good.MemberResponse;
import study.jackson.good.TeamResponse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 스프링 컨텍스트 없이 new ObjectMapper()로 실행한다.
 * Boot 빈과의 차이는 BootObjectMapperTest에서 검증한다.
 */
public class JacksonDemo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Member member = new Member(1L, "kim", "$2a$10$hash", Arrays.asList("java"));

        // [1] 엔티티(도메인 객체) 그대로 — password, new 노출
        System.out.println("[1] " + MAPPER.writeValueAsString(member));

        // [2] getter 없는 DTO
        try {
            MAPPER.writeValueAsString(new NoGetterResponse(1L, "kim"));
        } catch (JsonProcessingException e) {
            System.out.println("[2] " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }

        // [3] 이름 변환 / [4] @JsonProperty로 고정
        System.out.println("[3] " + MAPPER.writeValueAsString(new FlagResponse(true, false, 10)));
        System.out.println("[4] " + MAPPER.writeValueAsString(new FlagResponseFixed(true, false, 10)));

        // [5] 양방향 참조 / [6] DTO 변환
        Team team = new Team("dev");
        team.addPlayer(new Player("kim"));
        team.addPlayer(new Player("lee"));
        try {
            MAPPER.writeValueAsString(team);
        } catch (JsonProcessingException e) {
            // 2.15 이하: "Infinite recursion (StackOverflowError)", 2.16+: 중첩 깊이 초과 — 버전마다 다르다
            System.out.println("[5] 양방향 직렬화 실패: " + e.getClass().getSimpleName());
        }
        System.out.println("[6] " + MAPPER.writeValueAsString(TeamResponse.from(team)));

        // [7] 직렬화 = getter 호출
        AtomicInteger queries = new AtomicInteger();
        List<LazyOrder> orders = Arrays.asList(
                new LazyOrder(1L, queries), new LazyOrder(2L, queries), new LazyOrder(3L, queries));
        System.out.println("[7] 직렬화 전 쿼리 수 = " + queries.get());
        String ordersJson = MAPPER.writeValueAsString(orders);
        System.out.println("[7] 직렬화 후 쿼리 수 = " + queries.get() + "  " + ordersJson);

        // [8] 모듈 없는 매퍼로 java.time / [9] 모듈만 등록한 매퍼
        EventResponse event = new EventResponse(
                LocalDateTime.of(2026, 11, 2, 10, 15, 30),
                OffsetDateTime.of(2026, 11, 2, 10, 15, 30, 0, ZoneOffset.ofHours(9)));
        try {
            MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            System.out.println("[8] " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }
        ObjectMapper withModule = new ObjectMapper().registerModule(new JavaTimeModule());
        System.out.println("[9] " + withModule.writeValueAsString(event));

        // [10] 개선된 응답 DTO
        System.out.println("[10] " + MAPPER.writeValueAsString(MemberResponse.from(member)));
    }

    private static String firstLine(String message) {
        int cut = message.indexOf(" (");
        return cut > 0 ? message.substring(0, cut) : message;
    }
}
```

---

## 6. 실행 결과

> **예상 출력**이다. 이 문서 작성 환경에 JDK가 없어서 실행하지 못했다. Jackson 2.18 동작에서 도출했다.
> - **프로퍼티 순서**: 필드와 연결된 프로퍼티는 필드 선언 순서를 따르고, 필드 없는 getter 프로퍼티는 그 뒤에 온다. getter끼리의 순서는 리플렉션이 돌려주는 메서드 순서에 따라 **JVM마다 다를 수 있다**. 테스트는 순서에 의존하지 않게 작성했다.
> - **예외 메시지**: 문구는 Jackson 버전에 따라 다를 수 있다. 특히 [5]의 예외 종류는 2.15 이하와 2.16 이상이 다르다(확인 필요).

`JacksonDemo` 실행 (`new ObjectMapper()` 기준):

```
[1] {"id":1,"name":"kim","password":"$2a$10$hash","tags":["java"],"new":false}
[2] InvalidDefinitionException: No serializer found for class study.jackson.bad.NoGetterResponse and no properties discovered to create BeanSerializer
[3] {"isDeleted":false,"active":true,"xpos":10}
[4] {"isActive":true,"isDeleted":false,"xPos":10}
[5] 양방향 직렬화 실패: JsonMappingException
[6] {"name":"dev","playerNames":["kim","lee"]}
[7] 직렬화 전 쿼리 수 = 0
[7] 직렬화 후 쿼리 수 = 3  [{"id":1,"items":["book-1"]},{"id":2,"items":["book-2"]},{"id":3,"items":["book-3"]}]
[8] InvalidDefinitionException: Java 8 date/time type `java.time.LocalDateTime` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" to enable handling
[9] {"localStartAt":[2026,11,2,10,15,30],"offsetStartAt":1793582130.000000000}
[10] {"id":1,"name":"kim","tags":["java"]}
```

같은 `EventResponse`를 **Boot `ObjectMapper` 빈**으로 직렬화하면(`BootObjectMapperTest`):

```
{"localStartAt":"2026-11-02T10:15:30","offsetStartAt":"2026-11-02T10:15:30+09:00"}
```

결과 해석:

| 출력 | 무엇을 보여주나 |
|---|---|
| [1] | `getPassword()`, `isNew()` 때문에 비밀번호와 `new`가 노출된다 |
| [3] | `isActive()`는 `active`, `getIsDeleted()`는 `isDeleted`, `getXPos()`는 `xpos`가 된다. private 필드 `isActive`, `xPos`는 이름이 맞지 않아 사라진다 |
| [4] | `@JsonProperty`로 세 키가 모두 의도대로 고정된다. 이름을 바꾼 프로퍼티는 목록 뒤로 재배치될 수 있어서 순서는 다를 수 있다 |
| [7] | 직렬화가 getter를 3번 호출했다. 엔티티였다면 SELECT 3번(N+1)이다 |
| [8] → [9] → Boot | 모듈 없음 → 예외 / 모듈만 → 배열과 epoch 초 / Boot → ISO 문자열. **같은 객체가 매퍼에 따라 세 가지로 나온다** |
| [9] `1793582130.000000000` | 2026-11-02T01:15:30Z의 epoch 초. `WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS` 기본값 때문에 소수 9자리가 붙는다 |

---

## 7. 검증 코드

### `PlainObjectMapperTest.java`

```java
package study.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;
import study.jackson.bad.EventResponse;
import study.jackson.bad.FlagResponse;
import study.jackson.bad.LazyOrder;
import study.jackson.bad.NoGetterResponse;
import study.jackson.bad.Player;
import study.jackson.bad.Team;
import study.jackson.domain.Member;
import study.jackson.good.FlagResponseFixed;
import study.jackson.good.MemberRequest;
import study.jackson.good.MemberResponse;
import study.jackson.good.MemberResponseV8;
import study.jackson.good.TeamResponse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스프링 없이 new ObjectMapper()의 기본 동작을 고정한다. 순서에 의존하지 않도록 JsonNode로 검사한다. */
class PlainObjectMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final Member member = new Member(1L, "kim", "$2a$10$hash", Arrays.asList("java"));

    private JsonNode toTree(Object value) throws JsonProcessingException {
        return mapper.readTree(mapper.writeValueAsString(value));
    }

    @Test
    void getter가_있으면_필드가_없어도_나가고_도메인_객체는_비밀번호를_노출한다() throws Exception {
        JsonNode json = toTree(member);

        assertThat(json.has("password")).isTrue();
        assertThat(json.has("new")).isTrue();       // isNew() 편의 메서드
    }

    @Test
    void 응답_DTO는_명시한_필드만_나간다() throws Exception {
        JsonNode record = toTree(MemberResponse.from(member));
        JsonNode v8 = toTree(MemberResponseV8.from(member));

        assertThat(record.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name", "tags");
        assertThat(v8).isEqualTo(record);           // Java 8 클래스와 record가 같은 JSON
    }

    @Test
    void getter가_없으면_No_serializer_found() {
        assertThatThrownBy(() -> mapper.writeValueAsString(new NoGetterResponse(1L, "kim")))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("No serializer found");
    }

    @Test
    void 키_이름은_getter에서_만들어진다() throws Exception {
        JsonNode json = toTree(new FlagResponse(true, false, 10));

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("active", "isDeleted", "xpos");
    }

    @Test
    void JsonProperty로_키를_고정한다() throws Exception {
        JsonNode json = toTree(new FlagResponseFixed(true, false, 10));

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("isActive", "isDeleted", "xPos");
        assertThat(json.get("isActive").asBoolean()).isTrue();
    }

    @Test
    void 양방향_참조는_직렬화에_실패하고_DTO는_성공한다() throws Exception {
        Team team = new Team("dev");
        team.addPlayer(new Player("kim"));

        // 2.15 이하는 Infinite recursion, 2.16+는 중첩 깊이 초과 — 둘 다 JsonProcessingException 계열
        assertThatThrownBy(() -> mapper.writeValueAsString(team))
                .isInstanceOf(JsonProcessingException.class);

        assertThat(toTree(TeamResponse.from(team)).get("playerNames").get(0).asText()).isEqualTo("kim");
    }

    @Test
    void 직렬화는_getter를_원소마다_호출한다() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        List<LazyOrder> orders = Arrays.asList(
                new LazyOrder(1L, queries), new LazyOrder(2L, queries), new LazyOrder(3L, queries));

        mapper.writeValueAsString(orders);

        assertThat(queries.get()).isEqualTo(3);    // 엔티티였다면 N+1
    }

    @Test
    void 모듈_없는_매퍼는_LocalDateTime을_직렬화하지_못한다() {
        EventResponse event = new EventResponse(
                LocalDateTime.of(2026, 11, 2, 10, 15, 30),
                OffsetDateTime.of(2026, 11, 2, 10, 15, 30, 0, ZoneOffset.ofHours(9)));

        assertThatThrownBy(() -> mapper.writeValueAsString(event))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("jackson-datatype-jsr310");
    }

    @Test
    void 기본_매퍼는_모르는_필드에서_실패한다() {
        String body = "{\"name\":\"kim\",\"nickname\":\"k\"}";

        assertThatThrownBy(() -> mapper.readValue(body, MemberRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void ObjectMapper는_serializer를_인스턴스_단위로_캐시한다() throws Exception {
        // 캐시 자체를 단언하는 공개 API는 없다. 같은 매퍼 재사용 시 결과가 같고 예외가 없음을 확인하는 정도로 둔다
        String first = mapper.writeValueAsString(MemberResponse.from(member));
        String second = mapper.writeValueAsString(MemberResponse.from(member));

        assertThat(second).isEqualTo(first);
    }
}
```

### `BootObjectMapperTest.java`

```java
package study.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import study.jackson.bad.EventResponse;
import study.jackson.good.MemberRequest;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @JsonTest: JacksonAutoConfiguration만 올리는 슬라이스 테스트.
 * 컨트롤러 응답에 쓰이는 것과 같은 방식으로 구성된 ObjectMapper 빈을 주입받는다.
 */
@JsonTest
class BootObjectMapperTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void Boot_매퍼의_기본값은_new_ObjectMapper와_다르다() {
        assertThat(objectMapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();

        ObjectMapper plain = new ObjectMapper();
        assertThat(plain.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
        assertThat(plain.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    }

    @Test
    void 날짜는_ISO_문자열이고_OffsetDateTime은_오프셋을_포함한다() throws Exception {
        EventResponse event = new EventResponse(
                LocalDateTime.of(2026, 11, 2, 10, 15, 30),
                OffsetDateTime.of(2026, 11, 2, 10, 15, 30, 0, ZoneOffset.ofHours(9)));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(json.get("localStartAt").asText()).isEqualTo("2026-11-02T10:15:30");
        // spring.jackson.time-zone을 설정하면 이 값이 그 타임존으로 바뀔 수 있다 (확인 필요)
        assertThat(json.get("offsetStartAt").asText()).isEqualTo("2026-11-02T10:15:30+09:00");
    }

    @Test
    void 모르는_필드는_무시한다() throws Exception {
        String body = "{\"name\":\"kim\",\"nickname\":\"k\"}";

        MemberRequest request = objectMapper.readValue(body, MemberRequest.class);

        assertThat(request.getName()).isEqualTo("kim");
    }
}
```

---

## 8. 변형 실습

### 실습 1. 진짜 Hibernate 프록시로 `No serializer found` 보기

`spring-boot-starter-data-jpa`와 PostgreSQL(또는 Testcontainers)을 추가한다. `Member`에 `@ManyToOne(fetch = LAZY) Team team`을 두고, `memberRepository.findById(id)`로 읽은 엔티티를 `@RestController`에서 그대로 반환해 보자.
- 예상 (OSIV 켜짐, Boot 기본): `No serializer found for class org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor ... ["team"]->...["hibernateLazyInitializer"]`. 프록시가 가진 `getHibernateLazyInitializer()`가 프로퍼티로 잡히기 때문이다(이론 8.1). 메시지의 reference chain 형식은 버전에 따라 다를 수 있다.
- `spring.jpa.open-in-view=false`로 바꾸면 `LazyInitializationException`이 `HttpMessageNotWritableException`으로 감싸져 나온다. 두 경우 모두 `MemberResponse.from(...)`으로 바꾸면 사라진다.

### 실습 2. `LazyOrder`를 많이 만들어 "200에 잘린 JSON" 재현하기

`LazyOrder#getItems()`를 `id == 500`일 때 `IllegalStateException`을 던지도록 바꾸고, 주문 1000개 목록을 반환하는 컨트롤러를 만들어 `curl -i`로 호출해 보자.
- 예상: 상태는 `200`이고 본문이 중간에 끊긴다. 서버 로그에는 `HttpMessageNotWritableException`이 남는다. 실패 위치를 `id == 1`로 바꾸면 응답 버퍼가 차기 전이라 정상적인 500 에러 응답이 온다(이론 8.7, 11장 5번).
- `server.tomcat` 응답 버퍼 크기 설정에 따라 경계가 달라진다(확인 필요).

### 실습 3. `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY`를 켜면?

`spring.jackson.mapper.sort-properties-alphabetically=true`를 `@JsonTest(properties = ...)`로 주고 `FlagResponse`를 직렬화해 보자.
- 예상: 키가 알파벳순(`active`, `isDeleted`, `xpos`)으로 정렬된다. 6장에서 "getter 순서는 JVM마다 다를 수 있다"고 한 문제를 이 설정으로 없앨 수 있다. 응답 JSON을 스냅샷으로 비교하는 테스트가 있다면 이 설정이 유용하다.
- record는 이 설정이 없어도 컴포넌트 선언 순서를 따른다.
