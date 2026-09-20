---
장: 5
주제: HTTP/1.1, HTTP/2 — 실습 (다중화, 헤더 한도, 업스트림 keep-alive)
분류: 네트워크 / 응용 계층
난이도: 중급
관련표준: RFC 9110, RFC 9112, RFC 9113
실습환경: Docker Desktop(WSL2 백엔드) 또는 Linux Docker, Linux 컨테이너(nginx 1.27-alpine, python 3.12-alpine, nghttp2)
비용발생: 없음
작성일: 2026-09-20
선행지식: [HTTP 이론](./05-HTTP-이론.md), [TCP와 UDP](./03-TCP-이론.md)
---

# 05. HTTP/1.1, HTTP/2 — 실습

> 관련 문서: [이론](./05-HTTP-이론.md) · 실습 파일: [`labs/05-http/`](./labs/05-http/)

## 1. 실습 개요

같은 백엔드(Python HTTP/1.1 서버)를 Nginx가 세 가지 방식으로 노출한다. **평문 h1.1 / TLS h1.1 / TLS h2**. 프로토콜만 바꿔 가며 같은 부하를 주고, 무엇이 달라지는지 숫자로 본다. 그다음 실무에서 흔한 HTTP 장애 세 가지를 재현하고 고친다.

| 단계 | 하는 일 | 확인하는 이론 |
|---|---|---|
| 3-1 | HTTP/1.1 메시지를 손으로 쳐서 보내기 | 5절 메시지 문법 |
| 3-2 | 프록시가 업스트림에 실제로 보내는 버전·헤더 보기 | 4-1 단계 5, 5절 `proxy_http_version` |
| 3-3 | 청크 전송 인코딩 관찰 | 5절 `Transfer-Encoding` |
| 3-4 | ALPN 협상과 HTTP/2 프레임 보기 | 4-1 단계 3~4, 5절 프레임 |
| 3-5 | h1.1 vs h2 동시 처리량 측정 | 4-2 |
| 4-1 | 커넥션 1개 = 동시 요청 1개(h1.1)로 인한 지연 | 2절 HOL, 9-2 |
| 4-2 | 요청 헤더 9KB → 400, 응답 헤더 16KB → 502 | 9-3 |
| 4-3 | `http2_max_concurrent_streams 2` → h2인데 느림 | 9-2 |
| 4-4 | 업스트림 keep-alive 없음 → TIME_WAIT 누적 | 3장 9-1, 5절 `proxy_http_version` |
| 5 | h2 사용, 버퍼 조정, 업스트림 keep-alive로 하나씩 개선 | 6절, 7절 |

```text
                        ┌──────────────────────────────────────┐
  client ──────────────▶│ web (nginx)                          │
  curl / h2load /       │   :80    평문  HTTP/1.1               │
  nghttp / tcpdump      │   :8443  TLS   HTTP/1.1 (http2 off)  │──▶ app (python)
                        │   :9443  TLS   HTTP/2  (http2 on)    │    HTTP/1.1 :8000
                        └──────────────────────────────────────┘    /api/slow, /api/big,
                          web-tools = web와 netns 공유 (ss, tcpdump)  /api/headers, /api/stream

  /api/     → 업스트림 keep-alive 없음 (nginx 기본, proxy_http_version 1.0)
  /api-ka/  → 업스트림 keep-alive 사용 (proxy_http_version 1.1 + Connection "")
```

## 2. 환경과 준비물

> 💰 **비용 발생 없음.** 로컬 Docker만 사용한다.

| 항목 | 요구 사항 |
|---|---|
| Windows 11 | Docker Desktop (WSL2 백엔드) |
| macOS / Linux | Docker Desktop 또는 Docker Engine + Compose 플러그인. 명령 동일 |
| 인터넷 | 이미지 다운로드와 `apk add` |

인증서는 `certgen` 컨테이너가 자체 서명으로 만든다. 리포지터리에 키를 남기지 않도록 이름 있는 볼륨에 둔다. 클라이언트는 검증을 생략(`-k`)하고 접속한다. 인증서 체인 검증은 [6장](./06-TLS-이론.md)에서 다룬다.

**HTTP/3은 이 실습의 기본 경로에 포함하지 않았다.** 로컬에서 h3을 끝까지 확인하려면 ① QUIC을 포함해 빌드된 nginx, ② HTTP/3을 지원하는 curl 빌드, ③ UDP 443 경로가 모두 필요하고, 자체 서명 인증서로는 브라우저가 h3으로 승격하지 않는다. 대신 5-4에 설정 예시와 확인 방법을 두고, 관찰은 공개 사이트로 하도록 안내한다.

**프롬프트 표기**

| 표기 | 실행 위치 |
|---|---|
| `$` | 호스트 터미널 (PowerShell 또는 WSL2 bash) |
| `[client] #` | `$ docker compose exec client sh` |
| `[web-tools] #` | `$ docker compose exec web-tools sh` — nginx와 네트워크를 공유하는 컨테이너 |

### 기동

```text
$ cd "C:\Study\Network\네트워크 기초\labs\05-http"
$ docker compose up -d --build
$ docker compose ps
$ docker compose logs app --tail 3
```

예시 출력:

```text
app-1  | listening on 0.0.0.0:8000 (HTTP/1.1, threaded)
```

클라이언트 도구가 HTTP/2를 지원하는지 먼저 확인한다.

```text
[client] # curl -V | head -2
[client] # h2load --version
```

예시 출력 (버전은 다를 수 있음):

```text
curl 8.x.x (x86_64-alpine-linux-musl) libcurl/8.x.x OpenSSL/3.x.x ... nghttp2/1.x.x
Features: ... HTTP2 HTTPS-proxy ...
h2load nghttp2/1.x.x
```

