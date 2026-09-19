# 아이템 2. 생성자에 매개변수가 많다면 빌더를 고려하라
 
## 한 줄 결론
 
자바에는 **명명된 인자(named argument)와 기본값(default value)이 없다.** 빌더는 그 결핍을 메우는 우회로다. 매개변수가 많아질 때 점층적 생성자는 "순서를 사람이 외워야 하는" 문제를, 자바빈즈는 "완성 전 객체가 세상에 노출되는" 문제를 만드는데, 빌더는 **둘 다 피하면서 불변(immutable)을 유지하는 유일한 선택지**다.
 
---
 
## 신입 눈높이 설명
 
파이썬이나 코틀린이라면 이렇게 쓴다.
 
```python
NutritionFacts(serving_size=240, servings=8, sodium=35, carbohydrate=27)
```
 
이름을 붙여서 넘기니 순서를 몰라도 되고, 안 쓴 건 알아서 기본값이 된다. 자바에는 이 문법이 없다. 그래서 빌더는 **`.sodium(35)` 같은 메서드 호출로 "이름 붙인 인자"를 흉내 내고, 빌더 필드의 초기값으로 "기본값"을 흉내 낸다.** 빌더 패턴이 왜 그렇게 생겼는지는 이 관점에서 보면 전부 설명된다.
 
---
 
# 1단계 — 점층적 생성자 패턴이 왜 위험한가
 
## ① 흔히 쓰는(나쁜) 코드
 
```java
public class NutritionFacts {
    private final int servingSize;   // (mL, 필수)
    private final int servings;      // (회, 필수)
    private final int calories;      // (1회 제공량당) 선택
    private final int fat;           // (g) 선택
    private final int sodium;        // (mg) 선택
    private final int carbohydrate;  // (g) 선택
 
    public NutritionFacts(int servingSize, int servings) {
        this(servingSize, servings, 0);
    }
    public NutritionFacts(int servingSize, int servings, int calories) {
        this(servingSize, servings, calories, 0);
    }
    public NutritionFacts(int servingSize, int servings, int calories, int fat) {
        this(servingSize, servings, calories, fat, 0);
    }
    public NutritionFacts(int servingSize, int servings, int calories, int fat, int sodium) {
        this(servingSize, servings, calories, fat, sodium, 0);
    }
    public NutritionFacts(int servingSize, int servings, int calories,
                          int fat, int sodium, int carbohydrate) {
        this.servingSize  = servingSize;
        this.servings     = servings;
        this.calories     = calories;
        this.fat          = fat;
        this.sodium       = sodium;
        this.carbohydrate = carbohydrate;
    }
}
 
// 클라이언트
NutritionFacts cocaCola = new NutritionFacts(240, 8, 100, 0, 35, 27);
```
 
## ② 무엇이 문제인가
 
"읽기 어렵다"는 건 표면적인 문제다. 진짜 문제는 **컴파일러가 아무것도 막아주지 못한다**는 것이다.
 
매개변수가 전부 `int`다. 그래서 신입 개발자가 앞의 두 개를 헷갈려 이렇게 쓴다.
 
```java
// "8회 제공, 240mL씩" 이라고 생각하고 순서를 바꿔 씀
NutritionFacts cocaCola = new NutritionFacts(8, 240, 100, 0, 35, 27);
```
 
**컴파일 에러 없음. 런타임 예외 없음. 경고조차 없다.** `servingSize=8, servings=240`인 객체가 아무 저항 없이 만들어진다. 그 결과 총 칼로리 계산이 `100 × 240 = 24000`kcal로 나온다. 실제로 이게 발견되는 시점은:
 
- 단위 테스트를 짠 사람과 이 생성자를 호출한 사람이 같으면, 테스트도 똑같이 뒤바뀐 순서로 짜여 있어서 **테스트가 통과한다.**
- 발견은 대개 화면에 숫자가 이상하게 찍히고 나서다. 그때 코드를 봐도 `new NutritionFacts(8, 240, 100, 0, 35, 27)`은 아무 문제 없어 보인다. 여섯 개 숫자 중 무엇이 무엇인지 알려면 생성자 정의로 점프해야 한다.
여기에 더 나쁜 게 하나 있다. **값을 "설정하지 않겠다"와 "0으로 설정하겠다"를 구분할 수 없다.** `fat=0`을 넘긴 건가, 안 넘겨서 기본값 0이 된 건가? 점층적 생성자에서는 알 방법이 없다. 나중에 "지방 정보 미입력 상품은 화면에서 '-'로 표시"라는 요구가 오면 이 설계는 그대로 막힌다.
 
