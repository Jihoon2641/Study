# 아이템 1. 생성자 대신 정적 팩터리 메서드를 고려하라

## 한 줄 결론

2·3·4번은 사실 따로 노는 장점이 아니라 하나의 흐름이다. **정적 팩터리를 쓰는 순간 "어떤 객체를 돌려줄지"에 대한 결정권이 클라이언트에서 클래스 쪽으로 넘어온다.** 그 결정권으로 (2) 새로 안 만들고 재사용할지, (3) 어떤 구현체를 줄지, (4) 입력을 보고 그때그때 다른 구현체를 줄지를 라이브러리가 정하게 된다.

`new Foo()`는 이 셋 중 무엇도 못 한다. `new` 바이트코드는 "힙에 Foo 크기의 공간을 잡아라"라는 명령이라, **정확히 그 클래스의, 정확히 새로운 인스턴스**를 만드는 것 외에 다른 선택지가 없기 때문이다.

---

# 2. 호출될 때마다 인스턴스를 새로 생성하지 않아도 된다

## 신입 눈높이 설명

`new`는 "만들어라"는 명령이고, 정적 팩터리는 "구해와라"는 요청이다. 요청을 받은 쪽은 이미 갖고 있던 걸 줘도 되고, 새로 만들어 줘도 된다. 부르는 쪽 코드는 어느 쪽인지 알 필요가 없다.

이렇게 **인스턴스의 생성/제공을 클래스가 통제하는 클래스**를 인스턴스 통제 클래스(instance-controlled class)라고 한다.

## 원리 설명

바이트코드를 보면 차이가 분명하다.

```
new Integer(5)        →  new java/lang/Integer      // 힙 할당 확정
                         dup
                         iconst_5
                         invokespecial <init>       // 생성자 호출
 
Integer.valueOf(5)    →  iconst_5
                         invokestatic valueOf       // 그냥 메서드 호출. 할당은 없을 수도 있다
```

`new` 코드가 찍힌 이상 JVM은 무조건 힙(정확히는 TLAB)에 새 공간을 잡는다. 할당 자체는 포인터 증가라 싸지만, 젊은 세대(young generation)를 채우고 GC 횟수를 늘린다. 반면 `invokestatic`은 그냥 메서드 호출이라 캐시된 참조를 그대로 돌려줄 수 있다.

자바 라이브러리는 이미 이걸 깔고 있다. 오토박싱은 컴파일러가 `Integer.valueOf(...)`로 바꿔주는 것이지 `new Integer(...)`가 아니다(JLS 5.1.7). 그리고 명세는 `-128 ~ 127` 범위의 박싱 결과는 **반드시 캐시된 같은 인스턴스여야 한다**고 못 박고 있다.

인스턴스를 완전히 통제하면 따라오는 보너스가 있다. `**a.equals(b)`와 `a == b`가 같은 뜻이 된다.** 열거 타입(enum)이 `==` 비교로 안전한 이유가 정확히 이것이다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
// 값 객체인데 생성자를 그대로 public 으로 열어둔다
public final class Percent {
    private final int value;
 
    public Percent(int value) {   // 부를 때마다 무조건 새 객체
        if (value < 0 || value > 100)
            throw new IllegalArgumentException("0~100 이어야 함: " + value);
        this.value = value;
    }
 
    public int value() { return value; }
    // equals / hashCode 는 구현되어 있다고 하자
}
```

### ② 무엇이 문제인가

문제는 "객체가 많이 생긴다"는 성능 얘기만이 아니다. `**==` 로 비교해도 되는지 아닌지가 값에 따라 달라지는게 더 무섭다. 자바가 실제로 이 함정을 갖고 있다.

```java
Long a = 127L, b = 127L;
System.out.println(a == b);   // true   ← 캐시된 같은 인스턴스
 
