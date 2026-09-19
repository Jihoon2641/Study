# 아이템 5. 자원을 직접 명시하지 말고 의존 객체 주입을 사용하라

## 한 줄 결론

클래스가 자기 안에서 `new`로 자원을 만들면, **그 자원을 무엇으로 쓸지가 클래스 파일에 박혀 버린다.** 쓰는 자원에 따라 동작이 달라지는 클래스라면 자원을 생성자로 넘겨받아야 하고, 그래야 클래스 하나로 여러 자원을 쓸 수 있고 테스트에서 바꿔 끼울 수 있다.

정적 유틸리티 클래스(아이템 4)와 싱글턴(아이템 3)이 이 아이템에서 함께 부정되는 이유는, 둘 다 **자원을 클래스가 고정한다는 같은 결함**을 갖기 때문이다. 인스턴스가 0개냐 1개냐는 달라도 "누가 자원을 고르는가"에 대한 답은 똑같이 "클래스 자신"이다.

---

## 신입 눈높이 설명

책의 예는 맞춤법 검사기(`SpellChecker`)다. 검사기는 사전(`Lexicon`)이 있어야 동작한다. 여기서 사전처럼 **"이 클래스가 일하려면 반드시 있어야 하는 다른 객체"** 를 자원(resource) 또는 의존성(dependency)이라고 부른다.

두 가지 방식이 있다. 검사기가 생성자 안에서 `new KoreanDictionary()`로 사전을 직접 만들 수도 있고, 밖에서 만들어 준 사전을 생성자 매개변수로 받을 수도 있다. 후자를 의존 객체 주입(dependency injection)이라고 한다. 이름은 거창하지만 실체는 **생성자 매개변수 한 개**다.

한 줄로 대조하면 이렇다. **직접 명시는 "나는 한국어 사전을 쓴다"는 선언이고, 주입은 "나는 사전이 하나 필요하다"는 요구다.** 앞의 것은 클래스를 고쳐야 바뀌고, 뒤의 것은 호출자가 무엇을 넘기느냐로 바뀐다.

여기서 흔히 오해하는 게 하나 있다. 이 아이템은 "정적 유틸리티 클래스와 싱글턴은 나쁘다"는 말이 아니다. 조건이 붙어 있다. **사용하는 자원에 따라 동작이 달라지는 클래스**에 쓰지 말라는 것이다. `Math.abs`처럼 아무것도 참조하지 않는 순수 함수라면 정적 유틸리티가 맞다.

---

# 1단계 — 정적 유틸리티 클래스로 만들면

## ① 흔히 쓰는(나쁜) 코드

```java
// 정적 유틸리티를 잘못 사용한 예 — 유연하지 않고 테스트하기 어렵다
public class SpellChecker {
    private static final Lexicon dictionary = new KoreanDictionary();

    private SpellChecker() { }   // 객체 생성 방지 (아이템 4)

    public static boolean isValid(String word) { ... }
    public static List<String> suggestions(String typo) { ... }
}
```

호출하는 쪽은 `SpellChecker.isValid(word)` 한 줄이면 되니 처음에는 가장 편한 형태로 보인다.

## ② 무엇이 문제인가

문제는 요구가 하나 늘어날 때 시작된다. "영어 문서도 검사해야 한다"는 요청이 오면 `dictionary`가 `final`이라 손댈 수 없다. 그래서 거의 반드시 다음 코드가 등장한다.

```java
private static Lexicon dictionary = new KoreanDictionary();   // final 을 뗐다
public static void setDictionary(Lexicon d) { dictionary = d; }   // 그리고 세터를 열었다
```

책이 "어색하고 오류를 내기 쉬우며 멀티스레드 환경에서는 쓸 수 없다"고 한 형태가 정확히 이것이다. 무엇이 어떻게 깨지는지 보자.

```java
SpellChecker.setDictionary(koreanDictionary);
SpellChecker.isValid("사과");     // 이 두 줄 사이에 다른 요청이 끼어들 수 있다
```