`Features`에 `HTTP2`가 없으면 3-4 이후를 진행할 수 없다. 이미지 빌드 로그에서 `nghttp2` 설치가 실패했는지 확인한다.

## 3. 관찰 먼저

### 3-1. HTTP/1.1 메시지를 손으로 보내기

```text
[client] # printf 'GET /healthz HTTP/1.1\r\nHost: web\r\nConnection: close\r\n\r\n' | nc web 80
```

예시 출력 (날짜는 매번 다름):

```text
HTTP/1.1 200 OK
Server: nginx/1.27.x
Date: ...
Content-Type: text/plain
Content-Length: 3
Connection: close

ok
```

| 요소 | 의미 |
|---|---|
| `GET /healthz HTTP/1.1` | 요청 줄. 메서드, 타깃, 버전 |
| `Host: web` | HTTP/1.1 필수 헤더. 이게 있어서 IP 하나에 여러 도메인을 올릴 수 있다 |
| `\r\n\r\n` | 빈 줄이 헤더의 끝. 여기까지가 "헤더", 이후가 바디 |
| `Connection: close` | 응답 후 닫아 달라는 요청. 빼면 nginx가 커넥션을 유지한다(기본 keep-alive) |
| `Content-Length: 3` | 바디 길이 |

**이 단계가 가능한 것이 HTTP/1.1의 특징**이다. HTTP/2·3은 바이너리 프레임이라 손으로 칠 수 없다.

### 3-2. 프록시는 업스트림에 무엇을 보내는가

```text
[client] # curl -s http://web/api/headers | head -c 400; echo
[client] # curl -s http://web/api-ka/headers | head -c 400; echo
```

예시 출력 (포트 번호는 매번 다름):

```text
{"http_version_seen_by_app": "HTTP/1.0", "client_port": 47112, "headers": {"host": "web", "x-forwarded-for": "172.x.x.x", "x-forwarded-proto": "http", "connection": "close", "user-agent": "curl/8.x.x", "accept": "*/*"}}

{"http_version_seen_by_app": "HTTP/1.1", "client_port": 47120, "headers": {"host": "web", "x-forwarded-for": "172.x.x.x", "x-forwarded-proto": "http", "user-agent": "curl/8.x.x", "accept": "*/*"}}
```

- `/api/`는 앱이 받은 버전이 **HTTP/1.0**이고 `connection: close`가 붙어 있다. Nginx의 `proxy_http_version` 기본값이 1.0이기 때문이다. 즉 **요청마다 새 TCP 커넥션**이다(4-4에서 수치로 확인).
- `/api-ka/`는 **HTTP/1.1**이고 `connection` 헤더가 없다. 커넥션을 재사용할 수 있다.
- `x-forwarded-for`, `x-forwarded-proto`는 프록시가 붙인 것이다([1장 9-5](./01-OSI-TCPIP-이론.md)). 앱의 `getRemoteAddr()`가 프록시 IP를 보게 되는 이유이기도 하다.

같은 요청을 몇 번 반복하며 app 로그를 본다.

```text
$ docker compose logs app --tail 6
```

예시 출력:

```text
app-1  | [10:00:01] port=47112 HTTP/1.0 "GET /api/headers HTTP/1.0" 200 -
app-1  | [10:00:03] port=47118 HTTP/1.0 "GET /api/headers HTTP/1.0" 200 -
app-1  | [10:00:05] port=47120 HTTP/1.1 "GET /api-ka/headers HTTP/1.1" 200 -
app-1  | [10:00:07] port=47120 HTTP/1.1 "GET /api-ka/headers HTTP/1.1" 200 -
```

`/api/`는 포트가 매번 바뀌고, `/api-ka/`는 같은 포트가 반복된다. **포트가 같다 = 같은 TCP 커넥션을 재사용했다**([3장](./03-TCP-이론.md)).

### 3-3. 청크 전송 인코딩

```text
[client] # curl -N -v http://web/api/stream 2>&1 | grep -E 'Transfer-Encoding|^line'
```

예시 출력 (시각은 1초 간격):

```text
< Transfer-Encoding: chunked
line 1 at 10:05:00
line 2 at 10:05:01
line 3 at 10:05:02
line 4 at 10:05:03
line 5 at 10:05:04
```

- `Content-Length`가 없다. 길이를 모른 채 시작하는 응답은 이렇게 보낸다. SSE(`SseEmitter`)와 스트리밍 응답이 이 방식이다.
- `-N`은 curl의 출력 버퍼링을 끈다. Nginx는 기본적으로 업스트림 응답을 버퍼링(`proxy_buffering on`)하므로, 환경에 따라 줄이 한꺼번에 나올 수 있다. 그때는 `location`에 `proxy_buffering off;`를 넣고 다시 본다.
- HTTP/2에는 청크 인코딩이 없다. 프레이밍이 그 역할을 대신한다(`:9443`으로 같은 요청을 보내면 `Transfer-Encoding` 헤더가 사라진다).

### 3-4. ALPN 협상과 HTTP/2 프레임

```text
[client] # curl -vk https://web:8443/healthz 2>&1 | grep -E 'ALPN|HTTP/'
[client] # curl -vk https://web:9443/healthz 2>&1 | grep -E 'ALPN|HTTP/'
```

예시 출력:

```text
* ALPN: curl offers h2,http/1.1
* ALPN: server accepted http/1.1
> GET /healthz HTTP/1.1
< HTTP/1.1 200 OK

* ALPN: curl offers h2,http/1.1
* ALPN: server accepted h2
* using HTTP/2
> GET /healthz HTTP/2
< HTTP/2 200
```

같은 curl, 같은 경로인데 **서버가 ALPN에서 무엇을 고르느냐**로 프로토콜이 갈렸다(이론 9-1). 8443은 `http2 off`라 h1.1이 선택됐다.

