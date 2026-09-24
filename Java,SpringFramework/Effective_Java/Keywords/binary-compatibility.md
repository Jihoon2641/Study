# 바이너리 호환성 (binary compatibility)

## 한 줄 정의
어떤 클래스를 수정해 다시 컴파일했을 때, 그 클래스를 사용하는 다른 클래스를 재컴파일하지 않고도 계속 링크되고 실행되는 성질.

## 도입 버전
- JVM 링킹 모델 자체의 성질이라 모든 버전에 해당한다. JLS 13장(Binary Compatibility)이 어떤 변경이 호환을 깨는지 정의한다.
- Java 7 레거시에도 그대로 적용된다. 오히려 라이브러리 jar만 교체하는 일이 잦은 레거시 환경에서 더 자주 부딪힌다.

## 무엇을 하는가
자바 클래스 파일은 다른 클래스의 멤버를 주소가 아니라 기호 참조(symbolic reference)로 가리킨다. 기호 참조는 "클래스 이름 + 멤버 이름 + 서술자(descriptor)"로 이루어진다.

```
getfield      Point.x:D            // 필드: 이름 x, 타입 double
invokevirtual Point.getX:()D       // 메서드: 이름 getX, 인자 없음, double 반환
```

JVM은 이 참조가 처음 실행될 때 해석(resolution) 단계에서 실제 멤버를 찾는다. 찾지 못하면 링크 오류가 난다. 컴파일 에러가 아니라 실행 중 그 줄에 도달하는 순간 터지는 런타임 오류다.
- 필드를 지우거나 이름·타입을 바꿈 → `NoSuchFieldError`
- 메서드 시그니처를 바꾸거나 지움 → `NoSuchMethodError`
- 접근 수준을 낮춤(`public` → `private`) → `IllegalAccessError`
- 클래스를 인터페이스로 바꿈 → `IncompatibleClassChangeError`

반대로 메서드 본문을 고치거나, `private` 멤버를 바꾸거나, 새 메서드를 추가하는 것은 호환을 유지한다.

## 최소 사용 예
```java
// lib v1:  public class Point { public double x; }
// app:     double v = p.x;      → 바이트코드에 getfield Point.x:D 가 박힘
// lib v2:  public class Point { private double x; public double getX() { return x; } }
// app 재컴파일 없이 v2 jar만 교체 → 실행 중 IllegalAccessError (x가 private이 됨)
// v2에서 x를 아예 지웠다면       → NoSuchFieldError
```

## 자주 하는 오해 / 헷갈리는 짝
- **바이너리 호환 vs 소스 호환** — 기준은 "재컴파일이 필요한가"이다. 소스 호환은 사용처를 다시 컴파일했을 때 에러가 없는 것이고, 바이너리 호환은 재컴파일 없이 돌아가는 것이다. 둘은 서로 독립이다. 예를 들어 메서드 반환 타입을 `void` → `int`로 바꾸면 소스는 호환되지만 서술자가 `()V` → `()I`로 바뀌어 바이너리는 깨진다.
- **컴파일 타임 상수는 예외다** — `static final` 기본 타입·`String` 상수는 사용처에 값이 복사되어 박히므로, 값을 바꿔도 링크 오류 없이 옛 값으로 계속 동작한다. 오류가 없어서 더 발견하기 어렵다.

## 등장하는 아이템
- [아이템 15. 클래스와 멤버의 접근 권한을 최소화하라](../Item15/item-15-minimize-accessibility.md) — 접근 수준을 낮추면 `IllegalAccessError`가 나서 한 번 연 멤버를 되돌리기 어렵다는 근거.
- [아이템 16. public 클래스에서는 public 필드가 아닌 접근자 메서드를 사용하라](../Item16/item-16-accessor-methods.md) — public 필드는 이름과 타입이 호출자 바이너리에 박혀 내부 표현을 바꿀 수 없다는 원리.
