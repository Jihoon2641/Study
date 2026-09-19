# 박싱 캐시 (autoboxing cache, `Integer.valueOf` 캐시)

## 한 줄 정의
작은 범위의 기본 타입 값을 박싱할 때 매번 새 객체를 만들지 않고 미리 만들어 둔 래퍼 객체를 돌려주는 JDK의 캐시.

## 도입 버전
- `Boolean.valueOf(boolean)` — Java 1.4+ (`TRUE`/`FALSE` 상수 반환)
- `Integer.valueOf(int)` 등 캐시하는 `valueOf`와 오토박싱(autoboxing) — Java 5+
- 래퍼 생성자 `new Integer(int)` 등 — Java 9에서 deprecated, Java 16(JEP 390)에서 `forRemoval = true`

## 무엇을 하는가
`Integer x = 100;`처럼 오토박싱을 쓰면 javac는 이를 `Integer.valueOf(100)` 호출로 컴파일한다. 즉 오토박싱은 정적 팩터리 호출이고, `valueOf` 안에서 캐시를 확인한다.

보장 범위는 JLS 5.1.7이 정한다. 박싱되는 값이 `true`/`false`, `byte`, 코드 포인트 0~127(ASCII) 범위의 `char`, `-128`~`127` 범위의 `short`/`int`이면 **같은 값의 박싱 결과는 항상 `==`로 같다.** 그 밖의 값은 같을 수도 다를 수도 있다(명세상 미정).

OpenJDK 구현 세부:
- `Integer`는 내부 `IntegerCache` 배열을 쓰며, 상한은 JVM 옵션 `-XX:AutoBoxCacheMax=<n>`으로 올릴 수 있다. 하한 `-128`은 고정이다.
- `Long`, `Short`, `Byte`도 `-128`~`127`을, `Character`는 `0`~`127`을 캐시한다(`Long.valueOf`는 Javadoc상 캐시 의무가 없지만 구현은 캐시한다).
- `Float`, `Double`의 `valueOf`는 캐시하지 않는다.

## 최소 사용 예
```java
Integer a = 127, b = 127;
Integer c = 128, d = 128;
System.out.println(a == b);       // true  — JLS가 보장
System.out.println(c == d);       // false — 기본 설정 기준, 보장 밖
System.out.println(c.equals(d));  // true  — 값 비교는 항상 equals
```

## 자주 하는 오해 / 헷갈리는 짝
- **`Integer`끼리 `==`가 되던데요?** — 테스트 값이 우연히 캐시 범위 안이었을 뿐이다. 판단 기준은 하나, 박싱 타입끼리의 값 비교는 무조건 `equals`(또는 한쪽을 기본 타입으로 언박싱)를 쓴다.
- **`Integer` 한쪽이 `int`이면?** — `int == Integer`는 박싱 타입이 언박싱되어 값으로 비교되므로 캐시와 무관하게 맞다. 대신 `Integer`가 `null`이면 언박싱에서 `NullPointerException`이 난다.
- **캐시가 있으니 박싱 비용은 없다** — 범위 밖 값은 매번 할당된다. 반복문 안의 `Long sum += i` 같은 코드는 수백만 개의 객체를 만든다.

## 등장하는 아이템
- [아이템 1. 생성자 대신 정적 팩터리 메서드를 고려하라](../Item1/item-1-static-factory-method.md) — `valueOf`가 매번 새 객체를 만들지 않는 정적 팩터리의 실제 JDK 사례이자, 캐시를 식별성으로 믿으면 깨지는 함정의 예로 등장한다.
