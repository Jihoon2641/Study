# 아이템 4. 인스턴스화를 막으려거든 private 생성자를 사용하라

## 한 줄 결론

생성자를 하나도 쓰지 않으면 "생성자가 없는 클래스"가 되는 게 아니라, **컴파일러가 `public` 기본 생성자(default constructor)를 대신 만들어 준다.** 인스턴스화를 막는 유일한 방법은 생성자를 **없애는 것이 아니라 `private`으로 하나 선언해 두는 것**이다.

`abstract`로 선언하는 건 답이 아니다. 하위 클래스를 만들면 인스턴스가 생기고, 그보다 나쁜 건 `abstract`가 **"상속해서 쓰라"는 신호로 읽힌다**는 점이다.

---

## 신입 눈높이 설명

정적 메서드와 정적 필드만 모아 둔 클래스가 있다. `java.lang.Math`, `java.util.Arrays`, `java.util.Collections`, `java.util.Objects`가 전부 그런 클래스다. 이런 클래스는 객체를 만들 이유가 없다. `new Math()`로 만든 `Math` 객체는 아무 상태도 없고 할 수 있는 일도 없다.

여기서 흔한 오해가 하나 있다. "생성자를 안 썼으니까 못 만들겠지"다. 자바는 반대로 동작한다. **생성자를 하나도 안 쓴 클래스에만** 컴파일러가 매개변수 없는 생성자를 자동으로 넣는다. 그래서 `new Math()`가 컴파일된다. 실제로 이 오해 때문에 JDK 초창기의 `java.util.Arrays`도 한동안 `public` 생성자가 열려 있었다.

문서상으로도 구분이 안 된다. 자동 생성된 기본 생성자는 Javadoc에 일반 생성자와 똑같이 표시되므로, 읽는 사람은 **설계자가 의도해서 연 것인지 실수로 열린 것인지 알 수 없다.**

---

# 1단계 — 생성자를 안 쓰면 컴파일러가 만들어 준다

## ① 흔히 쓰는(나쁜) 코드

```java
public class MathUtils {
    static double round(double v) { return Math.floor(v + 0.5); }
    // 생성자를 안 썼다. "정적 메서드뿐이니 아무도 인스턴스를 못 만들겠지"
}
```

## ② 무엇이 문제인가

```java
MathUtils u = new MathUtils();          // 컴파일 통과, 예외 없음
u.round(2.5);                            // 인스턴스로 정적 메서드 호출 — 이것도 통과
```

예제 파일 `Item4Example.java`를 실행하면 `MathUtils.class.getDeclaredConstructors()`가 **소스에 쓴 적 없는 `public` 생성자 1개**를 돌려주는 것을 직접 볼 수 있다.

당장 터지는 건 없다. 문제는 이 클래스가 "인스턴스를 만들어도 되는 클래스"로 보인다는 데서 시작한다.

- 유틸리티 클래스를 만든 사람은 정적 메서드만 쓸 생각이었다. 테스트도 `MathUtils.round(...)`로만 짜여 있으니 전부 통과한다.
- 반년 뒤 다른 팀원이 `round`를 "그냥 물려받아 쓰려고" `class ReportService extends MathUtils`라고 쓴다. 상속이 열려 있으니 컴파일된다.
- 다시 얼마 뒤, 반올림 규칙을 바꾸려고 `ReportService`에 같은 시그니처의 `static double round(double)`을 추가한다. 재정의했다고 생각하지만 **정적 메서드는 재정의(overriding)되지 않고 은닉(hiding)된다.** `@Override`를 붙여 확인하려 해도 `static methods cannot be annotated with @Override` 컴파일 에러라 붙일 수조차 없다.
- 이제 같은 객체를 두고 `ReportService.round(2.5)`는 2.0, `MathUtils` 타입 참조로 부르는 `ref.round(2.5)`는 3.0을 돌려준다. **컴파일 에러 없음. 런타임 예외 없음. 로그 한 줄 안 남는다.** 참조 변수를 어떤 타입으로 선언했느냐에 따라 금액 반올림 결과가 갈린다.

