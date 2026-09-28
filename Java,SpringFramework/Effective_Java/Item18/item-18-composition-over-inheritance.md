# 아이템 18. 상속보다는 컴포지션을 사용하라

## 한 줄 결론

패키지 경계를 넘어 다른 사람이 만든 구체 클래스를 상속(extends)하면, 하위 클래스의 정확성이 상위 클래스의 내부 구현(어떤 메서드가 어떤 메서드를 부르는지)에 묶인다. 그 구현은 문서에도 없고 다음 릴리스에서 바뀔 수 있다. 그래서 기존 클래스를 확장하고 싶으면, 그 클래스의 인스턴스를 `private` 필드로 두고(컴포지션, composition) 메서드 호출을 그대로 넘겨주는(전달, forwarding) 래퍼 클래스를 만들어야 한다.

상속이 문제가 되는 이유는 "코드 재사용"이 아니라 "재정의(override)"에 있다. 재정의한 메서드는 상위 클래스 내부에서 호출될 때도 동적 디스패치로 하위 클래스 버전이 불린다. 컴포지션에서는 내부 객체가 래퍼의 존재를 모르므로 이런 역호출이 원천적으로 생기지 않는다.

---

## 신입 눈높이 설명

"A는 명령, B는 요청"으로 대조하면 이렇다. 상속은 상위 클래스의 코드 안으로 들어가서 일부 메서드를 바꿔치기하는 것이다. 바꿔치기한 메서드를 상위 클래스가 스스로 부르는지, 몇 번 부르는지는 상위 클래스 마음이다. 컴포지션은 상위 클래스를 바깥에서 쓰는 것이다. 래퍼는 내부 객체에 "이거 해 줘"라고 요청만 하고, 내부 객체가 안에서 무엇을 하든 래퍼 쪽으로 다시 호출이 돌아오지 않는다.

이 아이템이 말하는 "상속"은 클래스가 다른 클래스를 확장하는 구현 상속(implementation inheritance)이다. 클래스가 인터페이스를 구현하거나 인터페이스가 인터페이스를 확장하는 인터페이스 상속은 해당하지 않는다. 또 같은 프로그래머가 통제하는 패키지 안에서의 상속이나, 상속용으로 설계되고 문서화된 클래스(아이템 19)의 상속은 안전하다. 위험한 것은 "남이 만든, 상속을 염두에 두지 않은 구체 클래스를 패키지 경계 너머에서 상속하는 것"이다.

---

# 1단계 — 상속은 왜 캡슐화를 깨뜨리는가

## ① 흔히 쓰는(나쁜) 코드

책의 예제다. `HashSet`에 원소가 몇 번 추가됐는지 세는 기능을 붙이고 싶어서 `HashSet`을 상속했다.

```java
// 잘못된 예 — 상속을 잘못 사용했다!
public class InstrumentedHashSet<E> extends HashSet<E> {
    // 추가된 원소의 수
    private int addCount = 0;

    public InstrumentedHashSet() { }

    public InstrumentedHashSet(int initCap, float loadFactor) {
        super(initCap, loadFactor);
    }

    @Override public boolean add(E e) {
        addCount++;
        return super.add(e);
    }

    @Override public boolean addAll(Collection<? extends E> c) {
        addCount += c.size();
        return super.addAll(c);
    }

    public int getAddCount() { return addCount; }
}
```

`add`와 `addAll`을 모두 재정의했으니 빠짐없이 센다고 믿기 쉽다.

## ② 무엇이 문제인가

책이 드는 문제는 세 가지다. 셋 다 "하위 클래스가 상위 클래스의 구현 세부에 의존한다"는 한 문장에서 나온다.

### 문제 (a) — 자기사용(self-use) 때문에 두 번 센다

```java
InstrumentedHashSet<String> s = new InstrumentedHashSet<>();
s.addAll(List.of("틱", "탁탁", "펑"));
s.getAddCount();   // 기대: 3, 실제: 6
```

`HashSet.addAll`은 내부에서 원소마다 `add`를 호출한다. 그런데 이 `add`는 `InstrumentedHashSet`이 재정의한 `add`다. 그래서 `addAll`에서 3을 더하고, `add`가 세 번 불리면서 3을 또 더한다.

