# 아이템 19. 상속을 고려해 설계하고 문서화하라. 그러지 않았다면 상속을 금지하라

## 한 줄 결론

상속을 허용한다는 것은 "내 클래스 안의 재정의 가능한 호출 지점들을 하위 클래스에게 공개 API로 약속한다"는 뜻이다. 그래서 상속을 허용하려면 그 호출 지점(자기사용)을 `@implSpec`으로 문서화하고, 필요한 훅은 `protected`로 열고, 생성자·`clone`·`readObject`에서는 재정의 가능 메서드를 절대 부르지 말아야 한다. 이 비용을 치를 생각이 없으면 `final`이나 private 생성자로 상속을 막아야 한다.

아래 여섯 항목은 따로 떨어진 규칙이 아니라 하나의 흐름이다. 1~3은 "하위 클래스가 안전하게 끼어들 수 있도록 무엇을 공개할지"이고, 4~5는 "끼어들면 안 되는 시점"이며, 6은 "그 모든 걸 할 생각이 없을 때의 선택지"다.

---

# 1. 상속용 클래스는 재정의 가능 메서드의 자기사용을 문서로 남겨야 한다

## 신입 눈높이 설명

아이템 18에서 `InstrumentedHashSet`이 두 배로 센 이유는 "`addAll`이 내부에서 `add`를 부른다"는 사실을 하위 클래스 작성자가 몰랐기 때문이다. 이 아이템의 첫 번째 규칙은 그 사실을 상위 클래스 작성자가 문서에 적으라는 것이다.

일반 사용자에게 보이는 API 문서는 "무엇을 하는가"만 적는다. 상속용 클래스는 여기에 독자가 한 명 더 있다. 하위 클래스 작성자는 "이 메서드가 내부에서 어떤 재정의 가능 메서드를, 어떤 순서로 부르고, 그 결과를 어떻게 쓰는가"까지 알아야 재정의를 올바르게 할 수 있다. 책은 이것이 "좋은 API 문서는 '어떻게'가 아니라 '무엇'을 설명해야 한다"는 격언과 충돌한다고 인정한다. 상속이 캡슐화를 해치기 때문에 생기는 어쩔 수 없는 비용이다.

## 원리 설명

재정의 가능 메서드는 `public`이나 `protected`이면서 `final`이 아닌 메서드다. 상위 클래스 안에서 이런 메서드를 `this.m()`으로 호출하면 `invokevirtual`로 컴파일되어, 런타임에 하위 클래스의 재정의 버전이 실행된다. 즉 이 호출 지점 하나하나가 하위 클래스 코드가 끼어드는 입구다. 입구의 위치와 개수는 소스를 열어 보지 않으면 알 수 없으므로, 문서로 공개하지 않으면 하위 클래스 작성자는 추측으로 재정의해야 한다.

JDK는 이 설명을 `@implSpec` 태그로 적고, 생성된 문서에서는 "Implementation Requirements" 절로 보인다. 책이 드는 예는 `AbstractCollection.remove`다.

```java
// JDK 문서 발췌 — java.util.AbstractCollection.remove(Object) 의 Implementation Requirements
// "This implementation iterates over the collection looking for the specified element.
//  If it finds the element, it removes the element from the collection using the
//  iterator's remove method. Note that this implementation throws an
//  UnsupportedOperationException if the iterator returned by this collection's
//  iterator method does not implement the remove method and this collection
//  contains the specified object."
```

이 문단은 하위 클래스 작성자에게 "`iterator()`를 재정의하면 `remove(Object)`의 동작이 바뀐다"는 사실을 알려 준다. 이제 이 자기사용은 구현 세부가 아니라 계약이다. 상위 클래스 작성자는 다음 버전에서 `remove`를 `iterator()` 없이 구현하도록 바꿀 수 없다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
// 사내 공통 라이브러리 v1 — 상속해서 쓰라고 배포했지만 자기사용을 문서화하지 않았다
public abstract class Repository<T> {
    /** 여러 건을 저장한다. */
    public void saveAll(Collection<T> items) {
        for (T item : items) save(item);   // 내부에서 save 를 부른다 — 문서에는 없다
    }

    /** 한 건을 저장한다. */
    public void save(T item) { /* INSERT */ }
}

