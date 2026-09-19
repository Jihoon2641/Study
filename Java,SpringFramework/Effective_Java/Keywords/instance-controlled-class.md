# 인스턴스 통제 클래스 (instance-controlled class)

## 한 줄 정의
언제, 몇 개의 인스턴스가 존재할지를 클래스 스스로 결정하고 외부가 임의로 새 인스턴스를 만들 수 없도록 막은 클래스.

## 도입 버전
(모든 버전) 설계 기법이다. 수단은 버전별로 다르다.
- `private` 생성자 + 정적 팩터리/상수 — Java 1.0+
- 열거 타입(`enum`)으로 언어가 보장 — Java 5+
- `public record`로는 불가능 — Java 16+ 레코드는 정규 생성자를 레코드보다 좁게 숨길 수 없다(JLS 8.10.4)

## 무엇을 하는가
보통 클래스는 `new`를 부를 수 있는 사람이면 누구나 인스턴스를 늘릴 수 있다. 인스턴스 통제 클래스는 생성자를 닫고 인스턴스를 내주는 통로를 하나(정적 팩터리나 상수)로 좁혀서, 그 통로가 "이미 있는 걸 줄지, 새로 만들지"를 결정한다.

그 결과 클래스는 다음을 **보장**할 수 있다.
- 0개 — 인스턴스화 불가 유틸리티 클래스
- 1개 — 싱글턴
- 값마다 1개 — 불변 값 클래스에서 `a.equals(b)`이면 반드시 `a == b`. 그래서 `==`로 빠르게 비교할 수 있다.

생성자를 `private`으로 닫는 게 핵심인 이유는 JLS 8.8.7 때문이다. 하위 클래스의 생성자는 반드시 상위 생성자를 호출해야 하므로, 생성자가 전부 `private`이면 외부 상속으로 우회해 인스턴스를 만드는 길까지 막힌다. `enum`은 여기서 한 걸음 더 나가 JLS 8.9가 "열거 상수 외의 인스턴스는 없다"를 보장하며, 리플렉션(`Constructor.newInstance`)으로 만들려 해도 `IllegalArgumentException`으로 거부되고 직렬화도 상수 이름으로 처리된다.

비슷한 발상으로 플라이웨이트(Flyweight) 패턴이 있다. 같은 상태의 객체를 공유해 할당을 줄인다는 점이 같다.

## 최소 사용 예
```java
public final class Flag {
    public static final Flag ON  = new Flag(true);
    public static final Flag OFF = new Flag(false);

    private final boolean value;
    private Flag(boolean value) { this.value = value; }   // 외부 new 차단

    public static Flag valueOf(boolean b) { return b ? ON : OFF; }  // 인스턴스는 영원히 2개
}
// Java 5+ 라면 대부분 enum Flag { ON, OFF } 가 더 간단하고 더 강하게 보장한다
```

## 자주 하는 오해 / 헷갈리는 짝
- **캐시하는 클래스 = 인스턴스 통제 클래스** — 판단 기준은 "보장을 문서로 약속하는가". `Integer.valueOf`는 캐시를 하지만 `Integer`의 public 생성자가 (제거 예정이긴 해도) 남아 있고 캐시 범위도 제한적이라, `Integer`끼리 `==` 비교를 믿으면 안 된다. 반면 `enum`은 약속한다.
- **private 생성자면 완벽하다** — 직렬화(`Serializable`)와 리플렉션이 우회로다. 일반 클래스로 인스턴스 통제를 유지하려면 역직렬화 경로까지 막아야 하고, 그 수고가 크면 `enum`을 쓴다.
- **가변 객체도 통제하면 좋다** — 공유되는 인스턴스에 상태 변경이 생기면 모든 사용처가 영향을 받는다. 값 공유형 통제는 불변 객체에만 쓴다.

## 등장하는 아이템
- [아이템 1. 생성자 대신 정적 팩터리 메서드를 고려하라](../Item1/item-1-static-factory-method.md) — 정적 팩터리의 두 번째 장점(매번 새 객체를 만들지 않아도 된다)이 가능하게 하는 설계로 등장한다.
- [아이템 3. private 생성자나 열거 타입으로 싱글턴임을 보증하라](../Item3/item-3-singleton.md) — "1개"를 보증하는 형태로 등장한다. `private` 생성자만으로는 리플렉션·역직렬화 경로가 남는다는 한계를 구체적으로 다룬다.
- [아이템 4. 인스턴스화를 막으려거든 private 생성자를 사용하라](../Item4/item-4-noninstantiable-utility-class.md) — "0개"를 보증하는 형태로 등장한다. 생성자를 안 쓰면 오히려 컴파일러가 `public` 기본 생성자를 만들어 준다는 점이 출발점이다.
