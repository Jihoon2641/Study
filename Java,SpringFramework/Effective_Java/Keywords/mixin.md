# 믹스인 (mixin)

## 한 줄 정의
클래스의 주된 타입과 별개로 "이런 성질도 있다"는 선택적 기능을 덧붙이는(mix in) 타입. 자바에서는 인터페이스로 정의한다.

## 도입 버전
- 자바 언어 기능이 아니라 설계 개념이다. 인터페이스로 표현하므로 Java 1.0+.
- `(Java 8+)` default 메서드로 믹스인이 구현까지 함께 가져올 수 있게 되었다. 스칼라의 트레이트(trait)나 코틀린의 인터페이스 기본 구현과 가까워졌지만, 자바 인터페이스는 상태(인스턴스 필드)를 가질 수 없다는 점이 다르다.

## 무엇을 하는가
`String`의 주된 정체성은 문자열이다. 동시에 `Comparable<String>`(순서를 정할 수 있다), `CharSequence`(문자 시퀀스로 읽을 수 있다), `Serializable`(직렬화할 수 있다)을 구현한다. 이 인터페이스들이 믹스인이다. 어느 것도 `String`이 "무엇인가"를 정의하지 않고, "무엇을 더 할 수 있는가"를 덧붙인다.

믹스인이 추상 클래스일 수 없는 이유는 단일 상속이다. 클래스 파일에는 상위 클래스 자리(`super_class`)가 하나뿐이다. 주된 타입이 이미 그 자리를 차지하고 있으면 믹스인을 끼워 넣을 합리적인 위치가 계층구조에 없다. 인터페이스는 `interfaces[]` 배열에 몇 개든 들어간다.

## 최소 사용 예
```java
// 주된 타입(BaseEntity)은 그대로 두고, 필요한 클래스에만 성질을 덧붙인다
interface Exportable {
    String csvRow();
}

class Order extends BaseEntity implements Exportable, Comparable<Order> {
    public String csvRow() { return id + "," + amount; }
    public int compareTo(Order o) { return Long.compare(amount, o.amount); }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **믹스인 vs 주된 타입 인터페이스** — 기준은 "그 인터페이스를 빼면 클래스의 정체성이 사라지는가"이다. `ArrayList`에서 `List`를 빼면 정체성이 사라지므로 `List`는 주된 타입이다. `RandomAccess`나 `Cloneable`은 빼도 여전히 리스트이므로 믹스인이다.
- **마커 인터페이스도 믹스인인가** — 메서드가 없는 `Serializable`, `RandomAccess` 같은 마커 인터페이스는 행위 없이 성질만 표시하는 믹스인의 특수한 형태다.

## 등장하는 아이템
- [아이템 20. 추상 클래스보다는 인터페이스를 우선하라](../Item20/item-20-prefer-interfaces.md) — 2번 항목 "인터페이스는 믹스인 정의에 안성맞춤이다"와, 1번 예제의 `Exportable`이 믹스인이다.
