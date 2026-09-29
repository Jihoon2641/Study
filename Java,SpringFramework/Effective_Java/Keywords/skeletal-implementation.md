# 골격 구현 (skeletal implementation)

## 한 줄 정의
인터페이스와 짝을 이뤄 그 인터페이스의 대부분을 미리 구현해 두는 추상 클래스. 구현체는 이 클래스를 상속하고 기반 메서드 몇 개만 채우면 된다. 관례상 이름은 `AbstractInterface`로 짓는다.

## 도입 버전
- 설계 기법이라 버전과 무관하다. JDK의 대표 사례인 `AbstractCollection`, `AbstractSet`, `AbstractList`, `AbstractMap`은 Java 1.2+, `AbstractMap.SimpleEntry`(단순 구현)는 Java 6+. 모두 Java 7 레거시에서 그대로 쓸 수 있다.
- `(Java 8+)` default 메서드가 생기면서, 상태 없이 기반 메서드만으로 구현할 수 있는 부분은 인터페이스로 옮길 수 있게 되었다. `equals`/`hashCode`/`toString`과 인스턴스 필드는 여전히 골격 구현의 몫이다.

## 무엇을 하는가
역할이 둘로 나뉜다. 인터페이스는 타입을 정의한다. 골격 구현은 구현을 돕는다. 공개 API의 매개변수·반환 타입은 항상 인터페이스로 쓰고, 골격 구현은 구현하는 쪽만 안다.

작성 절차는 이렇다.
1. 인터페이스에서 다른 메서드의 구현에 쓰이는 기반 메서드를 고른다. `List`라면 `get`과 `size`다.
2. 기반 메서드만으로 구현할 수 있는 메서드는 인터페이스의 default 메서드로 준다(Java 8+).
3. `Object` 메서드(`equals`/`hashCode`/`toString`)와 상태가 필요한 메서드는 골격 구현 클래스에 넣는다. 기반 메서드는 골격 구현에서 추상 메서드로 남는다.
4. 골격 구현은 상속용 클래스이므로 자기사용을 `@implSpec`으로 문서화한다.

골격 구현을 상속할 수 없는 클래스(이미 다른 클래스를 상속한 경우)는 인터페이스를 직접 구현하거나, 골격 구현을 확장한 `private` 내부 클래스에 호출을 전달한다. 후자를 시뮬레이트한 다중 상속(simulated multiple inheritance)이라고 부른다.

## 최소 사용 예
```java
// get 과 size 만 구현하면 contains, indexOf, iterator, equals, hashCode, toString 이 따라온다
static List<Integer> intArrayAsList(final int[] a) {
    return new AbstractList<Integer>() {        // Java 9+ 는 new AbstractList<>() 도 가능
        @Override public Integer get(int i) {
            return a[i];
        }

        @Override public int size() {
            return a.length;
        }
    };
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **골격 구현 vs 그냥 추상 클래스** — 기준은 "타입이 인터페이스로 따로 있는가"이다. 인터페이스 없이 추상 클래스만 있으면 그 추상 클래스가 곧 타입이라 단일 상속 제약을 그대로 받는다. 골격 구현은 인터페이스가 타입을 맡으므로 상속하지 않아도 그 타입이 될 수 있다.
- **default 메서드가 생겼으니 골격 구현은 필요 없다** — `Object` 메서드와 상태는 인터페이스에 둘 수 없다. Java 21 JDK에도 `AbstractList`가 그대로 있는 이유다.

## 등장하는 아이템
- [아이템 20. 추상 클래스보다는 인터페이스를 우선하라](../Item20/item-20-prefer-interfaces.md) — 6번 항목의 주제다. `AbstractMapEntry`가 `Map.Entry`의 `equals`/`hashCode` 명세를 한 곳에서 지키는 예를 다룬다.