- 로컬에서는 요청을 하나씩 보내니 `set` 다음에 항상 자기 `isValid`가 실행된다. 전부 통과한다.
- 단위 테스트도 스레드 하나로 순차 실행되니 통과한다.
- 운영 서버는 요청마다 스레드가 다르다. 한국어 요청이 사전을 걸어 둔 직후, 다른 요청이 영어 사전으로 덮어쓴다. 첫 요청은 **자기가 건 사전이 아니라 남이 건 사전으로 검사한다.**
- 결과는 "맞춤법이 틀렸다"는 오판이다. **컴파일 에러 없음. 런타임 예외 없음. 로그 한 줄 안 남는다.** 같은 입력을 다시 넣으면 정상으로 나오니 재현도 안 된다.

예제 파일 `Item5Example.java`가 이 장면을 `CountDownLatch`로 순서를 고정해 재현한다. 한국어 사전을 걸어 둔 스레드에서 `isValid("사과")`가 항상 `false`로 나온다.

테스트 쪽 문제도 같은 뿌리다. 테스트에서 특수 어휘 사전을 쓰려면 `setDictionary`를 부를 수밖에 없는데, 그 순간 그 변경은 같은 JVM에서 뒤따르는 **모든 테스트에 남는다.** 테스트를 단독으로 실행하면 통과하고 전체로 돌리면 실패하는, 실행 순서에 의존하는 테스트가 만들어진다.

근본 원인은 하나다. **하나뿐인 자원을 모든 호출자가 공유하는데, 그 자원을 고를 권한만 호출자에게 반쯤 열어 준 구조**이기 때문이다.

## 원리 — invokestatic은 대상 클래스를 상수 풀에 박아 넣는다

"유연하지 않다"는 말은 추상적으로 들리지만 바이트코드에는 구체적인 흔적이 있다.

```
// SpellChecker.isValid("사과")
ldc           #2        // String 사과
invokestatic  #3        // Method SpellChecker.isValid:(Ljava/lang/String;)Z
```

`invokestatic`의 피연산자는 상수 풀의 메서드 참조이고, 거기에는 **`SpellChecker`라는 클래스 이름이 그대로 적혀 있다.** 호출 지점이 컴파일될 때 대상이 확정되고, 실행 중에 다른 구현으로 바뀔 여지가 없다. 클래스 안의 `new KoreanDictionary()`도 마찬가지로 `new` 옵코드의 피연산자에 `KoreanDictionary`가 박힌다.

반면 주입받은 필드로 호출하면 이렇게 된다.

```
// dictionary.contains(word)   — dictionary 의 정적 타입은 Lexicon(인터페이스)
aload_0
getfield      #4        // Field dictionary:LLexicon;
aload_1
invokeinterface #5, 2   // InterfaceMethod Lexicon.contains:(Ljava/lang/String;)Z
```

`invokeinterface`는 **실행 시점에 객체의 실제 클래스에서 메서드를 찾는다.** 상수 풀에 적힌 것은 `Lexicon`이라는 계약뿐이고, 어떤 구현이 실행될지는 그 필드에 무엇이 들어 있느냐가 정한다. 유연성이라는 말의 기계적 실체가 이것이다.

---

# 2단계 — 싱글턴으로 만들어도 마찬가지다

## ① 흔히 쓰는(나쁜) 코드

```java
// 싱글턴을 잘못 사용한 예 — 유연하지 않고 테스트하기 어렵다
public class SpellChecker {
    private final Lexicon dictionary = new KoreanDictionary();

    private SpellChecker() { }
    public static final SpellChecker INSTANCE = new SpellChecker();

    public boolean isValid(String word) { ... }
}
```

1단계와 달리 인스턴스가 하나 있고 필드도 `final`이라, 겉보기에는 개선된 것처럼 보인다.

## ② 무엇이 문제인가

바뀐 것은 인스턴스 개수뿐이고 **자원을 클래스가 고정한다는 사실은 그대로다.** 결국 같은 요청이 오면 같은 곳으로 수렴한다.