- 컴파일 에러 없음. 런타임 예외 없음. 로그 한 줄 안 남는다. 숫자만 두 배가 된다.
- `add`만 호출하는 단위 테스트는 통과한다. `addAll`로 한 번에 여러 개를 넣는 테스트를 따로 짜지 않으면 발견되지 않는다.
- 운영에서는 "API 호출 통계가 가끔 두 배로 잡힌다" 같은 증상으로 나타난다. 일괄 등록(`addAll`) 경로를 탄 요청만 두 배가 되므로, 원인을 `InstrumentedHashSet`까지 추적하기 어렵다.
- `HashSet`의 문서 어디에도 "`addAll`은 `add`를 이용해 구현했다"는 약속은 없다. 이 동작은 `AbstractCollection`의 구현 세부다. 원인을 만든 사람(JDK 작성자)과 코드를 짠 사람이 다르고, 원인은 소스를 열어 보기 전에는 보이지 않는다.

그럼 고치면 되지 않을까? 책은 두 가지 땜질을 검토하고 둘 다 기각한다.

```java
// 땜질 1: addAll 재정의를 지운다 → 지금은 3이 나온다
// 하지만 "HashSet.addAll이 add를 부른다"는 구현 세부에 기대어 맞는 것이다.
// 다음 릴리스에서 addAll이 add를 거치지 않게 바뀌면 이번엔 0으로 센다.

// 땜질 2: addAll을 직접 다시 구현한다
@Override public boolean addAll(Collection<? extends E> c) {
    boolean modified = false;
    for (E e : c) if (add(e)) modified = true;   // super.addAll 을 부르지 않는다
    return modified;
}
// 상위 클래스 메서드 동작을 다시 구현하는 것이라 어렵고, 오류를 내기 쉽고, 성능이 떨어질 수 있다.
// 상위 클래스의 private 필드를 써야 구현할 수 있는 메서드라면 아예 불가능하다.
```

### 문제 (b) — 상위 클래스에 새 메서드가 생기면 검증을 우회당한다

하위 클래스가 "모든 삽입 메서드를 재정의해서 원소를 검사"하는 식으로 보안·검증을 걸었다고 하자. 지금 존재하는 삽입 메서드는 전부 막았다. 그런데 다음 릴리스에서 상위 클래스에 새로운 삽입 메서드가 추가되면, 하위 클래스가 재정의하지 않은 그 메서드로 검사 안 된 원소가 들어간다. 책은 `Hashtable`과 `Vector`를 컬렉션 프레임워크에 편입하면서 이런 보안 구멍을 수정해야 했던 사례를 든다.

이건 가정이 아니라 Java 8에서 실제로 벌어졌다. 값에 음수를 금지하려고 `HashMap`을 상속한 코드를 보자.

```java
// Java 7 시절에 짠 코드 — 삽입 경로 put, putAll 을 모두 막았다고 믿는다
public class NonNegativeMap extends HashMap<String, Integer> {
    @Override public Integer put(String k, Integer v) {
        if (v < 0) throw new IllegalArgumentException("음수: " + v);
        return super.put(k, v);
    }
    // HashMap.putAll 은 내부에서 put 을 부르지 않으므로 putAll 도 재정의했다고 치자
}
```

Java 8에서 `Map`에 `putIfAbsent`, `merge`, `compute`, `computeIfAbsent`가 생겼고, `HashMap`은 이들을 `put`을 거치지 않는 자체 구현으로 재정의했다.

```java
NonNegativeMap m = new NonNegativeMap();
m.merge("stock", -5, Integer::sum);    // 검증 우회 — 음수가 그대로 들어간다
m.putIfAbsent("price", -1000);         // 이것도 우회
```

- `NonNegativeMap`의 코드는 한 줄도 안 바뀌었다. JDK만 7에서 8로 올렸다.
- 컴파일 에러 없음. 런타임 예외 없음. 새 API를 쓴 동료의 코드가 검증을 통과한 것처럼 보인다.
- 근본적으로 "재정의로 막는다"는 방식은 상위 클래스의 메서드 목록이 고정돼 있다는 가정 위에 서 있다. 그 목록을 정하는 사람은 하위 클래스 작성자가 아니다.

