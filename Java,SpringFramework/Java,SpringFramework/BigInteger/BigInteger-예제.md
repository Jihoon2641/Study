---
주제: BigInteger
분류: Java / 숫자·정밀 연산
난이도: 이론
관련버전: Java 7+ (예제 코드는 Java 7 문법, Java 9·17 API는 대안 코드로 병기)
작성일: 2026-11-03
선행지식: [long 오버플로, 2의 보수, 불변 객체, JUnit 5]
---

# BigInteger — 예제

관련 문서: [이론](./BigInteger-이론.md)

---

## 1. 예제 개요

long 넘침, 해시 바이트의 16진 변환, 해시 기반 샤딩, 외부 입력 파싱을 차례로 다루면서 BigInteger의 **경계 변환 함정**(이론 5.3)을 재현하고 고친다.

| 예제 | 검증하는 개념 | 이론 문서 대응 |
|---|---|---|
| `BigIntegerBad` | long wrap, 축소 변환 잘림, 앞자리 0 손실, 부호 바이트, remainder 음수, 불변성, `==` 캐시 | 2장, 8.1 ~ 8.5, 6장 |
| `Factorials` | long 넘침 감지(`Math.multiplyExact`)와 BigInteger 정확 계산 | 2장, 7장 |
| `HexCodec` | unsigned 해석, 고정 길이 16진/바이트 변환 | 8.2, 8.3 |
| `ShardRouter` | `mod`로 음수 없는 버킷 번호 | 8.4, 9장 |
| `SafeBigIntegerParser` | 입력 길이·bitLength 제한 | 8.7 |
| `BigIntegerTest` | 위 동작의 단언문 증명 | 전체 |

---

## 2. 환경

- Java 21 (코드는 Java 7 문법만 사용 — lambda·record·var·`BigInteger.TWO`·`HexFormat` 없음)
- Gradle, JUnit 5, AssertJ
- Spring 불필요. JSON 직렬화는 8장 변형 실습에서 다룬다.

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
src/main/java/example/biginteger/BigIntegerBad.java
src/main/java/example/biginteger/Factorials.java
src/main/java/example/biginteger/HexCodec.java
src/main/java/example/biginteger/ShardRouter.java
src/main/java/example/biginteger/SafeBigIntegerParser.java
src/main/java/example/biginteger/BigIntegerGood.java
src/test/java/example/biginteger/BigIntegerTest.java
```

---

## 3. 잘못된 코드 먼저

```java
package example.biginteger;

import java.math.BigInteger;

public class BigIntegerBad {

    public static void main(String[] args) {
        // [1] long 넘침 — 예외 없이 반대편 끝으로 돈다
        System.out.println("[1] Long.MAX_VALUE + 1 = " + (Long.MAX_VALUE + 1));

        // [2] 21!은 long 범위(약 9.22 × 10^18)를 넘는다. 결과는 쓰레기 값
        System.out.println("[2] factorial(21) with long = " + factorialLong(21));

        // [3] BigInteger로 정확히 계산해 놓고 longValue()로 꺼내면 다시 잘린다
        BigInteger over = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        System.out.println("[3] (MAX+1).longValue() = " + over.longValue());

        // [4] 해시 바이트를 BigInteger로 16진 변환 — 원래 길이를 모르므로 앞자리 0이 사라진다
        byte[] hash = { 0x00, 0x0A, (byte) 0xFF };
        System.out.println("[4] hex of {00,0A,FF} = " + new BigInteger(1, hash).toString(16));

        // [5] signum 없이 byte[] 생성자 — 2의 보수로 읽어 0xFF가 -1이 된다
        System.out.println("[5] new BigInteger({0xFF}) = " + new BigInteger(new byte[] { (byte) 0xFF }));

        // [6] toByteArray — 최상위 비트가 1인 양수에 0x00 부호 바이트가 붙어 길이가 늘어난다
        System.out.println("[6] valueOf(255).toByteArray().length = " + BigInteger.valueOf(255).toByteArray().length);

        // [7] remainder — 부호가 피제수를 따라 음수 인덱스가 나온다
        System.out.println("[7] -7 remainder 3 = " + BigInteger.valueOf(-7).remainder(BigInteger.valueOf(3)));

        // [8] 불변성 망각 — add는 새 객체를 반환할 뿐이다
        BigInteger sum = BigInteger.ZERO;
        sum.add(BigInteger.TEN);
        System.out.println("[8] sum after add (반환값 버림) = " + sum);

        // [9] == 비교 — valueOf는 -16~16을 캐시하므로 작은 값에서만 우연히 true
        System.out.println("[9] valueOf(10) == valueOf(10) ? " + (BigInteger.valueOf(10) == BigInteger.valueOf(10))
                + " / valueOf(100) == valueOf(100) ? " + (BigInteger.valueOf(100) == BigInteger.valueOf(100)));
    }

