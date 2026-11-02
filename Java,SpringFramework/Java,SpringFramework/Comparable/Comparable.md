# Java `Comparable<T>` 상세 설명

## 1. `Comparable`이란?

`Comparable<T>`은 객체 **자신의 기본 정렬 순서(자연 순서, natural ordering)**를 정의하는 인터페이스다. `java.lang` 패키지에 있으므로 별도 import 없이 사용할 수 있다.

```java
public interface Comparable<T> {
    int compareTo(T other);
}
```

예를 들어 `Integer`는 숫자 크기순, `String`은 사전식 순서, `LocalDate`는 날짜순으로 비교할 수 있다. 직접 만든 클래스도 `Comparable`을 구현하면 같은 방식으로 정렬할 수 있다.

## 2. `compareTo`의 반환값

`a.compareTo(b)`는 `a`를 `b`와 비교한다.

| 반환값 | 의미 | 오름차순 정렬 시 |
| --- | --- | --- |
| 음수 | `a`가 `b`보다 작다 | `a`가 앞에 온다 |
| `0` | 정렬 기준상 둘이 같다 | 같은 순위로 취급한다 |
| 양수 | `a`가 `b`보다 크다 | `a`가 뒤에 온다 |

정확히 `-1`, `0`, `1`을 반환할 필요는 없다. 음수인지, 0인지, 양수인지만 중요하다.

```java
System.out.println(Integer.valueOf(10).compareTo(20)); // 음수
System.out.println(Integer.valueOf(10).compareTo(10)); // 0
System.out.println(Integer.valueOf(20).compareTo(10)); // 양수
```

## 3. 직접 구현하기

학생을 **나이 오름차순**, 나이가 같으면 **이름 오름차순**으로 정렬해 보자. 아래 예제는 `record`를 사용하므로 Java 16 이상에서 실행할 수 있다.

```java
import java.util.ArrayList;
import java.util.List;

record Student(String name, int age) implements Comparable<Student> {
    @Override
    public int compareTo(Student other) {
        int ageResult = Integer.compare(this.age, other.age);
        if (ageResult != 0) {
            return ageResult;
        }
        return this.name.compareTo(other.name);
    }
}

class Main {
    public static void main(String[] args) {
        List<Student> students = new ArrayList<>(List.of(
            new Student("민수", 20),
            new Student("지연", 19),
            new Student("가영", 20)
        ));

        students.sort(null); // Student.compareTo()를 사용
        students.forEach(System.out::println);
    }
}
```

출력 순서는 `지연(19) → 가영(20) → 민수(20)`이다. `Collections.sort(students)`로도 같은 자연 순서를 사용할 수 있다. `Arrays.sort(array)`도 배열 원소의 자연 순서로 정렬할 수 있다.

`implements Comparable<Student>`의 타입 인자는 비교 대상의 타입이다. 이 선언 덕분에 `compareTo(Student other)`처럼 타입이 정해진 메서드를 구현할 수 있다.

> 이 예제에서는 `name`이 `null`이 아니라고 가정한다. `null` 값도 허용할 계획이라면 별도 처리 기준을 정해야 한다. 일반적인 `compareTo(null)`은 `NullPointerException`을 던진다.

## 4. 올바른 비교 규칙

비교 결과가 일관되지 않으면 정렬이나 정렬 기반 컬렉션이 예상과 다르게 동작할 수 있다.

1. **방향의 일치:** `a.compareTo(b)`가 음수라면 `b.compareTo(a)`는 양수여야 한다.
2. **추이성:** `a > b`이고 `b > c`라면 `a > c`여야 한다.
3. **동률의 일관성:** `a.compareTo(b) == 0`이라면, 임의의 `c`에 대해 `a`와 `c`의 비교 결과 및 `b`와 `c`의 비교 결과는 같은 부호여야 한다.
4. **`equals`와의 일치 권장:** 가능하면 `a.compareTo(b) == 0`과 `a.equals(b)`가 같은 의미가 되도록 만든다.

마지막 규칙은 `Comparable`의 필수 조건은 아니지만 실무에서 중요하다. `TreeSet`과 `TreeMap`은 원소나 키가 중복되는지 판단할 때 비교 결과 `0`을 사용한다. 따라서 `equals()`가 `false`인 두 객체도 `compareTo()`가 `0`이면 정렬 기반 컬렉션에서 같은 원소나 키로 취급될 수 있다.

