# 아이템 3. private 생성자나 열거 타입으로 싱글턴임을 보증하라

## 한 줄 결론

`private` 생성자는 **자바 소스 코드로 `new`를 쓰는 길**만 막는다. 리플렉션과 직렬화는 소스 코드의 `new`를 거치지 않고 객체를 만드는 경로라서 그 벽을 그냥 지나간다. 원소가 하나뿐인 열거 타입(enum)은 **언어 명세, 리플렉션 API, 직렬화 명세가 모두 "열거 상수 외의 인스턴스는 만들지 않는다"고 약속한** 타입이라서, 방어 코드 없이도 싱글턴이 보증된다.

제목의 핵심 단어는 "보증"이다. 싱글턴을 **만드는** 방법은 세 가지 모두 된다. 하지만 **보증하는** 방법은 열거 타입 하나뿐이다. 클래스 방식은 방어 코드 두 개(생성자 가드, `readResolve`)를 사람이 빠뜨리지 않았을 때만 보증된다.

---

## 신입 눈높이 설명

싱글턴(singleton)은 인스턴스를 오직 하나만 만들 수 있는 클래스다. 무상태(stateless) 객체나, 설계상 시스템에 하나만 있어야 하는 컴포넌트에 쓴다.

`private` 생성자는 건물의 정문을 잠그는 것과 같다. 그런데 이 건물에는 입구가 둘 더 있다. 하나는 관리자 마스터키(리플렉션)로, 잠긴 문을 열고 들어간다. 다른 하나는 복제기(직렬화)로, 문을 아예 거치지 않고 바이트에서 사람을 찍어 낸다. 열거 타입은 설계도에 "이 건물에는 입구가 없다"고 적혀 있고, 마스터키와 복제기를 만드는 쪽이 그 설계도를 지키기로 약속한 건물이다.

책이 싱글턴의 단점으로 가장 먼저 짚는 것은 테스트다. 클라이언트 코드가 `Elvis.INSTANCE`를 직접 부르면, 테스트에서 이 부분을 가짜 구현(mock)으로 바꿀 방법이 없다. 싱글턴이 인터페이스를 구현하고 클라이언트가 그 인터페이스 타입으로 받을 때만 바꿔 끼울 수 있다.

---

# 1단계 — public static final 필드 방식

## ① 흔히 쓰는(나쁜) 코드

```java
public class Elvis {
    public static final Elvis INSTANCE = new Elvis();
    private Elvis() { }

    public void leaveTheBuilding() { ... }
}
```

이 코드는 책이 소개하는 정상적인 방식이다. 문제는 **"이것만으로 인스턴스가 하나라는 게 보증된다"고 믿는 것**이다.

장점도 분명하다. `public static final` 필드는 다른 객체를 가리키도록 바꿀 수 없으니 API만 봐도 싱글턴임이 드러나고, 코드가 가장 간결하다. 성능 차이도 없다. HotSpot JIT은 `static final` 필드를 상수처럼 취급해 최적화하고, 2단계의 `getInstance()` 같은 짧은 정적 메서드는 인라인하므로 두 방식의 호출 비용은 사실상 같다.

## ② 무엇이 문제인가 — 리플렉션이 두 번째 인스턴스를 만든다

```java
Constructor<Elvis> ctor = Elvis.class.getDeclaredConstructor();
ctor.setAccessible(true);           // "접근 제한 검사를 건너뛰어라"
Elvis second = ctor.newInstance();  // second != Elvis.INSTANCE
```

"누가 일부러 이런 코드를 짜겠느냐"고 생각하기 쉽다. 하지만 이 호출을 늘 하는 쪽은 공격자가 아니라 **리플렉션 기반 프레임워크**다. 실무에서 가장 흔한 경로는 스프링이다.