프레임 단위로 본다.

```text
[client] # nghttp -nv https://web:9443/healthz 2>&1 | head -30
```

예시 출력 (스트림 ID와 값은 환경에 따라 다름):

```text
[  0.001] Connected
[  0.010] send SETTINGS frame <length=12, flags=0x00, stream_id=0>
          (niv=2)
          [SETTINGS_MAX_CONCURRENT_STREAMS(0x03):100]
          [SETTINGS_INITIAL_WINDOW_SIZE(0x04):65535]
[  0.011] recv SETTINGS frame <length=18, flags=0x00, stream_id=0>
          (niv=3)
          [SETTINGS_MAX_CONCURRENT_STREAMS(0x03):128]
          [SETTINGS_INITIAL_WINDOW_SIZE(0x04):65535]
          [SETTINGS_MAX_FRAME_SIZE(0x05):16384]
[  0.012] send HEADERS frame <length=..., flags=0x25, stream_id=1>
          ; END_STREAM | END_HEADERS | PRIORITY
          :method: GET
          :scheme: https
          :path: /healthz
          :authority: web:9443
[  0.015] recv HEADERS frame <length=..., flags=0x04, stream_id=1>
          :status: 200
[  0.015] recv DATA frame <length=3, flags=0x01, stream_id=1>
[  0.015] recv GOAWAY frame <length=8, flags=0x00, stream_id=0>
```

| 관찰 | 의미 |
|---|---|
| SETTINGS 교환이 먼저 | 이론 4-1 단계 4. 여기서 서버가 알린 `MAX_CONCURRENT_STREAMS`(예: 128)가 클라이언트의 동시 요청 상한이 된다 |
| `:method`, `:path`, `:authority` | HTTP/2의 **의사 헤더(pseudo-header)**. h1.1의 요청 줄과 `Host` 헤더가 헤더로 흡수됐다 |
| `stream_id=1` | 클라이언트가 연 첫 스트림(홀수) |
| HEADERS → DATA | 헤더와 바디가 별도 프레임 |

### 3-5. h1.1과 h2의 동시 처리량 측정 (기준선)

요청 20개를 **커넥션 1개**로 보낸다. 백엔드는 요청당 200ms가 걸린다.

```text
[client] # h2load --h1 -n 20 -c 1 -m 10 'https://web:8443/api/slow?ms=200' | grep -E 'finished in|requests/s|status codes'
[client] # h2load      -n 20 -c 1 -m 10 'https://web:9443/api/slow?ms=200' | grep -E 'finished in|requests/s|status codes'
```

예시 출력 (절대 수치는 환경마다 다르다. **비율**을 본다):

```text
finished in 4.05s, 4.93 req/s, ...          ← --h1 (HTTP/1.1)
finished in 0.45s, 44.1 req/s, ...          ← HTTP/2
```

- h1.1: 커넥션 1개에서 요청이 **순차** 처리된다. 20 × 200ms ≈ 4초.
- h2: `-m 10`(동시 스트림 10개)이 실제로 동시에 처리된다. 20 ÷ 10 × 200ms ≈ 0.4초.
- 이것이 이론 4-2의 그림을 숫자로 본 것이다. 단, 이 이득은 **커넥션 수가 제한될 때** 나온다. `-c 10`으로 커넥션을 10개 열면 h1.1도 비슷해진다. 직접 해보면 브라우저가 왜 도메인당 6커넥션을 여는지 이해된다.

## 4. 문제 상황 재현

### 4-1. 커넥션 1개 = 동시 요청 1개 (HTTP/1.1)

3-5의 첫 번째 결과가 재현 그 자체다. 조건을 바꿔 확인한다.

```text
[client] # h2load --h1 -n 20 -c 1  -m 10 'https://web:8443/api/slow?ms=200' | grep 'finished in'
[client] # h2load --h1 -n 20 -c 10 -m 10 'https://web:8443/api/slow?ms=200' | grep 'finished in'
```

예시 출력:

```text
finished in 4.05s, ...     ← 커넥션 1개
finished in 0.43s, ...     ← 커넥션 10개
```

- h1.1에서 동시성을 늘리는 방법은 **커넥션을 더 여는 것뿐**이다. `-m`(다중화)은 무시된다.
- 커넥션이 늘면 핸드셰이크·TLS·슬로 스타트 비용과 서버 메모리도 함께 는다([3장](./03-TCP-이론.md)).
- 실무 대응: 브라우저는 6개로 제한하고, 서버 간 호출은 커넥션 풀 크기로 조절한다(이론 6-3).

### 4-2. 헤더 크기 초과 — 400과 502

**요청 헤더가 큰 경우** (큰 JWT나 쿠키 누적을 흉내):

```text
[client] # BIG=$(head -c 9000 /dev/zero | tr '\0' 'x')
[client] # curl -sk -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $BIG" https://web:8443/api/headers
```

예시 출력:

```text
400
```

Nginx 로그를 본다.

```text
$ docker compose logs web --tail 5
```

예시 출력:

```text
web-1  | 2026/09/20 10:10:00 [error] 29#29: *12 client sent too long header line: "Authorization: Bearer xxxxx..." while reading client request headers, client: 172.x.x.x, server: _, request: "GET /api/headers HTTP/1.1"
web-1  | 172.x.x.x HTTP/1.1 "GET /api/headers HTTP/1.1" 400 req_len=9100 sent=0 upstream=- rt=0.000 urt=-
```

- `client sent too long header line`이 결정적 단서다. `large_client_header_buffers 4 8k`(기본값)의 8k를 넘었다.
- **앱 로그에는 아무것도 없다.** 요청이 앱까지 도달하지 못했다. 이론 9-3의 "앱 로그만 보면 원인을 못 찾는다"가 이것이다.