Long c = 128L, d = 128L;
System.out.println(c == d);   // false  ← 캐시 범위 밖. 서로 다른 인스턴스
```

언제 터지는가가 아주 구체적이다. 결제 금액을 `Long`으로 다루면서 `if (paidAmount == orderAmount)` 라고 쓴 코드가 있다고 하자.

- 단위 테스트는 금액 100, 0, 1 같은 작은 값으로 짠다 → **전부 통과한다.**
- QA도 소액 결제로 확인한다 → **통과한다.**
- 운영에서 10,000원짜리 결제가 들어온다 → `==` 가 `false`. 금액이 같은데 "금액 불일치" 처리된다.
예외도 안 나고, 스택 트레이스도 없고, 로그에는 두 값이 똑같이 `10000`으로 찍힌다. `Integer`/`Long`이 **-128~127만 통제하는 반쪽짜리 인스턴스 통제**라서 생기는 일이다.

위 `Percent`도 마찬가지다. 생성자를 열어둔 이상 "같은 값이면 같은 인스턴스"라는 보장이 어디에도 없으니, 팀원이 `==` 로 비교하는 코드를 짜도 막을 방법이 없다.

### ③ 개선된 코드

```java
public final class Percent {
    // 0~100 전 범위를 미리 만들어 둔다. 반쪽이 아니라 완전한 통제.
    private static final Percent[] CACHE = new Percent[101];
    static {
        for (int i = 0; i <= 100; i++) CACHE[i] = new Percent(i);
    }
 
    private final int value;
 
    private Percent(int value) {   // 생성자를 막는다
        this.value = value;
    }
 
    public static Percent of(int value) {
        if (value < 0 || value > 100)
            throw new IllegalArgumentException("0~100 이어야 함: " + value);
        return CACHE[value];       // 항상 이미 있는 인스턴스
    }
 
    public int value() { return value; }
}
```

이제 `Percent.of(50) == Percent.of(50)` 이 **항상** true다. 값이 뭐든 상관없다. 나중에 캐시를 없애고 싶으면 `of` 안만 고치면 되고, 클라이언트 코드는 한 줄도 안 바뀐다.

## 버전 표기

- 위 코드 전부: Java 1.5+ (제네릭 안 쓰면 1.0+). **Java 7 레거시에서도 그대로 사용 가능.**
- `new Integer(int)`, `new Long(long)` 등 박싱 타입 생성자: **Java 9부터 `@Deprecated`, Java 16부터 제거 예정(forRemoval) 표시.** 지금은 무조건 `valueOf`를 쓴다.
- Java 7 프로젝트에서도 `Integer.valueOf`는 1.5부터 있으니 문제없다.

## 심화 / 예외 상황

- **캐시가 항상 이득은 아니다.** 미리 만들어 두는 방식(eager)은 클래스 로딩 시점에 메모리를 먹는다. `Percent` 101개는 껌값이지만, 범위가 넓거나 객체가 무거우면 오히려 손해다. 이럴 땐 `ConcurrentHashMap` 기반 지연 캐시를 쓰되, **키가 외부 입력이면 무한히 자라 메모리 누수가 된다**는 점을 반드시 계산해야 한다.
- **가변 클래스에는 절대 쓰면 안 된다.** 캐시로 인스턴스를 공유했는데 그게 가변이면, A 서비스가 바꾼 상태가 B 서비스에 그대로 보인다. 인스턴스 캐싱은 불변 클래스(immutable class)라는 전제 위에서만 성립한다.
- 정적 팩터리는 플라이웨이트 패턴(flyweight pattern)의 자바식 구현이기도 하다.

---

# 3. 반환 타입의 하위 타입 객체를 반환할 수 있다

## 신입 눈높이 설명

메서드 시그니처에는 `Cache`라고 써놓고 실제로는 `LruCache`를 돌려줘도 된다는 뜻이다. 즉 **"무엇을 준다"(인터페이스)와 "어떻게 만든다"(구현 클래스)를 분리**할 수 있다. 생성자는 이게 원천적으로 불가능하다. `new LruCache()`는 반드시 `LruCache`를 돌려주기 때문이다.

## 원리 설명

정적 팩터리의 반환 타입을 인터페이스로 잡으면, 그 인터페이스의 구현 클래스는 `**public`일 필요가 없어진다.** 이게 핵심이다.

`public`이 아닌 클래스는 API의 일부가 아니다. API가 아니면 **아무 때나 바꾸거나 지워도 호환성이 깨지지 않는다.** 자바 표준 라이브러리의 `java.util.Collections`가 이 기법의 교과서다. 컬렉션 인터페이스에 "수정 불가", "동기화" 같은 기능을 덧붙인 구현체 수십 개를 제공하면서, 그 구현 클래스들은 전부 `Collections` 안의 private 중첩 클래스로 숨겨져 있다. 클라이언트가 배워야 할 것은 `Collections.unmodifiableList()` 하나뿐이다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
// 구현 클래스가 곧 API가 되어버린다
public class LruCache<K, V> {
    public LruCache(int capacity) { ... }
    public V get(K key) { ... }
    public void put(K key, V value) { ... }
}
 
// 클라이언트
LruCache<String, User> cache = new LruCache<>(100);
```

