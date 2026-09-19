---
장: 3
주제: TCP와 UDP — 실습
분류: 네트워크 / 전송 계층
난이도: 중급
관련표준: RFC 9293, RFC 768
실습환경: Docker Desktop(WSL2 백엔드) 또는 Linux Docker, Linux 컨테이너(python 3.12-alpine3.20, nginx 1.27-alpine)
비용발생: 없음
작성일: 2026-09-19
선행지식: [TCP와 UDP 이론](./03-TCP-이론.md)
---

# 03. TCP와 UDP — 실습

> 관련 문서: [이론](./03-TCP-이론.md) · 실습 파일: [`labs/03-tcp/`](./labs/03-tcp/)

## 1. 실습 개요

컨테이너 몇 개로 클라이언트·서버를 띄우고, TCP 커넥션의 일생(수립 → 전송 → 종료 → TIME_WAIT)을 tcpdump와 `ss`로 관찰한다. 그다음 실무에서 자주 나는 장애 네 가지를 일부러 만들고 고친다.

| 단계 | 하는 일 | 확인하는 이론 |
|---|---|---|
| 3-1 | 3-way handshake와 종료를 패킷으로 보기 | 4-1, 4-3, 5절 헤더·옵션 |
| 3-2 | 먼저 닫은 쪽에 TIME_WAIT가 남는 것 확인 | 4-3 "먼저 닫은 쪽" 규칙 |
| 3-3 | 서버가 먼저 닫을 때: 클라이언트 CLOSE_WAIT, 서버 TIME_WAIT | 4-3, 6-3 keep-alive 타임아웃 |
| 3-4 | `Connection refused`와 `Connect timed out`을 패킷으로 구분 | 6-1, SYN 재전송 |
| 3-5 | UDP는 핸드셰이크가 없다 | 4-5 |
| 4-1 | 요청마다 새 커넥션 → 임시 포트 고갈 | 9-1 |
| 4-2 | `close()`하지 않는 서버 → CLOSE_WAIT 누적, fd 증가 | 9-2 |
| 4-3 | `accept()`하지 않는 서버 → accept 큐 넘침, SYN 재전송 | 9-5, 1장 9-3 |
| 5 | 커넥션 재사용, 정상 close, 정상 accept로 하나씩 고치기 | 6-4, 7절 |

```text
  ┌──────────────┐          ┌───────────────────────────┐
  │ client       │ ───────▶ │ app  :9000 (python 에코)   │  MODE=normal|leak|noaccept
  │ client-small │          └───────────────────────────┘
  │ (포트 50개)   │          ┌───────────────────────────┐
  │              │ ───────▶ │ web  :80 (nginx)          │  keepalive_timeout 5s
  └──────────────┘          │  └ web-tools (같은 netns)  │  서버 쪽 ss/tcpdump용
                            └───────────────────────────┘
          모두 Compose 기본 네트워크 (IP는 Docker가 할당, 이름으로 접근)
```

## 2. 환경과 준비물

> 💰 **비용 발생 없음.** 로컬 Docker만 사용한다.

| 항목 | 요구 사항 |
|---|---|
| Windows 11 | Docker Desktop (WSL2 백엔드) |
| macOS | Docker Desktop. 명령 동일 |
| Linux | Docker Engine + Compose 플러그인. 명령 동일 |
| 인터넷 | 첫 빌드 시 이미지·패키지 다운로드 |

커널 파라미터(`ip_local_port_range`, `tcp_tw_reuse`)는 **컨테이너의 네트워크 네임스페이스에만** 적용된다(compose의 `sysctls`). `iptables`도 `app` 컨테이너 안에서만 실행한다. 호스트 설정은 바꾸지 않는다.

Docker Desktop은 Linux VM 위에서 컨테이너를 돌리므로 여기서 보는 동작은 **Linux 커널의 TCP 구현**이다. Windows 호스트 자체의 TCP 동작(TIME_WAIT 기본 240초 등)과는 다르다.

**프롬프트 표기**

| 표기 | 실행 위치 |
|---|---|
| `$` | 호스트 터미널 (PowerShell 또는 WSL2 bash) |
| `[client] #` | `$ docker compose exec client sh` 로 들어간 컨테이너 root 셸 |
| `[web-tools] #` | nginx와 네트워크를 공유하는 컨테이너. 여기서 본 소켓 = nginx의 소켓 |

### 기동

```text
$ cd "C:\Study\Network\네트워크 기초\labs\03-tcp"
$ docker compose up -d --build
$ docker compose ps
$ docker compose logs app
```

예시 출력 (`docker compose logs app`):

```text
app-1  | [10:00:01] listen 0.0.0.0:9000 backlog=128 mode=normal
```

## 3. 관찰 먼저

### 3-1. 3-way handshake와 4-way 종료 보기

터미널 두 개를 쓴다.

```text
[client] # tcpdump -nni eth0 -S 'tcp port 80'          # 터미널 1: 캡처 (-S: 시퀀스 번호를 절댓값으로)
[client] # curl -s -o /dev/null -w '%{http_code} local_port=%{local_port}\n' http://web/   # 터미널 2
```

예시 출력 — 터미널 1 (IP, 포트, 시퀀스 번호, win 값은 환경마다 다름):

```text
10:01:00.000001 IP 172.23.0.3.45678 > 172.23.0.5.80: Flags [S], seq 3001234567, win 64240, options [mss 1460,sackOK,TS val 100 ecr 0,nop,wscale 7], length 0
10:01:00.000040 IP 172.23.0.5.80 > 172.23.0.3.45678: Flags [S.], seq 1102345678, ack 3001234568, win 65160, options [mss 1460,sackOK,TS val 200 ecr 100,nop,wscale 7], length 0
10:01:00.000055 IP 172.23.0.3.45678 > 172.23.0.5.80: Flags [.], ack 1102345679, win 502, options [nop,nop,TS val 100 ecr 200], length 0
10:01:00.000120 IP 172.23.0.3.45678 > 172.23.0.5.80: Flags [P.], seq 3001234568:3001234638, ack 1102345679, win 502, options [nop,nop,TS val 100 ecr 200], length 70: HTTP: GET / HTTP/1.1
10:01:00.000135 IP 172.23.0.5.80 > 172.23.0.3.45678: Flags [.], ack 3001234638, win 509, options [nop,nop,TS val 200 ecr 100], length 0
10:01:00.000300 IP 172.23.0.5.80 > 172.23.0.3.45678: Flags [P.], seq 1102345679:1102345829, ack 3001234638, win 509, options [nop,nop,TS val 200 ecr 100], length 150: HTTP: HTTP/1.1 204 No Content
10:01:00.000310 IP 172.23.0.3.45678 > 172.23.0.5.80: Flags [.], ack 1102345829, win 501, options [nop,nop,TS val 101 ecr 200], length 0
10:01:00.000500 IP 172.23.0.3.45678 > 172.23.0.5.80: Flags [F.], seq 3001234638, ack 1102345829, win 501, options [nop,nop,TS val 101 ecr 200], length 0
10:01:00.000560 IP 172.23.0.5.80 > 172.23.0.3.45678: Flags [F.], seq 1102345829, ack 3001234639, win 509, options [nop,nop,TS val 201 ecr 101], length 0
10:01:00.000570 IP 172.23.0.3.45678 > 172.23.0.5.80: Flags [.], ack 1102345830, win 501, options [nop,nop,TS val 101 ecr 201], length 0
```

