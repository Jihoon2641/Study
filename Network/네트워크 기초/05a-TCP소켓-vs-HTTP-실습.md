---
장: 5a
주제: TCP 소켓 직접 통신 vs HTTP(S) — 실습 (메시지 경계, 길이 헤더, 프로토콜 불일치)
분류: 네트워크 / 전송 계층과 응용 계층의 경계
난이도: 중급
관련표준: RFC 9293, RFC 9112
실습환경: Docker Desktop(WSL2 백엔드) 또는 Linux Docker, Linux 컨테이너(eclipse-temurin 21 JDK, tcpdump, netcat)
비용발생: 없음
작성일: 2026-11-07
선행지식: [TCP 소켓 vs HTTP 이론](./05a-TCP소켓-vs-HTTP-이론.md), [TCP와 UDP](./03-TCP-이론.md)
---

# 05a. TCP 소켓 직접 통신 vs HTTP(S) — 실습

> 관련 문서: [이론](./05a-TCP소켓-vs-HTTP-이론.md) · 실습 파일: [`labs/05a-socket/`](./labs/05a-socket/)

## 1. 실습 개요

같은 TCP 소켓 위에 **HTTP(JDK 내장 `jwebserver`)**와 **직접 만든 길이 헤더 프로토콜(`FrameServer`)**을 올려 놓고, 다음을 눈으로 확인한다.

| 단계 | 관찰·재현하는 것 | 이론 문서 위치 |
|---|---|---|
| 3-1 | HTTP도 결국 소켓에 쓰는 텍스트 바이트이며, `Content-Length`가 메시지 경계를 알려준다 | 2절, 5절 "HTTP/1.1은 어떻게 메시지 끝을 아는가" |
| 3-2 | HTTP 서버는 엉뚱한 바이트에도 **표준 오류(400)**로 답한다 | 2절 "HTTP가 기본값이 된 이유" |
| 4-1 | `read()` 한 번 ≠ 메시지 하나. 메시지가 붙고 잘린다 | 4-3, 9-1 |
| 4-2 | 길이를 문자 수로 계산하면 한글 전문만 깨지고 이후 스트림 전체가 어긋난다 | 6-4, 9-2 |
| 4-3 | HTTP 요청을 길이 헤더 서버에 보내면 `"GET "`이 약 1.1GB 길이로 해석되어 OOM | 9-3 |
| 5-1~5-2 | 길이 헤더 + `readFully` + 길이 상한으로 위 문제가 사라진다 | 5절 프레이밍 |
| 5-3 | 와이어 위에서 HTTP와 자체 프로토콜의 바이트를 나란히 비교한다 | 4-1, 7절 |

## 2. 환경과 준비물

> 💰 **비용 발생 없음.** 로컬 Docker만 사용한다.

| 항목 | 요구 사항 |
|---|---|
| Windows 11 | Docker Desktop (WSL2 백엔드) |
| macOS / Linux | Docker Desktop 또는 Docker Engine + Compose 플러그인. 명령 동일 |
| 인터넷 | 이미지 다운로드와 `apt-get install` |

- Java는 호스트에 필요 없다. 컨테이너 안의 JDK 21로 `java 파일명.java`(단일 파일 소스 실행, JDK 11+) 방식으로 컴파일 없이 실행한다.
- 코드 문법은 Java 8 호환으로 썼다. Java 8에서 돌리려면 `javac`로 먼저 컴파일한다(6절 파일 주석 참고).
- OS별 차이(Windows 루프백 캡처, macOS `tcpdump` 권한)를 없애기 위해 모든 관찰은 **컨테이너 사이**에서 한다. 루프백이 아니라 실제 가상 NIC(`eth0`)를 거치므로 세그먼트가 진짜로 오간다.

**터미널을 3개** 연다.

| 표기 | 실행 위치 | 용도 |
|---|---|---|
| `$` | 호스트 터미널 (PowerShell 또는 WSL2 bash) | compose 조작 |
| `[server] #` | `$ docker compose exec server bash` | 서버 프로그램 실행 (출력을 계속 봐야 함) |
| `[client] #` | `$ docker compose exec client bash` | 클라이언트 실행 |
| `[client-2] #` | 두 번째 `$ docker compose exec client bash` | tcpdump |

### 기동

```text
$ cd "C:\Workspace\Study\Network\네트워크 기초\labs\05a-socket"
$ docker compose up -d --build
$ docker compose ps
```

예시 출력:

```text
NAME                 IMAGE          COMMAND                  SERVICE   STATUS
ch05a-lab-client-1   ch05a-lab:1    "/sbin/docker-init -…"   client    Up 3 seconds
ch05a-lab-server-1   ch05a-lab:1    "/sbin/docker-init -…"   server    Up 3 seconds
```

빌드가 `eclipse-temurin:21.0.4_7-jdk-jammy` 태그를 찾지 못해 실패하면 `Dockerfile`의 `FROM`을 Docker Hub에 있는 다른 `21.x.x_x-jdk-jammy` 태그로 바꾼다.

도구 확인:

```text
[client] # java -version 2>&1 | head -1
[client] # getent hosts server
```

예시 출력 (IP는 환경마다 다름):

```text
openjdk version "21.0.4" 2024-07-16 LTS
172.20.0.2      server
```

## 3. 관찰 먼저

### 3-1. HTTP 요청을 손으로 보내기 — HTTP도 소켓 위의 텍스트다

서버에서 JDK 내장 HTTP 서버를 띄운다.

```text
[server] # jwebserver -b 0.0.0.0 -p 8000 -d /www
```

예시 출력:

```text
Serving /www and subdirectories on 0.0.0.0 (all interfaces) port 8000
URL http://172.20.0.2:8000/
```