### ② 무엇이 문제인가

6개월 뒤 요구가 들어온다. "배치 서버에서는 캐시를 꺼야 한다", "API 서버에서는 여러 스레드가 동시에 쓰니 스레드 안전한 버전이 필요하다."

`ConcurrentLruCache`를 새로 만들어서 갈아끼우려는 순간 막힌다.

```java
// 이런 시그니처가 프로젝트 안에 20곳 있다고 하자
public void warmUp(LruCache<String, User> cache) { ... }
private final LruCache<String, User> userCache;
```

**변수 타입, 필드 타입, 메서드 파라미터, 테스트의 목(mock) 선언까지 전부 구체 클래스 `LruCache`로 박혀 있다.** `ConcurrentLruCache`는 `LruCache`의 하위 타입이 아니므로 이 20곳이 전부 컴파일 에러가 난다. 결국 "이번 스프린트엔 못 한다"가 되고, 대신 `LruCache` 안에 `if (threadSafe) synchronized...` 분기를 넣는 최악의 선택을 하게 된다.

캐시를 끄는 것도 같다. `capacity == 0`이어도 `LruCache` 인스턴스는 만들어지고, 모든 메서드에 `if (capacity == 0) return null;` 이 하나씩 붙는다. 실제 캐싱 로직과 "캐시 꺼짐" 로직이 한 클래스에 뒤엉킨다.

### ③ 개선된 코드

```java
// API 는 인터페이스 하나뿐
public interface Cache<K, V> {
    V get(K key);
    void put(K key, V value);
 
    // Java 8+ : 인터페이스가 직접 정적 팩터리를 가질 수 있다
    static <K, V> Cache<K, V> ofCapacity(int capacity) {
        if (capacity == 0) return EmptyCache.instance();  // 반환 타입의 하위 타입
        return new LruCache<>(capacity);
    }
 
    static <K, V> Cache<K, V> concurrent(int capacity) {
        return new ConcurrentLruCache<>(capacity);
    }
}
 
// 구현들은 전부 package-private → API 가 아니다
class LruCache<K, V> implements Cache<K, V> { ... }
class ConcurrentLruCache<K, V> implements Cache<K, V> { ... }
final class EmptyCache<K, V> implements Cache<K, V> {
    private static final EmptyCache<?, ?> INSTANCE = new EmptyCache<>();
 
    @SuppressWarnings("unchecked")
    static <K, V> Cache<K, V> instance() { return (Cache<K, V>) INSTANCE; }
 
    public V get(K key) { return null; }
    public void put(K key, V value) { /* 아무것도 안 한다 */ }
}
```

이제 클라이언트는 `Cache<String, User> cache = Cache.ofCapacity(100);` 라고만 쓴다. 구현을 통째로 갈아엎어도, 새 구현을 추가해도 클라이언트는 재컴파일조차 필요 없다.

## 버전 표기

- **인터페이스 안의 `static` 메서드: Java 8+ / 레거시 7 프로젝트에서는 사용 불가.**
- Java 7 대안은 책에 나오는 방식 그대로 — 동반 클래스(companion class)를 만든다. `Collections`가 `Collection`/`List`의 동반 클래스인 것과 같은 구조다.

```java
// Java 7 이하: 인스턴스화 불가능한 동반 클래스에 정적 팩터리를 모은다
public final class Caches {
    private Caches() { throw new AssertionError(); }  // 인스턴스화 방지
 
    public static <K, V> Cache<K, V> ofCapacity(int capacity) {
        if (capacity == 0) return EmptyCache.instance();
        return new LruCache<K, V>(capacity);   // 다이아몬드 연산자는 Java 7+ 이므로 <K,V> 명시해도 됨
    }
}
```