    static long factorialLong(int n) {
        long result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
```

| 번호 | 문제 | 이론 문서 |
|---|---|---|
| 1, 2 | 고정 길이 정수의 조용한 wrap-around | 2장 |
| 3 | 축소 변환은 범위 검사 없이 하위 비트만 남김 | 8.1 |
| 4 | 값만 저장하므로 앞자리 0 복원 불가 | 8.2 |
| 5, 6 | byte[] 변환은 2의 보수 | 8.3 |
| 7 | remainder 부호 | 8.4 |
| 8 | 불변 객체 반환값 무시 | 8.5 |
| 9 | 캐시된 인스턴스 때문에 `==` 버그가 숨음 | 6장 |

---

## 4. 개선된 코드

### 4.1 1단계 — 넘침을 감지하거나, 넘치지 않는 타입으로

```java
long safe = Math.multiplyExact(a, b);      // 넘치면 ArithmeticException: long overflow (Java 8+)
BigInteger exact = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));  // 넘치지 않음
```

**좋아진 점**: 잘못된 값이 조용히 흘러가지 않는다. long으로 충분하면 `*Exact`, 범위가 예측 불가하면 BigInteger.

### 4.2 2단계 — 꺼낼 때는 `*ValueExact`

```java
long id = big.longValueExact();             // 범위를 넘으면 ArithmeticException
```

Java 7이라면:

```java
if (big.bitLength() > 63) {
    throw new ArithmeticException("BigInteger out of long range: " + big);
}
long id = big.longValue();
```

### 4.3 3단계 — 바이트·16진 변환은 길이를 명시

`HexCodec`이 `new BigInteger(1, bytes)`로 항상 양수로 읽고, 내보낼 때는 **고정 길이**로 0을 채우거나 부호 바이트를 뗀다.

**좋아진 점**: 해시 64자, 키 32바이트 같은 외부 계약이 값과 무관하게 지켜진다.

### 4.4 4단계 — 버킷 번호는 `mod`

`ShardRouter`는 `mod`로 항상 `[0, n)`을 반환한다.

### 4.5 5단계 — 외부 입력은 크기부터 검사

`SafeBigIntegerParser`가 문자열 길이를 먼저 보고, 파싱 후 `bitLength()`를 다시 확인한다.

---

## 5. 전체 코드

### 5.1 `Factorials.java`

```java
package example.biginteger;

import java.math.BigInteger;

public class Factorials {

    /** long으로 계산하되 넘치면 즉시 실패한다. 20!까지만 성공한다. */
    public static long factorialExact(int n) {
        long result = 1;
        for (int i = 2; i <= n; i++) {
            // 넘침을 결과값 검사로 잡으려 하면 늦다. 곱하는 순간 감지해야 한다.
            result = Math.multiplyExact(result, i);
        }
        return result;
    }

    /** 범위 제한 없이 정확히 계산한다. */
    public static BigInteger factorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        BigInteger result = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            // 불변 객체: 반환값을 다시 대입해야 누적된다. 반복마다 새 객체가 생긴다.
            result = result.multiply(BigInteger.valueOf(i));
        }
        return result;
    }
}
```

### 5.2 `HexCodec.java`

```java
package example.biginteger;

import java.math.BigInteger;

public class HexCodec {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * 바이트 배열 → 16진 문자열. 수치 연산이 필요 없으면 BigInteger를 거치지 않는 게 정답이다.
     * 바이트 수 × 2 길이가 항상 보장된다.
     */
    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;   // byte는 signed라 & 0xFF로 0~255 범위로 만든다
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** 바이트 배열을 항상 양수(unsigned)로 해석한다. signum 1이 핵심. */
    public static BigInteger fromUnsignedBytes(byte[] bytes) {
        return new BigInteger(1, bytes);
    }