**응답 헤더가 큰 경우** (앱이 큰 헤더를 보냄):

```text
[client] # curl -sk -o /dev/null -w '%{http_code}\n' 'https://web:8443/api/bigheader?kb=16'
```

예시 출력:

```text
502
```

```text
$ docker compose logs web --tail 3
$ docker compose logs app --tail 3
```

예시 출력:

```text
web-1  | 2026/09/20 10:11:00 [error] 29#29: *15 upstream sent too big header while reading response header from upstream, client: 172.x.x.x, ... upstream: "http://172.x.x.x:8000/api/bigheader?kb=16"
app-1  | [10:11:00] port=47210 HTTP/1.0 "GET /api/bigheader?kb=16 HTTP/1.0" 200 -
```

- **앱은 200을 보냈는데 클라이언트는 502를 받았다.** Nginx가 업스트림 응답 헤더를 담을 버퍼(`proxy_buffer_size`, 기본 4k/8k)를 넘겨 응답을 버린 것이다.
- 502를 보면 "앱이 죽었나"부터 보기 쉽지만, 이 경우 앱은 정상이다. 앱 액세스 로그의 200과 프록시의 502가 **짝이 맞지 않는 것**이 신호다.

### 4-3. HTTP/2인데 느리다 — 동시 스트림 상한

호스트에서 `nginx/default.conf`의 `http2_max_concurrent_streams`를 128에서 **2**로 바꾸고 설정을 다시 읽힌다.

```text
$ docker compose exec web nginx -t
$ docker compose exec web nginx -s reload
[client] # nghttp -nv https://web:9443/healthz 2>&1 | grep MAX_CONCURRENT
[client] # h2load -n 20 -c 1 -m 10 'https://web:9443/api/slow?ms=200' | grep -E 'finished in'
```

예시 출력:

```text
          [SETTINGS_MAX_CONCURRENT_STREAMS(0x03):2]
finished in 2.05s, ...
```

- 클라이언트가 `-m 10`으로 10개를 동시에 보내려 해도, 서버가 SETTINGS로 2를 알렸으므로 동시 스트림은 2개다. 20 ÷ 2 × 200ms ≈ 2초로 h2의 이점이 대부분 사라졌다.
- **클라이언트 코드나 커넥션 풀 설정을 아무리 바꿔도 이 상한은 넘지 못한다.** 상대 서버가 정한 값이기 때문이다(이론 6-3).
- 실무에서는 게이트웨이·LB가 이 값을 낮게 잡아 둔 경우가 있으므로, h2 성능이 기대와 다르면 `nghttp -nv`로 먼저 확인한다.

### 4-4. 업스트림 keep-alive 없음 — 커넥션과 TIME_WAIT 누적

`/api/`(keep-alive 없음)와 `/api-ka/`(keep-alive 있음)에 각각 부하를 주고 **nginx 쪽** 소켓을 비교한다.

```text
[web-tools] # ss -tan state time-wait | wc -l          # 시작값 확인
[client]    # h2load -n 200 -c 1 -m 10 'https://web:9443/api/slow?ms=10' > /dev/null
[web-tools] # ss -tan state time-wait | wc -l
[client]    # h2load -n 200 -c 1 -m 10 'https://web:9443/api-ka/slow?ms=10' > /dev/null
[web-tools] # ss -tan state time-wait | wc -l
```

예시 출력:

```text
1        ← 헤더만 (시작)
201      ← /api/ 200요청 후: 요청 수만큼 TIME_WAIT
203      ← /api-ka/ 200요청 후: 거의 늘지 않음
```

- `/api/`는 요청 200개에 업스트림 커넥션 200개를 열고 닫았다. nginx가 먼저 닫으므로 **nginx 쪽에 TIME_WAIT**가 쌓인다([3장 9-1](./03-TCP-이론.md)).
- 부하가 크면 nginx의 임시 포트가 고갈되어 `Cannot assign requested address`가 난다. 클라이언트가 h2로 아무리 효율적으로 보내도, **프록시 뒤 구간에서 병목이 생긴다**(이론 9-2의 마지막 항목).
- app 로그의 `port=`도 함께 보면 `/api/`는 매번 다르고 `/api-ka/`는 재사용된다.

## 5. 개선된 구성

### 5-1. 동시 스트림 상한 복구

`nginx/default.conf`의 `http2_max_concurrent_streams`를 128로 되돌리고 reload한다.

```text
$ docker compose exec web nginx -s reload
[client] # h2load -n 20 -c 1 -m 10 'https://web:9443/api/slow?ms=200' | grep 'finished in'
```

예시 출력:

```text
finished in 0.45s, ...
```

4-3의 2.05초에서 돌아왔다. 값을 무작정 키우는 것은 답이 아니다. 스트림 하나가 서버의 워커·메모리를 쓰므로, **백엔드가 감당할 동시 처리량**에 맞춰 정한다(13장).

### 5-2. 헤더 버퍼 조정

`nginx/default.conf`의 `server` 블록(8443과 9443 둘 다)에 다음을 추가하고 reload한다.

```nginx
    # 요청 헤더: 기본 4 8k. 큰 JWT·쿠키를 받아야 하면 올린다 (4-2 요청 방향)
    large_client_header_buffers 4 32k;

    # 업스트림 응답 헤더: 기본 4k|8k. 앱이 큰 헤더를 보낼 때 502를 막는다 (4-2 응답 방향)
    proxy_buffer_size 32k;
    proxy_buffers 8 32k;
```

```text
$ docker compose exec web nginx -t && docker compose exec web nginx -s reload
[client] # curl -sk -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $BIG" https://web:8443/api/headers
[client] # curl -sk -o /dev/null -w '%{http_code}\n' 'https://web:8443/api/bigheader?kb=16'
```

