# 컴포지션 (composition)과 전달 (forwarding)

## 한 줄 정의
기존 클래스를 상속하는 대신 그 인스턴스를 `private` 필드로 갖고(컴포지션), 새 클래스의 메서드가 그 필드의 같은 메서드를 호출해 결과를 돌려주는(전달) 재사용 방식.

## 도입 버전
- 언어 기능이 아니라 설계 기법이라 버전과 무관하다. Java 1.0+, Java 7 레거시에서도 그대로 쓴다.
- 자바에는 코틀린의 `by`처럼 전달 코드를 자동 생성하는 언어 기능이 Java 21까지도 없다. 전달 메서드는 손으로 쓰거나 IDE의 "Generate → Delegate Methods"로 만든다.

## 무엇을 하는가
두 관계를 "has-a vs is-a"로 대조하면 쉽다. 상속은 "B는 A다", 컴포지션은 "B는 A를 가지고 있다"이다.

동작 차이는 호출의 수신 객체에서 갈린다. 상속한 클래스에서 `super.m()`을 부르면 상위 구현 안의 `this`는 여전히 하위 클래스 인스턴스라서, 그 안의 `this.n()`이 하위 클래스의 재정의 메서드로 디스패치된다. 전달에서는 `inner.m()`의 수신 객체가 내부 객체 자신이므로, 내부의 `this.n()`은 내부 객체의 메서드로만 간다. 내부 객체는 자신을 감싼 클래스의 존재를 모른다.

결과적으로 새 클래스는 기존 클래스의 구현 세부에서 벗어난다. 기존 클래스가 내부 구현을 바꾸거나 새 메서드를 추가해도 새 클래스에는 영향이 없다. 새 클래스가 공개하는 API도 기존 클래스와 다르게, 더 작게 설계할 수 있다.

엄밀한 용어로는 래퍼가 내부 객체에 자기 참조(`this`)를 넘기지 않으면 전달, 넘기면 위임(delegation)이다. 실무에서는 둘을 섞어 "위임"이라고 부르는 경우가 많다.

## 최소 사용 예
```java
// Deque 를 그대로 노출하지 않고, 스택 연산만 공개한다
public final class BoundedStack<E> {
    private final Deque<E> items = new ArrayDeque<>();   // 컴포지션
    private final int capacity;

    public BoundedStack(int capacity) { this.capacity = capacity; }

    public void push(E e) {
        if (items.size() == capacity) throw new IllegalStateException("가득 참");
        items.push(e);                                    // 전달
    }
    public E pop()     { return items.pop(); }            // 전달
    public int size()  { return items.size(); }           // 전달
}
```
`Deque`의 `addLast`, `removeFirstOccurrence` 같은 메서드는 공개되지 않으므로 "용량 제한"이라는 불변식을 우회할 경로가 없다.

## 자주 하는 오해 / 헷갈리는 짝
- **컴포지션 vs 상속** — 기준은 "B가 정말 A인가(is-a)"와 "A가 상속을 고려해 설계·문서화되었는가"이다. 둘 다 예일 때만 상속하고, 하나라도 아니면 컴포지션을 쓴다.
- **컴포지션은 코드가 많아서 비효율적이다** — 전달 메서드 호출은 JIT 인라인으로 사라지는 경우가 대부분이다. 전달 코드의 양은 인터페이스당 전달 클래스를 한 번만 만들면 해결된다.
- **컴포지션이면 무조건 안전하다** — 감싼 인터페이스에 default 메서드가 추가되거나, 내부 객체가 `this`를 외부에 넘기는(SELF 문제) 경우에는 래퍼를 우회하는 경로가 생긴다.

## 등장하는 아이템
- [아이템 18. 상속보다는 컴포지션을 사용하라](../Item18/item-18-composition-over-inheritance.md) — `ForwardingSet`/`InstrumentedSet`으로 상속의 자기사용 문제를 없애는 이 아이템의 주제 자체다.
