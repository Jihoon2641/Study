# 아이템 14. Comparable을 구현할지 고려하라

## 한 줄 결론

순서가 명확한 값 클래스라면 `Comparable`을 구현하되, `compareTo` 안에서 **두 값을 빼지 마라**. `Integer.compare` 같은 정적 compare 메서드나 `Comparator` 생성 메서드를 써라. 뺄셈은 오버플로 한 번으로 정렬·탐색 전체를 **예외 없이 조용히** 망가뜨린다.

## 신입 눈높이 설명

`compareTo`는 `Object`의 메서드가 아니다. 이게 아이템 10~13(equals, hashCode, toString, clone)과 아이템 14의 가장 큰 차이다. `compareTo`는 `Comparable` 인터페이스에 딱 하나 들어 있는 메서드이고, "나는 나와 같은 타입의 다른 인스턴스와 순서를 비교할 줄 안다"는 **선언**이다.

이 선언 하나를 붙이는 순간 `Arrays.sort`, `Collections.sort`, `List.sort`, `TreeSet`, `TreeMap`, `Collections.max/min`, 이진 탐색까지 자바 플랫폼의 정렬 기반 기능 전부가 공짜로 딸려온다. 반대로 붙이지 않으면 "이 클래스만 `TreeSet`에 못 넣는다"가 된다. 자바 라이브러리의 값 클래스와 모든 열거 타입은 이미 구현하고 있기 때문에, 빠지는 건 내가 만든 클래스뿐이다.

책이 "구현하라"가 아니라 "구현할지 **고려**하라"라고 쓴 이유는, 순서가 애초에 자연스럽지 않은 타입(예: 색상, 좌표점)에 억지 순서를 박아 넣지 말라는 뜻이다. 그런 경우는 `Comparator`를 별도로 만들어 쓰는 쪽이 맞다.

## 원리 — 규약과, 뺄셈이 규약을 깨는 이유

### compareTo 규약

`sgn(expr)`을 부호 함수(-1, 0, 1)라고 하면 규약은 네 줄이다. 앞 세 줄은 필수, 마지막은 강력 권고다.

1. 대칭성: `sgn(x.compareTo(y)) == -sgn(y.compareTo(x))`
2. 추이성: `x.compareTo(y) > 0 && y.compareTo(z) > 0` 이면 `x.compareTo(z) > 0`
3. 동치 일관성: `x.compareTo(y) == 0` 이면 모든 `z`에 대해 `sgn(x.compareTo(z)) == sgn(y.compareTo(z))`
4. (권고) `(x.compareTo(y) == 0) == x.equals(y)`

앞 세 줄이 `equals` 규약(아이템 10)과 정확히 같은 모양이라는 점이 핵심이다. 규약의 구조가 같으니 **깨지는 방식도 같다**. equals에서 상속으로 값 컴포넌트를 추가하면 대칭성이 깨졌던 것처럼, `compareTo`도 똑같이 깨진다.

### 왜 뺄셈이 위험한가

JLS 15.18.2(정수 뺄셈)는 정수 오버플로·언더플로를 **예외로 알리지 않는다**고 규정한다. `int` 연산 결과는 2^32로 감긴 2의 보수 값이 될 뿐이다. 바이트코드 수준에서도 `isub`는 32비트 wrap-around 연산이고, 오버플로 플래그를 확인하는 절차가 없다.

```java
// 2147483647 - (-1) = 2147483648  → int 범위 초과 → -2147483648
Integer.MAX_VALUE - (-1) == Integer.MIN_VALUE   // true
```

즉 `return hash - o.hash;`는 "왼쪽이 훨씬 크다"를 "왼쪽이 가장 작다"로 뒤집어 보고한다. `Integer.compare`는 내부적으로 `(x < y) ? -1 : ((x == y) ? 0 : 1)`이라 뺄셈 자체를 하지 않으므로 이 문제가 원천적으로 없다.

같은 이유로 부동소수점에도 `>`, `<` 나 뺄셈을 쓰면 안 된다. `Double.compare`는 `-0.0 < 0.0`이고 `NaN`이 그 어떤 값보다 크다고 규정된 **전순서(total order)** 를 준다. 관계 연산자는 `NaN`이 끼면 모든 비교가 `false`라 순서 자체가 정의되지 않는다.

### 깨졌을 때 어떻게 드러나는가 — 두 갈래

**(1) TreeMap / TreeSet: 조용히 틀린다.** 레드-블랙 트리는 "왼쪽 서브트리는 전부 나보다 작다"를 전제로 삽입하고 탐색한다. `compareTo`가 거짓말하면 원소는 엉뚱한 자리에 박히고, 이후 탐색·범위 질의·`first()`는 그 잘못된 자리를 그대로 신뢰한다. 예외는 안 난다. 예제 파일의 `bad.first()`가 `Integer.MAX_VALUE`를 반환하는 게 정확히 이 상황이다.

