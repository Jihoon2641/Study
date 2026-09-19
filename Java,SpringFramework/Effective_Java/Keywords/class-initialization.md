# 클래스 초기화 (class initialization, `<clinit>`)

## 한 줄 정의
클래스의 `static` 필드 초기화 식과 `static` 블록을, 그 클래스가 처음 실제로 사용되는 순간 JVM이 스레드 안전하게 정확히 한 번 실행하는 과정.

## 도입 버전
(모든 버전) JLS 12.4와 JVMS 5.5가 규정하는 JVM의 기본 동작이다. Java 7 레거시에서도 동일하다.

## 무엇을 하는가
javac는 `static` 필드 초기화 식과 `static { }` 블록을 **소스에 적힌 순서대로** 모아서 `<clinit>`이라는 특수 메서드 하나로 만든다. JVM은 이 메서드를 다음 시점에 한 번만 실행한다.

**언제 — 처음 "능동적으로 사용"될 때 (JLS 12.4.1)**
- 인스턴스 생성(`new`, 리플렉션 `newInstance` 포함)
- `static` 메서드 호출
- `static` 필드 읽기·쓰기. 단 컴파일 타임 상수(`static final int X = 3;` 같은 상수 변수)는 사용처에 값이 인라인되므로 초기화를 일으키지 않는다
- 하위 클래스가 초기화될 때 상위 클래스가 먼저
- `Class.forName("...")` (기본값 기준)

클래스를 로딩했다고 초기화되는 것은 아니다. `Holder.class`를 참조만 하거나, 그 클래스 타입의 배열을 만드는 것은 초기화를 일으키지 않는다.

**어떻게 — 초기화 락 (JLS 12.4.2)**
JVM은 클래스마다 초기화 락을 두고 상태를 "초기화 안 됨 → 진행 중 → 완료(또는 실패)"로 관리한다. 두 스레드가 동시에 처음 사용하면 한 스레드만 `<clinit>`을 실행하고 나머지는 완료될 때까지 기다린다. 완료된 뒤에는 락 없이 바로 접근한다. JIT도 초기화가 끝난 클래스라는 전제로 코드를 최적화한다.

**실패하면** — `<clinit>`이 예외로 끝나면 그 클래스는 "실패" 상태로 굳고, 이후 그 클래스를 사용하려는 모든 시도는 `NoClassDefFoundError`를 던진다. 운영 로그에서 첫 에러를 놓치면 원인을 찾기 매우 어려워진다. 이때 무엇이 던져지는지는 JLS 12.4.2가 나눠 둔다. 던져진 것이 `Error`의 하위 타입이면 **감싸지 않고 그대로 전파**되고, `Error`가 아닌 `Throwable`(대부분의 `RuntimeException`)이면 `ExceptionInInitializerError`에 원인으로 담겨 전파된다. 그래서 정적 초기화 중에 터진 `AssertionError`는 스택 트레이스에 `ExceptionInInitializerError` 없이 `<clinit>` 프레임과 함께 그대로 나타난다.

이 동작이 **홀더 클래스 지연 초기화**의 근거다. 바깥 클래스를 초기화할 때는 중첩 클래스가 초기화되지 않고, 중첩 클래스의 필드를 처음 읽는 시점에 초기화된다.

## 최소 사용 예
```java
public class Config {
    static { System.out.println("Config 초기화"); }
    public static final int VERSION = 3;          // 상수 변수 → 읽어도 초기화 안 됨

    private static class Holder {
        static { System.out.println("Holder 초기화"); }
        static final Config INSTANCE = new Config();
    }
    public static Config get() { return Holder.INSTANCE; }
}

System.out.println(Config.VERSION);  // 3            (아무 초기화도 일어나지 않음)
Config.get();                        // Config 초기화 → Holder 초기화
```

## 자주 하는 오해 / 헷갈리는 짝
- **클래스 로딩 = 초기화** — 아니다. 기준은 "`<clinit>`이 실행되었는가". 로딩과 링킹은 먼저 일어날 수 있고, 초기화는 처음 능동적으로 사용될 때로 미뤄진다.
- **`static` 필드는 선언 위치와 상관없이 다 준비된 뒤 쓰인다** — 아니다. `<clinit>`은 소스 순서대로 실행되므로, 위쪽 초기화 식이 아직 초기화되지 않은 아래쪽 `static` 필드를 간접적으로 읽으면 기본값(`0`, `null`)을 보게 된다.
- **초기화 락이 있으니 순환 참조도 안전하다** — 같은 스레드가 초기화 중인 클래스를 다시 사용하면 기다리지 않고 **초기화가 덜 된 상태를 그대로 본다**. 두 스레드가 서로 다른 클래스의 초기화를 교차로 기다리면 교착 상태(deadlock)가 날 수도 있다.

## 등장하는 아이템
- [아이템 3. private 생성자나 열거 타입으로 싱글턴임을 보증하라](../Item3/item-3-singleton.md) — 생성자 가드가 동작하는 이유(리플렉션 생성 전에 `INSTANCE`가 이미 초기화됨)와, 동기화 없이 스레드 안전한 지연 초기화를 만드는 홀더 클래스 방식의 근거로 등장한다.
- 아이템 83. 지연 초기화는 신중히 사용하라 — 정적 필드용 지연 초기화 홀더 클래스 관용구를 정식으로 다룬다. (설명 파일 아직 없음)
