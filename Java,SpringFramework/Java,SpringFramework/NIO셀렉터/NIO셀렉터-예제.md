---
주제: NIO Selector — 스레드 하나로 여러 연결을 처리하는 I/O 멀티플렉싱
분류: Java / 네트워크·I/O
난이도: 이론
관련버전: Java 17+ (Java 8 대체 코드 병기), 외부 라이브러리 없음
작성일: 2026-11-07
선행지식: [NIO Selector 이론 문서, ByteBuffer, JUnit 5]
---

# NIO Selector — 예제

관련 문서: [이론](./NIO셀렉터-이론.md) · [입출력 스트림 예제](../입출력스트림/입출력스트림-예제.md)

---

## 1. 예제 개요

줄(`\n`) 단위로 요청을 받아 응답하는 TCP 서버를 Selector로 만든다. 흔히 보이는 잘못된 에코 서버에서 시작해, 연결별 상태·프레이밍·쓰기 대기열·OP_WRITE 토글·워커 풀 연동까지 갖춘 서버로 고친다. 데모에서는 각 함정이 실제로 어떤 수치로 나타나는지 측정한다.

| 예제 | 검증하는 개념 | 이론 문서 대응 |
|---|---|---|
| `BadEchoServer` | 키 미삭제 → NPE, OP_WRITE 상시, -1 무시, 공유 버퍼, 경계 무시 | 함정 1·2·3·4·7 |
| `LineEchoServer` | 이벤트 루프, attachment, 프레이밍, 쓰기 대기열, 작업 큐 + wakeup, 연결별 순서 보장 | 5-1 ~ 5-5 |
| `BioEchoServer` | 비교용 연결당 스레드 서버 | 2장, 7장 |
| `SelectorDemo` | 스레드 수, 유휴 CPU, EOF 스핀, 부분 읽기/쓰기, 이벤트 루프 블로킹, register 동작 | 3장, 6장, 8장 |

---

## 2. 환경

- Java 17 이상(`String.repeat`, `ByteBuffer.get(int, byte[])`는 각각 11·13+). Java 8 대체 코드는 4장에 적었다.
- 실제 실행 환경: OpenJDK 25.0.4 (IntelliJ 번들 JBR), Windows 11, JUnit 5.11.4
- **의존성 없음.** `java.nio.channels`만 쓴다. Spring 설정도 없다(순수 Java로 `new`해서 실행).
- 모든 서버는 `127.0.0.1`의 임시 포트(0번 → OS가 할당)에 바인딩한다.

```groovy
// build.gradle
plugins {
    id 'java'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation platform('org.junit:junit-bom:5.11.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

test {
    useJUnitPlatform()
}
```

패키지 구조:

```
src/main/java/com/study/nio/
├── BadEchoServer.java    ← 잘못된 코드
├── LineEchoServer.java   ← 개선된 코드 (1~4단계)
├── BioEchoServer.java    ← 비교용 블로킹 서버
└── SelectorDemo.java     ← 실행용 main
src/test/java/com/study/nio/
└── SelectorTest.java
```

---

## 3. 잘못된 코드 먼저

인터넷의 "NIO 에코 서버" 예제를 옮기면 대개 이런 모양이다. 짧고 그럴듯하지만, 첫 연결이 들어오자마자 죽는다.

```java
package com.study.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 인터넷 예제를 그대로 옮기면 흔히 나오는 Selector 에코 서버. 따라 쓰지 말 것. */
public final class BadEchoServer {

    private final Selector selector;
    private final ServerSocketChannel server;
    // 문제 1: 모든 연결이 버퍼 하나를 같이 쓴다. 연결 A가 읽다 만 데이터가 연결 B의 응답에 섞일 수 있다.
    private final ByteBuffer buffer = ByteBuffer.allocate(1024);
    final AtomicLong selectCount = new AtomicLong();
    final AtomicReference<Throwable> crash = new AtomicReference<>();

    public BadEchoServer() throws IOException {
        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);
    }

    public int port() throws IOException {
        return ((InetSocketAddress) server.getLocalAddress()).getPort();
    }

    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                selector.select();
                selectCount.incrementAndGet();
                // 문제 2: 처리한 키를 selectedKeys에서 지우지 않는다.
                for (SelectionKey key : selector.selectedKeys()) {
                    if (key.isAcceptable()) {
                        SocketChannel client = server.accept();
                        client.configureBlocking(false);
                        // 문제 3: OP_WRITE를 항상 켜 둔다. 소켓 송신 버퍼에 빈자리가 있는 한 매번 "준비됨"이다.
                        client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    } else if (key.isReadable()) {
                        SocketChannel client = (SocketChannel) key.channel();
                        buffer.clear();
                        // 문제 4: read()가 -1(상대가 연결 종료)이어도 닫지 않는다.
                        client.read(buffer);
                        buffer.flip();
                        // 문제 5: 한 번의 read를 메시지 하나로 본다(경계 무시). write가 다 안 써져도 무시한다.
                        client.write(buffer);
                    }
                }
            }
        } catch (Throwable t) {
            crash.set(t);
        } finally {
            try {
                selector.close();
                server.close();
            } catch (IOException ignored) {
                // 데모 정리용
            }
        }
    }
}
```