- 레거시 코드는 `Elvis.INSTANCE`로 싱글턴을 쓰고 있다. 단위 테스트도 이것만 쓰니 통과한다.
- 새로 합류한 팀원이 "스프링 빈으로 주입받자"며 `Elvis`에 `@Component`를 붙이고 `@Autowired Elvis elvis`로 받는다.
- 스프링은 빈을 만들 때 `private` 생성자에도 `setAccessible(true)`를 호출해 인스턴스를 만든다. **컴파일 에러 없음. 기동 시 예외 없음. 로그 한 줄 안 남는다.**
- 이제 JVM 안에 `Elvis`가 두 개다. 싱글턴에 캐시나 카운터 같은 상태가 있다면, 레거시 경로와 신규 경로가 서로 다른 상태를 보면서 운영에서 숫자가 맞지 않게 된다.

`@Component`를 붙인 사람과, 두 경로의 상태가 어긋나 버그 리포트를 받는 사람이 다르다. 원인이 된 코드는 어노테이션 한 줄이라 리뷰에서도 거의 걸리지 않는다.

근본 원인은 하나다. **`private`은 컴파일러와 JVM 링킹 단계의 규칙일 뿐, 리플렉션 API는 호출자가 요청하면 그 규칙을 끄도록 설계되어 있다.**

## 원리 — private은 누구의 규칙인가

접근 제어는 세 곳에서 따로 검사된다.

- **javac** — 소스의 `new Elvis()`를 JLS 6.6 접근 규칙으로 검사해 컴파일 에러를 낸다.
- **JVM 링킹** — 바이트코드의 `invokespecial Elvis.<init>`을 해석(resolution)할 때 접근 권한을 다시 검사한다(JVMS 5.4.4).
- **리플렉션** — `Constructor.newInstance()`는 자기 코드 안에서 "호출자가 이 멤버에 접근할 수 있는가"를 직접 검사한다. `setAccessible(true)`는 `AccessibleObject`의 `override` 플래그를 켜서 **이 검사만 건너뛰게** 한다.

리플렉션으로 만든 객체는 바이트코드의 `invokespecial`을 거치지 않으므로 앞의 두 검사와 무관하다. 실제 생성 호출은 JDK 내부의 접근자(accessor)가 수행한다. `(Java 18+, JEP 416)` 이 접근자가 메서드 핸들 기반으로 다시 구현되었지만, 접근 검사를 건너뛰는 동작은 같다.

막는 방법은 두 가지였다. 하나는 `SecurityManager`로 `ReflectPermission("suppressAccessChecks")`를 거부하는 것인데, `SecurityManager`는 Java 17에서 제거 예정이 되었고(JEP 411) 이후 버전에서 비활성화되었다. 다른 하나는 모듈 시스템인데, 이는 아래 "현재 시점의 보완"에서 다룬다. 결국 애플리케이션 코드가 직접 할 수 있는 방어는 **생성자 안에서 두 번째 호출을 거부하는 것**뿐이다.

```java
private Elvis() {
    if (INSTANCE != null) throw new IllegalStateException("두 번째 Elvis 생성 시도");
}
```

이 가드가 동작하는 이유는 클래스 초기화 순서에 있다. 리플렉션으로 생성자를 호출하면 그 전에 `Elvis` 클래스 초기화(`<clinit>`)가 먼저 끝나야 한다(JLS 12.4.1). 그래서 가드가 실행되는 시점에는 `INSTANCE`가 이미 채워져 있다. 이 가드는 `INSTANCE`를 즉시 초기화하는 방식을 전제로 한다.

---

# 2단계 — 정적 팩터리 방식

## ① 흔히 쓰는(나쁜) 코드

```java
public class Elvis implements Serializable {       // 세션에 담아야 해서 붙였다
    private static final Elvis INSTANCE = new Elvis();
    private Elvis() { }
    public static Elvis getInstance() { return INSTANCE; }
}
```

## 장점 — 책이 꼽는 세 가지

1. **API를 바꾸지 않고 싱글턴을 그만둘 수 있다.** `getInstance()` 안만 고치면 스레드별 인스턴스 등으로 바꿀 수 있고, 호출하는 코드는 그대로다. 필드 방식이면 `Elvis.INSTANCE`를 쓰는 모든 코드를 고쳐야 한다.
2. **제네릭 싱글턴 팩터리로 만들 수 있다.** 인스턴스 하나를 여러 타입에 돌려쓰는 기법으로, `Collections.emptySet()`이 대표적이다. 제네릭 타입 정보가 소거(erasure)되기 때문에 가능하다.
3. **메서드 참조를 공급자(supplier)로 쓸 수 있다.** `Supplier<Elvis> s = Elvis::getInstance;`

