# 아이템 17. 변경 가능성을 최소화하라

## 한 줄 결론

불변 클래스(immutable class)는 생성된 순간의 상태를 끝까지 유지하는 클래스다. 그래서 동기화 없이 여러 스레드가 공유할 수 있고, 상수로 캐싱하거나 맵 키로 써도 안전하며, 방어적 복사(defensive copy)가 필요 없다. 치르는 비용은 값이 다를 때마다 새 객체를 만들어야 한다는 것 하나뿐이다. 이 비용은 가변 동반 클래스(companion class)로 줄일 수 있다.

책이 나열하는 다섯 가지 규칙, 여러 장점, 설계 방법은 사실 한 흐름이다. **"이 객체의 상태를 바꿀 수 있는 경로가 하나도 없다"는 사실을 컴파일러와 JVM이 보장하게 만드는 것.** 규칙은 그 경로를 하나씩 막는 방법이고, 장점은 경로가 다 막혔을 때 공짜로 따라오는 결과다.

---

# 1. 불변 클래스의 다섯 가지 규칙 — 하나만 빠져도 불변이 아니다

## 신입 눈높이 설명

다섯 규칙은 "객체 상태가 바뀔 수 있는 경로"를 하나씩 막는 장치다.

- 객체의 상태를 변경하는 메서드(변경자)를 제공하지 않는다 → 정문을 막는다.
- 클래스를 확장할 수 없게 한다 → 하위 클래스가 가변 상태나 변경 메서드를 몰래 추가하는 뒷문을 막는다.
- 모든 필드를 `final`로 선언한다 → 클래스 자신의 코드조차 생성 후 대입하지 못하게 컴파일러가 막는다. 스레드 간 공개 문제도 함께 풀린다(원리 참고).
- 모든 필드를 `private`으로 선언한다 → 필드를 참조하는 가변 객체에 외부가 직접 손대는 길을 막는다.
- 자신 외에는 내부의 가변 컴포넌트에 접근할 수 없게 한다 → 생성자로 받은 가변 객체나 접근자로 내준 가변 객체를 통해 들어오는 길을 막는다.

`final` 필드만으로 불변이 된다고 생각하기 쉽지만, 틀린 생각이다. `final`은 참조를 고정할 뿐이다. `private final Date start`라도 호출자가 넘겨준 `Date`를 그대로 저장했다면 호출자가 `date.setTime(...)`으로 바꿀 수 있다. 규칙 다섯 개가 모두 필요한 이유다.

## 원리 — final 필드는 불변성과 스레드 공개를 함께 보장한다

`final`의 첫 번째 역할은 컴파일 시점 검사다. `final` 인스턴스 필드는 생성자가 끝나기 전에 정확히 한 번 대입되어야 한다(definite assignment 규칙). 생성자 밖에서 대입하는 코드는 컴파일되지 않는다.

두 번째 역할이 더 중요한데, 책이 "스레드 안전"이라고만 적고 넘어가는 부분의 근거다. JLS 17.5(final 필드 의미론, final field semantics)는 다음을 보장한다. **생성자가 끝났을 때 `final` 필드에 쓰인 값은, 그 객체의 참조를 얻은 모든 스레드에게 동기화 없이도 정확히 보인다.** 단, 생성자 도중에 `this`가 밖으로 새어 나가지 않았다는 조건이 붙는다. JVM은 생성자 끝에 일종의 동결(freeze) 동작을 두고, 그 뒤의 참조 공개와 순서가 바뀌지 않도록 메모리 배리어를 넣는다.

`final`이 아닌 필드는 이 보장이 없다. 한 스레드가 `new Point(1, 2)`를 만들어 공유 변수에 넣으면, 다른 스레드는 참조는 보았지만 필드 값은 아직 기본값 `0`인 반쯤 만들어진 객체를 볼 수 있다. JIT와 CPU가 "필드 쓰기"와 "참조 쓰기"의 순서를 바꿀 수 있기 때문이다. 필드를 한 번도 바꾸지 않는 사실상 불변(effectively immutable) 클래스라도 `final`이 없으면 이 문제가 남는다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

규칙 다섯 개 중 "확장 불가"와 "가변 컴포넌트 접근 제한"을 빼먹은 코드다. 겉보기에는 불변처럼 보인다.

```java
public class Period {                     // final 이 아니다 — 규칙 2 위반
    private final Date start;
    private final Date end;

    public Period(Date start, Date end) {
        if (start.after(end)) throw new IllegalArgumentException(start + " > " + end);
        this.start = start;               // 호출자의 Date 를 그대로 저장 — 규칙 5 위반
        this.end = end;
    }
    public Date start() { return start; } // 내부 Date 를 그대로 내준다 — 규칙 5 위반
    public Date end()   { return end; }
}
```

### ② 무엇이 문제인가

생성자가 `start <= end`를 검증했는데도 불변식이 깨진다.

```java
Date s = new Date(), e = new Date();
Period p = new Period(s, e);
e.setYear(78);                // 호출자가 쥐고 있던 Date 를 바꿨다 → p.end 가 1978년이 된다
p.end().setYear(78);          // 또는 접근자가 내준 Date 로도 똑같이 바꿀 수 있다
```

- 컴파일 에러 없음. 런타임 예외 없음. `Period`는 자기가 바뀐 사실을 모른다.
- `Period`의 단위 테스트는 생성자 검증만 확인하므로 통과한다.
- 증상은 전혀 다른 곳에서 나타난다. 예약 시스템이 `p`를 `HashSet`에 넣어 두었다면 그 원소를 찾지 못하게 되고, 기간 계산 결과가 음수가 된다. 원인(`Date`를 재사용한 호출자 코드)과 증상(예약 조회 실패)이 멀리 떨어져 있다.