tcpdump 플래그 표기: `S`=SYN, `.`=ACK, `P`=PSH, `F`=FIN, `R`=RST. `[S.]`는 SYN+ACK다.

| 줄 | 해석 | 이론 연결 |
|---|---|---|
| 1 | 클라이언트 SYN. `seq`는 무작위 ISN. 옵션에 `mss 1460`, `sackOK`, `TS`(타임스탬프), `wscale 7` | 5절 옵션 표 |
| 2 | 서버 SYN-ACK. 자기 ISN과 `ack = 클라이언트 ISN + 1` (SYN은 1바이트로 계산) | 4-1 단계 2 |
| 3 | 클라이언트 ACK. **여기서 curl의 `connect()`가 반환됐다.** `win 502`는 스케일 적용 전 값(502 × 2^7 = 64,256바이트) | 4-1 단계 3 |
| 4~7 | HTTP 요청·응답(L7). `seq a:b`는 바이트 구간, `length`는 페이로드 크기 | 4-2 |
| 8 | **클라이언트가 먼저 FIN** — curl이 끝나며 소켓을 닫았다. 클라이언트 = 능동 종료 측 | 4-3 |
| 9~10 | 서버 FIN(nginx가 EOF를 보고 바로 닫음), 클라이언트의 마지막 ACK | 4-3 |

서버가 FIN과 ACK를 한 세그먼트로 보냈기 때문에 4-way가 아니라 3개 세그먼트로 끝났다. 수동 종료 측 애플리케이션이 EOF를 보자마자 닫으면 흔히 이렇게 된다.

### 3-2. TIME_WAIT는 먼저 닫은 쪽에 남는다

3-1 직후(60초 이내)에 양쪽을 본다.

```text
[client] # ss -tano state time-wait
[web-tools] # ss -tano state time-wait
```

예시 출력 — client:

```text
Recv-Q Send-Q  Local Address:Port    Peer Address:Port  Process
0      0         172.23.0.3:45678      172.23.0.5:80     timer:(timewait,52sec,0)
```

예시 출력 — web-tools(nginx):

```text
Recv-Q Send-Q  Local Address:Port    Peer Address:Port  Process
```

- TIME_WAIT는 **클라이언트에만** 있다. 3-1의 8번 줄에서 클라이언트가 먼저 FIN을 보냈기 때문이다.
- `timer:(timewait,52sec,0)` — 남은 시간. 60초에서 줄어든다. `sysctl net.ipv4.tcp_fin_timeout`을 확인해보면 60이 나오지만 **이 값은 TIME_WAIT와 무관하다**(이론 5절).
- `state` 필터를 쓰면 State 열은 출력되지 않는다.

`Local Address:Port`의 45678이 클라이언트의 임시 포트다. 이 포트는 60초 동안 **같은 목적지(web:80)로는** 다시 쓰이지 않는다. 4-1에서 이 사실을 이용해 포트를 고갈시킨다.

### 3-3. 서버가 먼저 닫으면: 클라이언트 CLOSE_WAIT, 서버 TIME_WAIT

nginx의 `keepalive_timeout`은 5초로 설정되어 있다. 요청 하나를 보낸 뒤 소켓을 닫지 않고 15초 기다리는 클라이언트를 실행한다.

```text
[client] # python tcp_client.py idle-http web 15
```

실행 중에 다른 터미널에서 약 8초 시점(서버가 닫은 뒤, 클라이언트가 닫기 전)에 양쪽을 본다.

```text
[client] # ss -tan 'dport = :80'
[web-tools] # ss -tan 'sport = :80'
```

예시 출력 — tcp_client.py:

```text
[10:05:00] 연결 local_port=46010
[10:05:00] 응답: b'HTTP/1.1 204 No Content'
[10:05:00] 소켓을 닫지 않고 15초 대기. 서버 keepalive_timeout이 지나면 서버가 먼저 FIN을 보냅니다.
[10:05:05] recv()가 b'' 반환 = 서버의 FIN 수신. 이 소켓은 지금 CLOSE_WAIT (아직 close() 안 함)
[10:05:15] 이제 close() → 클라이언트 FIN 전송
```

예시 출력 — 8초 시점:

```text
[client]    State       Recv-Q Send-Q  Local Address:Port   Peer Address:Port
            CLOSE-WAIT  0      0         172.23.0.3:46010     172.23.0.5:80

[web-tools] State       Recv-Q Send-Q  Local Address:Port   Peer Address:Port
            LISTEN      0      511          0.0.0.0:80           0.0.0.0:*
            FIN-WAIT-2  0      0         172.23.0.5:80        172.23.0.3:46010
```

- **서버(nginx)가 능동 종료 측**이 됐다. keep-alive 유휴 시간이 지나면 서버가 먼저 닫는다.
- 클라이언트는 FIN을 받았지만 애플리케이션이 아직 `close()`하지 않았으므로 **CLOSE_WAIT**. 이 상태가 오래 쌓이는 것이 4-2에서 재현할 CLOSE_WAIT 누수다. 여기서는 15초 뒤 닫으니 정상이다. `Recv-Q`가 0인 것은 스크립트가 EOF(`recv()`의 `b''`)를 이미 읽었기 때문이다. EOF조차 읽지 않고 방치된 CLOSE_WAIT는 FIN이 차지하는 1 때문에 `Recv-Q 1`로 보이는 경우가 많다.
- nginx의 `LISTEN` 줄 `Send-Q 511`은 nginx의 `listen` backlog 기본값 511이다(accept 큐 최대치).