상속한 사람과 규칙을 바꾼 사람과 잘못된 금액을 보고받는 사람이 전부 다르다. 원인 코드는 `extends` 한 단어라 리뷰에서도 거의 걸리지 않는다.

근본 원인은 하나다. **클래스가 "인스턴스로 쓰지 말라, 상속하지 말라"를 코드로 선언하지 않았고, 컴파일러의 기본값은 정반대(생성 가능, 상속 가능)이기 때문이다.**

## 원리 — 기본 생성자는 언제, 어떤 접근 수준으로 생기는가

JLS 8.8.9가 정한다. **클래스에 생성자 선언이 하나도 없으면** 컴파일러가 매개변수 없는 기본 생성자를 자동으로 추가한다. 이때 접근 수준은 **그 클래스의 접근 수준을 그대로 따른다.** `public class`면 `public` 생성자, package-private 클래스면 package-private 생성자다. 본문은 상위 클래스의 인자 없는 생성자를 호출하는 `super()` 한 줄뿐이다.

바이트코드로 보면 실체가 분명하다. `javap -p MathUtils.class`를 찍으면 소스에 없던 메서드가 나온다.

```
public class MathUtils {
  public MathUtils();          // 소스에는 없다
    Code:
       0: aload_0
       1: invokespecial #1     // Method java/lang/Object."<init>":()V
       4: return
  static double round(double);
}
```

생성자는 클래스 파일에서 `<init>`이라는 이름의 메서드로 존재한다. 즉 "생성자를 안 쓴다"는 것은 클래스 파일에 `<init>`이 없다는 뜻이 아니라, **javac가 기본형 `<init>`을 채워 넣는다**는 뜻이다. 이 자동 생성은 선언이 **하나라도** 있으면 일어나지 않으므로, `private` 생성자 하나를 두는 것만으로 자동 생성이 차단된다.

또 하나 딸려 오는 것이 상속이다. 상속을 막는 명시적 장치(`final`)가 없고 접근 가능한 생성자가 있으면 누구나 하위 클래스를 만들 수 있다. 하위 클래스 생성자는 첫 줄에서 상위 생성자를 호출해야 하는데(JLS 8.8.7), 자동 생성된 `public` 생성자가 그 자리를 채워 주기 때문이다.

---

# 2단계 — 추상 클래스로는 막을 수 없다

## ① 흔히 쓰는(나쁜) 코드

```java
public abstract class MathUtils {        // "추상이니 못 만들겠지"
    static double round(double v) { return Math.floor(v + 0.5); }
}
```

## ② 무엇이 문제인가

```java
// MathUtils m = new MathUtils();
// 컴파일 에러: MathUtils is abstract; cannot be instantiated   ← 여기까지만 막힌다

class ReportService extends MathUtils { }   // 한 줄이면 끝
MathUtils m = new ReportService();          // 인스턴스가 생겼다
```

막히는 건 직접 생성 한 가지뿐이고, 하위 클래스를 거치는 길은 그대로 열려 있다. 게다가 `abstract`는 1단계보다 상황을 **더 나쁘게** 만든다. 아무 표시가 없는 클래스는 그래도 "상속할 생각은 없었나 보다"로 읽히지만, `abstract`는 자바에서 관례적으로 **"이 클래스를 상속해서 완성하라"는 뜻**이기 때문이다. 인스턴스화를 막으려고 붙인 키워드가 오히려 1단계에서 본 은닉 사고를 유도한다.

## 원리 — abstract가 막는 것은 무엇인가

`abstract`는 JLS 8.1.1.1이 규정하는 클래스 수식자로, 클래스 파일의 `access_flags`에 `ACC_ABSTRACT` 플래그로 기록된다. 이 플래그가 켜진 클래스에 대해 javac는 `new` 표현식을 거부하고, JVM은 `new` 옵코드 실행 시 `InstantiationError`를 던진다(JVMS 6.5 `new`).