## 원리 — 왜 컴파일러가 못 잡는가
 
자바의 오버로딩 해소(overload resolution)는 **매개변수의 타입 목록만** 본다. 이름은 보지 않는다. 실제로 컴파일된 클래스 파일의 메서드 디스크립터는 `(IIIIII)V` 이고, 여기에 `servingSize`, `servings` 같은 이름은 들어 있지도 않다(디버그 정보로 `-parameters` 옵션을 줘야 겨우 남는다).
 
즉 **`int` 여섯 개는 컴파일러에게 완벽히 동등하다.** 이건 자바 언어의 한계이지 개발자의 부주의가 아니다. 그래서 "조심하자"로는 절대 해결되지 않는다.
 
---
 
# 2단계 — 자바빈즈 패턴이 왜 더 위험한가
 
## ① 흔히 쓰는(나쁜) 코드
 
읽기 문제를 해결하려고 대부분 여기로 도망친다.
 
```java
public class NutritionFacts {
    private int servingSize  = -1;   // 필수. 기본값 없음을 -1로 표시
    private int servings     = -1;   // 필수
    private int calories     = 0;
    private int fat          = 0;
    private int sodium       = 0;
    private int carbohydrate = 0;
 
    public NutritionFacts() { }
 
    public void setServingSize(int val)  { servingSize = val; }
    public void setServings(int val)     { servings = val; }
    public void setCalories(int val)     { calories = val; }
    public void setFat(int val)          { fat = val; }
    public void setSodium(int val)       { sodium = val; }
    public void setCarbohydrate(int val) { carbohydrate = val; }
}
 
// 클라이언트 — 읽기는 확실히 좋아졌다
NutritionFacts cocaCola = new NutritionFacts();
cocaCola.setServingSize(240);
cocaCola.setServings(8);
cocaCola.setCalories(100);
cocaCola.setSodium(35);
cocaCola.setCarbohydrate(27);
```
 
## ② 무엇이 문제인가
 
**객체 하나를 만드는 데 메서드를 여러 번 호출해야 하고, 그 사이 객체는 "미완성 상태"로 존재한다.** 여기서 두 가지가 터진다.
 
### 문제 (a) — 세터 하나를 빠뜨려도 아무도 모른다
 
`setServings(8)` 한 줄을 지웠다고 하자. 컴파일된다. 실행된다. `servings`는 `-1`인 채로 계산에 들어간다. 총 칼로리가 `100 × -1 = -100`kcal. 예외는 안 나고, 화면에 음수가 찍힌다.
 
점층적 생성자는 최소한 "필수 매개변수를 안 넘기면 컴파일 에러"였다. **자바빈즈는 그 마지막 안전망마저 없앤 것이다.**
 
### 문제 (b) — 다른 스레드가 반쪽짜리 객체를 본다
 
이게 훨씬 심각하고, 실무에서 원인 추적이 가장 어려운 유형이다. 아래처럼 조회 결과를 캐시에 넣는 코드가 있다고 하자.
 
```java
// 스레드 A — 상품 정보를 만들어 공유 캐시에 넣는다
NutritionFacts facts = new NutritionFacts();
sharedCache.put(productId, facts);   // ← 여기서 이미 다른 스레드가 접근 가능해진다
facts.setServingSize(240);
facts.setServings(8);
facts.setCalories(100);
```
 
`put`과 `setCalories` 사이에 스레드 B가 `sharedCache.get(productId)`를 하면, **칼로리가 0인 객체를 정상 객체로 착각하고 쓴다.** 예외도 없고, 재현도 안 된다. 부하가 걸린 운영 환경에서 하루에 몇 건씩 이상한 데이터가 나오는 형태로 나타난다.
 
`put`을 맨 뒤로 옮기면 해결될까? **아니다.** 자바 메모리 모델(JMM) 때문이다.
 
```java
NutritionFacts facts = new NutritionFacts();
facts.setServingSize(240);
facts.setCalories(100);
sharedCache.put(productId, facts);   // 뒤로 옮겼다
```
 