확장 가능하다는 문제도 있다.

```java
class EvilPeriod extends Period {          // 하위 클래스가 가변 상태를 추가한다
    Date overrideEnd;
    @Override public Date end() { return overrideEnd != null ? overrideEnd : super.end(); }
}
```

`Period`를 매개변수로 받는 메서드는 받은 객체가 불변이라고 믿고 검증 없이 캐싱하거나 공유한다. 그런데 실제로 넘어온 것이 `EvilPeriod`라면 그 믿음이 깨진다. 악의가 없어도, 누군가 "기간을 연장하는 기능이 필요하니 상속해서 세터를 붙이자"고 하는 순간 같은 일이 일어난다.

근본 원인은 하나다. **`final` 필드는 참조를 고정할 뿐이고, 참조가 가리키는 가변 객체와 하위 클래스는 막지 못한다.**

### ③ 개선된 코드

```java
public final class Period {                                // 규칙 2: 확장 불가
    private final Date start;                              // 규칙 3·4: private final
    private final Date end;

    public Period(Date start, Date end) {
        this.start = new Date(start.getTime());            // 규칙 5: 받을 때 복사 (검증보다 먼저)
        this.end   = new Date(end.getTime());
        if (this.start.after(this.end))
            throw new IllegalArgumentException(this.start + " > " + this.end);
    }
    public Date start() { return new Date(start.getTime()); }   // 규칙 5: 내줄 때 복사
    public Date end()   { return new Date(end.getTime()); }
}
```

복사를 검증보다 먼저 하는 이유는, 검증과 복사 사이에 다른 스레드가 원본을 바꾸는 공격(TOCTOU, time-of-check/time-of-use)을 막기 위해서다. `clone()` 대신 생성자로 복사하는 이유는 `Date`가 `final`이 아니라서 `clone()`이 하위 클래스가 재정의한 악의적인 메서드를 부를 수 있기 때문이다. 이 두 가지는 방어적 복사를 다루는 아이템 50의 주제다.

더 좋은 해결책은 애초에 가변 컴포넌트를 쓰지 않는 것이다. `(Java 8+)` `Date` 대신 불변인 `java.time.Instant`나 `LocalDateTime`을 쓰면 복사 코드가 전부 사라진다.

## 버전 표기

- `final` 클래스, `private final` 필드, 방어적 복사: Java 1.0+. Java 7 레거시에서도 그대로 쓸 수 있다.
- `final` 필드의 스레드 공개 보장(JLS 17.5): Java 5+(JSR-133 새 메모리 모델). Java 1.4 이전 JVM에서는 이 보장이 없었다. Java 7 이상에는 모두 적용된다.
- `java.time`(`Instant`, `LocalDate` 등): Java 8+. Java 7에서는 `Date`를 쓰면서 위 ③처럼 방어적으로 복사하거나, `long` 에포크 밀리초 값 자체를 필드로 저장하는 방법이 있다. `long` 필드는 기본 타입이라 복사할 필요가 없다.
- `Date.setYear`는 Java 1.1부터 deprecated다(`Calendar` 사용 권장). 위 코드는 실패를 보여 주기 위한 예다.

---

# 2. 함수형 방식 — 피연산자를 바꾸지 않고 새 객체를 반환한다

## 신입 눈높이 설명

책의 `Complex`는 `plus`, `minus`, `times`, `dividedBy`가 모두 새 `Complex`를 반환하고 자기 자신은 바꾸지 않는다. 책은 이것을 함수형 프로그래밍(functional programming) 방식이라 부른다. 반대로 피연산자 자신을 바꾸는 방식이 절차적(procedural) 또는 명령형(imperative) 방식이다.

한 줄로 가르면 이렇다. `a.add(b)`는 "a야, b만큼 커져라"라는 명령이고, `a.plus(b)`는 "a와 b를 더한 값을 줘"라는 질문이다. 질문은 몇 번을 해도 `a`가 그대로다.

메서드 이름도 이 차이를 드러낸다. 책은 동사(`add`) 대신 전치사(`plus`)를 쓴 것이 의도적이라고 말한다. 동사는 "무언가를 바꾼다"는 느낌을 주기 때문이다. `BigInteger.add`는 이 관례를 따르지 않은 예로, 이름 때문에 아래 심화에 나오는 실수를 부른다.

## 원리 설명

`Complex`의 필드가 모두 `private final`이므로, `plus` 안에서 `this.re += c.re`라고 쓰면 컴파일 에러(`cannot assign a value to final variable re`)가 난다. 결과를 돌려주려면 새 객체를 만드는 방법밖에 없다. 즉 함수형 방식은 스타일이 아니라 규칙 3을 지킨 결과로 강제된다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
public final class MutableComplex {
    public static final MutableComplex ZERO = new MutableComplex(0, 0);
    private double re, im;

