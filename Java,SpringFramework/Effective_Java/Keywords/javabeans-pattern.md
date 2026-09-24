# 자바빈즈 패턴 (JavaBeans pattern)

## 한 줄 정의
매개변수 없는 생성자로 객체를 만든 뒤, `setXxx` 세터 메서드를 호출해 필드 값을 하나씩 채우는 객체 생성 방식.

## 도입 버전
- JavaBeans 명세와 `java.beans` 패키지(`Introspector`, `PropertyDescriptor`) — Java 1.1+
- 이름 규약 자체는 언어 기능이 아니라 명세상의 관례라 모든 버전에서 쓸 수 있다.

## 무엇을 하는가
JavaBeans 명세는 원래 GUI 빌더 같은 도구가 컴포넌트를 **리플렉션으로 조작**할 수 있게 하려고 만든 규약이다. 그 핵심은 세 가지다.
- `public` 기본 생성자 — 도구가 클래스 이름만 알고도 `newInstance`로 만들 수 있게
- 프로퍼티 접근자 이름 규약 — `getName()`/`setName(String)`, `boolean`은 `isActive()`
- (선택) `Serializable` 구현

`Introspector.getBeanInfo(Class)`는 메서드 이름을 파싱해 "`name`이라는 프로퍼티가 있다"는 정보를 만든다. 이후 JPA, Jackson, 스프링의 데이터 바인딩 같은 프레임워크들이 이 규약에 기대어 객체를 만들고 채우면서, 도구용 규약이 일반 코드의 객체 생성 방식으로 퍼졌다.

생성 방식으로서의 약점은 언어 규칙에서 나온다. `final` 인스턴스 필드는 생성자가 끝나기 전에 반드시 대입되어야 하는데(JLS 8.3.1.2, 16장 Definite Assignment), 세터는 생성자가 끝난 **뒤에** 호출되므로 세터로 채우는 필드는 `final`일 수 없다. 따라서
- 객체는 구조적으로 가변이고 불변 객체로 만들 수 없다.
- `new` 직후부터 세터를 다 부를 때까지 필수 값이 비어 있는 **일관성이 깨진 상태**가 존재하고, 그 상태를 누구든 읽을 수 있다.
- 필수 값 검사를 모아 둘 "완성 시점"이 없어서, 누락이 사용 시점에야 드러난다.
- 여러 스레드가 보는 객체라면 동기화 책임이 사용자에게 넘어간다.

## 최소 사용 예
```java
public class Member {
    private String name;
    private int age;
    public Member() { }                       // 기본 생성자
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }
}
Member m = new Member();
m.setName("kim");      // setAge 를 빠뜨려도 컴파일러는 모른다 → age == 0
```

## 자주 하는 오해 / 헷갈리는 짝
- **게터·세터가 있으면 캡슐화다** — 판단 기준은 "불변식을 지킬 수 있는가". 모든 필드에 무조건 세터를 열면 필드를 `public`으로 둔 것과 거의 같다. 세터는 필요할 때만, 검증과 함께 연다.
- **프레임워크 때문에 무조건 자바빈즈여야 한다** — Jackson은 `@JsonCreator`·빌더로, 스프링 부트 설정 바인딩은 생성자·레코드로도 동작한다. JPA 엔티티는 기본 생성자가 필요하지만 `protected`로 숨길 수 있고, 세터 없이 필드 접근으로 매핑할 수 있다.
- **자바빈즈 vs 빌더** — 기준은 "완성 시점이 있는가". 빌더는 `build()`라는 완성·검증 시점이 있고, 자바빈즈에는 없다.

## 등장하는 아이템
- [아이템 2. 생성자에 매개변수가 많다면 빌더를 고려하라](../Item2/item-2-builder.md) — 점층적 생성자의 대안으로 소개되지만, 일관성이 깨지고 불변으로 만들 수 없다는 이유로 빌더에 밀리는 방식으로 등장한다.
- [아이템 16. public 클래스에서는 public 필드가 아닌 접근자 메서드를 사용하라](../Item16/item-16-accessor-methods.md) — 접근자 이름 규약 `getX`/`setX`의 출처로 등장한다. 레코드의 `x()` 접근자가 이 규약과 다르다는 점도 함께 다룬다.