핵심은 이 검사가 **"이 타입 이름으로 직접 할당하는 행위"만** 본다는 것이다. `new ReportService()`가 실행하는 `new` 옵코드의 대상은 `ReportService`이고, 이 클래스에는 `ACC_ABSTRACT`가 없다. 그 뒤에 이어지는 `invokespecial ReportService.<init>` 안에서 `super()`로 `MathUtils.<init>`이 호출되지만, **추상 클래스도 생성자를 가지며 하위 클래스가 호출하는 것은 정상 동작**이다. 결국 `MathUtils`의 상태를 가진 객체가 만들어진다.

즉 `abstract`는 "이 타입으로는 객체를 만들지 마라"는 제약이지 "이 타입의 객체가 존재하지 않게 하라"는 제약이 아니다. 인스턴스화를 막는다는 목적에는 처음부터 맞지 않는 도구다.

---

# 3단계 — private 생성자 + AssertionError

## ③ 개선된 코드

```java
// 인스턴스를 만들 수 없는 유틸리티 클래스
public class UtilityClass {
    // 기본 생성자가 만들어지는 것을 막는다(인스턴스화 방지용)
    private UtilityClass() {
        throw new AssertionError();
    }

    ... // 나머지 코드는 생략
}
```

두 줄에 세 가지 효과가 들어 있다. 생성자를 선언했으니 기본 생성자가 만들어지지 않고, `private`이니 클래스 밖에서 호출할 수 없고, 예외를 던지니 클래스 안에서 실수로 호출하는 것까지 막힌다.

## 원리 — 왜 굳이 AssertionError를 던지는가

`private` 생성자만으로 바깥에서의 `new`는 이미 막힌다. 그런데 **클래스 내부에서는 여전히 호출할 수 있다.** 같은 클래스 안의 코드에서 `new UtilityClass()`는 접근 제한에 걸리지 않으므로 아무 경고 없이 컴파일된다. 예제 파일에서 `new GoodMathUtils()`를 같은 파일 안에서 호출하는 줄이 정확히 이 경우다. 자바 11부터는 중첩 클래스들이 같은 둥지(nest)로 묶여 서로의 `private` 멤버에 접근할 수 있으므로, 바깥 클래스에서 중첩 유틸리티 클래스의 `private` 생성자를 호출하는 것도 컴파일된다.

예외를 던지면 이 경로가 실행 시점에 즉시 끊긴다. 던질 예외로 `AssertionError`를 고르는 이유는 의미 때문이다. 이 생성자에 도달했다는 것은 **"절대 일어나서는 안 되는 일이 일어났다"** 는 뜻이고, 그건 사용자 입력 문제(`IllegalArgumentException`)나 상태 문제(`IllegalStateException`)가 아니라 프로그램 자체의 결함이다. `Error`의 하위 타입이라 검사 예외도 아니고, `catch (Exception e)`로 감싸 둔 상위 코드에 조용히 먹히지도 않는다.

여기서 흔히 헷갈리는 점 하나. `AssertionError`를 **직접 `throw`하는 것**은 `assert` 문과 다르다. `assert` 문은 JVM 옵션 `-ea`로 켜야 동작하지만, `throw new AssertionError()`는 일반 `throw` 문이라 `-da`(어서션 비활성화, 기본값)로 실행해도 그대로 던진다.

리플렉션에도 한 겹 더 강하다. 아이템 3에서 봤듯 `setAccessible(true)`는 접근 검사만 끄므로 `private` 생성자도 호출할 수 있는데, 생성자 본문이 실행되면서 `AssertionError`가 `InvocationTargetException`에 감싸여 나온다. 예제 파일의 main이 이 두 경로를 모두 재현한다.

