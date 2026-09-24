# 캡슐화 (encapsulation) / 정보 은닉 (information hiding)

## 한 줄 정의
클래스의 내부 표현(필드, 구현 세부)을 숨기고, 외부에는 약속된 동작(메서드)만 공개해서, 내부를 바꿔도 외부가 영향받지 않게 하는 설계 원칙.

## 도입 버전
- 설계 원칙이라 버전과 무관하다. 도구가 되는 접근 제한자(`private`, package-private, `protected`, `public`)는 Java 1.0+
- `(Java 9+)` 모듈 시스템의 `exports`/`opens`로 패키지 단위 은닉이 추가되었다.
- `(Java 16/17)` JDK 내부 API에 대한 강한 캡슐화가 기본값이 되었다(JEP 396, JEP 403). Java 7 레거시에는 모듈 수준 은닉이 없다.

## 무엇을 하는가
핵심은 두 가지 통제권이다.
- 변경 통제권: 외부가 모르는 부분은 내가 마음대로 바꿀 수 있다. 공개된 부분은 사용자가 몇 명인지 모르므로 영원히 유지해야 한다.
- 상태 통제권: 모든 상태 변경이 내 메서드를 거치면 불변식(invariant)을 한 곳에서 지킬 수 있다.

자바에서 이 통제권은 선언만으로 끝나지 않는다. 접근 제한자는 클래스 파일의 `access_flags`에 기록되고, 컴파일 시점(javac)과 링킹 시점(JVM의 접근 검사)에 두 번 검사된다. 반대로 필드를 공개하면 호출자 바이너리에 필드 이름과 타입이 박혀서 변경 통제권을 잃는다.

캡슐화와 정보 은닉은 보통 같은 뜻으로 쓴다. 굳이 나누자면 정보 은닉은 "무엇을 숨길지 정하는 설계 결정"이고, 캡슐화는 "데이터와 동작을 한 단위로 묶고 경계를 긋는 언어 장치"다. 이펙티브 자바는 두 용어를 거의 구분하지 않는다.

## 최소 사용 예
```java
public final class Account {
    private long balance;                 // 표현 숨김 — 나중에 BigDecimal로 바꿔도 된다

    public long balance() { return balance; }

    public void withdraw(long amount) {   // 상태 변경은 이 경로뿐 → 불변식 balance >= 0 보장
        if (amount <= 0 || amount > balance) throw new IllegalArgumentException("금액: " + amount);
        balance -= amount;
    }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **캡슐화 = 필드를 private으로 만드는 것** — 필요조건일 뿐 충분조건은 아니다. 기준은 "외부가 내 불변식을 깰 수 있는가"이다. `private` 필드라도 검증 없는 세터나 가변 객체 참조를 그대로 돌려주는 게터가 있으면 캡슐화가 뚫린다.
- **캡슐화 vs 보안** — 캡슐화는 실수를 막는 장치이지 악의적인 공격을 막는 장치가 아니다. 리플렉션(`setAccessible`), 직렬화 같은 우회로가 있다. 다만 Java 17 이후 모듈 경계에서는 리플렉션 우회도 막힌다.

## 등장하는 아이템
- [아이템 15. 클래스와 멤버의 접근 권한을 최소화하라](../Item15/item-15-minimize-accessibility.md) — 접근 제한자로 경계를 긋는 방법. 캡슐화의 도구 편이다.
- [아이템 16. public 클래스에서는 public 필드가 아닌 접근자 메서드를 사용하라](../Item16/item-16-accessor-methods.md) — public 필드가 캡슐화의 두 통제권을 어떻게 모두 잃게 만드는지 다룬다.
