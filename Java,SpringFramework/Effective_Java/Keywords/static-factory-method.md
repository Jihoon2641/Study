# 정적 팩터리 메서드 (static factory method)

## 한 줄 정의
클래스의 인스턴스(또는 그 하위 타입 인스턴스)를 반환하는 `static` 메서드. 생성자와 달리 이름을 갖고, 무엇을 반환할지 본문에서 결정한다.

## 도입 버전
(모든 버전) 언어 기능이 아니라 설계 기법이다. 다만 놓을 수 있는 위치는 버전에 따라 넓어졌다.
- 클래스의 static 메서드 — Java 1.0+
- 인터페이스의 `static` 메서드 — Java 8+
- 인터페이스의 `private static` 메서드(팩터리 공통 로직 숨기기) — Java 9+
- Java 7 이하에서 인터페이스용 팩터리가 필요하면 `Collections` 같은 동반 클래스(companion class)에 둔다.

## 무엇을 하는가
호출자에게는 `new`와 똑같이 "객체를 받는 통로"로 보이지만, 클래스 쪽에서는 그 통로 뒤에서 캐시된 객체를 돌려주거나, 인터페이스 뒤에 숨긴 구현을 고르거나, 입력에 따라 다른 클래스를 내줄 수 있다.

원리는 바이트코드에 있다. `new X()`는 `new`(할당) → `dup` → `invokespecial <init>`(초기화)로 번역되며, `<init>`은 반환 타입이 void라 이미 할당된 객체 말고는 돌려줄 수 없다. 정적 팩터리는 `invokestatic` 호출인 평범한 메서드라서 선언된 반환 타입과 호환되는 어떤 참조든 `return`할 수 있다. 짧은 static 메서드는 JIT이 인라인하기 쉬워 호출 비용도 사실상 없다.

## 이름 관례
- `from` — 매개변수 하나를 받아 변환: `Date.from(instant)`
- `of` — 여러 매개변수를 모아 생성: `EnumSet.of(A, B)`, `List.of(1, 2)`
- `valueOf` — `from`/`of`의 자세한 버전: `Boolean.valueOf(true)`
- `instance` / `getInstance` — 같은 인스턴스일 수도 있음: `Calendar.getInstance()`
- `create` / `newInstance` — 매번 새 인스턴스를 보장: `Array.newInstance(...)`
- `getType` / `newType` — 다른 클래스에 둘 때: `Files.getFileStore(path)`, `Files.newBufferedReader(path)`
- `type` — 위의 간결판: `Collections.list(enumeration)`

`getInstance`와 `newInstance`의 차이(캐시 가능 vs 새 객체)는 호출자가 식별성을 기대해도 되는지에 대한 신호이므로 섞어 쓰지 않는다.

## 최소 사용 예
```java
public final class Temperature {
    private final double celsius;
    private Temperature(double celsius) { this.celsius = celsius; }

    // 시그니처는 둘 다 (double) 이지만 이름으로 의미를 가른다 — 생성자로는 불가능
    public static Temperature ofCelsius(double c)    { return new Temperature(c); }
    public static Temperature ofFahrenheit(double f) { return new Temperature((f - 32) * 5 / 9); }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **팩터리 메서드 패턴(GoF)과 같은 것이다** — 아니다. 판단 기준은 "상속이 필요한가". GoF 패턴은 하위 클래스가 생성 대상을 결정하도록 오버라이드하는 인스턴스 메서드 구조이고, 정적 팩터리는 오버라이드가 불가능한 static 메서드 하나다.
- **정적 팩터리는 항상 캐시한다** — 아니다. 캐시는 할 "수 있는" 선택지일 뿐이다. 문서가 인스턴스 통제를 약속하지 않았다면 `==`로 비교하지 말고 `equals`를 쓴다.
- **정적 팩터리만 있으면 상속이 막힌다** — `public`/`protected` 생성자가 하나도 없을 때만 그렇다. 생성자를 `protected`로 두고 팩터리를 공식 진입점으로 삼는 절충도 가능하다(JPA 엔티티에서 흔함).

## 등장하는 아이템
- [아이템 1. 생성자 대신 정적 팩터리 메서드를 고려하라](../Item1/item-1-static-factory-method.md) — 이 아이템의 주제 자체. 장단점과 이름 관례를 다룬다.
- [아이템 2. 생성자에 매개변수가 많다면 빌더를 고려하라](../Item2/item-2-builder.md) — 생성자와 함께 "선택 매개변수가 많으면 대응하기 어렵다"는 공통 한계를 가진 쪽으로 등장한다. 빌더 진입점을 `builder()` 정적 팩터리로 여는 형태로도 함께 쓰인다.