**(2) Arrays.sort / List.sort: 운이 좋으면 터진다.** Java 7부터 객체 배열 정렬은 TimSort를 쓴다. TimSort는 병합 중 자기 불변식이 깨지면 `IllegalArgumentException: Comparison method violates its general contract!`를 던진다. 원소가 32개 미만이면 이진 삽입 정렬로 처리되어 이 검증에 걸리지도 않는다. 즉 **로컬 테스트 데이터에서는 멀쩡하다가 운영 데이터 크기에서 처음 터지는** 전형적인 버그다. 터지는 쪽이 그나마 낫다. Java 6의 머지소트는 아무 말 없이 잘못 정렬했다.

## 책의 예제 — PhoneNumber

필드가 여럿이면 **가장 중요한 필드부터** 비교하고, 결과가 0이 아니면 즉시 반환한다.

```java
// Java 7+ — Short.compare, Integer.compare 등은 Java 7부터
public int compareTo(PhoneNumber pn) {
    int result = Short.compare(areaCode, pn.areaCode);      // 가장 중요한 필드
    if (result == 0) {
        result = Short.compare(prefix, pn.prefix);          // 두 번째로 중요한 필드
        if (result == 0)
            result = Short.compare(lineNum, pn.lineNum);    // 세 번째로 중요한 필드
    }
    return result;
}
```

Java 8부터는 같은 것을 비교자 생성 메서드 연쇄로 쓸 수 있다.

```java
// Java 8+ / 레거시 7 프로젝트에서는 사용 불가 — 대안은 위 코드
private static final Comparator<PhoneNumber> COMPARATOR =
        comparingInt((PhoneNumber pn) -> pn.areaCode)
            .thenComparingInt(pn -> pn.prefix)
            .thenComparingInt(pn -> pn.lineNum);

public int compareTo(PhoneNumber pn) {
    return COMPARATOR.compare(this, pn);
}
```

첫 람다에만 `(PhoneNumber pn)`처럼 타입을 명시한 건 장식이 아니다. 자바의 타입 추론은 `comparingInt`의 결과 타입을 확정해야 그다음 `thenComparingInt`의 파라미터 타입을 추론할 수 있어서, 이게 없으면 컴파일되지 않는다. 또 `thenComparing`에 **메서드 참조**(`Foo::getName`)를 넘기면 오버로드 모호성 에러가 나기 쉽다. 메서드 참조는 인수 개수를 미리 알 수 없어 `thenComparing(Comparator)`와 `thenComparing(Function)` 중 어느 쪽인지 컴파일러가 못 고르기 때문이다. 람다(`n -> n.name`)는 인수 개수가 드러나므로 안전하다.

## 심화 / 예외 상황

### equals와의 일관성은 "권고"지만, 어기면 컬렉션마다 답이 달라진다

`BigDecimal`이 대표 사례다. `new BigDecimal("1.0")`과 `new BigDecimal("1.00")`은 `equals`로는 다르고 `compareTo`로는 같다.

```java
Set<BigDecimal> hash = new HashSet<>();   // equals/hashCode 기준
Set<BigDecimal> tree = new TreeSet<>();   // compareTo 기준
// 둘 다에 "1.0"과 "1.00"을 넣으면
// hash.size() == 2, tree.size() == 1
```

같은 `Set` 인터페이스를 쓰는데 원소 개수가 달라진다. 구현을 바꿨을 뿐인데 로직이 바뀌는 것이다. 일관성을 못 지키겠다면 그 사실을 **문서화하라**. 책의 문구는 "주의: 이 클래스의 순서는 equals 메서드와 일관되지 않다"이다.

### 상속으로 값 컴포넌트를 추가하면 대칭성이 깨진다

`Comparable`을 구현한 클래스를 확장해 필드를 추가하면 규약을 지킬 방법이 없다. 아이템 10의 equals 문제와 같은 구조이고, 해결책도 같다: 상속 대신 **컴포지션**으로 원래 인스턴스를 필드로 갖고, 그 인스턴스를 반환하는 뷰 메서드를 제공한다.

### 비교자 연쇄의 성능

책은 이 방식이 "약 10% 정도 느려졌다"고 적는다. 람다·메서드 호출 계층이 한 겹 더 생기기 때문이다. 다만 **이 수치를 그대로 믿고 판단하지는 마라** — 측정 환경이 Java 9 시절이고, JIT의 인라이닝이 이 오버헤드를 대부분 흡수하는 경우가 많다. 현재 버전에서 실제로 어느 정도인지는 확실하지 않으니, 정렬이 병목으로 의심될 때만 직접 측정해서 판단하는 게 맞다. 그 전까지는 가독성 좋은 연쇄 방식이 기본값이다.