// 다른 팀 — 모든 저장에 감사 로그를 남기려고 두 메서드를 다 재정의했다
class AuditedRepository<T> extends Repository<T> {
    @Override public void save(T item) { audit(item); super.save(item); }
    @Override public void saveAll(Collection<T> items) { items.forEach(this::audit); super.saveAll(items); }
}
```

### ② 무엇이 문제인가

```java
auditedRepo.saveAll(List.of(order1, order2));   // 감사 로그 4줄 — 건마다 두 번
```

문제는 두 번 터진다.

- v1: 감사 로그가 두 배로 쌓인다. 아이템 18의 `InstrumentedHashSet`과 같은 현상이다. 하위 클래스 작성자가 `saveAll` 재정의를 지워서 "해결"한다. 이 해결은 문서에 없는 자기사용에 기대고 있다.
- v2: 공통 라이브러리 팀이 성능 개선으로 `saveAll`을 배치 INSERT로 바꾼다. 이제 `save`를 거치지 않는다. 공개 API의 시그니처도 문서도 그대로라서 릴리스 노트에는 "성능 개선"만 적힌다.
- 결과: `saveAll`로 저장한 주문은 감사 로그가 한 줄도 남지 않는다. 컴파일 에러 없음. 런타임 예외 없음. 감사 로그가 비었다는 사실은 몇 달 뒤 감사 때 발견된다.

원인을 만든 사람(v2의 라이브러리 팀)은 어떤 하위 클래스가 이 자기사용에 기대는지 알 방법이 없었고, 증상을 겪는 사람(감사 담당)은 라이브러리 버전이 올라갔다는 사실조차 모른다. 근본 원인은 하나다. **자기사용이 계약인지 구현 세부인지가 문서로 정해지지 않아서, 양쪽이 서로 다른 가정을 하고 있었다.**

### ③ 개선된 코드

```java
public abstract class Repository<T> {
    /**
     * 여러 건을 저장한다.
     *
     * @implSpec 이 구현은 {@code items}의 각 원소에 대해 {@link #save(Object)}를 순서대로 호출한다.
     *           따라서 저장 단위 부가 작업은 {@code save}만 재정의하면 된다.
     */
    public void saveAll(Collection<T> items) {
        for (T item : items) save(item);
    }

    public void save(T item) { /* INSERT */ }
}
```

이제 하위 클래스 작성자는 `save`만 재정의하면 된다는 것을 안다. 라이브러리 팀은 이 계약 때문에 v2에서 `saveAll`을 배치로 바꿀 수 없다. 바꾸고 싶으면 새 메서드(`saveAllInBatch`)를 추가해야 한다. 이 제약이 곧 "상속을 허용하는 비용"이다.

## 버전 표기

- `@implSpec` 태그: 책에 따르면 Java 8에서 도입되어 Java 9부터 JDK 문서에서 본격적으로 쓰였다. 책 기준으로는 javadoc 기본 태그가 아니어서 `-tag "implSpec:a:Implementation Requirements:"` 옵션을 켜야 문서에 절로 나온다. Java 10~21의 javadoc 도구가 이 태그를 옵션 없이 인식하는지는 확실하지 않다. 옵션을 붙여 두면 버전과 상관없이 안전하다.
- Java 7 레거시: 태그가 인식되지 않아도 javadoc 본문에 "구현 요구사항:" 문단을 직접 쓰면 같은 역할을 한다. 중요한 것은 태그가 아니라 문서화했다는 사실이다.
- 예시의 `List.of`는 Java 9+, `forEach`와 메서드 참조는 Java 8+다. Java 7이라면 `Arrays.asList`와 for 루프로 바꾼다.

## 심화 / 예외 상황

**무엇을 문서화하는가.** 책은 세 가지를 적으라고 한다. 어떤 재정의 가능 메서드를 호출하는지, 어떤 순서로 호출하는지, 각 호출 결과가 이어지는 처리에 어떤 영향을 주는지. 또 백그라운드 스레드나 정적 초기화 과정에서 재정의 가능 메서드를 호출할 수도 있으니, 재정의 가능 메서드를 호출할 수 있는 모든 상황을 적어야 한다.

---

# 2. 내부 동작 중간에 끼어들 수 있는 훅을 protected로 공개해야 할 수도 있다

## 신입 눈높이 설명

문서화만으로는 부족할 때가 있다. 하위 클래스가 상위 클래스 알고리즘의 한 단계를 더 효율적으로 바꿀 수 있는데 그 단계가 `private`에 숨어 있으면 방법이 없다. 그래서 상속용 클래스는 알고리즘의 중간 단계를 `protected` 메서드(훅, hook)로 열어 두기도 한다. 템플릿 메서드 패턴(template method pattern)이 바로 이 구조다.

## 원리 설명

책의 예는 `AbstractList.removeRange`다. 일반 사용자는 이 메서드를 부를 일이 없지만(`protected`), `clear()`와 `subList(...).clear()`가 이 메서드를 부른다.

```java
// JDK 소스 발췌 — java.util.AbstractList (Java 8~21 기준 핵심만)
public void clear() {
    removeRange(0, size());
}