참고로 JDK 자신은 대부분 예외를 던지지 않는다. 예를 들어 `java.util.Collections`의 생성자는 `private Collections() { }`로 비어 있고, `java.lang.Math`도 `private Math() { }`다. JDK 코드는 자기 클래스 안에서 실수로 호출할 일이 없다고 본 것이다. 예외를 던지는 쪽이 더 안전하지만, 안 던진다고 틀린 코드는 아니다.

## 부가 효과 — 상속도 막힌다

의도한 것은 인스턴스화 차단인데 상속 차단이 따라온다. 하위 클래스 생성자는 첫 줄에서 상위 클래스 생성자를 호출해야 하는데(JLS 8.8.7), 상위 생성자가 전부 `private`이면 호출할 수 있는 것이 없기 때문이다.

```java
class Sub extends UtilityClass { }
// 컴파일 에러: UtilityClass() has private access in UtilityClass
```

1단계에서 본 정적 메서드 은닉 사고가 이 한 줄로 원천 봉쇄된다. 이 부가 효과가 유틸리티 클래스에는 정확히 원하는 바이므로, `final`을 따로 붙이지 않아도 된다. 다만 **의도를 코드로 드러내려고** `public final class`로 선언하는 편이 읽는 사람에게 더 친절하다.

생성자 위의 주석이 필수인 이유도 같다. `private UtilityClass() { throw new AssertionError(); }`만 있으면 "왜 이런 걸 만들어 뒀지?" 하고 지우는 사람이 생긴다. 책이 예제에 주석을 달아 둔 것은 장식이 아니다.

## 버전 표기

- `private` 생성자 + `AssertionError` 관용구: **모든 버전. Java 7 레거시에서도 그대로 사용 가능.**
- 정적 임포트(`import static java.lang.Math.abs;`): **Java 5+ / 레거시 7에서도 사용 가능.** 유틸리티 클래스 호출을 짧게 쓰는 수단이지만, 어느 클래스의 메서드인지 흐려지므로 이름이 명확한 것에만 쓴다.
- `java.util.Objects`(`requireNonNull`, `equals`, `hash`): Java 7+. 대표적인 유틸리티 클래스이며 생성자는 `private`이다.

---

# 3판(Java 9 기준) 이후 — 현재(Java 21) 시점의 보완

## record와 enum은 대안이 되지 않는다

`(Java 16+)` 레코드는 `private` 생성자를 가질 수 없다. JLS 8.10.4에 따라 정규 생성자(canonical constructor)는 레코드 클래스 자신보다 좁은 접근 수준을 가질 수 없어서, `public record Utils()`의 생성자는 `public`이어야 한다. 컴포넌트가 없는 레코드라도 `new Utils()`가 가능하므로 인스턴스화를 막는 목적에는 쓸 수 없다.

`(Java 5+)` 원소가 없는 열거 타입은 기술적으로는 가능하다.

```java
public enum MathUtils {
    ;   // 상수 없음 — 세미콜론만 남는다
    public static double round(double v) { return Math.floor(v + 0.5); }
}
```

열거 타입이라 인스턴스화도 상속도 완전히 막히지만, 상수가 하나도 없는 `enum`은 읽는 사람을 혼란스럽게 하고 아이템 3의 싱글턴(`INSTANCE` 하나짜리 열거 타입)과 헷갈린다. Java 21에서도 유틸리티 클래스의 표준 답은 여전히 `private` 생성자다.

## 인터페이스의 static 메서드는 절반만 해결한다

`(Java 8+)` 인터페이스에 `static` 메서드를 둘 수 있게 되면서, 아예 인터페이스로 만들면 인스턴스화 문제가 없다고 생각하기 쉽다. 인터페이스는 인스턴스화할 수 없으니 이 부분은 맞다.