- "영어 사전도 지원하라"는 요청이 온다. `INSTANCE`가 하나뿐이라 사전을 둘로 쓸 방법이 없다.
- 누군가 `final`을 떼고 `INSTANCE.setDictionary(...)`를 추가한다. 1단계의 전역 상태 문제로 정확히 되돌아간다.
- 테스트도 마찬가지다. 실제 사전 파일을 수십 MB 읽어 들이는 생성자라면 단위 테스트가 그 로딩을 매번 감당해야 하고, CI 환경에 파일이 없으면 테스트가 실패한다. 이 실패의 원인은 테스트 대상 로직이 아니라 **테스트가 고를 수 없는 자원**이다.

근본 원인은 1단계와 한 글자도 다르지 않다. **클래스가 자원을 고르고 있다.**

## 원리 — 두 방식이 같은 결함인 이유

아이템 3과 아이템 4는 "인스턴스 개수를 통제하라"는 주제였다. 이 아이템은 그것과 직교하는 축을 본다. 개수와 무관하게, `private final Lexicon dictionary = new KoreanDictionary();`라는 한 줄이 클래스 안에 있는 순간 **구현 클래스 이름이 이 클래스의 바이트코드에 박히고, 두 클래스 사이에 컴파일 시점 의존이 생긴다.**

그 결과 `SpellChecker`는 `KoreanDictionary` 없이는 클래스 로딩조차 되지 않는다. 테스트에서 가짜 사전만 쓰고 싶어도 진짜 구현 클래스가 클래스패스에 있어야 한다. 이것이 "테스트하기 어렵다"의 실체다.

정리하면 판단 기준은 인스턴스 개수가 아니라 이것 하나다. **이 클래스의 동작이 어떤 자원을 쓰느냐에 따라 달라지는가.** 달라진다면 그 자원은 클래스가 정할 것이 아니라 밖에서 받아야 한다.

---

# 3단계 — 의존 객체 주입

## ③ 개선된 코드

```java
// 의존 객체 주입은 유연성과 테스트 용이성을 높여 준다
public class SpellChecker {
    private final Lexicon dictionary;

    public SpellChecker(Lexicon dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary);   // Java 7+
    }

    public boolean isValid(String word) { ... }
    public List<String> suggestions(String typo) { ... }
}

// 호출자가 자원을 고른다
SpellChecker korean  = new SpellChecker(new KoreanDictionary());
SpellChecker english = new SpellChecker(new EnglishDictionary());
SpellChecker test    = new SpellChecker(new FakeLexicon());     // 테스트용 가짜
```

바뀐 것은 생성자 매개변수 하나뿐인데 앞의 문제가 전부 사라진다. 자원이 여러 개여도, 의존 관계가 여러 단계로 얽혀 있어도 이 방식은 똑같이 동작한다.

## 원리 — 무엇이 달라졌는가

**불변이 되고 공유가 안전해진다.** `dictionary`가 `final`이므로 생성 후 바뀌지 않는다. JLS 17.5의 `final` 필드 규칙에 따라, 생성자가 정상 종료한 뒤 그 객체의 참조를 얻은 스레드는 `final` 필드의 올바른 값을 **동기화 없이도** 보게 된다. 1단계에서 필요했던 `volatile`이나 `synchronized`가 아예 필요 없어진다. 여러 클라이언트가 같은 `SpellChecker` 인스턴스를 공유해도 안전하다. 물론 주입받은 사전 자체가 불변이거나 스레드 안전해야 한다는 조건은 남는다.

**의존성이 시그니처로 드러난다.** 생성자 매개변수 목록이 곧 "이 클래스가 필요로 하는 것들"의 명세가 된다. `new SpellChecker()`만 보고는 안에서 무엇을 만드는지 알 수 없지만, `new SpellChecker(dictionary)`는 읽는 즉시 알 수 있다. 의존성을 빠뜨리면 컴파일 에러이고, 잘못된 값을 넣으면 `Objects.requireNonNull`이 생성 시점에 잡는다. **문제가 사용 시점이 아니라 생성 시점으로 당겨진다.**