| 문제 | 실제로 일어나는 일 (6장 실행 결과) | 이론 |
|---|---|---|
| 1. 버퍼 하나를 모든 연결이 공유 | 연결 A가 덜 읽은 데이터 위에 연결 B의 데이터가 덮어써진다 | 함정 7 |
| 2. `selectedKeys`에서 지우지 않음 | 서버 키의 `isAcceptable()`이 남아 `accept()`가 `null` → **select 2회 만에 NPE로 이벤트 루프 종료** | 함정 1 |
| 3. OP_WRITE 상시 등록 | 유휴 연결 하나로 1초에 1,057,854번 깨어나 CPU 100% | 함정 2 |
| 4. `read() == -1` 무시 | 끊어진 연결 하나로 1초에 435,889번 깨어남, 소켓 누수 | 함정 3 |
| 5. read 1회 = 메시지 1개, write 결과 무시 | `"HEL"`·`"LO\nWOR"`가 한 번에 `"HELLO\nWOR"`로 읽힌다. 큰 응답은 393KB만 나가고 나머지는 버려진다 | 함정 4 |

문제 2 때문에 나머지 문제가 드러나기도 전에 죽는다. 그래서 데모에서는 문제 3·4·5를 각각 따로 떼어 측정했다.

---

## 4. 개선된 코드

`LineEchoServer` 하나에 단계별 개선이 모두 들어 있다. 단계마다 해당 부분만 보인다(전체는 5장).

### 1단계: 이벤트 루프의 기본기 — 키 삭제, 유효성 확인, 키 단위 예외 처리

```java
selector.select();
runTasks();
Iterator<SelectionKey> it = selector.selectedKeys().iterator();
while (it.hasNext()) {
    SelectionKey key = it.next();
    it.remove();                         // 문제 2 해결
    try {
        if (!key.isValid()) continue;    // 같은 순회에서 이미 닫은 키일 수 있다
        if (key.isAcceptable()) accept();
        if (key.isValid() && key.isReadable()) read(key);
        if (key.isValid() && key.isWritable()) write(key);
    } catch (IOException e) {
        closeConnection(key);            // 연결 하나의 오류로 루프 전체가 죽지 않게
    }
}
```

- `accept()`는 `while ((client = server.accept()) != null)`로 백로그를 한 번에 비운다. null 확인이 루프 조건에 들어 있다.
- `read()` 결과가 -1이면 즉시 `cancel` + `close` → **문제 4 해결**

Java 11+라면 `selector.select(this::handle)`로 바꿔 `selectedKeys`와 `it.remove()`를 아예 없앨 수 있다.

### 2단계: 연결별 상태와 프레이밍 — 읽기 버퍼 + `compact()`

```java
private static final class Connection {
    final ByteBuffer in = ByteBuffer.allocate(MAX_LINE);   // 연결마다 따로 → 문제 1 해결
    final Queue<ByteBuffer> out = new ArrayDeque<>();
    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
}
// accept 시: client.register(selector, OP_READ, new Connection());
```

`read()`는 버퍼에 이어 쓰고, `flip()` 후 `\n`까지 완성된 줄만 꺼낸 다음, 남은 앞부분을 `compact()`로 당겨 둔다 → **문제 5(읽기) 해결**. 8KB가 차도록 줄바꿈이 없으면 연결을 끊는다(무한 버퍼링 방지, 테스트 `lineTooLong`).

Java 8 대체 코드(`ByteBuffer.get(int, byte[])`는 Java 13+):

```java
byte[] line = new byte[i - lineStart];
ByteBuffer view = c.in.duplicate();   // position/limit을 따로 갖는 뷰. 원본 상태를 건드리지 않는다
view.position(lineStart);
view.get(line);
```

### 3단계: 쓰기 대기열과 OP_WRITE 토글

```java
private void enqueue(SelectionKey key, String response) {
    c.out.add(ByteBuffer.wrap((response + "\n").getBytes(UTF_8)));
    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);    // 보낼 게 생기면 켠다
}

private void write(SelectionKey key) throws IOException {
    while (!c.out.isEmpty()) {
        ByteBuffer buf = c.out.peek();
        ch.write(buf);
        if (buf.hasRemaining()) return;                             // 송신 버퍼가 참 → 다음에 이어서
        c.out.poll();
    }
    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);   // 다 보냈으면 끈다
}
```

- 유휴 상태에서 select가 깨어나지 않는다. 실측: 유휴 1초 동안 **0회**(BadEchoServer 방식은 100만 회) → **문제 3 해결**
- 부분 쓰기를 대기열에 보관해 이어서 쓴다 → **문제 5(쓰기) 해결**

### 4단계: 느린 로직은 워커로, 결과는 작업 큐 + wakeup으로

```java
c.chain = c.chain.thenRunAsync(() -> {
    String response = handler.apply(line);         // 워커 스레드에서 실행
    tasks.add(() -> enqueue(key, response));       // 이벤트 루프에서 실행될 작업
    selector.wakeup();                             // select()에서 잠든 루프를 깨운다
}, workers);
```

