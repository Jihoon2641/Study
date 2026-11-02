---
주제: BigDecimal
분류: Java / 숫자·정밀 연산
난이도: 이론
관련버전: Java 7+ (예제 코드는 Java 7 문법으로 작성, Java 21에서 실행 가능)
작성일: 2026-11-02
선행지식: [double의 부동소수점 표현, 불변 객체, equals/hashCode, JUnit 5]
---

# BigDecimal — 예제

관련 문서: [이론](./BigDecimal-이론.md)

---

## 1. 예제 개요

주문 금액 계산기(할인 절사 + 부가세 반올림)와 N분할 배분기를 만들면서, BigDecimal의 함정을 먼저 재현하고 고친다.

| 예제 | 검증하는 개념 | 이론 문서 대응 |
|---|---|---|
| `BigDecimalBad` | double 오차, double 생성자, equals/scale, 불변성, divide 예외, 배분 오차, 지수 표기 | 3장, 8.1 ~ 8.6 |
| `OrderCalculator` | 연산별 scale 변화, 정책 지점에서 `setScale` | 5.2, 5.4 |
| `Allocator` | 몫 + 나머지 배분으로 합계 보존 | 8.5 |
| `BigDecimalGood` | 위 두 클래스를 실행하는 진입점 | 9장 |
| `BigDecimalTest` | 위 동작을 단언문으로 증명 | 전체 |

---

## 2. 환경

- Java 21 (코드는 Java 7 문법만 사용 — lambda·record·var 없음)
- Gradle, JUnit 5, AssertJ (테스트용)
- Spring 불필요. Jackson/JPA 연동은 8장 변형 실습에서 다룬다.

```groovy
// build.gradle
plugins {
    id 'java'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    testImplementation 'org.assertj:assertj-core:3.25.3'
}

test {
    useJUnitPlatform()
}
```

파일 배치:

```
src/main/java/example/bigdecimal/BigDecimalBad.java
src/main/java/example/bigdecimal/OrderCalculator.java
src/main/java/example/bigdecimal/Allocator.java
src/main/java/example/bigdecimal/BigDecimalGood.java
src/test/java/example/bigdecimal/BigDecimalTest.java
```

---

## 3. 잘못된 코드 먼저

실무에서 실제로 자주 보는 실수 10가지를 한 파일에 모았다. 각 줄의 결과를 먼저 예상해본 뒤 6장의 출력과 비교해보자.

```java
package example.bigdecimal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

public class BigDecimalBad {

    public static void main(String[] args) {
        // [1] double로 금액 계산 — 2진 근사값끼리 빼서 오차가 드러난다
        System.out.println("[1] double 연산: 1.03 - 0.42 = " + (1.03 - 0.42));

        // [2] double 생성자 — double 리터럴이 이미 가진 오차가 그대로 복사된다
        System.out.println("[2] new BigDecimal(0.1) = " + new BigDecimal(0.1));

        // [3] [2]의 결과: 1.005는 실제로 1.00499999...라서 HALF_UP을 해도 내림이 된다
        System.out.println("[3] new BigDecimal(1.005).setScale(2, HALF_UP) = "
                + new BigDecimal(1.005).setScale(2, RoundingMode.HALF_UP));

        // [4] equals로 금액 비교 — scale까지 비교한다
        BigDecimal paid = new BigDecimal("2.0");
        BigDecimal expected = new BigDecimal("2.00");
        System.out.println("[4] 2.0 equals 2.00 ? " + paid.equals(expected));

        // [5] HashSet 키 — hashCode에 scale이 섞여 같은 금액이 두 번 들어간다
        Set<BigDecimal> amounts = new HashSet<BigDecimal>();
        amounts.add(paid);
        amounts.add(expected);
        System.out.println("[5] HashSet size = " + amounts.size());

        // [6] 불변성 망각 — add는 새 객체를 반환할 뿐 total을 바꾸지 않는다
        BigDecimal total = BigDecimal.ZERO;
        total.add(new BigDecimal("100"));
        System.out.println("[6] total after add (반환값 버림) = " + total);

        // [7] 자리수 없는 divide — 나눠떨어지지 않으면 예외
        try {
            BigDecimal.ONE.divide(new BigDecimal("3"));
        } catch (ArithmeticException e) {
            System.out.println("[7] 1 ÷ 3 → " + e);
        }

        // [8] 반올림 모드 없는 setScale — 버릴 자리가 있으면 예외
        try {
            new BigDecimal("1.005").setScale(2);
        } catch (ArithmeticException e) {
            System.out.println("[8] 1.005 setScale(2) → " + e);
        }

        // [9] 배분할 때 각자 반올림 — 버려진 1원을 아무도 책임지지 않는다
        BigDecimal share = new BigDecimal("10000").divide(new BigDecimal("3"), 0, RoundingMode.HALF_UP);
        System.out.println("[9] 10000원 3명 배분(HALF_UP) 합계 = " + share.multiply(new BigDecimal("3")));

        // [10] toString을 외부 출력에 사용 — 지수 표기가 나올 수 있다
        System.out.println("[10] stripTrailingZeros().toString() = "
                + new BigDecimal("100.00").stripTrailingZeros());
    }
}
```