`servingSize`, `calories`는 `final`이 아니다. 그리고 JMM은 **`final`이 아닌 필드에 대해서는 "다른 스레드가 최신 값을 본다"는 보장을 전혀 하지 않는다.** 스레드 A의 쓰기와 스레드 B의 읽기 사이에 happens-before 관계가 없으면, B는 `calories`를 여전히 `0`으로 볼 수 있다. 컴파일러와 CPU가 쓰기 순서를 재배치해도 합법이다.
 
반대로 **`final` 필드는 특별 대우를 받는다.** JLS 17.5(final field semantics)는 "생성자가 정상 종료하는 시점에 freeze 액션이 일어나고, 그 객체 참조를 얻은 스레드는 `final` 필드의 올바른 값을 반드시 본다"고 규정한다. **동기화를 하나도 안 걸어도 보장된다.**
 
정리하면 이렇다. 자바빈즈 패턴은 필드를 `final`로 만들 수 없고, `final`을 포기하는 순간 **언어가 공짜로 주던 안전 공개(safe publication) 보장을 통째로 잃는다.** 이걸 되돌리려면 `volatile`을 붙이거나 `synchronized`로 감싸거나 수동 `freeze()` 메서드를 만들어야 하는데, 마지막 것은 컴파일러가 검증해 줄 수 없어서 아무도 제대로 못 쓴다.
 
---
 
# 3단계 — 빌더 패턴
 
## ③ 개선된 코드
 
```java
public class NutritionFacts {
    private final int servingSize;
    private final int servings;
    private final int calories;
    private final int fat;
    private final int sodium;
    private final int carbohydrate;
 
    public static class Builder {
        // 필수 매개변수 — 빌더 생성자로만 받는다
        private final int servingSize;
        private final int servings;
 
        // 선택 매개변수 — 여기 적은 값이 곧 "기본값"이다
        private int calories     = 0;
        private int fat          = 0;
        private int sodium       = 0;
        private int carbohydrate = 0;
 
        public Builder(int servingSize, int servings) {
            this.servingSize = servingSize;
            this.servings    = servings;
        }
 
        public Builder calories(int val)     { calories = val;     return this; }
        public Builder fat(int val)          { fat = val;          return this; }
        public Builder sodium(int val)       { sodium = val;       return this; }
        public Builder carbohydrate(int val) { carbohydrate = val; return this; }
 
        public NutritionFacts build() {
            return new NutritionFacts(this);
        }
    }
 
    private NutritionFacts(Builder builder) {   // 생성자는 private
        servingSize  = builder.servingSize;
        servings     = builder.servings;
        calories     = builder.calories;
        fat          = builder.fat;
        sodium       = builder.sodium;
        carbohydrate = builder.carbohydrate;
    }
}
 
// 클라이언트
NutritionFacts cocaCola = new NutritionFacts.Builder(240, 8)
        .calories(100)
        .sodium(35)
        .carbohydrate(27)
        .build();
```
 
## 원리 — 이게 왜 앞의 두 문제를 동시에 푸는가
 
세 지점을 짚으면 된다.
 
**필드가 전부 `final`이고 생성자 하나에서 한 번에 채워진다.** → 앞서 말한 `final` 필드 freeze 액션이 적용된다. 이 객체는 어떤 스레드에 어떻게 공개되든 항상 완전한 값을 보인다. 불변(immutable)이라 스레드 안전도 공짜다.
 
**필수 매개변수는 빌더 생성자로만 받는다.** → `new NutritionFacts.Builder()` 는 컴파일 에러다. 자바빈즈가 잃어버린 "필수값 누락은 컴파일 시점에 막는다"를 되찾았다.
 
**각 세터가 `this`를 반환한다(메서드 연쇄, method chaining).** → 이게 플루언트 API(fluent API)를 만든다. 호출부의 `.sodium(35)`는 결국 "sodium이라는 이름으로 35를 넘긴다"는 명명된 인자의 자바식 표현이다. 순서가 뒤바뀔 수가 없다. `.calories(100).sodium(35)` 와 `.sodium(35).calories(100)` 은 완전히 같은 객체를 만든다.
 
## 불변식 검사는 build()에서
 