클라이언트에서 **HTTP 클라이언트가 아닌** `nc`로 바이트를 직접 쓴다. `nc`는 프로토콜을 전혀 모르고 받은 바이트를 그대로 TCP에 쓰는 도구다.

```text
[client] # printf 'GET /hello.txt HTTP/1.1\r\nHost: server\r\nConnection: close\r\n\r\n' | nc server 8000
```

예시 출력 (`Date`, `Last-modified` 값과 헤더 대소문자는 다를 수 있음):

```text
HTTP/1.1 200 OK
Date: Sat, 07 Nov 2026 03:12:45 GMT
Last-modified: Sat, 07 Nov 2026 02:50:10 GMT
Content-type: text/plain; charset=utf-8
Content-length: 6

msg-1
```

**읽는 법**

- 우리가 보낸 것은 `GET ...` 으로 시작하는 **텍스트 바이트**였고, 서버가 돌려준 것도 텍스트다. `curl`, `RestClient`, 브라우저가 하는 일도 결국 이것이다.
- 요청의 끝은 빈 줄(`\r\n\r\n`, 구분자 방식)로, 응답 본문의 끝은 `Content-length: 6`(길이 접두 방식)으로 표시된다. 본문 `msg-1\n`은 정확히 6바이트다. **HTTP가 프레이밍을 표준화했다**는 이론 5절의 내용이 이것이다.
- `Connection: close`를 빼면 `nc`가 응답 후에도 종료되지 않는다. 서버가 keep-alive로 커넥션을 유지하기 때문이다(5장). `Ctrl+C`로 빠져나온다.

### 3-2. HTTP 서버에 HTTP가 아닌 바이트를 보내기

```text
[client] # printf 'hello\r\n\r\n' | nc server 8000
```

예시 출력 (`jwebserver` 버전에 따라 본문 문구는 다를 수 있음. 아무 응답 없이 끊기는 버전이면 그 사실을 기록한다):

```text
HTTP/1.1 400 Bad Request
Date: Sat, 07 Nov 2026 03:13:20 GMT
Content-length: 37

<h1>400 Bad Request</h1>Bad request line
```

**읽는 법**: 서버는 요청 줄(`메서드 SP URI SP 버전`) 형식이 아님을 알아채고 **누구나 이해하는 형식으로** 거부했다. 상태 코드 400은 curl, 브라우저, 로드밸런서 메트릭, Spring의 `HttpClientErrorException.BadRequest`까지 모두 같은 의미로 해석한다. 4-3에서 자체 프로토콜 서버가 같은 상황을 어떻게 다루는지와 비교한다.

`[server]` 터미널에서 `Ctrl+C`로 `jwebserver`를 끈다.

## 4. 문제 상황 재현

### 4-1. `read()` 한 번을 메시지 하나로 착각한 서버

`naive` 모드 서버는 `in.read(buf)`가 돌려준 바이트를 메시지 하나로 출력한다. 처리 시간을 흉내 내려고 매 `read()` 뒤 300ms 쉰다.

```text
[server] # java /src/FrameServer.java naive
```

```text
[server] mode=naive port=9000 maxLen=1048576
```

클라이언트는 길이 헤더 없이 ① `msg-1`~`msg-5`를 `write()` 5번으로 연달아 보내고 ② `HELLO-WORLD`를 `write()` 2번으로 1초 간격을 두고 나눠 보낸다. `TCP_NODELAY`를 켰으므로 `write()` 하나가 세그먼트 하나로 나간다.

```text
[client] # java /src/FrameClient.java raw
```

클라이언트 예시 출력:

```text
[client] write(5 bytes) msg-1
[client] write(5 bytes) msg-2
[client] write(5 bytes) msg-3
[client] write(5 bytes) msg-4
[client] write(5 bytes) msg-5
[client] write(5 bytes) HELLO-WORLD 앞부분
[client] write(6 bytes) HELLO-WORLD 뒷부분
[client] done
```

서버 예시 출력 (`read()` 묶음은 실행할 때마다 달라질 수 있다. **그것이 요점이다**):

```text
[server] accepted /172.20.0.3:41822
[server] read() #1 -> 5 bytes: "msg-1"
[server] read() #2 -> 25 bytes: "msg-2msg-3msg-4msg-5HELLO"
[server] read() #3 -> 6 bytes: "-WORLD"
[server] read() returned -1 (FIN 수신)
[server] closed /172.20.0.3:41822
```

**읽는 법**

- 클라이언트는 `write()`를 7번 했지만 서버의 `read()`는 3번이었다.
- `#1`: 서버가 `read()`에서 기다리고 있었으므로 첫 세그먼트가 오자마자 돌려받았다.
- `#2`: 서버가 300ms 쉬는 동안 나머지 4개와 `HELLO`가 커널 수신 버퍼에 **이어 붙었다**. 메시지 5개가 하나로 보인다(붙음).
- `#3`: `HELLO-WORLD`의 뒷부분은 1초 뒤에 왔으므로 따로 읽혔다. 메시지 하나가 둘로 보인다(잘림).
- 서버의 `sleep`을 빼더라도 결과가 항상 7번으로 나뉜다는 보장은 없다. 운영 환경에서는 GC 정지, 스레드 스케줄링, 네트워크 지연이 `sleep`과 같은 역할을 한다.

`[server]`에서 `Ctrl+C`.

### 4-2. 길이를 문자 수로 계산한 클라이언트

이번에는 길이 헤더 서버(`framed`)를 띄운다. `[4바이트 빅 엔디언 길이][UTF-8 본문]` 규칙으로 메시지를 정확히 복원하고 `ACK:<본문>`으로 응답한다.