15초가 지나 클라이언트가 닫은 뒤:

```text
[web-tools] # ss -tano state time-wait
0      0      172.23.0.5:80      172.23.0.3:46010    timer:(timewait,58sec,0)
```

TIME_WAIT가 **서버 쪽**에 생겼다. 3-2와 반대다. 규칙은 하나다. **먼저 닫은 쪽에 TIME_WAIT가 생긴다.** 서버의 TIME_WAIT는 모두 같은 로컬 포트 80을 공유하므로 임시 포트를 소비하지 않는다(이론 Q3).

> 실무 연결: 여기서 클라이언트가 **5초 시점에 요청을 보냈다면** 서버가 막 닫은 커넥션에 요청을 보내는 경쟁 상태가 된다. 이론 9-3의 `NoHttpResponseException`, 간헐적 502가 이것이다. 그래서 클라이언트의 idle 유지 시간은 서버 keep-alive보다 짧아야 한다.

### 3-4. `Connection refused` vs `Connect timed out`

app 컨테이너의 9001 포트에는 아무것도 리슨하지 않는다.

```text
[client] # tcpdump -nni eth0 'tcp port 9001'                                  # 터미널 1
[client] # curl -sS --connect-timeout 5 http://app:9001/                      # 터미널 2
```

예시 출력:

```text
curl: (7) Failed to connect to app port 9001 after 1 ms: Couldn't connect to server

10:10:00.000001 IP 172.23.0.3.47001 > 172.23.0.4.9001: Flags [S], seq 1234, win 64240, options [...], length 0
10:10:00.000030 IP 172.23.0.4.9001 > 172.23.0.3.47001: Flags [R.], seq 0, ack 1235, win 0, length 0
```

SYN에 즉시 RST(`[R.]`)가 왔다. **app 호스트까지는 도달했고, 그 포트에 리슨 소켓이 없다.** Java였다면 `ConnectException: Connection refused`. curl 메시지 문구는 버전에 따라 다르다(`curl -v`로 보면 `Connection refused`가 표시된다).

이제 app에서 9001로 오는 SYN을 조용히 버리게 한다(방화벽 DROP 흉내).

```text
$ docker compose exec app iptables -A INPUT -p tcp --dport 9001 -j DROP
[client] # curl -sS --connect-timeout 5 http://app:9001/
```

예시 출력:

```text
curl: (28) Failed to connect to app port 9001 after 5002 ms: Timeout was reached

10:11:00.000001 IP 172.23.0.3.47010 > 172.23.0.4.9001: Flags [S], seq 5678, ...
10:11:01.020000 IP 172.23.0.3.47010 > 172.23.0.4.9001: Flags [S], seq 5678, ...
10:11:03.100000 IP 172.23.0.3.47010 > 172.23.0.4.9001: Flags [S], seq 5678, ...
```

- 같은 `seq`의 SYN이 **약 1초, 3초** 시점에 재전송됐다. 초기 RTO 1초, 그다음 2초(지수 백오프). 5초 타임아웃 전에 한 번 더 보냈다면 7초 시점이다.
- 응답이 없으므로 원인은 경로 어딘가에서 버려진 것이다. Java였다면 `SocketTimeoutException: Connect timed out`.
- 이론 9-5의 "p99가 1초, 3초에 몰린다"는 이 재전송 간격이 응답 시간에 더해진 결과다.

원복:

```text
$ docker compose exec app iptables -D INPUT -p tcp --dport 9001 -j DROP
```

> `iptables` 명령이 `Could not fetch rule set generation id` 같은 에러를 내면 커널의 nf_tables 지원 문제다. `iptables-legacy`로 바꿔 실행해본다(확인 필요). 컨테이너 안의 규칙이므로 호스트에는 영향이 없다.

### 3-5. UDP: 핸드셰이크가 없다

app 컨테이너에 UDP 리스너를 하나 띄우고 데이터그램 하나를 보낸다.

```text
$ docker compose exec -d app python -c "import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('0.0.0.0',9999));d,a=s.recvfrom(100);s.sendto(d.upper(),a)"
[client] # tcpdump -nni eth0 'udp port 9999 or icmp'                         # 터미널 1
[client] # python -c "import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.settimeout(2);s.sendto(b'hello',('app',9999));print(s.recvfrom(100))"   # 터미널 2
```

예시 출력:

```text
(b'HELLO', ('172.23.0.4', 9999))

10:15:00.000001 IP 172.23.0.3.51234 > 172.23.0.4.9999: UDP, length 5
10:15:00.000050 IP 172.23.0.4.9999 > 172.23.0.3.51234: UDP, length 5
```

SYN도 FIN도 없이 **데이터 패킷 두 개가 전부**다. 리스너는 한 번 응답하고 종료했으므로, 같은 명령을 한 번 더 보내면:

```text
10:15:10.000001 IP 172.23.0.3.51240 > 172.23.0.4.9999: UDP, length 5
10:15:10.000030 IP 172.23.0.4 > 172.23.0.3: ICMP 172.23.0.4 udp port 9999 unreachable, length 41
```

UDP 포트가 닫혀 있으면 RST가 아니라 **ICMP Port Unreachable**이 온다(이론 4-5 flowchart). 클라이언트 파이썬은 `ConnectionRefusedError` 또는 타임아웃으로 끝난다. 결과는 소켓 사용 방식에 따라 다르다(`connect()`한 UDP 소켓만 ICMP 에러를 전달받는다. 확인 필요).

## 4. 문제 상황 재현

### 4-1. 요청마다 새 커넥션 → 임시 포트 고갈

`client-small`은 임시 포트가 **50개**(40000~40049)뿐인 클라이언트다. 운영 서버의 28,232개를 축소한 모형이다. 요청마다 curl 프로세스를 새로 띄우는 것은 "요청마다 `new RestTemplate()`"과 같다.

```text
[client-small] # sysctl net.ipv4.ip_local_port_range net.ipv4.tcp_tw_reuse
net.ipv4.ip_local_port_range = 40000	40049
net.ipv4.tcp_tw_reuse = 2

[client-small] # for i in $(seq 1 60); do curl -sS -o /dev/null -w "$i %{http_code} local_port=%{local_port}\n" http://web/ ; done
```

