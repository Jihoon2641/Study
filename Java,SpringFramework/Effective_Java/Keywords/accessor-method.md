# 접근자 메서드 (accessor method) / 변경자 메서드 (mutator method)

## 한 줄 정의
객체의 필드를 직접 노출하지 않고, 필드 값을 읽는 메서드(접근자, getter)와 바꾸는 메서드(변경자, setter)를 통해서만 상태에 접근하게 하는 방식.

## 도입 버전
- 언어 기능이 아니라 설계 관례다. 모든 자바 버전(1.0+)에서 쓸 수 있고, Java 7 레거시에서도 그대로 쓸 수 있다.
- `getX`/`setX`/`isX` 이름 규약은 JavaBeans 명세(Java 1.1+)에서 나왔다.
- `(Java 16+)` 레코드는 `x()` 형태의 접근자를 자동으로 만든다. 이름에 `get`이 붙지 않는다.

## 무엇을 하는가
필드 대입 `p.x = 10`은 데이터를 직접 쓰는 것이라 클래스가 끼어들 수 없다. `p.setX(10)`은 메서드 호출이라 클래스가 그 순간을 통제한다. 검증, 변경 알림, 동기화, 지연 계산을 모두 메서드 본문에 넣을 수 있다.

바이트코드로 보면 차이가 분명하다. 필드 접근은 호출자 클래스 파일에 `getfield Point.x:D`처럼 필드 이름과 타입을 새긴다. 접근자 호출은 `invokevirtual Point.getX:()D`처럼 메서드 시그니처만 새긴다. 그래서 접근자 뒤의 필드는 이름, 타입, 존재 여부까지 자유롭게 바꿀 수 있다.

비용은 사실상 없다. HotSpot JIT는 바이트코드가 작은 메서드(기본 35바이트 이하)를 호출 지점에 인라인하므로, 단순 게터는 기계어 수준에서 필드 직접 읽기와 같아진다.

## 최소 사용 예
```java
public final class Temperature {
    private double celsius;                       // 표현은 숨긴다

    public double getCelsius()    { return celsius; }
    public double getFahrenheit() { return celsius * 9 / 5 + 32; }   // 파생 값도 같은 모양으로

    public void setCelsius(double c) {
        if (c < -273.15) throw new IllegalArgumentException("절대영도 미만: " + c);
        this.celsius = c;                         // 모든 쓰기가 검증을 지난다
    }
}
```

## 자주 하는 오해 / 헷갈리는 짝
- **게터·세터를 만들면 캡슐화다** — 판단 기준은 "세터가 불변식을 지키는가"이다. 검증 없는 세터를 모든 필드에 열면 불변식 측면에서는 `public` 필드와 같다. 세터는 필요한 것만, 가능하면 0개로 둔다.
- **필드별 세터 vs 묶음 변경 메서드** — 불변식이 여러 필드에 걸쳐 있으면(`start <= end`) 필드별 세터로는 검증 순서 문제가 생긴다. 이때는 `set(start, end)`처럼 한 번에 바꾸는 메서드를 둔다.
- **게터는 필드와 1:1이어야 한다** — 그럴 필요 없다. 위 `getFahrenheit()`처럼 저장하지 않는 값도 같은 형태로 노출할 수 있다. 이것이 표현을 숨긴다는 말의 실제 의미다.

## 등장하는 아이템
- [아이템 16. public 클래스에서는 public 필드가 아닌 접근자 메서드를 사용하라](../Item16/item-16-accessor-methods.md) — 이 아이템의 주제 자체. public 필드의 세 가지 손실을 접근자가 어떻게 막는지 다룬다.