    public MutableComplex add(MutableComplex c) {  // this 를 바꾸고 this 를 반환
        re += c.re;
        im += c.im;
        return this;
    }
}
```

### ② 무엇이 문제인가

`add`가 `this`를 반환하므로 `sum = sum.add(x)`처럼 함수형으로 쓴 것처럼 보이는 코드도 실제로는 `sum`을 제자리에서 바꾼다. 누군가 누산기 시작값으로 상수를 쓰는 순간 전역 상수가 오염된다.

```java
MutableComplex sum = MutableComplex.ZERO;    // "0부터 더한다" — 자연스러운 코드
for (MutableComplex x : xs) sum.add(x);      // ZERO 자체가 바뀐다
```

- 첫 호출 `sumBad([1+i, 2+2i])`는 `3+3i`로 맞다. 단위 테스트는 한 번만 호출하므로 통과한다.
- 두 번째 호출 `sumBad([1])`은 `4+3i`를 반환한다. 이전 호출의 결과가 시작값에 섞였다.
- 이 시점부터 `ZERO`를 쓰는 모든 코드가 틀린 값을 낸다. 합계 유틸을 짠 사람과 `ZERO`를 쓰는 다른 모듈의 개발자가 다르면, 증상을 쫓아가도 원인이 보이지 않는다.
- 운영 환경에서는 요청마다 결과가 달라진다. 서버를 재시작하면 `ZERO`가 다시 초기화되어 증상이 사라지므로, 재현도 어렵다.

`HashSet`에 넣은 원소를 바꾸면 그 원소를 다시 찾지 못한다. 원소는 넣을 때의 해시 버킷에 남아 있는데, 조회는 바뀐 `hashCode`로 다른 버킷을 찾기 때문이다. `contains`는 `false`를 반환하고, `size()`는 1이다.

근본 원인은 하나다. **가변 객체를 공유하면 모든 공유자가 한 사람의 변경을 떠안는데, 누가 공유하고 있는지는 코드 어디에도 드러나지 않는다.** 예제 파일 `Item17Example.java`가 이 두 장면을 그대로 재현한다.

### ③ 개선된 코드

책의 `Complex`다(핵심만 발췌).

```java
public final class Complex {
    public static final Complex ZERO = new Complex(0, 0);
    public static final Complex ONE  = new Complex(1, 0);
    public static final Complex I    = new Complex(0, 1);

    private final double re;
    private final double im;

    private Complex(double re, double im) { this.re = re; this.im = im; }
    public static Complex valueOf(double re, double im) { return new Complex(re, im); }

    public double realPart()      { return re; }
    public double imaginaryPart() { return im; }

    public Complex plus(Complex c)  { return new Complex(re + c.re, im + c.im); }
    public Complex times(Complex c) {
        return new Complex(re * c.re - im * c.im, re * c.im + im * c.re);
    }