    /** BigInteger → 고정 길이 16진 문자열. 앞자리를 0으로 채운다. */
    public static String toFixedHex(BigInteger value, int byteLength) {
        requireFits(value, byteLength);
        // %x는 BigInteger를 직접 받는다. 폭 지정 + 0 채움으로 길이를 고정
        return String.format("%0" + (byteLength * 2) + "x", value);
    }

    /**
     * BigInteger → 고정 길이 unsigned 바이트 배열.
     * toByteArray()는 2의 보수라 부호 바이트(0x00)가 붙거나 앞자리 0바이트가 빠진다.
     */
    public static byte[] toFixedBytes(BigInteger value, int length) {
        requireFits(value, length);
        byte[] raw = value.toByteArray();

        // 최상위 비트가 1인 양수는 앞에 0x00 부호 바이트가 붙어 한 칸 길다
        int srcOffset = (raw.length > length) ? raw.length - length : 0;
        int copyLength = raw.length - srcOffset;

        byte[] out = new byte[length];   // 앞쪽 남는 칸은 0으로 채워진 상태
        System.arraycopy(raw, srcOffset, out, length - copyLength, copyLength);
        return out;
    }

    private static void requireFits(BigInteger value, int byteLength) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("negative value: " + value);
        }
        // bitLength는 부호 비트를 제외한 최소 비트 수 → unsigned 바이트 길이 판단 기준
        if (value.bitLength() > byteLength * 8) {
            throw new IllegalArgumentException("value does not fit in " + byteLength + " bytes: " + value);
        }
    }
}
```

> Java 17+ 라면 `toHex`를 표준 API로 대체한다.
> ```java
> import java.util.HexFormat;
>
> String hex = HexFormat.of().formatHex(bytes);   // 길이 = bytes.length * 2 보장
> byte[] back = HexFormat.of().parseHex(hex);
> ```

### 5.3 `ShardRouter.java`

```java
package example.biginteger;

import java.math.BigInteger;

public class ShardRouter {

    private final BigInteger shardCount;

    public ShardRouter(int shardCount) {
        if (shardCount <= 0) {
            // mod는 0 이하 modulus에 ArithmeticException을 던진다. 생성 시점에 막는다.
            throw new IllegalArgumentException("shardCount must be > 0");
        }
        this.shardCount = BigInteger.valueOf(shardCount);
    }

    /** 결과는 항상 [0, shardCount). remainder는 음수가 나올 수 있어 쓰지 않는다. */
    public int shardOf(BigInteger id) {
        // mod 결과 < shardCount <= Integer.MAX_VALUE 이므로 intValue()가 잘리지 않는다
        return id.mod(shardCount).intValue();
    }

    /** 해시 바이트를 unsigned로 해석해 샤드를 고른다. */
    public int shardOfHash(byte[] hash) {
        return shardOf(new BigInteger(1, hash));
    }
}
```

### 5.4 `SafeBigIntegerParser.java`

```java
package example.biginteger;

import java.math.BigInteger;

public class SafeBigIntegerParser {

    private final int maxDigits;
    private final int maxBitLength;

    public SafeBigIntegerParser(int maxDigits, int maxBitLength) {
        this.maxDigits = maxDigits;
        this.maxBitLength = maxBitLength;
    }

    public BigInteger parse(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("empty input");
        }
        // 파싱 비용은 문자열 길이에 비례해 커진다. 파싱 '전에' 길이부터 막아야 한다.
        int digits = (input.charAt(0) == '-' || input.charAt(0) == '+') ? input.length() - 1 : input.length();
        if (digits > maxDigits) {
            throw new IllegalArgumentException("too many digits: " + digits);
        }
        BigInteger value = new BigInteger(input);   // 숫자가 아니면 NumberFormatException

        // 자릿수 검사는 근사치다. 업무 한도(예: unsigned 64비트)는 bitLength로 정확히 확인한다.
        if (value.bitLength() > maxBitLength) {
            throw new IllegalArgumentException("value too large: bitLength=" + value.bitLength());
        }
        return value;
    }
}
```

### 5.5 `BigIntegerGood.java`

```java
package example.biginteger;

import java.math.BigInteger;
import java.util.Arrays;

public class BigIntegerGood {