책은 이 장점들이 필요 없다면 1단계의 필드 방식이 낫다고 정리한다.

## ② 무엇이 문제인가 — 직렬화할 때마다 새 인스턴스가 생긴다

```java
// 세션 클러스터링이나 Redis 세션이 내부에서 하는 일
Elvis restored = (Elvis) new ObjectInputStream(in).readObject();
restored == Elvis.getInstance();   // false
```

`implements Serializable` 한 줄이면 직렬화가 된다. 그런데 역직렬화할 때마다 새 `Elvis`가 생긴다.

- 로컬 개발에서는 서버가 한 대라 세션이 메모리에만 있고, 역직렬화가 일어나지 않는다. 모든 테스트가 통과한다.
- QA 환경도 인스턴스가 하나라 통과한다.
- 운영은 서버 2대에 세션을 Redis에 저장한다. 스프링 세션의 Redis 저장소는 기본적으로 JDK 직렬화를 쓴다. 로그인은 A 서버에서, 다음 요청은 B 서버에서 처리하면 **B는 세션에서 역직렬화된 두 번째 `Elvis`를 받는다.**
- `restored == Elvis.getInstance()` 비교가 거짓이 되고, 싱글턴이 가진 상태 변경이 반영되지 않는다. **예외 없음. 로그 없음. 서버 대수가 2 이상일 때만 재현된다.**

1단계의 생성자 가드를 넣어 두었어도 이 경로는 막지 못한다. 역직렬화는 `Elvis()` 생성자를 **호출하지 않기** 때문이다.

근본 원인은 하나다. **역직렬화는 생성자와 무관한 "숨은 생성자"이고, 클래스가 이 경로를 직접 막지 않으면 인스턴스 개수를 통제할 수 없다.**

## 원리 — 역직렬화는 생성자를 부르지 않는다

OpenJDK `ObjectInputStream.readOrdinaryObject()`의 흐름을 줄여 보면 다음과 같다(버전마다 세부 코드는 다르다).

```java
ObjectStreamClass desc = readClassDesc(false);
Object obj = desc.isInstantiable() ? desc.newInstance() : null;   // (1) 객체 생성
...
readSerialData(obj, desc);                                        // (2) 스트림의 필드 값 채우기
...
if (obj != null && desc.hasReadResolveMethod()) {
    Object rep = desc.invokeReadResolve(obj);                     // (3) readResolve 가 있으면
    ...
    handles.setObject(passHandle, obj = rep);                     //     반환값으로 바꿔치기
}
return obj;
```

(1)의 `desc.newInstance()`는 `Elvis()`를 호출하지 않는다. 직렬화 명세에 따라 **상위 클래스 중 `Serializable`이 아닌 첫 번째 클래스의 기본 생성자**(여기서는 `Object()`)만 실행하고, 그 결과를 `Elvis` 타입 객체로 만든다. JDK 내부의 `ReflectionFactory.newConstructorForSerialization`이 이런 특수한 생성자를 만들어 준다. 그래서 `Elvis` 생성자 본문에 넣은 가드는 한 번도 실행되지 않는다.

(3)이 해법이 들어갈 자리다. 클래스에 `readResolve` 메서드가 있으면, 방금 만든 객체 대신 그 메서드의 반환값이 `readObject()`의 결과가 된다.

## ③ 개선된 코드 — readResolve + transient

```java
public class Elvis implements Serializable {
    private static final Elvis INSTANCE = new Elvis();
    private transient String favoriteSong = "Hound Dog";  // 인스턴스 필드는 전부 transient

    private Elvis() {
        if (INSTANCE != null) throw new IllegalStateException();   // 1단계의 리플렉션 방어
    }
    public static Elvis getInstance() { return INSTANCE; }

    // 역직렬화로 생긴 가짜 Elvis는 버리고 진짜를 반환한다. 가짜는 GC 대상이 된다.
    private Object readResolve() { return INSTANCE; }
}
```

