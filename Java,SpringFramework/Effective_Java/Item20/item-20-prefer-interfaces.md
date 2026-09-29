# 아이템 20. 추상 클래스보다는 인터페이스를 우선하라

## 한 줄 결론

타입을 정의할 때는 추상 클래스가 아니라 인터페이스로 정의해야 한다. 추상 클래스로 정의한 타입은 그 클래스를 상속한 클래스만 구현할 수 있는데, 자바 클래스는 하나만 상속할 수 있으므로 타입을 붙일 수 있는 자리가 계층구조의 한 곳으로 묶여 버린다. 인터페이스는 기존 클래스 어디에든 `implements` 한 줄로 덧붙일 수 있다.

구현 코드를 공유하고 싶다면 인터페이스를 버릴 것이 아니라, 인터페이스와 골격 구현(skeletal implementation) 추상 클래스를 함께 제공하면 된다. 타입은 인터페이스가 정의하고, 추상 클래스는 구현을 돕는 도구로만 쓴다. 이렇게 하면 두 방식의 장점을 모두 얻는다.

---

## 신입 눈높이 설명

"A는 신분, B는 자격증"으로 대조하면 이렇다. 추상 클래스는 "어느 집안 출신인가"라는 신분이다. 태어날 때 한 번 정해지고 두 집안에 동시에 속할 수 없다. 인터페이스는 자격증이다. 이미 어느 집안 출신이든 상관없이 필요한 사람이 나중에 따면 되고, 여러 개를 동시에 가질 수 있다.

아래 1~4는 이 차이에서 나오는 장점들이고, 5~6은 "그래도 구현을 공유하고 싶을 때"의 방법이다.

---

# 1. 기존 클래스에도 손쉽게 새로운 인터페이스를 구현해 넣을 수 있다

## 신입 눈높이 설명

인터페이스라면 기존 클래스에 `implements`를 추가하고 메서드를 구현하면 끝이다. 책은 `Comparable`, `Iterable`, `AutoCloseable`이 추가됐을 때 수많은 기존 JDK 클래스가 이 방식으로 새 인터페이스를 구현해 넣었다고 설명한다.

추상 클래스는 그렇게 할 수 없다. 기존 클래스는 이미 다른 클래스를 상속하고 있을 가능성이 높다. 두 클래스가 같은 추상 클래스를 확장하게 하려면, 그 추상 클래스를 두 클래스의 공통 조상 위에 끼워 넣어야 한다. 그러면 그 조상의 모든 자손이 새 타입이 된다. 책의 표현대로 "그렇게 하는 것이 적절하지 않은 상황에서도 강제로" 말이다.

## 원리 설명

클래스 파일 구조(JVMS 4.1의 `ClassFile`)에는 상위 클래스를 가리키는 `super_class` 항목이 하나뿐이고, 인터페이스는 `interfaces[]` 배열로 여러 개를 가진다. 언어 규칙이 아니라 바이너리 포맷 수준에서 "상위 클래스는 하나, 인터페이스는 여럿"이 정해져 있는 것이다.

그래서 추상 클래스로 정의한 타입의 구현체가 되려면 `super_class` 한 자리를 그 추상 클래스(또는 그 자손)가 차지해야 한다. 이미 다른 클래스를 상속한 클래스에게 남은 방법은 계층 위쪽에 끼워 넣는 것뿐이고, 그 순간 `instanceof`와 타입 검사는 새 타입이 필요 없는 형제 클래스들까지 한꺼번에 통과시킨다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

외부 정산 시스템으로 주문을 CSV로 내보내는 기능을 새로 만들었다. 공통 구현(기본 `csvRow`)을 넣고 싶어서 추상 클래스로 정의했다.

```java
abstract class AbstractExportable {
    String csvRow() { return toString(); }        // 기본 구현
}

// Order 는 이미 BaseEntity 를 상속한다
// class Order extends BaseEntity, AbstractExportable { }
// 컴파일 에러: '{' expected
// → 끼워 넣을 자리는 공통 조상 BaseEntity 위밖에 없다
abstract class BaseEntity extends AbstractExportable { final long id; ... }

class Order extends BaseEntity { @Override String csvRow() { return id + "," + amount; } }
class User  extends BaseEntity { /* 비밀번호 해시가 있고, toString 이 모든 필드를 찍는다 */ }
```