protected void removeRange(int fromIndex, int toIndex) {
    ListIterator<E> it = listIterator(fromIndex);
    for (int i = 0, n = toIndex - fromIndex; i < n; i++) {
        it.next();
        it.remove();          // 원소 하나씩 지운다
    }
}
```

기본 구현은 반복자로 원소를 하나씩 지운다. 배열 기반 리스트에서 `it.remove()`는 뒤쪽 원소를 한 칸씩 당기므로 O(n)이고, n개를 지우면 O(n²)이 된다. `ArrayList`는 `removeRange`를 재정의해서 `System.arraycopy` 한 번으로 끝낸다. 이 훅이 `protected`로 열려 있기 때문에 가능한 일이다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
// AbstractList 를 상속해 만든 배열 기반 리스트 — removeRange 를 재정의하지 않았다
public class IntArrayList extends AbstractList<Integer> {
    private int[] data; private int size;
    @Override public Integer remove(int index) { /* 뒤쪽을 한 칸 당긴다: O(n) */ }
    // get, set, add, size ...
}
```

### ② 무엇이 문제인가

```java
list.subList(0, 100_000).clear();   // 원소 20만 개 리스트의 앞쪽 절반 삭제
```

- 단위 테스트는 원소 10개짜리 리스트로 `clear()`를 확인하고 통과한다.
- 운영에서 대량 데이터 정리 배치가 이 줄을 실행하는 순간, 한 번의 `remove`마다 수만 칸을 당기는 작업이 10만 번 반복된다. 약 50억 번의 원소 이동이다.
- 예외는 나지 않는다. 배치가 "멈춘 것처럼" 오래 돌 뿐이고, 스레드 덤프에는 `AbstractList.removeRange` → `IntArrayList.remove`만 반복해서 찍힌다.

근본 원인은 하나다. **상위 클래스가 연 훅을 하위 클래스가 몰랐거나, 상위 클래스가 훅을 아예 열지 않아서 하위 클래스가 알고리즘의 병목 단계를 바꿀 방법이 없었다.**

### ③ 개선된 코드

```java
public class IntArrayList extends AbstractList<Integer> {
    /** 구간 삭제를 배열 복사 한 번으로 처리한다. clear()와 subList().clear()가 이 메서드를 쓴다. */
    @Override protected void removeRange(int fromIndex, int toIndex) {
        System.arraycopy(data, toIndex, data, fromIndex, size - toIndex);
        size -= toIndex - fromIndex;
        modCount++;
    }
}
```

## 버전 표기

- `AbstractList.removeRange`: Java 1.2+. Java 7 레거시에서도 그대로 재정의할 수 있다.
- 숫자 리터럴의 밑줄(`100_000`): Java 7+.

## 심화 / 예외 상황

**훅을 몇 개 열 것인가에 정답은 없다.** 너무 적게 열면 상속하는 의미가 없어지고, 너무 많이 열면 그 전부가 영원히 지켜야 할 공개 API가 된다. `protected` 메서드 하나하나가 내부 구현에 대한 약속이기 때문이다. 이 균형을 판단하는 유일한 방법이 다음 항목이다.

---

# 3. 상속용 클래스는 배포 전에 하위 클래스를 직접 만들어 시험해야 한다

## 신입 눈높이 설명

어떤 훅을 열지는 머리로 예측하기 어렵다. 책의 답은 단순하다. 직접 하위 클래스를 만들어 보라는 것이다. 책은 하위 클래스 3개 정도가 적당하고, 그중 하나 이상은 상위 클래스 작성자가 아닌 사람이 만들어 보라고 권한다.

## 원리 설명

하위 클래스를 만들다 보면 두 방향의 신호가 나온다. 꼭 필요한데 `private`이라 손댈 수 없는 단계가 있으면 그 단계를 훅으로 열어야 한다는 신호다. 반대로 하위 클래스 여러 개를 만드는 동안 한 번도 쓰지 않은 `protected` 멤버가 있다면, 그 멤버는 사실 `private`이었어야 할 가능성이 크다. 이 실험은 반드시 배포 전에 해야 한다. 일단 배포하면 공개된 `protected` 멤버와 문서화한 자기사용은 되돌릴 수 없다.

코드로 된 3단 대비는 이 항목에서 성립하지 않는다. 규칙 자체가 "코드를 어떻게 짜라"가 아니라 "배포 전에 무엇을 검증하라"이기 때문이다.

## 심화 / 예외 상황