`readResolve`만으로는 부족하고 **인스턴스 필드를 모두 `transient`로 선언해야 하는** 이유가 있다. (2) 단계에서 `transient`가 아닌 참조 필드를 채우는 동안, 조작된 바이트 스트림은 그 필드 자리에 "역직렬화 중인 가짜 `Elvis`의 참조를 몰래 저장하는 객체"를 넣을 수 있다. 그러면 (3)에서 `readResolve`가 가짜를 버려도, 공격 코드는 이미 가짜의 참조를 손에 쥐고 있다. 이 공격의 구체적인 형태는 아이템 89가 다룬다. 요점은 **`readResolve`로 인스턴스 개수를 통제하는 방식은 필드 선언 하나만 실수해도 뚫린다**는 것이고, 이것이 3단계로 넘어가는 이유다.

클래스가 `final`이 아니라면 `readResolve`의 접근 수준도 신경 써야 한다. `private`이면 하위 클래스에는 적용되지 않고, `protected`나 `public`이면 하위 클래스를 역직렬화했을 때 상위 클래스의 `INSTANCE`가 반환되어 `ClassCastException`이 날 수 있다.

## 버전 표기

- 1·2단계 코드 전부: Java 1.2+ (`readResolve`는 Java 1.2에서 도입). **Java 7 레거시에서도 그대로 사용 가능.**
- `Supplier<Elvis> s = Elvis::getInstance;`: **Java 8+ / 레거시 7 프로젝트에서는 사용 불가.** Java 7에서는 인터페이스를 직접 정의하고 익명 클래스로 구현한다.

```java
// Java 7 대안
interface ElvisProvider { Elvis get(); }

ElvisProvider provider = new ElvisProvider() {
    @Override public Elvis get() { return Elvis.getInstance(); }
};
```

- `AccessibleObject.setAccessible`: Java 1.2+. 모듈 경계에서 `InaccessibleObjectException`을 던지는 동작은 Java 9+.
- `SecurityManager`: Java 17에서 제거 예정(forRemoval, JEP 411)이 되었고, 이후 버전에서 비활성화되었다. 리플렉션 방어 수단으로 기대하면 안 된다.

---

# 3단계 — 원소가 하나뿐인 열거 타입

## ③ 개선된 코드

```java
public enum Elvis {
    INSTANCE;

    public void leaveTheBuilding() { ... }
}
```

생성자 가드도, `readResolve`도, `transient`도 없다. 그래도 리플렉션, 직렬화, `clone` 세 경로가 모두 막혀 있다. 예제 파일 `Item3Example.java`의 main이 1단계 `Elvis`는 두 경로 모두에서 뚫리고, 열거 타입은 두 경로 모두에서 막히는 것을 실제로 보여 준다.

## 원리 — javac가 만든 결과물은 사실 1단계와 같다

`enum Elvis { INSTANCE; }`를 컴파일해 `javap -p`로 열어 보면 대략 이런 모양이다.

```
final class Elvis extends java.lang.Enum<Elvis>     // 클래스 플래그에 ACC_ENUM
  public static final Elvis INSTANCE;               // 필드 플래그에 ACC_ENUM
  private static final Elvis[] $VALUES;
  private Elvis(String name, int ordinal);          // 컴파일러가 name, ordinal 매개변수를 추가
  static {
    INSTANCE = new Elvis("INSTANCE", 0);
    $VALUES = new Elvis[] { INSTANCE };
  }
```

**구조만 보면 1단계의 `public static final` 필드 방식과 같다.** 차이는 `ACC_ENUM` 플래그이고, JDK의 각 경로가 이 플래그를 보고 인스턴스 생성을 거부한다.

- **소스 코드** — JLS 8.9는 열거 타입에 열거 상수 외의 인스턴스가 없다고 규정하고, `new Elvis()`는 컴파일 에러다. 열거 타입의 생성자는 암묵적으로 `private`이다.
- **리플렉션** — JLS 8.9는 열거 타입을 리플렉션으로 인스턴스화하는 것을 금지한다고 명시한다. `Constructor.newInstance()` 구현은 `setAccessible` 여부와 상관없이 다음 검사를 먼저 한다.

  ```java
  // java.lang.reflect.Constructor (OpenJDK, 발췌)
  if ((clazz.getModifiers() & Modifier.ENUM) != 0)
      throw new IllegalArgumentException("Cannot reflectively create enum objects");
  ```

