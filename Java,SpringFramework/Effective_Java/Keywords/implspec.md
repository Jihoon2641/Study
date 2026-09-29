# @implSpec (Implementation Requirements)

## 한 줄 정의
메서드가 내부에서 어떤 재정의 가능 메서드를 어떻게 사용하는지(자기사용)를 하위 클래스 작성자에게 약속하는 javadoc 태그. 생성된 문서에서는 "Implementation Requirements" 절로 보인다.

## 도입 버전
- 이펙티브 자바 3판에 따르면 Java 8에서 도입되어 Java 9부터 JDK 문서에서 본격적으로 쓰였다.
- 책 기준으로는 javadoc 기본 태그가 아니어서 `-tag "implSpec:a:Implementation Requirements:"` 옵션을 켜야 문서에 나온다. Java 10~21의 javadoc 도구가 옵션 없이 인식하는지는 확실하지 않다. 옵션을 붙여 두면 버전과 상관없이 안전하다.
- Java 7 레거시: 태그 대신 javadoc 본문에 "구현 요구사항:" 문단을 직접 쓰면 같은 역할을 한다.

## 무엇을 하는가
JDK 문서는 메서드 설명을 세 종류로 나눠 쓴다.
- 본문(API 명세): 모든 사용자에게 하는 약속. "무엇을 하는가".
- `@implSpec`: 이 클래스를 상속하는 사람에게 하는 약속. "이 구현은 내부에서 무엇을 호출하는가". 하위 클래스가 재정의할 때 기대도 된다.
- `@implNote`: 약속이 아닌 참고 정보. 현재 구현의 세부 사항이라 다음 버전에서 바뀔 수 있다.

`@implSpec`으로 적은 내용은 계약이 된다. 상위 클래스 작성자는 그 자기사용 패턴을 다음 버전에서 바꿀 수 없다. 반대로 적지 않은 자기사용은 구현 세부이므로 하위 클래스가 기대면 안 된다. 취약한 기반 클래스 문제를 이 경계로 관리하는 것이다.

인터페이스의 default 메서드에도 쓴다. 예를 들어 `Collection.removeIf`의 `@implSpec`은 "기본 구현은 `iterator()`로 순회하며 `Iterator.remove()`로 지운다"고 적혀 있어서, 구현 클래스는 `iterator().remove()`를 지원하지 않으면 `removeIf`도 재정의해야 한다는 걸 안다.

## 최소 사용 예
```java
public abstract class Repository<T> {
    /**
     * 여러 건을 저장한다.
     *
     * @implSpec 이 구현은 {@code items}의 각 원소에 대해 {@link #save(Object)}를 순서대로 호출한다.
     * @implNote 현재는 트랜잭션 하나로 묶어 실행한다(바뀔 수 있음).
     */
    public void saveAll(Collection<T> items) {
        for (T item : items) save(item);
    }
    public void save(T item) { /* ... */ }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **@implSpec vs @implNote** — 기준은 "하위 클래스가 이 내용에 기대도 되는가"이다. 기대도 되면(계약) `@implSpec`, 기대면 안 되면(참고) `@implNote`다.
- **문서만 쓰면 끝이다** — 태그는 약속을 적는 도구일 뿐이다. 약속한 자기사용은 이후 버전에서 성능 개선을 이유로도 바꿀 수 없다는 제약이 함께 따라온다.

## 등장하는 아이템
- [아이템 19. 상속을 고려해 설계하고 문서화하라. 그러지 않았다면 상속을 금지하라](../Item19/item-19-design-for-inheritance.md) — 1번 항목 "재정의 가능 메서드의 자기사용을 문서로 남겨라"의 도구다.