위 `Student` 예제에서는 `record`가 `name`과 `age`를 모두 사용하는 `equals()`를 생성하고, `compareTo()`도 두 필드를 모두 비교하므로 두 기준이 일치한다.

### `BigDecimal`의 주의 사례

```java
new java.math.BigDecimal("1.0")
    .compareTo(new java.math.BigDecimal("1.00")); // 0

new java.math.BigDecimal("1.0")
    .equals(new java.math.BigDecimal("1.00"));    // false
```

수치상으로는 같지만 소수 자릿수가 달라 `equals()` 결과는 다르다. 따라서 `BigDecimal`을 `TreeSet`이나 `TreeMap`에 사용할 때는 이 차이를 알아야 한다.

## 5. 비교할 때 피해야 할 코드

```java
return this.age - other.age; // 정수 오버플로 가능
```

두 값의 차가 `int` 범위를 넘으면 부호가 잘못될 수 있다. 대신 비교 전용 메서드를 사용한다.

```java
return Integer.compare(this.age, other.age);
```

`long`에는 `Long.compare()`, `double`에는 `Double.compare()`처럼 해당 타입의 비교 메서드를 사용한다. 특히 실수는 `NaN`이나 `-0.0` 같은 값이 있으므로 단순 뺄셈으로 비교하지 않는 편이 안전하다.

## 6. `Comparable`과 `Comparator`의 차이

| 구분 | `Comparable<T>` | `Comparator<T>` |
| --- | --- | --- |
| 기준을 정의하는 위치 | 비교 대상 클래스 내부 | 클래스 외부의 별도 객체나 람다식 |
| 주요 메서드 | `compareTo(T other)` | `compare(T a, T b)` |
| 주요 용도 | 객체의 기본 순서 | 상황에 따라 선택하는 정렬 기준 |

`Student`의 기본 순서가 나이순이어도 특정 상황에서는 이름순으로 정렬할 수 있다.

```java
import java.util.Comparator;

students.sort(Comparator.comparing(Student::name));
```

이때 `Comparator`를 전달했으므로 해당 정렬에서는 `Student.compareTo()` 대신 이름순 기준이 사용된다. 내림차순이나 여러 정렬 기준이 필요할 때도 `Comparator`가 편리하다.

```java
students.sort(
    Comparator.comparingInt(Student::age)
              .reversed()
              .thenComparing(Student::name)
);
```

위 코드는 나이 내림차순, 나이가 같으면 이름 오름차순으로 정렬한다.

## 7. 언제 사용하는가?

`Comparable`은 한 타입에 누구나 납득할 만한 **기본 순서**가 있을 때 적합하다. `List.sort(null)`, `Collections.sort()`, `Arrays.sort()`, `TreeSet`, `TreeMap` 등이 이 순서를 사용할 수 있다. 상황별로 기준이 달라진다면 `Comparator`를 전달하면 된다.

정렬 기준에 사용하는 필드가 객체 생성 후 바뀔 수 있다면 `TreeSet`·`TreeMap`에 넣은 뒤 변경하지 않도록 주의해야 한다. 저장 중 값이 바뀌면 컬렉션 내부의 정렬 상태와 객체의 현재 값이 어긋날 수 있다.

## 백엔드에서 정렬 방법을 선택하는 기준

| 상황 | 주로 사용하는 것 |
| --- | --- |
| DB에서 조회할 목록을 정렬하고 페이지를 나눔 | SQL `ORDER BY` |
| 이미 메모리에 있는 목록을 정렬함 | `Comparator` 또는 `Comparable` |
| 같은 객체를 화면마다 다른 기준으로 정렬함 | `Comparator` |
| 객체 자체에 명확한 기본 순서가 있음 | `Comparable` |
| `PriorityQueue`, `TreeSet`, `TreeMap`의 순서를 정함 | `Comparator` 또는 `Comparable` |

페이지네이션에서 전체 데이터의 순서가 중요하다면 DB의 `ORDER BY`로 정렬한 뒤 페이지를 가져온다. 조회한 페이지를 Java에서 다시 정렬하면 해당 페이지 내부의 순서만 바뀐다.