### ② 무엇이 문제인가

```java
for (Object e : allEntities)
    if (e instanceof AbstractExportable)           // User 도 true
        rows.add(((AbstractExportable) e).csvRow());
// 출력: 1,30000
//       User(id=2, email=kim@example.com, passwordHash=$2a$10$Xyz...)
```

- 내보내기 기능을 만든 사람은 `Order`만 테스트했다. `csvRow`를 재정의했으니 결과가 정확했다.
- 몇 주 뒤 야간 배치가 "내보낼 수 있는 엔티티 전부"를 모으도록 바뀐다. `User`는 `BaseEntity`를 상속한다는 이유만으로 `AbstractExportable`이 되어 있다.
- `User`는 `csvRow`를 재정의하지 않았으므로 기본 구현인 `toString()`이 쓰인다. 디버깅용 `toString`(Lombok `@ToString`과 같은 형태)이 비밀번호 해시까지 찍는다.
- 컴파일 에러 없음. 런타임 예외 없음. CSV 한 줄이 외부 정산 시스템으로 나갔을 뿐이다. `User` 클래스 작성자는 내보내기 기능이 생긴 줄도 모른다. 원인(`BaseEntity`에 끼워 넣은 한 줄)과 증상(외부 시스템의 CSV)이 서로 다른 팀, 다른 시스템에 있다.

근본 원인은 하나다. **추상 클래스는 "필요한 클래스에만" 붙일 수 없어서, 공통 조상에 붙이는 순간 그 타입이 필요 없는 자손들까지 그 타입이 된다.**

### ③ 개선된 코드

```java
interface Exportable {
    String csvRow();
}

abstract class BaseEntity { final long id; ... }             // 기존 계층은 그대로

class Order extends BaseEntity implements Exportable {       // 필요한 클래스에만 붙인다
    @Override public String csvRow() { return id + "," + amount; }
}
class User extends BaseEntity { ... }                        // Exportable 이 아니다
```

이제 배치는 `Order`만 내보낸다. 게다가 같은 실수를 컴파일러가 잡아 준다.

```java
static void download(List<? extends Exportable> rows) { ... }
download(users);
// 컴파일 에러: incompatible types: List<User> cannot be converted to List<? extends Exportable>
```

`Item20Problem.java`가 ①과 ③을 나란히 실행해 이 차이를 보여 준다.

## 버전 표기

- 인터페이스 구현, `instanceof`, 한정적 와일드카드(`List<? extends Exportable>`): Java 5+. 예제 전체가 Java 7 레거시에서 그대로 컴파일된다.
- `Comparable`(Java 1.2), `Iterable`(Java 5), `AutoCloseable`(Java 7)이 기존 클래스에 사후 적용된 대표 사례다.

## 심화 / 예외 상황

**"기본 구현이 필요해서 추상 클래스로 했다"는 이유는 Java 8 이후로 약해졌다.** ①이 추상 클래스를 고른 이유는 `csvRow`의 기본 구현이었다. Java 8+에서는 인터페이스의 default 메서드로 기본 구현을 줄 수 있다(5번 항목). 다만 ①의 기본 구현(`toString()` 그대로 쓰기) 자체도 위험하다. 새 타입의 필수 동작에 기본 구현을 주면, 재정의를 잊은 구현체가 조용히 틀린 결과를 낸다. 필수 동작은 추상 메서드로 두는 편이 안전하다.

---

# 2. 인터페이스는 믹스인(mixin) 정의에 안성맞춤이다

## 신입 눈높이 설명

믹스인은 클래스가 구현할 수 있는 타입으로, "주된 타입 외에도 특정 선택적 행위를 제공한다"고 선언하는 효과를 준다. 책의 예는 `Comparable`이다. `String`의 주된 정체성은 문자열이지만, `Comparable`을 구현함으로써 "순서를 정할 수 있다"는 성질을 덧붙인다(mix in).

## 원리 설명