- **직렬화** — 직렬화 명세는 열거 상수를 **이름만** 기록하게 한다. 역직렬화할 때는 `Enum.valueOf(클래스, 이름)`으로 현재 JVM에 이미 있는 상수를 찾아 돌려준다. 클래스에 `readObject`, `writeObject`, `readResolve` 같은 메서드를 선언해도 열거 타입에서는 무시되고, `serialVersionUID`도 0L로 고정된다. 가짜 인스턴스를 만드는 단계 자체가 없으니 2단계의 `transient` 공격도 성립하지 않는다.
- **clone** — `Enum.clone()`은 `final`이고 `CloneNotSupportedException`을 던진다. 하위에서 재정의할 수도 없다.

즉 열거 타입의 보증은 "개발자가 방어 코드를 잘 넣었다"가 아니라 **"JDK의 모든 객체 생성 경로가 `ACC_ENUM`을 존중한다"** 는 데서 나온다.

## 한계

- **`Enum` 외의 클래스를 상속해야 하면 쓸 수 없다.** 열거 타입은 암묵적으로 `java.lang.Enum`을 상속하므로 `extends`를 쓸 수 없다. 인터페이스 구현은 가능하므로, 테스트를 위해 인터페이스를 두는 설계와는 잘 맞는다.
- **어색해 보일 수 있다.** "열거"라는 이름과 "싱글턴"이라는 의도가 어긋나서, 처음 보는 팀원이 `INSTANCE` 옆에 상수를 추가하는 실수를 할 수 있다. 클래스 주석으로 의도를 적어 두는 게 좋다.
- **초기화 시점은 클래스 초기화에 묶인다.** 상수는 `<clinit>`에서 만들어지고, 클래스 초기화는 처음 실제로 사용되는 시점에 일어난다(JLS 12.4.1). 그래서 대부분 필요할 때 만들어지지만, 같은 열거 타입의 다른 정적 멤버를 먼저 건드려도 그때 함께 만들어진다.

## 버전 표기

- 열거 타입 싱글턴: **Java 5+. Java 7 레거시에서도 그대로 사용 가능.**
- 열거 타입에 인터페이스 구현, 상수별 메서드 구현: Java 5+.

---

# 확장 — 지연 초기화 싱글턴 (책 범위 밖, 실무 보완)

책의 세 방식은 모두 `INSTANCE`를 즉시 초기화한다. 하지만 실무 코드에서는 "필요할 때 만들자"며 지연 초기화(lazy initialization)로 싱글턴을 짜는 경우가 매우 흔하고, 여기서 싱글턴이 가장 자주 깨진다. 지연 초기화 자체는 아이템 83이 다룬다.

## ① 흔히 쓰는(나쁜) 코드

```java
public class Elvis {
    private static Elvis instance;
    private Elvis() { /* 무거운 초기화: 커넥션 풀 생성 등 */ }

    public static Elvis getInstance() {
        if (instance == null) {          // 두 스레드가 동시에 여기를 통과할 수 있다
            instance = new Elvis();
        }
        return instance;
    }
}
```

## ② 무엇이 문제인가

- 로컬에서는 요청을 하나씩 보내니 `getInstance()`가 순서대로 호출되고, 인스턴스는 늘 하나다.
- 배포 직후 트래픽이 몰리는 워밍업 구간에 요청 수십 개가 동시에 처음으로 `getInstance()`를 호출한다. 둘 이상의 스레드가 `instance == null`을 동시에 통과하면 **생성자가 여러 번 실행된다.**
- 생성자가 커넥션 풀을 만든다면 풀이 둘 이상 생긴다. `instance` 필드에는 마지막 것만 남고, 먼저 만든 풀은 아무도 닫지 않은 채 커넥션을 쥐고 있다. **예외 없음. 재기동하면 재현되지 않는다.** 증상은 한참 뒤 "DB 커넥션 수가 설정보다 많다"는 알람으로 나타난다.

