# 아이템 16. public 클래스에서는 public 필드가 아닌 접근자 메서드를 사용하라

## 한 줄 결론

`public` 클래스가 필드를 직접 노출하면 필드의 이름·타입·내부 표현이 그대로 API가 되고, 값이 쓰이는 순간에 끼어들 방법(검증, 알림, 동기화)이 사라진다. 그래서 필드는 `private`으로 두고 접근자(accessor, getter)와 필요한 경우에만 변경자(mutator, setter)를 제공해야 한다.

`public final`로 불변 필드만 노출하면 불변식은 생성자에서 지킬 수 있으니 덜 위험하다. 하지만 표현을 바꿀 수 없다는 문제와 읽는 순간에 끼어들 수 없다는 문제는 그대로 남는다. 반대로 package-private 클래스나 private 중첩 클래스는 필드를 노출해도 괜찮다. 노출 범위가 패키지 안으로 한정되기 때문이다.

---

## 신입 눈높이 설명

코틀린이나 C#을 써 봤다면 비교하면 쉽다. 두 언어에는 프로퍼티(property)가 있어서, `p.x`라고 쓴 호출 코드는 그대로 두고 선언 쪽에서 `x`를 필드에서 게터·세터가 붙은 프로퍼티로 바꿀 수 있다. 호출하는 쪽 소스는 한 글자도 바뀌지 않는다.

자바에는 이런 장치가 없다. 자바에서 `p.x`는 언제나 필드 접근이고 `p.getX()`는 언제나 메서드 호출이며, 둘은 문법부터 다르다. 그래서 처음에 `public` 필드로 열어 두면, 나중에 "여기에 검증을 넣자"고 결정하는 순간 모든 호출 코드를 `p.x` → `p.getX()`로 고쳐야 한다. 그 호출 코드가 다른 팀, 다른 회사의 코드라면 고칠 방법이 없다.

결국 이 아이템이 하는 말은 하나다. **자바에서는 필드로 열지 메서드로 열지를 나중에 바꿀 수 없으니, 처음부터 메서드로 열어라.** 이 결정의 무게는 클래스가 얼마나 넓게 공개되는지에 비례한다. `public` 클래스라면 무겁고, 패키지 안에서만 쓰는 클래스라면 가볍다.

---

# 1단계 — public 가변 필드는 왜 위험한가

## ① 흔히 쓰는(나쁜) 코드

책의 예제는 필드를 모아 놓는 것 말고는 하는 일이 없는 퇴보한(degenerate) 클래스다.

```java
// 이처럼 퇴보한 클래스는 public이어서는 안 된다
class Point {
    public double x;
    public double y;
}
```

`Point`는 불변식이 딱히 없는 클래스라서 ②의 (b)는 생성자에서 검증하는 `Time` 클래스로 바꿔서 본다. `Time`은 책이 2단계에서 쓰는 예제이기도 하다.

```java
public class BadTime {
    public int hour;
    public int minute;

    public BadTime(int hour, int minute) {
        if (hour < 0 || hour >= 24) throw new IllegalArgumentException("시: " + hour);
        if (minute < 0 || minute >= 60) throw new IllegalArgumentException("분: " + minute);
        this.hour = hour;
        this.minute = minute;
    }
}
```

## ② 무엇이 문제인가

책이 드는 손실은 세 가지다. 캡슐화의 이점을 하나도 누리지 못한다는 한 문장을 셋으로 나눈 것이다.

### 문제 (a) — API를 고치지 않고는 내부 표현을 바꿀 수 없다

`Point`를 v1로 배포했다. 좌표 계산이 대부분 회전이라서 v2에서 극좌표(`r`, `theta`)로 저장하고 싶어졌다. 필드 `x`, `y`를 지우는 순간 이 클래스를 쓰는 모든 코드가 깨진다.

```java
double d = p.x * p.x + p.y * p.y;   // v2: 컴파일 에러 — cannot find symbol: variable x
```