믹스인은 대상 타입의 주된 기능에 선택적 기능을 "혼합"한다는 뜻이다. 1번의 원리 그대로, 추상 클래스는 `super_class` 한 자리를 차지하므로 기존 클래스에 덧붙일 수 없다. 클래스 계층구조에는 믹스인을 삽입하기에 합리적인 위치가 없다. ③의 `Exportable`이 바로 믹스인이다. `Order`의 주된 타입은 여전히 `BaseEntity`이고, `Exportable`은 "CSV로 내보낼 수 있다"는 성질만 덧붙인다.

3단 대비는 1번과 같은 구조라 따로 쓰지 않는다. 1번의 ①이 "믹스인을 추상 클래스로 만들려다 실패한 사례"이기도 하다.

## 버전 표기

- `Comparable<T>`: Java 1.2+(제네릭 형태는 Java 5+). Java 7 레거시에서도 그대로 사용 가능하다.

---

# 3. 인터페이스로는 계층구조가 없는 타입 프레임워크를 만들 수 있다

## 신입 눈높이 설명

현실의 개념 중에는 계층으로 딱 나눠지지 않는 것이 많다. 가수(`Singer`)와 작곡가(`Songwriter`)가 있으면, 둘 다인 싱어송라이터도 있다. 인터페이스라면 둘을 모두 구현하거나, 둘을 확장한 제3의 인터페이스를 만들면 된다.

## 원리 설명

같은 구조를 클래스로 만들면 가능한 모든 조합마다 별도 클래스를 정의해야 한다. 속성이 n개면 조합은 2ⁿ개다. 책은 이를 조합 폭발(combinatorial explosion)이라고 부른다. 공통 기능을 정의해 둔 타입이 없으니 매개변수 타입만 다른 메서드도 수없이 생긴다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
abstract class Singer     { abstract AudioClip sing(Song s); }
abstract class Songwriter { abstract Song compose(int chartPosition); }

// 둘 다 할 수 있지만 하나만 상속할 수 있다 — Singer 를 골랐다
class SingerSongwriter extends Singer {
    AudioClip sing(Song s) { ... }
    Song compose(int chartPosition) { ... }          // Songwriter 의 메서드를 "흉내"만 냈다
}
```

### ② 무엇이 문제인가

```java
static void publish(Songwriter writer) { ... }       // 작곡가의 곡을 등록하는 기존 API
publish(new SingerSongwriter());
// 컴파일 에러: incompatible types: SingerSongwriter cannot be converted to Songwriter
```

`compose` 메서드가 있어도 타입은 `Songwriter`가 아니다. 결국 `publish(SingerSongwriter)` 오버로드를 하나 더 만들거나, `SingerSongwriter`를 감싸는 `Songwriter` 어댑터를 만들어야 한다. 속성이 하나 늘 때마다(연주자 `Performer`, 편곡자 `Arranger`...) 이런 우회 코드가 조합 수만큼 늘어난다.

근본 원인은 하나다. **"가수이면서 작곡가"라는 관계를 단일 상속 계층으로는 표현할 수 없다.**

### ③ 개선된 코드

책의 예제 그대로다.

```java
public interface Singer {
    AudioClip sing(Song s);
}

public interface Songwriter {
    Song compose(int chartPosition);
}

public interface SingerSongwriter extends Singer, Songwriter {
    AudioClip strum();
    void actSensitive();
}
```

`SingerSongwriter` 구현체는 `publish(Songwriter)`에도, `sing`을 요구하는 곳에도 그대로 넘어간다. 게다가 새 메서드(`strum`, `actSensitive`)까지 추가할 수 있다.

## 버전 표기

- 인터페이스 다중 확장(`extends Singer, Songwriter`): Java 1.0+. Java 7 레거시에서도 그대로 사용 가능하다.

## 심화 / 예외 상황

**두 인터페이스의 default 메서드가 겹치면 컴파일러가 선택을 강요한다.** `(Java 8+)` `Singer`와 `Songwriter`가 같은 시그니처의 default 메서드 `perform()`을 각자 갖고 있으면, 둘을 모두 구현하는 클래스는 컴파일되지 않는다.

```java
class SingerSongwriterImpl implements Singer, Songwriter { }
// 컴파일 에러: types Singer and Songwriter are incompatible;
//   class SingerSongwriterImpl inherits unrelated defaults for perform() from types Singer and Songwriter