### 문제 (c) — 내가 추가한 메서드가 상위 클래스의 새 메서드와 부딪친다

재정의를 아예 안 하고 새 메서드만 추가하면 안전할까? 그렇지도 않다. 다음 릴리스에서 상위 클래스에 같은 이름의 메서드가 생기면 두 가지 중 하나가 된다.

- 시그니처가 같고 반환 타입이 다르다 → 하위 클래스는 컴파일조차 되지 않는다.
- 시그니처와 반환 타입이 모두 같다 → 의도치 않게 재정의가 되어 문제 (a), (b)로 돌아간다. 게다가 내 메서드는 상위 클래스가 새 메서드에 요구하는 규약을 모른 채 작성된 것이다.

근본 원인은 하나다. **상속은 상위 클래스의 구현 세부를 하위 클래스의 정확성 조건으로 만들어서, 상위 클래스가 캡슐화한 것을 하위 클래스가 알아야 하게 만든다.**

## 원리 — 왜 super.addAll 안에서 내 add가 불리는가

`InstrumentedHashSet.addAll`의 `super.addAll(c)`는 이렇게 컴파일된다.

```
aload_0
aload_1
invokespecial #12  // Method java/util/HashSet.addAll:(Ljava/util/Collection;)Z
```

`invokespecial`은 동적 디스패치를 하지 않고 상위 클래스 쪽 구현을 직접 부른다. `HashSet`에는 `addAll`이 없으므로 `AbstractCollection.addAll`까지 올라가 찾는다. 여기까지는 의도대로다. 문제는 그 안이다.

```java
// JDK 소스 발췌 — java.util.AbstractCollection (Java 8~21 기준 핵심만. HashSet은 addAll을 재정의하지 않는다)
public boolean addAll(Collection<? extends E> c) {
    boolean modified = false;
    for (E e : c)
        if (add(e))          // this.add(e)
            modified = true;
    return modified;
}
```

```
aload_0                              // this — 여전히 InstrumentedHashSet 인스턴스다
aload_3
invokevirtual #7   // Method add:(Ljava/lang/Object;)Z
```

`add(e)`는 `this.add(e)`이고 `invokevirtual`로 컴파일된다. `invokevirtual`은 컴파일 타입(`AbstractCollection`)이 아니라 수신 객체(`this`)의 런타임 클래스에서부터 메서드를 찾는다(JLS 15.12.4.4). `this`는 `super.addAll`을 거쳐 왔어도 여전히 `InstrumentedHashSet`이므로 재정의한 `add`가 선택된다. `super.`는 "이 한 번의 호출만" 상위 구현으로 보낼 뿐이고, 그 안에서 벌어지는 호출들의 디스패치에는 영향이 없다.

이것이 "상속은 캡슐화를 깨뜨린다"는 말의 바이트코드 수준 의미다. 상위 클래스 안의 `invokevirtual this.xxx()` 하나하나가 하위 클래스로 향하는 문이다. 어떤 메서드가 어떤 문을 여는지(자기사용 패턴)는 상위 클래스의 구현 세부이고, 하위 클래스는 그걸 알아야만 올바르게 동작한다.

---

# 2단계 — 컴포지션과 전달로 래퍼 클래스를 만든다

## ③ 개선된 코드

책의 개선형이다. 재사용 가능한 전달 클래스(forwarding class)와, 그것을 확장한 래퍼 클래스로 나눈다.