문제 요약:

| 번호 | 문제 | 이론 문서 |
|---|---|---|
| 1 | double은 10진 소수를 정확히 표현 못 함 | 2장 |
| 2, 3 | double 생성자가 오차를 복사 | 8.1 |
| 4, 5 | equals/hashCode가 scale 포함 | 8.2 |
| 6 | 불변 객체의 반환값 무시 | 8.3 |
| 7, 8 | 반올림 규칙을 지정하지 않음 | 8.4 |
| 9 | 중간 반올림으로 합계 손실 | 8.5 |
| 10 | 지수 표기 출력 | 8.6 |

---

## 4. 개선된 코드

### 4.1 1단계 — 생성은 문자열 또는 `valueOf`

```java
BigDecimal a = new BigDecimal("1.03");        // 출처가 문자열·DB면 이것
BigDecimal b = BigDecimal.valueOf(0.42);      // 어쩔 수 없이 double을 받았다면 valueOf
BigDecimal qty = BigDecimal.valueOf(3L);      // 정수는 valueOf(long). 0~10은 캐시된 인스턴스를 재사용
```

**좋아진 점**: 오차가 BigDecimal 안으로 들어오지 않는다. `valueOf(double)`은 `Double.toString`을 거치므로 사람이 쓴 값이 된다. 다만 이미 연산으로 오차가 쌓인 double(`1.03 - 0.42`)은 valueOf로도 복구되지 않는다 — **double을 거치지 않는 게 최선**이다.

### 4.2 2단계 — 비교는 `compareTo`

```java
if (paid.compareTo(expected) == 0) { ... }      // 값만 비교
if (balance.compareTo(BigDecimal.ZERO) < 0) { ... }  // 음수 판정. signum() < 0 도 가능
```

**좋아진 점**: DB에서 읽어 scale이 바뀐 값과도 올바르게 비교된다.

### 4.3 3단계 — 모든 연산 결과를 변수에 받는다

```java
total = total.add(lineAmount);
price = price.setScale(0, RoundingMode.HALF_UP);
```

### 4.4 4단계 — 반올림은 "정책 지점"에서 명시적으로

`OrderCalculator`에서 할인은 절사(`DOWN`), 부가세는 반올림(`HALF_UP`)이라는 비즈니스 규칙을 **상수로 드러내고**, 곱셈 직후에만 `setScale`한다. 덧셈·뺄셈은 scale이 늘지 않으므로 반올림하지 않는다.

**좋아진 점**: 반올림 지점이 코드에서 보인다. 정책이 바뀌면 상수 하나만 고친다.

### 4.5 5단계 — 배분은 몫 + 나머지

`Allocator`는 `DOWN`으로 몫을 구하고 나머지를 최소 단위씩 앞사람부터 더해준다. 결과 합계는 항상 원금과 같다.

