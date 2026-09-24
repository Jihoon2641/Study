# 레코드 (`record`)

## 한 줄 정의
컴포넌트 목록만 선언하면 `private final` 필드, 정규 생성자, 접근자, `equals`/`hashCode`/`toString`을 컴파일러가 만들어 주는, 불변 데이터 운반용 클래스.

## 도입 버전
- `(Java 16+)` 정식 기능이다. Java 14(JEP 359), 15(JEP 384)에서는 프리뷰였다. 정식 도입은 JEP 395.
- Java 7~15 대안: `final` 클래스 + `private final` 필드 + 접근자 + 직접 구현한 `equals`/`hashCode`/`toString`. `Objects.equals`/`Objects.hash`는 Java 7+라서 레거시에서도 쓸 수 있다. 동작은 같지만 컴포넌트를 추가할 때 다섯 곳을 직접 맞춰야 한다는 점이 다르다.

## 무엇을 하는가
`record Point(int x, int y) {}` 한 줄은 다음을 뜻한다.
- `java.lang.Record`를 상속하는 `final` 클래스. 다른 클래스를 상속할 수 없지만 인터페이스는 구현할 수 있다.
- 컴포넌트마다 `private final` 필드와 같은 이름의 `public` 접근자 `x()`. 이름에 `get`이 붙지 않는다.
- 컴포넌트 순서대로 인자를 받는 정규 생성자(canonical constructor).
- 모든 컴포넌트를 비교하는 `equals`/`hashCode`, `Point[x=1, y=2]` 형태의 `toString`.

추가 인스턴스 필드는 선언할 수 없다. 즉 컴포넌트 목록이 곧 상태 전체이자 공개 API다. 클래스 파일에는 `Record` 속성이 기록되어 리플렉션(`Class.getRecordComponents()`)과 직렬화가 이 정보를 쓴다. 레코드의 직렬화는 필드를 직접 채우지 않고 반드시 정규 생성자를 거친다.

## 최소 사용 예
```java
public record Range(int start, int end) {
    public Range {                                   // compact 생성자 — 검증만 쓴다
        if (start > end) throw new IllegalArgumentException(start + " > " + end);
    }
    public int length() { return end - start; }      // 파생 값 메서드는 자유롭게 추가 가능
}

Range r = new Range(1, 5);
r.start();          // 1 — 접근자
// r.start = 3;     // 컴파일 에러: 필드는 private final
```

## 자주 하는 오해 / 헷갈리는 짝
- **레코드는 깊은 불변이다** — 아니다. 필드가 `final`일 뿐이라 컴포넌트가 `List`나 배열이면 내용은 바뀔 수 있다. compact 생성자에서 `List.copyOf`로 복사하고, 배열은 접근자도 재정의해서 복사본을 돌려줘야 한다.
- **레코드 vs 일반 불변 클래스** — 판단 기준은 "내부 표현을 나중에 바꿀 여지가 필요한가"이다. 레코드는 표현이 곧 API라서 필드 구성을 바꾸면 생성자와 접근자가 함께 바뀐다. 표현이 고정된 값·DTO라면 레코드, 표현을 숨겨야 하는 도메인 클래스라면 일반 클래스를 쓴다.
- **레코드는 JavaBean이다** — 접근자가 `getX`가 아니어서 JavaBeans 규약 기반 도구가 인식하지 못할 수 있다. Jackson은 2.12부터 레코드를 공식 지원한다.

## 등장하는 아이템
- [아이템 16. public 클래스에서는 public 필드가 아닌 접근자 메서드를 사용하라](../Item16/item-16-accessor-methods.md) — 접근자를 자동으로 만들어 이 아이템을 대부분 지켜 주지만, 표현 변경이 불가능하다는 문제는 풀지 못한다는 점을 다룬다.