```java
// 재사용할 수 있는 전달 클래스 — Set 의 모든 메서드를 내부 s 에 그대로 넘긴다
public class ForwardingSet<E> implements Set<E> {
    private final Set<E> s;
    public ForwardingSet(Set<E> s) { this.s = s; }

    public void clear()                                   { s.clear(); }
    public boolean contains(Object o)                     { return s.contains(o); }
    public boolean isEmpty()                              { return s.isEmpty(); }
    public int size()                                     { return s.size(); }
    public Iterator<E> iterator()                         { return s.iterator(); }
    public boolean add(E e)                               { return s.add(e); }
    public boolean remove(Object o)                       { return s.remove(o); }
    public boolean containsAll(Collection<?> c)           { return s.containsAll(c); }
    public boolean addAll(Collection<? extends E> c)      { return s.addAll(c); }
    public boolean removeAll(Collection<?> c)             { return s.removeAll(c); }
    public boolean retainAll(Collection<?> c)             { return s.retainAll(c); }
    public Object[] toArray()                             { return s.toArray(); }
    public <T> T[] toArray(T[] a)                         { return s.toArray(a); }
    @Override public boolean equals(Object o)             { return s.equals(o); }
    @Override public int hashCode()                       { return s.hashCode(); }
    @Override public String toString()                    { return s.toString(); }
}

// 래퍼 클래스 — 상속 대신 컴포지션을 사용했다
public class InstrumentedSet<E> extends ForwardingSet<E> {
    private int addCount = 0;

    public InstrumentedSet(Set<E> s) { super(s); }

    @Override public boolean add(E e) {
        addCount++;
        return super.add(e);
    }
    @Override public boolean addAll(Collection<? extends E> c) {
        addCount += c.size();
        return super.addAll(c);
    }
    public int getAddCount() { return addCount; }
}
```

```java
InstrumentedSet<String> s = new InstrumentedSet<>(new HashSet<>());
s.addAll(List.of("틱", "탁탁", "펑"));
s.getAddCount();   // 3
```

## 원리 — 이게 왜 1단계의 세 문제를 동시에 푸는가

`InstrumentedSet.addAll` → `ForwardingSet.addAll` → `s.addAll(c)`로 흐른다. 마지막 호출의 수신 객체는 `this`가 아니라 필드 `s`, 즉 순수한 `HashSet` 인스턴스다. `HashSet` 안에서 `this.add(e)`가 불려도 그 `this`는 `HashSet`이므로 `HashSet.add`가 선택된다. `HashSet`은 자신을 감싼 래퍼가 있다는 사실을 모르고, 래퍼로 돌아오는 호출 경로 자체가 없다.

- (a) 자기사용: 내부 객체의 자기사용은 내부 객체 안에서 끝난다. `HashSet.addAll`이 `add`를 쓰든 안 쓰든 `InstrumentedSet`의 결과는 같다.
- (b) 새 메서드: 래퍼가 외부에 공개하는 메서드는 `Set` 인터페이스에 정의된 것뿐이다. `HashSet`에 새 public 메서드가 생겨도 래퍼 사용자는 그걸 호출할 방법이 없다(인터페이스 자체에 메서드가 추가되는 경우는 예외다. 아래 Java 21 보완 참고).
- (c) 이름 충돌: 래퍼는 `HashSet`의 하위 타입이 아니므로 `HashSet`에 무엇이 추가되든 래퍼의 메서드와 충돌하지 않는다.

덤으로 유연성도 생긴다. `InstrumentedHashSet`은 `HashSet`에만 붙지만, `InstrumentedSet`은 `Set`이면 무엇이든 감쌀 수 있고 생성자도 따로 만들 필요가 없다.

```java
Set<Instant> times = new InstrumentedSet<>(new TreeSet<>(cmp));
Set<E> s2 = new InstrumentedSet<>(new HashSet<>(INIT_CAPACITY));
```

이미 쓰이고 있는 `Set` 인스턴스를 잠깐 감쌌다가 벗기는 것도 가능하다. 이렇게 다른 `Set`에 계측 기능을 덧씌운다는 뜻에서 이런 구조를 데코레이터 패턴(Decorator pattern)이라고 한다.

전달 클래스를 한 번 만들어 두면 래퍼는 관심 있는 메서드만 재정의하면 된다. `ForwardingSet` 자체는 `s`에 대해서만 호출하고 `this`의 다른 메서드를 부르지 않는다. 그래서 `InstrumentedSet`이 `ForwardingSet`을 상속해도 1단계 문제가 재발하지 않는다. `ForwardingSet`은 같은 패키지에서 통제하는, 자기사용이 없도록 설계된 클래스이기 때문이다.