```text
[server] # java /src/FrameServer.java framed
```

`charlen` 모드 클라이언트는 `"안녕하세요"`를 보내면서 길이 헤더에 `String.length()` = **5**를 넣는다. 실제 UTF-8 바이트는 15다. 이어서 정상 전문 `msg-2`를 보낸다.

```text
[client] # java /src/FrameClient.java charlen
```

클라이언트 예시 출력:

```text
[client] write(19 bytes) [len=5] 안녕하세요 (실제 15 bytes)
[client] write(9 bytes) [len]msg-2
[client] response: "ACK:안�"
[client] 응답 2/2 대기 중 서버가 커넥션을 닫음 (EOF)
[client] done
```

서버 예시 출력:

```text
[server] accepted /172.20.0.3:41830
[server] header len=5 (0x00000005)
[server] message (5 bytes, body 완성까지 0ms): "안�"
[server] header len=-1779591784 (0x95ED9598)
[server] REJECT: len=-1779591784 는 허용 범위(0..1048576)를 벗어남. 커넥션을 닫는다
[server] closed /172.20.0.3:41830
```

`�`(대체 문자)의 개수는 JDK 디코더 동작에 따라 1~2개로 보일 수 있다. 나머지 값은 바이트가 고정이므로 항상 같다.

**읽는 법**: 바이트 단위로 따라가 보면 원인이 정확히 보인다.

```text
보낸 바이트:  00 00 00 05 | EC 95 88  EB 85 95  ED 95 98  EC 84 B8  EC 9A 94 | 00 00 00 05 6D 73 67 2D 32
              길이=5       |   안         녕         하         세         요    | 길이=5      m  s  g  -  2
서버 해석:    [길이=5]      [본문 5바이트: EC 95 88 EB 85] [다음 길이: 95 ED 95 98] ...
                            = "안" + 녕의 앞 2바이트(깨짐)   = 0x95ED9598 → 음수
```

1. 서버는 길이 5를 믿고 5바이트만 본문으로 가져갔다. `안`(3바이트) + `녕`의 앞 2바이트 → 깨진 문자.
2. 그다음 4바이트(`녕`의 마지막 바이트 + `하`)를 **다음 메시지의 길이**로 읽었다. 이후 스트림 전체가 어긋난다.
3. 정상 전문 `msg-2`는 처리될 기회조차 없었다. **한 번 어긋난 길이 헤더 스트림은 스스로 복구되지 않는다.** 그래서 서버는 이상한 길이를 보면 커넥션을 끊는 것이 맞다.
4. 영문만 보냈다면 문자 수 = 바이트 수라서 버그가 드러나지 않는다. 개발 중 테스트 데이터가 영문뿐이면 운영에서 처음 터진다.

`[server]`는 그대로 둔다.

### 4-3. 길이 헤더 서버에 HTTP 요청이 들어오면

`[server]`에서 `Ctrl+C` 후, **길이 상한 검사를 끄고**(세 번째 인자 `0`) 힙을 64MB로 제한해 띄운다.

```text
[server] # java -Xmx64m /src/FrameServer.java framed 9000 0
```

```text
[server] mode=framed port=9000 maxLen=0
```

누군가 이 포트를 HTTP 서버로 착각하고 요청을 보낸 상황이다. 로드밸런서의 HTTP 헬스체크를 잘못 걸었거나, 보안 스캐너가 지나간 경우와 같다.

```text
[client] # curl -sS --max-time 5 http://server:9000/
```

서버 예시 출력 (스택 트레이스의 줄 번호·스레드 이름은 다를 수 있음):

```text
[server] accepted /172.20.0.3:41836
[server] header len=1195725856 (0x47455420)
[server] closed /172.20.0.3:41836
Exception in thread "Thread-0" java.lang.OutOfMemoryError: Java heap space
	at FrameServer.framed(FrameServer.java:95)
	at FrameServer$1.run(FrameServer.java:43)
	at java.base/java.lang.Thread.run(Thread.java:1583)
```

클라이언트 예시 출력:

```text
curl: (52) Empty reply from server
```

**읽는 법**

- `0x47455420`을 바이트로 나누면 `47 45 54 20` = ASCII `G E T 공백`. HTTP 요청의 첫 4글자가 길이로 해석되어 약 1.1GB 배열을 만들려다 OOM이 났다.
- 3-2의 HTTP 서버는 같은 상황에 **400으로 이유를 알려줬지만**, 자체 프로토콜 서버는 상대에게 아무것도 알려줄 수 없다. curl은 "빈 응답"만 받는다.
- 이 실습에서는 해당 스레드만 죽었지만, 실제 서버에서는 **같은 힙을 쓰는 다른 스레드**(정상 거래 처리 중인 스레드)에서 OOM이 터질 수도 있다. 힙이 충분하면 OOM 대신 1.1GB가 올 때까지 `readFully`에서 영원히 기다리는 스레드가 생긴다.

서버 프로세스는 계속 살아 있다. `Ctrl+C`로 끈다.

## 5. 개선된 구성

### 5-1. 길이 헤더 + `readFully`로 경계 복원

기본값(길이 상한 1MB)으로 `framed` 서버를 띄우고, 4-1과 같은 시나리오를 길이 헤더를 붙여 보낸다.

```text
[server] # java /src/FrameServer.java framed
```

```text
[client] # java /src/FrameClient.java framed
```

클라이언트 예시 출력:

