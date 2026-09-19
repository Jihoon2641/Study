# 기본 생성자 (default constructor)

## 한 줄 정의
클래스에 생성자 선언이 하나도 없을 때, 컴파일러가 자동으로 추가해 주는 매개변수 없는 생성자.

## 도입 버전
(모든 버전) JLS 8.8.9가 규정하는 컴파일러 동작이다. Java 7 레거시에서도 동일하다.
- 레코드의 정규 생성자 자동 생성(Java 16+)은 이와 다른 규칙이다. 컴포넌트를 매개변수로 받고, 레코드보다 좁은 접근 수준을 가질 수 없다.

## 무엇을 하는가
규칙은 세 가지다.

1. **생성자 선언이 하나도 없을 때만** 자동 추가된다. 매개변수를 받는 생성자를 하나라도 선언하면 추가되지 않는다. 그래서 인자 있는 생성자를 추가한 뒤 기존의 `new Foo()` 호출이 깨지는 일이 생긴다.
2. **접근 수준은 클래스의 접근 수준을 그대로 따른다.** `public class`면 `public` 생성자, package-private 클래스면 package-private 생성자다. `protected class`나 `private class`는 톱레벨에 존재할 수 없지만, 중첩 클래스라면 그 수식자를 따른다.
3. **본문은 상위 클래스의 인자 없는 생성자를 호출하는 `super()` 한 줄**이다. 상위 클래스에 인자 없는 생성자가 없으면 컴파일 에러가 난다.

생성자는 클래스 파일에서 `<init>`이라는 이름의 메서드로 저장되므로, "생성자를 안 썼다"는 것은 `<init>`이 없다는 뜻이 아니다. `javap -p`로 확인할 수 있다.

```
public class MathUtils {
  public MathUtils();        // 소스에는 없다
    Code:
       0: aload_0
       1: invokespecial #1   // Method java/lang/Object."<init>":()V
       4: return
}
```

Javadoc에도 일반 생성자와 똑같이 표시되므로, 읽는 사람은 설계자가 의도해서 연 것인지 실수로 열린 것인지 구분할 수 없다. 인스턴스화를 막으려면 `private` 생성자를 명시적으로 하나 선언해야 하는 이유다.

## 최소 사용 예
```java
class A { }                               // 컴파일 결과: A() 가 package-private 으로 생긴다
public class B { }                        // 컴파일 결과: public B() 가 생긴다
public class C { public C(int x) { } }    // 생성자를 선언했으므로 C() 는 생기지 않는다
// new C();  → 컴파일 에러: constructor C in class C cannot be applied to given types
```

## 자주 하는 오해 / 헷갈리는 짝
- **생성자를 안 쓰면 생성자가 없는 클래스가 된다** — 반대다. 안 쓸 때만 생긴다.
- **기본 생성자는 항상 `public`이다** — 클래스의 접근 수준을 따른다. package-private 클래스의 기본 생성자는 package-private이다.
- **기본 생성자 vs 인자 없는 생성자(no-arg constructor)** — 기준은 "누가 썼는가". 개발자가 직접 쓴 `public Foo() { }`는 인자 없는 생성자이고, 기본 생성자는 컴파일러가 만든 것이다. JPA 명세가 요구하는 것은 "인자 없는 생성자"이므로 직접 선언해도 된다.
- **필드 초기화 식은 기본 생성자에 안 들어간다** — 들어간다. 인스턴스 필드 초기화 식과 인스턴스 초기화 블록은 `super()` 호출 뒤에 모든 생성자 본문 앞에 삽입된다.

## 등장하는 아이템
- [아이템 4. 인스턴스화를 막으려거든 private 생성자를 사용하라](../Item4/item-4-noninstantiable-utility-class.md) — 유틸리티 클래스가 의도치 않게 인스턴스화 가능해지는 원인으로 등장한다.
