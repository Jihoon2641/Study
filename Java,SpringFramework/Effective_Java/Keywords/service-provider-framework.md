# 서비스 제공자 프레임워크 (service provider framework)

## 한 줄 정의
API(인터페이스)를 먼저 정의해 두고, 그 구현체(제공자)는 나중에 별도로 만들어 런타임에 등록·선택되게 하는 구조.

## 도입 버전
- 구조 자체는 모든 버전에서 직접 만들 수 있다. JDBC는 Java 1.1부터 이 구조다.
- `java.util.ServiceLoader` — Java 6+. `META-INF/services/{인터페이스 FQCN}` 파일에 구현 클래스 이름을 적으면 찾아 준다.
- JDBC 4.0 드라이버 자동 등록 — Java 6+. 이 덕분에 `Class.forName("...Driver")` 호출이 불필요해졌다.
- 모듈 선언의 `uses` / `provides ... with` — Java 9+. 모듈 환경에서는 제공자 클래스에 `public static provider()` 메서드를 두어 정적 팩터리로 제공할 수도 있다.

## 무엇을 하는가
네 구성요소로 설명한다(JDBC 대응).

- 서비스 인터페이스 — 구현체가 따라야 할 동작 (`Connection`)
- 제공자 등록 API — 구현체를 시스템에 등록 (`DriverManager.registerDriver`)
- 서비스 접근 API — 클라이언트가 인스턴스를 얻는 정적 팩터리 (`DriverManager.getConnection`)
- 서비스 제공자 인터페이스(선택) — 서비스 인터페이스의 인스턴스를 만드는 팩터리 (`Driver`). 없으면 리플렉션으로 구현을 인스턴스화한다.

`new MySqlConnection()`처럼 생성자를 쓰면 컴파일 시점에 구체 클래스 이름이 소스에 박혀야 한다. 서비스 접근 API는 정적 팩터리이므로 반환 타입만 인터페이스로 고정하고, 실제 구현은 런타임에 클래스패스(또는 모듈 경로)에서 찾는다. `ServiceLoader`는 설정 파일에서 이름을 읽어 클래스 로더로 로드한 뒤 인스턴스화하며, 이터레이터를 돌 때 지연(lazy) 로딩한다.

의존 객체 주입(DI) 프레임워크도 같은 발상을 더 일반화한 강력한 서비스 제공자로 볼 수 있다.

## 최소 사용 예
```java
// Java 6+ : 구현체 jar만 클래스패스에 넣으면 코드 수정 없이 교체된다
public interface Greeter { String greet(String name); }

// META-INF/services/Greeter 파일 내용: com.example.KoreanGreeter
ServiceLoader<Greeter> loader = ServiceLoader.load(Greeter.class);
for (Greeter g : loader) {
    System.out.println(g.greet("자바"));
}
// Java 9+ : loader.findFirst() 로 Optional<Greeter> 를 받을 수도 있다
```

## 자주 하는 오해 / 헷갈리는 짝
- **`Class.forName`으로 드라이버를 꼭 로딩해야 한다** — Java 6 + JDBC 4.0 이상 드라이버라면 필요 없다. 레거시 코드에 남아 있는 건 그 이전의 관습이다. 판단 기준은 드라이버 jar에 `META-INF/services/java.sql.Driver` 파일이 있는가다.
- **ServiceLoader vs 스프링 DI** — 기준은 "누가 구현을 고르는가". ServiceLoader는 클래스패스에 있는 구현을 전부 발견만 해 주고 선택은 호출자가 한다. 스프링은 설정·조건에 따라 컨테이너가 골라 주입한다. 스프링 부트의 자동 설정 목록도 비슷한 파일 기반 발견 방식을 쓴다.

## 등장하는 아이템
- [아이템 1. 생성자 대신 정적 팩터리 메서드를 고려하라](../Item1/item-1-static-factory-method.md) — 다섯 번째 장점 "작성 시점에 반환할 클래스가 없어도 된다"의 대표 사례로 등장한다.