---

## 5. 전체 코드

### 5.1 `OrderCalculator.java`

```java
package example.bigdecimal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OrderCalculator {

    // 원화는 소수 자리가 없다. 통화별로 다르면 Currency#getDefaultFractionDigits()로 얻는다.
    private static final int KRW_SCALE = 0;

    // 비즈니스 정책을 상수로 드러낸다. "할인은 고객에게 불리하지 않게 절사"
    private static final RoundingMode DISCOUNT_ROUNDING = RoundingMode.DOWN;
    // "부가세는 사사오입"
    private static final RoundingMode VAT_ROUNDING = RoundingMode.HALF_UP;

    // 문자열 생성자: 0.10을 정확히 표현. double 0.1을 쓰면 55자리 근사값이 된다.
    private static final BigDecimal VAT_RATE = new BigDecimal("0.10");

    public static final class OrderLine {
        private final BigDecimal unitPrice;
        private final int quantity;

        public OrderLine(BigDecimal unitPrice, int quantity) {
            if (unitPrice == null || unitPrice.signum() < 0) {
                throw new IllegalArgumentException("unitPrice must be >= 0");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be > 0");
            }
            this.unitPrice = unitPrice;
            this.quantity = quantity;
        }

        public BigDecimal amount() {
            // 정수 × 정수라 scale은 0 그대로. 반올림 불필요.
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public static final class Receipt {
        private final BigDecimal subtotal;
        private final BigDecimal discount;
        private final BigDecimal vat;
        private final BigDecimal total;

        Receipt(BigDecimal subtotal, BigDecimal discount, BigDecimal vat, BigDecimal total) {
            this.subtotal = subtotal;
            this.discount = discount;
            this.vat = vat;
            this.total = total;
        }

        public BigDecimal getSubtotal() { return subtotal; }
        public BigDecimal getDiscount() { return discount; }
        public BigDecimal getVat() { return vat; }
        public BigDecimal getTotal() { return total; }

        @Override
        public String toString() {
            // 외부로 나가는 문자열은 지수 표기가 없는 toPlainString
            return "소계=" + subtotal.toPlainString()
                    + ", 할인=" + discount.toPlainString()
                    + ", 부가세=" + vat.toPlainString()
                    + ", 합계=" + total.toPlainString();
        }
    }

    public Receipt calculate(List<OrderLine> lines, BigDecimal discountRate) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderLine line : lines) {
            // 불변 객체: 반환값을 다시 대입해야 누적된다
            subtotal = subtotal.add(line.amount());
        }

        // 곱셈은 scale이 더해진다: 68700(0) × 0.15(2) = 10305.00(2)
        // 그래서 곱셈 직후가 정책을 적용할 지점이다
        BigDecimal discount = subtotal.multiply(discountRate)
                .setScale(KRW_SCALE, DISCOUNT_ROUNDING);

        BigDecimal taxable = subtotal.subtract(discount);

        // 58395(0) × 0.10(2) = 5839.50(2) → HALF_UP → 5840
        BigDecimal vat = taxable.multiply(VAT_RATE)
                .setScale(KRW_SCALE, VAT_ROUNDING);

        // 덧셈은 scale이 늘지 않으므로 추가 반올림 없음
        BigDecimal total = taxable.add(vat);

        return new Receipt(subtotal, discount, vat, total);
    }

    public static List<OrderLine> sampleLines() {
        List<OrderLine> lines = new ArrayList<OrderLine>();
        lines.add(new OrderLine(new BigDecimal("19900"), 3));
        lines.add(new OrderLine(new BigDecimal("4500"), 2));
        return Collections.unmodifiableList(lines);
    }
}
```

