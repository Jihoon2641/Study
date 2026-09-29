# 훅 메서드 (hook method)와 템플릿 메서드 패턴 (template method pattern)

## 한 줄 정의
상위 클래스가 알고리즘의 전체 흐름(템플릿 메서드)은 고정하고, 그중 일부 단계를 하위 클래스가 재정의할 수 있는 `protected` 메서드(훅)로 열어 두는 설계. GoF 디자인 패턴 중 하나다.

## 도입 버전
- 설계 패턴이라 버전과 무관하다. Java 1.0+, Java 7 레거시에서도 그대로 쓴다.
- JDK 사례: `AbstractList.removeRange`(Java 1.2+), `java.io.InputStream.read(byte[], int, int)`가 추상 메서드 `read()`를 쓰는 구조(Java 1.0+), `HttpServlet.service`가 `doGet`/`doPost`를 부르는 구조(서블릿 API).

## 무엇을 하는가
두 종류의 메서드가 짝을 이룬다.
- 템플릿 메서드: 알고리즘의 순서를 정의한다. 하위 클래스가 순서를 바꾸지 못하도록 보통 `final`로 둔다. `public`이다.
- 훅 메서드: 알고리즘의 한 단계다. 기본 구현이 있으면 선택적 훅, `abstract`면 필수 단계다. 하위 클래스만 재정의하면 되고 외부 사용자가 부를 일은 없으므로 `protected`로 둔다.

`AbstractList`에서 `clear()`가 템플릿 메서드이고 `removeRange`가 훅이다. 기본 구현은 반복자로 하나씩 지워 배열 기반 리스트에서 O(n²)이지만, `ArrayList`는 훅을 재정의해 `System.arraycopy` 한 번으로 끝낸다.

`protected`는 "하위 클래스에게만 공개"라서 내부처럼 느껴지지만, 다른 패키지의 하위 클래스도 접근할 수 있으므로 공개 API다. 한 번 열린 훅은 이름, 시그니처, 호출 시점을 영원히 지켜야 한다.

## 최소 사용 예
```java
public abstract class ReportGenerator {
    public final String generate() {          // 템플릿 메서드 — 순서를 고정한다
        return header() + body() + footer();
    }
    protected String header() { return "== 보고서 ==\n"; }   // 선택적 훅
    protected abstract String body();                        // 필수 단계
    protected String footer() { return "\n== 끝 ==";  }      // 선택적 훅
}

class SalesReport extends ReportGenerator {
    @Override protected String body() { return "매출 1,000"; }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **훅 vs 전략 패턴(strategy pattern)** — 기준은 "바꾸는 단위가 상속인가 객체인가"이다. 훅은 하위 클래스를 만들어 컴파일 타임에 단계를 바꾸고, 전략은 인터페이스 구현 객체를 주입해 런타임에 바꾼다. 상속의 위험(취약한 기반 클래스)을 피하고 싶으면 전략이 낫다. Java 8+ 에서는 람다로 전략을 넘기기도 쉽다.
- **훅은 많을수록 유연하다** — 열린 훅 하나하나가 지켜야 할 약속이다. 하위 클래스를 직접 만들어 보며 실제로 필요한 훅만 연다.

## 등장하는 아이템
- [아이템 19. 상속을 고려해 설계하고 문서화하라. 그러지 않았다면 상속을 금지하라](../Item19/item-19-design-for-inheritance.md) — 2번 항목 "내부 동작 중간에 끼어들 수 있는 훅을 protected로 공개하라"의 구조다.
- [아이템 20. 추상 클래스보다는 인터페이스를 우선하라](../Item20/item-20-prefer-interfaces.md) — 골격 구현이 기반 메서드(`get`, `size`)를 훅으로 삼아 나머지를 구현하는 템플릿 메서드 패턴이다.