하지만 두 가지가 더 나빠진다. 첫째, **인터페이스는 `implements`로 누구나 붙을 수 있다.** 클래스에 `private` 생성자를 둔 것과 달리 상속 경로가 오히려 활짝 열린다. 상수만 담은 인터페이스를 구현해 이름을 짧게 쓰는 상수 인터페이스 안티패턴이 아이템 22에서 금지되는 이유와 같다. 둘째, 인터페이스의 필드는 암묵적으로 `public static final`이고 `private` 필드는 `(Java 9+)`부터만 가능해서, 유틸리티 내부 상태를 숨기기가 까다롭다.

정리하면, 인터페이스의 `static` 메서드는 **그 인터페이스와 관련된 팩터리·헬퍼**를 두는 용도(아이템 1의 `Cache.ofCapacity` 같은)이고, 관련 없는 정적 메서드를 모아 두는 용도가 아니다.

## Lombok @UtilityClass의 함정

`(외부 라이브러리)` Lombok의 `@UtilityClass`는 클래스를 `final`로 바꾸고 `private` 생성자에서 `UnsupportedOperationException`을 던지게 해 준다. 여기까지는 이 아이템 그대로다.

함정은 그 다음이다. `@UtilityClass`는 **클래스 안의 모든 필드·메서드·중첩 클래스를 자동으로 `static`으로 바꾼다.** 의도하지 않은 인스턴스 필드가 정적 필드가 되어 모든 호출이 공유하는 전역 상태가 되고, 여러 스레드가 동시에 그 필드를 쓰면 요청끼리 값이 섞인다. 소스에는 `static`이 안 보이는데 동작은 `static`이라 코드만 읽어서는 원인을 찾기 어렵다.

## 스프링에서는 정적 유틸리티와 빈을 섞지 않는다

스프링 애플리케이션에서 유틸리티 클래스에 `@Component`를 붙이거나 정적 필드에 `@Value`, `@Autowired`를 쓰는 코드를 종종 본다. 정적 필드 주입은 스프링이 지원하지 않아 조용히 `null`로 남고, 첫 호출에서 `NullPointerException`이 난다.

판단 기준은 하나다. **외부 의존성이 전혀 없는 순수 함수의 묶음이면 `private` 생성자를 둔 정적 유틸리티 클래스, 무언가를 주입받아야 하면 처음부터 정적 메서드가 아니라 빈**이다. 후자를 정적으로 만들면 테스트에서 대체할 수 없다는 문제가 따라오고, 그 답이 아이템 5의 의존 객체 주입이다.

---

# 심화 / 예외 상황

**유틸리티 클래스가 늘어나는 것 자체가 신호일 수 있다.** 책도 "유틸리티 클래스는 남용하면 절차적 설계가 된다"는 맥락을 여러 아이템에 걸쳐 깐다. `OrderUtils.calculateTotal(order)` 같은 메서드가 쌓이고 있다면, 그 로직은 대개 `Order` 자신이 가져야 할 동작이다. 상태 없이 인자만 받아 계산하는 함수(수학 연산, 문자열 변환, 배열 조작)에는 유틸리티 클래스가 맞지만, 특정 도메인 객체의 데이터를 꺼내 쓰는 함수라면 그 객체의 메서드로 옮기는 편이 낫다.

**테스트 커버리지 도구와의 충돌.** `private` 생성자는 테스트에서 호출되지 않으므로 JaCoCo 같은 도구가 "커버되지 않은 라인"으로 잡는다. 커버리지 100%를 요구하는 프로젝트에서 이 때문에 리플렉션으로 생성자를 억지로 호출하는 테스트를 짜는 관행이 생기는데, 이는 검증 가치가 없는 테스트다. JaCoCo는 커버리지 제외 규칙(`excludes`)이나 `@Generated` 어노테이션 필터를 지원하므로 그쪽으로 해결하는 편이 맞다.