// 해결: 직접 재정의하고, 필요하면 한쪽을 명시적으로 부른다
@Override public String perform() { return Singer.super.perform(); }
```

C++의 다이아몬드 문제처럼 "어느 쪽이 불릴지 모르는" 상황은 생기지 않는다. 자바는 모호하면 컴파일을 거부하기 때문이다. 인터페이스에는 인스턴스 필드가 없어서 상태가 두 벌 생기는 문제도 없다.

---

# 4. 래퍼 클래스 관용구와 함께 사용하면 기능을 향상시키는 안전하고 강력한 수단이 된다

## 원리 설명

아이템 18의 `ForwardingSet`/`InstrumentedSet`은 `Set`이 인터페이스였기 때문에 가능했다. 래퍼가 원본과 같은 타입이 되려면 원본이 구현한 타입을 래퍼도 구현해야 하는데, 그 타입이 추상 클래스였다면 래퍼는 그 추상 클래스를 상속해야 한다. 그러면 아이템 18에서 피하려던 상속으로 되돌아간다.

타입을 추상 클래스로 정의하면 그 타입에 기능을 추가하는 방법은 상속뿐이다. 상속해서 만든 클래스는 래퍼 클래스보다 활용도가 떨어지고 깨지기는 더 쉽다. 3단 대비는 아이템 18 문서의 1단계(`InstrumentedHashSet`)와 2단계(`InstrumentedSet`)가 그대로 이 항목의 ①과 ③이다.

## 버전 표기

- 래퍼 클래스 관용구: Java 1.0+. Java 7 레거시에서도 그대로 사용 가능하다.

---

# 5. 디폴트 메서드로 구현을 제공할 수 있다 — 단, 제약이 있다

## 신입 눈높이 설명

`(Java 8+)` 인터페이스 메서드 중 구현 방법이 명백한 것이 있다면 default 메서드로 기본 구현을 줄 수 있다. 책의 예는 `Collection.removeIf`다. default 메서드를 제공할 때는 상속하려는 사람을 위해 `@implSpec`으로 문서화해야 한다(아이템 19).

## 원리 설명

default 메서드는 인터페이스의 클래스 파일에 본문을 가진 메서드로 들어간다. 구현 클래스가 재정의하지 않으면 `invokeinterface`(또는 `invokevirtual`)의 메서드 선택 과정에서 인터페이스의 default 구현이 골라진다. 선택 규칙은 두 가지다. 클래스(또는 상위 클래스)에 구현이 있으면 그것이 이긴다. 인터페이스끼리 겹치면 더 구체적인(하위) 인터페이스의 것이 이긴다. 그래도 모호하면 3번 심화처럼 컴파일 에러다.

책이 드는 제약은 다음과 같다.

- `equals`, `hashCode` 같은 `Object`의 메서드는 default 메서드로 제공할 수 없다. "클래스가 이긴다" 규칙 때문에 모든 클래스가 `Object`에서 물려받은 구현이 항상 이겨서, 그런 default는 절대 호출될 수 없다. 그래서 컴파일러가 막는다.
- 인터페이스는 인스턴스 필드를 가질 수 없고, `public`이 아닌 정적 멤버도 가질 수 없다(단, `private` 정적 메서드는 예외).
- 내가 만들지 않은 인터페이스에는 default 메서드를 추가할 수 없다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
public interface Point {
    int x();
    int y();

    // 모든 구현체가 같은 방식으로 비교하도록 인터페이스에서 정해 두고 싶었다
    default boolean equals(Object o) { ... }
    // 컴파일 에러: default method equals in interface Point overrides a member of java.lang.Object
}
```

### ② 무엇이 문제인가

이 경우는 컴파일 에러로 바로 막히므로 조용한 실패는 없다. 문제는 그 다음 선택이다. 에러를 피하려고 `Point`를 추상 클래스로 바꾸면 `equals`는 공유되지만, 1번 항목의 문제가 그대로 돌아온다. 이미 다른 클래스를 상속한 클래스는 `Point`가 될 수 없다.

### ③ 개선된 코드

타입은 인터페이스로 두고, `equals`/`hashCode` 같은 공통 구현은 6번의 골격 구현 추상 클래스에 넣는다. 구현체가 골격 구현을 상속할 수 있으면 상속하고, 이미 다른 클래스를 상속한 구현체는 인터페이스만 구현한다. 두 선택지가 모두 열려 있다는 것이 핵심이다.

