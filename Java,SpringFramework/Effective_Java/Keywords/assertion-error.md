# AssertionError (`java.lang.AssertionError`)

## 한 줄 정의
"절대 일어나서는 안 되는 일이 일어났다"를 뜻하는 `Error`의 하위 클래스. 프로그램 자체의 결함을 알리는 데 쓴다.

## 도입 버전
- `AssertionError`와 `assert` 문 — Java 1.4+
- 원인(cause)을 받는 생성자 `AssertionError(String, Throwable)` — Java 7+

Java 7 레거시에서도 그대로 쓸 수 있다.

## 무엇을 하는가
자바의 던질 수 있는 타입은 `Throwable` 아래 `Error`와 `Exception`으로 나뉜다. `Error`는 "정상적인 애플리케이션이 잡으려 해서는 안 되는 심각한 문제"를 뜻하고(`OutOfMemoryError`, `StackOverflowError` 등), `AssertionError`는 그중 **프로그래머의 가정이 깨졌음**을 알리는 것이다.

`Error`의 하위 타입이므로 얻는 성질이 세 가지다.
- 검사 예외(checked exception)가 아니라 `throws` 선언이 필요 없다.
- `catch (Exception e)`로 감싸 둔 상위 코드에 조용히 먹히지 않는다.
- 잡아서 복구하라는 뜻이 아니라, 고쳐야 할 버그라는 뜻으로 읽힌다.

가장 자주 헷갈리는 지점은 `assert` 문과의 관계다. `assert cond : msg;`는 컴파일 시 조건 검사 + `AssertionError` 던지기로 번역되지만, 그 전체가 **어서션 활성화 플래그로 감싸진다.** 그래서 JVM 옵션 `-ea` 없이 실행하면 `assert` 문은 아무 일도 하지 않는다(기본값은 비활성화). 반면 `throw new AssertionError()`는 그냥 `throw` 문이라 **플래그와 무관하게 항상 실행된다.** 유틸리티 클래스의 `private` 생성자나 도달 불가능한 `switch`의 `default` 절에서 직접 `throw`를 쓰는 이유가 이것이다.

## 최소 사용 예
```java
// (1) 도달할 수 없는 생성자 — -da 로 실행해도 던진다
private MathUtils() { throw new AssertionError("인스턴스화 금지"); }

// (2) 도달할 수 없는 분기
switch (op) {
    case PLUS:  return x + y;
    case MINUS: return x - y;
    default:    throw new AssertionError("알 수 없는 연산: " + op);
}

// (3) 일어날 수 없는 검사 예외를 감쌀 때 (Java 7+ : 원인 보존)
try {
    return MessageDigest.getInstance("SHA-256");
} catch (NoSuchAlgorithmException e) {
    throw new AssertionError("SHA-256 은 모든 JVM 이 지원한다", e);
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **`AssertionError`는 `-ea` 없이는 안 던져진다** — `assert` 문에만 해당한다. 직접 `throw`하는 것은 항상 던져진다.
- **`AssertionError` vs `IllegalStateException` / `IllegalArgumentException`** — 기준은 "누구의 잘못인가". 호출자가 잘못된 인자를 줬으면 `IllegalArgumentException`, 객체 상태가 맞지 않으면 `IllegalStateException`, **어느 쪽도 아니고 애초에 도달할 수 없어야 하는 코드**라면 `AssertionError`다.
- **`UnsupportedOperationException`을 써도 된다** — 틀린 코드는 아니고 Lombok의 `@UtilityClass`도 이 예외를 쓴다. 다만 이 예외는 "이 구현은 이 연산을 지원하지 않는다"(예: 불변 컬렉션의 `add`)는 정상적인 계약 표현이라, "버그"라는 의미는 `AssertionError`가 더 정확하다.

## 등장하는 아이템
- [아이템 4. 인스턴스화를 막으려거든 private 생성자를 사용하라](../Item4/item-4-noninstantiable-utility-class.md) — 클래스 안에서 실수로 `private` 생성자를 호출하는 것까지 막는 수단으로 등장한다.
