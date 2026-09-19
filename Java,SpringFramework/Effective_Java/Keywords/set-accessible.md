# setAccessible (`java.lang.reflect.AccessibleObject#setAccessible`)

## 한 줄 정의
리플렉션으로 필드·메서드·생성자를 사용할 때, 자바 언어의 접근 제한자(`private` 등) 검사를 건너뛰도록 요청하는 메서드.

## 도입 버전
- `setAccessible(boolean)` — Java 1.2+
- 모듈 경계에서 `InaccessibleObjectException` 발생, `trySetAccessible()` 추가 — Java 9+
- JDK 내부 API에 대한 강한 캡슐화가 기본값 — Java 16 (JEP 396), 우회 옵션 `--illegal-access` 제거 — Java 17 (JEP 403)
- 리플렉션 내부 구현을 메서드 핸들 기반으로 교체 — Java 18 (JEP 416), 동작은 동일
- `SecurityManager`로 막는 방법 — Java 17에서 제거 예정(JEP 411), 이후 비활성화

## 무엇을 하는가
접근 제한은 세 곳에서 따로 검사된다. 소스를 컴파일할 때 javac가(JLS 6.6), 바이트코드를 링킹할 때 JVM이(JVMS 5.4.4), 그리고 리플렉션 호출 시 `Method.invoke`나 `Constructor.newInstance` 구현 안에서 JDK 코드가 직접 검사한다.

`setAccessible(true)`는 세 번째 검사만 끈다. `AccessibleObject`의 `override` 플래그를 켜면 `newInstance`나 `invoke`가 호출자 권한 확인을 건너뛴다. 리플렉션 호출은 바이트코드의 `invokespecial`이나 `invokevirtual`을 거치지 않으므로 앞의 두 검사와도 무관하다. 그래서 `private` 생성자로 막은 클래스도 인스턴스를 만들 수 있다.

스프링(빈 생성, 필드 주입), JPA 구현체(엔티티 생성, 필드 접근), Jackson·Gson(객체 생성과 필드 설정), 테스트 도구 같은 프레임워크가 이 메서드에 크게 의존한다.

`setAccessible`로도 넘을 수 없는 벽은 두 가지다.
- **모듈** — Java 9+에서 대상 클래스가 이름 있는 모듈에 있고, 그 패키지가 호출자 모듈에 `opens`되지 않았다면 `InaccessibleObjectException`이 난다. 클래스패스 애플리케이션은 전부 이름 없는 모듈에 있어서 서로 자유롭게 열 수 있다.
- **JDK의 의미 규칙** — 열거 타입 생성(`Cannot reflectively create enum objects`)처럼 JDK가 별도로 금지한 동작은 접근 검사를 꺼도 막힌다.

## 최소 사용 예
```java
class Secret {
    private Secret() { }
    private String code = "1234";
}

Constructor<Secret> c = Secret.class.getDeclaredConstructor();
c.setAccessible(true);                 // 끄지 않으면 IllegalAccessException
Secret s = c.newInstance();

Field f = Secret.class.getDeclaredField("code");
f.setAccessible(true);
System.out.println(f.get(s));          // 1234
```

## 자주 하는 오해 / 헷갈리는 짝
- **`private`이면 외부에서 절대 못 쓴다** — 기준은 "소스 코드 수준인가, 리플렉션 수준인가". 컴파일된 코드의 직접 호출은 막히지만, 리플렉션은 같은 모듈(또는 클래스패스) 안이라면 막히지 않는다. `private`은 보안 장치가 아니라 API 경계 선언이다.
- **`final` 필드도 `setAccessible`로 바꿀 수 있다** — 일반 인스턴스 `final` 필드는 `Field.set`으로 바뀌지만, `static final` 필드와 레코드 필드(Java 16+)는 `IllegalAccessException`으로 막힌다. 바뀌더라도 JIT이 이미 상수로 접어 둔 코드에는 반영되지 않을 수 있다.
- **`getDeclaredConstructor` vs `getConstructor`** — `getConstructor`는 `public` 생성자만 찾고, `getDeclaredConstructor`는 접근 수준과 상관없이 그 클래스에 선언된 생성자를 찾는다.

## 등장하는 아이템
- [아이템 3. private 생성자나 열거 타입으로 싱글턴임을 보증하라](../Item3/item-3-singleton.md) — `private` 생성자 싱글턴에 두 번째 인스턴스를 만드는 경로로 등장하고, 생성자 가드가 필요한 이유가 된다.
- [아이템 4. 인스턴스화를 막으려거든 private 생성자를 사용하라](../Item4/item-4-noninstantiable-utility-class.md) — 유틸리티 클래스의 `private` 생성자도 리플렉션으로는 호출되지만, 본문의 `AssertionError`가 `InvocationTargetException`에 감싸여 나오며 차단된다.