재컴파일하지 않고 jar만 바꿔 끼우면 더 나쁘다. 이 줄에 도달하는 순간 런타임에 `NoSuchFieldError`가 난다(원리 참고). 결국 `x`, `y` 필드는 영원히 남겨야 하고, 극좌표는 그 옆에 중복으로 두면서 두 표현을 동기화해야 한다. 그런데 문제 (c) 때문에 동기화할 방법도 없다.

### 문제 (b) — 불변식을 보장할 수 없다

`BadTime` 생성자는 `new BadTime(10, 75)`를 막는다. 테스트도 이 부분을 검증하고 통과한다. 그런데 몇 달 뒤 누군가 회의 연장 기능을 이렇게 구현한다.

```java
meetingA.minute += 45;   // 10:30 → 10:75. 시 올림을 빠뜨렸다
```

- 컴파일 에러 없음. 런타임 예외 없음. 로그 한 줄 안 남는다.
- 생성자 검증 테스트는 여전히 통과한다. 검증을 거치지 않는 경로가 따로 있다는 사실은 테스트에 드러나지 않는다.
- 증상은 다른 모듈에서 나타난다. 예약 중복 검사가 `hour * 60 + minute`로 비교하면 `10:75`와 `11:15`가 같은 슬롯으로 판정된다. 원인(연장 기능)과 증상(예약 거절)이 서로 다른 팀의 코드에 있어서, 증상을 쫓아가도 원인이 보이지 않는다.

`BadTime`에는 생성자 검증 코드가 있지만 불변식을 지키지는 못한다. 검증을 거치지 않는 쓰기 경로가 필드 개수만큼 열려 있기 때문이다.

### 문제 (c) — 필드에 접근할 때 부수 작업을 끼워 넣을 수 없다

`java.awt` 컴포넌트의 위치가 `Point`에 담겨 있다고 하자. `p.x = 100` 다음에는 화면을 다시 그려야 한다. 하지만 필드 대입은 메서드 호출이 아니어서 끼어들 지점이 없다. 그 밖에 끼어들 수 없는 작업은 다음과 같다.

- 값이 바뀔 때 리스너에 알리기, 파생 값 캐시 무효화하기, 감사 로그 남기기
- 여러 스레드가 동시에 쓸 때 락 걸기. 필드 대입은 `synchronized` 메서드를 거치지 않으므로 클래스 쪽에서는 동기화를 강제할 수 없다.
- 지연 초기화(lazy initialization). 처음 읽을 때 계산하는 방식으로 바꿀 수 없다.

근본 원인은 하나다. **필드는 데이터이지 동작이 아니어서, 클래스가 자기 상태에 대한 통제권을 호출자에게 넘겨 버린다.**

## 원리 — 필드 접근은 바이트코드에 이름과 타입으로 박힌다

`p.x = 10; return p.x + p.getY();`를 컴파일하면 호출하는 쪽 클래스 파일에 다음 명령이 들어간다.

```
aload_1
ldc2_w        #7    // double 10.0d
putfield      #9    // Field Point.x:D          ← 클래스·이름·타입 서술자가 호출자에 박힘
aload_1
getfield      #9    // Field Point.x:D
aload_1
invokevirtual #13   // Method Point.getY:()D    ← 메서드 이름과 시그니처만 박힘
dadd
dreturn
```

(상수 풀 번호 `#7`, `#9` 등은 예시다.) `putfield`/`getfield`의 피연산자는 필드에 대한 기호 참조(symbolic reference)다. 이 참조는 클래스 이름, 필드 이름, 필드 서술자(`D` = double)로 이루어진다. JVM은 링킹 단계에서 이 세 가지로 필드를 찾는다. 그래서 `x`의 이름을 바꾸거나 타입을 `double` → `int`(`I`)로 바꾸면, 재컴파일하지 않은 호출자 클래스는 해석(resolution) 단계에서 `NoSuchFieldError`를 던진다. 필드 이름과 타입이 호출자의 바이너리에 그대로 새겨져 있다는 뜻이다. 필드를 공개하는 것이 곧 표현을 공개하는 것이라는 말이 바이트코드 수준에서는 이런 의미다.