## 버전 표기

- default 메서드: Java 8+. 인터페이스 `static` 메서드: Java 8+. 인터페이스 `private` 메서드: Java 9+.
- Java 7 레거시에는 default 메서드가 없다. 대안은 6번의 골격 구현 추상 클래스다. 동작 차이가 있다. default 메서드는 인터페이스만 구현해도 따라오지만, 골격 구현은 그 클래스를 상속해야만 쓸 수 있다. 이미 다른 클래스를 상속한 구현체는 6번의 "시뮬레이트한 다중 상속"으로 우회한다.

## 심화 / 예외 상황

**기존 인터페이스에 default 메서드를 추가하는 일은 위험하다.** 새 default 메서드는 기존 구현체의 불변식을 모른 채 모든 구현체에 끼어든다. 아이템 21이 이 주제를 따로 다룬다.

---

# 6. 골격 구현 — 인터페이스와 추상 클래스의 장점을 모두 취한다

## 신입 눈높이 설명

인터페이스로는 타입을 정의하고, 필요하면 default 메서드 몇 개도 제공한다. 골격 구현 클래스는 나머지 메서드들까지 구현한다. 그러면 골격 구현을 확장하기만 해도 인터페이스 구현에 필요한 일이 대부분 끝난다. 템플릿 메서드 패턴이다.

관례상 인터페이스 이름이 `Interface`면 골격 구현은 `AbstractInterface`로 짓는다. `AbstractCollection`, `AbstractSet`, `AbstractList`, `AbstractMap`이 컬렉션 인터페이스의 골격 구현이다.

## 원리 설명

책의 첫 예제는 `AbstractList`를 이용한 `int` 배열 어댑터다. 메서드 세 개만 구현했는데 완전한 `List`가 된다.

```java
static List<Integer> intArrayAsList(int[] a) {
    Objects.requireNonNull(a);

    // 다이아몬드 연산자를 이렇게 사용하는 건 자바 9부터 가능하다
    return new AbstractList<>() {
        @Override public Integer get(int i) {
            return a[i];                          // 오토박싱
        }

        @Override public Integer set(int i, Integer val) {
            int oldVal = a[i];
            a[i] = val;                           // 오토언박싱
            return oldVal;                        // 오토박싱
        }

        @Override public int size() {
            return a.length;
        }
    };
}
```

`contains`, `indexOf`, `iterator`, `subList`, `equals`, `hashCode`, `toString`은 모두 `AbstractList`가 `get`과 `size`로 구현해 둔다. 이 자기사용은 아이템 19에서 말한 대로 `@implSpec`으로 문서화되어 있어서, 하위 클래스는 무엇을 구현해야 하는지 안다.

골격 구현을 작성하는 절차도 책에 있다. 인터페이스에서 다른 메서드의 구현에 쓰이는 기반 메서드들을 고른다. 이 기반 메서드들이 골격 구현에서는 추상 메서드가 된다. 기반 메서드들로 직접 구현할 수 있는 메서드는 인터페이스의 default 메서드로 제공한다. 단, `equals`/`hashCode` 같은 `Object` 메서드는 default로 제공할 수 없으므로 골격 구현 클래스에 넣는다. 나머지 메서드도 골격 구현 클래스에 작성한다.

## 3단 대비

책의 두 번째 예제인 `Map.Entry`의 골격 구현으로 대비한다.

### ① 흔히 쓰는(나쁜) 코드

```java
// Map.Entry 를 직접 구현했다 — 필요한 메서드만 채우면 컴파일은 된다
final class RawEntry<K, V> implements Map.Entry<K, V> {
    private final K key;
    private final V value;
    public K getKey()   { return key; }
    public V getValue() { return value; }
    public V setValue(V v) { throw new UnsupportedOperationException(); }
    // equals, hashCode, toString 은 구현하지 않았다
}
```

### ② 무엇이 문제인가

`Map.Entry`의 명세는 `equals`와 `hashCode`의 계산 방식까지 정해 두었다. `RawEntry`는 `Object`의 동일성 비교를 그대로 쓰므로 이 명세를 어긴다. 그런데 어기는 방식이 교묘해서 대부분의 코드는 멀쩡히 돈다.