예시 출력:

```text
200
200
```

여기서 멈추지 말고 두 가지를 더 확인한다.

1. **경로상의 다른 구간**: 실제 서비스라면 Tomcat(`server.max-http-request-header-size`, 기본 8KB)과 ALB·CloudFront 한도도 같이 올려야 한다. 한 군데만 올리면 다음 구간에서 같은 증상이 난다.
2. **헤더를 줄이는 쪽**: 32KB 헤더를 매 요청 보내는 것은 그 자체로 낭비다. JWT 클레임과 쿠키를 먼저 점검한다(이론 9-3, 10절).

### 5-3. 업스트림 keep-alive

이미 `/api-ka/`로 검증했다(4-4). 실제 설정에서는 **모든 `location`이 keep-alive 업스트림을 쓰도록** 바꾼다. 필요한 것은 세 가지다.

```nginx
upstream app_ka {
    server app:8000;
    keepalive 32;              # 워커당 유지할 유휴 커넥션 수. 백엔드 동시 처리량 기준으로 정한다
}

location /api/ {
    proxy_pass http://app_ka/api/;
    proxy_http_version 1.1;    # 기본 1.0 — 이것만 빠져도 keepalive 지시어가 무의미해진다
    proxy_set_header Connection "";
}
```

확인 방법은 세 가지다. ① app 로그의 `port=`가 반복되는지, ② `[web-tools] # ss -tan | grep :8000 | wc -l`이 요청 수가 아니라 `keepalive` 수 근처에서 유지되는지, ③ TIME_WAIT가 늘지 않는지.

백엔드가 Tomcat이면 `server.tomcat.keep-alive-timeout`이 Nginx의 유휴 유지 시간보다 **길어야** 한다([3장 9-3](./03-TCP-이론.md)). 짧으면 Tomcat이 먼저 닫는 순간 요청이 겹쳐 502가 난다.

### 5-4. (선택) HTTP/3 켜보기

`nginx -V`에 `--with-http_v3_module`이 있어야 한다.

```text
$ docker compose exec web nginx -V 2>&1 | tr ' ' '\n' | grep -i http_v3
```

나오면 `nginx/default.conf`의 9443 서버 블록에서 주석 처리된 세 줄을 푼다.

```nginx
    listen 9443 quic reuseport;
    http3 on;
    add_header Alt-Svc 'h3=":9443"; ma=86400' always;
```

```text
$ docker compose exec web nginx -t && docker compose exec web nginx -s reload
[client] # curl -skI https://web:9443/healthz | grep -i alt-svc
```

예시 출력:

```text
alt-svc: h3=":9443"; ma=86400
```

여기까지가 로컬에서 확실히 확인할 수 있는 범위다. **실제 h3 연결까지 보려면** ① HTTP/3을 지원하는 curl 빌드(`curl -V`의 Features에 `HTTP3`)나 ② 유효한 인증서를 가진 브라우저 환경이 필요하다. 자체 서명 인증서에서는 브라우저가 `Alt-Svc`를 따라 h3으로 승격하지 않는다.

대신 공개 사이트로 관찰할 수 있다. 브라우저 개발자도구 Network 탭에서 Protocol 열을 켜고 `https://cloudflare-quic.com` 같은 h3 지원 사이트를 열면 `h3`이 표시된다. 회사 네트워크에서 `h2`로만 보인다면 UDP 443이 막혔을 가능성이 높다(이론 9-7).

## 6. 전체 구성 파일

```text
labs/05-http/
├── docker-compose.yml
├── nginx/
│   ├── default.conf
│   └── locations.conf
├── app/
│   └── app.py
└── client/
    └── Dockerfile
```

### `docker-compose.yml`

```yaml
# 5장 실습: HTTP/1.1 vs HTTP/2 (다중화, 헤더 한도, 업스트림 keep-alive)
#
#   certgen : 자체 서명 인증서를 만들어 certs 볼륨에 넣고 종료 (1회성)
#   app     : 파이썬 HTTP/1.1 백엔드 :8000 (느린 응답, 큰 헤더, 청크 스트리밍)
#   web     : nginx — :80 평문 h1.1 / :8443 TLS h1.1 / :9443 TLS h2
#   client  : curl + nghttp2(h2load, nghttp) + tcpdump
name: ch05-lab

volumes:
  # 인증서를 리포지터리에 남기지 않도록 이름 있는 볼륨에 만든다
  certs:

services:
  certgen:
    image: alpine:3.20
    volumes:
      - certs:/certs
    # SAN에 web/localhost를 넣어 컨테이너 이름과 호스트 양쪽에서 쓸 수 있게 한다.
    # 자체 서명이므로 클라이언트는 -k(검증 생략)로 접속한다. 인증서 체인은 6장에서 다룬다.
    command:
      - sh
      - -c
      - |
        apk add --no-cache openssl >/dev/null
        if [ ! -f /certs/web.crt ]; then
          openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
            -keyout /certs/web.key -out /certs/web.crt \
            -subj "/CN=web" \
            -addext "subjectAltName=DNS:web,DNS:localhost,IP:127.0.0.1"
        fi
        chmod 644 /certs/web.key
        ls -l /certs

  app:
    image: python:3.12-alpine3.20
    hostname: app
    environment:
      PORT: "8000"
    volumes:
      - ./app:/app:ro
    # -u: 표준 출력 버퍼링 끄기 (docker compose logs에 즉시 보이도록)
    command: ["python", "-u", "/app/app.py"]

  web:
    image: nginx:1.27-alpine
    hostname: web
    depends_on:
      certgen:
        condition: service_completed_successfully
      app:
        condition: service_started
    volumes:
      - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
      - ./nginx/locations.conf:/etc/nginx/snippets/locations.conf:ro
      - certs:/etc/nginx/certs:ro
    ports:
      - "8080:80"        # 평문 HTTP/1.1
      - "8443:8443"      # TLS + HTTP/1.1
      - "9443:9443"      # TLS + HTTP/2
      - "9443:9443/udp"  # HTTP/3(QUIC)을 켤 경우에만 의미 있음

  # nginx와 네트워크 네임스페이스를 공유한다. 여기서 본 ss/tcpdump 결과 = nginx의 소켓
  # (nginx 이미지에는 ss, tcpdump가 없다. web을 재생성하면 이 컨테이너도 재생성해야 한다)
  web-tools:
    build: ./client
    image: ch05-client:1
    depends_on:
      web:
        condition: service_started
    cap_add: [NET_RAW, NET_ADMIN]
    init: true
    network_mode: "service:web"
    command: ["sleep", "infinity"]

  client:
    build: ./client
    image: ch05-client:1
    # NET_RAW: tcpdump
    cap_add: [NET_RAW, NET_ADMIN]
    init: true
    command: ["sleep", "infinity"]
```

