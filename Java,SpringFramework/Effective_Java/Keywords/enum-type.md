# 열거 타입 (enum type, `java.lang.Enum<E>`)

## 한 줄 정의
정해진 상수 인스턴스들만 존재하도록 언어와 JDK가 함께 보증하는 특수한 클래스.

## 도입 버전
(Java 5+) Java 7 레거시에서도 그대로 쓸 수 있다.
- `EnumSet`, `EnumMap` — Java 5+
- `switch`에서 열거 상수 사용 — Java 5+ / 화살표 `case` — Java 14+ / 패턴 매칭 switch — Java 21+
- 봉인 인터페이스(`sealed`)를 열거 타입이 구현 — Java 17+

## 무엇을 하는가
겉보기에는 상수 목록이지만 실체는 클래스다. javac는 `enum Color { RED, GREEN }`를 대략 다음처럼 컴파일한다.

```
final class Color extends java.lang.Enum<Color>       // ACC_ENUM 플래그
  public static final Color RED, GREEN;               // 상수 = public static final 필드
  private static final Color[] $VALUES;
  private Color(String name, int ordinal);            // 이름·순서를 받는 생성자
  static { RED = new Color("RED", 0); GREEN = new Color("GREEN", 1); ... }
  public static Color[] values();  public static Color valueOf(String);
```

즉 구조는 "`private` 생성자 + `public static final` 필드"다. 특별한 점은 JDK의 여러 객체 생성 경로가 `ACC_ENUM` 플래그를 보고 추가 인스턴스 생성을 거부한다는 것이다. JLS 8.9가 이를 규정한다.

- **`new`** — 컴파일 에러. 생성자는 암묵적으로 `private`.
- **리플렉션** — `Constructor.newInstance`가 열거 타입이면 `IllegalArgumentException("Cannot reflectively create enum objects")`를 던진다.
- **직렬화** — 상수의 이름만 기록하고, 역직렬화할 때는 `Enum.valueOf`로 기존 상수를 찾는다. 클래스에 선언한 `readObject`, `writeObject`, `readResolve`는 무시되고 필드 값은 전송되지 않는다.
- **`clone`** — `Enum.clone()`이 `final`이고 예외를 던진다.

그래서 열거 상수끼리는 `==` 비교가 안전하고(같은 클래스 로더 안이라면), 원소 하나짜리 열거 타입은 가장 견고한 싱글턴이 된다. `equals`와 `hashCode`도 `Enum`에서 `final`로 막혀 있다.

## 최소 사용 예
```java
public enum Operation {
    PLUS("+")  { public int apply(int x, int y) { return x + y; } },
    MINUS("-") { public int apply(int x, int y) { return x - y; } };

    private final String symbol;
    Operation(String symbol) { this.symbol = symbol; }   // 암묵적으로 private
    public abstract int apply(int x, int y);             // 상수별 메서드 구현
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **`ordinal()`을 값으로 저장해도 된다** — 기준은 "선언 순서가 바뀔 수 있는가". 상수를 중간에 추가하면 `ordinal`이 밀린다. DB나 파일에 저장할 값은 별도 필드로 둔다.
- **열거 타입 vs `sealed` 계층** — 기준은 "인스턴스가 고정인가, 하위 타입만 고정인가". 상수 인스턴스 자체가 정해져 있으면 열거 타입, 종류는 정해져 있지만 각 종류의 인스턴스가 서로 다른 값을 가져야 하면 `sealed interface` + `record`(Java 17+)가 맞다.
- **열거 타입은 필드를 가질 수 없다/불변이다** — 필드와 메서드를 가질 수 있고, 가변 필드를 두면 전역 가변 상태가 된다. 불변이 자동으로 보장되지는 않는다.

## 등장하는 아이템
- [아이템 3. private 생성자나 열거 타입으로 싱글턴임을 보증하라](../Item3/item-3-singleton.md) — 원소 하나짜리 열거 타입이 리플렉션·직렬화·`clone`을 모두 막는 가장 견고한 싱글턴으로 등장한다.