```text
[client] write(9 bytes) [len]msg-1
[client] write(9 bytes) [len]msg-2
[client] write(9 bytes) [len]msg-3
[client] write(9 bytes) [len]msg-4
[client] write(9 bytes) [len]msg-5
[client] write(7 bytes) [len]HELLO-WORLD 앞부분
[client] write(8 bytes) [len]HELLO-WORLD 뒷부분
[client] response: "ACK:msg-1"
[client] response: "ACK:msg-2"
[client] response: "ACK:msg-3"
[client] response: "ACK:msg-4"
[client] response: "ACK:msg-5"
[client] response: "ACK:HELLO-WORLD"
[client] done
```

서버 예시 출력 (`body 완성까지` 시간은 환경에 따라 다름):

```text
[server] accepted /172.20.0.3:41840
[server] header len=5 (0x00000005)
[server] message (5 bytes, body 완성까지 0ms): "msg-1"
[server] header len=5 (0x00000005)
[server] message (5 bytes, body 완성까지 0ms): "msg-2"
[server] header len=5 (0x00000005)
[server] message (5 bytes, body 완성까지 0ms): "msg-3"
[server] header len=5 (0x00000005)
[server] message (5 bytes, body 완성까지 0ms): "msg-4"
[server] header len=5 (0x00000005)
[server] message (5 bytes, body 완성까지 0ms): "msg-5"
[server] header len=11 (0x0000000B)
[server] message (11 bytes, body 완성까지 1001ms): "HELLO-WORLD"
[server] EOF (FIN 수신) — 정상 종료
[server] closed /172.20.0.3:41840
```

**무엇이 달라졌나**

- 네트워크에서 바이트가 어떻게 묶여 왔든 메시지 6개가 정확히 6개로 복원됐다. `BufferedInputStream`이 `msg-2`~`msg-5`를 한 번에 읽어 왔더라도 `readInt`/`readFully`가 그 안에서 경계를 잘라낸다.
- `HELLO-WORLD`는 `header len=11`을 읽은 뒤 `readFully`가 **약 1초 동안 나머지 바이트를 기다려** 완성했다. 4-1에서 두 조각으로 보였던 메시지가 하나가 됐다. 앞부분 7바이트 = 길이 4바이트 + `HEL` 3바이트라서 길이 헤더 자체는 이미 완성된 상태였다.
- 응답도 같은 규칙으로 읽으므로 클라이언트가 요청 6개에 대한 응답 6개를 순서대로 받았다. 이 프로토콜은 **거래 ID가 없고 순서로 대응**하므로, 이 커넥션을 여러 스레드가 공유하면 안 된다(이론 9-4).

### 5-2. 길이 상한으로 이상한 입력 거부

같은 서버(기본 `maxLen=1048576`)에 4-3의 HTTP 요청과 TLS 요청을 보낸다.

```text
[client] # curl -sS --max-time 5 http://server:9000/
[client] # curl -sS --max-time 5 -k https://server:9000/
```

서버 예시 출력:

```text
[server] accepted /172.20.0.3:41846
[server] header len=1195725856 (0x47455420)
[server] REJECT: len=1195725856 는 허용 범위(0..1048576)를 벗어남. 커넥션을 닫는다
[server] closed /172.20.0.3:41846
[server] accepted /172.20.0.3:41850
[server] header len=369295617 (0x16030101)
[server] REJECT: len=369295617 는 허용 범위(0..1048576)를 벗어남. 커넥션을 닫는다
[server] closed /172.20.0.3:41850
```

클라이언트 예시 출력 (TLS 쪽 메시지는 curl/OpenSSL 버전에 따라 다름):

```text
curl: (52) Empty reply from server
curl: (35) OpenSSL SSL_connect: Connection reset by peer in connection to server:9000
```

**무엇이 달라졌나**

- OOM 없이 즉시 거부했다. 로그의 16진수만 보고 침입자의 정체를 알 수 있다. `0x47455420` = `"GET "`, `0x1603xxxx` = TLS 레코드 헤더(Handshake, 버전 3.x). TLS 쪽 마지막 바이트는 ClientHello 길이에 따라 달라진다.
- 이것이 이론 9-3 "확인 방법"의 실제 모습이다. 운영 서버에서도 **길이 헤더 값을 16진수와 함께 로그로 남기면** 원인 파악이 몇 초로 줄어든다.
- 그래도 상대에게 이유를 알려줄 방법은 없다. 3-2의 HTTP 400과 비교해 보면 "표준 오류 표현"의 가치가 드러난다.

### 5-3. 와이어 위의 바이트 비교 — HTTP vs 자체 프로토콜

`[server]`에서 `framed` 서버가 떠 있는 상태로, 다른 서버 터미널을 하나 더 열어 HTTP 서버도 띄운다.

```text
$ docker compose exec server bash
[server] # jwebserver -b 0.0.0.0 -p 8000 -d /www
```

`[client-2]`에서 캡처를 시작한다. `-X`는 16진수 + ASCII, `-nn`은 이름 해석 생략이다.

```text
[client-2] # tcpdump -i eth0 -nn -X 'tcp port 8000 or tcp port 9000'
```

`[client]`에서 같은 내용(`msg-1`)을 두 방식으로 한 번씩 요청한다. HTTP 쪽 `hello.txt`의 내용이 `msg-1\n`이다.

```text
[client] # curl -s http://server:8000/hello.txt
[client] # java /src/FrameClient.java framed
```

`[client-2]` 예시 출력에서 데이터가 실린 세그먼트만 발췌 (포트, seq, 체크섬, IP 헤더 바이트는 환경마다 다름):