빌더의 덜 알려진 핵심 가치가 이것이다. **여러 필드에 걸친 제약(cross-field invariant)을 검사할 자리가 생긴다.**
 
```java
public NutritionFacts build() {
    // 개별 매개변수 검사
    if (servingSize <= 0)
        throw new IllegalArgumentException("servingSize는 양수여야 함: " + servingSize);
 
    // 여러 매개변수에 걸친 불변식 — 생성자만으로는 검사 타이밍을 잡기 어렵다
    if (fat * 9 + carbohydrate * 4 > calories)
        throw new IllegalStateException(
            "지방/탄수화물 열량 합이 총 칼로리를 초과: " + calories);
 
    return new NutritionFacts(this);
}
```
 
빌더 세터에서 검사하면 "아직 다른 값이 안 들어와서" 판단할 수 없다. `build()`는 모든 값이 모인 유일한 시점이다. 그리고 여기서 던지는 예외 덕분에 **`NutritionFacts` 인스턴스는 태어나는 순간부터 항상 유효하다**는 게 보장된다. 유효하지 않은 조합은 아예 객체가 되지 못한다.
 
세터 시점 검사는 `IllegalArgumentException`, `build()` 시점의 조합 검사는 `IllegalStateException` — 관례상 이렇게 나눈다.
 
## 방어적 복사가 필요한 경우
 
컬렉션이나 가변 객체를 받는 빌더라면 `build()`에서 반드시 복사해야 한다.
 
```java
public class Order {
    private final List<String> items;
 
    public static class Builder {
        private final List<String> items = new ArrayList<>();
        public Builder addItem(String item) {
            items.add(Objects.requireNonNull(item));   // Java 7+
            return this;
        }
        public Order build() { return new Order(this); }
    }
 
    private Order(Builder builder) {
        this.items = List.copyOf(builder.items);   // Java 10+ : 진짜 불변 복사
        // Java 9  : Collections.unmodifiableList(new ArrayList<>(builder.items))
        // Java 7  : Collections.unmodifiableList(new ArrayList<String>(builder.items))
    }
 
    public List<String> items() { return items; }
}
```
 
복사하지 않으면 이렇게 깨진다: 빌더로 `Order`를 만든 뒤 **같은 빌더에 `addItem`을 한 번 더 호출하면, 이미 만들어진 `Order`의 목록까지 같이 바뀐다.** 빌더 내부 리스트와 `Order`의 리스트가 같은 객체이기 때문이다. 불변이라고 믿고 캐시에 넣어둔 주문의 품목이 뒤늦게 늘어나는, 추적 거의 불가능한 버그가 된다.
 
## 버전 표기
 
- 위 빌더 코드 전부: **Java 1.5+. Java 7 레거시 프로젝트에서 그대로 사용 가능.** (`Objects.requireNonNull`은 Java 7+)
- `List.copyOf`: **Java 10+.** Java 9는 `List.of(...)` 또는 `Collections.unmodifiableList(new ArrayList<>(...))`, Java 7~8은 후자만.
- 다이아몬드 연산자 `new ArrayList<>()`: Java 7+.
---
 
# 계층적으로 설계된 클래스와 빌더
 
책 후반부의 `Pizza` 예제가 어렵게 느껴지는 지점은 딱 하나다. **자바에는 셀프 타입(self type)이 없다.** 그걸 우회하는 관용구를 봐야 한다.
 
## ② 무엇이 문제인가 — 순진하게 짜면
 
```java
public abstract class Pizza {
    abstract static class Builder {
        EnumSet<Topping> toppings = EnumSet.noneOf(Topping.class);
        Builder addTopping(Topping topping) {   // 반환 타입이 상위 Builder
            toppings.add(topping);
            return this;
        }
        abstract Pizza build();
    }
}
 
public class NyPizza extends Pizza {
    public static class Builder extends Pizza.Builder {
        public Builder size(Size size) { ... return this; }
        @Override public NyPizza build() { return new NyPizza(this); }
    }
}
```
 
이렇게 짜면 이 코드가 **컴파일 에러**다.
 
```java
NyPizza pizza = new NyPizza.Builder(SMALL)
        .addTopping(HAM)     // 반환 타입이 Pizza.Builder 로 좁아진다
        .size(SMALL)         // ← 컴파일 에러: Pizza.Builder 에는 size()가 없다
        .build();
```
 