**구현이 아니라 인터페이스에 의존하게 된다.** 매개변수 타입이 `Lexicon`이므로 `SpellChecker`의 바이트코드에는 `KoreanDictionary`라는 이름이 없다. 새 사전 구현을 추가할 때 `SpellChecker`는 재컴파일조차 필요 없다.

주입은 생성자에만 쓸 수 있는 게 아니다. 정적 팩터리(아이템 1)나 빌더(아이템 2)에도 똑같이 적용된다. 자원이 많아지면 빌더로 받는 편이 읽기 좋다.

## 버전 표기

- 생성자 주입 자체: **모든 버전. Java 7 레거시에서도 그대로 사용 가능.**
- `Objects.requireNonNull`: **Java 7+.** Java 6 이하라면 직접 검사한다.

```java
// Java 6 이하
public SpellChecker(Lexicon dictionary) {
    if (dictionary == null) throw new NullPointerException("dictionary");
    this.dictionary = dictionary;
}
```

- `Objects.requireNonNull(obj, "메시지")` 2-인자 형태도 Java 7+. `requireNonNullElse`는 Java 9+.

---

# 확장 — 자원 대신 자원 팩터리를 넘긴다

## ② 왜 팩터리가 필요한가

생성자로 자원 **인스턴스**를 받으면, 그 `SpellChecker`는 평생 그 사전 하나만 쓴다. 대부분은 그걸로 충분하다. 그런데 호출할 때마다 새 자원이 필요하거나, 어떤 자원을 쓸지 호출 시점에야 정해지는 경우가 있다. 책의 예는 타일(`Tile`)을 받아 모자이크를 만드는 메서드다. 타일은 매번 새로 만들어져야 한다.

인스턴스를 받으면 같은 타일이 모자이크 전체에 재사용되어 버린다. 그렇다고 `Class<Tile>`을 받아 리플렉션으로 만들면 생성자 인자를 다룰 수 없고 예외 처리가 지저분해진다.

## ③ 개선된 코드

```java
// Java 8+
Mosaic create(Supplier<? extends Tile> tileFactory) { ... }

// 호출
Mosaic m = create(RedTile::new);                    // 메서드 참조
Mosaic n = create(() -> new BlueTile(size));        // 람다
```

`Supplier<T>`는 인수를 받지 않고 `T`를 반환하는 함수형 인터페이스다. 여기에 **팩터리 메서드 패턴(factory method pattern)** 이 그대로 들어 있다. 무엇을 만들지는 넘기는 쪽이 정하고, 만드는 시점은 받는 쪽이 정한다.

## 원리 — 왜 `? extends Tile`인가

`Supplier<Tile>`로 선언하면 `Supplier<RedTile>`을 넘길 수 없다. 제네릭은 **불공변(invariant)** 이라 `RedTile`이 `Tile`의 하위 타입이어도 `Supplier<RedTile>`은 `Supplier<Tile>`의 하위 타입이 아니기 때문이다. 이 규칙이 없으면 `List<Object> l = new ArrayList<String>()` 같은 대입이 허용되어 타입 안전성이 깨진다.

`? extends Tile`(한정적 와일드카드, bounded wildcard)을 쓰면 "`Tile`이거나 그 하위 타입을 공급하는 무언가"를 모두 받을 수 있다. 공급자(생산자)에게는 `extends`를 쓴다는 규칙이 아이템 31의 PECS(producer-extends, consumer-super)다.

## 버전 표기

- `Supplier<T>`, 람다, 메서드 참조: **Java 8+ / 레거시 7 프로젝트에서는 사용 불가.**
- Java 7 대안은 팩터리 인터페이스를 직접 정의하고 익명 클래스로 구현하는 것이다. 동작은 같고 문법만 길다.