```text
# HTTP 요청 (curl → server:8000)
IP 172.20.0.3.52114 > 172.20.0.2.8000: Flags [P.], seq 1:82, ack 1, win 502, length 81: HTTP: GET /hello.txt HTTP/1.1
	0x0030:  .... 4745 5420 2f68 656c 6c6f 2e74 7874  ..GET./hello.txt
	0x0040:  2048 5454 502f 312e 310d 0a48 6f73 743a  .HTTP/1.1..Host:
	0x0050:  2073 6572 7665 723a 3830 3030 0d0a 5573  .server:8000..Us
	...

# HTTP 응답 (server:8000 → curl)
IP 172.20.0.2.8000 > 172.20.0.3.52114: Flags [P.], seq 1:..., length 1xx: HTTP: HTTP/1.1 200 OK

# 자체 프로토콜 요청 (FrameClient → server:9000)
IP 172.20.0.3.41860 > 172.20.0.2.9000: Flags [P.], seq 1:10, ack 1, win 502, length 9
	0x0030:  .... 0000 0005 6d73 672d 31              .....msg-1

# 자체 프로토콜 응답
IP 172.20.0.2.9000 > 172.20.0.3.41860: Flags [P.], seq 1:14, ack 10, win 509, length 13
	0x0030:  .... 0000 0009 4143 4b3a 6d73 672d 31    .....ACK:msg-1
```

**읽는 법**

| 항목 | HTTP | 자체 프로토콜 |
|---|---|---|
| 요청 페이로드 | 약 80바이트 (`GET` 줄 + `Host` + `User-Agent` + `Accept` + 빈 줄). 정확한 값은 curl 버전의 `User-Agent` 길이에 따라 다름 | 9바이트 (`00 00 00 05` + `msg-1`) |
| tcpdump의 해석 | 줄 끝에 `HTTP: GET /hello.txt HTTP/1.1`이라고 **프로토콜을 알아본다** | `length 9`뿐. 이 바이트가 무엇인지는 규격서를 가진 사람만 안다 |
| 메시지 경계 표시 | `0d0a 0d0a`(빈 줄), 응답은 `Content-length` | 앞 4바이트 `0000 0005` |

자체 프로토콜은 바이트가 적지만, 그 대가로 **경로 위의 어떤 도구도 이 바이트를 이해하지 못한다.** 같은 이유로 Nginx `http {}` 블록이나 ALB 뒤에 둘 수 없다(이론 9-6). 반대로 HTTP의 수십~수백 바이트 헤더는 대부분의 서비스에서 병목이 아니다.

캡처는 `Ctrl+C`로 멈춘다. 마지막 줄의 `N packets captured`에서 HTTP 쪽 패킷 수가 더 많다면 응답이 헤더·본문으로 나뉘어 나갔거나 커넥션 종료 세그먼트가 포함된 것이다.

## 6. 전체 구성 파일

디렉터리 구조:

```text
labs/05a-socket/
├── Dockerfile
├── docker-compose.yml
├── java/
│   ├── FrameServer.java
│   └── FrameClient.java
└── www/
    └── hello.txt
```

### `Dockerfile`

```dockerfile
# 05a 실습: JDK 21 + 관찰 도구
# - java: 단일 파일 소스 실행(java X.java)과 jwebserver(JDK 18+ 내장 간이 HTTP 서버)를 쓴다
# - tcpdump: 바이트가 실제로 어떻게 오가는지 확인 (3장과 같은 도구)
# - netcat-openbsd: 아무 바이트나 손으로 보내는 "프로토콜 없는" 클라이언트
# - iproute2: ss로 커넥션 상태 확인
# - curl: HTTP 클라이언트
# 태그는 버전을 고정한다. 이 태그가 레지스트리에 없으면 Docker Hub의 eclipse-temurin 태그 목록에서
# 21.x.x_x-jdk-jammy 형식의 다른 패치 버전으로 바꾼다 (확인 필요)
FROM eclipse-temurin:21.0.4_7-jdk-jammy
RUN apt-get update \
 && apt-get install -y --no-install-recommends tcpdump netcat-openbsd iproute2 curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
```

### `docker-compose.yml`

```yaml
# 5a장 실습: TCP 소켓 직접 통신 vs HTTP
#
#   server : FrameServer(:9000, 직접 만든 프로토콜) 와 jwebserver(:8000, HTTP)를 손으로 띄우는 컨테이너
#   client : FrameClient, nc, curl, tcpdump를 실행하는 컨테이너
#
# 두 컨테이너 모두 sleep 상태로 대기한다. 서버/클라이언트 프로그램은 docker compose exec로
# 직접 실행해야 출력을 터미널에서 바로 볼 수 있다.
name: ch05a-lab

services:
  server:
    build: .
    image: ch05a-lab:1
    hostname: server
    volumes:
      - ./java:/src:ro
      - ./www:/www:ro
    # NET_RAW: tcpdump
    cap_add: [NET_RAW]
    # init: sleep이 SIGTERM을 무시해서 docker compose down이 10초씩 기다리는 것을 막는다
    init: true
    command: ["sleep", "infinity"]

  client:
    build: .
    image: ch05a-lab:1
    hostname: client
    volumes:
      - ./java:/src:ro
    cap_add: [NET_RAW]
    init: true
    command: ["sleep", "infinity"]
```

호스트로 포트를 공개(`ports`)하지 않았다. 모든 통신은 compose 기본 네트워크 안에서만 일어나므로 호스트의 다른 프로세스와 포트가 충돌하지 않는다.

### `java/FrameServer.java`