예시 출력:

```text
1 204 local_port=40012
2 204 local_port=40036
...
50 204 local_port=40021
51 000 local_port=0
curl: (7) Failed to connect to web port 80 after 0 ms: Couldn't connect to server
52 000 local_port=0
curl: (7) Failed to connect to web port 80 after 0 ms: Couldn't connect to server
...
```

실패 원인을 확정한다.

```text
[client-small] # ss -tan state time-wait | wc -l
51
[client-small] # curl -v http://web/ 2>&1 | grep -i -E 'trying|connect'
*   Trying 172.23.0.5:80...
* Immediate connect fail for 172.23.0.5: Cannot assign requested address
```

- TIME_WAIT가 50개(헤더 1줄 포함 51) = 포트 전부.
- `Cannot assign requested address` = 커널의 `EADDRNOTAVAIL`. Java였다면 `NoRouteToHostException: Cannot assign requested address` 또는 `BindException: Cannot assign requested address`(이론 9-1).
- 포트 순서가 뒤섞여 보이는 것은 커널이 임시 포트를 무작위로 고르기 때문이다(RFC 6056).
- 51번째 요청은 web 서버에 **도달하지도 않았다**. 서버 로그와 서버 지표는 깨끗하다. 이 장애가 "서버는 멀쩡한데 호출이 실패한다"로 보이는 이유다.

60초 기다리면 TIME_WAIT가 풀리면서 다시 성공한다. `ss -tano state time-wait`의 타이머로 확인해본다.

**계산해보기**: 운영 기본값 28,232개 ÷ 60초 ≈ 초당 470개. 같은 (목적지 IP, 목적지 포트)로 초당 470건 넘게 **새** 커넥션을 만들면 같은 일이 생긴다.

### 4-2. `close()`하지 않는 서버 → CLOSE_WAIT 누적

app을 leak 모드로 재생성한다.

```text
$ $env:MODE="leak"; docker compose up -d app          # PowerShell
$ MODE=leak docker compose up -d app                  # bash
$ docker compose logs app --tail 1
app-1  | [10:20:00] listen 0.0.0.0:9000 backlog=128 mode=leak
```

클라이언트가 20번 연결하고, 매번 먼저 닫는다.

```text
[client] # python tcp_client.py close app 9000 20
```

예시 출력:

```text
[10:20:10] #1 local_port=48122 recv=b'hi\n' -> close()
[10:20:10] #2 local_port=39410 recv=b'hi\n' -> close()
...
[10:20:10] #20 local_port=51877 recv=b'hi\n' -> close()
```

클라이언트 쪽에서는 모든 요청이 성공했다. 이제 서버를 본다.

```text
$ docker compose exec app sh
[app] # ss -tanp state close-wait | head -5
[app] # ss -tan state close-wait | wc -l
[app] # ls /proc/$(pgrep -f tcp_server.py)/fd | wc -l
```

예시 출력:

```text
Recv-Q Send-Q  Local Address:Port   Peer Address:Port  Process
0      0         172.23.0.4:9000      172.23.0.3:48122  users:(("python",pid=7,fd=4))
0      0         172.23.0.4:9000      172.23.0.3:39410  users:(("python",pid=7,fd=5))
0      0         172.23.0.4:9000      172.23.0.3:51877  users:(("python",pid=7,fd=23))
...
21
27
```

- CLOSE_WAIT 20개(헤더 포함 21). 각각 `users:(("python",pid=7,fd=N))` — **fd를 프로세스가 쥐고 있다.** TIME_WAIT(fd 없음)와의 결정적 차이다.
- fd 수(27 = 표준 입출력·리슨 소켓 등 + 20)는 요청 수만큼 늘었다. 이 숫자가 `ulimit -n`에 닿으면 `Too many open files`다.
- **Peer Address가 한 곳(클라이언트)에 몰려 있다.** 실무에서는 이 주소를 보고 어느 연동 코드가 새는지 찾는다.
- 서버 로그에는 `EOF 수신, close() 하지 않음`이 찍혀 있다. 실제 장애에서는 이런 로그가 없다. 예외 경로에서 `close()`가 빠진 코드는 아무 말 없이 샌다.

클라이언트 쪽:

```text
[client] # ss -tano state fin-wait-2
0   0   172.23.0.3:48122   172.23.0.4:9000   timer:(timewait,41sec,0)
...
```

클라이언트 소켓은 서버의 FIN을 기다리는 FIN_WAIT_2다. 애플리케이션이 이미 닫았으므로 고아 소켓이고, `tcp_fin_timeout`(60초) 후 커널이 정리한다(`ss`의 타이머 이름이 `timewait`로 표시되는 것은 커널 구현상 같은 타이머를 쓰기 때문이다). **60초 뒤에도 서버의 CLOSE_WAIT는 그대로 남아 있다.** 확인해본다.

```text
[app] # ss -tan state close-wait | wc -l      # 1분 뒤에도 21
```

커널은 CLOSE_WAIT를 시간 초과로 정리하지 않는다. 애플리케이션의 `close()`를 기다린다.

### 4-3. `accept()`하지 않는 서버 → accept 큐 넘침

app을 accept를 하지 않는 모드, backlog 2로 재생성한다. Tomcat 스레드가 모두 막혀 `max-connections`에 도달한 상황을 극단적으로 만든 것이다.

```text
$ $env:MODE="noaccept"; $env:BACKLOG="2"; docker compose up -d app      # PowerShell
$ MODE=noaccept BACKLOG=2 docker compose up -d app                      # bash
```

연결 6개를 0.2초 간격으로 하나씩 시도한다.

```text
[client] # python tcp_client.py hold app 9000 6
```

예시 출력:

```text
#01 connect() 성공     1 ms  local_port=41220
#02 connect() 성공     0 ms  local_port=55301
#03 connect() 성공     0 ms  local_port=36612
#04 connect() 실패  3003 ms  timed out
#05 connect() 실패  3002 ms  timed out
#06 connect() 실패  3003 ms  timed out
[10:30:20] 성공 3개 / 전체 6개. 성공한 연결을 60초간 유지합니다. 다른 터미널에서 ss로 관찰하세요.
```

60초가 지나기 전에 서버를 본다.

