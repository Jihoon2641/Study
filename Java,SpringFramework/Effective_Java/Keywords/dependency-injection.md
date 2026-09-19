# 의존 객체 주입 (dependency injection, DI)

## 한 줄 정의
클래스가 필요한 자원을 자기 안에서 직접 만들지 않고, 밖에서 만들어 넘겨받는 설계 기법.

## 도입 버전
(모든 버전) 언어 기능이 아니라 설계 기법이다. 매개변수로 객체를 넘기는 것이 전부다.
- `Objects.requireNonNull`로 주입값 검증 — Java 7+
- 자원 대신 팩터리를 주입할 때 쓰는 `Supplier<T>`·람다·메서드 참조 — Java 8+ (Java 7에서는 팩터리 인터페이스를 직접 정의)
- `record`를 의존성 그릇으로 사용 — Java 16+

## 무엇을 하는가
"이 클래스가 일하려면 반드시 있어야 하는 다른 객체"를 자원(resource) 또는 의존성이라고 한다. 클래스 안에서 `new`로 만들면 그 구현 클래스 이름이 바이트코드에 박혀 바꿀 수 없고, 매개변수로 받으면 호출자가 정한다.

주입 방식은 셋이지만 우선순위가 분명하다.

1. **생성자 주입** — 기본. 필드를 `final`로 만들 수 있어 객체가 불변이 되고, JLS 17.5의 `final` 필드 규칙 덕분에 동기화 없이도 다른 스레드가 올바른 값을 본다. 의존성을 빠뜨리면 컴파일 에러라 문제가 생성 시점으로 당겨진다.
2. **세터 주입** — 선택적 의존성에만. 필드가 `final`일 수 없고, 주입 전 상태가 외부에 노출된다(아이템 2의 자바빈즈 패턴과 같은 문제).
3. **필드 주입(리플렉션)** — 프레임워크가 `setAccessible(true)`로 `private` 필드를 채우는 방식. `final`을 쓸 수 없고, 컨테이너 없이는 객체를 만들 수 없어 단위 테스트가 어려워진다.

유연성의 실체는 호출 방식에 있다. 정적 유틸리티 호출은 `invokestatic`으로 컴파일되어 대상 클래스가 상수 풀에 박히지만, 인터페이스 타입 필드로 호출하면 `invokeinterface`가 되어 **실행 시점 객체가 구현을 정한다.**

제어의 역전(inversion of control, IoC)은 "무엇을 쓸지 정하는 권한을 클래스에서 호출자로 넘긴다"는 더 넓은 원칙이고, 의존 객체 주입은 그 구체적 수단이다. 스프링·대거·주스 같은 프레임워크는 이 연결을 대신 해 줄 뿐, **DI 자체에 프레임워크가 필요하지는 않다.**

## 최소 사용 예
```java
public class SpellChecker {
    private final Lexicon dictionary;

    public SpellChecker(Lexicon dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary);   // Java 7+
    }
    public boolean isValid(String word) { return dictionary.contains(word); }
}

SpellChecker korean = new SpellChecker(new KoreanDictionary());
SpellChecker test   = new SpellChecker(new FakeLexicon());      // 테스트에서 바꿔 끼운다
```

## 자주 하는 오해 / 헷갈리는 짝
- **DI는 스프링 같은 프레임워크 기능이다** — 아니다. 생성자 매개변수로 넘기는 것이 DI다. 프레임워크는 객체 그래프가 커졌을 때 연결을 자동화하는 도구다.
- **모든 것을 주입받아야 한다** — 기준은 "동작이 그 자원에 따라 달라지는가". 시간·난수·파일·DB처럼 테스트에서 고정하고 싶어지는 것은 주입 대상이고, `Math.abs` 같은 순수 함수는 정적 유틸리티가 맞다.
- **`final` 필드로 받았으니 안전하다** — `final`은 참조가 바뀌지 않음만 보장한다. 주입받은 객체 자체가 가변이고 공유되면 상태가 섞이는 문제는 그대로다.
- **주입하면 의존성이 줄어든다** — 줄지 않고 **드러난다.** 생성자 매개변수가 계속 늘어난다면 그 클래스가 책임을 너무 많이 지고 있다는 신호다.

## 등장하는 아이템
- [아이템 5. 자원을 직접 명시하지 말고 의존 객체 주입을 사용하라](../Item5/item-5-dependency-injection.md) — 이 아이템의 주제 자체. 정적 유틸리티·싱글턴과 비교하고 팩터리 주입까지 다룬다.