```java
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 05a 실습용 TCP 소켓 서버.
 *
 * 실행: java -Xmx64m /src/FrameServer.java <mode> [port] [maxLen]
 *   mode   = naive  : read() 한 번 = 메시지 하나라고 착각하는 서버 (흔한 버그)
 *            framed : [4바이트 길이(big-endian)][본문] 규칙으로 메시지 경계를 복원하는 서버
 *   port   = 기본 9000
 *   maxLen = framed 모드의 최대 본문 길이. 기본 1MB. 0이면 검사하지 않음(일부러 위험하게)
 *
 * 문법은 Java 8 호환으로 썼다. 단, "java 파일명.java" 단일 파일 실행은 Java 11 이상에서만 된다.
 * Java 8에서는 javac FrameServer.java && java FrameServer naive 로 실행한다.
 */
public class FrameServer {

    public static void main(String[] args) throws IOException {
        final String mode = args.length > 0 ? args[0] : "framed";
        final int port = args.length > 1 ? Integer.parseInt(args[1]) : 9000;
        final int maxLen = args.length > 2 ? Integer.parseInt(args[2]) : 1024 * 1024;

        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.printf("[server] mode=%s port=%d maxLen=%d%n", mode, port, maxLen);
            while (true) {
                final Socket s = ss.accept();
                System.out.printf("[server] accepted %s%n", s.getRemoteSocketAddress());
                // 커넥션 하나당 스레드 하나 (Tomcat의 기본 모델과 같은 구조)
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            if ("naive".equals(mode)) {
                                naive(s);
                            } else {
                                framed(s, maxLen);
                            }
                        } catch (Exception e) {
                            System.out.printf("[server] %s: %s%n", e.getClass().getName(), e.getMessage());
                        } finally {
                            try { s.close(); } catch (IOException ignore) { }
                            System.out.printf("[server] closed %s%n", s.getRemoteSocketAddress());
                        }
                    }
                }).start();
            }
        }
    }

    /** read()가 돌려준 바이트 묶음을 그대로 "메시지 하나"로 취급한다. TCP에는 메시지 경계가 없으므로 틀린 방식이다. */
    static void naive(Socket s) throws IOException, InterruptedException {
        InputStream in = s.getInputStream();
        byte[] buf = new byte[1024];
        int n;
        int count = 0;
        while ((n = in.read(buf)) != -1) {
            count++;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            System.out.printf("[server] read() #%d -> %d bytes: \"%s\"%n", count, n, text);
            // 처리 시간을 흉내 낸다. 그 사이 도착한 데이터는 커널 수신 버퍼에 쌓이고,
            // 다음 read() 한 번에 여러 개가 한꺼번에 나온다.
            Thread.sleep(300);
        }
        System.out.println("[server] read() returned -1 (FIN 수신)");
    }

    /** 길이 헤더로 메시지 경계를 복원한다. readInt/readFully가 "모자라면 더 읽기"를 대신 해준다. */
    static void framed(Socket s, int maxLen) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        while (true) {
            int len;
            try {
                len = in.readInt(); // 정확히 4바이트를 모을 때까지 블록
            } catch (EOFException e) {
                System.out.println("[server] EOF (FIN 수신) — 정상 종료");
                return;
            }
            System.out.printf("[server] header len=%d (0x%08X)%n", len, len);

            if (len < 0 || (maxLen > 0 && len > maxLen)) {
                // 길이를 믿고 바로 new byte[len] 하면 안 된다. 상대가 프로토콜을 모르거나 악의적일 수 있다.
                System.out.printf("[server] REJECT: len=%d 는 허용 범위(0..%d)를 벗어남. 커넥션을 닫는다%n", len, maxLen);
                return;
            }

            long t0 = System.nanoTime();
            byte[] body = new byte[len];   // maxLen=0 이고 len이 크면 여기서 OutOfMemoryError
            in.readFully(body);            // 본문이 여러 세그먼트로 나뉘어 와도 len 바이트를 다 모은다
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;

            String msg = new String(body, StandardCharsets.UTF_8);
            System.out.printf("[server] message (%d bytes, body 완성까지 %dms): \"%s\"%n", len, waitedMs, msg);

            byte[] resp = ("ACK:" + msg).getBytes(StandardCharsets.UTF_8);
            out.writeInt(resp.length);
            out.write(resp);
            out.flush();
        }
    }
}
```

`OutOfMemoryError`는 `Exception`이 아니라 `Error`이므로 `catch (Exception e)`에 잡히지 않는다. `finally`에서 소켓을 닫은 뒤 스레드의 기본 예외 처리기가 스택 트레이스를 출력한다. 4-3 출력에서 `closed`가 OOM 메시지보다 먼저 찍힌 이유다.

### `java/FrameClient.java`