```java
Map.Entry<String, Integer> jdk = Map.entry("a", 1);
RawEntry<String, Integer>  raw = new RawEntry<>("a", 1);

jdk.equals(raw);                      // true  ← JDK 엔트리는 명세대로 getKey/getValue 로 비교한다
raw.equals(jdk);                      // false ← 대칭성 위반
map.entrySet().contains(raw);         // true  ← HashMap 도 raw 의 getKey/getValue 를 읽어 비교한다

Set<Map.Entry<String, Integer>> seen = new HashSet<>();
seen.add(jdk);
seen.contains(raw);                   // false ← HashSet 은 raw 의 hashCode 로 버킷을 찾는다
seen.add(raw); seen.add(new RawEntry<>("a", 1));   // 크기 3 — 같은 ("a", 1)이 세 번 들어간다
```

- 단위 테스트는 `map.entrySet().contains(raw)`로 검증해서 통과한다. JDK 쪽이 명세대로 비교해 주기 때문이다.
- 운영에서 두 데이터 소스의 키-값 쌍을 `HashSet<Map.Entry>`에 모아 중복을 제거하는 코드를 만나는 순간, 중복이 하나도 제거되지 않는다.
- 로그에는 `RawEntry@24269709` 같은 해시코드만 찍혀서, 어떤 값이 중복됐는지조차 보이지 않는다.
- 컴파일 에러 없음. 런타임 예외 없음. 비교 방향에 따라 결과가 달라지므로 재현도 들쭉날쭉하다.

근본 원인은 하나다. **인터페이스 명세가 요구하는 공통 구현(`equals`/`hashCode`/`toString`)을 구현체마다 새로 짜게 만들면, 그중 하나는 반드시 빠뜨리거나 틀린다.**

### ③ 개선된 코드

책의 `AbstractMapEntry`다. 명세가 요구하는 공통 구현을 한 번만 작성해 두고, 구현체는 기반 메서드만 채운다.

```java
// 골격 구현 클래스
public abstract class AbstractMapEntry<K, V> implements Map.Entry<K, V> {
    // 변경 가능한 엔트리는 이 메서드를 반드시 재정의해야 한다
    @Override public V setValue(V value) {
        throw new UnsupportedOperationException();
    }

    // Map.Entry.equals 의 일반 규약을 구현한다
    @Override public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Map.Entry)) return false;
        Map.Entry<?, ?> e = (Map.Entry) o;
        return Objects.equals(e.getKey(), getKey())
                && Objects.equals(e.getValue(), getValue());
    }

    // Map.Entry.hashCode 의 일반 규약을 구현한다
    @Override public int hashCode() {
        return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
    }

    @Override public String toString() {
        return getKey() + "=" + getValue();
    }
}

// 구현체는 기반 메서드 두 개만 채우면 된다
final class Entry<K, V> extends AbstractMapEntry<K, V> {
    private final K key;
    private final V value;
    public K getKey()   { return key; }
    public V getValue() { return value; }
}
```

`Map.Entry`는 `getKey`, `getValue`, (선택적으로) `setValue`가 기반 메서드다. `equals`/`hashCode`는 `Object` 메서드라서 default로 줄 수 없으니 골격 구현에 넣었다. 위 ②의 호출은 모두 양방향 `true`가 되고, `HashSet`은 중복을 제거한다. 이 동작은 직접 실행해서 확인했다.

## 시뮬레이트한 다중 상속과 단순 구현

골격 구현의 장점은 "상속할 수 있으면 편하고, 상속할 수 없어도 막히지 않는다"는 데 있다. 구조상 골격 구현을 확장하지 못하는 클래스는 인터페이스를 직접 구현하면 된다. 이때도 인터페이스의 default 메서드 혜택은 그대로 받는다.

또 골격 구현을 확장한 `private` 내부 클래스를 만들고, 인터페이스 메서드 호출을 그 내부 클래스 인스턴스에 전달할 수도 있다. 아이템 18의 래퍼 클래스와 비슷한 이 방식을 책은 시뮬레이트한 다중 상속(simulated multiple inheritance)이라고 부른다.