**널리 쓰일 클래스일수록 이 결정은 영원하다.** 책은 상속용으로 설계한 클래스를 배포하면, 문서화한 내부 사용 패턴과 `protected` 메서드·필드의 구현 방식에 영원히 묶인다고 강조한다. 성능 개선도 그 틀 안에서만 할 수 있다.

---

# 4. 상속용 클래스의 생성자는 재정의 가능 메서드를 호출해서는 안 된다

## 신입 눈높이 설명

이 규칙은 직접 호출뿐 아니라 간접 호출에도 적용된다. 생성자가 부른 메서드가 다시 재정의 가능 메서드를 부르는 경우도 똑같이 위험하다.

이유는 생성 순서에 있다. `new Sub()`를 하면 `Super` 생성자가 먼저 끝나고, 그다음에 `Sub` 생성자 본문이 실행된다. 그런데 `Super` 생성자 안에서 재정의 가능 메서드를 부르면 `Sub`의 재정의 버전이 실행된다. 이 시점에 `Sub`의 필드는 아직 아무것도 초기화되지 않았다. 하위 클래스의 코드가 "태어나기도 전에" 실행되는 것이다.

## 원리 — 필드 대입보다 상위 생성자 호출이 먼저다

javac는 `Sub()` 생성자를 다음 순서로 컴파일한다. 소스에 `super()`를 쓰지 않았어도 컴파일러가 맨 앞에 넣는다.

```
// Sub.<init>
aload_0
invokespecial Super."<init>":()V                  // ① 상위 생성자가 먼저 — 이 안에서 overrideMe() 가 불린다
aload_0
invokestatic  java/time/Instant.now:()Ljava/time/Instant;
putfield      Sub.instant:Ljava/time/Instant;      // ② 필드 대입은 그 다음
return

// Super.<init>
aload_0
invokespecial java/lang/Object."<init>":()V
aload_0
invokevirtual Super.overrideMe:()V                 // 수신 객체는 이미 Sub 인스턴스 → Sub.overrideMe 로 디스패치
return
```

`new` 명령은 생성자를 실행하기 전에 객체의 메모리를 할당하고 모든 필드를 기본값(`null`, `0`, `false`)으로 채운다. 객체의 런타임 클래스는 이 시점에 이미 `Sub`로 정해져 있다. 그래서 `Super.<init>` 안의 `invokevirtual`은 `Sub.overrideMe`를 고르고, 그 메서드는 기본값 `null`인 `instant`를 본다. JLS 12.5(새 클래스 인스턴스 생성)가 이 순서를 정의하고, 생성 중 동적 디스패치가 일어나는 예제까지 싣고 있다.

`private`, `final`, `static` 메서드는 재정의될 수 없으므로 생성자에서 호출해도 안전하다. `private` 메서드 호출은 이 디스패치 대상이 아니다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

책의 예제 그대로다.

```java
public class Super {
    // 잘못된 예 — 생성자가 재정의 가능 메서드를 호출한다
    public Super() {
        overrideMe();
    }

    public void overrideMe() {
    }
}

public final class Sub extends Super {
    // 초기화 일관성을 위해 생성자에서 초기화한다
    private final Instant instant;

    Sub() {
        instant = Instant.now();
    }

    // 재정의 가능 메서드. 상위 클래스의 생성자가 호출한다
    @Override public void overrideMe() {
        System.out.println(instant);
    }

    public static void main(String[] args) {
        Sub sub = new Sub();
        sub.overrideMe();
    }
}
```

### ② 무엇이 문제인가

```java
Sub sub = new Sub();   // 출력: null
sub.overrideMe();      // 출력: 2026-09-29T12:35:40.996Z
```

`instant`는 `final`인데 두 가지 값으로 관찰됐다. `final` 필드는 "한 번 정해지면 바뀌지 않는다"는 약속인데, 생성이 끝나기 전에 새어 나간 `this`를 통해 그 약속이 깨졌다. 그래도 컴파일 에러도 런타임 예외도 없다. `println`이 `null`을 순순히 출력해 주기 때문이다.

필드를 역참조하는 순간 조용함은 끝난다.

```java
@Override public void overrideMe() {
    System.out.println(instant.getEpochSecond());   // new StrictSub() 에서 바로 NPE
}
```