반면 `invokevirtual Point.getY:()D`가 고정하는 것은 메서드 이름과 반환 타입뿐이다. 메서드 본문이 `y` 필드를 읽든, `r * Math.sin(theta)`를 계산하든, 캐시를 확인하든 호출자의 바이트코드는 바뀌지 않는다. 문제 (a)와 (c)가 모두 이 지점에서 풀린다.

"메서드 호출 비용이 아깝다"는 걱정은 JIT를 생각하면 근거가 없다. HotSpot C2는 바이트코드가 작은 메서드(기본 `MaxInlineSize` = 35바이트)를 호출 지점에 인라인한다. 필드 하나를 반환하는 게터는 5바이트 안팎이어서 거의 항상 인라인되고, 기계어 수준에서는 필드를 직접 읽는 것과 같아진다. 그래서 JIT 컴파일이 끝난 뒤의 성능 차이는 사실상 없다.

---

# 2단계 — public final 불변 필드는 덜 위험하지만 여전히 문제다

## ① 흔히 쓰는(나쁜) 코드

책이 드는 예다. 필드를 `final`로 만들면 생성자 말고는 값을 쓸 수 없으니 괜찮지 않을까 하는 발상이다.

```java
public final class Time {
    private static final int HOURS_PER_DAY    = 24;
    private static final int MINUTES_PER_HOUR = 60;

    public final int hour;
    public final int minute;

    public Time(int hour, int minute) {
        if (hour < 0 || hour >= HOURS_PER_DAY)
            throw new IllegalArgumentException("시간: " + hour);
        if (minute < 0 || minute >= MINUTES_PER_HOUR)
            throw new IllegalArgumentException("분: " + minute);
        this.hour = hour;
        this.minute = minute;
    }
    // 나머지 코드 생략
}
```

## ② 무엇이 문제인가

1단계의 문제 (b)는 해결된다. `final` 필드에 대한 `putfield`는 그 클래스의 생성자 안에서만 허용되므로(`t.minute += 45`는 컴파일 에러 `cannot assign a value to final variable minute`), 쓰기 경로가 생성자 하나로 줄어든다. 생성자에서 검증하면 불변식이 지켜진다.

하지만 문제 (a)와 (c)는 그대로 남는다.

- 표현 변경이 여전히 불가능하다. 1단계 원리에서 본 대로 `getfield Time.hour:I`가 호출자에게 박혀 있으므로, 저장 방식을 `minuteOfDay` 하나로 바꾸면 `t.hour`를 읽는 모든 코드가 깨진다. 예를 들어 `Time`을 수백만 개 메모리에 올리게 되어 `short` 하나로 압축하고 싶어져도 할 수 없다.
- 읽는 순간에 끼어들 수 없다. 첫 조회 시 계산하는 지연 초기화, 조회 횟수 측정, 사용 중단(deprecated) 경고 같은 작업을 추가할 곳이 없다.

근본 원인은 1단계와 같다. **`final`은 쓰기 경로를 막을 뿐이고, 표현이 API라는 사실은 바꾸지 못한다.** 책도 이 방식을 "단점이 조금은 줄어드는" 정도로만 평가한다.

---

# 3단계 — private 필드 + 접근자 메서드

## ③ 개선된 코드

책의 `Point` 개선형이다.

```java
class Point {
    private double x;
    private double y;

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double getX() { return x; }
    public double getY() { return y; }

    public void setX(double x) { this.x = x; }
    public void setY(double y) { this.y = y; }
}
```

같은 방식으로 `Time`을 고치면 내부 표현을 바꿀 수 있다는 점이 드러난다. `Item16Example.java`의 `Time`이 이 형태다.

```java
public final class Time {
    private int minuteOfDay;                 // 표현을 바꿨지만 아무도 모른다

    public Time(int hour, int minute) { set(hour, minute); }

    public int hour()   { return minuteOfDay / 60; }
    public int minute() { return minuteOfDay % 60; }

    public void set(int hour, int minute) {  // 모든 쓰기가 이 한 곳을 지난다
        if (hour < 0 || hour >= 24) throw new IllegalArgumentException("시: " + hour);
        if (minute < 0 || minute >= 60) throw new IllegalArgumentException("분: " + minute);
        this.minuteOfDay = hour * 60 + minute;
    }
}
```