    public static void main(String[] args) {
        // 넘침 감지 vs 정확 계산
        try {
            Factorials.factorialExact(21);
        } catch (ArithmeticException e) {
            System.out.println("[1] factorialExact(21) → " + e);
        }
        System.out.println("[2] factorial(21) = " + Factorials.factorial(21));
        System.out.println("[3] factorial(25) = " + Factorials.factorial(25));

        // 꺼낼 때는 *ValueExact
        BigInteger over = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        try {
            over.longValueExact();
        } catch (ArithmeticException e) {
            System.out.println("[4] (MAX+1).longValueExact() → " + e);
        }

        // 고정 길이 16진·바이트
        byte[] hash = { 0x00, 0x0A, (byte) 0xFF };
        System.out.println("[5] toHex({00,0A,FF}) = " + HexCodec.toHex(hash)
                + ", toFixedHex = " + HexCodec.toFixedHex(HexCodec.fromUnsignedBytes(hash), 3));
        System.out.println("[6] fromUnsignedBytes({0xFF}) = " + HexCodec.fromUnsignedBytes(new byte[] { (byte) 0xFF }));
        System.out.println("[7] toFixedBytes(255, 1) = " + Arrays.toString(HexCodec.toFixedBytes(BigInteger.valueOf(255), 1))
                + ", toFixedBytes(10, 4) = " + Arrays.toString(HexCodec.toFixedBytes(BigInteger.TEN, 4)));

        // 샤딩
        ShardRouter router = new ShardRouter(16);
        System.out.println("[8] shardOfHash({FF,FF}) = " + router.shardOfHash(new byte[] { (byte) 0xFF, (byte) 0xFF })
                + ", shardOf(-7) = " + router.shardOf(BigInteger.valueOf(-7)));

        // unsigned 64비트 ID: BigInteger vs long 하나에 담기
        BigInteger unsignedMax = new BigInteger("18446744073709551615");   // 2^64 - 1
        System.out.println("[9] 2^64-1: shard = " + router.shardOf(unsignedMax)
                + ", longValue = " + unsignedMax.longValue()
                + ", Long.toUnsignedString = " + Long.toUnsignedString(unsignedMax.longValue()));

        // 외부 입력 검증: 최대 20자리, unsigned 64비트
        SafeBigIntegerParser parser = new SafeBigIntegerParser(20, 64);
        try {
            parser.parse("123456789012345678901234567890");
        } catch (IllegalArgumentException e) {
            System.out.println("[10] parse(30자리) → " + e);
        }
        try {
            parser.parse("99999999999999999999");   // 20자리지만 2^64를 넘음
        } catch (IllegalArgumentException e) {
            System.out.println("[11] parse(20자리, 2^64 초과) → " + e);
        }
    }
}
```

`BigIntegerBad.java`는 3장 코드 그대로다.

---

## 6. 실행 결과

> 이 환경에서 직접 실행하지 못했다. 아래는 **예상 출력**이다. 계산 값은 JDK 버전과 무관하며, 예외 메시지 문구는 OpenJDK 기준이라 구현에 따라 다를 수 있다.

### `BigIntegerBad` (예상 출력)

```
[1] Long.MAX_VALUE + 1 = -9223372036854775808
[2] factorial(21) with long = -4249290049419214848
[3] (MAX+1).longValue() = -9223372036854775808
[4] hex of {00,0A,FF} = aff
[5] new BigInteger({0xFF}) = -1
[6] valueOf(255).toByteArray().length = 2
[7] -7 remainder 3 = -1
[8] sum after add (반환값 버림) = 0
[9] valueOf(10) == valueOf(10) ? true / valueOf(100) == valueOf(100) ? false
```

### `BigIntegerGood` (예상 출력)

```
[1] factorialExact(21) → java.lang.ArithmeticException: long overflow
[2] factorial(21) = 51090942171709440000
[3] factorial(25) = 15511210043330985984000000
[4] (MAX+1).longValueExact() → java.lang.ArithmeticException: BigInteger out of long range
[5] toHex({00,0A,FF}) = 000aff, toFixedHex = 000aff
[6] fromUnsignedBytes({0xFF}) = 255
[7] toFixedBytes(255, 1) = [-1], toFixedBytes(10, 4) = [0, 0, 0, 10]
[8] shardOfHash({FF,FF}) = 15, shardOf(-7) = 9
[9] 2^64-1: shard = 15, longValue = -1, Long.toUnsignedString = 18446744073709551615
[10] parse(30자리) → java.lang.IllegalArgumentException: too many digits: 30
[11] parse(20자리, 2^64 초과) → java.lang.IllegalArgumentException: value too large: bitLength=67
```

출력 해설:
- `[7]`의 `[-1]`은 바이트 `0xFF`를 Java의 signed `byte`로 출력한 것이다. 값은 255가 맞다.
- `[8]` `{FF,FF}` = 65535 = 16 × 4095 + 15. `-7 mod 16` = 9 (−7 + 16).
- `[9]` 2^64−1의 하위 64비트는 전부 1이라 signed long으로는 −1이다. 같은 비트를 unsigned로 해석하면 원래 값. **long 하나에 unsigned를 담는 방식은 비트를 보존할 뿐, 해석은 매번 `*Unsigned` 메서드로 해야 한다.**
- `[11]` 99,999,999,999,999,999,999 ≈ 2^66.4 이므로 bitLength 67. 자릿수 검사만으로는 막지 못하는 경우다.

---

## 7. 검증 코드

```java
package example.biginteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BigIntegerTest {

    private static final BigInteger TWO_POW_63 = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

    @Test
    @DisplayName("long은 조용히 넘치고, multiplyExact는 감지하고, BigInteger는 정확하다")
    void overflow() {
        assertEquals(Long.MIN_VALUE, Long.MAX_VALUE + 1);
        assertEquals(2432902008176640000L, Factorials.factorialExact(20));   // 20!은 long 안에 들어간다
        assertThrows(ArithmeticException.class, new Executable() {
            @Override
            public void execute() {
                Factorials.factorialExact(21);
            }
        });
        assertEquals(new BigInteger("51090942171709440000"), Factorials.factorial(21));
    }

    @Test
    @DisplayName("longValue는 잘라내고 longValueExact는 예외를 던진다")
    void narrowing() {
        assertEquals(Long.MIN_VALUE, TWO_POW_63.longValue());
        assertEquals(64, TWO_POW_63.bitLength());
        assertEquals(63, BigInteger.valueOf(Long.MAX_VALUE).bitLength());
        assertThrows(ArithmeticException.class, new Executable() {
            @Override
            public void execute() {
                TWO_POW_63.longValueExact();
            }
        });
    }

    @Test
    @DisplayName("toString(16)은 앞자리 0을 잃고, 고정 길이 변환은 보존한다")
    void hexLeadingZeros() {
        byte[] hash = { 0x00, 0x0A, (byte) 0xFF };

        assertEquals("aff", new BigInteger(1, hash).toString(16));
        assertEquals("000aff", HexCodec.toHex(hash));
        assertEquals("000aff", HexCodec.toFixedHex(new BigInteger(1, hash), 3));
    }

    @Test
    @DisplayName("byte[] 생성자는 2의 보수, signum 1 생성자는 unsigned")
    void signedVsUnsignedBytes() {
        byte[] ff = { (byte) 0xFF };

        assertEquals(BigInteger.valueOf(-1), new BigInteger(ff));
        assertEquals(BigInteger.valueOf(255), new BigInteger(1, ff));

        // 양수 255를 내보내면 부호 바이트 0x00이 붙는다
        assertArrayEquals(new byte[] { 0x00, (byte) 0xFF }, BigInteger.valueOf(255).toByteArray());
        // 고정 길이 변환은 부호 바이트를 떼고, 모자란 앞자리는 0으로 채운다
        assertArrayEquals(new byte[] { (byte) 0xFF }, HexCodec.toFixedBytes(BigInteger.valueOf(255), 1));
        assertArrayEquals(new byte[] { 0, 0, 0, 10 }, HexCodec.toFixedBytes(BigInteger.TEN, 4));
    }

    @Test
    @DisplayName("고정 길이에 들어가지 않는 값은 거부한다")
    void fixedBytesRejectsOverflow() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                HexCodec.toFixedBytes(BigInteger.valueOf(256), 1);
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                HexCodec.toFixedHex(BigInteger.valueOf(-1), 1);
            }
        });
    }

    @Test
    @DisplayName("remainder는 음수가 나올 수 있고 mod는 항상 0 이상")
    void remainderVsMod() {
        BigInteger three = BigInteger.valueOf(3);

        assertEquals(BigInteger.valueOf(-1), BigInteger.valueOf(-7).remainder(three));
        assertEquals(BigInteger.valueOf(2), BigInteger.valueOf(-7).mod(three));
        assertThrows(ArithmeticException.class, new Executable() {
            @Override
            public void execute() {
                BigInteger.TEN.mod(BigInteger.ZERO);
            }
        });
    }

    @Test
    @DisplayName("샤드 번호는 항상 [0, n)")
    void shardRange() {
        ShardRouter router = new ShardRouter(16);

        assertEquals(15, router.shardOfHash(new byte[] { (byte) 0xFF, (byte) 0xFF }));
        assertEquals(9, router.shardOf(BigInteger.valueOf(-7)));
        assertEquals(15, router.shardOf(new BigInteger("18446744073709551615")));
        // 같은 비트를 signed long으로 읽으면 -1 → unsigned 연산으로 같은 답을 얻는다
        assertEquals(15L, Long.remainderUnsigned(-1L, 16L));
    }

    @Test
    @DisplayName("불변: 연산은 새 객체를 반환하고, equals는 값만 비교한다")
    void immutabilityAndEquality() {
        BigInteger sum = BigInteger.ZERO;
        BigInteger returned = sum.add(BigInteger.TEN);

        assertEquals(BigInteger.ZERO, sum);
        assertEquals(BigInteger.TEN, returned);
        assertEquals(new BigInteger("100"), BigInteger.valueOf(100));   // scale 개념이 없어 equals 안전
        assertThat(new BigInteger("100")).isEqualByComparingTo(BigInteger.valueOf(100));
    }

    @Test
    @DisplayName("valueOf는 -16~16만 캐시한다 — == 비교 금지")
    void valueOfCache() {
        assertSame(BigInteger.valueOf(10), BigInteger.valueOf(10));
        assertNotSame(BigInteger.valueOf(100), BigInteger.valueOf(100));
    }

    @Test
    @DisplayName("외부 입력은 길이와 bitLength로 제한한다")
    void safeParsing() {
        final SafeBigIntegerParser parser = new SafeBigIntegerParser(20, 64);

        assertEquals(new BigInteger("18446744073709551615"), parser.parse("18446744073709551615"));
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                parser.parse("123456789012345678901234567890");
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                parser.parse("99999999999999999999");
            }
        });
        assertThrows(NumberFormatException.class, new Executable() {
            @Override
            public void execute() {
                parser.parse("12a");
            }
        });
    }
}
```

> Java 8+ 라면 `new Executable() { ... }` 익명 클래스를 람다(`() -> Factorials.factorialExact(21)`)로 줄인다.
> `valueOfCache` 테스트는 OpenJDK 구현 세부(캐시 범위)에 의존한다. 스펙이 보장하는 동작이 아니므로 **"== 를 쓰면 안 되는 이유"를 보여주는 용도**로만 둔다.

---

## 8. 변형 실습

### 8.1 SHA-256 해시로 앞자리 0 손실을 직접 재현하면?

`MessageDigest.getInstance("SHA-256")`으로 `"key-0"` ~ `"key-999"`를 해시하고, `new BigInteger(1, digest).toString(16).length()`가 64가 아닌 경우를 세어보자. 첫 바이트가 `0x00`~`0x0F`일 확률이 1/16이므로 약 60개 안팎이 나와야 한다. 같은 입력을 `HexCodec.toHex`로 바꾸면 모두 64자인지 확인한다.

### 8.2 `ShardRouter`를 long 버전으로 바꾸면?

`shardOf(long id)`를 `(int) (id % 16)`으로 구현하고, `Math.floorMod(id, 16)`, `Long.remainderUnsigned(id, 16)`과 비교해보자.
- `id = -7`: `%`는 −7, `floorMod`는 9, `remainderUnsigned`는 (2^64 − 7) mod 16 = 9.
- `id = -1`(unsigned 2^64−1의 비트): 세 방식의 결과를 비교하고, **signed 값의 버킷**과 **unsigned 값의 버킷**이 언제 갈리는지 shardCount를 3으로 바꿔 확인해보자(2^64가 3으로 나눠떨어지지 않아 갈린다).

### 8.3 Jackson으로 큰 정수를 내보내면?

Spring Boot에서 `BigInteger id = new BigInteger("9007199254740993")`(2^53 + 1)를 응답에 담는다.
- 기본: JSON `9007199254740993` → 브라우저 콘솔에서 `JSON.parse`하면 `9007199254740992`.
- 필드에 `@JsonSerialize(using = ToStringSerializer.class)` → `"9007199254740993"` 문자열.
- React에서 `BigInt("9007199254740993") + 1n`으로 정확한 연산이 되는지 확인해보자.