```java
// 이미 BaseEntity 를 상속해서 AbstractMapEntry 를 상속할 수 없는 클래스
class Setting extends BaseEntity implements Map.Entry<String, String> {
    private final Entry<String, String> delegate;   // 골격 구현을 확장한 내부 클래스 인스턴스

    public String getKey()   { return delegate.getKey(); }
    public String getValue() { return delegate.getValue(); }
    public String setValue(String v) { return delegate.setValue(v); }
    @Override public boolean equals(Object o) { return delegate.equals(o); }
    @Override public int hashCode() { return delegate.hashCode(); }
}
```

단순 구현(simple implementation)은 골격 구현의 작은 변종이다. 상속을 위해 인터페이스를 구현한 것이지만 추상 클래스가 아니다. 그대로 써도 되고 필요에 맞게 확장해도 된다. 책의 예는 `AbstractMap.SimpleEntry`다.

## 버전 표기

- `AbstractList`, `AbstractCollection`, `AbstractMap`: Java 1.2+. `AbstractMap.SimpleEntry`/`SimpleImmutableEntry`: Java 6+. 모두 Java 7 레거시에서 그대로 쓸 수 있다.
- 익명 클래스에 다이아몬드 연산자(`new AbstractList<>() { ... }`): Java 9+. Java 7·8에서는 `new AbstractList<Integer>() { ... }`로 타입 인자를 적어야 한다. Java 8로 컴파일하면 `cannot infer type arguments for AbstractList<E>` 에러가 난다.
- `Objects.equals`/`Objects.hashCode`/`Objects.requireNonNull`: Java 7+.
- `Map.entry(k, v)`: Java 9+. 불변 엔트리를 만들 때 직접 `Map.Entry`를 구현할 필요 자체가 줄었다. Java 7·8에서는 `new AbstractMap.SimpleImmutableEntry<>(k, v)`를 쓴다.

## 심화 / 예외 상황

**골격 구현은 상속용 클래스다.** 아이템 19의 문서화 지침을 모두 따라야 한다. `AbstractList`가 `get`/`size`를 어떻게 쓰는지 `@implSpec`으로 밝히는 이유가 이것이다.

**`Arrays.asList(int[])`의 함정.** `intArrayAsList`가 필요한 이유이기도 하다. `Arrays.asList(new int[]{1, 2, 3})`는 `List<Integer>`가 아니라 원소가 배열 하나인 `List<int[]>`를 돌려준다. 가변인수 `T...`의 `T`가 기본 타입이 될 수 없어서 `int[]` 전체가 원소 하나가 되기 때문이다.

---

# 3판(Java 9 기준) 이후 — 현재(Java 21) 시점의 보완

## sealed 인터페이스 — 구현체를 통제하면서 인터페이스의 유연성은 유지한다

`(Java 17+)` 인터페이스의 약점 하나는 "누구나 구현할 수 있다"는 것이다. 그래서 `switch`로 모든 구현체를 빠짐없이 처리했는지 컴파일러가 확인해 줄 수 없었다. `sealed` 인터페이스는 구현체 목록을 고정한다.

```java
public sealed interface Payment permits Card, BankTransfer { long amount(); }
public record Card(long amount, String cardNo) implements Payment { }
public record BankTransfer(long amount, String account) implements Payment { }

// (Java 21+) switch 패턴 매칭 — 구현체를 빠뜨리면 컴파일 에러
String describe(Payment p) {
    return switch (p) {
        case Card c         -> "카드 " + c.amount();
        case BankTransfer b -> "계좌이체 " + b.amount();
    };
}
```

`sealed`는 추상 클래스에도 붙일 수 있지만, 인터페이스에 붙이면 레코드가 구현체가 될 수 있다. 레코드는 다른 클래스를 상속할 수 없고 인터페이스만 구현할 수 있기 때문이다. 이 아이템의 결론이 Java 16 이후에는 더 강해졌다. 타입을 추상 클래스로 정의하면 레코드는 그 타입이 될 수 없다.

단, `sealed`는 1번 항목의 장점("기존 클래스에도 손쉽게 구현해 넣을 수 있다")을 일부러 포기하는 선택이다. 구현체를 모두 알고 통제해야 하는 도메인 모델에 쓰고, 외부에서 구현할 API 타입에는 쓰지 않는다.