## 원리 — 이게 왜 세 문제를 동시에 푸는가

- (a) 표현 변경: 호출자에게 박히는 것은 `invokevirtual Time.hour:()I`뿐이다. 필드가 `(hour, minute)`에서 `minuteOfDay`로 바뀌어도 호출자의 바이너리는 그대로 유효하다.
- (b) 불변식: 쓰기 경로가 `set` 하나다. 1단계의 `meetingA.minute += 45`와 같은 실수를 해도 `t.set(10, 75)` 줄에서 바로 `IllegalArgumentException`이 터진다. 원인이 있는 줄과 증상이 나타나는 줄이 같아진다.
- (c) 부수 작업: 게터·세터 본문이 곧 끼어들 지점이다. 리스너 알림, 락, 지연 초기화를 나중에 추가해도 호출자는 모른다.

주의할 점이 있다. 세터가 있다고 해서 캡슐화가 되는 것은 아니다. 검증 없이 `this.x = x`만 하는 세터를 모든 필드에 열어 두면 불변식 측면에서는 `public` 필드와 거의 다르지 않다. 그나마 나은 점은 (a)와 (c)에 대비할 자리를 확보했다는 것뿐이다. 세터는 정말 필요할 때만 열고, 가능하면 아예 없애서 불변으로 만드는 것이 다음 아이템(17)의 방향이다.

## 버전 표기

- `private` 필드 + 접근자 메서드 패턴: Java 1.0+. Java 7 레거시에서도 그대로 쓸 수 있다. 예제 파일 전체가 Java 7 문법으로 컴파일된다.
- `String.format`(예제의 `toString`): Java 5+
- 게터 이름 규약 `getX()`/`isX()`는 JavaBeans 명세(Java 1.1+)의 관례이고 언어 규칙은 아니다. 책의 `Point`는 `getX()`를 쓰고, 이 문서의 `Time`은 `hour()`처럼 레코드 스타일 이름을 쓴다. 어떤 규약을 따를지는 프레임워크와의 호환성을 보고 정한다(아래 Java 21 보완 참고).

---

# 예외 — package-private 클래스와 private 중첩 클래스는 필드를 노출해도 된다

## 원리 설명

1단계의 세 문제는 모두 "호출자를 내가 고칠 수 없다"는 전제에서 나온다. 클래스가 package-private이면 호출자는 같은 패키지 안에만 있다. 같은 패키지는 보통 같은 모듈, 같은 저장소, 같은 팀의 코드이므로 표현을 바꾸고 싶으면 패키지 안의 사용처를 한 번에 고치면 된다. 바이너리 호환성 문제도 생기지 않는다. 패키지는 함께 컴파일되어 함께 배포되기 때문이다. private 중첩 클래스라면 범위가 바깥 클래스 하나로 더 좁아진다.

이럴 때는 게터 없이 필드를 직접 쓰는 편이 선언도 짧고 사용 코드도 읽기 쉽다. 책도 이 경우 필드 노출이 "클래스가 표현하려는 추상 개념만 올바르게 표현하면 문제없다"고 본다.

```java
public final class Graph {
    // 바깥 클래스 전용 자료 구조. 게터를 만들면 코드만 길어진다
    private static final class Edge {
        final int from;
        final int to;
        final double weight;
        Edge(int from, int to, double weight) { this.from = from; this.to = to; this.weight = weight; }
    }

    private final List<Edge> edges = new ArrayList<>();

    double totalWeight() {
        double sum = 0;
        for (Edge e : edges) sum += e.weight;   // 필드 직접 접근 — 괜찮다
        return sum;
    }
}
```

## 버전 표기