동기화를 줄이려고 흔히 쓰는 이중 검사(double-checked locking)는 `volatile`을 빠뜨리면 더 조용하게 깨진다. `instance = new Elvis()`는 원자적 연산이 아니다. JIT 컴파일 결과에 따라 **생성자가 끝나기 전에 참조가 먼저 필드에 기록될 수 있어서**, 다른 스레드가 `null`이 아닌, 아직 초기화 중인 객체를 받아 쓸 수 있다.

근본 원인은 하나다. **"확인 후 생성"이라는 두 동작을 원자적으로 묶지 않았다.**

## ③ 개선된 코드 — 초기화 주문형 홀더 클래스 (holder idiom)

```java
public class Elvis {
    private Elvis() { }

    private static class Holder {                       // getInstance() 가 처음 불릴 때 초기화된다
        static final Elvis INSTANCE = new Elvis();
    }

    public static Elvis getInstance() { return Holder.INSTANCE; }
}
```

## 원리 — JVM의 클래스 초기화 락을 빌려 쓴다

`Holder`는 `Holder.INSTANCE`를 처음 읽는 `getstatic` 시점에 초기화된다(JLS 12.4.1). JLS 12.4.2의 클래스 초기화 절차는 **클래스마다 초기화 락을 잡고 정확히 한 번만 `<clinit>`을 실행**하도록 JVM에 요구한다. 동시에 들어온 다른 스레드는 초기화가 끝날 때까지 기다린 뒤 완성된 값을 본다. 초기화가 끝난 뒤에는 `getInstance()`에 동기화 비용이 전혀 없다. `synchronized`를 한 줄도 쓰지 않고 지연 초기화와 스레드 안전성을 동시에 얻는 방식이다.

직렬화나 리플렉션까지 막아야 한다면 결국 3단계의 열거 타입으로 돌아간다. 열거 타입도 클래스 초기화 시점에 상수가 만들어지므로 사실상 같은 지연 효과를 낸다.

## 버전 표기

- 홀더 클래스 방식: **모든 버전. Java 7 레거시에서도 그대로 사용 가능.**
- `volatile`을 붙인 이중 검사: **Java 5+.** Java 5에서 자바 메모리 모델이 개정(JSR-133)되기 전에는 `volatile`을 붙여도 올바르게 동작한다는 보장이 없었다.

---

# 3판(Java 9 기준) 이후 — 현재(Java 21) 시점의 보완

## record로는 싱글턴을 보증할 수 없다

`(Java 16+)` 레코드는 불변 데이터 객체를 만들기 편하지만, JLS 8.10.4에 따라 **정규 생성자가 레코드 클래스 자신보다 좁은 접근 수준을 가질 수 없다.** `public record Config(...)`의 생성자는 `public`이어야 하므로 누구나 `new`로 만들 수 있다. 즉 레코드는 1단계의 출발점인 `private` 생성자조차 가질 수 없어서 싱글턴 용도로는 맞지 않는다. 불변 싱글턴이 필요하면 열거 타입을 쓴다.

## 리플렉션의 벽은 모듈 경계에서만 높아졌다

`(Java 9+)` 모듈 시스템에서는 대상 패키지가 호출자 모듈에 `opens`되어 있지 않으면 `setAccessible(true)`가 `InaccessibleObjectException`을 던진다. `(Java 16, JEP 396 / Java 17, JEP 403)` JDK 내부 API는 기본적으로 강하게 캡슐화되었다.

하지만 **클래스패스로 실행하는 일반적인 스프링 애플리케이션에서는 모든 클래스가 이름 없는 모듈(unnamed module) 하나에 들어 있어서**, 내 코드의 `private` 생성자에 대한 리플렉션은 여전히 막히지 않는다. 1단계의 `@Component` 시나리오는 Java 21에서도 그대로 재현된다.

## 직렬화는 이제 "필터로 막는" 기술이다