```text
[app] # ss -ltn 'sport = :9000'
[app] # ss -tan 'sport = :9000'
[app] # nstat -az TcpExtListenOverflows TcpExtListenDrops
```

예시 출력:

```text
State   Recv-Q Send-Q  Local Address:Port  Peer Address:Port
LISTEN  3      2             0.0.0.0:9000       0.0.0.0:*

State   Recv-Q Send-Q  Local Address:Port  Peer Address:Port
LISTEN  3      2             0.0.0.0:9000       0.0.0.0:*
ESTAB   0      0          172.23.0.4:9000    172.23.0.3:41220
ESTAB   0      0          172.23.0.4:9000    172.23.0.3:55301
ESTAB   0      0          172.23.0.4:9000    172.23.0.3:36612

#kernel
TcpExtListenOverflows           9                  0.0
TcpExtListenDrops               9                  0.0
```

| 관찰 | 해석 |
|---|---|
| `LISTEN Recv-Q 3 Send-Q 2` | accept 큐에 3개 대기, 최대치 2. Linux는 "현재 > 최대"일 때 가득 찬 것으로 보므로 backlog+1개까지 들어간다 |
| ESTAB 3개 | **애플리케이션이 `accept()`를 한 번도 호출하지 않았는데** 커널이 핸드셰이크를 완료했다 |
| #04~06 `timed out` | 큐가 찬 뒤 온 SYN을 커널이 버렸다. 클라이언트는 SYN을 재전송하다 3초 타임아웃 |
| `ListenOverflows` 9 | 버려진 SYN 수(3개 연결 × 원본+재전송 약 3회). 값은 타이밍에 따라 다름 |

**연결 성공 3개가 1장 9-3의 핵심이다.** L4 헬스체크(TCP 포트 체크)는 이 서버를 "정상"으로 판정한다. 앱은 요청을 하나도 처리하지 않는데도 그렇다. `hold`가 연결을 유지하는 동안 클라이언트에서 확인해본다.

```text
[client] # python -c "import socket;s=socket.create_connection(('app',9000),timeout=2);print('connected');s.sendall(b'hi');print(s.recv(10))"
```

큐에 자리가 없으면 `timed out`, 자리가 있으면 `connected` 출력 후 `recv`에서 `timed out`이 난다. 연결은 됐는데 응답이 없다. Java였다면 `SocketTimeoutException: Read timed out`이다.

## 5. 개선된 구성

### 5-1. 커넥션 재사용으로 포트 고갈 해결

4-1과 똑같이 60번 요청하되, **하나의 curl 프로세스(= 하나의 클라이언트 인스턴스)**로 보낸다. curl의 URL 글로빙 `[1-60]`은 URL 60개를 만들고, curl은 keep-alive 커넥션을 재사용한다. 먼저 4-1의 TIME_WAIT가 풀리도록 60초 기다린다.

```text
[client-small] # ss -tan state time-wait | wc -l          # 1 (헤더만) 이 될 때까지 대기
[client-small] # curl -sS -w '%{http_code} local_port=%{local_port} new_connects=%{num_connects}\n' "http://web/?n=[1-60]"
```

예시 출력:

```text
204 local_port=40031 new_connects=1
204 local_port=40031 new_connects=0
204 local_port=40031 new_connects=0
...
204 local_port=40031 new_connects=0
[client-small] # ss -tan state time-wait | wc -l
2
```

- 60건 모두 **같은 로컬 포트**, 새 연결은 첫 요청 1번뿐(`new_connects=0`은 재사용).
- TIME_WAIT는 1개(헤더 포함 2). 포트 50개로도 여유 있다.
- 요청 60건이 연속으로 이어져 nginx `keepalive_timeout 5s` 안에 끝났기 때문에 커넥션이 유지됐다. 요청 간격이 5초를 넘으면 nginx가 먼저 닫는다(3-3).

Spring 앱에서 이 "하나의 curl 프로세스"에 해당하는 것이 **빈으로 한 번 생성한 HTTP 클라이언트와 그 커넥션 풀**이다(이론 6-4).

### 5-2. 정상 close로 CLOSE_WAIT 해결

```text
$ $env:MODE="normal"; $env:BACKLOG="128"; docker compose up -d app      # PowerShell
$ docker compose up -d app                                              # bash (기본값 normal, 128)
[client] # python tcp_client.py close app 9000 20
[app] # ss -tan state close-wait | wc -l
1
[app] # ls /proc/$(pgrep -f tcp_server.py)/fd | wc -l
7
```

- CLOSE_WAIT 0개, fd 수가 일정하다. 서버 로그에는 `EOF 수신, close() 완료`가 찍힌다.
- 이번에도 클라이언트가 먼저 닫았으므로 **TIME_WAIT는 클라이언트에** 20개 생긴다. `[client] # ss -tan state time-wait | wc -l`로 확인한다.
- 앱 재생성으로 4-2의 CLOSE_WAIT도 사라졌다. 프로세스가 죽으면 커널이 fd를 모두 닫기 때문이다. "재시작하면 해결되고 며칠 뒤 재발"(이론 9-2)의 이유다.

### 5-3. 정상 accept로 accept 큐 해결

5-2 상태(normal, backlog 128)에서 다시 시도한다.

```text
[client] # python tcp_client.py hold app 9000 6
[app] # ss -ltn 'sport = :9000'
[app] # nstat -az TcpExtListenOverflows
```

예시 출력:

```text
#01 connect() 성공     1 ms  local_port=...
...
#06 connect() 성공     0 ms  local_port=...

LISTEN  0      128     0.0.0.0:9000     0.0.0.0:*
TcpExtListenOverflows           0                  0.0
```

- 6개 모두 성공, accept 큐 `Recv-Q 0`. 애플리케이션이 즉시 꺼내 가므로 큐가 쌓이지 않는다.
- `nstat -az`의 값은 **컨테이너 재생성 후 새 네트워크 네임스페이스 기준**이다. 4-3의 카운터는 이전 컨테이너와 함께 사라졌다.
- `Send-Q 128` = backlog 128. 이 값은 `min(128, somaxconn)`이다. `[app] # sysctl net.core.somaxconn`으로 확인한다.

## 6. 전체 구성 파일

```text
labs/03-tcp/
├── Dockerfile
├── docker-compose.yml
├── nginx/
│   └── default.conf
└── scripts/
    ├── tcp_server.py
    └── tcp_client.py
```