```java
// Java 7
public interface TileFactory { Tile create(); }

Mosaic create(TileFactory tileFactory) { ... }

Mosaic m = create(new TileFactory() {
    @Override public Tile create() { return new RedTile(); }
});
```

- `Supplier` 외에 `Function<T,R>`, `BiFunction`, `IntFunction` 등도 같은 용도로 쓸 수 있다(전부 Java 8+).

---

# 3판(Java 9 기준) 이후 — 현재(Java 21) 시점의 보완

## 스프링은 생성자 주입으로 수렴했다

책은 대거(Dagger), 주스(Guice), 스프링 같은 프레임워크를 "의존 객체 주입을 대신해 주는 도구"로 짧게 소개한다. 그 뒤로 스프링 생태계의 관례가 분명해졌다.

`(스프링 4.3+)` 생성자가 하나뿐인 빈은 `@Autowired`를 붙이지 않아도 생성자 주입이 적용된다. 그래서 요즘 코드는 어노테이션 없이 `private final` 필드와 생성자만 둔다. 필드 주입(`@Autowired private Lexicon dictionary;`)은 다음 이유로 권장되지 않는다.

- 필드를 `final`로 만들 수 없다. 3단계에서 본 `final` 필드의 안전 공개 보장을 잃는다.
- 컨테이너 없이 `new`로 만들면 의존성이 전부 `null`이라, 단순한 단위 테스트에서도 스프링을 띄워야 한다.
- 생성자 매개변수가 늘어나면 "이 클래스가 하는 일이 너무 많다"는 신호가 눈에 보이는데, 필드 주입은 그 신호를 가린다.
- 순환 참조가 기동 시점에 드러나지 않는다. `(스프링 부트 2.6+)` 순환 참조는 기본적으로 금지되었다.

롬복 `@RequiredArgsConstructor`는 `final` 필드를 받는 생성자를 만들어 주므로 이 아이템과 방향이 같다. 다만 생성자가 소스에 보이지 않아서 매개변수가 몇 개까지 늘었는지 체감하기 어렵다는 부작용이 있다.

## record는 주입과 잘 맞지만 프록시와는 맞지 않는다

`(Java 16+)` 레코드는 모든 필드가 `final`이고 정규 생성자가 자동으로 생기므로, 의존성을 담는 그릇으로는 형태가 잘 맞는다. 설정값 묶음을 받는 용도로 특히 쓸모 있다.

그러나 **레코드는 암묵적으로 `final`이라 상속할 수 없다.** 스프링이 `@Transactional`, `@Cacheable`, `@Async` 같은 기능을 위해 CGLIB 프록시를 만들려면 대상 클래스를 상속해야 하는데, 레코드는 그 대상이 될 수 없다. 인터페이스를 구현하면 JDK 동적 프록시를 쓸 수 있지만, 이 조합이 모든 상황에서 문제없이 동작하는지는 **확실하지 않으니** 실제로 쓰기 전에 확인하는 편이 좋다. 안전한 쪽은 서비스 빈은 일반 클래스로, 레코드는 값 객체로 쓰는 것이다.

## 전역 가변 상태를 우회하는 기법도 한계가 있다

1단계의 `setDictionary` 문제를 "스레드마다 따로 저장하면 되지 않나" 하고 `ThreadLocal`로 우회하는 코드를 실무에서 종종 본다. 스레드 간 간섭은 사라지지만 값을 지우지 않으면 스레드 풀에서 다음 요청으로 값이 새고, 어디서 설정됐는지 코드로 추적할 수 없는 숨은 의존성이 된다.

`(Java 21)` 가상 스레드가 들어오면서 이 우회책의 비용이 더 커졌다. 스레드가 수십만 개가 되면 스레드마다 딸린 `ThreadLocal` 저장소도 그만큼 늘어난다. 같은 Java 21에 미리보기로 들어온 `ScopedValue`가 이 문제를 겨냥한 API인데, 미리보기 단계라 버전마다 API가 달라질 수 있다. 어느 쪽이든 **애초에 생성자로 넘기면 필요 없는 장치**라는 점이 이 아이템의 요지다.