`(Java 9+, JEP 290)` 역직렬화 필터(`ObjectInputFilter`)가 도입되었고, 이후 Java 8·7 업데이트에도 백포트되었다. `(Java 17, JEP 415)` 역직렬화 작업마다 필터를 따로 지정할 수 있게 되었다. JDK의 방향은 명확하다. 신뢰할 수 없는 데이터에 자바 직렬화를 쓰지 말고, 쓸 거라면 허용할 클래스를 명시하라는 것이다(아이템 85). 새 코드에서 자바 직렬화를 쓸 일은 줄었지만, 2단계에서 봤듯 **세션·캐시 저장소의 기본 직렬화기로 여전히 남아 있는 곳이 많으니** 설정을 확인해야 한다.

## 스프링의 "싱글턴 스코프"는 이 아이템의 싱글턴이 아니다

스프링 빈의 기본 스코프가 싱글턴이라 헷갈리기 쉽다. 스프링의 싱글턴은 **"컨테이너 하나에 빈 정의 하나당 인스턴스 하나"** 라는 뜻이다. 클래스 자체는 누구든 `new`로 만들 수 있고, 컨테이너가 둘이면 인스턴스도 둘이다. 반대로 클래스 쪽에서는 아무것도 보증하지 않기 때문에 테스트에서 가짜 구현으로 쉽게 바꿀 수 있다. 책이 지적한 싱글턴의 테스트 문제를 의존 객체 주입(아이템 5)으로 해결한 형태다.

실무 결론은 이렇다. 스프링 애플리케이션 안에서는 이 아이템의 싱글턴을 직접 만들기보다 **빈으로 등록하고 주입받는 것이 기본**이다. 이 아이템의 기법은 컨테이너 밖에서도 동작해야 하는 코드(라이브러리, 유틸리티, 컨테이너가 뜨기 전에 필요한 객체)에 쓴다. 그리고 1단계 시나리오처럼 **두 방식을 한 클래스에 섞는 것**이 가장 위험하다.

---

# 심화 / 예외 상황

**클래스 로더가 둘이면 싱글턴도 둘이다.** JVM에서 클래스의 정체성은 "클래스 이름 + 그 클래스를 정의한 클래스 로더" 쌍이다(JVMS 5.3). 같은 `Elvis.class`라도 다른 클래스 로더가 읽으면 JVM 입장에서는 다른 클래스이고, `INSTANCE`도 따로 생긴다. 열거 타입도 예외가 아니다. 실무에서 흔한 예가 스프링 부트 DevTools다. DevTools는 라이브러리 jar를 읽는 base 클래스 로더와 프로젝트 클래스를 읽는 restart 클래스 로더를 따로 둔다. 그래서 싱글턴이 두 로더에 걸쳐 참조되면 `Elvis cannot be cast to Elvis` 같은, 이름은 같은데 캐스팅이 실패하는 예외를 만나게 된다. 톰캣 하나에 웹 애플리케이션을 여러 개 올려도 애플리케이션마다 싱글턴이 따로 생긴다.

**보증되는 것은 "개수"이지 "스레드 안전성"이 아니다.** 열거 타입 싱글턴에 `HashMap` 필드를 두고 여러 요청 스레드가 동시에 `put`하면, 인스턴스는 분명 하나인데 그 안의 맵이 손상된다. 싱글턴은 곧 전역 공유 상태다. 가능하면 무상태로 만들고, 상태가 필요하면 `ConcurrentHashMap` 같은 동시성 자료구조를 쓰거나 동기화한다.

**열거 타입 싱글턴의 필드 값은 직렬화되지 않는다.** 직렬화 명세상 열거 상수는 이름만 오간다. 서버 A에서 `Elvis.INSTANCE`의 필드를 바꾼 뒤 세션에 담아 서버 B로 보내면, B가 받는 것은 B의 JVM에 있는 `Elvis.INSTANCE`이고 필드 값도 B의 것이다. 인스턴스 개수를 보증하는 바로 그 동작의 반대 면이다. 서버 간에 상태를 옮겨야 한다면 싱글턴 안에 상태를 두는 설계부터 다시 봐야 한다.