    @Override public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Complex)) return false;
        Complex c = (Complex) o;
        return Double.compare(c.re, re) == 0 && Double.compare(c.im, im) == 0;
    }
    @Override public int hashCode() { return 31 * Double.hashCode(re) + Double.hashCode(im); }
}
```

`equals`에서 `==` 대신 `Double.compare`를 쓰는 이유는 `NaN`과 `-0.0` 때문이다. 아이템 10에서 다룬 내용이다.

## 버전 표기

- `Complex` 전체: Java 1.0+ 문법이다. 단 `Double.hashCode(double)` 정적 메서드는 Java 8+다. Java 7에서는 아래처럼 쓴다. 두 방식의 결과 값은 같다. `Double.hashCode(d)`가 내부적으로 이 계산을 한다.

  ```java
  // Java 7 대안
  private static int hash(double d) {
      long bits = Double.doubleToLongBits(d);
      return (int) (bits ^ (bits >>> 32));
  }
  @Override public int hashCode() { return 31 * hash(re) + hash(im); }
  ```

- 예제 파일은 `Objects.hash(re, im)`(Java 7+)을 썼다. 결과 값은 위와 다르지만 `equals`와 일관된다는 점은 같다. `Objects.hash`는 가변 인자 배열과 박싱을 거치므로 조금 더 느리다.

## 심화 / 예외 상황

**반환값을 버리는 실수.** 함수형 방식의 대표적인 함정이다. `BigDecimal`은 불변이다.

```java
BigDecimal total = BigDecimal.ZERO;
for (Item i : items) total.add(i.price());   // 반환값을 버림 → total 은 끝까지 0
```

컴파일 에러도 런타임 예외도 없다. 이름이 동사 `add`라서 제자리에서 바뀐다고 착각하기 쉽다. `total = total.add(...)`로 다시 대입해야 한다. IntelliJ는 이런 호출에 "결과가 무시되었다"는 경고를 띄우고, Error Prone 같은 정적 분석 도구도 반환값을 버리는 호출을 잡아낸다. `String.concat`, `LocalDate.plusDays`처럼 불변 타입의 "변경처럼 보이는" 메서드는 모두 같은 함정이 있다.

---

# 3. 불변 객체는 스레드 안전하고, 따라서 자유롭게 공유할 수 있다

## 신입 눈높이 설명

동기화(synchronization)가 필요한 이유는 "읽는 도중에 누가 바꿀 수 있어서"다. 불변 객체는 아무도 바꿀 수 없으므로 동기화할 대상이 없다. 락도, `volatile`도, `AtomicXxx`도 필요 없다.

책은 여기서 세 가지 결론을 끌어낸다.

- 자주 쓰는 값은 `public static final` 상수로 제공한다(`Complex.ZERO`, `ONE`, `I`). 가변 클래스라면 위 2번의 ② 사고가 나지만, 불변이면 안전하다.
- 한발 더 나아가 정적 팩터리로 같은 값의 인스턴스를 캐싱해서 재사용할 수 있다. `Integer.valueOf(-128~127)`, `BigInteger.valueOf`가 그렇다. 이 선택은 클라이언트 코드를 바꾸지 않고 나중에 할 수도 있다.
- 방어적 복사가 필요 없다. 복사해 봤자 원본과 똑같으니 복사 생성자나 `clone`을 제공하지 말아야 한다. 책은 `String`의 복사 생성자 `new String("abc")`를 쓰지 말라고 한다.

## 원리 설명

"자유롭게 공유한다"가 성립하려면 조건이 두 개 필요하다.

1. 공유한 뒤에 아무도 바꿀 수 없다 → 규칙 1~5가 보장한다.
2. 다른 스레드에 공유하는 순간 완성된 상태가 보인다 → 1번 원리의 JLS 17.5 final 필드 의미론이 보장한다.

2번이 중요하다. 불변 객체는 `volatile`이나 `synchronized` 없이 경쟁 조건이 있는 방식(data race)으로 공개해도 안전하다. 반면 가변 객체나 `final`이 없는 "사실상 불변" 객체는 안전하게 공개(safe publication)해야 한다. `volatile` 필드, `synchronized` 블록, `ConcurrentHashMap` 같은 스레드 안전 컨테이너를 통해서만 넘겨야 한다.

JDK의 캐싱 구현도 이 성질에 기대고 있다.

```java
// JDK 소스 발췌 — java.lang.Integer (버전에 따라 세부는 다를 수 있다)
public static Integer valueOf(int i) {
    if (i >= IntegerCache.low && i <= IntegerCache.high)
        return IntegerCache.cache[i + (-IntegerCache.low)];   // 같은 인스턴스를 공유
    return new Integer(i);
}
```

`Integer`가 가변이었다면 한 곳에서 `Integer.valueOf(1)`의 값을 바꾸는 순간 JVM 전체의 `1`이 바뀌었을 것이다.

## 3단 대비

2번의 3단 대비(`MutableComplex.ZERO` vs `Complex.ZERO`)가 곧 이 항목의 3단 대비다. 공유 상수가 오염되는 장면이 "자유롭게 공유할 수 없다"는 것의 구체적인 모습이다. 스레드 관점의 차이만 덧붙인다.

- ① `MutableComplex`를 여러 요청 스레드가 공유하는 캐시(`Map<String, MutableComplex>`)에 넣으면, 한 스레드가 `add`하는 중에 다른 스레드가 `re`는 새 값, `im`은 옛 값인 상태를 읽는다. `double` 쓰기 두 번 사이에 끼어드는 것이라서 재현 빈도가 낮고 로그도 남지 않는다. 게다가 `double`/`long` 필드는 `volatile`이 아니면 쓰기 자체가 원자적이라는 보장도 없다(JLS 17.7). 64비트 JVM에서는 실제로 쪼개지는 경우가 거의 없지만, 명세상 보장은 없다.
- ③ `Complex`는 캐시에 넣고 여러 스레드가 동시에 읽어도 추가 코드 없이 안전하다.

## 버전 표기

- `Integer.valueOf(int)`와 캐시: Java 5+. 박싱 타입 생성자 `new Integer(int)`는 Java 9에서 deprecated, Java 16에서 forRemoval로 표시되었다.
- `new String(String)` 복사 생성자: deprecated는 아니지만 불변 문자열을 복사할 이유가 없다. Java 7 레거시 코드에서 `new String("...")`을 보면 지워도 된다. 예외적으로 Java 7u6 이전에는 `substring`이 원본의 `char[]`를 공유해서, 큰 문자열의 작은 조각만 남기려고 `new String(sub)`로 복사하는 관용구가 있었다. 7u6부터는 `substring`이 복사하므로 이 관용구도 필요 없다.

---

# 4. 불변 객체끼리는 내부 데이터를 공유할 수 있다

## 원리 설명

책의 예는 `BigInteger.negate()`다. `BigInteger`는 부호(`signum`)와 크기(`mag`, `int[]`)로 값을 표현한다. 부호만 뒤집은 새 `BigInteger`를 만들 때 크기 배열을 복사하지 않고 원본과 같은 배열을 가리키게 한다.

```java
// JDK 소스 발췌 — java.math.BigInteger (버전에 따라 세부는 다를 수 있다)
public BigInteger negate() {
    return new BigInteger(this.mag, -this.signum);   // mag 배열을 복사하지 않고 공유
}
```

`int[]`는 가변이다. 그런데도 공유가 안전한 이유는, 이 배열에 접근할 수 있는 코드가 `BigInteger` 내부뿐이고 `BigInteger`의 어떤 메서드도 생성 후 `mag`를 수정하지 않기 때문이다. 즉 규칙 5("가변 컴포넌트에 자신 외에는 접근할 수 없다")가 지켜지는 한, 가변 컴포넌트도 불변 객체들 사이에서는 공유할 수 있다. 수백만 자리 수를 부정할 때 O(n) 복사가 O(1)이 된다.

이 설계는 가변 클래스에서는 불가능하다. 원본이 `mag`를 제자리에서 바꾸면 부정한 객체도 함께 바뀌기 때문이다.

## 버전 표기

- `BigInteger`: Java 1.1+. Java 7 레거시에서도 동일하게 동작한다.

---

# 5. 불변 객체는 그 자체로 실패 원자성을 제공한다

## 신입 눈높이 설명

실패 원자성(failure atomicity)이란 메서드가 예외를 던져도 객체가 호출 전 상태 그대로 남는 성질이다. 불변 객체는 상태가 절대 바뀌지 않으므로 중간에 실패해도 반쯤 바뀐 상태가 생길 수 없다. 이 성질을 위해 따로 코드를 쓸 필요가 없다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
public void transfer(MutableAccount to, long amount) {
    this.balance -= amount;                // 여기까지 성공
    to.deposit(amount);                    // 여기서 예외 (한도 초과 등)
}
```