## 정적 유틸리티가 여전히 맞는 경우

Java 21에서도 자원을 쓰지 않는 순수 함수는 정적 유틸리티가 맞다. `Math.abs`, `Objects.requireNonNull`, `List.of`가 그렇다. 판단 기준은 처음과 같다. **동작이 어떤 자원에 의존하는가**만 보면 된다. 시간(`Clock`), 난수(`Random`), 파일 시스템, 네트워크, DB 연결처럼 테스트에서 고정하고 싶어지는 것이 안에 있으면 그건 자원이고, 주입 대상이다.

---

# 언제 `new`를 그대로 써도 되는가

## 먼저 전제 하나를 고친다 — 싱글턴 여부와 주입 여부는 다른 축이다

"싱글턴으로 쓸 게 아니면 주입해야 하나"라고 물으면 두 가지를 하나로 묶은 것이다. 이 아이템이 문제 삼는 것은 인스턴스가 몇 개냐가 아니라 **누가 자원을 고르느냐**다.

가장 분명한 반례가 스프링이다. 스프링 빈은 대부분 싱글턴 스코프인데도 전부 생성자 주입을 쓴다. 인스턴스는 하나지만 무엇을 넣을지는 컨테이너(즉 바깥)가 정하기 때문에 이 아이템을 위반하지 않는다. 반대로 인스턴스를 매번 새로 만들더라도 생성자 안에서 `new KoreanDictionary()`를 하고 있으면 그대로 위반이다.

## 판단 기준 두 가지

**기준 1 — 그 객체가 값·자료구조인가, 협력자인가.**

`new ArrayList<>()`, `new StringBuilder()`, `new Order(items, address)`, `LocalDate.of(2026, 9, 16)` 같은 것은 데이터를 담거나 계산 중간에 쓰는 도구다. 이런 객체를 주입받으려 들면 오히려 코드가 이상해진다. 반면 `PaymentGateway`, `Lexicon`, `UserRepository`처럼 **내가 일을 시키는 상대**는 협력자(collaborator)이고, 이 아이템이 말하는 자원이다.

**기준 2 — 테스트나 다른 환경에서 다른 것으로 바꾸고 싶어지는가.**

바꾸고 싶어지는 것에는 공통점이 있다. 외부 세계에 닿는다는 점이다. 네트워크, DB, 파일 시스템, 현재 시각(`Clock`), 난수(`Random`), 메시지 큐가 전부 여기 해당한다. `new Random()`을 코드 안에 박아 두면 테스트에서 결과를 고정할 수 없고, `LocalDate.now()`를 직접 부르면 월말 로직을 검증할 방법이 없다. 이런 것은 개수와 무관하게 주입 대상이다.

두 기준 모두 "아니오"면 `new`를 그대로 쓴다.

```java
public class OrderService {
    private final PaymentGateway gateway;   // 협력자 + 테스트에서 바꾼다 → 주입
    private final Clock clock;              // 외부 세계(시각)      → 주입 (Clock 은 Java 8+)

    public OrderService(PaymentGateway gateway, Clock clock) { ... }

    public Receipt place(List<Item> items) {
        List<Line> lines = new ArrayList<>();          // 자료구조 → new 로 충분
        Money total = new Money(0, KRW);               // 값 객체   → new 로 충분
        LocalDate today = LocalDate.now(clock);        // 시각은 주입받은 clock 에서 얻는다
        ...
    }
}
```

`(Java 8+)` `Clock`은 정확히 이 목적으로 추가된 클래스다. 테스트에서는 `Clock.fixed(...)`를 주입한다. `(Java 7)` 에는 `Clock`이 없으므로 `interface TimeProvider { long currentTimeMillis(); }` 같은 인터페이스를 직접 정의해 같은 구조를 만든다.

## 원리 — `new`는 없어지는 게 아니라 한곳으로 모인다