**테스트 간 상태 누수.** 싱글턴은 JVM이 살아 있는 동안 계속 살아 있고, 열거 타입은 테스트에서 새로 만들 방법도 없다. 한 테스트가 싱글턴의 상태를 바꾸면 같은 JVM에서 뒤따르는 테스트가 그 상태를 물려받는다. 그래서 **테스트 실행 순서에 따라 성공과 실패가 갈리는** 테스트가 생긴다. 상태를 가진 싱글턴이라면 인터페이스로 추상화해 테스트마다 새 구현을 주입하는 편이 낫다.

**상위 클래스가 `Cloneable`이면 복제 경로가 하나 더 있다.** 클래스 방식 싱글턴이 `Cloneable`을 구현한 클래스를 상속하면, `clone()`으로 두 번째 인스턴스를 만들 수 있다. `clone()`을 재정의해 `CloneNotSupportedException`을 던져야 한다. 열거 타입은 `Enum.clone()`이 이미 막고 있다.

---

## 핵심 키워드

- [싱글턴 패턴](../Keywords/singleton-pattern.md) — 인스턴스가 하나뿐임을 보증하는 설계와 그 구현 방식들
- [열거 타입](../Keywords/enum-type.md) — javac가 만드는 구조와, JDK가 `ACC_ENUM`을 보고 인스턴스 생성을 막는 원리
- [readResolve와 역직렬화 경로](../Keywords/read-resolve.md) — 역직렬화가 생성자를 부르지 않는 이유와 객체 바꿔치기 훅
- [setAccessible](../Keywords/set-accessible.md) — 리플렉션이 `private`을 무시하는 원리와 모듈 시대의 제약
- [클래스 초기화](../Keywords/class-initialization.md) — `<clinit>`이 언제, 몇 번, 어떤 락 아래서 실행되는가
- [인스턴스 통제 클래스](../Keywords/instance-controlled-class.md) — 싱글턴은 "인스턴스 1개"를 보증하는 인스턴스 통제의 한 형태

## 함께 보면 좋은 아이템

아이템 3과 4는 "`private` 생성자로 인스턴스를 통제한다"는 짝이다. 3이 인스턴스를 하나로 만든다면, 4는 하나도 만들지 않는다.

- **아이템 1. 생성자 대신 정적 팩터리 메서드를 고려하라** — 2단계 `getInstance()`는 아이템 1의 인스턴스 통제 클래스를 가장 극단적으로 쓴 형태다. `getInstance`라는 이름이 "같은 인스턴스를 줄 수 있다"는 신호라는 이름 관례도 여기서 나온다.
- **아이템 89. 인스턴스 수를 통제해야 한다면 readResolve보다는 열거 타입을 사용하라** — 2단계에서 "`transient`를 빠뜨리면 `readResolve`가 뚫린다"고만 짚은 공격을 실제 코드로 보여 주고, 3단계의 열거 타입이 왜 답인지 직렬화 관점에서 끝까지 파고든다.
- **아이템 83. 지연 초기화는 신중히 사용하라** — 확장 섹션의 홀더 클래스 방식과 이중 검사를 정식으로 다룬다. 지연 초기화가 애초에 필요한지부터 따지는 기준도 여기 있다.
- **아이템 5. 자원을 직접 명시하지 말고 의존 객체 주입을 사용하라** — 신입 눈높이 설명의 "싱글턴은 테스트하기 어렵다"는 문제와, 스프링 싱글턴 스코프 섹션에서 말한 "빈으로 주입받는 게 기본"이라는 결론의 근거다.

## 확인 질문

예제의 `GuardedElvis`에 `private int playCount;` 필드를 추가하고 `transient`는 붙이지 않았다고 하자. `readResolve`는 그대로 있다.

```java
// 서버 A: GuardedElvis.INSTANCE.playCount == 10 인 상태에서 세션에 담아 직렬화
// 서버 B: GuardedElvis.INSTANCE.playCount == 3 인 상태에서 그 세션을 역직렬화
GuardedElvis restored = (GuardedElvis) in.readObject();
```

서버 B에서 `restored.playCount`는 몇일까? 그리고 스트림에 담겨 온 `10`은 역직렬화 과정의 어느 단계에서 어디에 들어갔다가 어떻게 사라졌을까?