- Java 8의 인터페이스 `static` 메서드는 `public`만 가능해서 보조 메서드를 숨길 수 없었다. **Java 9+ 부터 인터페이스에 `private` 메서드**를 둘 수 있어, 여러 정적 팩터리가 공유하는 검증 로직 등을 안으로 숨길 수 있다.
- 반환 타입을 `Cache`처럼 인터페이스로 잡는 습관은 `**var`(Java 10+)와 궁합이 나쁘다.** `var cache = Cache.ofCapacity(100);` 은 정적 타입이 `Cache`로 잡히니 괜찮지만, 팩터리가 구현 타입을 반환하도록 시그니처를 잘못 짜두면 `var`가 구현 타입을 그대로 추론해 버린다. 정적 팩터리의 **선언된 반환 타입**이 곧 계약이라는 점을 기억할 것.

## 심화 / 예외 상황

- 이 기법의 대가는 아이템 1의 단점 첫 번째와 정확히 맞물린다. **구현 클래스를 숨기면(package-private + private 생성자) 상속이 불가능하다.** 확장이 필요하면 상속 대신 컴포지션(composition)을 쓰라는 지침으로 이어진다.
- Java 17+ 라면 `sealed interface`로 "구현체는 이 셋뿐"임을 컴파일러에게까지 알릴 수 있다. 다만 봉인(sealed)은 허용 목록을 공개하는 셈이라, "구현을 완전히 숨긴다"는 목적과는 방향이 반대다. 목적에 맞게 골라야 한다.

---

# 4. 입력 매개변수에 따라 매번 다른 클래스의 객체를 반환할 수 있다

## 신입 눈높이 설명

3번의 자연스러운 확장이다. 3번이 "구현을 숨길 수 있다"라면, 4번은 "숨긴 김에 **상황을 보고 골라서** 줄 수 있다"이다. 클라이언트는 어떤 걸 받았는지 모르고, 알 필요도 없다.

## 원리 설명 — EnumSet 실제 코드

`EnumSet`은 `public` 생성자가 하나도 없고 정적 팩터리만 있다. JDK의 실제 코드는 이렇게 생겼다.

```java
public static <E extends Enum[[ORCA_RICH_MD:dd37fe811d4fc4ce506497df4a02c58f:inline-html:%3CE%3E]]> EnumSet[[ORCA_RICH_MD:dd37fe811d4fc4ce506497df4a02c58f:inline-html:%3CE%3E]] noneOf(Class[[ORCA_RICH_MD:dd37fe811d4fc4ce506497df4a02c58f:inline-html:%3CE%3E]] elementType) {
    Enum<?>[] universe = getUniverse(elementType);   // 해당 enum 의 상수 전체
    if (universe == null)
        throw new ClassCastException(elementType + " not an enum");
 
    if (universe.length <= 64)
        return new RegularEnumSet<>(elementType, universe);  // long 1개로 비트 관리
    else
        return new JumboEnumSet<>(elementType, universe);    // long[] 로 비트 관리
}
```

왜 64인가. `long`이 64비트라서다. 상수가 64개 이하면 원소 하나당 비트 하나를 배정해 **집합 전체를 `long` 변수 하나로** 표현할 수 있다. 그러면 `add`는 `elements |= (1L << ordinal)`, `contains`는 `(elements & (1L << ordinal)) != 0` — **비트 연산 한두 개**로 끝난다. `HashSet`의 해시 계산 + 버킷 탐색과는 비교가 안 되게 빠르고, 메모리도 8바이트다.

여기서 결정적인 게 있다. `**RegularEnumSet`과 `JumboEnumSet`은 `public`이 아니다.** `java.util` 패키지 안의 package-private 클래스다. 그래서 자바 개발팀이 "이제 Regular는 필요 없다"고 판단하면 다음 릴리스에서 그냥 지워도 세상 어느 코드도 깨지지 않는다. 반대로 "33개 이하면 `int` 하나로 관리하는 TinyEnumSet"을 추가해도 마찬가지다. **이게 4번의 진짜 가치다 — 성능 최적화가 아니라, 나중에 마음대로 바꿀 수 있는 자유.**

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