- private 정적 중첩 클래스와 필드 직접 접근: Java 1.1+. 예제의 다이아몬드 연산자 `new ArrayList<>()`는 Java 7+라서 Java 7 레거시에서도 그대로 쓸 수 있다.
- 이 경우는 바로 레코드가 잘 맞는 자리다. `(Java 16+)` `private record Edge(int from, int to, double weight) {}` 한 줄이면 된다. Java 7~15에서는 위처럼 `final` 필드 클래스로 쓴다.
- Java 10 이하에서는 바깥 클래스가 중첩 클래스의 `private` 멤버에 접근할 때 javac가 package-private 합성 접근 메서드(`access$000`)를 생성했다. Java 11(JEP 181, nestmates)부터는 이 메서드가 없어졌다. 위 `Edge`처럼 필드를 package-private으로 두면 버전과 상관없이 합성 메서드가 생기지 않는다.

## 심화 / 예외 상황

**패키지가 커지면 예외가 약해진다.** package-private이라도 패키지 안의 클래스가 수십 개이고 여러 사람이 손대는 패키지라면, 사실상 1단계의 (b) 문제가 패키지 안에서 재현된다. 이 예외는 "고칠 수 있다"는 전제 위에 서 있으므로, 그 전제가 흔들리면 접근자로 돌아가는 편이 낫다.

**package-private 클래스를 나중에 public으로 올릴 때가 위험하다.** 내부용으로 필드를 연 클래스를 "다른 패키지에서도 쓰고 싶다"며 `public`만 붙여 승격하면, 필드까지 그대로 공개 API가 된다. 승격할 때는 필드를 먼저 `private`으로 바꾸고 접근자를 만들어야 한다.

---

# 자바 플랫폼의 반례 — java.awt.Point와 java.awt.Dimension

## 원리 설명

책은 이 규칙을 어긴 자바 플랫폼 라이브러리의 대표 예로 `java.awt` 패키지의 `Point`와 `Dimension`을 든다. 두 클래스는 Java 1.0부터 필드를 `public`으로 노출했고, 호환성 때문에 지금까지 그대로다.

```java
// JDK 소스 발췌 — java.awt.Dimension (핵심만)
public class Dimension extends Dimension2D implements java.io.Serializable {
    public int width;
    public int height;
    // ...
}
```

`Dimension`이 가변이고 필드가 열려 있어서 생기는 비용은 `java.awt.Component`에서 드러난다. 컴포넌트가 자기 크기를 내부 `Dimension` 그대로 돌려주면 호출자가 `d.width = 0`으로 컴포넌트 상태를 망가뜨릴 수 있다. 그래서 `getSize()`는 호출할 때마다 새 객체를 만들어 반환한다.

```java
// JDK 소스 발췌 — java.awt.Component (버전에 따라 세부는 다를 수 있다)
public Dimension getSize() {
    return size();
}

@Deprecated
public Dimension size() {
    return new Dimension(width, height);   // 호출할 때마다 방어적 복사
}

// 할당을 피하고 싶은 호출자를 위해 "결과를 담을 객체를 넘겨받는" 오버로드까지 생겼다
public Dimension getSize(Dimension rv) {
    if (rv == null) {
        return new Dimension(getWidth(), getHeight());
    } else {
        rv.setSize(getWidth(), getHeight());
        return rv;
    }
}
```

레이아웃 계산처럼 `getSize()`를 초당 수만 번 호출하는 코드에서는 이 할당이 GC 부담이 된다. `getWidth()`/`getHeight()`와 `getSize(Dimension rv)`가 뒤늦게(Java 1.2) 추가된 이유가 이것이다. `public` 가변 필드라는 한 번의 설계 결정이 수십 년 동안 API를 우회로로 덮게 만든 사례다. 이 사례는 최적화를 다루는 아이템에서도 다시 등장한다.

## 버전 표기

- `java.awt.Point`, `java.awt.Dimension`의 public 필드: Java 1.0부터 지금(Java 21)까지 그대로 있다.
- `Component.getWidth()`/`getHeight()`/`getSize(Dimension)`: Java 1.2+
- `Component.size()`: Java 1.1부터 deprecated이고 `getSize()`로 대체되었다. forRemoval 표시는 붙지 않았다.

---

# 3판(Java 9 기준) 이후 — 현재(Java 21) 시점의 보완

## record는 이 아이템을 대부분 자동으로 지켜 준다

