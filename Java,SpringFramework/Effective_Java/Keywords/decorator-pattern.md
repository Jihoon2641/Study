# 데코레이터 패턴 (Decorator pattern) / 래퍼 클래스 (wrapper class)

## 한 줄 정의
어떤 인터페이스의 구현 객체를 감싸면서 자신도 같은 인터페이스를 구현해, 호출을 내부 객체에 전달하는 앞뒤로 기능을 덧붙이는 구조. GoF 디자인 패턴 중 하나다.

## 도입 버전
- 설계 패턴이라 버전과 무관하다. Java 1.0+.
- JDK 안의 대표 사례: `java.io`의 `BufferedInputStream`/`DataInputStream`(Java 1.0+), `Collections.unmodifiableList`·`synchronizedList`·`checkedList`(Java 1.2+, `checked*`는 Java 5+).

## 무엇을 하는가
핵심 조건은 두 가지다.
1. 래퍼가 감싸는 대상과 같은 인터페이스를 구현한다. 그래서 사용자 입장에서는 래퍼와 원본을 구분할 필요가 없고, 래퍼를 또 다른 래퍼로 감쌀 수 있다.
2. 감싸는 대상의 타입을 구체 클래스가 아니라 인터페이스로 받는다. 그래서 하나의 래퍼가 모든 구현체에 붙는다.

```
new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF_8))
       └ 버퍼링          └ 바이트→문자 변환         └ 실제 파일 읽기
```

상속으로 같은 일을 하면 "버퍼링되는 파일 스트림", "버퍼링되는 소켓 스트림"처럼 조합마다 클래스가 필요하다. 데코레이터는 기능 하나당 클래스 하나로 끝나고, 조합은 런타임에 정한다.

## 최소 사용 예
```java
// 모든 List 구현체에 "null 금지"를 덧씌운다 (전달 클래스 없이 AbstractList 로 간단히)
final class NonNullList<E> extends AbstractList<E> {
    private final List<E> list;
    NonNullList(List<E> list) { this.list = list; }

    @Override public E get(int i)           { return list.get(i); }
    @Override public int size()             { return list.size(); }
    @Override public void add(int i, E e)   { list.add(i, Objects.requireNonNull(e)); }
    @Override public E set(int i, E e)      { return list.set(i, Objects.requireNonNull(e)); }
    @Override public E remove(int i)        { return list.remove(i); }
}
```
`AbstractList`는 상속용으로 설계·문서화된 골격 구현이라 상속해도 안전하다. `Objects.requireNonNull`은 Java 7+.

## 자주 하는 오해 / 헷갈리는 짝
- **데코레이터 vs 프록시(Proxy)** — 구조는 같다. 기준은 목적이다. 기능을 덧붙이면 데코레이터, 접근을 통제(지연 로딩, 원격 호출, 권한·트랜잭션)하면 프록시라 부른다. 스프링의 `@Transactional` 프록시도 구조상 래퍼라서 자기 호출(`this.save()`)이 프록시를 우회하는 SELF 문제를 똑같이 겪는다.
- **래퍼로 감싸면 원본이 보호된다** — 원본 참조를 다른 코드가 쥐고 있으면 래퍼를 거치지 않고 바로 수정된다. `Collections.unmodifiableList`가 진짜 불변이 아니라 "읽기 전용 뷰"인 이유다.
- **래퍼와 원본은 같은 객체다** — 아니다. `equals`는 전달해서 같게 만들 수 있지만 `==`, `IdentityHashMap`, `synchronized(obj)`에서는 서로 다른 객체다.

## 등장하는 아이템
- [아이템 18. 상속보다는 컴포지션을 사용하라](../Item18/item-18-composition-over-inheritance.md) — `InstrumentedSet`이 어떤 `Set`이든 감싸서 계측 기능을 덧씌우는 데코레이터다.
- [아이템 20. 추상 클래스보다는 인터페이스를 우선하라](../Item20/item-20-prefer-interfaces.md) — 타입이 인터페이스여야 래퍼가 원본과 같은 타입이 될 수 있다는 4번 항목의 근거다.
