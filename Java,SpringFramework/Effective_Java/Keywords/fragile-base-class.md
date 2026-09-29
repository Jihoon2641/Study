# 취약한 기반 클래스 문제 (fragile base class problem)

## 한 줄 정의
상위 클래스(기반 클래스)가 공개 API는 그대로 둔 채 내부 구현만 바꿨는데도, 그 클래스를 상속한 하위 클래스가 오동작하는 현상.

## 도입 버전
- 객체지향 언어 전반의 설계 문제라 자바 버전과 무관하다.
- 자바에서 이 문제를 줄이는 언어 장치: `final` 클래스(Java 1.0+), `sealed` 클래스 `(Java 17+)`, 문서화 태그 `@implSpec` `(Java 8+ 에서 JDK 문서에 도입된 관례. javadoc 기본 태그가 아니라 -tag 옵션으로 켠다)`.

## 무엇을 하는가
원인은 크게 두 갈래다.

- 자기사용(self-use) 변경: 상위 클래스의 메서드 A가 내부에서 재정의 가능한 메서드 B를 호출한다. 하위 클래스가 B를 재정의하면 A를 부를 때 하위 클래스의 B가 끼어든다. 이 호출 관계는 보통 문서화되지 않은 구현 세부라서, 상위 클래스가 A를 B 없이 구현하도록 바꾸면 하위 클래스의 결과가 달라진다.
- 메서드 추가: 상위 클래스에 새 메서드가 생기면, 하위 클래스가 재정의로 걸어 둔 검증을 새 메서드가 우회하거나, 하위 클래스가 먼저 만든 같은 이름의 메서드와 충돌한다.

JVM 수준에서 보면 상위 클래스 안의 `this.b()`는 `invokevirtual`로 컴파일되고, 런타임에 수신 객체의 실제 클래스에서 메서드를 고른다. 그래서 상위 클래스 코드 안의 재정의 가능한 호출 하나하나가 하위 클래스로 향하는 연결점이 된다. 이 연결점의 위치와 개수가 바뀌는 것이 곧 "구현만 바꿨는데 하위 클래스가 깨지는" 이유다.

## 최소 사용 예
```java
class Counter extends HashSet<String> {
    int count;
    @Override public boolean add(String s)                   { count++; return super.add(s); }
    @Override public boolean addAll(Collection<? extends String> c) { count += c.size(); return super.addAll(c); }
}
Counter c = new Counter();
c.addAll(Arrays.asList("a", "b", "c"));
// c.count == 6 — AbstractCollection.addAll 이 내부에서 add 를 부르기 때문
```

## 자주 하는 오해 / 헷갈리는 짝
- **공개 API만 안 바꾸면 하위 호환이다** — 상속을 허용한 클래스에서는 아니다. 자기사용 패턴도 사실상 API의 일부가 된다. 그래서 상속을 허용하려면 그 패턴을 `@implSpec`으로 문서화하고 영원히 지켜야 한다.
- **취약한 기반 클래스 vs 바이너리 호환성** — 바이너리 호환성은 "재컴파일 없이 링크되는가"(`NoSuchMethodError` 등)의 문제이고, 이 문제는 링크도 되고 실행도 되는데 결과가 틀리는 의미(semantic)의 문제다. 후자가 더 조용하다.
- **인터페이스 구현에는 이 문제가 없다** — 대부분 맞지만 `(Java 8+)` default 메서드가 생긴 뒤로는 인터페이스에 추가된 메서드가 구현 클래스의 불변식을 우회하는 형태로 일부 재현된다.

## 등장하는 아이템
- [아이템 18. 상속보다는 컴포지션을 사용하라](../Item18/item-18-composition-over-inheritance.md) — `InstrumentedHashSet`의 두 배 계산과 `HashMap.merge`의 검증 우회가 이 문제의 두 갈래를 각각 보여 준다.
- [아이템 19. 상속을 고려해 설계하고 문서화하라. 그러지 않았다면 상속을 금지하라](../Item19/item-19-design-for-inheritance.md) — 상위 클래스 작성자 쪽의 대책이다. 자기사용을 `@implSpec`으로 계약으로 만들거나, `final`로 상속을 막아 자기사용을 구현 세부로 되돌린다.