- 상위 클래스 작성자는 v1에서 생성자에 `overrideMe()` 호출이 없었는데, v2에서 "초기화 훅"이라며 추가했다고 하자.
- `overrideMe`를 재정의하지 않았거나, 재정의했어도 필드를 안 쓰는 하위 클래스는 전부 통과한다. 상위 클래스의 테스트도 통과한다.
- 필드를 쓰는 하위 클래스 하나만 객체를 만드는 순간 `NullPointerException: Cannot invoke "java.time.Instant.getEpochSecond()" because "this.instant" is null`이 난다.
- 스택 트레이스는 `Super.<init>` → `StrictSub.overrideMe`를 가리킨다. `StrictSub` 작성자는 `overrideMe`를 호출한 적이 없는데 그 안에서 터지니, 원인을 자기 코드에서 찾다가 시간을 쓴다.

근본 원인은 하나다. **생성자에서 재정의 가능 메서드를 호출하면 하위 클래스의 코드가 하위 클래스의 초기화보다 먼저 실행되고, 이 순서는 하위 클래스 작성자가 바꿀 수 없다.**

### ③ 개선된 코드

책이 6번 항목에서 소개하는 도우미 메서드(helper method) 기법을 쓴다. 재정의 가능 메서드의 본문을 `private` 도우미로 옮기고, 생성자는 도우미만 호출한다.

```java
public class Super {
    public Super() {
        initHelper();              // private → 재정의 불가 → 하위 클래스 코드가 끼어들지 못한다
    }

    /** @implSpec 이 구현은 initHelper 를 호출한다. 생성자는 이 메서드를 호출하지 않는다. */
    public void overrideMe() {
        initHelper();
    }

    private void initHelper() {
        // 원래 overrideMe 에 있던 코드
    }
}
```

하위 클래스는 한 글자도 고치지 않았는데, `new Sub()`는 아무것도 출력하지 않고 `sub.overrideMe()`는 초기화가 끝난 값을 딱 한 번 출력한다. `Item19Problem.java`가 ①과 ③을 나란히 실행해 이 차이를 보여 준다.

## 버전 표기

- `java.time.Instant`: Java 8+. Java 7 레거시에서는 `java.util.Date`로 똑같이 재현된다. 생성 순서 문제는 언어 규칙이라 버전과 상관없이 같다.

  ```java
  // Java 7 — 같은 문제, 같은 해법
  private final Date date;
  Sub() { date = new Date(); }
  @Override public void overrideMe() { System.out.println(date); }   // 생성 중에는 null
  ```

- NPE의 상세 메시지(`because "this.instant" is null`): Java 14에서 도입(JEP 358)되어 15부터 기본으로 켜진다. Java 7~13에서는 메시지 없이 `java.lang.NullPointerException`만 나와서 원인을 찾기가 더 어렵다.
- Java 25의 유연한 생성자 본문(flexible constructor bodies)은 이 문제를 하위 클래스 쪽에서 피할 길을 열어 준다. 아래 Java 21 보완 참고.

## 심화 / 예외 상황

**생성자에서 `this`를 넘기는 것도 같은 문제다.** 생성자에서 리스너 등록(`bus.register(this)`)이나 스레드 시작(`new Thread(this::run).start()`)을 하면, 다른 코드가 초기화가 덜 끝난 객체를 보게 된다. 재정의 가능 메서드 호출은 이 "생성 중 `this` 유출"의 한 형태다.

**하위 클래스가 `final` 필드 보장을 믿을 수 없게 된다.** JMM의 `final` 필드 보장(JLS 17.5)은 생성자가 끝난 뒤에 적용된다. 생성 도중 새어 나간 참조로 읽은 값에는 아무 보장도 없다.

---

# 5. Cloneable과 Serializable을 구현한 상속용 클래스 — clone과 readObject도 생성자와 같다

## 신입 눈높이 설명

`clone`과 `readObject`는 생성자를 거치지 않고 객체를 만드는 "숨은 생성자"다. 그래서 4번 규칙이 그대로 적용된다. 이 두 메서드 안에서도 재정의 가능 메서드를 직접이든 간접이든 호출하면 안 된다. 책은 상속용 클래스에서 `Cloneable`이나 `Serializable`을 구현하는 것 자체가 좋지 않은 생각이라고도 말한다. 하위 클래스를 만드는 사람에게 부담이 너무 크기 때문이다.

## 원리 설명

`readObject`는 하위 클래스의 상태가 역직렬화되기 전에 재정의한 메서드를 실행한다. `clone`은 더 나쁘다. 하위 클래스의 `clone`이 복제본의 상태를 고치기 전에 재정의한 메서드가 실행되는데, 이때 복제본은 원본과 가변 필드를 공유하는 얕은 복사 상태다. 그래서 재정의한 메서드가 복제본을 건드리면 원본까지 망가진다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
public class Base implements Cloneable {
    @Override public Base clone() {
        try {
            Base b = (Base) super.clone();
            b.reset();                     // 복제본을 초기 상태로 — 재정의 가능 메서드 호출
            return b;
        } catch (CloneNotSupportedException e) { throw new AssertionError(); }
    }
    public void reset() { }
}

