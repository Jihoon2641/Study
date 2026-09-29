# default 메서드 (default method)

## 한 줄 정의
인터페이스에 본문을 가진 인스턴스 메서드를 선언해서, 구현 클래스가 재정의하지 않으면 그 본문이 쓰이게 하는 기능. 가상 확장 메서드(virtual extension method)라고도 부른다.

## 도입 버전
- `(Java 8+)` 람다와 스트림을 기존 컬렉션 인터페이스에 붙이기 위해 도입되었다(`Collection.stream`, `Iterable.forEach`, `Collection.removeIf` 등).
- 인터페이스 `static` 메서드도 Java 8+, 인터페이스 `private` 메서드(default 메서드끼리 공통 코드를 나누는 용도)는 Java 9+.
- Java 7 레거시에는 없다. 대안은 골격 구현 추상 클래스나 정적 유틸리티 동반 클래스(`Collections`처럼)다. 골격 구현은 상속해야만 혜택을 받는다는 점이 다르다.

## 무엇을 하는가
default 메서드는 인터페이스 클래스 파일에 본문과 함께 들어간다. 구현 클래스가 재정의하지 않았으면 메서드 호출 시 JVM의 메서드 선택 과정에서 인터페이스의 본문이 골라진다. 선택 규칙은 다음과 같다.
1. 클래스가 이긴다. 클래스나 그 상위 클래스에 구현이 있으면 default보다 우선한다.
2. 더 구체적인 인터페이스가 이긴다. `B extends A`이고 둘 다 default가 있으면 `B`의 것이 쓰인다.
3. 그래도 모호하면 컴파일 에러다. 구현 클래스가 직접 재정의하고, 필요하면 `A.super.m()`으로 한쪽을 부른다.

규칙 1 때문에 `equals`/`hashCode`/`toString`을 default로 선언하면 `Object`의 구현이 항상 이겨서 절대 호출될 수 없다. 그래서 컴파일러가 막는다(`default method equals in interface X overrides a member of java.lang.Object`). 인터페이스는 인스턴스 필드도 가질 수 없으므로 default 메서드는 다른 추상 메서드만으로 동작을 구현해야 한다.

## 최소 사용 예
```java
public interface Shape {
    double area();

    /**
     * @implSpec 이 구현은 area() 를 두 번 비교한다.
     */
    default boolean isLargerThan(Shape other) {   // area() 만으로 구현할 수 있다
        return area() > other.area();
    }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **default 메서드 vs 추상 클래스의 구현 메서드** — 기준은 "상태와 `Object` 메서드가 필요한가"이다. 필요 없으면 default로 충분하고, 필요하면 골격 구현 추상 클래스가 있어야 한다.
- **기존 인터페이스에 default를 추가하면 하위 호환이다** — 컴파일은 깨지지 않지만 동작은 깨질 수 있다. 새 default는 기존 구현체의 불변식(예: 모든 메서드에 락을 건다)을 모른 채 모든 구현체에 끼어든다.
- **다이아몬드 문제가 생긴다** — 인터페이스에는 상태가 없고, 모호한 default는 컴파일 에러라서 C++식 다이아몬드 문제는 생기지 않는다.

## 등장하는 아이템
- [아이템 20. 추상 클래스보다는 인터페이스를 우선하라](../Item20/item-20-prefer-interfaces.md) — 5번 항목에서 default 메서드의 제약과, 그 제약 때문에 골격 구현이 여전히 필요한 이유를 다룬다.
