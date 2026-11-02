# Java `Comparator<T>` 상세 설명

## 1. `Comparator`란?

`Comparator<T>`는 **두 객체를 어떤 기준으로 비교할지** 정의하는 인터페이스다. 비교 기준을 객체의 클래스 밖에 둘 수 있어, 같은 객체를 상황에 따라 여러 방식으로 정렬할 때 유용하다. `java.util` 패키지에 있으므로 사용 시 import가 필요하다.

```java
import java.util.Comparator;

Comparator<String> byLength = (a, b) -> Integer.compare(a.length(), b.length());
```

핵심 메서드는 `compare(T a, T b)`다. `Comparator`는 함수형 인터페이스이므로 위처럼 람다식으로 구현할 수 있다.

## 2. `compare`의 반환값

`compare(a, b)`는 첫 번째 인수 `a`를 두 번째 인수 `b`와 비교한다.

| 반환값 | 의미 | 오름차순 정렬 시 |
| --- | --- | --- |
| 음수 | `a`가 `b`보다 작다 | `a`가 앞에 온다 |
| `0` | 정렬 기준상 둘이 같다 | 같은 순위로 취급한다 |
| 양수 | `a`가 `b`보다 크다 | `a`가 뒤에 온다 |

반환값이 꼭 `-1`, `0`, `1`일 필요는 없다. 부호만 중요하다.

## 3. 기본 사용 예제

다음 예제는 학생 목록을 나이순, 이름순으로 각각 정렬한다. `record`를 사용하므로 Java 16 이상에서 실행할 수 있다.

```java
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

record Student(String name, int age) {}

class Main {
    public static void main(String[] args) {
        List<Student> students = new ArrayList<>(List.of(
            new Student("민수", 20),
            new Student("지연", 19),
            new Student("가영", 20)
        ));

        Comparator<Student> byAge = (a, b) -> Integer.compare(a.age(), b.age());
        students.sort(byAge);
        // 지연(19), 민수(20), 가영(20)

        Comparator<Student> byName = (a, b) -> a.name().compareTo(b.name());
        students.sort(byName);
        // 가영, 민수, 지연
    }
}
```

`Student`가 `Comparable`을 구현하지 않아도 `Comparator`를 전달하면 정렬할 수 있다. `List.sort(comparator)`, `Collections.sort(list, comparator)`, `Arrays.sort(array, comparator)`, `Stream.sorted(comparator)` 등에서 사용할 수 있다.

> 이 예제에서는 이름이 `null`이 아니라고 가정한다. 같은 나이의 학생끼리 정렬된 순서를 항상 명시하고 싶다면 두 번째 기준을 추가한다.

## 4. `Comparator`를 간결하게 만드는 메서드

Java가 제공하는 팩터리 메서드와 연결 메서드를 사용하면 비교 기준을 읽기 쉽게 표현할 수 있다.

### `comparing`과 기본형 전용 메서드

```java
Comparator<Student> byName = Comparator.comparing(Student::name);
Comparator<Student> byAge = Comparator.comparingInt(Student::age);
```

`comparing`은 추출한 값의 자연 순서를 사용한다. `int` 필드에는 `comparingInt`, `long`에는 `comparingLong`, `double`에는 `comparingDouble`을 사용할 수 있다.

### `thenComparing`: 동률일 때 다음 기준 적용

```java
Comparator<Student> byAgeThenName =
    Comparator.comparingInt(Student::age)
              .thenComparing(Student::name);

students.sort(byAgeThenName);
// 지연(19), 가영(20), 민수(20)
```

첫 기준의 결과가 `0`일 때만 다음 기준을 비교한다. 동률을 구분할 수 있는 필드를 추가하면 결과를 더 예측하기 쉽다.

### `reversed`: 순서 뒤집기

```java
Comparator<Student> byAgeDescending =
    Comparator.comparingInt(Student::age).reversed();
```

`reversed()`는 **앞에 만든 비교 기준 전체**를 뒤집는다. 나이 내림차순, 이름 오름차순으로 정렬하려면 다음처럼 나이 기준을 먼저 뒤집고 이름 기준을 연결한다.

```java
Comparator<Student> byAgeDescThenNameAsc =
    Comparator.comparingInt(Student::age)
              .reversed()
              .thenComparing(Student::name);
```

반대로 체인 맨 끝에서 `reversed()`를 호출하면 나이와 이름 기준이 모두 뒤집힌다.

### `nullsFirst`와 `nullsLast`: `null` 위치 지정

```java
List<String> names = new ArrayList<>();
names.add("민수");
names.add(null);
names.add("가영");

names.sort(Comparator.nullsLast(Comparator.naturalOrder()));
// [가영, 민수, null]
```

`nullsFirst`는 `null`을 앞에, `nullsLast`는 뒤에 놓는다. 객체 자체가 아니라 비교할 **필드**가 `null`일 수 있다면 필드용 비교기에 적용한다.