### `nginx/default.conf`

```nginx
# 업스트림 A: keepalive 지시어 없음 = nginx 기본.
# 요청마다 app 으로 새 TCP 커넥션을 열고 닫는다 (4-4에서 TIME_WAIT로 확인)
upstream app_plain {
    server app:8000;
}

# 업스트림 B: 유휴 커넥션을 32개까지 유지한다 (5-4 개선안)
upstream app_ka {
    server app:8000;
    keepalive 32;
}

# $server_protocol = 클라이언트가 사용한 HTTP 버전 (HTTP/1.1, HTTP/2.0)
# $request_length  = 요청 줄 + 헤더 + 바디 크기 (헤더 비대 확인용)
log_format lab '$remote_addr $server_protocol "$request" $status '
               'req_len=$request_length sent=$body_bytes_sent '
               'upstream=$upstream_addr rt=$request_time urt=$upstream_response_time';
access_log /var/log/nginx/access.log lab;

# 1) 평문 HTTP/1.1 — TLS가 없으므로 브라우저는 여기서 절대 h2를 쓰지 않는다
server {
    listen 80;
    server_name _;
    include /etc/nginx/snippets/locations.conf;
}

# 2) TLS + HTTP/1.1 전용 — h2와 비교하기 위한 대조군
server {
    listen 8443 ssl;
    # nginx 1.25.1+ 부터는 listen 의 http2 파라미터 대신 이 지시어를 쓴다. 기본값은 off
    http2 off;
    server_name _;

    ssl_certificate     /etc/nginx/certs/web.crt;
    ssl_certificate_key /etc/nginx/certs/web.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    include /etc/nginx/snippets/locations.conf;
}

# 3) TLS + HTTP/2
server {
    listen 9443 ssl;
    http2 on;
    # 한 커넥션에서 동시에 열 수 있는 스트림 수. 기본 128.
    # 4-3에서 2로 낮춰 "h2인데 느린" 상황을 재현한다
    http2_max_concurrent_streams 128;
    server_name _;

    ssl_certificate     /etc/nginx/certs/web.crt;
    ssl_certificate_key /etc/nginx/certs/web.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    include /etc/nginx/snippets/locations.conf;

    # --- HTTP/3(QUIC)을 시도하려면 아래 주석을 풀고 nginx -V 에 --with-http_v3_module 이 있는지 먼저 확인한다.
    # listen 9443 quic reuseport;
    # http3 on;
    # add_header Alt-Svc 'h3=":9443"; ma=86400' always;   # 클라이언트에게 h3 가능함을 알리는 헤더
}
```

### `nginx/locations.conf`

```nginx
# 세 개의 server 블록(80 / 8443 / 9443)이 공유하는 location 정의.
# 같은 경로를 프로토콜만 바꿔 비교하기 위해 별도 파일로 뺐다.

location = /healthz {
    return 200 "ok\n";
}

# 업스트림 keep-alive 없음 (nginx 기본): 요청마다 app 으로 새 TCP 커넥션
location /api/ {
    proxy_pass http://app_plain/api/;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

# 업스트림 keep-alive 사용. 두 줄이 함께 있어야 동작한다:
#   proxy_http_version 1.1  — 기본값 1.0은 지속 커넥션을 쓰지 않는다
#   proxy_set_header Connection ""  — 클라이언트의 Connection 헤더가 업스트림으로 새는 것을 막는다
location /api-ka/ {
    proxy_pass http://app_ka/api/;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

### `app/app.py`

```python
"""5장 실습용 백엔드 (Python 3.8+ 표준 라이브러리만 사용)

Spring Boot 앱 자리에 두는 HTTP/1.1 서버. Tomcat처럼 스레드로 요청을 처리한다.

엔드포인트
  GET /api/slow?ms=200      지정한 시간만큼 기다린 뒤 응답 (다중화 효과 측정용)
  GET /api/big?kb=256       지정 크기의 바디 (흐름 제어·전송 시간 관찰용)
  GET /api/headers          받은 요청 헤더와 HTTP 버전을 그대로 반환 (프록시가 무엇을 바꿨는지 확인)
  GET /api/bigheader?kb=16  아주 큰 응답 헤더 (Nginx proxy_buffer_size 초과 재현)
  GET /api/stream           Content-Length 없이 청크로 5줄을 1초 간격 전송 (chunked 관찰)

로그는 "클라이언트포트 HTTP버전 경로" 형태로 남긴다.
같은 포트가 반복되면 커넥션을 재사용한 것이고, 매번 다르면 요청마다 새 커넥션이다.
"""
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(os.environ.get("PORT", "8000"))