구현 선택을 클라이언트에게 떠넘기는 API다.

```java
// 두 구현을 모두 public 으로 노출하고, 고르는 건 사용자 몫
public final class SmallBitSet<E extends Enum<E>> implements Set<E> {
    private long elements = 0L;
    public SmallBitSet(Class<E> type) { ... }
    public boolean add(E e) { elements |= (1L << e.ordinal()); return ...; }
    public boolean contains(Object o) { return (elements & (1L << ((E) o).ordinal())) != 0; }
}
public final class BigBitSet<E extends Enum<E>> implements Set<E> { ... long[] ... }
 
// 클라이언트: "우리 enum 은 작으니까 Small 쓰면 되겠네"
Set<Permission> perms = new SmallBitSet<>(Permission.class);
```

### ② 무엇이 문제인가

`Permission` enum에 상수가 60개 있었다. 6개월 뒤 다른 팀원이 권한 5개를 추가해 **65개**가 된다.

- `SmallBitSet`은 그 enum이 몇 개인지 모른다. 그냥 `1L << ordinal` 을 할 뿐이다.
- 새로 추가된 65번째 상수의 `ordinal()`은 64다.
- 자바에서 `long`에 대한 시프트 연산은 **오른쪽 피연산자의 하위 6비트만 사용한다**(JLS 15.19). 즉 `1L << 64` 는 예외가 아니라 `**1L << 0`, 그러니까 `1L`이 된다.**
무슨 일이 벌어지는가:

```java
perms.add(Permission.values()[64]);   // 65번째 권한을 추가
perms.contains(Permission.values()[0]);  // → true !!  0번 권한을 준 적이 없는데
```

**컴파일 에러 없음. 런타임 예외 없음. 로그 한 줄 안 남는다.** 65번째 권한을 켰더니 0번 권한이 같이 켜진다. 권한 체크라면 이건 그대로 보안 사고다. 그리고 문제를 일으킨 사람(enum에 상수를 추가한 사람)과 코드를 짠 사람(`SmallBitSet`을 고른 사람)이 다르기 때문에, 원인 추적에 며칠이 걸린다.

근본 원인은 하나다. **"어떤 구현이 안전한가"라는 판단을 클라이언트가 하도록 API를 설계했다.** 클라이언트는 그 판단에 필요한 정보(미래의 enum 크기)를 갖고 있지 않다.

### ③ 개선된 코드

```java
Set<Permission> perms = EnumSet.noneOf(Permission.class);
```

끝이다. 상수가 60개든 65개든 640개든, 판단은 `noneOf` 안에서 매번 다시 이뤄진다. 클라이언트 코드는 한 글자도 안 바뀐다.

직접 만든다면 이런 모양이 된다.

```java
public abstract class BitSetLike<E extends Enum[[ORCA_RICH_MD:dd37fe811d4fc4ce506497df4a02c58f:inline-html:%3CE%3E]]> implements Set[[ORCA_RICH_MD:dd37fe811d4fc4ce506497df4a02c58f:inline-html:%3CE%3E]] {
    // 생성자는 package-private → 외부에서 상속/생성 불가
    BitSetLike() { }
 
    public static <E extends Enum[[ORCA_RICH_MD:dd37fe811d4fc4ce506497df4a02c58f:inline-html:%3CE%3E]]> BitSetLike[[ORCA_RICH_MD:dd37fe811d4fc4ce506497df4a02c58f:inline-html:%3CE%3E]] noneOf(Class[[ORCA_RICH_MD:dd37fe811d4fc4ce506497df4a02c58f:inline-html:%3CE%3E]] type) {
        E[] universe = type.getEnumConstants();
        // 판단은 여기서, 매 호출마다 한다
        return universe.length <= 64
                ? new SmallBitSet<>(universe)   // package-private
                : new BigBitSet<>(universe);    // package-private
    }
}
```

## 버전 표기