```java
Comparator<Student> byNullableName = Comparator.comparing(
    Student::name,
    Comparator.nullsLast(Comparator.naturalOrder())
);
```

## 5. 비교 규칙과 주의점

정렬이 올바르게 동작하려면 비교 결과가 일관되어야 한다.

1. **방향의 일치:** `compare(a, b)`와 `compare(b, a)`의 부호는 반대여야 한다.
2. **추이성:** `a > b`이고 `b > c`이면 `a > c`여야 한다.
3. **동률의 일관성:** `compare(a, b) == 0`이면, 임의의 `c`와 비교할 때 `a`와 `b`는 같은 순서 관계를 가져야 한다.

정수 필드를 비교할 때 뺄셈을 사용하지 않는다.

```java
// 큰 정수에서는 오버플로로 잘못된 결과가 나올 수 있다.
Comparator<Student> unsafe = (a, b) -> a.age() - b.age();

// 안전한 방법
Comparator<Student> safe = Comparator.comparingInt(Student::age);
```

`TreeSet`과 `TreeMap`은 전달된 `Comparator`의 결과가 `0`이면 같은 원소나 키로 판단한다. 예를 들어 학생을 나이로만 비교하는 `Comparator`를 `TreeSet`에 사용하면 나이가 같은 학생은 이름이 달라도 둘 다 저장되지 않는다.

```java
var studentsByAge = new java.util.TreeSet<>(Comparator.comparingInt(Student::age));
studentsByAge.add(new Student("민수", 20));
studentsByAge.add(new Student("가영", 20));
System.out.println(studentsByAge.size()); // 1
```

두 학생을 모두 저장하려면 나이 다음에 이름 등 중복을 구분할 기준을 추가한다. 또한 정렬 기준으로 사용하는 값이 컬렉션에 저장된 뒤 바뀌면 정렬 상태가 어긋날 수 있으므로 주의한다.

## 6. `Comparable`과의 차이

| 구분 | `Comparable<T>` | `Comparator<T>` |
| --- | --- | --- |
| 비교 메서드 | `compareTo(T other)` | `compare(T a, T b)` |
| 구현 위치 | 정렬할 클래스 내부 | 클래스 외부의 별도 객체·람다식 |
| 기준 수 | 보통 한 가지 기본 순서 | 필요한 만큼 여러 기준 정의 가능 |
| 정렬 호출 | `list.sort(null)` | `list.sort(comparator)` |

`Comparable`은 객체의 **자연 순서**를 정의한다. `Comparator`는 **호출하는 곳에서 원하는 순서**를 선택한다. 둘 다 존재할 때 `sort(comparator)`처럼 비교기를 명시하면 그 `Comparator`가 사용된다.

예를 들어 `LocalDate`의 자연 순서는 오래된 날짜부터 최신 날짜까지다. 같은 목록을 특정 화면에서 최신 날짜부터 보여주려면 `Comparator.reverseOrder()`를 전달하면 된다.

```java
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class Main {
    public static void main(String[] args) {
        List<LocalDate> dates = new ArrayList<>(List.of(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2025, 12, 25),
            LocalDate.of(2025, 1, 1)
        ));

        dates.sort(null); // LocalDate의 자연 순서: 오래된 날짜 먼저
        System.out.println("기본 순서: " + dates);

        dates.sort(Comparator.reverseOrder()); // 자연 순서의 반대: 최신 날짜 먼저
        System.out.println("역순: " + dates);
    }
}
```

```text
기본 순서: [2025-01-01, 2025-12-25, 2026-01-01]
역순: [2026-01-01, 2025-12-25, 2025-01-01]
```

`LocalDate`가 `Comparable`을 구현해 기본 날짜 순서를 제공한다. `sort(null)`은 그 순서를 사용하고, `sort(Comparator.reverseOrder())`는 그 순서를 뒤집는다.

## 백엔드에서 정렬 방법을 선택하는 기준

| 상황 | 주로 사용하는 것 |
| --- | --- |
| DB에서 조회할 목록을 정렬하고 페이지를 나눔 | SQL `ORDER BY` |
| 이미 메모리에 있는 목록을 정렬함 | `Comparator` 또는 `Comparable` |
| 같은 객체를 화면마다 다른 기준으로 정렬함 | `Comparator` |
| 객체 자체에 명확한 기본 순서가 있음 | `Comparable` |
| `PriorityQueue`, `TreeSet`, `TreeMap`의 순서를 정함 | `Comparator` 또는 `Comparable` |

페이지네이션에서 전체 데이터의 순서가 중요하다면 DB의 `ORDER BY`로 정렬한 뒤 페이지를 가져온다. 조회한 페이지를 Java에서 다시 정렬하면 해당 페이지 내부의 순서만 바뀐다.