class Handler(BaseHTTPRequestHandler):
    # 기본값은 HTTP/1.0 이라 응답마다 커넥션을 닫는다. keep-alive를 보려면 1.1로 올려야 한다
    protocol_version = "HTTP/1.1"
    server_version = "LabApp/1.0"
    sys_version = ""

    def log_message(self, fmt, *args):
        sys.stderr.write("[%s] port=%-5s %s %s\n" % (
            time.strftime("%H:%M:%S"),
            self.client_address[1],
            self.request_version,
            fmt % args,
        ))

    def _send(self, status, content_type, body, extra_headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, status=200):
        self._send(status, "application/json", json.dumps(obj, ensure_ascii=False).encode("utf-8"))

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)

        def num(name, default):
            return int(query.get(name, [default])[0])

        try:
            if parsed.path == "/api/slow":
                ms = num("ms", 200)
                time.sleep(ms / 1000.0)
                self._json({"slept_ms": ms, "client_port": self.client_address[1]})

            elif parsed.path == "/api/big":
                kb = num("kb", 256)
                self._send(200, "application/octet-stream", b"x" * (kb * 1024))

            elif parsed.path == "/api/headers":
                self._json({
                    "http_version_seen_by_app": self.request_version,
                    "client_port": self.client_address[1],
                    "headers": {k.lower(): v for k, v in self.headers.items()},
                })

            elif parsed.path == "/api/bigheader":
                kb = num("kb", 16)
                # 바디는 작지만 응답 "헤더"가 크다 → 프록시의 응답 헤더 버퍼를 넘긴다
                self._send(
                    200,
                    "application/json",
                    json.dumps({"header_kb": kb}).encode(),
                    {"X-Big-Header": "y" * (kb * 1024)},
                )

            elif parsed.path == "/api/stream":
                # Content-Length를 보내지 않고 청크 전송 인코딩으로 조금씩 흘린다
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                for i in range(5):
                    chunk = ("line %d at %s\n" % (i + 1, time.strftime("%H:%M:%S"))).encode()
                    # 청크 형식: 길이(16진수) CRLF 데이터 CRLF
                    self.wfile.write(b"%x\r\n" % len(chunk) + chunk + b"\r\n")
                    self.wfile.flush()
                    time.sleep(1)
                self.wfile.write(b"0\r\n\r\n")  # 마지막 0 청크가 본문의 끝

            else:
                self._json({"error": "not found", "path": parsed.path}, 404)

        except (BrokenPipeError, ConnectionResetError):
            # 클라이언트가 응답 도중 끊은 경우. Tomcat의 ClientAbortException에 해당한다
            self.log_message("%s", "클라이언트가 먼저 끊음 (Broken pipe)")


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    sys.stderr.write("listening on 0.0.0.0:%d (HTTP/1.1, threaded)\n" % PORT)
    server.serve_forever()


if __name__ == "__main__":
    main()
```

### `client/Dockerfile`

```dockerfile
# curl: HTTP/1.1·HTTP/2 클라이언트 / nghttp2: h2load(부하), nghttp(프레임 관찰)
# tcpdump, iproute2: 커넥션과 패킷 관찰 (3장 도구와 동일)
# 알파인 저장소에서 nghttp2 도구 패키지 이름이 버전에 따라 다를 수 있어 둘 중 되는 쪽을 설치한다
FROM alpine:3.20
RUN apk add --no-cache curl tcpdump iproute2 \
 && (apk add --no-cache nghttp2 || apk add --no-cache nghttp2-tools)
WORKDIR /lab
```


## 7. 실행 결과와 해석

> 이 문서의 출력은 모두 **예시 출력**이다. 작성 시점에 작성자 환경에서 Docker 데몬이 실행 중이 아니어서 직접 실행 결과를 첨부하지 못했다. 다음 값은 환경마다 반드시 달라진다: 컨테이너 IP와 포트 번호, 날짜·시각, 라이브러리 버전 문자열, h2load의 절대 시간과 req/s(호스트 성능에 좌우된다), nginx 워커 PID, 소켓 개수의 시작값. 구조(어느 쪽이 몇 배 빠른지, 어떤 상태 코드가 나오는지, 로그 문구)는 같아야 한다. 다르면 그것 자체가 조사할 거리다.

| 단계 | 봐야 할 출력 | 정상 해석 |
|---|---|---|
| 3-1 | 평문 응답 텍스트 | h1.1은 사람이 읽고 쓸 수 있는 프로토콜 |
| 3-2 | `http_version_seen_by_app` | `/api/`는 HTTP/1.0(+`connection: close`), `/api-ka/`는 HTTP/1.1 |
| 3-3 | `Transfer-Encoding: chunked` | 길이를 모르는 응답의 전송 방식 |
| 3-4 | `ALPN: server accepted h2`, SETTINGS/HEADERS/DATA | 프로토콜은 TLS 핸드셰이크에서 결정된다 |
| 3-5 | 약 4초 vs 약 0.45초 | 커넥션 1개에서의 다중화 효과 |
| 4-1 | `-c 10`이면 h1.1도 빨라짐 | h1.1의 동시성 수단은 커넥션 수뿐 |
| 4-2 | 400 + `too long header line`, 502 + `upstream sent too big header` | 요청·응답 헤더 한도는 별개 설정 |
| 4-3 | `MAX_CONCURRENT_STREAMS:2`, 약 2초 | 동시성 상한은 서버가 정한다 |
| 4-4 | TIME_WAIT 200개 증가 vs 거의 증가 없음 | 업스트림 keep-alive의 효과 |
| 5-2 | 200, 200 | 양방향 버퍼를 모두 올려야 해결 |

## 8. 검증

| # | 명령 | 기대 결과 |
|---|---|---|
| 1 | `[client] # curl -sk -o /dev/null -w '%{http_version}\n' https://web:8443/healthz` | `1.1` |
| 2 | `[client] # curl -sk -o /dev/null -w '%{http_version}\n' https://web:9443/healthz` | `2` |
| 3 | `[client] # curl -s http://web/api/headers \| grep -o 'HTTP/1.0'` | `HTTP/1.0` (업스트림 기본 동작) |
| 4 | `[client] # curl -s http://web/api-ka/headers \| grep -o 'HTTP/1.1'` | `HTTP/1.1` |
| 5 | 3-5의 두 h2load 결과 | h2가 h1.1보다 최소 3배 이상 빠름 |
| 6 | 4-2 요청 헤더 9KB | `400`, web 로그에 `too long header line` |
| 7 | 4-2 응답 헤더 16KB | `502`, web 로그에 `upstream sent too big header`, app 로그는 `200` |
| 8 | 4-3 후 `nghttp -nv ... \| grep MAX_CONCURRENT` | `2` |
| 9 | 4-4 `/api/` 부하 후 TIME_WAIT 증가량 | 요청 수와 비슷한 수 |
| 10 | 5-2 후 4-2의 두 요청 | 둘 다 `200` |