class Sub extends Base {
    int[] data = {1, 2, 3};
    @Override public void reset() { Arrays.fill(data, 0); }
    @Override public Sub clone() {
        Sub s = (Sub) super.clone();       // 이 안에서 s.reset() 이 먼저 불린다
        s.data = data.clone();             // 배열을 떼어내는 건 그 "다음"이다
        return s;
    }
}
```

### ② 무엇이 문제인가

```java
Sub orig = new Sub();
Sub copy = orig.clone();
// orig.data = [0, 0, 0], copy.data = [0, 0, 0]   ← 복제만 했는데 원본이 지워졌다
```

`Object.clone`이 만든 복제본의 `data`는 원본과 같은 배열을 가리킨다. `Base.clone`이 `reset()`을 호출하면 `Sub.reset`이 실행되어 그 공유 배열을 0으로 채운다. `Sub.clone`이 `data.clone()`으로 배열을 떼어낼 때는 이미 원본이 지워진 뒤다. 복제는 읽기 전용 연산이라고 믿기 쉬워서, 원본이 바뀌었다는 사실을 의심하는 사람이 없다. 예외도 로그도 없다.

근본 원인은 하나다. **상위 클래스의 `clone`이 하위 클래스의 복제 작업이 끝나기 전에 하위 클래스의 코드를 실행시켰다.**

### ③ 개선된 코드

```java
public class Base implements Cloneable {
    @Override public Base clone() {
        try {
            Base b = (Base) super.clone();
            b.resetHelper();               // private 도우미 — 하위 클래스가 끼어들지 못한다
            return b;
        } catch (CloneNotSupportedException e) { throw new AssertionError(); }
    }
    public void reset() { resetHelper(); }
    private void resetHelper() { /* Base 자신의 상태만 초기화 */ }
}
```

직렬화 쪽 추가 규칙: 상속용 `Serializable` 클래스에 `readResolve`나 `writeReplace`가 있다면 `private`이 아니라 `protected`로 선언해야 한다. `private`이면 하위 클래스에서 무시된다. 상속을 허용하기 위해 내부 구현을 클래스 API로 공개하는 또 하나의 예다.

## 버전 표기

- `Cloneable`/`Object.clone`: Java 1.0+. 공변 반환 타입(`public Base clone()`, `public Sub clone()`)은 Java 5+. 모두 Java 7 레거시에서 그대로 쓸 수 있다.
- `readResolve`/`writeReplace`: Java 1.2+.

---

# 6. 상속용으로 설계하지 않은 클래스는 상속을 금지하라

## 신입 눈높이 설명

1~5를 다 지키는 것은 큰 비용이다. 그 비용을 치를 생각이 없는 일반 구체 클래스라면 상속을 막는 게 맞다. 방법은 두 가지다.

- 클래스를 `final`로 선언한다.
- 모든 생성자를 `private`이나 package-private으로 두고 `public` 정적 팩터리를 제공한다(아이템 17). 내부에서는 하위 클래스를 만들 수 있어서 더 유연하다.

## 원리 설명

`final` 클래스는 클래스 파일의 `access_flags`에 `ACC_FINAL`이 켜진다. javac는 `extends`를 컴파일 에러(`cannot inherit from final Money`)로 막는다. 컴파일러를 우회해 만든 클래스 파일도 JVM이 로딩 단계에서 거부한다. HotSpot에서는 `VerifyError: Cannot inherit from final class`로 나타나며, 목(mock) 라이브러리가 `final` 클래스를 상속하려다 실패할 때 자주 보는 에러가 이것이다.

`final` 클래스의 메서드는 재정의될 수 없으므로, 1~5에서 다룬 "재정의 가능한 호출 지점"이 사라진다. 그러면 자기사용은 다시 순수한 구현 세부가 되고, 다음 버전에서 마음대로 바꿔도 된다. 캡슐화를 되찾는 것이다.

## 3단 대비

### ① 흔히 쓰는(나쁜) 코드

```java
// 아무 생각 없이 열어 둔 구체 클래스 — 표준 인터페이스도 없다
public class Money {
    public Money add(Money other) { return plus(other.amount); }
    public Money plus(long amount) { return new Money(this.amount + amount); }
}
```

### ② 무엇이 문제인가

누군가 `Money`를 상속해 `plus`에 통화 검사를 넣었다. `add`가 `plus`를 부른다는 사실에 기대서 `add`는 재정의하지 않았다. 다음 버전에서 `Money` 작성자가 `add`를 `new Money(amount + other.amount)`로 직접 계산하도록 "정리"하는 순간, `add` 경로에서 통화 검사가 사라진다. `Money` 작성자 입장에서는 공개 API를 전혀 바꾸지 않은 리팩터링이었다. 아이템 18의 문제 (a)가 그대로 재현된다.

### ③ 개선된 코드

상속을 막을 수 있으면 막는다.

```java
public final class Money { /* ... */ }
```

표준 인터페이스가 없어서 상속을 막으면 쓰기 불편해지는 클래스라면, 상속을 허용하되 클래스 내부에서 재정의 가능 메서드를 호출하지 않게 만든다. 4번에서 쓴 도우미 메서드 기법이다.

```java
public class Money {
    public Money add(Money other) { return plusHelper(other.amount); }   // plus 대신 도우미를 직접 호출
    public Money plus(long amount) { return plusHelper(amount); }
    private Money plusHelper(long amount) { return new Money(this.amount + amount); }
}
```

이제 `plus`를 재정의해도 `add`의 동작은 바뀌지 않는다. 자기사용이 사라졌으니 상속해도 안전하다. 이 사실을 문서에 적어 두면 된다.

## 버전 표기

- `final` 클래스, private 생성자 + 정적 팩터리, 도우미 메서드 기법: 모두 Java 1.0+. Java 7 레거시에서도 그대로 사용 가능하다.

## 심화 / 예외 상황

**인터페이스가 있으면 상속을 막아도 손해가 없다.** `Set`, `List`, `Map`처럼 클래스의 본질을 정의한 인터페이스가 있으면, 상속을 금지해도 아이템 18의 래퍼 클래스로 기능을 얼마든지 덧붙일 수 있다.

---

# 3판(Java 9 기준) 이후 — 현재(Java 21) 시점의 보완

## sealed는 "상속 허용"과 "상속 금지" 사이의 선택지다

`(Java 17+)` 책의 선택지는 "1~5를 다 지키고 누구에게나 열거나, `final`로 닫거나" 둘뿐이었다. `sealed`는 허락한 하위 클래스만 상속하게 한다.

```java
public sealed abstract class Shape permits Circle, Square { /* ... */ }
public final class Circle extends Shape { /* ... */ }
public final class Square extends Shape { /* ... */ }
```

하위 클래스가 같은 모듈(이름 없는 모듈이면 같은 패키지)에 있고 목록이 정해져 있으므로, 자기사용을 바꿀 때 영향받는 하위 클래스를 전부 알고 함께 고칠 수 있다. 3번의 "하위 클래스를 직접 만들어 시험하라"가 자동으로 충족되는 셈이다.

하지만 `sealed`가 4번 문제를 푸는 것은 아니다. 허락된 하위 클래스라도 상위 생성자가 재정의 가능 메서드를 부르면 똑같이 `null`을 본다. 또 허락된 하위 클래스를 `non-sealed`로 선언하면 그 아래로는 다시 누구나 상속할 수 있으므로, 그 지점부터는 1~5를 다시 지켜야 한다.

Java 7~16 대안: 생성자를 package-private으로 두면 다른 패키지에서의 상속을 막을 수 있다. 다만 같은 패키지 안의 상속은 목록으로 제한되지 않는다.

## record와 enum은 언어가 상속을 막는다

`(Java 16+)` 레코드는 암묵적으로 `final`이다. 열거 타입도 상수별 몸체를 빼면 상속할 수 없다. 이 아이템의 결론인 "설계하지 않았다면 상속을 금지하라"가 이 두 타입에서는 기본값이 되었다.

## Java 25의 유연한 생성자 본문 — 하위 클래스 쪽의 탈출구

Java 22~24에서 프리뷰였던 유연한 생성자 본문(flexible constructor bodies)이 Java 25에서 정식 기능이 되었다(JEP 513). 이제 `super(...)` 호출 앞에 문장을 쓸 수 있고, 자기 클래스의 필드를 `super(...)` 전에 대입할 수도 있다.

```java
// Java 25+ — 필드를 먼저 초기화하고 나서 상위 생성자를 부른다
Sub() {
    instant = Instant.now();
    super();                  // 이제 Super() 안에서 불리는 overrideMe 가 초기화된 instant 를 본다
}
```

이 문서의 예제를 Java 25에서 이렇게 바꿔 실행해 보면 상위 생성자 안의 `overrideMe`가 `null`이 아니라 초기화된 값을 본다. 하지만 이것은 하위 클래스 작성자가 문제를 알고 있을 때만 쓸 수 있는 우회로다. 상위 클래스 작성자가 생성자에서 재정의 가능 메서드를 부르면 안 된다는 규칙은 그대로다. 그리고 이 기능은 Java 25+라서 Java 7~21 범위에서는 쓸 수 없다.

## 실무 함정 — 프레임워크 프록시는 상속을 전제로 한다

스프링과 JPA는 클래스를 상속한 프록시(proxy)를 런타임에 만들어 쓴다. 그래서 "상속을 금지하라"를 그대로 따르면 충돌이 난다.

- 스프링: 인터페이스 없는 빈에 `@Transactional`을 걸거나 `@Configuration` 클래스를 쓰면 CGLIB가 그 클래스를 상속한 프록시를 만든다. 클래스가 `final`이면 프록시를 만들 수 없어 기동 시점에 실패하고, 메서드가 `final`이면 프록시가 그 메서드를 가로채지 못해 트랜잭션이 조용히 빠진다.
- JPA(하이버네이트): 지연 로딩 프록시가 엔티티를 상속한다. JPA 명세도 엔티티 클래스와 영속 상태 메서드가 `final`이면 안 된다고 정한다. `final` 게터를 가진 엔티티의 프록시에서 그 게터를 부르면, 실제 엔티티 대신 초기화되지 않은 프록시 자신의 필드를 읽어 `null`이 나올 수 있다.
- 코틀린은 클래스가 기본으로 `final`이라서 스프링·JPA용 `all-open` 컴파일러 플러그인을 따로 둔다.

그래서 이런 클래스들은 "상속용으로 설계했다"고 보고 4번 규칙을 지키는 쪽을 택한다. 프록시 생성 라이브러리(스프링은 Objenesis)는 보통 생성자를 거치지 않고 프록시 객체를 만들지만, 그렇지 않은 환경에서는 엔티티 생성자에서 재정의 가능 메서드를 부르는 순간 4번 문제가 그대로 난다.

---

## 핵심 키워드

- [객체 생성 순서](../Keywords/instance-initialization-order.md) — 상위 생성자가 하위 클래스의 필드 대입보다 먼저 실행되는 이유
- [@implSpec](../Keywords/implspec.md) — 자기사용을 계약으로 만드는 문서화 태그
- [훅 메서드와 템플릿 메서드 패턴](../Keywords/hook-method.md) — `removeRange`처럼 알고리즘의 한 단계를 `protected`로 여는 구조
- [동적 디스패치](../Keywords/dynamic-dispatch.md) — 상위 생성자 안에서도 하위 클래스의 재정의 메서드가 불리는 원리
- [취약한 기반 클래스 문제](../Keywords/fragile-base-class.md) — 이 아이템이 문서화와 상속 금지로 막으려는 문제

## 함께 보면 좋은 아이템

- **아이템 18. 상속보다는 컴포지션을 사용하라** — 18이 "남의 클래스를 상속하지 마라"라는 사용자 쪽 이야기라면, 19는 "상속을 허용하려면 이렇게 설계하라"는 작성자 쪽 이야기다. 1번 항목의 `Repository` 사례가 18의 `InstrumentedHashSet`과 같은 구조다.
- **아이템 17. 변경 가능성을 최소화하라** — 6번 항목의 "private 생성자 + 정적 팩터리로 상속을 막는다"는 방법이 17의 불변 클래스 규칙 2에서 먼저 나온다.
- **아이템 13. clone 재정의는 주의해서 진행하라** — 5번 항목의 "`clone`에서 재정의 가능 메서드를 부르지 마라"가 13에서도 같은 이유로 다시 나온다. 상속용 클래스는 `Cloneable`을 구현하지 말라는 조언도 13과 이어진다.
- **아이템 20. 추상 클래스보다는 인터페이스를 우선하라** — 20의 골격 구현(skeletal implementation)이 바로 이 아이템의 규칙대로 "상속을 고려해 설계하고 문서화한" 클래스다. `AbstractList`, `AbstractCollection`이 그 예다.

(아이템 번호는 3판 한국어판 기준으로 기억하고 있으나, 13·20은 책에서 한 번 확인해 주면 확실하다.)

## 확인 질문

③의 `Super`에 생성 로그 한 줄을 추가했다. 생성자는 여전히 재정의 가능 메서드를 직접 부르지 않는다. 그런데 `Sub`가 아래처럼 `toString`을 재정의하고 있다면 `new Sub()`는 어떻게 될까? 그리고 왜 그럴까?

```java
public Super() {
    initHelper();
    System.out.println("생성됨: " + this);   // 추가한 줄
}
// Sub 에는: @Override public String toString() { return "Sub@" + instant.getEpochSecond(); }
```
