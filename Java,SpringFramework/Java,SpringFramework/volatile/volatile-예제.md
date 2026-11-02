---
주제: volatile 키워드
분류: Java / 동시성 / 메모리 모델
난이도: 이론
관련버전: Java 21 (Java 7/8 대응 코드 병기), JUnit 5+
작성일: 2026-10-04
선행지식: [스레드 기초, synchronized, ExecutorService, JUnit 5]
---

# volatile 예제 — 보이지 않는 쓰기, 사라지는 증가, 깨진 쌍

관련 문서: [이론](./volatile-이론.md)

---

## 1. 예제 개요

volatile이 **보장하는 것**(가시성)과 **보장하지 않는 것**(복합 연산 원자성, 여러 필드의 일관성)을 각각 실행해서 확인한다.

| 예제 | 검증하는 개념 | 이론 문서 대응 |
|---|---|---|
| 1. 종료 플래그 | volatile이 없으면 JIT 최적화 때문에 다른 스레드의 쓰기를 영원히 못 본다 | [3장](./volatile-이론.md#3-최소-예제로-보기), [6.2](./volatile-이론.md#62-jit-루프-밖으로-끌어내기-3장의-원인), [8.1](./volatile-이론.md#81-종료-플래그에-volatile을-빠뜨림) |
| 2. 카운터 | volatile `count++`는 증가가 사라진다. `AtomicInteger`로 해결 | [8.2](./volatile-이론.md#82-volatile-필드에-count) |
| 3. 범위 설정 | 관련된 필드 두 개를 각각 volatile로 두면 깨진 쌍이 보인다. 불변 스냅샷 + volatile 참조로 해결 | [8.4](./volatile-이론.md#84-서로-관련된-필드를-각각-volatile로-둠) |
| 4. 큐 설정 | login-queue `QueueProperties`처럼 독립 필드 + 단순 대입이면 volatile만으로 충분하다 | [9.1](./volatile-이론.md#91-실행-중에-바뀌는-설정--login-queue-queueproperties) |

---

## 2. 환경

- Java 21 (record, 람다 사용. Java 7/8 대응 코드는 4.4절)
- Spring 없음. 순수 Java로 동작 원리만 본다.
- 테스트: JUnit Jupiter (5.x 또는 6.x)

```groovy
// build.gradle
plugins {
    id 'java'
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    testImplementation platform('org.junit:junit-bom:5.11.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

test { useJUnitPlatform() }
```

> 예제 코드는 실행하기 쉽도록 기본 패키지(default package)에 둔다. 실제 프로젝트에서는 패키지를 선언한다.

---

## 3. 잘못된 코드 먼저

### 3.1 volatile 없는 종료 플래그

```java
public class StopFlagBad {

    private static boolean running = true;   // volatile 없음

    public static void main(String[] args) throws InterruptedException {
        Thread worker = new Thread(() -> {
            long count = 0;
            while (running) {
                count++;
            }
            System.out.println("worker 종료, count=" + count);
        });
        worker.start();

        Thread.sleep(1000);
        running = false;
        System.out.println("main: running=false 설정");

        worker.join(3000);
        System.out.println("3초 후 worker 살아있음? " + worker.isAlive());
        // worker가 끝나지 않으므로 강제로 JVM을 내린다
        System.exit(0);
    }
}
```

**문제**: C2 JIT가 `running` 읽기를 루프 밖으로 끌어내 `if (running) while (true)`로 만든다. main 스레드가 `false`를 써도 worker는 다시 읽지 않는다.

### 3.2 volatile 필드에 `++`

```java
private static volatile int volatileCount = 0;

// 8개 스레드가 동시에
volatileCount++;   // 읽기 → +1 → 쓰기. 사이에 다른 스레드가 끼어든다
```

**문제**: volatile은 각 읽기·쓰기가 최신 값을 보게 할 뿐, 세 단계를 하나로 묶지 않는다.

### 3.3 관련된 두 필드를 각각 volatile로

```java
/**
 * 서로 관계가 있는 두 값을 각각 volatile 필드에 둔 설정.
 * 필드 하나하나는 최신 값이 보이지만, 두 필드를 "한 쌍"으로 읽는다는 보장은 없다.
 */
public class RangeBad {

    private volatile int min = 0;
    private volatile int max = 10;

    public void set(int min, int max) {
        this.min = min;   // 이 사이에 다른 스레드가 읽으면 새 min + 옛 max
        this.max = max;
    }

    public int getMin() { return min; }
    public int getMax() { return max; }
}
```

**문제**: `set(100, 110)` 도중에 읽으면 `(100, 10)`처럼 존재한 적 없는 쌍을 본다. getter를 두 번 부르는 것 자체가 두 번의 독립된 읽기다.

---

## 4. 개선된 코드

### 4.1 1단계: 플래그에 volatile

```java
private static volatile boolean running = true;
```

**좋아진 점**: JIT가 읽기를 루프 밖으로 끌어낼 수 없다. 매 반복마다 다시 읽으므로 main의 쓰기가 보인다.

### 4.2 2단계: 증가는 `AtomicInteger`

```java
private static final AtomicInteger atomicCount = new AtomicInteger();

atomicCount.incrementAndGet();   // CAS로 읽기-더하기-쓰기를 한 번에
```

**좋아진 점**: 내부 `value`가 volatile이라 가시성이 있고, CAS(compare-and-swap)가 다른 스레드가 끼어들었는지 확인해 실패하면 다시 시도한다. 증가가 사라지지 않는다.

### 4.3 3단계: 불변 스냅샷 + volatile 참조

```java
/** 불변 스냅샷. 필드가 final이라 생성이 끝난 뒤에는 바뀌지 않는다. */
public record Range(int min, int max) {
    public Range {
        if (min > max) {
            throw new IllegalArgumentException("min > max: " + min + " > " + max);
        }
    }
}
```

```java
/**
 * 두 값을 불변 객체 하나에 묶고, 그 참조만 volatile로 교체한다.
 * 읽는 쪽은 참조를 한 번 읽어 지역 변수에 담으면 항상 같은 쌍을 본다.
 */
public class RangeGood {

    private volatile Range range = new Range(0, 10);

    public void set(int min, int max) {
        this.range = new Range(min, max);   // 참조 대입 1번 = 원자적 교체
    }

    public Range get() { return range; }
}
```

**좋아진 점**

- 참조 대입은 원자적이다. 읽는 쪽은 옛 `Range` 아니면 새 `Range`를 보고, 그 중간은 없다.
- `new Range(...)`의 생성자 안 쓰기는 volatile 쓰기보다 앞서므로, volatile 읽기로 참조를 받은 스레드에게 함께 보인다(이론 5.1).
- 생성자에서 `min <= max`를 검증하므로, 잘못된 쌍은 애초에 발행되지 않는다.

### 4.4 Java 7/8 대응 코드

record와 람다가 없는 코드베이스에서는 다음처럼 쓴다. 동작은 같다.

```java
/** Java 7/8용 불변 스냅샷. final 필드라 생성 완료 후 안전하게 공유된다. */
public final class Range {

    private final int min;
    private final int max;

    public Range(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min > max: " + min + " > " + max);
        }
        this.min = min;
        this.max = max;
    }

    public int min() { return min; }
    public int max() { return max; }
}
```

```java
// Java 7: 람다 대신 익명 클래스
Thread worker = new Thread(new Runnable() {
    @Override
    public void run() {
        long count = 0;
        while (running) {
            count++;
        }
        System.out.println("worker 종료, count=" + count);
    }
});
```

> Java 7에서는 숫자 리터럴의 `_`(예: `100_000`)는 쓸 수 있지만, 익명 클래스에서 지역 변수를 쓰려면 `final`을 명시해야 한다(Java 8부터 effectively final 허용).

---

## 5. 전체 코드

### 5.1 `StopFlagGood.java`

```java
public class StopFlagGood {

    private static volatile boolean running = true;

    public static void main(String[] args) throws InterruptedException {
        Thread worker = new Thread(() -> {
            long count = 0;
            while (running) {
                count++;
            }
            System.out.println("worker 종료, count=" + count);
        });
        worker.start();

        Thread.sleep(1000);
        running = false;
        System.out.println("main: running=false 설정");

        worker.join(3000);
        System.out.println("3초 후 worker 살아있음? " + worker.isAlive());
    }
}
```

### 5.2 `CounterDemo.java`

```java
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class CounterDemo {

    private static final int THREADS = 8;
    private static final int INCREMENTS = 100_000;

    private static volatile int volatileCount = 0;
    private static final AtomicInteger atomicCount = new AtomicInteger();

    public static void main(String[] args) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        // 모든 스레드가 준비된 뒤 동시에 출발시켜 경합을 최대로 만든다
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < INCREMENTS; i++) {
                    volatileCount++;                 // 읽기 → +1 → 쓰기 (원자적이지 않음)
                    atomicCount.incrementAndGet();   // CAS로 한 번에
                }
                done.countDown();
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        System.out.println("기대값        = " + THREADS * INCREMENTS);
        System.out.println("volatile int  = " + volatileCount);
        System.out.println("AtomicInteger = " + atomicCount.get());
    }
}
```

### 5.3 `RangeDemo.java`

`RangeBad`(3.3), `Range`·`RangeGood`(4.3)과 같은 디렉터리에 둔다.

```java
public class RangeDemo {

    private static final long DURATION_MS = 1000;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("RangeBad  깨진 쌍 관측 횟수 = " + runBad());
        System.out.println("RangeGood 깨진 쌍 관측 횟수 = " + runGood());
    }

    // 쓰는 쪽은 (0,10)과 (100,110)을 번갈아 넣는다. 올바른 쌍이면 항상 max - min == 10이다.
    private static long runBad() throws InterruptedException {
        RangeBad r = new RangeBad();
        long[] broken = new long[1];
        Thread reader = new Thread(() -> {
            long end = System.currentTimeMillis() + DURATION_MS;
            while (System.currentTimeMillis() < end) {
                int min = r.getMin();
                int max = r.getMax();
                if (max - min != 10) broken[0]++;
            }
        });
        Thread writer = new Thread(() -> {
            long end = System.currentTimeMillis() + DURATION_MS;
            boolean flip = false;
            while (System.currentTimeMillis() < end) {
                if (flip) r.set(0, 10); else r.set(100, 110);
                flip = !flip;
            }
        });
        reader.start();
        writer.start();
        reader.join();   // join()이 happens-before를 만들어 broken[0]을 안전하게 읽는다
        writer.join();
        return broken[0];
    }

    private static long runGood() throws InterruptedException {
        RangeGood r = new RangeGood();
        long[] broken = new long[1];
        Thread reader = new Thread(() -> {
            long end = System.currentTimeMillis() + DURATION_MS;
            while (System.currentTimeMillis() < end) {
                Range snap = r.get();   // volatile 읽기는 한 번만 하고 지역 변수로 쓴다
                if (snap.max() - snap.min() != 10) broken[0]++;
            }
        });
        Thread writer = new Thread(() -> {
            long end = System.currentTimeMillis() + DURATION_MS;
            boolean flip = false;
            while (System.currentTimeMillis() < end) {
                if (flip) r.set(0, 10); else r.set(100, 110);
                flip = !flip;
            }
        });
        reader.start();
        writer.start();
        reader.join();
        writer.join();
        return broken[0];
    }
}
```

### 5.4 `QueueSettings.java`

```java
/**
 * login-queue의 QueueProperties와 같은 구조.
 * HTTP 요청 스레드가 setter로 바꾸고, 스케줄러 스레드가 getter로 읽는다.
 * 필드끼리 서로 관계가 없고 setter가 단순 대입만 하므로 volatile만으로 충분하다.
 */
public class QueueSettings {

    private volatile int maxActive = 50;
    private volatile int admitPerSecond = 20;

    public int getMaxActive() { return maxActive; }
    public void setMaxActive(int maxActive) { this.maxActive = maxActive; }

    public int getAdmitPerSecond() { return admitPerSecond; }
    public void setAdmitPerSecond(int admitPerSecond) { this.admitPerSecond = admitPerSecond; }
}
```

### 실행 방법

```bash
javac -encoding UTF-8 *.java
java StopFlagBad
java -Xint StopFlagBad      # JIT를 끄고 비교
java StopFlagGood
java CounterDemo
java RangeDemo
```

---

## 6. 실행 결과

아래는 **실제 실행 결과**다. 환경: Java 21.0.12 (HotSpot 64-Bit Server VM), Windows 11, 24코어.
멀티스레드 결과라 숫자는 실행할 때마다 달라진다. 특히 `StopFlagBad`는 JVM 버전·CPU·JIT 상황에 따라 멈출 수도 있다. "멈추지 않을 수 있다"가 문제이지, "반드시 안 멈춘다"는 보장이 아니다.

### 6.1 `StopFlagBad` (3번 실행)

```text
main: running=false 설정
3초 후 worker 살아있음? true
main: running=false 설정
3초 후 worker 살아있음? true
main: running=false 설정
3초 후 worker 살아있음? true
```

"worker 종료" 줄이 한 번도 찍히지 않았다. 3번 모두 멈추지 않았다.

### 6.2 `StopFlagBad` + `-Xint`

```text
main: running=false 설정
worker 종료, count=383040274
3초 후 worker 살아있음? false
```

같은 코드인데 JIT를 끄면 멈춘다. 원인이 JIT의 루프 최적화라는 증거다(이론 6.2). 인터프리터라 count도 JIT 버전보다 훨씬 적다.

### 6.3 `StopFlagGood`

```text
main: running=false 설정
worker 종료, count=4595804448
3초 후 worker 살아있음? false
```

### 6.4 `CounterDemo` (3번 실행)

```text
기대값        = 800000
volatile int  = 282166
AtomicInteger = 800000

기대값        = 800000
volatile int  = 323300
AtomicInteger = 800000

기대값        = 800000
volatile int  = 309799
AtomicInteger = 800000
```

volatile 카운터는 증가의 60% 이상이 사라졌다. `AtomicInteger`는 매번 정확하다.

### 6.5 `RangeDemo` (3번 실행)

```text
RangeBad  깨진 쌍 관측 횟수 = 678669
RangeGood 깨진 쌍 관측 횟수 = 0

RangeBad  깨진 쌍 관측 횟수 = 567435
RangeGood 깨진 쌍 관측 횟수 = 0

RangeBad  깨진 쌍 관측 횟수 = 586913
RangeGood 깨진 쌍 관측 횟수 = 0
```

두 필드가 각각 volatile이어도 1초에 50만 번 넘게 존재한 적 없는 쌍이 보였다. 불변 스냅샷은 한 번도 깨지지 않았다.

---

## 7. 검증 코드

멀티스레드 버그는 "일어나지 않을 수도" 있으므로 **잘못된 코드가 실패한다는 단언은 하지 않는다.** 대신 올바른 코드가 항상 성공한다는 것을 증명한다.

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class VolatileTest {

    private static volatile boolean running;

    @Test
    void volatile_플래그를_내리면_다른_스레드의_루프가_끝난다() throws InterruptedException {
        running = true;
        Thread worker = new Thread(() -> {
            while (running) {
                // 바쁜 대기: JIT가 루프를 최적화할 시간을 준다
            }
        });
        worker.start();
        Thread.sleep(500);

        running = false;

        worker.join(2000);
        assertFalse(worker.isAlive(), "volatile 쓰기가 worker에게 보여야 한다");
    }

    @Test
    void 증가_연산은_volatile이_아니라_AtomicInteger로_해야_정확하다() throws InterruptedException {
        int threads = 8;
        int increments = 100_000;
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < increments; i++) {
                    counter.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(threads * increments, counter.get());
    }

    @Test
    void 불변_스냅샷을_volatile_참조로_교체하면_깨진_쌍을_보지_않는다() throws InterruptedException {
        RangeGood range = new RangeGood();
        AtomicLong broken = new AtomicLong();
        long end = System.currentTimeMillis() + 500;

        Thread reader = new Thread(() -> {
            while (System.currentTimeMillis() < end) {
                Range snap = range.get();
                if (snap.max() - snap.min() != 10) broken.incrementAndGet();
            }
        });
        Thread writer = new Thread(() -> {
            boolean flip = false;
            while (System.currentTimeMillis() < end) {
                if (flip) range.set(0, 10); else range.set(100, 110);
                flip = !flip;
            }
        });
        reader.start();
        writer.start();
        reader.join();
        writer.join();

        assertEquals(0, broken.get());
    }

    @Test
    void 요청_스레드가_바꾼_설정을_스케줄러_스레드가_본다() {
        QueueSettings settings = new QueueSettings();
        CountDownLatch seen = new CountDownLatch(1);

        // 스케줄러 역할: 설정이 200으로 바뀔 때까지 계속 읽는다
        Thread scheduler = new Thread(() -> {
            while (settings.getMaxActive() != 200) {
                // 바쁜 대기
            }
            seen.countDown();
        });
        scheduler.start();

        // HTTP 요청 스레드 역할
        settings.setMaxActive(200);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> seen.await());
    }
}
```

실제 실행 결과 (JUnit Jupiter 6.0.3):

```text
Test run finished after 1119 ms
[         4 tests found           ]
[         4 tests started         ]
[         4 tests successful      ]
[         0 tests failed          ]
```

---

## 8. 변형 실습

### 8.1 루프 안에 `System.out.println`이나 `Thread.sleep(1)`을 넣으면?

`StopFlagBad`의 루프 본문을 `count++; Thread.sleep(1);` 또는 `System.out.println(count);`로 바꿔본다.

<details><summary>예상과 이유</summary>

대개 멈춘다. (실측: `Thread.sleep(1)`을 넣었더니 2번 실행에서 2번 모두 멈췄다.) 루프 안에 메서드 호출이 있으면 JIT가 그 메서드가 필드를 바꾸지 않는다고 증명하기 어려워 읽기를 루프 밖으로 끌어내지 못하는 경우가 많다. `println`은 내부에서 `synchronized`를 쓰기도 한다. **하지만 이것은 우연히 동작하는 것이지 JMM이 보장하는 것이 아니다.** "로그를 넣으면 버그가 사라진다"는 현상의 흔한 원인이며, 로그를 지우면 다시 나타난다. 올바른 해결은 여전히 volatile이다.
</details>

### 8.2 `CounterDemo`에서 `THREADS`를 1로 바꾸면?

<details><summary>예상과 이유</summary>

volatile 카운터도 정확히 100,000이 나온다. 끼어들 다른 스레드가 없기 때문이다. 원자성 문제는 **동시에 쓰는 스레드가 2개 이상**일 때만 드러난다. 반대로 말하면, 쓰는 스레드가 하나뿐이고 나머지는 읽기만 한다면 `volatile` 카운터로도 충분하다(single-writer 원칙).
</details>

### 8.3 `RangeGood`의 reader에서 `r.get()`을 두 번 부르면?

```java
if (r.get().max() - r.get().min() != 10) broken[0]++;
```

<details><summary>예상과 이유</summary>

다시 깨진 쌍이 관측된다. (실측: 1초에 약 380만~420만 번.) `r.get()`을 두 번 부르면 volatile 읽기가 두 번 일어나고, 그 사이에 writer가 참조를 교체할 수 있다. `max()`는 새 `Range`에서, `min()`은 옛 `Range`에서 읽게 된다. 불변 스냅샷 패턴은 **참조를 한 번만 읽어 지역 변수에 담을 때만** 일관성을 보장한다. login-queue `QueueService`의 ETA 계산처럼 설정값 하나를 한 번만 읽는 코드는 이 문제가 없다.
</details>