`addTopping`을 한 번 거치는 순간 정적 타입이 상위 빌더로 떨어져서, 하위 빌더의 메서드를 더는 부를 수 없다. 메서드 연쇄가 상속과 만나면 이 문제가 반드시 생긴다.
 
## ③ 개선된 코드 — 재귀적 타입 한정 + self()
 
```java
public abstract class Pizza {
    public enum Topping { HAM, MUSHROOM, ONION, PEPPER, SAUSAGE }
    final Set<Topping> toppings;
 
    // 재귀적 타입 한정(recursive type bound): "T는 자기 자신을 가리키는 Builder다"
    abstract static class Builder<T extends Builder<T>> {
        EnumSet<Topping> toppings = EnumSet.noneOf(Topping.class);
 
        public T addTopping(Topping topping) {
            toppings.add(Objects.requireNonNull(topping));
            return self();          // this 가 아니라 self()
        }
 
        abstract Pizza build();
 
        // 하위 클래스가 "자기 자신"을 반환하도록 강제 — 셀프 타입 흉내
        protected abstract T self();
    }
 
    Pizza(Builder<?> builder) {
        toppings = builder.toppings.clone();   // 방어적 복사
    }
}
 
public class NyPizza extends Pizza {
    public enum Size { SMALL, MEDIUM, LARGE }
    private final Size size;
 
    public static class Builder extends Pizza.Builder<Builder> {
        private final Size size;
 
        public Builder(Size size) { this.size = Objects.requireNonNull(size); }
 
        @Override public NyPizza build() { return new NyPizza(this); }
        @Override protected Builder self() { return this; }   // 여기서 타입이 확정된다
    }
 
    private NyPizza(Builder builder) {
        super(builder);
        size = builder.size;
    }
}
```
 
이제 이렇게 쓸 수 있다.
 
```java
NyPizza pizza = new NyPizza.Builder(SMALL)
        .addTopping(SAUSAGE)     // 반환 타입이 NyPizza.Builder 로 유지된다
        .addTopping(ONION)
        .build();
```
 
원리는 이렇다. `NyPizza.Builder`가 `Pizza.Builder<Builder>`를 상속하는 순간 타입 매개변수 `T`가 `NyPizza.Builder`로 확정된다. 따라서 `addTopping`의 선언 반환 타입 `T`도 `NyPizza.Builder`가 되고, 연쇄가 끊기지 않는다.
 
`self()`가 필요한 이유는 `return this;` 의 정적 타입이 `Pizza.Builder<T>` 여서 `T`로 자동 변환되지 않기 때문이다. `return (T) this;` 로 캐스팅하면 컴파일은 되지만 비검사 경고(unchecked warning)가 뜬다. **추상 메서드 `self()`를 두면 캐스팅 없이, 하위 클래스가 정확한 타입을 직접 반환하도록 컴파일러가 강제한다.**
 
그리고 `build()`가 각 하위 클래스에서 `NyPizza`, `Calzone` 같은 구체 타입을 반환하는 것 — 이건 **공변 반환 타이핑(covariant return typing)** 이다. 재정의 메서드가 상위 메서드 반환 타입의 하위 타입을 반환할 수 있는 기능으로, **Java 5+** 부터 가능하다. 덕분에 클라이언트가 형변환할 필요가 없다.
 
**버전: 재귀적 타입 한정과 공변 반환 타이핑 모두 Java 5+. Java 7 레거시에서 그대로 사용 가능하다.**
 
---
 
# 3판(Java 9 기준) 이후 — 현재(Java 21) 시점의 보완
 
## record와 빌더의 관계
 
책이 쓰인 시점에는 `record`가 없었다. 지금은 **필드가 적은 불변 값 객체라면 `record`가 빌더보다 낫다.**
 
```java
// Java 16+ — 컴팩트 생성자에서 검증
public record Point(int x, int y) {
    public Point {
        if (x < 0 || y < 0) throw new IllegalArgumentException();
    }
}
```
 
다만 오해하면 안 되는 게 있다. **`record`는 아이템 2가 다루는 문제를 풀지 못한다.** `record NutritionFacts(int servingSize, int servings, int calories, int fat, int sodium, int carbohydrate)` 는 결국 정규 생성자(canonical constructor)에 `int` 여섯 개를 순서대로 넘기는 것이라, 앞에서 본 "순서를 헷갈려도 컴파일된다" 문제가 그대로 남는다.
 