> Java 16+ 라면 `OrderLine`, `Receipt`를 `record`로 줄일 수 있다.
> ```java
> public record OrderLine(BigDecimal unitPrice, int quantity) {
>     public OrderLine {
>         if (unitPrice == null || unitPrice.signum() < 0) throw new IllegalArgumentException("unitPrice must be >= 0");
>         if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
>     }
>     public BigDecimal amount() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
> }
> ```
> 주의: record의 자동 생성 `equals`는 필드의 `equals`를 쓰므로 `BigDecimal` 필드의 scale이 다르면 두 record는 다르다고 판단된다(이론 8.2).

### 5.2 `Allocator.java`

```java
package example.bigdecimal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class Allocator {

    /**
     * total을 n명에게 배분한다. 합계는 항상 total과 같다.
     * 나머지는 최소 단위씩 앞사람부터 더한다(정책에 따라 바꿀 수 있는 지점).
     */
    public List<BigDecimal> allocate(BigDecimal total, int n, int scale) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be > 0");
        }
        BigDecimal count = BigDecimal.valueOf(n);

        // 최소 단위: scale 0 → 1, scale 2 → 0.01
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);

        // DOWN으로 몫을 구해야 나머지가 항상 0 이상이 된다.
        // HALF_UP을 쓰면 몫 × n이 total을 넘어 나머지가 음수가 될 수 있다.
        BigDecimal share = total.divide(count, scale, RoundingMode.DOWN);
        BigDecimal remainder = total.subtract(share.multiply(count));

        // 나머지는 unit의 정수배이므로 자리수 없는 divide가 안전하다.
        // intValueExact는 소수부가 남거나 int 범위를 넘으면 예외 — 가정이 깨지면 바로 알 수 있다.
        int extraUnits = remainder.divide(unit).intValueExact();

        List<BigDecimal> result = new ArrayList<BigDecimal>(n);
        for (int i = 0; i < n; i++) {
            result.add(i < extraUnits ? share.add(unit) : share);
        }
        return result;
    }
}
```

### 5.3 `BigDecimalGood.java`

```java
package example.bigdecimal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

public class BigDecimalGood {

    public static void main(String[] args) {
        // 생성: 문자열 / valueOf
        System.out.println("[1] 1.03 - 0.42 = "
                + new BigDecimal("1.03").subtract(new BigDecimal("0.42")));
        System.out.println("[2] BigDecimal.valueOf(1.005).setScale(2, HALF_UP) = "
                + BigDecimal.valueOf(1.005).setScale(2, RoundingMode.HALF_UP));

        // 비교: compareTo
        System.out.println("[3] 2.0 compareTo 2.00 == 0 ? "
                + (new BigDecimal("2.0").compareTo(new BigDecimal("2.00")) == 0));

        // 나눗셈: scale + RoundingMode 또는 MathContext
        System.out.println("[4] 1 ÷ 3 (scale 4, HALF_UP) = "
                + BigDecimal.ONE.divide(new BigDecimal("3"), 4, RoundingMode.HALF_UP));
        System.out.println("[5] 1 ÷ 3 (DECIMAL128) = "
                + BigDecimal.ONE.divide(new BigDecimal("3"), MathContext.DECIMAL128));

        // 주문 계산
        OrderCalculator calculator = new OrderCalculator();
        OrderCalculator.Receipt receipt =
                calculator.calculate(OrderCalculator.sampleLines(), new BigDecimal("0.15"));
        System.out.println("[6] 주문: " + receipt);

        // 배분
        Allocator allocator = new Allocator();
        List<BigDecimal> krw = allocator.allocate(new BigDecimal("10000"), 3, 0);
        System.out.println("[7] 10000원 3명 배분 = " + krw + ", 합계 = " + sum(krw));
        List<BigDecimal> usd = allocator.allocate(new BigDecimal("100.00"), 3, 2);
        System.out.println("[8] 100.00달러 3명 배분 = " + usd + ", 합계 = " + sum(usd));

        // 출력: toPlainString
        System.out.println("[9] stripTrailingZeros().toPlainString() = "
                + new BigDecimal("100.00").stripTrailingZeros().toPlainString());
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum;
    }
}
```

`BigDecimalBad.java`는 3장 코드 그대로다.

---

