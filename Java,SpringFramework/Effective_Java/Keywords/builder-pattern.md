# 빌더 패턴 (builder pattern)

## 한 줄 정의
객체에 넣을 값을 이름 붙은 메서드로 하나씩 빌더에 모아 두었다가, `build()` 한 번으로 검증을 거친 완성 객체를 만드는 생성 기법.

## 도입 버전
(모든 버전) 언어 기능이 아니라 설계 기법이다.
- 계층형 빌더의 `Builder<T extends Builder<T>>`(제네릭) — Java 5+
- 하위 빌더의 `build()`가 구체 타입을 반환하는 공변 반환 — Java 5+
- 레코드와 조합 — Java 16+ / 빌더 결과를 `var`로 받기 — Java 10+

## 무엇을 하는가
보통 형태는 네 부분이다.
1. 대상 클래스의 생성자는 `private`이고 빌더를 인수로 받는다.
2. 빌더 생성자는 **필수 값**을 받는다 → 누락되면 컴파일 에러.
3. **선택 값**은 `this`를 반환하는 메서드로 받는다 → 메서드 연쇄(method chaining), 플루언트 API(fluent API)라고도 부른다.
4. `build()`가 대상의 `private` 생성자를 호출하고, 생성자는 빌더의 값을 `final` 필드로 복사한 뒤 불변식을 검사한다.

원리의 핵심은 **가변성의 위치**다. 값이 조금씩 채워지는 가변 상태는 빌더에만 있고, 빌더는 보통 지역 변수로 쓰고 버린다. 외부에 공개되는 대상 객체는 생성자가 끝나는 순간 모든 필드가 확정된 불변 객체다. 또 인수가 메서드 이름에 묶이므로(`fat(35)`), 타입이 같은 인수의 순서를 뒤바꾸는 실수가 원천적으로 사라진다. 자바는 호출 시 인수 이름을 쓸 수 없고 바이트코드 디스크립터에도 타입만 기록되기 때문에, 이름으로 값을 구분하려면 메서드를 나눌 수밖에 없다.

GoF의 빌더 패턴(Director가 Builder 인터페이스로 복잡한 제품을 단계별로 조립)과 뿌리는 같지만, 이펙티브 자바가 말하는 형태는 "생성자 매개변수 문제를 푸는 정적 중첩 빌더"로 더 단순하다. 흔히 "블로크(Bloch) 빌더"라고 부른다.

## 최소 사용 예
```java
public final class Request {
    private final String url; private final int timeoutMs;
    private Request(Builder b) { url = b.url; timeoutMs = b.timeoutMs; }

    public static final class Builder {
        private final String url;          // 필수
        private int timeoutMs = 3000;      // 선택, 기본값
        public Builder(String url) { this.url = url; }
        public Builder timeoutMs(int v) { timeoutMs = v; return this; }
        public Request build() { return new Request(this); }
    }
}
Request r = new Request.Builder("https://example.com").timeoutMs(500).build();
```

## 자주 하는 오해 / 헷갈리는 짝
- **빌더 vs 점층적 생성자** — 기준은 "매개변수 개수와 증가 가능성". 4개 이상이거나 계속 늘어날 것 같으면 빌더, 두세 개로 고정이면 생성자나 정적 팩터리로 충분하다.
- **빌더로 만든 객체는 자동으로 불변이다** — 아니다. 빌더의 컬렉션·배열 필드를 대상 생성자에서 복사하지 않고 참조만 넘기면, 빌더를 재사용할 때 이미 만든 객체의 내용이 바뀐다.
- **빌더는 스레드 안전하다** — 대상 객체가 불변일 뿐, 빌더 자체는 가변이다. 공유하지 않는다.
- **Lombok `@Builder`도 같다** — 필수 값 강제가 없어 빈 `build()`도 컴파일된다. 필드 초기값은 `@Builder.Default`가 없으면 무시된다.

## 등장하는 아이템
- [아이템 2. 생성자에 매개변수가 많다면 빌더를 고려하라](../Item2/item-2-builder.md) — 이 아이템의 주제 자체. 점층적 생성자·자바빈즈와 비교해 빌더의 이점과 계층형 빌더를 다룬다.