- 이벤트 루프는 handler가 느려도 멈추지 않는다. 실측: 500ms handler가 있을 때 다른 연결의 응답 시간이 449ms → **0ms**
- Selector·키·`Connection`은 이벤트 루프 스레드만 만진다. 워커는 큐에 넣고 깨우기만 한다. 그래서 `Connection`의 버퍼·대기열에 락이 없다([이론 함정 6](./NIO셀렉터-이론.md#함정-6-다른-스레드에서-selector키연결-상태를-직접-만진다))
- `chain`을 이어 붙이므로 같은 연결의 요청은 앞 요청이 끝난 뒤에 실행된다. 워커 8개가 제각각의 시간으로 처리해도 응답 순서가 요청 순서와 같다(테스트 `responseOrderPreserved`)
- 종료(`close()`)도 같은 원리다. `running = false` 후 `wakeup()`으로 루프를 깨워 빠져나오게 한다. `running`은 다른 스레드가 바꾸므로 `volatile`이다([volatile](../volatile/volatile-이론.md))

`workerThreads`를 0으로 주면 handler를 이벤트 루프에서 직접 실행한다. 비교 데모를 위한 안티패턴 모드다.

---

## 5. 전체 코드

### LineEchoServer.java

```java
package com.study.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 줄('\n') 단위 요청/응답 서버. 스레드 하나(이벤트 루프)가 모든 연결의 I/O를 처리하고,
 * 느릴 수 있는 비즈니스 로직(handler)은 워커 풀에서 실행한다.
 *
 * 규칙: Selector와 SelectionKey, 연결 상태(Connection)는 이벤트 루프 스레드만 만진다.
 *       다른 스레드는 tasks 큐에 일을 넣고 selector.wakeup()으로 깨우기만 한다.
 */
public final class LineEchoServer implements Closeable {

    private static final int MAX_LINE = 8 * 1024;

    private final Selector selector;
    private final ServerSocketChannel server;
    private final Function<String, String> handler;
    private final ExecutorService workers;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Thread loopThread;
    // 다른 스레드(close 호출자)가 바꾸고 이벤트 루프가 읽으므로 가시성이 필요하다.
    private volatile boolean running = true;

    final AtomicLong selectCount = new AtomicLong();
    final AtomicInteger openConnections = new AtomicInteger();

    /** 연결마다 하나. SelectionKey의 attachment로 붙여 둔다. */
    private static final class Connection {
        // 읽기 버퍼는 연결마다 따로. 한 번에 다 오지 않은 줄의 앞부분을 다음 read까지 보관한다.
        final ByteBuffer in = ByteBuffer.allocate(MAX_LINE);
        // 아직 소켓에 다 못 쓴 응답들. 순서대로 비운다.
        final Queue<ByteBuffer> out = new ArrayDeque<>();
        // 같은 연결의 요청은 순서대로 처리해야 응답 순서가 보장된다. 앞 작업이 끝나야 다음이 시작되게 이어 붙인다.
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
    }

    public LineEchoServer(Function<String, String> handler, int workerThreads) throws IOException {
        this.handler = handler;
        // workerThreads == 0이면 handler를 이벤트 루프에서 직접 실행한다(비교 데모용 안티패턴).
        this.workers = workerThreads > 0 ? Executors.newFixedThreadPool(workerThreads) : null;
        this.selector = Selector.open();
        this.server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("127.0.0.1", 0), 1024);
        // 논블로킹으로 바꾸지 않으면 register에서 IllegalBlockingModeException이 난다.
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);
        this.loopThread = new Thread(this::loop, "event-loop");
        loopThread.start();
    }

    public int port() throws IOException {
        return ((InetSocketAddress) server.getLocalAddress()).getPort();
    }

    private void loop() {
        try {
            while (running) {
                selector.select();
                selectCount.incrementAndGet();
                runTasks();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    // Selector는 selectedKeys를 비워 주지 않는다. 지우지 않으면 다음 select 후에 같은 키를 또 처리한다.
                    it.remove();
                    try {
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isAcceptable()) {
                            accept();
                        }
                        if (key.isValid() && key.isReadable()) {
                            read(key);
                        }
                        if (key.isValid() && key.isWritable()) {
                            write(key);
                        }
                    } catch (IOException e) {
                        // 연결 하나의 오류(상대가 RST 등)로 이벤트 루프 전체가 죽으면 안 된다.
                        closeConnection(key);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            for (SelectionKey key : selector.keys()) {
                closeQuietly(key);
            }
            closeQuietly(selector);
            closeQuietly(server);
        }
    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }
    }

    private void accept() throws IOException {
        SocketChannel client;
        // 백로그에 쌓인 연결을 한 번에 받는다. 없으면 null(논블로킹).
        while ((client = server.accept()) != null) {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ, new Connection());
            openConnections.incrementAndGet();
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        Connection c = (Connection) key.attachment();
        int n = ch.read(c.in);
        if (n == -1) {
            // 상대가 FIN을 보냈다. 닫지 않으면 이 채널은 계속 "읽을 수 있음"으로 깨어난다(-1을 계속 돌려준다).
            closeConnection(key);
            return;
        }
        c.in.flip();
        int lineStart = c.in.position();
        for (int i = c.in.position(); i < c.in.limit(); i++) {
            if (c.in.get(i) == '\n') {
                byte[] line = new byte[i - lineStart];
                c.in.get(lineStart, line);
                dispatch(key, c, new String(line, StandardCharsets.UTF_8));
                lineStart = i + 1;
            }
        }
        c.in.position(lineStart);
        // 처리 못 한 줄 앞부분을 버퍼 앞으로 당겨, 다음 read가 그 뒤에 이어 쓰게 한다.
        c.in.compact();
        if (!c.in.hasRemaining()) {
            // 버퍼가 꽉 찼는데 줄바꿈이 없다. 무한히 기다리면 메모리를 잡아먹는 공격이 된다.
            closeConnection(key);
        }
    }

    private void dispatch(SelectionKey key, Connection c, String line) {
        if (workers == null) {
            // 안티패턴: handler가 200ms 걸리면 그동안 이 서버의 모든 연결이 멈춘다.
            enqueue(key, handler.apply(line));
            return;
        }
        // 이벤트 루프에서 handler를 직접 부르면, handler가 느릴 때 모든 연결이 멈춘다. 워커로 넘긴다.
        c.chain = c.chain.thenRunAsync(() -> {
            String response = handler.apply(line);
            tasks.add(() -> enqueue(key, response));
            // select()에서 잠든 이벤트 루프를 깨워 tasks를 실행시킨다.
            selector.wakeup();
        }, workers);
    }

    private void enqueue(SelectionKey key, String response) {
        if (!key.isValid()) {
            return; // 그사이 연결이 닫혔다
        }
        Connection c = (Connection) key.attachment();
        c.out.add(ByteBuffer.wrap((response + "\n").getBytes(StandardCharsets.UTF_8)));
        // 보낼 것이 생겼을 때만 OP_WRITE를 켠다.
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        Connection c = (Connection) key.attachment();
        while (!c.out.isEmpty()) {
            ByteBuffer buf = c.out.peek();
            ch.write(buf);
            if (buf.hasRemaining()) {
                // 소켓 송신 버퍼가 찼다. OP_WRITE를 켠 채로 두고, 다음에 비면 이어서 쓴다.
                return;
            }
            c.out.poll();
        }
        // 다 보냈으면 OP_WRITE를 끈다. 켜 두면 송신 버퍼에 빈자리가 있는 한 select가 계속 즉시 깨어난다.
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    private void closeConnection(SelectionKey key) {
        if (key.channel() instanceof SocketChannel && key.isValid()) {
            openConnections.decrementAndGet();
        }
        closeQuietly(key);
    }

    private static void closeQuietly(SelectionKey key) {
        key.cancel();
        closeQuietly(key.channel());
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // 정리 중 오류는 무시한다
        }
    }

    @Override
    public void close() {
        running = false;
        selector.wakeup();
        try {
            loopThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }
}
```

### BioEchoServer.java

```java
package com.study.nio;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** 비교용: 연결마다 스레드 하나(thread-per-connection)를 쓰는 블로킹 I/O 에코 서버. */
public final class BioEchoServer implements Closeable {

    private final ServerSocket server;

    public BioEchoServer() throws IOException {
        server = new ServerSocket(0, 1024, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "bio-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                // 연결 하나 = 플랫폼 스레드 하나. readLine()에서 블로킹되는 동안 이 스레드는 아무것도 못 한다.
                Thread t = new Thread(() -> handle(socket), "bio-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            String line;
            while ((line = in.readLine()) != null) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // 연결 종료
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
```

### BadEchoServer.java

3장의 코드와 같다.

### SelectorDemo.java

```java
package com.study.nio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SelectorDemo {

    public static void main(String[] args) throws Exception {
        implementation();
        threadsPerConnection();
        idleSpin();
        eofIgnored();
        staleSelectedKey();
        partialRead();
        partialWrite();
        blockingEventLoop();
        registerWhileSelecting();
    }

    private static void implementation() throws IOException {
        System.out.println("== 1. 이 JDK의 Selector 구현 ==");
        try (Selector s = Selector.open()) {
            System.out.println(System.getProperty("os.name") + " / JDK " + System.getProperty("java.version")
                    + " → " + s.getClass().getName());
        }
        System.out.println();
    }

    private static int threadCount() {
        return ManagementFactory.getThreadMXBean().getThreadCount();
    }

    private static void threadsPerConnection() throws Exception {
        System.out.println("== 2. 동시 연결 1,000개: 스레드 수 비교 ==");
        int n = 1000;
        int base = threadCount();
        try (LineEchoServer nio = new LineEchoServer(line -> line, 4)) {
            long t = System.nanoTime();
            List<Socket> clients = connectAndEcho(nio.port(), n);
            long ms = (System.nanoTime() - t) / 1_000_000;
            System.out.println("NIO Selector: 연결 " + nio.openConnections.get() + "개, 증가한 스레드 "
                    + (threadCount() - base) + "개 (이벤트 루프 1 + 워커 4), 전체 왕복 " + ms + " ms");
            closeAll(clients);
        }
        Thread.sleep(300);
        base = threadCount();
        try (BioEchoServer bio = new BioEchoServer()) {
            long t = System.nanoTime();
            List<Socket> clients = connectAndEcho(bio.port(), n);
            long ms = (System.nanoTime() - t) / 1_000_000;
            System.out.println("BIO 연결당 스레드: 증가한 스레드 " + (threadCount() - base) + "개, 전체 왕복 " + ms + " ms");
            closeAll(clients);
        }
        System.out.println();
    }

    private static List<Socket> connectAndEcho(int port, int n) throws IOException {
        List<Socket> clients = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
            s.getOutputStream().write(("hello-" + i + "\n").getBytes(StandardCharsets.UTF_8));
            clients.add(s);
        }
        for (int i = 0; i < n; i++) {
            BufferedReader in = new BufferedReader(new InputStreamReader(clients.get(i).getInputStream(), StandardCharsets.UTF_8));
            String echoed = in.readLine();
            if (!("hello-" + i).equals(echoed)) {
                throw new IllegalStateException("응답 불일치: " + echoed);
            }
        }
        return clients;
    }

    private static void closeAll(List<Socket> sockets) throws IOException {
        for (Socket s : sockets) {
            s.close();
        }
    }

    /** 서버 소켓 + 연결된 클라이언트 + 서버 쪽 채널 한 쌍을 만든다. */
    private static SocketChannel[] connectedPair(ServerSocketChannel listener) throws IOException {
        SocketChannel client = SocketChannel.open(listener.getLocalAddress());
        SocketChannel serverSide = listener.accept();
        serverSide.configureBlocking(false);
        return new SocketChannel[] {client, serverSide};
    }

    private static long countSelectsFor(Selector selector, long millis, boolean closeOnEof) throws IOException {
        long count = 0;
        long end = System.currentTimeMillis() + millis;
        ByteBuffer buf = ByteBuffer.allocate(64);
        while (System.currentTimeMillis() < end) {
            // 남은 시간만큼만 기다린다. 준비된 채널이 있으면 즉시 반환된다.
            selector.select(Math.max(1, end - System.currentTimeMillis()));
            count++;
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (key.isValid() && key.isReadable()) {
                    buf.clear();
                    int n = ((SocketChannel) key.channel()).read(buf);
                    if (n == -1 && closeOnEof) {
                        key.cancel();
                        key.channel().close();
                    }
                }
            }
        }
        return count;
    }

    private static void idleSpin() throws Exception {
        System.out.println("== 3. 할 일 없는 연결 1개를 1초 동안: OP_WRITE를 켜 두면 ==");
        try (ServerSocketChannel listener = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
            SocketChannel[] pair = connectedPair(listener);
            try (Selector selector = Selector.open()) {
                SelectionKey key = pair[1].register(selector, SelectionKey.OP_READ);
                System.out.println("OP_READ만        : select 반환 " + countSelectsFor(selector, 1000, true) + "회");
                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                long t = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                long count = countSelectsFor(selector, 1000, true);
                long cpuMs = (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - t) / 1_000_000;
                System.out.println("OP_READ|OP_WRITE : select 반환 " + count + "회, 이 스레드 CPU 사용 " + cpuMs + " ms / 1000 ms");
            }
            pair[0].close();
            pair[1].close();
        }
        try (LineEchoServer server = new LineEchoServer(line -> line, 2);
             Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            Thread.sleep(200);
            long before = server.selectCount.get();
            Thread.sleep(1000);
            System.out.println("LineEchoServer   : 유휴 1초 동안 select 반환 " + (server.selectCount.get() - before) + "회");
        }
        System.out.println();
    }

    private static void eofIgnored() throws Exception {
        System.out.println("== 4. 상대가 연결을 끊었는데 read() == -1을 무시하면 ==");
        try (ServerSocketChannel listener = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
            SocketChannel[] pair = connectedPair(listener);
            pair[0].close(); // 클라이언트가 FIN 전송
            try (Selector selector = Selector.open()) {
                pair[1].register(selector, SelectionKey.OP_READ);
                System.out.println("-1 무시     : 1초 동안 select 반환 " + countSelectsFor(selector, 1000, false) + "회");
            }
            pair[1].close();
            SocketChannel[] pair2 = connectedPair(listener);
            pair2[0].close();
            try (Selector selector = Selector.open()) {
                pair2[1].register(selector, SelectionKey.OP_READ);
                System.out.println("-1이면 close: 1초 동안 select 반환 " + countSelectsFor(selector, 1000, true) + "회");
            }
        }
        System.out.println();
    }

    private static void staleSelectedKey() throws Exception {
        System.out.println("== 5. selectedKeys에서 키를 지우지 않으면 (BadEchoServer) ==");
        BadEchoServer bad = new BadEchoServer();
        Thread t = new Thread(bad::run, "bad-loop");
        t.start();
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), bad.port())) {
            t.join(2000);
            Throwable crash = bad.crash.get();
            System.out.println("클라이언트 1개 접속 후 이벤트 루프: " + (crash == null ? "살아 있음" : "종료됨"));
            if (crash != null) {
                System.out.println("원인: " + crash.getClass().getSimpleName() + " — " + crash.getMessage());
                System.out.println("죽기 전 select 반환 횟수: " + bad.selectCount.get());
            }
        }
        t.interrupt();
        System.out.println();
    }

    private static void partialRead() throws Exception {
        System.out.println("== 6. TCP는 메시지 경계를 모른다 ==");
        try (ServerSocketChannel listener = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
            SocketChannel[] pair = connectedPair(listener);
            pair[0].write(ByteBuffer.wrap("HEL".getBytes(StandardCharsets.UTF_8)));
            Thread.sleep(200);
            pair[0].write(ByteBuffer.wrap("LO\nWOR".getBytes(StandardCharsets.UTF_8)));
            Thread.sleep(200);
            ByteBuffer buf = ByteBuffer.allocate(64);
            pair[1].configureBlocking(true);
            // 위 두 write가 이미 도착했으므로 서버 쪽 read는 한 번에 모두 가져올 수도, 나눠 가져올 수도 있다.
            int n = pair[1].read(buf);
            System.out.println("클라이언트: write(\"HEL\") → 200ms → write(\"LO\\nWOR\")");
            System.out.println("서버 read 1회: " + n + "바이트 \"" + new String(buf.array(), 0, n, StandardCharsets.UTF_8).replace("\n", "\\n") + "\"");
            pair[0].close();
            pair[1].close();
        }
        try (LineEchoServer server = new LineEchoServer(String::toUpperCase, 2);
             Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.write("hel".getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(200);
            out.write("lo\nwor".getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(200);
            out.write("ld\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            System.out.println("LineEchoServer(대문자 변환)에 \"hel\" / \"lo\\nwor\" / \"ld\\n\" 세 번 나눠 보냄 → 응답 \""
                    + in.readLine() + "\", \"" + in.readLine() + "\"");
        }
        System.out.println();
    }

    private static void partialWrite() throws Exception {
        System.out.println("== 7. 상대가 읽지 않으면 write는 일부만 쓴다 ==");
        try (ServerSocketChannel listener = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
            SocketChannel[] pair = connectedPair(listener);
            ByteBuffer big = ByteBuffer.allocate(32 * 1024 * 1024);
            int calls = 0;
            long total = 0;
            int written;
            // 클라이언트(pair[0])는 아무것도 읽지 않는다.
            while ((written = pair[1].write(big)) > 0) {
                total += written;
                calls++;
            }
            System.out.println("32MB를 write: " + calls + "번 호출로 " + total + "바이트 쓴 뒤 write()가 0을 반환, 남은 "
                    + big.remaining() + "바이트");
            pair[0].close();
            pair[1].close();
        }
        System.out.println();
    }

    private static long roundTripMs(int port, String line) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            long t = System.nanoTime();
            s.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return (System.nanoTime() - t) / 1_000_000;
        }
    }

    private static void blockingEventLoop() throws Exception {
        System.out.println("== 8. 이벤트 루프에서 느린 작업(500ms)을 실행하면 ==");
        java.util.function.Function<String, String> handler = line -> {
            if (line.equals("slow")) {
                try {
                    Thread.sleep(500); // DB·외부 API 호출을 흉내
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return line;
        };
        for (int workers : new int[] {0, 4}) {
            try (LineEchoServer server = new LineEchoServer(handler, workers)) {
                Thread slow = new Thread(() -> {
                    try {
                        roundTripMs(server.port(), "slow");
                    } catch (IOException ignored) {
                        // 데모
                    }
                });
                slow.start();
                Thread.sleep(50); // slow 요청이 먼저 처리되기 시작하도록
                long fastMs = roundTripMs(server.port(), "fast");
                slow.join();
                System.out.println((workers == 0 ? "이벤트 루프에서 직접 실행" : "워커 풀(4)로 넘김     ")
                        + " → 다른 연결의 \"fast\" 요청 응답 시간: " + fastMs + " ms");
            }
        }
        System.out.println();
    }

    private static void registerWhileSelecting() throws Exception {
        System.out.println("== 9. select()가 블로킹 중일 때 다른 스레드에서 register ==");
        try (Selector selector = Selector.open();
             ServerSocketChannel ch = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
            ch.configureBlocking(false);
            Thread loop = new Thread(() -> {
                try {
                    selector.select(); // 깨울 때까지 무기한 대기
                } catch (IOException ignored) {
                    // 데모
                }
            });
            loop.start();
            Thread.sleep(300);
            long t = System.nanoTime();
            ch.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("register 소요: " + (System.nanoTime() - t) / 1000 + " µs (JDK 11+는 블로킹되지 않음)");
            selector.wakeup();
            loop.join();
        }
    }
}
```

---

## 6. 실행 결과

OpenJDK 25.0.4, Windows 11에서 **실제로 실행한 출력**이다. 실행마다·환경마다 달라지는 부분:
- 2번 왕복 시간, 3·4번 select 횟수, 8번 응답 시간: 하드웨어·OS 스케줄링에 따라 다르다. 자릿수(0회 vs 수십만 회, 0ms vs 수백 ms)를 보면 된다
- 6번 첫 read 결과: 두 write가 이미 도착한 뒤 읽었으므로 한 번에 합쳐져 나왔다. 타이밍에 따라 나뉘어 읽힐 수도 있다. 어느 쪽이든 "write 단위 ≠ read 단위"라는 점이 핵심이다
- 7번 쓴 바이트 수: OS 소켓 버퍼 크기(송신 + 상대 수신 버퍼)에 따라 다르다. Linux·macOS에서는 다른 값이 나온다
- 1번 구현 클래스: Linux는 `EPollSelectorImpl`, macOS는 `KQueueSelectorImpl`
- 5번 NPE 메시지의 `<local3>`: 컴파일 옵션(디버그 정보 포함 여부)에 따라 변수명으로 표시될 수 있다

```
== 1. 이 JDK의 Selector 구현 ==
Windows 11 / JDK 25.0.4 → sun.nio.ch.WEPollSelectorImpl

== 2. 동시 연결 1,000개: 스레드 수 비교 ==
NIO Selector: 연결 1000개, 증가한 스레드 5개 (이벤트 루프 1 + 워커 4), 전체 왕복 117 ms
BIO 연결당 스레드: 증가한 스레드 1001개, 전체 왕복 109 ms

== 3. 할 일 없는 연결 1개를 1초 동안: OP_WRITE를 켜 두면 ==
OP_READ만        : select 반환 1회
OP_READ|OP_WRITE : select 반환 1057854회, 이 스레드 CPU 사용 1000 ms / 1000 ms
LineEchoServer   : 유휴 1초 동안 select 반환 0회

== 4. 상대가 연결을 끊었는데 read() == -1을 무시하면 ==
-1 무시     : 1초 동안 select 반환 435889회
-1이면 close: 1초 동안 select 반환 2회

== 5. selectedKeys에서 키를 지우지 않으면 (BadEchoServer) ==
클라이언트 1개 접속 후 이벤트 루프: 종료됨
원인: NullPointerException — Cannot invoke "java.nio.channels.SocketChannel.configureBlocking(boolean)" because "<local3>" is null
죽기 전 select 반환 횟수: 2

== 6. TCP는 메시지 경계를 모른다 ==
클라이언트: write("HEL") → 200ms → write("LO\nWOR")
서버 read 1회: 9바이트 "HELLO\nWOR"
LineEchoServer(대문자 변환)에 "hel" / "lo\nwor" / "ld\n" 세 번 나눠 보냄 → 응답 "HELLO", "WORLD"

== 7. 상대가 읽지 않으면 write는 일부만 쓴다 ==
32MB를 write: 1번 호출로 393213바이트 쓴 뒤 write()가 0을 반환, 남은 33161219바이트

== 8. 이벤트 루프에서 느린 작업(500ms)을 실행하면 ==
이벤트 루프에서 직접 실행 → 다른 연결의 "fast" 요청 응답 시간: 449 ms
워커 풀(4)로 넘김      → 다른 연결의 "fast" 요청 응답 시간: 0 ms

== 9. select()가 블로킹 중일 때 다른 스레드에서 register ==
register 소요: 38 µs (JDK 11+는 블로킹되지 않음)
```

결과 읽기:

- **2번**: 처리량(왕복 시간)은 1,000개 수준에서 비슷하다. 차이는 **자원**이다. NIO는 스레드 5개, BIO는 1,001개였다. 연결이 1만·10만으로 늘거나 대부분 유휴 상태(keep-alive, 푸시)일수록 이 차이가 결정적이 된다.
- **3번**: OP_WRITE 하나 때문에 할 일 없는 서버가 코어 하나를 다 썼다. `LineEchoServer`는 유휴 1초 동안 한 번도 깨어나지 않았다.
- **4번**: 끊어진 연결을 닫지 않으면 같은 스핀이 생긴다. 닫으면 2회(EOF 처리 1회 + 타임아웃 1회)로 끝난다.
- **5번**: 클라이언트 하나가 접속하자 select 2회 만에 이벤트 루프가 죽었다. 이후 모든 연결이 응답을 받지 못한다.
- **6번**: `LineEchoServer`는 세 번에 나뉘어 온 바이트에서 정확히 두 줄을 만들어 냈다.
- **7번**: 상대가 읽지 않으면 첫 write에서 약 393KB만 들어가고 이후로는 0이다. 반환값을 무시하면 나머지 32MB는 그냥 사라진다.
- **8번**: 이벤트 루프에서 500ms 작업을 하면 그 작업과 무관한 연결도 기다렸다(449ms. slow 요청이 50ms 먼저 시작해 500 - 50 ≈ 450). 워커로 넘기면 즉시 응답한다.
- **9번**: JDK 25에서는 `select()` 중에도 `register()`가 38µs 만에 반환됐다. Java 8에서는 이 호출이 `select()`가 끝날 때까지 블로킹될 수 있다(이론 10장). 이 데모는 Java 8에서 실행하지 않았다.

---

## 7. 검증 코드

```java
package com.study.nio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class SelectorTest {

    private static Socket connect(int port) throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(5_000);
        return s;
    }

    private static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    private static void send(Socket s, String text) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("조건을 5초 안에 만족하지 못함");
            }
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("스레드 하나(이벤트 루프)가 동시 연결 200개를 모두 처리한다")
    void manyConnectionsOneLoop() throws Exception {
        try (LineEchoServer server = new LineEchoServer(line -> line, 2)) {
            List<Socket> clients = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                Socket s = connect(server.port());
                send(s, "msg-" + i + "\n");
                clients.add(s);
            }
            for (int i = 0; i < 200; i++) {
                assertEquals("msg-" + i, reader(clients.get(i)).readLine());
            }
            assertEquals(200, server.openConnections.get());
            for (Socket s : clients) {
                s.close();
            }
        }
    }

    @Test
    @DisplayName("한 줄이 여러 번에 나뉘어 와도 줄 단위로 한 번만 응답한다")
    void framingAcrossReads() throws Exception {
        try (LineEchoServer server = new LineEchoServer(String::toUpperCase, 2); Socket s = connect(server.port())) {
            BufferedReader in = reader(s);
            send(s, "hel");
            Thread.sleep(100);
            send(s, "lo\nwor");
            Thread.sleep(100);
            send(s, "ld\n");
            assertEquals("HELLO", in.readLine());
            assertEquals("WORLD", in.readLine());
        }
    }

    @Test
    @DisplayName("한 번에 여러 줄이 와도 각각 응답한다")
    void manyLinesInOneRead() throws Exception {
        try (LineEchoServer server = new LineEchoServer(line -> line, 2); Socket s = connect(server.port())) {
            BufferedReader in = reader(s);
            send(s, "a\nb\nc\n");
            assertEquals("a", in.readLine());
            assertEquals("b", in.readLine());
            assertEquals("c", in.readLine());
        }
    }

    @Test
    @DisplayName("워커 8개가 처리 시간이 제각각이어도 같은 연결의 응답 순서는 요청 순서와 같다")
    void responseOrderPreserved() throws Exception {
        try (LineEchoServer server = new LineEchoServer(line -> {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return line;
        }, 8); Socket s = connect(server.port())) {
            StringBuilder pipelined = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                pipelined.append(i).append('\n');
            }
            send(s, pipelined.toString());
            BufferedReader in = reader(s);
            for (int i = 0; i < 100; i++) {
                assertEquals(String.valueOf(i), in.readLine());
            }
        }
    }

    @Test
    @DisplayName("클라이언트가 끊으면 서버가 -1을 보고 채널을 닫는다")
    void closesOnEof() throws Exception {
        try (LineEchoServer server = new LineEchoServer(line -> line, 2)) {
            Socket s = connect(server.port());
            await(() -> server.openConnections.get() == 1);
            s.close();
            await(() -> server.openConnections.get() == 0);
        }
    }

    @Test
    @DisplayName("유휴 상태에서는 select가 깨어나지 않는다 (OP_WRITE를 필요할 때만 켬)")
    void noBusySpinWhenIdle() throws Exception {
        try (LineEchoServer server = new LineEchoServer(line -> line, 2); Socket s = connect(server.port())) {
            BufferedReader in = reader(s);
            send(s, "ping\n");
            assertEquals("ping", in.readLine()); // 한 번 쓰고 나면 OP_WRITE가 꺼져야 한다
            Thread.sleep(100);
            long before = server.selectCount.get();
            Thread.sleep(500);
            assertTrue(server.selectCount.get() - before < 5, "유휴 중 select 반환: " + (server.selectCount.get() - before));
        }
    }

    @Test
    @DisplayName("줄바꿈 없이 8KB를 넘기면 연결을 끊는다")
    void lineTooLong() throws Exception {
        try (LineEchoServer server = new LineEchoServer(line -> line, 2); Socket s = connect(server.port())) {
            send(s, "x".repeat(10_000));
            assertNull(reader(s).readLine()); // 서버가 닫으면 EOF
        }
    }

    @Test
    @DisplayName("close()는 wakeup으로 이벤트 루프를 깨워 종료하고 포트를 닫는다")
    void shutdownWithWakeup() throws Exception {
        LineEchoServer server = new LineEchoServer(line -> line, 2);
        int port = server.port();
        server.close();
        assertThrows(ConnectException.class, () -> connect(port).close());
    }

    @Test
    @DisplayName("selectedKeys를 지우지 않는 서버는 첫 클라이언트 접속 후 NPE로 죽는다")
    void staleKeyCrashesBadServer() throws Exception {
        BadEchoServer bad = new BadEchoServer();
        Thread t = new Thread(bad::run);
        t.start();
        try (Socket s = connect(bad.port())) {
            t.join(5_000);
            assertInstanceOf(NullPointerException.class, bad.crash.get());
        } finally {
            t.interrupt();
        }
    }
}
```

실행 결과 (JUnit Platform Console Launcher 1.11.4, 실제 실행):

```
└─ SelectorTest ✔
   ├─ selectedKeys를 지우지 않는 서버는 첫 클라이언트 접속 후 NPE로 죽는다 ✔
   ├─ 줄바꿈 없이 8KB를 넘기면 연결을 끊는다 ✔
   ├─ 유휴 상태에서는 select가 깨어나지 않는다 (OP_WRITE를 필요할 때만 켬) ✔
   ├─ 스레드 하나(이벤트 루프)가 동시 연결 200개를 모두 처리한다 ✔
   ├─ close()는 wakeup으로 이벤트 루프를 깨워 종료하고 포트를 닫는다 ✔
   ├─ 한 줄이 여러 번에 나뉘어 와도 줄 단위로 한 번만 응답한다 ✔
   ├─ 클라이언트가 끊으면 서버가 -1을 보고 채널을 닫는다 ✔
   ├─ 워커 8개가 처리 시간이 제각각이어도 같은 연결의 응답 순서는 요청 순서와 같다 ✔
   └─ 한 번에 여러 줄이 와도 각각 응답한다 ✔

[         9 tests successful      ]
[         0 tests failed          ]

```

`@Timeout(20)`을 붙였다. 네트워크 테스트는 버그가 있으면 실패하는 대신 **영원히 멈추기** 쉽다(응답이 오지 않는 `readLine()`). 소켓에도 `setSoTimeout(5_000)`을 걸어 무한 대기를 막았다. 유휴 스핀 테스트(`noBusySpinWhenIdle`)는 "5회 미만"처럼 여유 있는 기준을 써서 환경 차이로 흔들리지 않게 했다.

---

## 8. 변형 실습

1. **`LineEchoServer.write()` 마지막 줄(`OP_WRITE` 끄기)을 지우면?**
   `noBusySpinWhenIdle` 테스트가 실패하고, 실패 메시지에 유휴 0.5초 동안의 select 횟수(수십만)가 찍힌다. 응답은 정상으로 나가므로 **기능 테스트는 모두 통과한다**. 운영에서 "CPU만 높고 기능은 정상"인 장애가 어떻게 생기는지, 그리고 이런 성질을 테스트로 고정해 두는 이유를 생각해 보자.

2. **`dispatch()`의 `c.chain = c.chain.thenRunAsync(...)`를 `CompletableFuture.runAsync(..., workers)`로 바꾸면?**
   `responseOrderPreserved` 테스트를 여러 번 돌려 보자. handler가 0~4ms 랜덤으로 잠들기 때문에 응답 순서가 뒤바뀌어 실패한다(실행마다 실패 위치가 다르다). HTTP/1.1 파이프라이닝, Redis 파이프라이닝처럼 "응답 순서로 요청을 매칭하는" 프로토콜에서 이 버그가 무엇을 의미하는지 생각해 보자.

3. **`selectedKeys()` 순회를 Java 11+의 `selector.select(this::handle)`로 바꿔 보자.**
   `it.remove()`가 사라지고, 함정 1이 구조적으로 불가능해진다. 바꾼 뒤 모든 테스트가 통과하는지 확인한다. 이어서 `BadEchoServer`도 같은 방식으로 바꾸면 NPE가 사라지는지, 그래도 남는 문제(OP_WRITE 스핀, -1 무시)는 무엇인지 확인해 보자.
