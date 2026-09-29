# 동적 디스패치 (dynamic dispatch)

## 한 줄 정의
인스턴스 메서드 호출에서 실제로 실행할 메서드를 컴파일 타입이 아니라 런타임에 수신 객체의 실제 클래스를 보고 고르는 방식. 자바에서 재정의(override)가 동작하는 원리다.

## 도입 버전
- Java 1.0+. 버전별 변화는 거의 없다.
- `(Java 11+)` nestmate 도입(JEP 181)으로 같은 중첩 그룹 안의 `private` 메서드 호출도 `invokevirtual`/`invokeinterface`로 컴파일될 수 있게 되었지만, `private` 메서드는 재정의되지 않으므로 선택 결과는 같다.

## 무엇을 하는가
javac는 호출 형태에 따라 다른 바이트코드를 낸다.

| 호출 | 바이트코드 | 디스패치 |
|---|---|---|
| `obj.m()`, `this.m()` (클래스 타입) | `invokevirtual` | 동적 — 수신 객체의 런타임 클래스부터 찾는다 |
| `obj.m()` (인터페이스 타입) | `invokeinterface` | 동적 |
| `super.m()`, 생성자, (Java 10 이하의) `private` 메서드 | `invokespecial` | 정적 — 지정된 클래스의 구현을 직접 부른다 |
| `Cls.m()` | `invokestatic` | 정적 — 재정의 개념이 없다 |

`invokevirtual`의 선택 규칙(JLS 15.12.4.4)은 "수신 객체의 실제 클래스에서 시작해 상위 클래스로 올라가며 시그니처가 맞는 첫 메서드를 고른다"이다. HotSpot은 이를 클래스마다 가상 메서드 테이블(vtable)로 구현해 인덱스 한 번 조회로 끝내고, 호출 지점에서 실제로 한 가지 타입만 관찰되면 JIT가 인라인까지 한다.

중요한 점은 `super.m()`이 정적으로 부르는 것은 그 한 번의 호출뿐이라는 것이다. 상위 구현 `m()` 안에서 `this.n()`을 부르면 `this`는 여전히 하위 클래스 인스턴스이므로 다시 동적 디스패치가 일어나 하위 클래스의 `n()`이 실행된다.

## 최소 사용 예
```java
class Parent {
    void greet() { System.out.println("부모 greet → " + name()); }   // this.name() — invokevirtual
    String name() { return "부모"; }
}
class Child extends Parent {
    @Override void greet() { super.greet(); }                         // invokespecial Parent.greet
    @Override String name() { return "자식"; }
}
new Child().greet();   // 출력: 부모 greet → 자식
```

## 자주 하는 오해 / 헷갈리는 짝
- **동적 디스패치 vs 오버로딩 해석** — 기준은 "무엇을 보고 고르는가"이다. 재정의된 메서드 중 무엇을 실행할지는 런타임 수신 객체로 고르고(동적), 오버로딩된 메서드 중 어느 시그니처를 부를지는 컴파일 타임에 인자의 정적 타입으로 고른다(정적). 인자의 런타임 타입은 오버로딩 선택에 영향이 없다.
- **필드와 static 메서드도 재정의된다** — 아니다. 필드는 컴파일 타입으로 접근하고(숨김, hiding), static 메서드도 숨김일 뿐 동적 디스패치 대상이 아니다.
- **생성자 안에서의 호출은 안전하다** — 상위 클래스 생성자에서 재정의 가능한 메서드를 부르면, 하위 클래스 필드가 초기화되기 전에 하위 클래스 메서드가 실행된다.

## 등장하는 아이템
- [아이템 18. 상속보다는 컴포지션을 사용하라](../Item18/item-18-composition-over-inheritance.md) — `super.addAll` 안의 `this.add`가 하위 클래스의 `add`로 디스패치되어 두 번 세는 원리다.
- [아이템 19. 상속을 고려해 설계하고 문서화하라. 그러지 않았다면 상속을 금지하라](../Item19/item-19-design-for-inheritance.md) — 상위 생성자 안의 `invokevirtual`이 초기화 전의 하위 클래스 메서드로 디스패치되는 사례를 다룬다.