### ② 무엇이 문제인가

`to.deposit`이 예외를 던지면 `this.balance`는 이미 줄어든 상태로 남는다. 호출자는 예외를 잡고 재시도한다. 그러면 돈이 두 번 빠진다. 예외 로그는 남지만 로그에는 "입금 실패"만 찍히고, 출금이 이미 반영되었다는 사실은 드러나지 않는다.

근본 원인은 하나다. **여러 단계로 상태를 바꾸는 도중에 실패하면, 이미 바뀐 단계를 되돌리는 책임이 호출자에게 넘어간다.**

### ③ 개선된 코드

```java
// 불변 계좌: 새 상태를 모두 계산한 뒤에야 결과를 돌려준다
public Transfer transfer(Account to, long amount) {
    Account newFrom = this.withdraw(amount);   // 새 객체 — this 는 그대로
    Account newTo   = to.deposit(amount);      // 여기서 예외가 나도 this, to 는 무사
    return new Transfer(newFrom, newTo);
}
```

예외가 어디서 나든 `this`와 `to`는 호출 전 그대로다. 결과를 저장소에 반영하는 단계만 트랜잭션으로 묶으면 된다. 아이템 76(가능한 한 실패 원자적으로 만들라)이 권하는 첫 번째 방법이 "불변 객체로 설계하라"인 이유다.

## 버전 표기

- 언어 기능과 무관한 설계 기법이다. Java 7 레거시에서도 그대로 쓸 수 있다.

---

# 6. 단점 — 값이 다르면 반드시 독립된 객체로 만들어야 한다

## 신입 눈높이 설명

백만 비트짜리 `BigInteger`에서 비트 하나만 바꿔도 `flipBit`은 백만 비트짜리 새 객체를 만든다. 가변인 `BitSet`은 해당 비트만 바꾸면 끝이다. 한 번이면 괜찮지만, 여러 단계를 거치며 중간 객체를 계속 만들고 버리는 연산이면 비용이 커진다.

책의 해법은 가변 동반 클래스(companion class)다. 다단계 연산은 패키지 내부의 가변 클래스로 처리하고, 최종 결과만 불변 객체로 내보낸다. `BigInteger`는 내부에 package-private `MutableBigInteger`를 둔다. `String`은 공개된 동반 클래스 `StringBuilder`를 둔다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
String csv = "";
for (Order o : orders) csv += o.id() + ",";   // 반복마다 새 String
```

### ② 무엇이 문제인가

`+=`를 할 때마다 지금까지 만든 문자열 전체를 새 배열에 복사한다. 주문이 n개면 복사량이 O(n²)이다.

- 개발 환경에서 주문 10건으로 테스트하면 1ms도 안 걸린다.
- 운영에서 월말 정산으로 주문 10만 건을 CSV로 만드는 순간 수십 초가 걸린다. 중간 `String` 수십 GB가 GC로 쏟아진다.
- 예외도 로그도 없고 "느리다"는 증상만 있다. 원인이 문자열 연결이라는 것은 프로파일러를 붙여야 보인다.

### ③ 개선된 코드

```java
StringBuilder sb = new StringBuilder();       // 가변 동반 클래스로 누적
for (Order o : orders) sb.append(o.id()).append(',');
String csv = sb.toString();                   // 결과만 불변 객체로
```

## 버전 표기

- `StringBuilder`: Java 5+. Java 1.4 이전에는 동기화된 `StringBuffer`만 있었다. Java 7 레거시에서도 그대로 쓸 수 있다.
- `(Java 8+)` `String.join(",", list)`, `Collectors.joining(",")`도 내부적으로 `StringBuilder`를 쓴다.
- `(Java 9+)` JEP 280은 `+` 연산자를 `invokedynamic`(`StringConcatFactory`)으로 컴파일해서 한 식 안의 연결(`a + b + c`)을 최적화했다. 하지만 반복문에서 누적하는 `+=`는 여전히 반복마다 새 `String`을 만든다. O(n²) 문제는 Java 21에서도 그대로다.

---

# 7. 불변 클래스를 만드는 또 다른 설계 방법 — private 생성자 + 정적 팩터리

## 신입 눈높이 설명

규칙 2(확장 불가)를 지키는 방법은 `final` 클래스만이 아니다. 모든 생성자를 `private`이나 package-private으로 두고 `public` 정적 팩터리를 제공하면, 패키지 밖에서는 하위 클래스를 만들 수 없다. 하위 클래스의 생성자는 반드시 상위 클래스의 생성자를 호출해야 하는데, 그 생성자가 보이지 않기 때문이다.

책은 이 방법이 더 유연하다고 말한다. 패키지 안에서는 구현 클래스를 여러 개 둘 수 있고, 나중에 캐싱 같은 기능을 추가해도 클라이언트 코드가 바뀌지 않는다. 앞의 `Complex`가 바로 이 형태다(`private` 생성자 + `valueOf`). `Complex`는 `final`까지 붙어 있지만, `final`이 없어도 밖에서는 확장할 수 없다.

## 원리 — BigInteger와 BigDecimal의 설계 실수

`BigInteger`와 `BigDecimal`은 불변 객체가 반드시 `final`이어야 한다는 생각이 널리 퍼지기 전에 만들어져서, 둘 다 `public` 생성자를 가진 비`final` 클래스다. 누구나 상속해서 가변 하위 클래스를 만들 수 있고, 하위 호환성 때문에 지금도 고칠 수 없다.

### ① 흔히 쓰는(나쁜) 코드

```java
public void charge(BigInteger amount) {
    validate(amount);            // 불변이라고 믿고 검증 후
    pending.add(amount);         // 그대로 저장
}
```

### ② 무엇이 문제인가

신뢰할 수 없는 클라이언트가 `BigInteger`를 상속해 `toString`, `intValue` 같은 메서드가 호출 시점마다 다른 값을 돌려주는 하위 클래스를 넘기면, 검증할 때와 사용할 때의 값이 달라진다. 컴파일러는 막지 않는다. 받은 객체가 진짜 `BigInteger`인지는 타입만으로 알 수 없다.

### ③ 개선된 코드

책이 제시하는 방어 코드다.

```java
public static BigInteger safeInstance(BigInteger val) {
    return val.getClass() == BigInteger.class ? val : new BigInteger(val.toByteArray());
}
```

`instanceof`가 아니라 `getClass() ==`로 비교하는 것이 핵심이다. `instanceof`는 하위 클래스도 통과시킨다. 진짜 `BigInteger`가 아니면 바이트 배열로 복사해 진짜 `BigInteger`를 새로 만든다.

## 버전 표기

- private 생성자 + 정적 팩터리: Java 1.0+. Java 7 레거시에서도 그대로 쓸 수 있다.
- `BigInteger.toByteArray()`, `BigInteger(byte[])`: Java 1.1+
- `(Java 17+)` `sealed` 클래스로 "허가된 하위 클래스만 확장 가능"하게 만들 수도 있다. private 생성자 방식이 "패키지 안에서만"이라는 암묵적 제한이라면, `sealed`는 `permits`로 허용 목록을 명시한다. Java 7~16에서는 private/package-private 생성자가 대안이다.

---

# 8. 규칙 완화 — "외부에 비치는 값"만 바뀌지 않으면 된다

## 원리 설명

책은 "모든 필드는 `final`"이라는 규칙이 실제로는 너무 엄격하다고 말한다. 완화된 규칙은 **"어떤 메서드도 객체의 상태 중 외부에 비치는 값을 변경할 수 없다"**이다. 대표적인 예가 계산 비용이 큰 값을 처음 필요할 때 계산해서 `final`이 아닌 필드에 캐싱하는 것이다. 몇 번을 계산해도 같은 값이므로 외부에서는 차이가 보이지 않는다.

JDK의 `String.hashCode`가 정확히 이 형태다.

```java
// JDK 소스 발췌 — java.lang.String (Java 13+ 기준, 버전에 따라 세부는 다르다)
private int hash;            // final 이 아니다 — 기본값 0
private boolean hashIsZero;  // Java 13+: 해시가 실제로 0인 문자열을 매번 재계산하지 않으려는 플래그