## 버전 표기

- 컴포지션·전달·래퍼 클래스: 언어 기능이 아니라 설계 기법이다. Java 1.0+. `ForwardingSet`/`InstrumentedSet` 전체가 Java 7 레거시에서 그대로 컴파일된다. 다이아몬드 `new InstrumentedSet<>(...)`는 Java 7+.
- 책의 `List.of("틱", "탁탁", "펑")`은 Java 9+다. Java 7에서는 `Arrays.asList("틱", "탁탁", "펑")`을 쓴다. `Arrays.asList`는 원본 배열을 감싼 고정 크기 가변 리스트라서 `List.of`의 진짜 불변과는 다르지만, `addAll`의 인자로 넘기는 용도에서는 동작이 같다. 예제 파일은 이 방식으로 Java 7 문법만 쓴다.
- 1단계 문제 (b)의 `Map.merge`/`putIfAbsent`/`computeIfAbsent`: Java 8+. 문제를 일으킨 쪽이 이 메서드들이므로, 역설적으로 Java 7에서는 `NonNegativeMap`이 제대로 동작했다.
- `HashSet`이 `addAll`을 재정의하지 않고 `AbstractCollection.addAll`을 물려받는 것은 Java 8~21에서 확인되는 구현이다. 구현 세부이므로 다른 버전·다른 JDK 구현에서는 다를 수 있다. 바로 그 점이 이 아이템의 요점이다.

---

# 확장 — 래퍼 클래스의 한계와 상속을 써도 되는 때

## 래퍼의 단점: 콜백 프레임워크와 SELF 문제

책이 드는 래퍼 클래스의 단점은 거의 하나다. 콜백(callback) 프레임워크와는 잘 어울리지 않는다. 내부 객체가 자기 참조(`this`)를 다른 객체에 넘겨 나중에 호출받게 하면, 넘어간 것은 래퍼가 아니라 내부 객체다. 이후 콜백은 래퍼를 건너뛴다. 이를 SELF 문제라고 한다.

```java
class Worker implements Task {
    public void register(Scheduler sch) { sch.schedule(this); }   // 래퍼가 아니라 자신을 넘긴다
    public void run() { /* ... */ }
}

Task logged = new LoggingTask(new Worker());   // 실행 로그를 남기는 래퍼
((LoggingTask) logged).register(scheduler);    // 전달 → worker.register → schedule(worker)
// 스케줄러가 나중에 run()을 호출하면 LoggingTask.run 을 거치지 않는다. 로그가 한 줄도 안 남는다.
```

백엔드 개발자에게 가장 익숙한 SELF 문제는 스프링 AOP의 자기 호출(self-invocation)이다. `@Transactional`은 빈을 프록시(래퍼)로 감싸서 구현하는데, 빈 안에서 `this.save()`로 자기 메서드를 부르면 프록시를 거치지 않으므로 트랜잭션이 적용되지 않는다. 구조가 정확히 같다.

그 밖의 우려는 실무에서 거의 문제가 되지 않는다. 전달 메서드 호출 비용은 JIT가 인라인하면 사라지고, 래퍼 객체 하나만큼 메모리를 더 쓰는 것도 대부분 무시할 수준이다. 전달 메서드를 일일이 작성하는 게 귀찮지만, 인터페이스당 전달 클래스는 한 번만 만들면 된다. 책은 구아바(Guava)가 모든 컬렉션 인터페이스용 전달 클래스(`ForwardingSet`, `ForwardingList` 등)를 이미 제공한다고 소개한다.

## 상속을 써도 되는 때: 진짜 is-a 관계

상속은 하위 클래스가 상위 클래스의 진짜 하위 타입일 때만 써야 한다. "B가 정말 A인가?"라는 질문에 확실히 예라고 답할 수 없으면 상속하지 말고, A를 B의 `private` 필드로 두고 A와는 다른 작은 API를 공개해야 한다. 이때 A는 B의 필수 구성요소가 아니라 구현 방법 중 하나일 뿐이다.

