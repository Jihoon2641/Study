# 객체 생성 순서 (instance initialization order)

## 한 줄 정의
`new Sub()` 한 줄이 실행될 때 메모리 할당, 필드 기본값 설정, 상위 생성자, 필드 초기화 식, 인스턴스 초기화 블록, 생성자 본문이 실행되는 정해진 순서. JLS 12.5에 정의되어 있다.

## 도입 버전
- Java 1.0+. Java 7~21에서 동일하다.
- `(Java 25+)` 유연한 생성자 본문(JEP 513)으로 `super(...)` 앞에 문장을 쓰고 자기 필드를 먼저 대입할 수 있게 되었다. Java 22~24에서는 프리뷰였다. Java 7~21에서는 `super(...)`가 항상 생성자의 첫 문장이다.

## 무엇을 하는가
`new Sub()`의 순서는 다음과 같다.

1. `new` 명령이 `Sub` 크기의 메모리를 할당하고 모든 필드(상위 클래스 필드 포함)를 기본값(`null`, `0`, `false`)으로 채운다. 객체의 런타임 클래스는 이 순간 이미 `Sub`다.
2. `Sub` 생성자가 호출되고, 첫 문장인 `super(...)`로 `Super` 생성자가 실행된다. 이 과정이 `Object`까지 재귀한다.
3. `Super` 생성자가 끝나면, `Sub`의 필드 초기화 식(`int x = 5;`)과 인스턴스 초기화 블록이 소스에 쓰인 순서대로 실행된다.
4. 마지막으로 `Sub` 생성자 본문의 나머지가 실행된다.

javac는 3번의 필드 초기화 식을 별도 메서드로 만들지 않고, 각 생성자의 `invokespecial Super.<init>` 바로 뒤에 복사해 넣는다. 그래서 2번 단계에서 `Super` 생성자가 재정의 가능 메서드를 호출하면, 동적 디스패치로 `Sub`의 메서드가 실행되지만 `Sub`의 필드는 아직 1번의 기본값 그대로다.

## 최소 사용 예
```java
class Super {
    Super() { System.out.println("Super 생성자: " + describe()); }
    String describe() { return "super"; }
}
class Sub extends Super {
    private String name = "sub";               // 필드 초기화 식 — Super() 가 끝난 뒤 실행
    Sub() { System.out.println("Sub 생성자: " + describe()); }
    @Override String describe() { return "name=" + name; }
}
new Sub();
// Super 생성자: name=null   ← 초기화 식보다 먼저 불렸다
// Sub 생성자: name=sub
```

## 자주 하는 오해 / 헷갈리는 짝
- **필드 초기화 식은 생성자보다 먼저 실행되니 안전하다** — 자기 클래스의 생성자 본문보다 먼저일 뿐, 상위 클래스 생성자보다는 늦다. 상위 생성자 안에서 보면 초기화 식이 붙은 필드도 기본값이다.
- **인스턴스 초기화 vs 클래스 초기화** — 기준은 "무엇이 트리거인가"이다. 클래스 초기화(정적 필드, `static` 블록)는 클래스를 처음 쓸 때 한 번 일어나고, 인스턴스 초기화는 `new`마다 일어난다. 클래스 초기화는 [클래스 초기화](class-initialization.md) 문서에서 다룬다.
- **`final` 필드는 언제 봐도 같은 값이다** — 생성자가 끝난 뒤에만 맞는 말이다. 생성 도중 새어 나간 `this`로 보면 기본값이 보일 수 있다.

## 등장하는 아이템
- [아이템 19. 상속을 고려해 설계하고 문서화하라. 그러지 않았다면 상속을 금지하라](../Item19/item-19-design-for-inheritance.md) — 상위 생성자가 재정의 가능 메서드를 부르면 안 되는 이유가 이 순서에 있다.
