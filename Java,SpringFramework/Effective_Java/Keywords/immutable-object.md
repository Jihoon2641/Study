# 불변 객체 (immutable object)

## 한 줄 정의
생성된 뒤에는 외부에서 관찰할 수 있는 상태가 절대 바뀌지 않는 객체. 그런 객체를 만드는 클래스를 불변 클래스(immutable class)라 한다.

## 도입 버전
- 설계 개념이라 모든 버전에 해당한다. `String`, `Integer` 같은 박싱 타입, `BigInteger`/`BigDecimal`은 Java 1.0~1.1부터 불변이다.
- `final` 필드의 스레드 공개 보장: Java 5+(JSR-133 메모리 모델)
- `(Java 8+)` `java.time`의 모든 날짜·시간 타입이 불변이다. Java 7의 `Date`, `Calendar`는 가변이다.
- `(Java 9+)` `List.of`/`Set.of`/`Map.of`, `(Java 10+)` `List.copyOf` 등 불변 컬렉션
- `(Java 16+)` 레코드: 얕은 불변 클래스를 한 줄로 선언

## 무엇을 하는가
불변 클래스는 다섯 가지 규칙으로 만든다. 변경자 메서드를 두지 않고, 확장을 막고, 모든 필드를 `private final`로 하고, 가변 컴포넌트는 방어적으로 복사해서 외부와 공유하지 않는다.

이 규칙을 모두 지키면 상태를 바꿀 경로가 없으므로 다음이 공짜로 따라온다.
- 스레드 안전: 동기화할 대상이 없다. `final` 필드 덕분에 `volatile` 없이 공개해도 완성된 상태가 보인다.
- 자유로운 공유: 상수로 두거나 캐싱해서 재사용할 수 있다.
- 해시 기반 컬렉션의 키로 안전: `hashCode`가 바뀌지 않으므로 넣은 원소를 항상 찾을 수 있다.
- 실패 원자성: 연산이 실패해도 원래 객체는 그대로다.

비용은 값이 다를 때마다 새 객체를 만들어야 한다는 것이다. 다단계 연산은 `StringBuilder` 같은 가변 동반 클래스로 처리한다.

## 최소 사용 예
```java
public final class Money {
    private final long amount;
    private final String currency;

    public Money(long amount, String currency) {
        this.amount = amount;
        this.currency = Objects.requireNonNull(currency);
    }
    public Money plus(Money m) {                  // 자신을 바꾸지 않고 새 객체를 반환
        if (!currency.equals(m.currency)) throw new IllegalArgumentException("통화 불일치");
        return new Money(amount + m.amount, currency);
    }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **불변 vs 수정 불가 뷰(unmodifiable view)** — 기준은 "원본을 통해 바뀔 수 있는가"이다. `Collections.unmodifiableList(list)`는 수정 메서드만 막은 뷰라서 원본 `list`가 바뀌면 같이 바뀐다. `List.copyOf`는 복사해서 만든 진짜 불변 리스트다.
- **얕은 불변 vs 깊은 불변** — `final` 필드가 가변 객체(`List`, `Date`, 배열)를 가리키면 필드는 고정이지만 내용은 바뀐다. 레코드와 Lombok `@Value`는 얕은 불변만 보장한다. 깊은 불변은 방어적 복사로 직접 만들어야 한다.
- **`final` 변수 = 불변 객체** — 아니다. `final List<String> xs`는 `xs`가 다른 리스트를 가리키지 못할 뿐, `xs.add(...)`는 된다.

## 등장하는 아이템
- [아이템 17. 변경 가능성을 최소화하라](../Item17/item-17-minimize-mutability.md) — 이 아이템의 주제 자체. 다섯 규칙, 장점, 단점, 설계 방법을 다룬다.