`(Java 16+)` 레코드는 각 컴포넌트마다 `private final` 필드와 같은 이름의 `public` 접근자를 자동으로 만든다. 2단계의 `Time`은 이렇게 줄어든다.

```java
public record Time(int hour, int minute) {
    public Time {   // compact 생성자 — 검증만 쓰면 필드 대입은 자동
        if (hour < 0 || hour >= 24) throw new IllegalArgumentException("시: " + hour);
        if (minute < 0 || minute >= 60) throw new IllegalArgumentException("분: " + minute);
    }
}
// 사용: t.hour()  — 필드 t.hour 는 private 이라 외부에서 접근 불가
```

불변식은 compact 생성자에서 지키고(문제 b), 접근은 메서드를 거치므로 읽는 순간에 끼어들 자리도 있다(문제 c). 접근자를 직접 재정의하면 된다.

## 하지만 record는 문제 (a)를 풀지 못한다

이 부분이 함정이다. 레코드는 선언된 컴포넌트가 곧 필드이고, 정규 생성자의 시그니처이며, 접근자 목록이다. 이 셋이 하나로 묶여 있다. 그래서 `Time`의 내부 표현을 `minuteOfDay` 하나로 바꾸고 싶다면 `record Time(int minuteOfDay)`로 바꿔야 한다. 그러면 정규 생성자 `new Time(10, 30)`과 접근자 `hour()`, `minute()`가 모두 사라진다. 레코드 본문에 인스턴스 필드를 추가로 선언하는 것도 언어가 금지한다.

즉 레코드는 표현이 곧 API라고 스스로 선언하는 타입이다. 필드가 메서드 뒤에 숨어 있을 뿐, 2단계 `public final` 필드와 같은 이유로 표현을 바꿀 수 없다. 레코드는 표현이 바뀔 일이 없는 순수 데이터 운반체(DTO, 값 객체)에 쓰고, 내부 표현을 바꿀 여지를 남겨야 하는 `public` 도메인 클래스는 3단계처럼 일반 클래스로 쓰는 것이 맞다.

Java 7~15 대안은 3단계의 `final` 클래스 + `private final` 필드 + 접근자다. 레코드가 자동 생성하는 `equals`/`hashCode`/`toString`까지 맞추려면 직접 구현해야 한다(`Objects.hash`, `Objects.equals`는 Java 7+).

## 레코드 접근자 이름은 getX가 아니다

레코드 접근자는 `hour()`이지 `getHour()`가 아니다. JavaBeans 이름 규약(`getX`)으로 프로퍼티를 찾는 도구는 레코드의 프로퍼티를 인식하지 못할 수 있다. Jackson은 2.12부터 레코드를 공식 지원한다. 그 이전 버전에서는 직렬화 결과가 `{}`로 나오거나 역직렬화에 실패하므로, 레코드를 도입할 때 라이브러리 버전을 확인해야 한다.

## Lombok의 @Data / @Setter는 문제 (b)를 되살린다

Lombok `@Getter @Setter`나 `@Data`는 필드를 `private`으로 둔 채 모든 필드에 검증 없는 세터를 만든다. 겉모양은 3단계지만 불변식 측면에서는 1단계 `BadTime`과 같다. `time.setMinute(75)`가 조용히 통과한다. 문제 (a)와 (c)에 대비할 자리는 확보되지만, 세터는 필요한 필드에만 직접 쓰는 것이 이 아이템의 취지에 맞다. `@Value`(모든 필드 `private final` + 게터, 세터 없음)가 오히려 이 아이템과 아이템 17에 가깝다.

---

# 심화 / 예외 상황

**JPA 엔티티의 필드 접근과 프로퍼티 접근.** JPA는 `@Id`를 필드에 붙이면 필드 접근(field access), 게터에 붙이면 프로퍼티 접근(property access)을 쓴다. 필드 접근이면 하이버네이트가 리플렉션으로 `private` 필드를 직접 읽고 쓰므로 세터가 없어도 된다. "JPA 때문에 세터가 필요하다"는 흔한 오해이고, 엔티티에서도 세터를 줄이는 것이 이 아이템의 방향과 맞다.

