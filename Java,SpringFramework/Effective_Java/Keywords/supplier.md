# Supplier (`java.util.function.Supplier<T>`)와 팩터리 주입

## 한 줄 정의
인수를 받지 않고 값 하나를 돌려주는 함수형 인터페이스. 자원 자체 대신 "자원을 만드는 방법"을 넘길 때 쓴다.

## 도입 버전
(Java 8+ / 레거시 7 프로젝트에서는 사용 불가) 람다·메서드 참조와 함께 도입되었다.
- Java 7 이하에서는 같은 모양의 인터페이스를 직접 선언하고 익명 클래스로 구현한다. 동작은 같고 문법만 길다.
- 기본 타입용 변형 `IntSupplier`, `LongSupplier`, `DoubleSupplier`, `BooleanSupplier`도 Java 8+.

## 무엇을 하는가
선언은 메서드 하나뿐이다.

```java
@FunctionalInterface
public interface Supplier<T> { T get(); }
```

자원 **인스턴스**를 주입하면 받는 쪽은 평생 그 하나만 쓴다. 호출할 때마다 새 객체가 필요하거나, 만드는 시점을 받는 쪽이 정해야 하거나, 만드는 비용이 커서 실제로 쓸 때까지 미루고 싶다면 인스턴스 대신 `Supplier`를 넘긴다. 무엇을 만들지는 넘기는 쪽이 정하고, 언제 만들지는 받는 쪽이 정한다. 팩터리 메서드 패턴(factory method pattern)을 함수형 인터페이스 하나로 표현한 것이다.

컴파일러는 람다를 익명 클래스로 만들지 않는다. 람다 본문은 합성 메서드로 추출되고, 호출 지점에는 `invokedynamic` 명령이 놓여 `LambdaMetafactory`가 실행 시점에 구현체를 만들어 준다. 그래서 람다는 익명 클래스와 달리 클래스 파일을 따로 만들지 않는다.

JDK에서도 이 패턴을 쓴다. `Objects.requireNonNull(obj, Supplier<String>)`(Java 8+), `Optional.orElseGet(Supplier)`, `Optional.orElseThrow(Supplier)`, `Logger`의 지연 로깅이 모두 **필요할 때만 값을 만들기 위해** `Supplier`를 받는다.

## 최소 사용 예
```java
// Java 8+ : 자원 대신 자원 팩터리를 받는다
Mosaic create(Supplier<? extends Tile> tileFactory) {
    Tile t1 = tileFactory.get();
    Tile t2 = tileFactory.get();    // 호출할 때마다 새 타일
    ...
}
create(RedTile::new);                       // 메서드 참조
create(() -> new BlueTile(size));           // 람다

// Java 7 대안
public interface TileFactory { Tile create(); }
Mosaic create(TileFactory f) { ... }
create(new TileFactory() { public Tile create() { return new RedTile(); } });
```

## 자주 하는 오해 / 헷갈리는 짝
- **`Supplier<Tile>`이면 `Supplier<RedTile>`도 받는다** — 아니다. 제네릭은 불공변(invariant)이라 하위 타입 관계가 전달되지 않는다. `Supplier<? extends Tile>`로 선언해야 한다.
- **`Supplier` vs `Callable`** — 기준은 "검사 예외를 던지는가". `Supplier.get()`은 검사 예외를 던질 수 없고, `Callable.call()`은 `throws Exception`이다. 파일·네트워크처럼 검사 예외가 나는 작업에는 `Supplier`가 맞지 않는다.
- **`Supplier` 주입은 항상 지연 생성이다** — 지연될 뿐 캐시되지는 않는다. `get()`을 두 번 부르면 두 번 만들어진다. 한 번만 만들고 재사용하려면 받는 쪽이 직접 저장해야 한다.
- **람다는 익명 클래스의 문법 설탕이다** — 결과는 비슷하지만 구현이 다르다. 람다는 `invokedynamic` 기반이고 `this`가 바깥 인스턴스를 가리킨다(익명 클래스의 `this`는 자기 자신).

## 등장하는 아이템
- [아이템 5. 자원을 직접 명시하지 말고 의존 객체 주입을 사용하라](../Item5/item-5-dependency-injection.md) — 자원 인스턴스 대신 자원 팩터리를 주입하는 변형으로 등장한다.