## 6. 실행 결과

> 이 환경에서 직접 실행하지 못했다. 아래는 **예상 출력**이다. 계산 값은 JDK 버전과 무관하게 동일하며, 예외 메시지 문구는 OpenJDK 기준이라 JDK 구현에 따라 다를 수 있다.

### `BigDecimalBad` (예상 출력)

```
[1] double 연산: 1.03 - 0.42 = 0.6100000000000001
[2] new BigDecimal(0.1) = 0.1000000000000000055511151231257827021181583404541015625
[3] new BigDecimal(1.005).setScale(2, HALF_UP) = 1.00
[4] 2.0 equals 2.00 ? false
[5] HashSet size = 2
[6] total after add (반환값 버림) = 0
[7] 1 ÷ 3 → java.lang.ArithmeticException: Non-terminating decimal expansion; no exact representable decimal result.
[8] 1.005 setScale(2) → java.lang.ArithmeticException: Rounding necessary
[9] 10000원 3명 배분(HALF_UP) 합계 = 9999
[10] stripTrailingZeros().toString() = 1E+2
```

### `BigDecimalGood` (예상 출력)

```
[1] 1.03 - 0.42 = 0.61
[2] BigDecimal.valueOf(1.005).setScale(2, HALF_UP) = 1.01
[3] 2.0 compareTo 2.00 == 0 ? true
[4] 1 ÷ 3 (scale 4, HALF_UP) = 0.3333
[5] 1 ÷ 3 (DECIMAL128) = 0.3333333333333333333333333333333333
[6] 주문: 소계=68700, 할인=10305, 부가세=5840, 합계=64235
[7] 10000원 3명 배분 = [3334, 3333, 3333], 합계 = 10000
[8] 100.00달러 3명 배분 = [33.34, 33.33, 33.33], 합계 = 100.00
[9] stripTrailingZeros().toPlainString() = 100
```

주문 계산 과정의 scale 변화:

| 단계 | 연산 | 값 | scale |
|---|---|---|---|
| 라인1 | 19900 × 3 | 59700 | 0 |
| 라인2 | 4500 × 2 | 9000 | 0 |
| 소계 | 59700 + 9000 | 68700 | 0 |
| 할인(반올림 전) | 68700 × 0.15 | 10305.00 | 2 |
| 할인 | setScale(0, DOWN) | 10305 | 0 |
| 과세 금액 | 68700 − 10305 | 58395 | 0 |
| 부가세(반올림 전) | 58395 × 0.10 | 5839.50 | 2 |
| 부가세 | setScale(0, HALF_UP) | 5840 | 0 |
| 합계 | 58395 + 5840 | 64235 | 0 |

---

## 7. 검증 코드