주입을 쓴다고 `new`가 사라지지는 않는다. 누군가는 결국 구현체를 만들어야 한다. 달라지는 것은 **그 `new`가 어디에 있느냐**다.

```java
// 조립은 한곳에서 — 손으로 엮는 경우 보통 main
public static void main(String[] args) {
    Lexicon dictionary = new KoreanDictionary();
    SpellChecker checker = new SpellChecker(dictionary);
    new Server(checker).start();
}
```

이렇게 객체 그래프를 조립하는 지점을 조립 루트(composition root)라고 부른다. 스프링에서는 컨테이너가 그 역할을 한다. 즉 의존 객체 주입은 `new`를 금지하는 기법이 아니라, **구현 클래스 이름이 코드 곳곳에 흩어지지 않고 한 곳에만 남게 하는 기법**이다. 1단계에서 본 바이트코드 관점으로 말하면, `new KoreanDictionary()`가 만드는 컴파일 시점 의존을 조립 루트 하나로 몰아넣는 것이다.

## 과용하면 생기는 일

- **구현이 하나뿐인 인터페이스가 양산된다.** `FooService` / `FooServiceImpl` 쌍이 프로젝트에 수십 개 생기는 패턴이 대표적이다. 바꿔 끼울 일도 없고 테스트에서 가짜로 만들 일도 없다면 인터페이스 없이 클래스를 그대로 주입해도 된다.
- **생성자 매개변수가 계속 늘어난다.** 값 객체까지 주입하려 들면 금방 열 개가 넘는다. 그 시점에는 주입 방식을 고민할 게 아니라 클래스를 쪼개야 한다.
- **모든 의존성을 가짜로 바꾼 테스트만 남는다.** 심화 섹션 마지막 항목과 같은 이야기다.

한 줄로 정리하면, 실무에서는 **"테스트에서 이걸 다른 것으로 바꾸고 싶어질까?"** 를 스스로에게 묻고, 답이 예면 주입하고 아니오면 `new`를 쓰면 된다.

---

# 심화 / 예외 상황

**의존성이 너무 많아지면 그건 설계 신호다.** 주입으로 바꾸면 생성자 매개변수가 늘어난다. 매개변수가 대여섯 개를 넘기 시작하면 빌더를 쓸 게 아니라(아이템 2는 값 객체 이야기다) 그 클래스가 책임을 너무 많이 지고 있는 건 아닌지 먼저 봐야 한다. 주입은 의존성을 **드러내는** 도구이지 줄여 주는 도구가 아니다.

**주입받은 객체가 가변이면 불변의 이점이 사라진다.** `final Lexicon dictionary`는 참조가 바뀌지 않음을 보장할 뿐, 그 사전 객체 내부가 바뀌지 않는다는 보장은 아니다. 주입한 사전에 `addWord` 같은 메서드가 있고 여러 스레드가 공유한다면, 1단계와 똑같은 공유 상태 문제가 한 겹 안쪽에서 재현된다. 주입받는 자원은 불변이거나 스레드 안전해야 한다.

**프레임워크 없이도 성립한다.** 의존 객체 주입은 라이브러리나 컨테이너가 필요한 기법이 아니다. 매개변수로 넘기는 것이 전부이고, 스프링 같은 도구는 그 연결을 대신 해 줄 뿐이다. 작은 프로그램에서 컨테이너를 도입할 이유는 없다. 다만 객체 그래프가 커지면 손으로 엮는 코드가 길어지므로 그때 도구를 고려한다.

**생성 시점에 무거운 작업을 하면 안 된다.** 생성자에서 DB에 붙거나 수십 MB 파일을 읽는 자원이라면, 주입 자체는 옳아도 그 자원을 만드는 비용이 문제로 남는다. 자원을 지연 생성하고 싶다면 자원 대신 팩터리(`Supplier`)를 주입하는 확장 섹션의 방식이 맞다.

