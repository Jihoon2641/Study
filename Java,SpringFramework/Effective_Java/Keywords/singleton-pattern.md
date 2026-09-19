# 싱글턴 패턴 (singleton pattern)

## 한 줄 정의
클래스의 인스턴스가 JVM(정확히는 클래스 로더) 안에 오직 하나만 존재하도록 클래스 스스로 보증하고, 그 인스턴스로 가는 전역 접근 경로를 제공하는 설계.

## 도입 버전
(모든 버전) 설계 기법이다. 구현 수단에 따라 버전이 갈린다.
- `private` 생성자 + `public static final` 필드 / 정적 팩터리 — 모든 버전
- `readResolve`로 직렬화 방어 — Java 1.2+
- 원소 하나짜리 열거 타입 — Java 5+
- `volatile` 이중 검사 지연 초기화 — Java 5+ (자바 메모리 모델 개정 이후에만 올바름)

## 무엇을 하는가
인스턴스를 하나로 제한하려면 **객체가 만들어지는 모든 경로**를 막아야 한다. 자바에서 객체가 생기는 경로는 넷이다.

1. 소스 코드의 `new` — `private` 생성자로 막는다. javac와 JVM 링킹 단계가 검사한다.
2. 리플렉션 `Constructor.newInstance` — `setAccessible(true)`로 접근 검사를 끌 수 있어서 `private`만으로는 못 막는다. 생성자 안에 두 번째 호출을 거부하는 가드를 둔다.
3. 역직렬화 — 생성자를 아예 호출하지 않는다. `readResolve`와 `transient` 필드로 막는다.
4. `clone` — `Cloneable` 상속 경로가 있으면 `clone()`을 재정의해 막는다.

열거 타입은 JDK가 네 경로 모두에서 열거 상수 외의 인스턴스 생성을 거부하므로, 방어 코드 없이 네 경로가 한꺼번에 막힌다.

주의할 점은 보증 범위가 **클래스 로더 단위**라는 것이다. JVM은 "이름 + 정의한 클래스 로더"로 클래스를 구분하므로, 같은 클래스를 로더 두 개가 읽으면 싱글턴도 두 개가 된다.

## 최소 사용 예
```java
// 즉시 초기화 + 모든 경로 방어 — Java 5+
public enum Registry {
    INSTANCE;
    private final Map<String, String> map = new ConcurrentHashMap<String, String>();
    public void put(String k, String v) { map.put(k, v); }
}

// 지연 초기화 — 홀더 클래스, 모든 버전 (리플렉션·직렬화 방어는 별도)
public class Heavy {
    private Heavy() { }
    private static class Holder { static final Heavy INSTANCE = new Heavy(); }
    public static Heavy getInstance() { return Holder.INSTANCE; }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **싱글턴 vs 스프링 싱글턴 스코프** — 기준은 "누가 개수를 보증하는가". GoF 싱글턴은 클래스가 보증하고, 스프링은 컨테이너가 "빈 정의 하나당 하나"만 관리할 뿐 클래스는 누구나 `new`할 수 있다. 테스트 대역 교체는 스프링 방식이 훨씬 쉽다.
- **싱글턴 vs 정적 유틸리티 클래스** — 기준은 "인스턴스가 필요한가". 인터페이스를 구현하거나 다형성, 의존 주입, 직렬화가 필요하면 싱글턴이고, 순수 함수 묶음이면 인스턴스화를 막은 유틸리티 클래스로 충분하다.
- **인스턴스가 하나면 스레드 안전하다** — 아니다. 인스턴스가 하나라는 건 그 상태를 모든 스레드가 공유한다는 뜻이라, 오히려 동기화가 더 필요하다.

## 등장하는 아이템
- [아이템 3. private 생성자나 열거 타입으로 싱글턴임을 보증하라](../Item3/item-3-singleton.md) — 이 아이템의 주제 자체. 필드·정적 팩터리·열거 타입 세 방식과 각 방식의 방어 경로를 다룬다.
- [아이템 5. 자원을 직접 명시하지 말고 의존 객체 주입을 사용하라](../Item5/item-5-dependency-injection.md) — 싱글턴으로 만들면 안 되는 경우로 등장한다. 인스턴스 개수를 통제하는 것과 자원을 클래스가 고르는 것은 별개의 축이며, 후자가 문제다.