책은 이 원칙을 어긴 자바 플랫폼의 사례로 `Stack`(`Vector` 상속)과 `Properties`(`Hashtable` 상속)를 든다. 둘 다 컴포지션을 썼어야 했다. `Properties`로 실패를 재현해 보면 이렇다.

```java
Properties props = new Properties();
props.put("timeout", 30);                  // Hashtable.put 이 노출되어 있어서 Integer 가 들어간다
props.getProperty("timeout");              // null  ← 문자열이 아니면 없는 것으로 취급한다
props.store(out, null);                    // ClassCastException: Integer cannot be cast to String
```

`Properties`는 "키와 값이 모두 문자열"이라는 불변식을 갖지만, 상위 클래스 `Hashtable`의 `put(Object, Object)`가 그대로 공개되어 있어서 불변식을 지킬 방법이 없다. 증상(`null` 또는 `store`의 예외)은 원인(`put`)에서 멀리 떨어진 곳에서 나타난다. `Stack`도 마찬가지로 `stack.add(0, x)`나 `stack.remove(1)`처럼 `Vector`의 인덱스 연산으로 LIFO 규칙을 깨는 호출이 모두 공개되어 있다.

컴포지션 대신 상속을 쓰면 내부 구현을 불필요하게 노출하는 것이다. API가 내부 구현(`Hashtable`)에 묶이고, 클라이언트가 노출된 내부에 직접 접근할 수 있게 된다. `Stack`이 필요하면 `Deque` 인터페이스와 `ArrayDeque`(Java 6+)를 쓴다. 공개 API가 `Deque`이므로 구현을 바꿀 수 있다.

상속하기로 결정했더라도 마지막으로 확인할 것이 있다. 확장하려는 클래스의 API에 결함이 있는가? 상속은 상위 클래스의 결함까지 그대로 승계하지만, 컴포지션으로는 결함을 숨기는 새 API를 설계할 수 있다.

## 버전 표기

- `Stack`, `Properties`, `Hashtable`, `Vector`: Java 1.0+. 호환성 때문에 Java 21에서도 상속 구조가 그대로다. deprecated는 아니다.
- `ArrayDeque`/`Deque`: Java 6+. Java 7 레거시에서도 그대로 쓸 수 있다.

---

# 3판(Java 9 기준) 이후 — 현재(Java 21) 시점의 보완

## sealed는 "통제 가능한 상속"을 선언으로 만든다

`(Java 17+)` 책은 "같은 프로그래머가 통제하는 패키지 안에서라면 상속도 안전하다"고 말한다. 그런데 Java 16 이전에는 `public` 클래스를 같은 패키지에서만 상속하게 제한할 방법이 마땅치 않았다. 생성자를 package-private으로 두는 우회가 전부였다. `sealed`는 이 조건을 언어 차원에서 선언하게 해 준다.

```java
// 상속을 허락할 클래스를 직접 나열한다 — 나머지는 컴파일 에러
public sealed abstract class Payment permits CardPayment, BankTransfer { /* ... */ }
public final class CardPayment extends Payment { /* ... */ }
public final class BankTransfer extends Payment { /* ... */ }
```

하위 클래스는 같은 모듈 안에 있어야 한다(이름 없는 모듈이면 같은 패키지). 즉 `sealed` 계층은 책이 "안전하다"고 한 바로 그 상황이다. 상위 클래스의 자기사용이 바뀌면 `permits`에 적힌 하위 클래스만 같이 고치면 된다.

하지만 `sealed`가 이 아이템의 문제를 푸는 것은 아니다. `sealed`는 "누가 상속하느냐"를 제한할 뿐, 허락된 하위 클래스 안에서 벌어지는 자기사용 문제는 그대로다. 허락된 하위 클래스를 `non-sealed`로 선언하면 그 아래로는 다시 누구나 상속할 수 있다. 또 `HashSet` 같은 남의 클래스에는 `sealed`를 붙일 수 없으므로 1단계 상황에는 아무 도움이 안 된다.

