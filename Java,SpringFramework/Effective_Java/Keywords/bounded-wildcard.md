# 한정적 와일드카드 (bounded wildcard, `? extends T` / `? super T`)

## 한 줄 정의
제네릭 타입 인수 자리에 "정확히 이 타입"이 아니라 "이 타입의 하위 타입 아무거나"(`? extends T`) 또는 "상위 타입 아무거나"(`? super T`)를 받도록 여는 문법.

## 도입 버전
(Java 5+) 제네릭과 함께 도입되었다. Java 7 레거시에서도 그대로 쓸 수 있다.
- 다이아몬드 연산자(`new ArrayList<>()`)와 함께 쓸 때의 추론 규칙 — Java 7+
- 제네릭 타입 추론 개선(타깃 타입 추론) — Java 8+

## 무엇을 하는가
출발점은 **제네릭의 불공변성(invariance)** 이다. `RedTile`이 `Tile`의 하위 타입이어도 `List<RedTile>`은 `List<Tile>`의 하위 타입이 **아니다.** 이 규칙이 없으면 다음이 허용되어 타입 안전성이 깨진다.

```java
List<Tile> list = redTileList;   // 만약 허용된다면
list.add(new BlueTile());        // RedTile 리스트에 BlueTile 이 들어간다
```

배열은 반대로 공변(covariant)이라 `Object[] a = new String[1];`이 컴파일되고, 그래서 `a[0] = 1;`이 런타임에 `ArrayStoreException`을 던진다. 제네릭은 그 실수를 컴파일 시점으로 옮긴 것이다.

와일드카드는 이 엄격함을 필요한 방향으로만 푼다.

- `? extends T` — **읽기(생산) 전용.** 원소를 `T`로 꺼내 쓸 수 있지만 넣을 수는 없다(`null` 제외). 실제 타입 인수가 무엇인지 모르므로 무엇을 넣어도 안전하지 않기 때문이다.
- `? super T` — **쓰기(소비) 전용.** `T`나 그 하위 타입을 넣을 수 있지만, 꺼내면 `Object`로만 받을 수 있다.

외우는 규칙이 PECS다. **producer-extends, consumer-super** — 매개변수가 값을 생산해 주면 `extends`, 내 값을 받아 소비하면 `super`를 쓴다. 반환 타입에는 와일드카드를 쓰지 않는다. 쓰면 호출하는 쪽 코드에도 와일드카드가 번진다.

제네릭 타입 정보는 컴파일 후 소거(erasure)되므로, 와일드카드는 클래스 파일에 남지 않고 컴파일 시점 검사에만 쓰인다.

## 최소 사용 예
```java
// 생산자 — Tile 을 공급해 주므로 extends
Mosaic create(Supplier<? extends Tile> tileFactory) { Tile t = tileFactory.get(); ... }
create(RedTile::new);                    // Supplier<RedTile> 도 받는다 (Java 8+)

// 소비자 — 내 원소를 받아 가므로 super
void pushAll(Collection<? super E> dst, Collection<? extends E> src) {
    for (E e : src) dst.add(e);
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **`List<?>`와 `List<Object>`는 같다** — 다르다. `List<Object>`에는 아무 객체나 넣을 수 있지만, `List<?>`에는 `null` 외에 아무것도 넣을 수 없다. 대신 `List<?>`는 어떤 `List`든 받는다.
- **`? extends T`와 `T extends U`(타입 한정)** — 기준은 "선언인가 사용인가". `<T extends Comparable<T>>`는 타입 매개변수를 선언하며 제약을 거는 것이고, `? extends T`는 사용 지점에서 타입 인수 자리를 여는 것이다.
- **와일드카드를 쓰면 더 유연하니 항상 쓴다** — 클래스 사용자가 와일드카드를 신경 써야 한다면 API 설계가 잘못된 것이다. 특히 반환 타입에는 쓰지 않는다.

## 등장하는 아이템
- [아이템 5. 자원을 직접 명시하지 말고 의존 객체 주입을 사용하라](../Item5/item-5-dependency-injection.md) — 팩터리를 주입할 때 `Supplier<? extends Tile>`로 선언해야 하위 타입 팩터리를 받을 수 있는 이유로 등장한다.
- 아이템 31. 한정적 와일드카드를 사용해 API 유연성을 높이라 — PECS 규칙을 정면으로 다룬다. (설명 파일 아직 없음)
