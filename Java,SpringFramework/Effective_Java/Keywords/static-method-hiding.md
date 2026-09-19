# 정적 메서드 은닉 (static method hiding)

## 한 줄 정의
하위 클래스가 상위 클래스의 정적 메서드와 같은 시그니처의 정적 메서드를 선언하면, 재정의(overriding)가 아니라 가리기(hiding)가 되어 호출할 메서드가 객체가 아닌 **참조의 컴파일 시점 타입**으로 결정되는 현상.

## 도입 버전
(모든 버전) JLS 8.4.8.2가 규정한다. Java 7 레거시에서도 동일하다.
- `@Override`를 정적 메서드에 붙이면 컴파일 에러(`static methods cannot be annotated with @Override`)라 오해를 확인할 수단이 없다.

## 무엇을 하는가
인스턴스 메서드의 재정의는 런타임에 객체의 실제 타입을 보고 결정된다(동적 디스패치). 바이트코드로는 `invokevirtual`이고, JVM이 객체의 클래스에서 시작해 메서드 테이블을 찾아 올라간다.

정적 메서드에는 이 과정이 없다. 호출은 `invokestatic`으로 컴파일되고, **어느 클래스의 메서드를 부를지는 javac가 컴파일 시점에 참조의 정적 타입으로 결정해 상수 풀에 박아 넣는다.** 객체는 개입하지 않는다. 그래서 같은 객체를 가리키는 두 참조라도, 선언한 타입이 다르면 다른 메서드가 불린다.

인스턴스 참조로 정적 메서드를 호출하는 문법(`obj.staticMethod()`)이 허용되는 것이 이 혼란을 키운다. 이 형태는 javac가 `Type.staticMethod()`로 바꿔 컴파일하며, 참조 변수의 값은 평가만 되고 버려진다. `null`인 참조로 불러도 `NullPointerException`이 나지 않는다.

같은 현상이 필드에도 있다. 필드는 정적이든 아니든 언제나 참조의 정적 타입으로 결정된다(필드는 재정의되지 않는다).

## 최소 사용 예
```java
class Parent { static String who() { return "Parent"; } }
class Child extends Parent { static String who() { return "Child"; } }   // 재정의 아님

Parent p = new Child();
System.out.println(p.who());        // "Parent"  ← 참조 타입이 Parent
System.out.println(Child.who());    // "Child"
System.out.println(((Child) p).who());  // "Child"  ← 캐스팅으로 정적 타입만 바꿔도 결과가 바뀐다

Parent nil = null;
System.out.println(nil.who());      // "Parent"  ← NPE 가 나지 않는다
```

## 자주 하는 오해 / 헷갈리는 짝
- **은닉 vs 재정의** — 기준은 "정적인가". 정적 메서드는 은닉(컴파일 시점 타입으로 결정), 인스턴스 메서드는 재정의(런타임 타입으로 결정)다. 상위가 정적인데 하위가 인스턴스 메서드로(또는 그 반대로) 선언하면 컴파일 에러다.
- **`@Override`를 안 붙였을 뿐 동작은 같다** — 다르다. 그리고 정적 메서드에는 `@Override`를 붙일 수 없어서, 컴파일러의 도움을 받아 실수를 잡을 수 없다.
- **`private` 정적 메서드도 은닉된다** — `private` 메서드는 상속되지 않으므로 하위 클래스의 같은 이름 메서드는 완전히 별개의 메서드다.

호출 결과가 참조 타입에 좌우되는 코드는 읽는 사람이 예측하기 어렵다. 실무 규칙은 단순하다. 정적 메서드는 **항상 클래스 이름으로 호출**하고(`Parent.who()`), 정적 메서드를 가진 클래스는 애초에 상속되지 않게 만든다.

## 등장하는 아이템
- [아이템 4. 인스턴스화를 막으려거든 private 생성자를 사용하라](../Item4/item-4-noninstantiable-utility-class.md) — 유틸리티 클래스의 상속을 열어 두었을 때 생기는 조용한 버그로 등장한다. `private` 생성자가 상속을 막는 부가 효과가 이 문제를 원천 봉쇄한다.