Spring Boot 앱으로 같은 것을 확인하려면 컨트롤러 하나와 `server.max-http-request-header-size` 설정이면 충분하지만, 이 장의 목적(프로토콜 동작 관찰)에는 프록시와 단순 백엔드 조합이 더 명확해 테스트 코드는 두지 않았다. Tomcat 스레드·타임아웃과 엮인 재현은 13장에서 Spring Boot 앱으로 다룬다.

## 9. 변형 실습

**V1.** `h2load -n 100 -c 1 -m 10 'https://web:9443/api/big?kb=1024'`로 1MB 응답 100개를 받아보고, `kb=16`일 때와 요청당 시간을 비교하면?

<details><summary>예상 결과</summary>

큰 응답에서는 다중화 이득이 줄어든다. HTTP/2의 초기 흐름 제어 윈도우는 스트림당 65535바이트라, 1MB를 보내려면 WINDOW_UPDATE를 주고받으며 여러 RTT를 쓴다. 게다가 커넥션 하나의 대역폭을 스트림들이 나눠 쓰므로 총 전송 시간은 결국 대역폭에 수렴한다. h2의 이점은 **작고 많은 요청**에서 크고, 대용량 전송에서는 작다(이론 7절 선택 기준). 로컬 루프백은 대역폭이 매우 커서 차이가 작게 보일 수 있으니, 수치보다 "왜 줄어드는가"를 확인하는 데 의미를 둔다.
</details>

**V2.** `/api/slow?ms=3000`을 요청한 뒤 1초 만에 `Ctrl+C`로 끊으면 app 로그에 무엇이 남을까? h1.1(`:8443`)과 h2(`:9443`)에서 차이가 있을까?

<details><summary>예상 결과</summary>

app 로그에 `클라이언트가 먼저 끊음 (Broken pipe)`이 남는다. Tomcat이라면 `ClientAbortException: java.io.IOException: Broken pipe`다(이론 9-5). Nginx 액세스 로그에는 상태 코드 **499**가 남는다. h1.1에서는 클라이언트가 TCP 커넥션을 닫아 알리고, h2에서는 RST_STREAM 프레임으로 **그 스트림만** 취소한다. 다만 이 실습에서는 nginx가 업스트림 커넥션을 따로 관리하므로, 앱까지 취소가 전파되는 시점은 버퍼링 설정에 따라 다를 수 있다. 취소 전파가 중요한 서비스라면 이 경로를 반드시 직접 확인해야 한다.
</details>

**V3.** 9443 서버 블록의 `http2 on;`을 `http2 off;`로 바꾸고 reload한 뒤, 3-5의 h2load 명령(`--h1` 없이)을 그대로 실행하면?

<details><summary>예상 결과</summary>

h2load가 HTTP/2로 연결하지 못하고 실패하거나(ALPN에서 h2가 거부됨) HTTP/1.1로 떨어진다. 어느 쪽이든 결과 시간은 4초대로 돌아간다. 클라이언트가 h2를 "원한다"고 해서 쓰이는 게 아니라 **서버가 ALPN에서 수락해야** 쓰인다는 점을 보여준다(이론 9-1). 확인 후 `http2 on;`으로 되돌린다.
</details>

## 10. 정리 (Clean-up)

```text
$ cd "C:\Study\Network\네트워크 기초\labs\05-http"
$ docker compose down -v
```

- `down -v`: 컨테이너 5개, 네트워크 `ch05-lab_default`, 그리고 **인증서 볼륨 `ch05-lab_certs`**까지 삭제한다. 자체 서명 키를 남기지 않기 위해 `-v`를 쓴다.
- 4-3·5-2에서 `nginx/default.conf`를 편집했다면 원래 값(`http2_max_concurrent_streams 128`, 버퍼 지시어 제거)으로 되돌린다. `git diff`로 확인하면 빠르다.

남은 것 확인:

```text
$ docker ps -a --filter "name=ch05-lab"        # 아무것도 없어야 함
$ docker volume ls --filter "name=ch05-lab"    # 아무것도 없어야 함
```

이미지 삭제(선택):

```text
$ docker image rm ch05-client:1 nginx:1.27-alpine python:3.12-alpine3.20 alpine:3.20
```

호스트 설정은 바꾸지 않았으므로 원복할 것은 없다. 호스트 포트 8080·8443·9443을 쓰는 다른 프로세스가 있었다면 기동 시 충돌했을 것이므로, 그때는 compose의 `ports`에서 호스트 쪽 번호만 바꾼다.