### 해시코드 순서 비교자

이건 뺄셈 함정에 가장 자주 빠지는 자리다. 해시코드는 음수와 거대한 양수가 흔히 나오므로 오버플로가 이론이 아니라 일상이다.

```java
// 나쁜 예 — 추이성 위반
static Comparator<Object> hashCodeOrder = (o1, o2) -> o1.hashCode() - o2.hashCode();

// 대안 1 (Java 7+)
static Comparator<Object> hashCodeOrder =
        (o1, o2) -> Integer.compare(o1.hashCode(), o2.hashCode());

// 대안 2 (Java 8+)
static Comparator<Object> hashCodeOrder = Comparator.comparingInt(Object::hashCode);
```

## Java 21 기준 보완

`compareTo` 규약 자체는 Java 9 이후 바뀐 것이 없다. 다만 주변 환경은 달라졌다.

- **`record`(Java 16+)는 `Comparable`을 자동으로 구현해 주지 않는다.** `equals`/`hashCode`/`toString`은 만들어 주지만 순서는 "무엇이 중요한 필드인지"를 컴파일러가 알 수 없으므로 만들어 줄 수 없다. 직접 써야 하고, 이때 `static final Comparator` 관용구가 가장 잘 맞는다.

  ```java
  // Java 16+
  public record PhoneNumber(short areaCode, short prefix, short lineNum)
          implements Comparable<PhoneNumber> {
      private static final Comparator<PhoneNumber> COMPARATOR =
              Comparator.comparingInt(PhoneNumber::areaCode)
                        .thenComparingInt(PhoneNumber::prefix)
                        .thenComparingInt(PhoneNumber::lineNum);

      @Override public int compareTo(PhoneNumber pn) { return COMPARATOR.compare(this, pn); }
  }
  ```
  참고로 여기서는 `thenComparingInt`가 오버로드 모호성이 없어(인수 하나짜리 `ToIntFunction`만 받는다) 메서드 참조를 써도 된다. 모호해지는 건 `thenComparing` 쪽이다.

- **`sealed`(Java 17+)는 상속 대칭성 문제를 "없애지는" 않지만 관리 가능하게 만든다.** 하위 타입이 컴파일타임에 고정되므로, 타입 간 순서를 `compareTo` 안에서 전부 열거해 정의할 수 있다. 열린 상속에서는 불가능했던 일이다.

- `Comparator.nullsFirst` / `nullsLast`(Java 8+)로 null을 담는 컬렉션의 순서를 명시할 수 있다. `compareTo` 자체는 인수가 null이면 `NullPointerException`을 던져야 한다는 규약이므로, null 허용은 비교자 쪽에서 해결해야 한다.

- 버전 요약: `Integer/Long/Short/Byte/Character/Boolean.compare`는 **Java 7+**, `Double.compare`/`Float.compare`는 **Java 1.4+**, `Comparator.comparing`·`comparingInt`·`thenComparing`·`naturalOrder`·`reverseOrder`·`nullsFirst`는 **Java 8+**, `List.sort`는 **Java 8+**, `record`는 **Java 16+**, `sealed`는 **Java 17+**.

## 함께 보면 좋은 아이템

- **아이템 10. equals는 일반 규약을 지켜 재정의하라** — compareTo 규약의 대칭성·추이성이 equals 규약과 같은 구조다. 상속으로 값 컴포넌트를 추가할 때 깨지는 이유도, 컴포지션으로 푸는 해법도 동일하다.
- **아이템 11. equals를 재정의하려거든 hashCode도 재정의하라** — 해시코드 순서 비교자에서 뺄셈을 쓰는 실수가 나오는 지점이고, `HashSet`(equals 기준)과 `TreeSet`(compareTo 기준)이 갈리는 BigDecimal 사례의 반대쪽 절반이다.
- **아이템 18. 상속보다는 컴포지션을 사용하라** — Comparable 구현 클래스를 확장하면 대칭성을 지킬 수 없다. 그 대안이 이 아이템이다.
- 아이템 10~14는 하나의 덩어리다. equals, hashCode, toString, clone, compareTo — 전부 "라이브러리가 말없이 호출하는 규약"이고, 어겼을 때 컴파일러가 아니라 컬렉션이 대신 벌을 준다는 점이 같다.

## 확인 질문

예제의 `BadNode`에서 `compareTo` 코드는 그대로 두고 `hash` 필드 타입만 `int`에서 `short`로 바꾸면 이 버그는 사라질까? 사라진다면 왜 사라지는지, 그런데도 책의 `PhoneNumber`가 `short` 필드에 굳이 `Short.compare`를 쓰는 이유는 뭐라고 보는지 한 줄씩 답해 보라.