- 위 정적 팩터리 방식: **Java 1.5+ (제네릭·enum 필요). Java 7 레거시에서 그대로 사용 가능.** 클래스 안의 `static` 메서드라 인터페이스 `static` 메서드 제약(Java 8)에 걸리지 않는다.
- `EnumSet` 자체: Java 5+.
- **현대적인 같은 사례(Java 9+): `List.of`, `Set.of`, `Map.of`.** 인수 개수에 따라 내부적으로 서로 다른 클래스를 반환한다 — 원소가 없으면 공유 상수, 1~2개면 필드 두 개짜리 전용 클래스(`List12`), 3개 이상이면 배열 기반 클래스(`ListN`). 전부 `java.util.ImmutableCollections`의 package-private 클래스이고, 정확한 분기 기준은 JDK 버전에 따라 바뀔 수 있다. 바뀌어도 되니까 숨겨둔 것이다.
- Java 7이라면 `Collections.unmodifiableList(Arrays.asList(...))` 가 대안이지만, 이건 원본 리스트를 감싼 뷰(view)라 원본이 바뀌면 같이 바뀐다. `List.of`의 진짜 불변과는 다르다. **Java 7에서 진짜 불변을 원하면 `new ArrayList<>(원본)`으로 복사한 뒤 감싸야 한다.**

## 심화 / 예외 상황

- **분기 조건이 클라이언트 눈에 보이는 동작 차이를 만들면 안 된다.** `RegularEnumSet`과 `JumboEnumSet`은 성능만 다르고 `Set` 규약은 완전히 동일하다. 만약 반환 클래스에 따라 `null` 허용 여부나 순서 보장이 달라진다면, 그건 4번의 오용이다. 클라이언트가 디버깅하다가 미쳐버린다.
- 4번은 3번을 전제로만 성립한다. 구현 클래스가 `public`이면 클라이언트가 그 타입으로 캐스팅하거나 `instanceof`로 분기하기 시작하고, 그 순간 "나중에 바꿀 자유"가 사라진다. **숨기지 않으면 4번은 의미가 없다.**
- 여기서 한 걸음 더 나가면 장점 5번(서비스 제공자 프레임워크)이다. 반환할 클래스를 **컴파일 시점에 몰라도 되게** 만드는 것 — `JDBC`의 `DriverManager.getConnection`이 그 예다.

---

## 함께 보면 좋은 아이템

- **아이템 6. 불필요한 객체 생성을 피하라** — 장점 2번의 실전 적용편. 정적 팩터리는 "재사용할 수 있게 하는 장치"이고, 아이템 6은 "그래서 실제로 어디서 재사용해야 하는가"를 다룬다. `Boolean.valueOf` 예제가 두 아이템에 겹쳐 나온다.
- **아이템 17. 변경 가능성을 최소화하라** — 장점 2번의 인스턴스 캐싱은 **불변 클래스에서만 안전하다**는 전제를 다루는 아이템. 위 `Percent` 예제가 `final` 필드에 setter가 없는 이유가 여기 있다.
- **아이템 64. 객체는 인터페이스를 사용해 참조하라** — 장점 3번의 클라이언트 쪽 짝. 3번이 "인터페이스로 반환하라"라면 64번은 "인터페이스로 받아라"다. `LruCache cache = ...` 로 선언하면 3번의 이점이 전부 무효화된다는 걸 이 아이템이 설명한다.
- **아이템 36. 비트 필드 대신 EnumSet을 사용하라** — 장점 4번의 예시로 나온 `EnumSet`을 정면으로 다루는 아이템. 위 `SmallBitSet` 사고 시나리오가 왜 직접 비트 필드를 굴리면 안 되는지에 대한 답이기도 하다.
(아이템 번호는 3판 한국어판 기준으로 기억하고 있으나, 6·17·36·64는 책에서 한 번 확인해 주면 확실하다.)

---

## 확인 질문

위 `Cache` 예제에서, 정적 팩터리 `Cache.ofCapacity(0)`이 `EmptyCache`의 **싱글턴 인스턴스**를 반환하도록 짰다. 그런데 만약 `EmptyCache`가 "몇 번 조회됐는지" 세는 `private int missCount` 필드를 갖게 된다면 어떤 문제가 생길까? 그리고 그건 장점 2번(인스턴스 재사용)의 어떤 전제를 깨는 걸까?