Java 7~16 대안: `sealed` 대신 인터페이스와 `instanceof` 분기를 쓴다. 구현체를 빠뜨렸을 때 컴파일러가 알려 주지 않으므로 마지막 `else`에서 예외를 던져 런타임에라도 드러나게 한다.

## default 메서드가 골격 구현을 대체하지는 못한다

Java 8에서 default 메서드가 생기면서 "이제 골격 구현 추상 클래스는 필요 없다"고 생각하기 쉽다. 5번 원리에서 본 것처럼 그렇지 않다. `equals`/`hashCode`/`toString`은 default로 줄 수 없고, 인스턴스 필드(상태)도 인터페이스에 둘 수 없다. 그래서 Java 21의 JDK에도 `AbstractList`, `AbstractMap` 같은 골격 구현이 그대로 남아 있다. default 메서드는 골격 구현의 역할 중 "상태 없이 기반 메서드로 구현할 수 있는 부분"만 가져갔다.

## 추상 클래스가 여전히 맞는 경우

인터페이스로 타입을 정의한 뒤에도 추상 클래스가 필요한 곳이 있다. 인스턴스 필드(상태)를 공유해야 할 때, `protected` 메서드나 생성자로 하위 클래스에게만 도구를 열어야 할 때, `equals`/`hashCode` 같은 `Object` 메서드의 공통 구현이 필요할 때다. 이 경우에도 추상 클래스는 타입이 아니라 "골격 구현"의 자리로 들어가야 한다. 공개 API의 매개변수·반환 타입은 인터페이스로 둔다.

---

## 핵심 키워드

- [골격 구현](../Keywords/skeletal-implementation.md) — 인터페이스와 짝을 이루는 `AbstractXxx` 추상 클래스
- [default 메서드](../Keywords/default-method.md) — 인터페이스가 구현을 갖게 된 방법과 그 제약
- [믹스인](../Keywords/mixin.md) — 주된 타입에 선택적 성질을 덧붙이는 타입
- [훅 메서드와 템플릿 메서드 패턴](../Keywords/hook-method.md) — 골격 구현이 기반 메서드로 나머지를 구현하는 구조
- [데코레이터 패턴](../Keywords/decorator-pattern.md) — 4번 항목의 래퍼 클래스 관용구

## 함께 보면 좋은 아이템

- **아이템 18. 상속보다는 컴포지션을 사용하라** — 4번 항목의 래퍼 클래스와 6번의 시뮬레이트한 다중 상속이 모두 18의 전달(forwarding) 기법이다. 인터페이스가 있어야 래퍼가 원본과 같은 타입이 될 수 있다.
- **아이템 19. 상속을 고려해 설계하고 문서화하라. 그러지 않았다면 상속을 금지하라** — 6번의 골격 구현은 상속용 클래스이므로 19의 문서화 규칙을 그대로 따라야 한다. 5번의 default 메서드도 `@implSpec`으로 문서화한다.
- **아이템 21. 인터페이스는 구현하는 쪽을 생각해 설계하라** — 5번 심화의 "기존 인터페이스에 default 메서드를 추가하는 위험"을 자세히 다룬다.
- **아이템 10. equals는 일반 규약을 지켜 재정의하라** — 6번 ②의 `RawEntry`가 깨뜨린 대칭성이 10의 규약이다. 골격 구현이 그 규약을 한 곳에서 지켜 준다.

(아이템 번호는 3판 한국어판 기준으로 기억하고 있으나, 21은 책에서 한 번 확인해 주면 확실하다.)

## 확인 질문

6번의 `AbstractMapEntry`에서 `equals`의 `instanceof` 검사를 아래처럼 바꿨다. 이 골격 구현을 상속한 `Entry("a", 1)`과 JDK의 `Map.entry("a", 1)`을 서로 비교하면 양방향 결과가 각각 어떻게 될까? 그리고 이 변경이 `Map.Entry`의 명세 관점에서 왜 문제일까?

```java
if (!(o instanceof AbstractMapEntry)) return false;   // Map.Entry 대신 자기 골격 구현으로 검사
AbstractMapEntry<?, ?> e = (AbstractMapEntry<?, ?>) o;
```