**public 필드가 정말 필요한 성능 특수 경우.** 책은 이 예외를 명시하지 않는다. JIT 인라인 덕분에 접근자 비용은 사실상 0이어서, 성능을 이유로 `public` 필드를 쓰는 것은 대부분 근거 없는 최적화다. JIT가 켜지기 전(인터프리터 구간)이나 인라인 한도를 넘는 호출 깊이에서는 차이가 날 수 있지만, 이는 측정으로 확인할 문제이지 설계 원칙을 바꿀 이유가 아니다.

**불변식이 여러 필드에 걸쳐 있으면 세터를 필드마다 두면 안 된다.** 예제의 `Time`이 `setHour`/`setMinute`가 아니라 `set(hour, minute)` 하나를 둔 이유다. 필드별 세터는 검증 단위가 필드 하나라서 필드 사이의 관계를 검증하는 순간 호출 순서 문제가 생긴다(아래 확인 질문).

**public static final 상수는 이 아이템의 예외다.** 불변 값을 담은 상수는 필드로 공개해도 된다. 다만 기본 타입이거나 불변 객체를 참조해야 하고, 컴파일 타임 상수는 사용처에 인라인된다는 함정이 있다. 이 내용은 아이템 15 문서의 3-2, 3-3에서 다뤘다.

---

## 핵심 키워드

- [접근자 메서드](../Keywords/accessor-method.md) — 게터·세터가 필드 대신 공개되는 이유와 이름 규약
- [캡슐화](../Keywords/encapsulation.md) — 표현을 숨기고 동작만 공개한다는 원칙
- [바이너리 호환성](../Keywords/binary-compatibility.md) — 필드 변경이 `NoSuchFieldError`로 터지는 원리
- [레코드](../Keywords/record.md) — 접근자를 자동으로 만들지만 표현이 곧 API인 타입
- [자바빈즈 패턴](../Keywords/javabeans-pattern.md) — `getX`/`setX` 이름 규약의 출처

## 함께 보면 좋은 아이템

- **아이템 15. 클래스와 멤버의 접근 권한을 최소화하라** — "public 클래스의 인스턴스 필드는 public이면 안 된다"는 15의 한 줄을 풀어 쓴 것이 이 아이템이다. package-private 예외도 15의 "패키지 안은 내부 구현"이라는 논리에 기대고 있다.
- **아이템 17. 변경 가능성을 최소화하라** — 3단계 원리 끝에서 말한 "세터는 필요할 때만, 가능하면 없애라"의 연장이다. 2단계가 불변 필드로 불변식 문제를 절반 풀었다면, 17은 private 필드와 불변성을 함께 가져가는 방법을 다룬다.
- **아이템 50. 적시에 방어적 복사본을 만들라** — `Component.getSize()`의 매 호출 복사가 이 아이템의 실제 사례다. 접근자로 감춰도 가변 객체를 그대로 돌려주면 1단계의 문제 (b)가 다시 열린다.
- **아이템 67. 최적화는 신중히 하라** — `Dimension`의 가변 public 필드 설계 때문에 `getSize()`가 매번 객체를 만들어야 하는 성능 문제가 여기서 다시 등장한다. API 설계 결정이 성능을 영구히 묶는 예로 쓰인다.

(아이템 번호는 3판 한국어판 기준으로 기억하고 있으나, 50·67은 책에서 한 번 확인해 주면 확실하다.)

## 확인 질문

3단계 방식대로, 구간을 나타내는 `Range`에 필드별 세터를 두고 각 세터에서 `start <= end`를 검증했다. `[1, 5]`인 구간을 `[10, 20]`으로 옮기려고 아래 코드를 실행하면 무슨 일이 벌어질까? 세터 호출 순서를 바꾸면 해결될까, 그리고 이 문제를 근본적으로 없애려면 `Range`의 API를 어떻게 바꿔야 할까?

```java
public void setStart(int s) { if (s > end) throw new IllegalArgumentException(); start = s; }
public void setEnd(int e)   { if (e < start) throw new IllegalArgumentException(); end = e; }

Range r = new Range(1, 5);
r.setStart(10);
r.setEnd(20);
```
