# 공변 반환 타이핑 (covariant return typing)

## 한 줄 정의
하위 클래스가 메서드를 재정의할 때, 반환 타입을 상위 메서드 반환 타입의 하위 타입으로 좁혀 선언할 수 있게 하는 규칙.

## 도입 버전
(Java 5+) 그 전(Java 1.4 이하)에는 재정의 메서드의 반환 타입이 정확히 같아야 해서 호출자가 캐스팅해야 했다. Java 7 레거시에서도 쓸 수 있다.

## 무엇을 하는가
상위 `Pizza.Builder`에 `abstract Pizza build();`가 있을 때, 하위 `NyPizza.Builder`는 `NyPizza build()`로 재정의할 수 있다. 호출자는 `NyPizza.Builder`로 호출하면 캐스팅 없이 `NyPizza`를 받는다.

규칙은 JLS 8.4.5의 "반환 타입 대체 가능(return-type-substitutable)"과 8.4.8.3이 정한다. 참조 타입 반환이라면 하위 메서드의 반환 타입이 상위 반환 타입의 하위 타입이면 된다. 기본 타입(`int` 등)과 `void`는 정확히 같아야 한다. 이게 안전한 이유는 리스코프 치환 원칙이다. `Pizza`를 기대하는 호출자에게 `NyPizza`를 돌려줘도 문제가 없다.

바이트코드 쪽에는 한 가지 문제가 있다. JVM은 메서드를 **이름 + 매개변수 타입 + 반환 타입**으로 이루어진 디스크립터로 구분한다. 그래서 `build()Pizza`와 `build()NyPizza`는 JVM이 보기에 서로 다른 메서드이고, 상위 타입 참조로 `build()`를 부르면 재정의된 메서드를 찾지 못한다. javac는 이를 해결하려고 하위 클래스에 **브리지 메서드(bridge method)** 를 몰래 추가한다.

```
// javac가 NyPizza.Builder 안에 자동 생성 (ACC_BRIDGE | ACC_SYNTHETIC 플래그)
Pizza build() { return this.build(); }   // 실제로는 NyPizza build() 를 호출
```

`javap -p`로 클래스 파일을 열어 보면 같은 이름의 `build` 메서드가 두 개 보인다. 제네릭 소거 때문에 생기는 브리지 메서드와 같은 메커니즘이다.

## 최소 사용 예
```java
class Animal {
    Animal copy() { return new Animal(); }
}
class Dog extends Animal {
    @Override Dog copy() { return new Dog(); }   // 반환 타입을 Dog 로 좁힘
}
Dog d = new Dog().copy();          // 캐스팅 불필요
Animal a = new Dog();
Animal c = a.copy();               // 브리지 메서드를 거쳐 Dog.copy() 실행
```

## 자주 하는 오해 / 헷갈리는 짝
- **매개변수 타입도 좁힐 수 있다** — 아니다. 기준은 "반환인가 매개변수인가". 매개변수 타입을 바꾸면 재정의가 아니라 **오버로딩**이 되어 상위 타입 참조로 호출할 때 선택되지 않는다. `@Override`를 붙이면 이 실수가 컴파일 에러로 잡힌다.
- **공변 반환 vs 셀프 타입 흉내** — 기준은 "모든 하위 클래스가 직접 재정의하는가". `build()`처럼 하위 클래스마다 어차피 구현하는 메서드는 공변 반환으로 충분하다. `addTopping()`처럼 상위에서 한 번 구현하고 반환 타입만 하위 타입이어야 하면 재귀적 타입 한정과 `self()`가 필요하다.

## 등장하는 아이템
- [아이템 2. 생성자에 매개변수가 많다면 빌더를 고려하라](../Item2/item-2-builder.md) — 하위 빌더의 `build()`가 `Pizza` 대신 `NyPizza`/`Calzone`을 반환해 호출자가 형변환하지 않아도 되게 한다.
- 아이템 13. clone 재정의는 주의해서 진행하라 — `clone()`이 `Object` 대신 자기 타입을 반환하도록 재정의할 때 쓰인다. (설명 파일 아직 없음)