public int hashCode() {
    int h = hash;                          // 필드를 지역 변수로 한 번만 읽는다
    if (h == 0 && !hashIsZero) {
        h = isLatin1() ? StringLatin1.hashCode(value) : StringUTF16.hashCode(value);
        if (h == 0) hashIsZero = true;
        else hash = h;
    }
    return h;
}
```

`hash`는 `final`도 `volatile`도 아니고 동기화도 없다. 그런데도 안전한 이유는 두 가지다.

- 계산이 멱등적(idempotent)이다. 두 스레드가 동시에 계산해도 같은 값을 쓴다. 경쟁이 일어나도 계산을 한 번 더 할 뿐이다.
- `int` 쓰기는 원자적이다(JLS 17.7). 다른 스레드가 반쯤 쓰인 값을 볼 일이 없다. 따라서 `0`(아직 계산 안 됨)을 보거나 완성된 값을 볼 뿐이다.

이 두 조건이 이 기법의 전제다. 캐싱할 값이 `long`/`double`이거나 참조 타입의 가변 객체면 이 무동기화 방식은 안전하지 않다. 필드를 `h`라는 지역 변수로 한 번만 읽는 것도 중요하다. `return hash;`처럼 필드를 두 번 읽으면, 두 번째 읽기에서 `0`이 보일 수 있다는 것이 메모리 모델상 허용된다.

## 버전 표기

- 해시 캐싱 기법 자체: Java 1.0+. Java 7 레거시에서도 쓸 수 있다.
- `String.hashIsZero` 필드: Java 13+. 그 전에는 해시가 0인 문자열(`""` 등)은 호출할 때마다 다시 계산했다.
- `String`의 `Latin1`/`UTF16` 분기: Java 9+(JEP 254, Compact Strings). Java 8 이하에서는 `char[]`를 바로 순회한다.

---

# 9. 정리 — 불변으로 만들 수 없다면 변경 가능한 부분을 최소화하라

## 신입 눈높이 설명

책의 마지막 권고는 세 가지다.

- 게터가 있다고 해서 무조건 세터를 만들지 말라. 클래스는 꼭 필요한 경우가 아니면 불변이어야 한다.
- 불변으로 만들 수 없는 클래스라도 변경할 수 있는 부분을 최소한으로 줄여라. 다른 합당한 이유가 없다면 모든 필드는 `private final`이어야 한다.
- 생성자는 불변식 설정이 모두 끝난, 초기화가 완벽히 끝난 상태의 객체를 생성해야 한다. 생성자와 정적 팩터리 외에 초기화 메서드를 `public`으로 제공해서는 안 된다. 객체를 재활용할 목적으로 상태를 초기화하는 메서드도 안 된다.

책은 `CountDownLatch`를 좋은 예로 든다. 가변 클래스이지만 상태 수가 적다. 인스턴스를 생성해 한 번 쓰고 카운트가 0이 되면 더는 재사용할 수 없다. `reset()` 메서드가 없다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
public class ReportGenerator {
    private DataSource ds;
    private String title;
    public ReportGenerator() { }
    public void init(DataSource ds, String title) { this.ds = ds; this.title = title; }
    public void reset() { this.ds = null; this.title = null; }   // 재활용용
    public Report generate() { return new Report(title, ds.query()); }
}
```