Java 7~16 대안: 상위 클래스의 생성자를 package-private으로 두면 다른 패키지에서는 상속할 수 없다. 다만 같은 패키지 안의 상속은 목록으로 제한되지 않고, `switch` 패턴 매칭의 완전성(exhaustiveness) 검사도 받을 수 없다는 점이 `sealed`와 다르다.

## record는 상속을 아예 막는다 — 확장하려면 컴포지션뿐이다

`(Java 16+)` 레코드는 암묵적으로 `final`이고 다른 클래스를 상속할 수도 없다(`java.lang.Record`를 이미 상속한다). 그래서 레코드에 기능을 덧붙이고 싶으면 컴포지션 말고 선택지가 없다. `record Money(long amount, Currency currency)`에 할인 정책을 붙이려면 `Money`를 필드로 갖는 새 타입을 만든다. 언어가 이 아이템의 결론을 강제한 셈이다.

## 인터페이스 default 메서드는 래퍼도 흔든다

`(Java 8+)` 2단계 원리에서 "(b) 새 메서드 문제는 인터페이스에 메서드가 추가되면 예외"라고 한 부분이다. `ForwardingSet`은 Java 7 시절 `Set`의 메서드만 전달한다. Java 8에서 `Collection`에 `removeIf`, `stream`, `forEach`, `spliterator` 같은 default 메서드가 추가되었고, `ForwardingSet`은 이들을 전달하지 않는다. 그래서 `wrapper.removeIf(p)`는 내부 `s`의 최적화된 `removeIf`가 아니라 `Collection`의 default 구현을 실행한다. default 구현은 `iterator()`를 통해 동작하므로 결과는 맞지만, 래퍼가 "모든 호출에 락을 건다" 같은 불변식을 가졌다면 default 메서드 경로에서는 그 불변식이 빠진다. 이 문제는 인터페이스 설계를 다루는 아이템 21에서 아파치 커먼즈의 `SynchronizedCollection` 사례로 다시 나온다.

따라서 JDK를 올릴 때는 래퍼가 감싼 인터페이스에 새 default 메서드가 생겼는지 확인하고, 필요하면 전달 메서드를 추가해야 한다. 컴포지션이 상속보다 안전하긴 하지만 완전히 면역은 아니다.

## Lombok @Delegate와 코틀린 by는 전달 코드를 자동으로 만든다

코틀린은 `class InstrumentedSet<E>(s: MutableSet<E>) : MutableSet<E> by s`처럼 언어 차원에서 위임을 지원한다. 자바에는 이런 기능이 없어서 Lombok `@Delegate`로 전달 메서드를 생성하는 경우가 있다. 두 가지를 알아 둬야 한다.

- `@Delegate`는 `lombok.experimental` 패키지에 있는 실험 기능이고, Lombok 팀도 정식 기능으로 승격할 계획이 없다고 밝힌 상태다. 장기적으로 기대기 어렵다.
- 전달 메서드가 컴파일 시점의 타입 정보를 보고 생성되므로, 위의 default 메서드 문제처럼 "어떤 메서드가 전달되고 어떤 메서드가 빠졌는지"가 소스에 보이지 않는다. 손으로 쓴 `ForwardingSet`은 적어도 목록이 눈에 보인다.

---

# 심화 / 예외 상황

**엄밀한 의미의 위임과 전달은 다르다.** 컴포지션과 전달의 조합을 흔히 위임(delegation)이라고 부르지만, 책은 엄밀히 말해 래퍼 객체가 내부 객체에 자기 자신의 참조를 넘기는 경우만 위임이라고 짚는다. `InstrumentedSet`은 `this`를 넘기지 않으므로 전달이다. 이 차이가 곧 SELF 문제의 유무다. 위임이면 내부 객체가 래퍼를 다시 부를 수 있다.

**equals 규약과 컴포지션.** 아이템 10에서 "구체 클래스를 확장해 값을 추가하면서 `equals` 규약을 지킬 방법은 없다"고 했다. 거기서도 우회책은 이 아이템과 같다. `ColorPoint`가 `Point`를 상속하지 말고 `Point`를 `private` 필드로 가진 뒤, `asPoint()` 뷰 메서드를 공개하는 것이다. `equals` 문제도 결국 상속이 만든 문제다.