```java
package example.bigdecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BigDecimalTest {

    @Test
    @DisplayName("double 연산은 10진 소수를 정확히 표현하지 못한다")
    void doubleIsInexact() {
        assertNotEquals(0.61, 1.03 - 0.42);
        assertEquals(0, new BigDecimal("1.03").subtract(new BigDecimal("0.42"))
                .compareTo(new BigDecimal("0.61")));
    }

    @Test
    @DisplayName("double 생성자는 오차를 복사하고, valueOf는 Double.toString을 거친다")
    void constructorVsValueOf() {
        assertEquals("0.1000000000000000055511151231257827021181583404541015625",
                new BigDecimal(0.1).toString());
        assertEquals("0.1", BigDecimal.valueOf(0.1).toString());

        // 1.005의 실제 double 값은 1.00499999...라서 HALF_UP으로도 내려간다
        assertEquals("1.00", new BigDecimal(1.005).setScale(2, RoundingMode.HALF_UP).toString());
        assertEquals("1.01", BigDecimal.valueOf(1.005).setScale(2, RoundingMode.HALF_UP).toString());
    }

    @Test
    @DisplayName("equals는 scale까지 비교하고 compareTo는 값만 비교한다")
    void equalsVsCompareTo() {
        BigDecimal a = new BigDecimal("2.0");
        BigDecimal b = new BigDecimal("2.00");

        assertFalse(a.equals(b));
        assertEquals(0, a.compareTo(b));
        assertThat(a).isEqualByComparingTo(b);   // AssertJ: compareTo 기반 단언

        Set<BigDecimal> hashSet = new HashSet<BigDecimal>(Arrays.asList(a, b));
        Set<BigDecimal> treeSet = new TreeSet<BigDecimal>(Arrays.asList(a, b));
        assertEquals(2, hashSet.size());   // equals/hashCode 기준
        assertEquals(1, treeSet.size());   // compareTo 기준
    }

    @Test
    @DisplayName("연산은 새 객체를 반환하고 원본은 그대로다")
    void immutability() {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal returned = total.add(new BigDecimal("100"));

        assertEquals(0, total.signum());
        assertEquals(0, returned.compareTo(new BigDecimal("100")));
    }

    @Test
    @DisplayName("덧셈은 max(scale), 곱셈은 scale의 합")
    void scaleRules() {
        BigDecimal x = new BigDecimal("1.5");
        BigDecimal y = new BigDecimal("2.25");

        assertEquals(2, x.add(y).scale());
        assertEquals("3.75", x.add(y).toString());
        assertEquals(3, x.multiply(y).scale());
        assertEquals("3.375", x.multiply(y).toString());
    }

    @Test
    @DisplayName("자리수 없는 divide와 반올림 모드 없는 setScale은 예외를 던질 수 있다")
    void roundingMustBeExplicit() {
        assertThrows(ArithmeticException.class, new Executable() {
            @Override
            public void execute() {
                BigDecimal.ONE.divide(new BigDecimal("3"));
            }
        });
        assertThrows(ArithmeticException.class, new Executable() {
            @Override
            public void execute() {
                new BigDecimal("1.005").setScale(2);
            }
        });

        // 나눠떨어지면 자리수 없는 divide도 성공한다 — 테스트에서 문제를 놓치는 이유
        assertEquals(0, new BigDecimal("10000").divide(new BigDecimal("4"))
                .compareTo(new BigDecimal("2500")));

        assertEquals("0.3333", BigDecimal.ONE.divide(new BigDecimal("3"), 4, RoundingMode.HALF_UP).toString());
        assertEquals(34, BigDecimal.ONE.divide(new BigDecimal("3"), MathContext.DECIMAL128).precision());
    }

    @Test
    @DisplayName("주문 계산: 할인 절사, 부가세 반올림")
    void orderCalculation() {
        OrderCalculator.Receipt receipt = new OrderCalculator()
                .calculate(OrderCalculator.sampleLines(), new BigDecimal("0.15"));

        assertThat(receipt.getSubtotal()).isEqualByComparingTo("68700");
        assertThat(receipt.getDiscount()).isEqualByComparingTo("10305");
        assertThat(receipt.getVat()).isEqualByComparingTo("5840");   // 5839.50 → HALF_UP
        assertThat(receipt.getTotal()).isEqualByComparingTo("64235");
        assertEquals(0, receipt.getTotal().scale());
    }

    @Test
    @DisplayName("배분 결과의 합계는 항상 원금과 같다")
    void allocationPreservesTotal() {
        Allocator allocator = new Allocator();

        List<BigDecimal> krw = allocator.allocate(new BigDecimal("10000"), 3, 0);
        assertEquals(Arrays.asList(new BigDecimal("3334"), new BigDecimal("3333"), new BigDecimal("3333")), krw);
        assertThat(sum(krw)).isEqualByComparingTo("10000");

        List<BigDecimal> usd = allocator.allocate(new BigDecimal("100.00"), 3, 2);
        assertThat(sum(usd)).isEqualByComparingTo("100.00");

        // 각자 반올림하는 방식은 1원을 잃는다
        BigDecimal naive = new BigDecimal("10000").divide(new BigDecimal("3"), 0, RoundingMode.HALF_UP);
        assertThat(naive.multiply(new BigDecimal("3"))).isEqualByComparingTo("9999");
    }

    @Test
    @DisplayName("toString은 지수 표기를 쓸 수 있고 toPlainString은 쓰지 않는다")
    void plainString() {
        BigDecimal stripped = new BigDecimal("100.00").stripTrailingZeros();

        assertEquals(-2, stripped.scale());
        assertEquals("1E+2", stripped.toString());
        assertEquals("100", stripped.toPlainString());

        // Java 8+: 0의 stripTrailingZeros는 BigDecimal.ZERO (Java 7에선 0.000 유지)
        assertEquals("0", new BigDecimal("0.000").stripTrailingZeros().toString());
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum;
    }

    // assertTrue는 아래처럼 compareTo 결과를 직접 확인할 때 쓸 수 있다
    @Test
    void negativeCheck() {
        assertTrue(new BigDecimal("-0.01").signum() < 0);
        assertTrue(new BigDecimal("-0.01").compareTo(BigDecimal.ZERO) < 0);
    }
}
```