**이 관용구가 필요 없는 경우.** 인스턴스화를 막는 것이 목적이 아니라면 쓰지 않는다. 정적 메서드와 인스턴스 메서드를 함께 제공하는 클래스(예: `Integer`는 `valueOf` 같은 정적 메서드도 있지만 인스턴스가 본체다)에는 당연히 해당하지 않는다. 또 프레임워크가 기본 생성자를 요구하는 클래스(JPA 엔티티, 자바빈즈)에도 쓸 수 없다.

**`final` 필드 상수만 모아 두는 클래스에도 그대로 적용된다.** `public class Constants { public static final int MAX = 10; }`에도 `private` 생성자가 필요하다. 다만 상수를 모아 두기 전에, 그 상수가 특정 클래스나 열거 타입에 속하는 것이 더 자연스럽지 않은지 먼저 따져 보는 게 좋다.

---

## 핵심 키워드

- [유틸리티 클래스](../Keywords/utility-class.md) — 정적 멤버만 모은 클래스와 그 설계 규칙
- [기본 생성자](../Keywords/default-constructor.md) — javac가 언제, 어떤 접근 수준으로 `<init>`을 채워 넣는가
- [AssertionError](../Keywords/assertion-error.md) — `assert` 문과의 차이, `-ea`/`-da`와 무관한 이유
- [정적 메서드 은닉](../Keywords/static-method-hiding.md) — 재정의처럼 보이지만 참조 타입으로 결정되는 호출
- [인스턴스 통제 클래스](../Keywords/instance-controlled-class.md) — 이 아이템은 인스턴스 개수를 0으로 통제하는 경우다

## 함께 보면 좋은 아이템

아이템 3과 4는 `private` 생성자로 인스턴스를 통제하는 짝이다. 3이 인스턴스를 하나로 만든다면, 4는 하나도 만들지 않는다. 그리고 5가 "그 정적 유틸리티가 사실은 빈이어야 하는 경우"를 이어받는다.

- **아이템 3. private 생성자나 열거 타입으로 싱글턴임을 보증하라** — 3단계의 `private` 생성자 + 예외 던지기는 아이템 3의 생성자 가드와 똑같은 기법이다. 리플렉션이 `setAccessible`로 뚫고 들어오는 경로도 동일하므로 두 아이템을 이어서 보면 이해가 붙는다.
- **아이템 5. 자원을 직접 명시하지 말고 의존 객체 주입을 사용하라** — 책이 이 아이템 바로 뒤에 배치한 이유가 있다. 정적 유틸리티 클래스로 만들면 안 되는 경우(사용하는 자원이 상황에 따라 달라지는 경우)를 다룬다. 위의 스프링 섹션 결론이 그대로 이어진다.
- **아이템 22. 인터페이스는 타입을 정의하는 용도로만 사용하라** — "인터페이스의 static 메서드는 절반만 해결한다" 섹션의 근거다. 상수 인터페이스 안티패턴이 왜 문제인지를 정면으로 다룬다.
- **아이템 19. 상속을 고려해 설계하고 문서화하라. 그러지 않았다면 상속을 금지하라** — 3단계의 부가 효과(상속 차단)와 1단계의 은닉 사고를 설계 원칙으로 일반화한 아이템이다.

(아이템 번호는 3판 한국어판 기준으로 기억하고 있으나, 19·22는 책에서 한 번 확인해 주면 확실하다.)

## 확인 질문

아래처럼 유틸리티 클래스에 `private` 생성자를 넣되, 예외는 던지지 않고 비워 두었다고 하자. JDK의 `Collections`와 같은 형태다.

```java
public final class MathUtils {
    private MathUtils() { }                       // 예외를 던지지 않는다
    private static final MathUtils SELF = new MathUtils();   // 어느 팀원이 추가했다
    public static double round(double v) { ... }
}
```

이 코드는 컴파일될까? 컴파일된다면, `AssertionError`를 던지는 버전으로 바꿨을 때는 어느 시점에 어떤 예외가 어떤 형태로 터질까?