**래퍼를 식별자(identity)로 쓰는 경우.** 래퍼와 내부 객체는 서로 다른 객체다. `ForwardingSet`은 `equals`/`hashCode`를 내부 `s`에 전달하므로 내용 비교는 대칭적으로 동작한다. 하지만 `wrapper == s`는 `false`이고, `IdentityHashMap`이나 `synchronized (wrapper)`처럼 객체 식별자로 동작하는 코드에서는 두 객체가 다르게 취급된다. 한쪽은 래퍼로, 다른 쪽은 원본으로 락을 잡으면 서로 배제되지 않는다.

**원본 참조가 새어 나가면 래퍼가 무력해진다.** `new InstrumentedSet<>(existing)`으로 감싼 뒤 다른 코드가 `existing`에 직접 `add`하면 계측되지 않는다. 래퍼가 불변식을 책임지려면 원본을 생성자 안에서 직접 만들거나(`new InstrumentedSet<>(new HashSet<>())`), 원본 참조를 버려야 한다. `Collections.unmodifiableSet`이 "뷰"일 뿐 진짜 불변이 아닌 이유도 같다.

---

## 핵심 키워드

- [컴포지션과 전달](../Keywords/composition.md) — 기존 객체를 필드로 두고 호출을 넘기는 재사용 방식
- [데코레이터 패턴](../Keywords/decorator-pattern.md) — 같은 인터페이스를 구현한 래퍼로 기능을 덧씌우는 구조
- [취약한 기반 클래스 문제](../Keywords/fragile-base-class.md) — 상위 클래스의 자기사용과 새 메서드가 하위 클래스를 깨뜨리는 현상
- [동적 디스패치](../Keywords/dynamic-dispatch.md) — `super.addAll` 안에서도 재정의한 `add`가 불리는 이유
- [캡슐화](../Keywords/encapsulation.md) — 상속이 깨뜨리는 원칙

## 함께 보면 좋은 아이템

- **아이템 19. 상속을 고려해 설계하고 문서화하라. 그러지 않았다면 상속을 금지하라** — 이 아이템의 뒷면이다. 1단계 문제 (a)의 자기사용을 상위 클래스 작성자가 문서화(`@implSpec`)하면 상속이 안전해지고, 그럴 여력이 없으면 `final`이나 private 생성자로 막으라는 내용이다.
- **아이템 20. 추상 클래스보다는 인터페이스를 우선하라** — 2단계의 래퍼는 `Set`이라는 인터페이스가 있어서 가능했다. 인터페이스가 있으면 래퍼 클래스로 기능을 안전하게 덧붙일 수 있다는 논리가 20에서 이어진다.
- **아이템 21. 인터페이스는 구현하는 쪽을 생각해 설계하라** — Java 21 보완의 "default 메서드는 래퍼도 흔든다"를 자세히 다룬다. `SynchronizedCollection`과 `removeIf` 사례가 여기 나온다.
- **아이템 10. equals는 일반 규약을 지켜 재정의하라** — `ColorPoint` 문제의 우회책이 "상속 대신 컴포지션"이다. 심화의 첫 번째 문단과 연결된다.

(아이템 번호는 3판 한국어판 기준으로 기억하고 있으나, 20·21은 책에서 한 번 확인해 주면 확실하다.)

## 확인 질문

2단계의 `ForwardingSet`에서 `addAll` 한 메서드만 아래처럼 바꿨다. `s.addAll(c)`로 넘기는 대신 `add`를 반복 호출한다. 이 상태에서 `new InstrumentedSet<>(new HashSet<>())`에 `addAll`로 원소 3개를 넣으면 `getAddCount()`는 얼마가 될까? 컴포지션을 썼는데도 그렇게 되는 이유는 무엇일까?

```java
// ForwardingSet 안에서 이 메서드만 바꿨다
public boolean addAll(Collection<? extends E> c) {
    boolean changed = false;
    for (E e : c) changed |= add(e);   // s.addAll(c) 대신
    return changed;
}
```