```java
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 05a 실습용 TCP 소켓 클라이언트.
 *
 * 실행: java /src/FrameClient.java <mode> [host] [port]
 *   mode = raw     : 길이 헤더 없이 메시지를 연달아 보낸다 (naive 서버용)
 *          framed  : [4바이트 길이][본문]으로 보내고 ACK를 받는다 (framed 서버용)
 *          charlen : 길이를 String.length()(문자 수)로 잘못 계산해 보낸다 (framed 서버용)
 *   host = 기본 server, port = 기본 9000
 *
 * 공통 시나리오
 *   1) 짧은 메시지 5개를 write() 5번으로 연달아 보낸다
 *   2) 메시지 하나를 write() 2번으로 나눠 1초 간격으로 보낸다
 */
public class FrameClient {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "framed";
        String host = args.length > 1 ? args[1] : "server";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 9000;

        try (Socket s = new Socket(host, port)) {
            // Nagle 끄기: write() 하나가 가능한 한 세그먼트 하나로 나가게 해서
            // "보낼 때는 분명히 5번 나눠 보냈다"는 조건을 만든다 (3장 9-6 참고)
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000); // 응답 대기 상한. 없으면 서버가 응답하지 않을 때 영원히 블록
            OutputStream out = s.getOutputStream(); // 버퍼 없이 바로 소켓에 쓴다

            int expectedAcks = 0;
            if ("raw".equals(mode)) {
                for (int i = 1; i <= 5; i++) {
                    send(out, ("msg-" + i).getBytes(StandardCharsets.UTF_8), "msg-" + i);
                }
                sendSplit(out, "HELLO-WORLD".getBytes(StandardCharsets.UTF_8), "HELLO-WORLD");
            } else if ("framed".equals(mode)) {
                for (int i = 1; i <= 5; i++) {
                    send(out, frame("msg-" + i), "[len]msg-" + i);
                }
                sendSplit(out, frame("HELLO-WORLD"), "[len]HELLO-WORLD");
                expectedAcks = 6;
            } else if ("charlen".equals(mode)) {
                String korean = "안녕하세요";
                byte[] body = korean.getBytes(StandardCharsets.UTF_8);
                // 버그: 길이 헤더에 문자 수(5)를 넣었다. 실제 UTF-8 바이트 수는 15
                ByteBuffer bb = ByteBuffer.allocate(4 + body.length);
                bb.putInt(korean.length()).put(body);
                send(out, bb.array(), "[len=" + korean.length() + "] " + korean + " (실제 " + body.length + " bytes)");
                send(out, frame("msg-2"), "[len]msg-2");
                expectedAcks = 2;
            } else {
                throw new IllegalArgumentException("mode must be raw | framed | charlen");
            }

            readAcks(s, expectedAcks);
        }
        System.out.println("[client] done");
    }

    /** [4바이트 길이(big-endian, UTF-8 바이트 수)][본문] */
    static byte[] frame(String msg) {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
    }

    static void send(OutputStream out, byte[] data, String label) throws IOException {
        out.write(data);
        out.flush();
        System.out.printf("[client] write(%d bytes) %s%n", data.length, label);
    }

    /** 메시지 하나를 두 번의 write()로 나눠 보낸다. 네트워크가 세그먼트를 나눈 상황을 흉내 낸다. */
    static void sendSplit(OutputStream out, byte[] data, String label) throws IOException, InterruptedException {
        int half = data.length / 2;
        out.write(data, 0, half);
        out.flush();
        System.out.printf("[client] write(%d bytes) %s 앞부분%n", half, label);
        Thread.sleep(1000);
        out.write(data, half, data.length - half);
        out.flush();
        System.out.printf("[client] write(%d bytes) %s 뒷부분%n", data.length - half, label);
    }

    static void readAcks(Socket s, int expected) throws IOException {
        if (expected == 0) {
            return;
        }
        DataInputStream in = new DataInputStream(s.getInputStream());
        for (int i = 0; i < expected; i++) {
            try {
                int len = in.readInt();
                byte[] body = new byte[len];
                in.readFully(body);
                System.out.printf("[client] response: \"%s\"%n", new String(body, StandardCharsets.UTF_8));
            } catch (EOFException e) {
                System.out.printf("[client] 응답 %d/%d 대기 중 서버가 커넥션을 닫음 (EOF)%n", i + 1, expected);
                return;
            } catch (SocketTimeoutException e) {
                System.out.printf("[client] 응답 %d/%d 대기 중 Read timed out%n", i + 1, expected);
                return;
            }
        }
    }
}
```

`readAcks`는 일부러 **응답 길이에 상한을 두지 않았다.** 9절 변형 실습 3에서 이 빈틈을 확인한다.

### `www/hello.txt`

```text
msg-1
```

(줄바꿈 포함 6바이트)

## 7. 실행 결과와 해석

모든 출력은 위 각 절에 "예시 출력"으로 실었다. 실제 실행 결과와 비교할 때 **값이 반드시 같아야 하는 것**과 **달라도 되는 것**을 구분한다.

| 항목 | 같아야 함 | 달라도 됨 |
|---|---|---|
| 4-1 naive | `read()` 횟수가 `write()` 횟수(7)보다 적다 | 정확한 묶음(어느 메시지끼리 붙었는지) |
| 4-2 charlen | `len=5`, `0x95ED9598`, 두 번째 응답 전 EOF | 대체 문자 `�`의 개수 |
| 4-3 OOM | `0x47455420`, `OutOfMemoryError`, curl `(52)` | 스택 트레이스 줄 번호, 스레드 이름 |
| 5-1 framed | 메시지 6개, 응답 6개, 마지막 본문 완성 대기 약 1000ms | 앞 5개의 대기 시간(0~수 ms) |
| 5-2 거부 | `0x47455420`, `0x1603`으로 시작하는 값 | TLS 길이의 마지막 바이트, curl 오류 문구 |
| 5-3 캡처 | 자체 프로토콜 요청 `length 9`, 앞 4바이트 `0000 0005` | IP, 포트, HTTP 요청 길이(curl 버전) |

같아야 하는 값이 다르면 `java/` 아래 파일을 수정했는지(`git diff`), 서버를 올바른 모드로 띄웠는지 확인한다.

## 8. 검증

이론의 핵심 주장을 체크리스트로 확인한다.