### `Dockerfile`

```dockerfile
# 3장 실습 공용 도구 이미지
# python: 실습용 TCP 서버/클라이언트 스크립트 실행
# iproute2: ss, nstat / tcpdump: 패킷 관찰 / curl: HTTP 클라이언트 / iptables: SYN DROP 재현
FROM python:3.12-alpine3.20
RUN apk add --no-cache iproute2 tcpdump curl iptables
WORKDIR /lab
```

### `docker-compose.yml`

```yaml
# 3장 실습: 3-way handshake, TIME_WAIT, CLOSE_WAIT, accept 큐, 커넥션 재사용
#
#   client        : 관찰용 클라이언트 (기본 커널 설정)
#   client-small  : 임시 포트를 50개로 줄인 클라이언트 (포트 고갈 재현)
#   app           : 파이썬 TCP 에코 서버 :9000 (MODE로 정상/누수/accept 안 함 전환)
#   web           : nginx :80 (HTTP keep-alive 관찰)
#   web-tools     : web과 네트워크 네임스페이스를 공유하는 도구 컨테이너 (서버 쪽 ss/tcpdump용)
name: ch03-lab

x-tools: &tools
  build: .
  image: ch03-tools:1
  # NET_ADMIN: iptables / NET_RAW: tcpdump
  cap_add: [NET_ADMIN, NET_RAW]
  init: true
  # 스크립트는 이미지에 굽지 않고 마운트: 수정 후 컨테이너 재생성 없이 바로 반영
  volumes:
    - ./scripts:/lab:ro

services:
  client:
    <<: *tools
    hostname: client
    command: ["sleep", "infinity"]

  client-small:
    <<: *tools
    hostname: client-small
    command: ["sleep", "infinity"]
    sysctls:
      # 기본 "32768 60999"(28,232개) → 50개. 시작/끝 홀짝을 다르게 두라는 커널 권고에 맞춤
      net.ipv4.ip_local_port_range: "40000 40049"
      # 기본 2(루프백만 재사용). 변형 실습 V1에서 "1"로 바꿔본다
      net.ipv4.tcp_tw_reuse: "2"

  app:
    <<: *tools
    hostname: app
    environment:
      # normal   : 상대가 닫으면(EOF) 나도 close()
      # leak     : EOF를 받아도 close() 안 함 → CLOSE_WAIT 누적
      # noaccept : listen()만 하고 accept() 안 함 → accept 큐 넘침
      MODE: ${MODE:-normal}
      # listen(backlog). 실제 accept 큐 상한 = min(BACKLOG, net.core.somaxconn)
      BACKLOG: ${BACKLOG:-128}
    command: ["python", "-u", "tcp_server.py"]

  web:
    image: nginx:1.27-alpine
    hostname: web
    volumes:
      - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro

  web-tools:
    <<: *tools
    # web 컨테이너의 네트워크 네임스페이스를 그대로 사용 → 여기서 본 ss는 nginx의 소켓이다
    # web을 재생성하면 이 컨테이너도 재생성해야 한다
    network_mode: "service:web"
    command: ["sleep", "infinity"]
```

### `nginx/default.conf`

```nginx
server {
    listen 80;

    # HTTP keep-alive 유휴 커넥션 유지 시간. 기본 75s.
    # 실습에서 "서버가 먼저 닫는" 상황을 몇 초 안에 보려고 5s로 줄였다.
    keepalive_timeout 5s;

    # 커넥션 하나로 처리할 최대 요청 수. 기본 1000 (nginx 1.19.10+). 명시만 해둠.
    keepalive_requests 1000;

    # 바디 없는 204 응답: curl 출력이 -w 형식 문자열만 남도록
    location / {
        return 204;
    }
}
```

### `scripts/tcp_server.py`

```python
"""3장 실습용 TCP 에코 서버 (Python 3.8+ 표준 라이브러리만 사용)

환경 변수
  MODE     normal   : 상대가 FIN을 보내면(recv()가 b'') 나도 close() → 정상 종료
           leak     : EOF를 받아도 close()하지 않음 → 서버 소켓이 CLOSE_WAIT에 남음
           noaccept : listen()만 하고 accept()를 호출하지 않음 → accept 큐가 참
  PORT     리슨 포트 (기본 9000)
  BACKLOG  listen() backlog (기본 128)
"""
import os
import socket
import threading
import time

MODE = os.environ.get("MODE", "normal")
PORT = int(os.environ.get("PORT", "9000"))
BACKLOG = int(os.environ.get("BACKLOG", "128"))

leaked = []  # leak 모드에서 닫지 않은 소켓을 붙잡아 둔다 (GC가 닫지 못하게)


def log(msg):
    print("[{}] {}".format(time.strftime("%H:%M:%S"), msg), flush=True)


def handle(conn, addr):
    peer = "{}:{}".format(*addr)
    while True:
        data = conn.recv(4096)
        if not data:
            # recv()가 빈 바이트 = 상대의 FIN 수신(EOF). 커널 소켓은 지금 CLOSE_WAIT.
            break
        conn.sendall(data)

    if MODE == "leak":
        leaked.append(conn)
        log("{} EOF 수신, close() 하지 않음 → CLOSE_WAIT 유지 (누적 {}개)".format(peer, len(leaked)))
    else:
        conn.close()
        log("{} EOF 수신, close() 완료".format(peer))


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    # 재시작 시 TIME_WAIT가 남아 있어도 bind 가능하게 (서버 소켓의 일반적인 설정)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(BACKLOG)
    log("listen 0.0.0.0:{} backlog={} mode={}".format(PORT, BACKLOG, MODE))

    if MODE == "noaccept":
        log("accept()를 호출하지 않습니다. 핸드셰이크는 커널이 처리하고 accept 큐에 쌓입니다.")
        while True:
            time.sleep(3600)

    while True:
        conn, addr = srv.accept()
        log("accept {}:{}".format(*addr))
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
```

### `scripts/tcp_client.py`

