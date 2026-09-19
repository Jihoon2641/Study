# 유틸리티 클래스 (utility class)

## 한 줄 정의
정적 메서드와 정적 필드만 모아 두어 인스턴스를 만들 이유가 없는 클래스. 헬퍼 클래스(helper class)라고도 한다.

## 도입 버전
(모든 버전) 설계 기법이다. 관련 기능의 버전은 다음과 같다.
- `private` 생성자로 인스턴스화 차단 — 모든 버전
- 정적 임포트(`import static`) — Java 5+
- 인터페이스의 `static` 메서드 — Java 8+ / `private` 메서드 — Java 9+
- `java.util.Objects` — Java 7+

## 무엇을 하는가
특정 객체의 상태와 무관한 함수들을 한 클래스에 모아 이름 공간(namespace)을 만든다. JDK의 대표적인 예가 `java.lang.Math`, `java.util.Arrays`, `java.util.Collections`, `java.util.Objects`다.

설계 규칙은 세 가지다.

1. **`private` 생성자를 반드시 선언한다.** 생성자를 하나도 쓰지 않으면 컴파일러가 `public` 기본 생성자를 대신 만들어 주기 때문이다. 생성자가 하나라도 선언되어 있으면 자동 생성이 일어나지 않는다.
2. **생성자 본문에서 `AssertionError`를 던진다.** 클래스 바깥은 `private`으로 막히지만, 클래스 안(그리고 Java 11+에서는 같은 둥지의 중첩 클래스)에서는 여전히 호출할 수 있기 때문이다. JDK 자신은 대개 빈 생성자를 쓴다.
3. **상속은 자동으로 막힌다.** 하위 클래스 생성자는 상위 생성자를 호출해야 하는데(JLS 8.8.7) 호출할 수 있는 생성자가 없다. 의도를 드러내려고 `final`을 함께 붙이기도 한다.

`abstract`로 선언하는 것은 해법이 아니다. 하위 클래스를 만들면 인스턴스가 생기고, `abstract`는 오히려 "상속해서 쓰라"는 신호로 읽힌다.

## 최소 사용 예
```java
public final class MathUtils {
    // 기본 생성자가 만들어지는 것을 막는다(인스턴스화 방지용)
    private MathUtils() { throw new AssertionError(); }

    public static double round(double v) { return Math.floor(v + 0.5); }
}
// import static 은 Java 5+ : import static com.example.MathUtils.round;
```

## 자주 하는 오해 / 헷갈리는 짝
- **유틸리티 클래스 vs 싱글턴** — 기준은 "인스턴스가 필요한가". 인터페이스 구현, 다형성, 의존 주입, 직렬화가 필요하면 싱글턴(인스턴스 1개)이고, 순수 함수 묶음이면 유틸리티 클래스(인스턴스 0개)다.
- **유틸리티 클래스 vs 도메인 메서드** — 기준은 "특정 객체의 데이터를 꺼내 쓰는가". `OrderUtils.calculateTotal(order)`처럼 한 객체의 내부를 파고드는 함수라면 그 객체의 메서드로 옮기는 편이 낫다. 유틸리티가 늘어나는 것은 절차적 설계로 기울고 있다는 신호다.
- **인터페이스의 static 메서드로 만들면 더 낫다** — 인스턴스화는 막히지만 `implements`로 누구나 붙을 수 있어 상속 경로가 오히려 열린다. 관련 없는 정적 메서드를 모으는 용도로는 쓰지 않는다.
- **스프링에서는 `@Component`를 붙이면 된다** — 정적 필드에는 주입이 되지 않아 조용히 `null`로 남는다. 주입이 필요하면 정적 유틸리티가 아니라 빈으로 만든다.

## 등장하는 아이템
- [아이템 4. 인스턴스화를 막으려거든 private 생성자를 사용하라](../Item4/item-4-noninstantiable-utility-class.md) — 이 아이템의 주제 자체. 기본 생성자 자동 생성, `abstract`가 안 되는 이유, `AssertionError`를 다룬다.
- [아이템 5. 자원을 직접 명시하지 말고 의존 객체 주입을 사용하라](../Item5/item-5-dependency-injection.md) — 자원에 따라 동작이 달라지는 클래스를 정적 유틸리티로 만들면 안 되는 이유를 다룬다. 자원을 쓰지 않는 순수 함수라면 여전히 유틸리티 클래스가 맞다.