**테스트에서 진짜 구현을 쓰는 게 나을 때도 있다.** 주입 덕분에 가짜 객체를 넣을 수 있게 되지만, 모든 의존성을 가짜로 바꾼 테스트는 "내가 짠 가짜가 내 기대대로 동작하는지"만 검증하게 되기 쉽다. 빠르고 부수효과 없는 구현이라면 진짜를 쓰는 편이 낫다. 가짜로 바꿀 가치가 큰 것은 느리거나(네트워크, DB), 비결정적이거나(시간, 난수), 재현하기 어려운(예외 상황) 자원이다.

---

## 핵심 키워드

- [의존 객체 주입](../Keywords/dependency-injection.md) — 자원을 밖에서 받아 넣는 기법과 세 가지 주입 방식
- [Supplier와 팩터리 주입](../Keywords/supplier.md) — 자원 대신 자원을 만드는 방법을 넘기기
- [한정적 와일드카드](../Keywords/bounded-wildcard.md) — `Supplier<? extends Tile>`이 필요한 이유와 PECS
- [유틸리티 클래스](../Keywords/utility-class.md) — 1단계에서 부정되는 형태이자, 순수 함수에는 여전히 맞는 형태
- [싱글턴 패턴](../Keywords/singleton-pattern.md) — 2단계에서 부정되는 형태. 인스턴스 개수와 자원 선택은 다른 축이다

## 함께 보면 좋은 아이템

아이템 1~6은 2장 "객체 생성과 파괴"의 한 덩어리다. 3과 4가 인스턴스 개수를 통제하는 방법이었다면, 5는 **그 통제가 오히려 해가 되는 경우**를 지적하며 방향을 튼다. 그래서 책이 3·4 바로 뒤에 5를 배치했다.

- **아이템 3. private 생성자나 열거 타입으로 싱글턴임을 보증하라 / 아이템 4. 인스턴스화를 막으려거든 private 생성자를 사용하라** — 이 아이템의 1·2단계가 정확히 그 둘을 반례로 든다. 세 아이템을 이어서 보면 "싱글턴과 유틸리티 클래스는 언제 쓰고 언제 쓰면 안 되는가"가 한 문장으로 정리된다.
- **아이템 1. 생성자 대신 정적 팩터리 메서드를 고려하라 / 아이템 2. 생성자에 매개변수가 많다면 빌더를 고려하라** — 3단계의 주입은 생성자 말고 정적 팩터리나 빌더로도 받을 수 있다. 자원이 여러 개로 늘어나면 아이템 2로 넘어가는 지점이 생긴다.
- **아이템 31. 한정적 와일드카드를 사용해 API 유연성을 높이라** — 확장 섹션의 `Supplier<? extends Tile>`이 왜 `Supplier<Tile>`이면 안 되는지를 정면으로 다룬다. PECS 규칙이 여기 있다.
- **아이템 49. 매개변수가 유효한지 검사하라** — 3단계의 `Objects.requireNonNull(dictionary)`가 이 아이템의 적용이다. 잘못된 의존성을 생성 시점에 잡는다는 발상이 같다.

(아이템 번호는 3판 한국어판 기준으로 기억하고 있으나, 31·49는 책에서 한 번 확인해 주면 확실하다.)

## 확인 질문

3단계의 `SpellChecker`를 그대로 두고, 사전 구현만 아래처럼 바꿨다고 하자. 사용자가 새 단어를 등록할 수 있는 사전이다.

```java
class MutableLexicon implements Lexicon {
    private final Set<String> words = new HashSet<>();
    public void addWord(String w) { words.add(w); }      // 외부에서 호출 가능
    public boolean contains(String w) { return words.contains(w); }
}

// 스프링 빈 하나를 여러 요청이 공유한다
SpellChecker shared = new SpellChecker(new MutableLexicon());
```

`SpellChecker`의 `dictionary` 필드는 여전히 `final`이다. 그런데도 1단계에서 본 것과 같은 종류의 문제가 다시 생길 수 있다. 어떤 호출이 그 문제를 만들고, `final`이 무엇까지만 지켜 주기에 막지 못하는 걸까?
