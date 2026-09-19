# 재귀적 타입 한정 (recursive type bound)과 셀프 타입 흉내 (simulated self-type)

## 한 줄 정의
타입 매개변수가 자기 자신을 포함하는 식으로 한정되는 것(`<T extends Comparable<T>>`). 이를 이용해 "하위 클래스 자신의 타입"을 흉내 내는 관용구가 simulated self-type이다.

## 도입 버전
(Java 5+) 제네릭과 함께 도입. Java 7 레거시에서도 그대로 쓸 수 있다.

## 무엇을 하는가
`<T extends Comparable<T>>`는 "자기 자신과 비교할 수 있는 타입 `T`"라는 뜻이다. 타입 매개변수 `T`가 자신의 한정(bound) 안에 다시 등장해서 "재귀적"이라고 부른다. JDK에서는 `Collections.max`의 시그니처, 그리고 `Enum<E extends Enum<E>>` 선언에서 볼 수 있다.

**셀프 타입 흉내**는 이 구조를 상속 계층에 쓰는 기법이다. 자바에는 "이 메서드를 호출한 실제 클래스의 타입"을 뜻하는 셀프 타입이 없다. 그래서 상위 클래스 메서드가 `return this;`를 하면 반환 타입이 상위 클래스로 고정되고, 메서드 연쇄 중에 하위 클래스 전용 메서드를 부를 수 없게 된다.

해결책은 하위 클래스가 **자기 타입을 타입 인수로 넘기는 것**이다.
1. `abstract class Builder<T extends Builder<T>>` — `T`는 Builder의 어떤 하위 타입
2. 상위 메서드는 `T`를 반환하고, 반환값은 `self()`로 얻는다
3. 하위 클래스는 `class NyBuilder extends Builder<NyBuilder>`로 선언하고 `self()`를 `return this;`로 구현한다

`self()` 대신 `return (T) this;`로 캐스팅하면 컴파일러는 `T`가 정말 `this`의 타입인지 확인할 수 없어 비검사(unchecked) 경고를 낸다. 제네릭은 소거(erasure)되어 런타임에 `T`가 `Builder`로 바뀌므로 이 캐스트는 실제로 아무것도 검사하지 않고, 누군가 `extends Builder<OtherBuilder>`처럼 잘못 선언하면 호출 지점에서 `ClassCastException`이 난다. `self()`는 하위 클래스가 올바른 타입을 직접 반환하므로 경고도 위험도 없다.

## 최소 사용 예
```java
abstract static class Builder<T extends Builder<T>> {
    String name;
    T name(String n) { name = n; return self(); }  // 반환 타입이 하위 빌더로 유지된다
    protected abstract T self();
}
static final class DogBuilder extends Builder<DogBuilder> {
    boolean barks;
    DogBuilder barks(boolean b) { barks = b; return this; }
    @Override protected DogBuilder self() { return this; }
}
new DogBuilder().name("choco").barks(true);   // name() 뒤에도 barks() 호출 가능
```

## 자주 하는 오해 / 헷갈리는 짝
- **`T`가 항상 자기 자신이라는 게 보장된다** — 아니다. 판단 기준은 "컴파일러가 막는가". `class A extends Builder<A>`와 `class B extends Builder<A>`는 둘 다 컴파일된다. 규약은 사람이 지켜야 하고, `self()`는 잘못 선언해도 캐스팅 사고로 번지지 않게 해 줄 뿐이다.
- **재귀적 타입 한정 vs 와일드카드 `<T extends Comparable<? super T>>`** — 기준은 "상위 타입이 비교 기능을 구현했는가". 상위 클래스가 `Comparable`을 구현하고 하위 클래스는 물려받기만 했다면 `? super T`가 있어야 받아들여진다.

## 등장하는 아이템
- [아이템 2. 생성자에 매개변수가 많다면 빌더를 고려하라](../Item2/item-2-builder.md) — `Pizza.Builder<T extends Builder<T>>`와 `self()`로 계층형 빌더의 메서드 연쇄를 가능하게 한다.
- 아이템 30. 이왕이면 제네릭 메서드로 만들라 — 재귀적 타입 한정 자체(`<E extends Comparable<E>>`)를 다룬다. (설명 파일 아직 없음)