```python
"""3장 실습용 TCP 클라이언트 (Python 3.8+ 표준 라이브러리만 사용)

사용법
  python tcp_client.py close HOST PORT COUNT
      COUNT번 반복: 연결 → 'hi' 송신 → 응답 수신 → 클라이언트가 먼저 close()
  python tcp_client.py hold HOST PORT COUNT
      COUNT개 연결을 0.2초 간격으로 하나씩 시도(connect 타임아웃 3초), 결과 출력 후 60초 유지
  python tcp_client.py burst HOST PORT COUNT
      COUNT개 연결을 동시에 시도, 결과 출력 후 60초 유지
  python tcp_client.py idle-http HOST SECONDS
      HTTP 요청 1회 후 SECONDS초 동안 소켓을 닫지 않고 기다림 (서버가 먼저 닫는 상황 관찰)
"""
import socket
import sys
import threading
import time


def ts():
    return time.strftime("%H:%M:%S")


def do_close(host, port, count):
    for i in range(count):
        s = socket.create_connection((host, port), timeout=3)
        local_port = s.getsockname()[1]
        s.sendall(b"hi\n")
        try:
            data = s.recv(1024)
        except socket.timeout:
            data = b"(no reply)"
        s.close()  # 클라이언트가 먼저 FIN → 이 쪽이 능동 종료 측
        print("[{}] #{} local_port={} recv={!r} -> close()".format(ts(), i + 1, local_port, data))


def attempt(host, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(3)
    start = time.monotonic()
    try:
        s.connect((host, port))
        ms = (time.monotonic() - start) * 1000
        return s, "connect() 성공 {:5.0f} ms  local_port={}".format(ms, s.getsockname()[1])
    except OSError as e:
        ms = (time.monotonic() - start) * 1000
        s.close()
        return None, "connect() 실패 {:5.0f} ms  {}".format(ms, e)


def report_and_hold(results):
    socks = [s for s, _ in results if s is not None]
    for i, (_, msg) in enumerate(results):
        print("#{:02d} {}".format(i + 1, msg))
    print("[{}] 성공 {}개 / 전체 {}개. 성공한 연결을 60초간 유지합니다. 다른 터미널에서 ss로 관찰하세요.".format(
        ts(), len(socks), len(results)))
    time.sleep(60)
    for s in socks:
        s.close()


def do_hold(host, port, count):
    results = []
    for _ in range(count):
        results.append(attempt(host, port))
        time.sleep(0.2)  # 앞 연결의 핸드셰이크가 끝난 뒤 다음 SYN을 보내도록
    report_and_hold(results)


def do_burst(host, port, count):
    results = [None] * count

    def run(i):
        results[i] = attempt(host, port)

    threads = [threading.Thread(target=run, args=(i,)) for i in range(count)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    report_and_hold(results)


def do_idle_http(host, seconds):
    s = socket.create_connection((host, 80), timeout=seconds + 5)
    print("[{}] 연결 local_port={}".format(ts(), s.getsockname()[1]))
    s.sendall("GET / HTTP/1.1\r\nHost: {}\r\n\r\n".format(host).encode())
    print("[{}] 응답: {!r}".format(ts(), s.recv(1024).split(b"\r\n")[0]))
    print("[{}] 소켓을 닫지 않고 {}초 대기. 서버 keepalive_timeout이 지나면 서버가 먼저 FIN을 보냅니다.".format(
        ts(), seconds))
    deadline = time.monotonic() + seconds
    eof_seen = False
    while time.monotonic() < deadline:
        s.settimeout(max(0.1, deadline - time.monotonic()))
        try:
            data = s.recv(1024)
            if data == b"" and not eof_seen:
                eof_seen = True
                print("[{}] recv()가 b'' 반환 = 서버의 FIN 수신. 이 소켓은 지금 CLOSE_WAIT (아직 close() 안 함)".format(ts()))
                time.sleep(max(0.0, deadline - time.monotonic()))
        except socket.timeout:
            pass
    s.close()
    print("[{}] 이제 close() → 클라이언트 FIN 전송".format(ts()))


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    mode = sys.argv[1]
    if mode == "idle-http":
        do_idle_http(sys.argv[2], int(sys.argv[3]))
        return
    host, port, count = sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    if mode == "close":
        do_close(host, port, count)
    elif mode == "hold":
        do_hold(host, port, count)
    elif mode == "burst":
        do_burst(host, port, count)
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
```

## 7. 실행 결과와 해석

> 이 문서의 출력은 모두 **예시 출력**이다. 작성 시점에 작성자 환경에서 Docker 데몬이 실행 중이 아니어서 직접 실행 결과를 첨부하지 못했다. 다음 값은 환경마다 반드시 달라진다: 컨테이너 IP(Compose가 할당하는 172.x 대역), 임시 포트 번호, 시퀀스 번호, `win` 값, 타임스탬프, 지연 시간(ms), `ListenOverflows` 누적 수, fd 번호, curl 에러 문구(curl 버전별). 구조(누구에게 TIME_WAIT가 생기는지, 성공/실패 개수, 재전송 간격이 약 1초·3초인지)는 같아야 한다. 다르면 그것 자체가 조사할 거리다.

| 단계 | 봐야 할 출력 | 정상 해석 |
|---|---|---|
| 3-1 | tcpdump `[S]` `[S.]` `[.]` … `[F.]` | 첫 `[F.]`를 보낸 쪽 = 능동 종료 측 |
| 3-2 | `ss state time-wait` 양쪽 | 먼저 닫은 클라이언트에만 존재 |
| 3-3 | 8초 시점 `ss` | 클라이언트 CLOSE-WAIT, 서버 FIN-WAIT-2 → 이후 서버 TIME-WAIT |
| 3-4 | `[R.]` vs 같은 `seq`의 `[S]` 반복 | refused = 도달했으나 리슨 없음 / timeout = 중간에서 버려짐 |
| 3-5 | UDP 패킷 2개, 닫힌 포트에는 ICMP | 핸드셰이크·종료 절차가 없음 |
| 4-1 | 51번째부터 실패, TIME_WAIT 50개 | 임시 포트 전부가 TIME_WAIT |
| 4-2 | CLOSE-WAIT 20개에 `users:(("python",...,fd=N))` | fd를 쥔 채 앱이 닫지 않음. 시간이 지나도 사라지지 않음 |
| 4-3 | LISTEN `Recv-Q 3 Send-Q 2`, ESTAB 3, 나머지 timeout | 커널이 앱 대신 핸드셰이크 완료, 큐가 차면 SYN 폐기 |
| 5-1 | 같은 local_port, `new_connects=0` | 커넥션 재사용 |

## 8. 검증