**즉 매개변수가 4개 이상이면 `record`를 쓰더라도 빌더를 얹는 게 맞다.**
 
```java
public record NutritionFacts(int servingSize, int servings, int calories,
                             int fat, int sodium, int carbohydrate) {
    public NutritionFacts {
        if (servingSize <= 0) throw new IllegalArgumentException();
    }
 
    public static Builder builder(int servingSize, int servings) {
        return new Builder(servingSize, servings);
    }
 
    public static final class Builder {
        private final int servingSize, servings;
        private int calories, fat, sodium, carbohydrate;
 
        private Builder(int servingSize, int servings) {
            this.servingSize = servingSize; this.servings = servings;
        }
        public Builder calories(int v)     { this.calories = v; return this; }
        public Builder fat(int v)          { this.fat = v; return this; }
        public Builder sodium(int v)       { this.sodium = v; return this; }
        public Builder carbohydrate(int v) { this.carbohydrate = v; return this; }
 
        public NutritionFacts build() {
            return new NutritionFacts(servingSize, servings, calories, fat, sodium, carbohydrate);
        }
    }
}
```
 
`record`는 `final` 필드·`equals`/`hashCode`/`toString`을 자동으로 주고, 빌더는 호출부 가독성과 불변식 검사를 준다. 역할이 겹치지 않는다.
 
**버전: `record`는 Java 16+ (15에서 프리뷰). Java 7~15 대안은 `final` 필드 + 수동 `equals`/`hashCode`/`toString` + private 생성자, 즉 위의 원래 빌더 코드 그대로다.**
 
`builder()` 정적 팩터리를 두면 클라이언트가 `NutritionFacts.builder(240, 8)...` 로 쓸 수 있다. 아이템 1과 아이템 2가 실제로는 이렇게 붙어서 쓰인다.
 
## 롬복 @Builder — 실무에서 꼭 알아야 할 함정
 
스프링 프로젝트라면 손으로 빌더를 짜는 대신 `@Builder`를 쓸 가능성이 높다. 편하지만 **책의 빌더와 동작이 다른 지점이 두 개** 있다.
 
**함정 (a) — 필수 매개변수를 강제할 수 없다.**
 
```java
@Builder
public class NutritionFacts {
    private final int servingSize;   // 필수인데
    private final int servings;      // 필수인데
    private final int calories;
}
 
NutritionFacts f = NutritionFacts.builder().calories(100).build();
// 컴파일 통과. servingSize=0, servings=0 인 객체가 만들어진다.
```
 
책의 빌더는 필수값을 빌더 생성자로 받아 **컴파일 시점에** 막았다. `@Builder`는 그게 안 된다. 그래서 `@Builder`를 쓸 때는 **필수값 검증을 반드시 런타임으로 옮겨야 한다** — `@Builder`를 클래스가 아니라 검증이 들어간 생성자에 붙이거나, `build()` 이후 별도 검증을 태우는 식으로. 이 차이를 모르고 쓰면 자바빈즈 패턴의 문제 (a)를 그대로 다시 만나게 된다.
 
**함정 (b) — `@Builder.Default` 없는 필드 초기값은 무시된다.**
 
```java
@Builder
public class Config {
    private int timeout = 3000;   // 기본 3초로 의도했지만
}
 
Config c = Config.builder().build();
// c.timeout == 0  ← 초기값이 조용히 사라진다
```
 
`@Builder.Default`를 붙여야 의도대로 동작한다. 붙이지 않으면 경고는 뜨지만 컴파일은 되고, "타임아웃 0 = 무한 대기"로 해석되는 라이브러리를 만나면 운영에서 스레드가 무한정 묶인다.
 
## Optional은 빌더 매개변수로 쓰지 않는다
 
Java 8 이후 `Optional`을 빌더 세터 파라미터에 쓰고 싶은 유혹이 생기는데, 권장되지 않는다. `Optional`은 **반환 타입**으로 쓰라고 만든 타입이고, 매개변수로 받으면 호출부가 `Optional.of(x)`로 감싸는 비용만 늘어난다. 선택 매개변수는 그냥 빌더 필드의 기본값으로 표현하는 게 맞다.
 