### ② 무엇이 문제인가

`new ReportGenerator().generate()`가 컴파일된다. `init`을 빠뜨린 호출자는 `generate`에서 `NullPointerException`을 만난다. 스택 트레이스는 `generate` 안을 가리키지만 원인은 `init`을 부르지 않은 호출 코드다. `reset()` 뒤에 `init`을 다시 부르기 전 짧은 구간에 다른 스레드가 `generate`를 부르면 같은 일이 일어난다. 객체에는 "초기화 전", "초기화 후", "리셋 후"라는 세 가지 상태가 있고, 모든 메서드가 세 상태를 전부 고려해야 한다.

### ③ 개선된 코드

```java
public final class ReportGenerator {
    private final DataSource ds;
    private final String title;
    public ReportGenerator(DataSource ds, String title) {
        this.ds = Objects.requireNonNull(ds);
        this.title = Objects.requireNonNull(title);
    }
    public Report generate() { return new Report(title, ds.query()); }
}
```

객체가 존재한다는 것 자체가 초기화가 끝났다는 증거다. 다른 제목이 필요하면 새로 만든다.

## 버전 표기

- `Objects.requireNonNull`: Java 7+. Java 7 레거시에서도 쓸 수 있다. Java 6 이하라면 `if (ds == null) throw new NullPointerException();`
- `CountDownLatch`: Java 5+

---

# 3판(Java 9 기준) 이후 — 현재(Java 21) 시점의 보완

## record는 얕은 불변일 뿐이다

`(Java 16+)` 레코드는 규칙 1~4를 자동으로 지킨다. 변경자가 없고, 암묵적으로 `final` 클래스이며, 필드는 `private final`이다. 그러나 규칙 5는 지켜 주지 않는다.

```java
public record Team(String name, List<String> members) { }

List<String> list = new ArrayList<>(List.of("kim"));
Team t = new Team("A", list);
list.add("lee");                 // t.members() 도 [kim, lee] 가 된다
t.members().add("park");         // 접근자가 내부 리스트를 그대로 내준다
```

compact 생성자에서 직접 복사해야 한다.

```java
public record Team(String name, List<String> members) {
    public Team {
        members = List.copyOf(members);   // (Java 10+) 불변 복사본. null 원소면 NPE
    }
}
```

`List.copyOf`로 받으면 접근자가 불변 리스트를 반환하므로 접근자를 재정의할 필요가 없다. 반면 배열 컴포넌트는 불변 배열이 없으므로 생성자에서 `clone()`하고 접근자도 재정의해서 `clone()`을 반환해야 한다.

Java 7~15 대안은 1번 ③처럼 `final` 클래스 + `private final` 필드로 직접 쓰고, `Collections.unmodifiableList(new ArrayList<>(members))`로 복사 후 감싸는 것이다. 이때 `new ArrayList<>(...)`로 먼저 복사하는 것이 중요하다. `unmodifiableList`만 쓰면 원본을 감싼 뷰라서 호출자가 원본 `list`를 바꾸면 그대로 보인다. `List.copyOf`와 동작이 같아지려면 복사가 필요하다.

## record로는 7번의 "private 생성자 + 정적 팩터리"를 할 수 없다

레코드의 정규 생성자는 레코드 클래스 자신보다 접근 범위가 좁을 수 없다. `public record`의 정규 생성자는 `public`이어야 한다. 따라서 `Complex.valueOf`로 인스턴스를 캐싱하고 싶어도 `new Complex(0, 0)`을 막을 수 없다. 인스턴스 통제(캐싱, 하위 구현 교체)가 필요한 불변 클래스라면 레코드가 아니라 일반 `final` 클래스로 써야 한다.

## Stream.toList와 collect(toList())는 불변성이 다르다

- `collect(Collectors.toList())`(Java 8+): 반환 리스트의 가변성이 명세상 보장되지 않는다. 실제 구현은 `ArrayList`라서 수정할 수 있다. 수정해도 되는 줄 알고 쓰다가 구현이 바뀌면 깨질 수 있는 코드가 된다.
- `collect(Collectors.toUnmodifiableList())`(Java 10+): 불변 리스트. `null` 원소가 있으면 `NullPointerException`.
- `stream.toList()`(Java 16+): 수정 불가 리스트. `null` 원소를 허용한다는 점이 `toUnmodifiableList`와 다르다.

불변 클래스의 필드를 스트림 결과로 채운다면 `toList()`(Java 16+)나 `toUnmodifiableList()`(Java 10+)를 써야 한다. Java 8~9에서는 `Collections.unmodifiableList(stream.collect(toList()))`가 대안이다. 여기서는 `collect`가 새 리스트를 만들므로 뷰 문제가 생기지 않는다.

## Lombok @Value와 @Data