- [ ] **HTTP는 소켓 위의 텍스트다**: 3-1에서 `nc`로 손으로 쓴 바이트에 서버가 `HTTP/1.1 200 OK`로 응답했다.
- [ ] **HTTP는 메시지 경계를 표준으로 표시한다**: 3-1 응답의 `Content-length` 값 = 본문 바이트 수(6).
- [ ] **TCP는 `write()` 경계를 보존하지 않는다**: 4-1에서 서버 `read()` 횟수 < 클라이언트 `write()` 횟수 7.
- [ ] **길이 단위는 바이트여야 한다**: 4-2에서 영문 `msg-2`조차 처리되지 못하고 커넥션이 끊겼다.
- [ ] **길이 헤더는 검증해야 한다**: 4-3에서 `maxLen=0`일 때만 OOM, 5-2에서 기본값이면 즉시 REJECT.
- [ ] **프레이밍을 구현하면 네트워크 묶음과 무관해진다**: 5-1에서 응답 6개가 순서대로 모두 도착.
- [ ] **자체 프로토콜은 도구가 해석하지 못한다**: 5-3에서 tcpdump가 HTTP에는 `HTTP: GET ...`을 붙였고 9000 포트에는 `length 9`만 표시.

커넥션이 정상 정리됐는지도 확인한다. 클라이언트 프로그램이 끝난 직후:

```text
[client] # ss -tan '( dport = :9000 )'
```

예시 출력:

```text
State      Recv-Q Send-Q  Local Address:Port   Peer Address:Port
TIME-WAIT  0      0       172.20.0.3:41840     172.20.0.2:9000
```

클라이언트가 먼저 `close()`(try-with-resources)했으므로 **클라이언트 쪽에 TIME_WAIT**가 남는다([3장 4-3](./03-TCP-이론.md)). 약 60초 뒤 사라진다. 5-2 REJECT 경우에는 서버가 먼저 닫았으므로 TIME_WAIT가 서버 쪽에 생긴다. `[server] # ss -tan '( sport = :9000 )'`로 비교해 본다.

## 9. 변형 실습

**1. naive 서버의 `Thread.sleep(300)`을 지우고(파일을 호스트에서 편집 — 볼륨이 읽기 전용이므로 컨테이너 안에서는 편집 불가) 4-1을 여러 번 반복하면?**

<details><summary>예상 결과</summary>

`read()` 횟수가 7에 가까워지거나 7이 되는 경우가 많아진다. 서버가 항상 `read()`에서 기다리고 있어 세그먼트가 도착하는 즉시 가져가기 때문이다. 하지만 **여러 번 반복하면 가끔 붙는다.** 스레드 스케줄링과 출력(`printf`) 시간이 짧은 지연을 만든다. "sleep 없으면 괜찮다"가 아니라 "운이 좋으면 안 보인다"는 것을 확인하는 실습이다. 원복을 잊지 말 것.
</details>

**2. `FrameClient`의 `s.setTcpNoDelay(true)`를 `false`로 바꾸고 tcpdump를 켠 채 `framed` 모드를 실행하면?**

<details><summary>예상 결과</summary>

Nagle 알고리즘이 켜져서, 첫 세그먼트(`msg-1`)가 나간 뒤 ACK가 오기 전까지 `msg-2`~`msg-5`가 송신 버퍼에 모였다가 **한 세그먼트로 합쳐져** 나갈 수 있다. tcpdump에서 `length 36`(9×4) 같은 세그먼트가 보이면 확정이다. 그래도 서버의 `framed` 모드는 메시지 6개를 정확히 복원한다. **프레이밍이 있으면 세그먼트 묶음 방식이 정확성에 영향을 주지 않는다**는 확인이다. 다만 요청-응답형에서는 지연이 생길 수 있다([3장 9-6](./03-TCP-이론.md)).
</details>

**3. `framed` 클라이언트를 HTTP 서버(8000 포트)에 붙이면? (`[client] # java /src/FrameClient.java framed server 8000`)**

<details><summary>예상 결과</summary>

방향이 반대인 9-3 사고가 클라이언트에서 난다. `jwebserver`는 `00 00 00 05 msg-1...`을 잘못된 요청 줄로 보고 `HTTP/1.1 400 Bad Request ...`를 보낸 뒤 닫을 가능성이 높다. 클라이언트의 `readAcks`는 응답 첫 4바이트 `"HTTP"` = `0x48545450` = **1,213,486,160**을 길이로 믿고 `new byte[1213486160]`을 시도한다. 컨테이너 메모리와 JVM 기본 최대 힙(대개 메모리의 1/4)에 따라 ① 즉시 `OutOfMemoryError`, 또는 ② 할당은 성공하고 `readFully`가 나머지를 기다리다 서버가 닫아 `EOF`가 난다. `readAcks`에 길이 상한이 없기 때문이다. **길이 검증은 서버만의 일이 아니라 수신하는 모든 쪽의 일**이다. 확인 후 `readAcks`에 상한 검사를 추가해 보면 좋다.
</details>

## 10. 정리 (Clean-up)

실행 중인 서버 프로그램(`FrameServer`, `jwebserver`)과 `tcpdump`를 각 터미널에서 `Ctrl+C`로 끄고 `exit`로 컨테이너 셸을 빠져나온 뒤:

```text
$ cd "C:\Workspace\Study\Network\네트워크 기초\labs\05a-socket"
$ docker compose down
```

- `down`: 컨테이너 2개와 네트워크 `ch05a-lab_default`를 삭제한다. 이름 있는 볼륨을 만들지 않았으므로 `-v`는 필요 없다.
- 9절 변형 실습에서 `java/` 파일을 고쳤다면 원래대로 되돌린다. `git diff labs/05a-socket`으로 확인한다.

남은 것 확인:

```text
$ docker ps -a --filter "name=ch05a-lab"     # 아무것도 없어야 함
$ docker network ls --filter "name=ch05a-lab" # 아무것도 없어야 함
```

이미지 삭제(선택):

```text
$ docker image rm ch05a-lab:1 eclipse-temurin:21.0.4_7-jdk-jammy
```

호스트 설정과 포트는 건드리지 않았으므로 원복할 것은 없다.