---
 
# 심화 / 예외 상황
 
**언제 빌더를 쓰지 않아도 되는가.** 책의 기준은 "매개변수 4개 이상"이다. 이건 그냥 경험칙이라 절대적이지 않지만, 방향은 맞다. 매개변수 2~3개에 타입도 서로 다르면(`String`, `int`, `LocalDate`) 순서를 헷갈릴 여지가 적으니 생성자나 `record`로 충분하다. **반대로 같은 타입이 연속으로 두 개 이상 나오면 개수가 적어도 위험 신호다.** `new Range(int, int)` 는 매개변수 2개지만 `min`/`max`를 뒤집을 수 있다.
 
**성능 비용은 실제로 있다.** 객체 하나를 만들려고 빌더 객체를 먼저 만든다. 초당 수십만 번 객체를 생성하는 경로(고빈도 루프, 저지연 시스템)에서는 무시 못 할 수 있다. 다만 대부분의 웹 애플리케이션에서는 측정되지도 않는 수준이고, JIT의 이스케이프 분석(escape analysis)이 빌더를 스칼라 치환(scalar replacement)으로 없애버리는 경우도 많다. **먼저 재는 게 순서다. 추측으로 빌더를 버리지 말 것.**
 
**API를 나중에 빌더로 바꾸는 건 비싸다.** 이미 배포된 점층적 생성자를 지우면 호출부가 전부 깨진다. 그래서 책이 "생성자에 매개변수가 많다면"이 아니라 "많아질 것 같으면 처음부터"를 강조한다. 실무에서 매개변수는 거의 항상 늘어난다.
 
**빌더 재사용의 위험.** 빌더 하나로 여러 객체를 만들 수 있다는 게 장점으로 소개되지만, 위 `Order` 예제에서 봤듯 컬렉션 필드가 있으면 값이 누적된다. 재사용할 거면 `build()`에서 방어적 복사가 되어 있는지 반드시 확인해야 한다.
 
---
 
## 함께 보면 좋은 아이템
 
- **아이템 1. 생성자 대신 정적 팩터리 메서드를 고려하라** — 1과 2는 "생성자만으로는 부족하다"라는 같은 문제의식의 앞뒤 짝이다. 아이템 1이 "무엇을 반환할지"에 대한 통제권을 가져오는 거라면, 아이템 2는 "무엇을 넘길지"에 대한 통제권을 가져온다. 실제 코드에서는 `Foo.builder()`처럼 둘이 붙어서 쓰인다.
- **아이템 17. 변경 가능성을 최소화하라** — 자바빈즈 패턴을 버려야 하는 근본 이유. 위에서 설명한 `final` 필드의 안전 공개 보장이 이 아이템의 핵심 근거이고, 빌더는 "불변을 유지하면서 생성 편의성을 얻는" 수단이다.
- **아이템 50. 적시에 방어적 복사본을 만들라** — 빌더의 `build()`에서 컬렉션·`Date` 같은 가변 객체를 복사해야 하는 이유. `Pizza` 예제의 `builder.toppings.clone()`이 정확히 이 아이템의 적용이다.
- **아이템 49. 매개변수가 유효한지 검사하라** — 불변식 검사를 `build()`에 몰아넣는 것의 근거. "객체는 태어날 때부터 유효해야 한다"는 원칙이 두 아이템에 걸쳐 있다.
(아이템 번호는 3판 한국어판 기준으로 기억하고 있으나, 17·49·50은 책에서 한 번 확인해 주면 확실하다.)
 
---
 
## 확인 질문
 
위 `Pizza` 예제에서 `Pizza.Builder`의 `toppings` 필드가 `EnumSet`이고, `Pizza` 생성자에서 `builder.toppings.clone()`으로 복사하고 있다.
 
만약 이 `clone()`을 빼고 `toppings = builder.toppings;` 라고 썼다면, **빌더 하나로 피자 두 판을 만드는** 아래 코드에서 정확히 무슨 일이 벌어질까?
 
```java
NyPizza.Builder b = new NyPizza.Builder(SMALL);
NyPizza plain = b.build();                    // 토핑 없는 피자
NyPizza deluxe = b.addTopping(HAM).addTopping(ONION).build();
```