아래 체크리스트로 각 개념이 실제로 그렇게 동작함을 확인한다.

| # | 조건 | 명령 | 기대 결과 |
|---|---|---|---|
| 1 | curl 1회 직후 | `[client] # ss -tan state time-wait \| wc -l` | 2 이상 (클라이언트에 TIME_WAIT) |
| 2 | 같은 시점 | `[web-tools] # ss -tan state time-wait \| wc -l` | 1 (서버엔 없음, 헤더만) |
| 3 | 3-3의 8초 시점 | `[client] # ss -tan state close-wait \| wc -l` | 2 |
| 4 | 4-1 반복 60회 직후 | `[client-small] # ss -tan state time-wait \| wc -l` | 51 |
| 5 | 4-1 반복 결과 | 실패 메시지 | 51번째부터 `Cannot assign requested address` (`curl -v`로 확인) |
| 6 | 4-2 후 1분 경과 | `[app] # ss -tan state close-wait \| wc -l` | 21 (줄지 않음) |
| 7 | 4-3 중 | `[app] # ss -ltn 'sport = :9000'` | `Recv-Q 3`, `Send-Q 2` |
| 8 | 4-3 중 | `[app] # nstat -az TcpExtListenOverflows` | 0보다 큼 |
| 9 | 5-1 | `[client-small] # curl ... "http://web/?n=[1-60]"` | `new_connects=1` 한 번, 나머지 0 |
| 10 | 5-2 | `[app] # ss -tan state close-wait \| wc -l` | 1 |

애플리케이션 코드를 검증하는 실습이 아니므로 JUnit·Testcontainers 테스트는 두지 않는다. HikariCP 풀 고갈과 타임아웃을 Spring Boot 앱으로 재현하는 실습은 13장(커넥션 관리)에서 다룬다.

## 9. 변형 실습

**V1.** `client-small`의 `net.ipv4.tcp_tw_reuse`를 `"1"`로 바꾸고 `docker compose up -d client-small`로 재생성한 뒤 4-1을 다시 실행하면?

<details><summary>예상 결과</summary>

요청 사이 간격에 따라 달라진다. `tcp_tw_reuse=1`은 **생성된 지 1초 이상 지난** TIME_WAIT 4-튜플을 나가는 연결에 재사용하게 한다(타임스탬프 옵션 필요, 커널 기본으로 켜짐). curl 60회가 1초 안에 끝나면 가장 오래된 TIME_WAIT도 1초가 안 됐으므로 여전히 51번째부터 실패할 수 있다. 반복문에 `sleep 0.05`를 넣어 전체가 몇 초에 걸치게 하면 대부분 성공하고, 같은 로컬 포트가 다시 쓰이는 것이 `local_port`로 보인다. 즉 `tcp_tw_reuse`는 응급 처치일 뿐이고 초당 새 연결 수의 한계는 남는다. 근본 해결은 5-1의 재사용이다. (재사용 대기 시간을 바꾸는 `tcp_tw_reuse_delay`가 최신 커널에 추가됐다는 이야기가 있다. 확인 필요)
</details>

**V2.** 4-3의 noaccept·backlog 2 상태에서 `hold` 대신 `burst`(6개 동시 시도)를 실행하면?

<details><summary>예상 결과</summary>

`hold`와 달리 **6개 모두 `connect()` 성공**으로 나올 수 있다. 6개의 SYN이 거의 동시에 도착하면, 그 순간 accept 큐는 아직 비어 있으므로 커널이 모두에게 SYN-ACK를 보낸다. 클라이언트는 SYN-ACK를 받는 순간 ESTABLISHED가 되어 `connect()`가 반환된다. 서버에서는 마지막 ACK 중 accept 큐에 들어가지 못한 것들이 버려지고(`tcp_abort_on_overflow=0`), 그 커넥션은 `SYN_RECV`에 머물며 SYN-ACK를 재전송한다. `[app] # ss -tan 'sport = :9000'`에 `SYN-RECV`가 보이고 `ListenOverflows`가 증가한다. **클라이언트는 연결됐다고 믿지만 서버는 연결을 완성하지 않은 불일치**다(이론 Q4). 커널 버전과 타이밍에 따라 일부는 실패로 나올 수 있다.
</details>

**V3.** nginx의 `keepalive_timeout`을 `0`으로 바꾸고(`docker compose restart web` 후 `docker compose up -d --force-recreate web-tools`) 3-1과 3-2를 다시 하면 TIME_WAIT는 어느 쪽에 생길까?

<details><summary>예상 결과</summary>

**서버(web-tools) 쪽**에 생긴다. `keepalive_timeout 0`은 keep-alive를 끈다. nginx는 응답에 `Connection: close`를 넣고 응답을 보낸 직후 먼저 FIN을 보낸다. tcpdump에서 첫 `[F.]`가 서버 측(`.80 >`)에서 나오는 것을 확인할 수 있다. 이 설정에서는 요청마다 새 커넥션이 필요하다. 5-1의 `new_connects`도 매번 1이 된다. 클라이언트 재사용이 서버 설정 하나로 무력화되는 것이다. 이론 6-3의 `max-keep-alive-requests`에 도달했을 때 Tomcat이 하는 동작도 같다(그 커넥션에 한해서).
</details>

## 10. 정리 (Clean-up)

```text
$ cd "C:\Study\Network\네트워크 기초\labs\03-tcp"
$ docker compose down
$ Remove-Item Env:MODE, Env:BACKLOG -ErrorAction SilentlyContinue    # PowerShell에서 환경 변수를 설정했다면
```

- `down`: 컨테이너 5개와 네트워크 `ch03-lab_default`를 삭제한다. 컨테이너별 sysctl과 iptables 규칙도 함께 사라진다.
- 볼륨은 만들지 않았다.

남은 것 확인:

```text
$ docker ps -a --filter "name=ch03-lab"        # 아무것도 없어야 함
$ docker network ls --filter "name=ch03-lab"   # 아무것도 없어야 함
```

직접 빌드한 이미지와 받은 이미지 삭제(선택):

```text
$ docker image rm ch03-tools:1 nginx:1.27-alpine python:3.12-alpine3.20
```

호스트 커널 설정은 바꾸지 않았으므로 원복할 것은 없다. nginx 설정을 V3에서 바꿨다면 `keepalive_timeout 5s;`로 되돌린다.