> Java 8+ 라면 `new Executable() { ... }` 익명 클래스를 `() -> BigDecimal.ONE.divide(new BigDecimal("3"))` 람다로 줄일 수 있다.
> `allocationPreservesTotal`의 `assertEquals(List, List)`는 원소의 `equals`를 쓰므로 scale까지 일치해야 통과한다 — 여기서는 모두 scale 0이라 통과한다.

---

## 8. 변형 실습

### 8.1 부가세 반올림을 `HALF_EVEN`으로 바꾸면?

`VAT_ROUNDING`을 `RoundingMode.HALF_EVEN`으로 바꾼다. 이 샘플(5839.50)에서는 앞자리 9가 홀수라 짝수 쪽인 5840으로 올라가 결과가 같다.
단가를 조정해 반올림 전 부가세가 `xxx8.50`이 되게 만들면(예: 과세 금액 58385 → 5838.50) `HALF_UP`은 5839, `HALF_EVEN`은 5838이 된다. **어떤 경계값에서 두 정책이 갈리는지** 테스트 케이스로 고정해보자.

### 8.2 할인을 라인별로 적용하면?

할인을 소계에 한 번 적용하는 대신 라인마다 `amount × 0.15 → setScale(0, DOWN)`을 한 뒤 합산하도록 바꾼다.
19900 × 3 = 59700 → 8955, 4500 × 2 = 9000 → 1350, 합 10305로 이 샘플에서는 같다.
라인을 `19999 × 3`, `4503 × 1`로 바꾸면 갈린다.

| 방식 | 계산 | 할인 |
|---|---|---|
| 라인별 절사 | `59997 × 0.15 = 8999.55 → 8999`, `4503 × 0.15 = 675.45 → 675` | 9674 |
| 소계에 한 번 | `64500 × 0.15 = 9675.00 → 9675` | 9675 |

라인마다 버려진 0.55 + 0.45 = 1원이 차이다. **반올림 지점이 많아질수록 버려지는 조각이 쌓인다**(이론 8.5). 어느 방식이 맞는지는 기술이 아니라 정책(영수증에 라인별 할인을 표시하는가)이 정한다.

### 8.3 Jackson으로 직렬화하면?

Spring Boot 프로젝트에서 `Receipt`를 응답으로 반환하고, 금액 하나를 `new BigDecimal("100.00").stripTrailingZeros()`로 바꿔보자.
- 기본 설정: JSON에 `1E+2`가 숫자로 나갈 수 있다.
- `application.properties`에 `spring.jackson.generator.write-bigdecimal-as-plain=true` 추가 → `100`.
- 필드에 `@JsonFormat(shape = JsonFormat.Shape.STRING)` → `"100"` 문자열. React에서 큰 금액의 JS `Number` 정밀도 손실(2^53 초과)을 피할 때 쓴다.

각 경우의 응답 JSON을 직접 확인하고, 프론트에서 `JSON.parse` 결과가 어떻게 달라지는지 비교해보자.