`@Value`는 클래스를 `final`로, 필드를 `private final`로 만들고 세터를 생성하지 않는다. 규칙 1~4에 해당한다. 하지만 레코드와 마찬가지로 규칙 5(가변 컴포넌트의 방어적 복사)는 해 주지 않는다. `@Value` 클래스에 `List` 필드가 있으면 위 `Team` 레코드와 같은 문제가 그대로 있다. `@Data`는 모든 필드에 세터를 만드므로 이 아이템과 정반대 방향이다.

---

# 심화 / 예외 상황

**생성자에서 `this`가 새어 나가면 final 보장이 깨진다.** 생성자 안에서 `listener.register(this)`처럼 자신을 외부에 등록하면, 다른 스레드가 생성자가 끝나기 전의 객체를 볼 수 있다. 이때는 JLS 17.5의 보장이 적용되지 않아 `final` 필드도 기본값으로 보일 수 있다. 불변 클래스의 생성자는 필드 대입과 검증만 해야 한다.

**리플렉션과 직렬화는 final을 우회한다.** `Field.setAccessible(true)` 후 `set`하면 `final` 인스턴스 필드도 바뀐다. 역직렬화는 생성자를 거치지 않고 필드를 채우므로, `Serializable`을 구현한 불변 클래스는 `readObject`에서 방어적 복사와 검증을 다시 해야 한다(12장 직렬화, 아이템 88). 레코드는 역직렬화할 때 정규 생성자를 거치므로 이 구멍이 없다. 레코드가 직렬화 측면에서 일반 불변 클래스보다 나은 점이다.

**불변이라고 무조건 빠른 것은 아니다.** 객체 생성과 GC 비용은 짧게 살다 죽는 객체에서는 거의 0에 가깝다(세대별 GC의 영 영역은 살아남은 객체만 복사한다). 다만 `BigInteger`처럼 큰 객체를 반복해서 조금씩 바꾸는 작업은 6번의 문제가 실제 병목이 된다. 이때도 가변 클래스로 공개 API를 바꾸기보다 가변 동반 클래스를 내부에 두는 방식이 먼저다.

**JPA 엔티티는 불변으로 만들기 어렵다.** 엔티티는 식별자가 같으면 같은 객체로 보고 상태 변경을 추적하는 모델이라 본질적으로 가변이다. 이 경우 9번의 권고대로 변경 경로를 최소화한다. 세터 대신 `changeAddress(Address)` 같은 의도가 드러나는 메서드만 두고, 값 타입(`@Embeddable Address`)을 불변으로 만든다. 값 타입은 공유 참조로 인한 부작용 때문에 불변으로 만드는 것이 JPA에서도 권장되는 방식이다.

---

## 핵심 키워드

- [불변 객체](../Keywords/immutable-object.md) — 다섯 규칙과 "얕은 불변 vs 깊은 불변"
- [final 필드 의미론](../Keywords/final-field-semantics.md) — 동기화 없이도 완성된 상태가 보이는 이유(JLS 17.5)
- [방어적 복사](../Keywords/defensive-copy.md) — 규칙 5를 지키는 방법과 복사 순서
- [실패 원자성](../Keywords/failure-atomicity.md) — 예외가 나도 호출 전 상태가 유지되는 성질
- [레코드](../Keywords/record.md) — 규칙 1~4는 자동, 규칙 5는 수동
- [정적 팩터리 메서드](../Keywords/static-factory-method.md) — 7번 설계 방법과 인스턴스 캐싱의 도구

## 함께 보면 좋은 아이템

- **아이템 16. public 클래스에서는 public 필드가 아닌 접근자 메서드를 사용하라** — 16에서 "세터는 필요할 때만, 가능하면 없애라"로 끝낸 이야기의 연장이다. 15(접근 권한 최소화) → 16(필드 대신 접근자) → 17(변경 가능성 최소화)이 캡슐화라는 한 덩어리다.
- **아이템 50. 적시에 방어적 복사본을 만들라** — 1번의 규칙 5와 `Period` 예제를 깊이 다룬다. 검증보다 복사가 먼저인 이유와 `clone()`을 쓰면 안 되는 경우가 여기서 나온다.
- **아이템 1. 생성자 대신 정적 팩터리 메서드를 고려하라** — 3번의 인스턴스 캐싱과 7번의 private 생성자 설계가 모두 정적 팩터리에 기댄다.
- **아이템 76. 가능한 한 실패 원자적으로 만들라** — 5번에서 본 실패 원자성을 가변 객체에서 얻는 방법(검증 먼저, 복사본에서 작업 등)을 다룬다.

(아이템 번호는 3판 한국어판 기준으로 기억하고 있으나, 50·76은 책에서 한 번 확인해 주면 확실하다.)

## 확인 질문

2번의 `Complex`에서 필드 선언만 `private final double re, im;` → `private double re, im;`으로 바꿨다. 이 클래스에는 여전히 필드를 바꾸는 메서드가 하나도 없다. 아래처럼 한 스레드가 만든 `Complex`를 `volatile`이 아닌 정적 필드로 다른 스레드에 넘길 때, 이 변경으로 무엇이 달라지는가? 그리고 `Complex.ZERO`처럼 `static final` 필드로 공유하는 상수는 영향을 받는가?

```java
static Complex shared;                                  // volatile 아님
// 스레드 A
shared = Complex.valueOf(1, 2);
// 스레드 B
Complex c = shared;
if (c != null) System.out.println(c.realPart());       // 무엇이 출력될 수 있나?
```
