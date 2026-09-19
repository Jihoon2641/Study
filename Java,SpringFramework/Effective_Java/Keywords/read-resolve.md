# readResolve와 역직렬화의 객체 생성 경로

## 한 줄 정의
`readResolve`는 역직렬화가 막 만들어 낸 객체를 `readObject()`의 반환값이 되기 직전에 다른 객체로 바꿔치기할 수 있게 해 주는, 직렬화 명세에 정의된 특수 메서드다.

## 도입 버전
- `Serializable`, `ObjectInputStream` — Java 1.1+
- `readResolve` / `writeReplace` — Java 1.2+
- 역직렬화 필터 `ObjectInputFilter` — Java 9+ (JEP 290, 이후 Java 8·7 업데이트에도 백포트)
- 레코드 직렬화(정규 생성자를 거쳐 복원) — Java 16+

Java 7 레거시에서도 `readResolve`는 그대로 쓸 수 있다.

## 무엇을 하는가
역직렬화는 생성자를 부르지 않는다. 이 사실이 모든 이야기의 출발점이다. OpenJDK `ObjectInputStream`이 일반 객체를 읽는 흐름은 다음과 같다.

1. **객체 생성** — 클래스 계층을 위로 올라가며 `Serializable`이 아닌 첫 번째 상위 클래스를 찾고, **그 클래스의 기본 생성자만** 실행해 대상 타입의 객체를 만든다. 대부분 `Object()`다. 대상 클래스의 생성자 본문과 필드 초기화 식은 실행되지 않는다.
2. **필드 채우기** — 스트림에 기록된 값으로 `transient`가 아닌 필드를 채운다. 클래스에 `readObject`가 있으면 그 메서드가 이 단계를 맡는다.
3. **바꿔치기** — 클래스에 `readResolve`가 있으면 호출하고, 그 반환값을 최종 결과로 삼는다. 1~2단계에서 만든 객체는 참조가 사라지면 GC 대상이 된다.

`readResolve`는 접근 수준과 상관없이 호출되지만, 시그니처는 `Object readResolve() throws ObjectStreamException` 형태여야 한다.

한계는 2단계에 있다. `readResolve`가 실행되기 전에 이미 2단계에서 필드가 채워진다. 그래서 조작된 스트림이 참조 필드 자리에 "지금 만들어지는 객체의 참조를 몰래 저장하는 객체"를 넣으면, 3단계에서 바꿔치기를 해도 공격자는 버려질 객체의 참조를 이미 가지고 있다. 인스턴스 통제용 `readResolve`를 쓴다면 **모든 인스턴스 필드를 `transient`로 선언**해야 하는 이유다. 열거 타입은 1단계부터 `Enum.valueOf`로 기존 상수를 찾으므로 이 문제가 없다.

## 최소 사용 예
```java
public final class Elvis implements Serializable {
    public static final Elvis INSTANCE = new Elvis();
    private transient String song = "Hound Dog";   // 인스턴스 필드는 transient
    private Elvis() { }

    private Object readResolve() {                  // 역직렬화된 가짜 대신 진짜를 반환
        return INSTANCE;
    }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **`private` 생성자에 넣은 검증이 역직렬화에도 적용된다** — 아니다. 생성자가 호출되지 않으니 불변식 검사도 실행되지 않는다. 검증이 필요하면 `readObject`에서 다시 해야 한다.
- **`readResolve` vs `readObject`** — 기준은 "무엇을 결정하는가". `readObject`는 이미 만들어진 객체의 **필드를 어떻게 채울지**를 정하고, `readResolve`는 **어떤 객체를 결과로 돌려줄지**를 정한다.
- **`readResolve` vs `writeReplace`** — `writeReplace`는 직렬화할 때 다른 객체(직렬화 프록시 등)를 대신 기록하고, `readResolve`는 역직렬화 결과를 바꾼다. 둘을 함께 쓰는 것이 직렬화 프록시 패턴이다.
- **열거 타입에도 `readResolve`를 두면 된다** — 열거 타입에서는 선언해도 무시된다. 애초에 필요 없다.

## 등장하는 아이템
- [아이템 3. private 생성자나 열거 타입으로 싱글턴임을 보증하라](../Item3/item-3-singleton.md) — 클래스 방식 싱글턴이 역직렬화로 두 번째 인스턴스를 만들지 않게 막는 방어 수단으로 등장하고, 그 한계가 열거 타입을 권하는 근거가 된다.
- 아이템 89. 인스턴스 수를 통제해야 한다면 readResolve보다는 열거 타입을 사용하라 — `transient`를 빠뜨렸을 때의 공격을 코드로 다룬다. (설명 파일 아직 없음)
