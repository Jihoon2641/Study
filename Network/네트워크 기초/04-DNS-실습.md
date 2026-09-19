---
장: 4
주제: DNS — 실습 (조회 경로, TTL, 캐시 계층, 부정 캐싱, ndots, 5초 지연)
분류: 네트워크 / 응용 계층 인프라
난이도: 중급
관련표준: RFC 1034, RFC 1035, RFC 2308
실습환경: Docker Desktop(WSL2 백엔드) 또는 Linux Docker, Linux 컨테이너(CoreDNS 1.11.3, Debian 12.7, Temurin JDK 21.0.4)
비용발생: 없음
작성일: 2026-09-19
선행지식: [DNS 이론](./04-DNS-이론.md)
---

# 04. DNS — 실습

> 관련 문서: [이론](./04-DNS-이론.md) · 실습 파일: [`labs/04-dns/`](./labs/04-dns/)

## 1. 실습 개요

로컬에 **권한 서버 → 캐시 리졸버 → 클라이언트(glibc) → JVM**으로 이어지는 작은 DNS 경로를 만든다. 레코드를 직접 바꾸면서 변경이 각 계층에 언제 반영되는지 관찰하고, 흔한 DNS 장애를 재현한 뒤 고친다.

| 단계 | 하는 일 | 확인하는 이론 |
|---|---|---|
| 3-1 | `dig` 응답 해부: 헤더, 플래그, 섹션, TTL | 5절 메시지 구조 |
| 3-2 | 리졸버를 거치면 TTL이 줄어든다, 권한 서버에는 첫 질의만 도달 | 4절 TTL의 의미 |
| 3-3 | CNAME 체인 | 4절 단계 6 |
| 3-4 | 실제 인터넷에서 루트부터 반복 조회(`dig +trace`) | 4절 단계 5 |
| 3-5 | `dig`와 앱(`getent`)의 조회 경로 차이 | 9-5 |
| 4-1 | 레코드 변경 → 권한 서버·리졸버·JVM의 반영 시점 차이, TTL 1일 레코드 | 9-1, 6-1 |
| 4-2 | 없는 이름을 먼저 조회 → 만든 뒤에도 NXDOMAIN | 9-4 |
| 4-3 | 쿠버네티스식 `ndots:5` → 조회 1번에 질의 8번 | 9-2 |
| 4-4 | 첫 nameserver가 죽어 있음 → 정확히 5초 지연 | 9-3 |
| 5 | JVM TTL 조정, ndots·FQDN, timeout 조정으로 하나씩 고치기 | 6-1, 5절 |

```text
                      ┌──────────────────────────┐
                      │ auth 10.53.0.10          │ 권한 서버 (존 파일 = 원본)
                      │ lab.internal, cluster.local │
                      └────────────▲─────────────┘
                                   │ 캐시 miss일 때만
                      ┌────────────┴─────────────┐
                      │ resolver 10.53.0.53      │ 캐시 리졸버 (TTL만큼 캐시)
                      └────▲──────────────▲──────┘
                           │              │
   ┌───────────────────────┴───┐   ┌──────┴────────────────────────────┐
   │ client 10.53.0.20 (glibc) │   │ java 10.53.0.30 (JVM 캐시 30초)    │
   │ dig / getent / tcpdump    │   │ DnsWatch.java                     │
   └───────────────────────────┘   └───────────────────────────────────┘
   blackhole 10.53.0.99: 질의를 받고 절대 응답하지 않는 서버 (4-4)
```

client와 java의 기본 `resolv.conf`는 Docker 내장 DNS(`127.0.0.11`)를 가리키고, 내장 DNS가 질의를 resolver로 넘긴다(compose의 `dns:`). 4-3·4-4에서는 client의 `resolv.conf`를 직접 바꿔 내장 DNS를 거치지 않고 resolver에 바로 묻는다.

## 2. 환경과 준비물

> 💰 **비용 발생 없음.** 로컬 Docker만 사용한다. 3-4만 인터넷의 실제 DNS 서버에 질의를 보낸다.

| 항목 | 요구 사항 |
|---|---|
| Windows 11 | Docker Desktop (WSL2 백엔드) |
| macOS / Linux | Docker Desktop 또는 Docker Engine + Compose 플러그인. 명령 동일 |
| 인터넷 | 이미지 다운로드. 3-4는 외부 UDP 53 허용 필요(회사망에서는 막혀 있을 수 있음) |
| 편집기 | 존 파일은 LF 줄바꿈 유지 권장(VS Code 우하단 `CRLF`/`LF`) |

호스트 DNS 설정은 바꾸지 않는다. `resolv.conf` 교체는 client 컨테이너 안에서만 한다.

**프롬프트 표기**

| 표기 | 실행 위치 |
|---|---|
| `$` | 호스트 터미널 (PowerShell 또는 WSL2 bash) |
| `[client] #` | `$ docker compose exec client bash` |
| `[java] #` | `$ docker compose exec java bash` |

### 사전 점검과 기동

실습 대역 `10.53.0.0/24`가 회사망·VPN과 겹치지 않는지 확인한다([2장 실습](./02-IP-서브넷-CIDR-실습.md) 사전 점검과 같은 방법).

```text
$ cd "C:\Study\Network\네트워크 기초\labs\04-dns"
$ docker compose up -d --build
$ docker compose ps
$ docker compose logs auth
```

예시 출력 (`docker compose logs auth`, 버전 문자열은 다를 수 있음):

```text
auth-1  | lab.internal.:53
auth-1  | cluster.local.:53
auth-1  | CoreDNS-1.11.3
auth-1  | linux/amd64, go1.21.x, ...
```

로그는 실습 내내 계속 본다. 터미널 하나를 이것 전용으로 둔다.

```text
$ docker compose logs -f auth resolver
```

client에서 원래 `resolv.conf`를 백업해 둔다.

```text
[client] # cp /etc/resolv.conf /tmp/resolv.docker.conf
[client] # cat /etc/resolv.conf
nameserver 127.0.0.11
options ndots:0
```

## 3. 관찰 먼저

### 3-1. `dig` 응답 해부 — 권한 서버에 직접

```text
[client] # dig @10.53.0.10 api.lab.internal
```

예시 출력 (`id`, `Query time`, `WHEN`은 매번 다름):

```text
; <<>> DiG 9.18.x-Debian <<>> @10.53.0.10 api.lab.internal
; (1 server found)
;; global options: +cmd
;; Got answer:
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 40213
;; flags: qr aa rd; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 1
;; WARNING: recursion requested but not available

;; OPT PSEUDOSECTION:
; EDNS: version: 0, flags:; udp: 1232
;; QUESTION SECTION:
;api.lab.internal.		IN	A

;; ANSWER SECTION:
api.lab.internal.	30	IN	A	10.53.0.101

;; Query time: 1 msec
;; SERVER: 10.53.0.10#53(10.53.0.10) (UDP)
;; WHEN: ...
;; MSG SIZE  rcvd: 77
```

| 출력 | 의미 | 이론 |
|---|---|---|
| `status: NOERROR` | RCODE 0 | 5절 헤더 |
| `flags: qr aa rd` | 응답(qr), **권한 응답(aa)**, 클라이언트가 재귀 요청함(rd). **`ra`가 없다** | 3절 권한 서버 |
| `WARNING: recursion requested but not available` | 권한 서버는 재귀 조회를 해주지 않는다 | 7절 역할 비교 |
| `EDNS: ... udp: 1232` | EDNS(0) OPT 레코드. 이 크기까지 UDP로 받겠다는 광고 (값은 서버·dig 버전마다 다를 수 있음) | 5절 전송 |
| `api.lab.internal. 30 IN A 10.53.0.101` | 이름, **TTL 30**, 클래스, 타입, 데이터. 이름 끝의 점은 FQDN | 3절 |
| `(UDP)` | UDP 53으로 질의 | 5절 |

같은 명령을 몇 번 반복해도 TTL은 항상 30이다. 권한 서버는 원본을 주므로 줄어들지 않는다.

### 3-2. 리졸버를 거치면 TTL이 줄어든다

```text
[client] # dig @10.53.0.53 api.lab.internal +noall +answer +comments | grep -E 'flags|IN\s+A'
[client] # sleep 7; dig @10.53.0.53 api.lab.internal +noall +answer
[client] # sleep 7; dig @10.53.0.53 api.lab.internal +noall +answer
```

예시 출력:

```text
;; flags: qr rd ra; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 1
api.lab.internal.	30	IN	A	10.53.0.101
api.lab.internal.	23	IN	A	10.53.0.101
api.lab.internal.	16	IN	A	10.53.0.101
```

- 플래그가 `qr rd ra`로 바뀌었다. `aa`가 없고 `ra`(재귀 가능)가 있다. **리졸버의 캐시에서 나온 답**이다.
- TTL이 30 → 23 → 16으로 줄어든다. 리졸버는 "남은 캐시 시간"을 TTL로 돌려준다. 이 응답을 받은 다음 계층도 최대 16초만 캐시할 수 있다.

`logs -f` 터미널을 본다. 예시 출력(주소·포트·ID·시간은 다름):

```text
resolver-1  | [INFO] 10.53.0.20:52110 - 11807 "A IN api.lab.internal. udp 57 true 1232" NOERROR qr,rd,ra 77 0.0012s
auth-1      | [INFO] 10.53.0.53:41822 - 30871 "A IN api.lab.internal. udp 57 true 2048" NOERROR qr,aa,rd 77 0.0001s
resolver-1  | [INFO] 10.53.0.20:39214 - 50122 "A IN api.lab.internal. udp 57 true 1232" NOERROR qr,rd,ra 77 0.0001s
resolver-1  | [INFO] 10.53.0.20:44871 - 6618 "A IN api.lab.internal. udp 57 true 1232" NOERROR qr,rd,ra 77 0.0001s
```

- resolver는 질의 3번을 모두 받았다.
- **auth에는 첫 질의 1번만 도달했다.** 나머지는 리졸버 캐시에서 끝났다. 캐시가 루트·TLD·권한 서버의 부하를 막는 원리다(이론 2절).
- 30초가 지난 뒤 다시 물으면 auth에 한 번 더 도달한다.

로그 형식: `[INFO] 클라이언트IP:포트 - 질의ID "타입 클래스 이름 프로토콜 크기 DO비트 버퍼크기" RCODE 플래그 응답크기 소요시간`.

### 3-3. CNAME 체인

```text
[client] # dig @10.53.0.53 db.lab.internal +noall +answer
```

예시 출력:

```text
db.lab.internal.	10	IN	CNAME	db-primary.lab.internal.
db-primary.lab.internal. 10	IN	A	10.53.0.111
```

`db`는 IP가 아니라 다른 이름을 가리키고, 리졸버가 그 이름까지 풀어서 함께 돌려줬다. RDS·Aurora 엔드포인트가 이 구조다. **CNAME과 A는 각자의 TTL로 캐시된다.** 4-1에서 이 CNAME의 대상을 바꿔 장애 조치를 흉내 낸다.

### 3-4. 루트부터 직접: `dig +trace`

실제 인터넷 DNS에 질의한다. 외부 UDP 53이 막힌 네트워크에서는 실패할 수 있다. 그 경우 이 단계는 건너뛴다.

```text
[client] # dig +trace www.example.com
```

예시 출력 (서버 이름·IP·TTL·소요 시간은 매번 다름, DNSSEC 레코드 줄은 생략):

```text
.			518400	IN	NS	a.root-servers.net.
.			518400	IN	NS	b.root-servers.net.
...
;; Received 239 bytes from 127.0.0.11#53(127.0.0.11) in 20 ms

com.			172800	IN	NS	a.gtld-servers.net.
com.			172800	IN	NS	b.gtld-servers.net.
...
;; Received 1170 bytes from 198.41.0.4#53(a.root-servers.net) in 150 ms

example.com.		172800	IN	NS	a.iana-servers.net.
example.com.		172800	IN	NS	b.iana-servers.net.
;; Received 330 bytes from 192.5.6.30#53(a.gtld-servers.net) in 180 ms

www.example.com.	300	IN	A	93.184.x.x
;; Received 56 bytes from 199.43.135.53#53(a.iana-servers.net) in 170 ms
```

| 블록 | 누가 답했나 | 준 것 |
|---|---|---|
| 1 | 기본 리졸버 | 루트 서버 목록 (시작점) |
| 2 | **루트 서버** | 답이 아니라 **위임**: "com은 gtld-servers에게 물어라" |
| 3 | **com TLD 서버** | 위임: "example.com은 iana-servers에게 물어라" |
| 4 | **example.com 권한 서버** | 실제 A 레코드 |

재귀 리졸버가 캐시 없이 하는 일이 바로 이것이다(이론 4절 시퀀스 다이어그램 단계 5). 루트·TLD의 NS 레코드 TTL이 172800초(2일) 이상인 것도 눈여겨본다. 윗단 위임 정보가 오래 캐시되니 루트에 질의가 몰리지 않는다.

### 3-5. `dig`와 애플리케이션은 다른 길로 조회한다

`getent ahosts`는 glibc의 `getaddrinfo()`를 부른다. Java가 쓰는 경로와 같다(NSS → `/etc/hosts` → DNS).

```text
[client] # getent ahosts api.lab.internal
[client] # echo "10.53.0.199 api.lab.internal" >> /etc/hosts
[client] # getent ahosts api.lab.internal
[client] # dig +short api.lab.internal
```

예시 출력:

```text
10.53.0.101     STREAM api.lab.internal
10.53.0.101     DGRAM
10.53.0.101     RAW

10.53.0.199     STREAM api.lab.internal
10.53.0.199     DGRAM
10.53.0.199     RAW

10.53.0.101
```

- `/etc/hosts`에 한 줄 넣자 **앱 경로(`getent`)는 .199**를 주는데 **`dig`는 여전히 .101**이다. `dig`는 `/etc/hosts`를 보지 않는다.
- "dig로는 맞는데 앱은 엉뚱한 곳으로 간다"(이론 9-5)의 가장 흔한 원인이다.
- `STREAM`/`DGRAM`/`RAW` 세 줄은 같은 주소의 소켓 타입별 결과다.

원복:

```text
[client] # sed -i '/10.53.0.199 api.lab.internal/d' /etc/hosts
```

## 4. 문제 상황 재현

### 4-1. 레코드를 바꿨는데 반영이 늦다 — 캐시 계층별 반영 시점

**준비 (변경 전 캐시 채우기).** 터미널 하나에서 JVM 감시를 시작한다.

```text
[java] # java DnsWatch.java api.lab.internal
```

예시 출력:

```text
security  networkaddress.cache.ttl          = null
security  networkaddress.cache.negative.ttl = 10
system    sun.net.inetaddr.ttl              = null
java.version                                = 21.0.4
[10:00:00] api.lab.internal -> 10.53.0.101  (4,210 us)
[10:00:02] api.lab.internal -> 10.53.0.101  (41 us)
[10:00:04] api.lab.internal -> 10.53.0.101  (18 us)
...
[10:00:30] api.lab.internal -> 10.53.0.101  (1,102 us)
```

- `networkaddress.cache.ttl = null` → 설정 없음 → **기본 30초**. `negative.ttl = 10`은 JDK의 `java.security` 파일에 들어 있는 기본값이다.
- 걸린 시간이 대부분 수십 us(캐시 hit)이고, **약 30초마다 한 번씩 ms 단위로 튄다.** 그때가 JVM 캐시가 만료되어 OS 리졸버까지 다녀온 순간이다. resolver 로그에도 그 시각에만 질의가 찍힌다.

client에서 다른 이름들도 리졸버 캐시에 넣어 둔다. `new.lab.internal`은 아직 **없는** 이름이다.

```text
[client] # for n in static db new; do dig @10.53.0.53 $n.lab.internal +noall +answer +authority +comments | grep -E 'status|IN'; done
```

예시 출력:

```text
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 1201
static.lab.internal.	86400	IN	A	10.53.0.120
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 5540
db.lab.internal.	10	IN	CNAME	db-primary.lab.internal.
db-primary.lab.internal. 10	IN	A	10.53.0.111
;; ->>HEADER<<- opcode: QUERY, status: NXDOMAIN, id: 7710
lab.internal.		60	IN	SOA	ns1.lab.internal. admin.lab.internal. 2026091901 3600 600 86400 60
```

`new`의 응답은 NXDOMAIN이고, AUTHORITY 섹션의 SOA TTL 60이 **"이 부정 응답을 60초 캐시하라"**는 뜻이다(4-2에서 이어서 본다).

**변경.** 존 파일을 v2로 교체한다(serial 2026091901 → 2026091902, api .101→.102, static .120→.121, db CNAME primary→replica, new 추가).

```text
$ docker compose exec client cp /zones/db.lab.internal.v2 /zones/db.lab.internal
```

2초 안에 auth 로그에 재적재 메시지가 나온다(예시, 문구는 버전별로 다를 수 있음):

```text
auth-1  | [INFO] plugin/file: Successfully reloaded zone "lab.internal." in "/zones/db.lab.internal" with 2026091902 SOA serial
```

**관찰.** 권한 서버와 리졸버를 나란히 본다. 10초 간격으로 몇 번 반복한다.

```text
[client] # for n in api static db new; do printf "%-8s auth=%-28s resolver=%s\n" $n "$(dig @10.53.0.10 +short $n.lab.internal | tr '\n' ' ')" "$(dig @10.53.0.53 +short $n.lab.internal | tr '\n' ' ')"; done
```

예시 출력 — 변경 직후:

```text
api      auth=10.53.0.102                  resolver=10.53.0.101
static   auth=10.53.0.121                  resolver=10.53.0.120
db       auth=db-replica.lab.internal.     resolver=db-primary.lab.internal. 10.53.0.111
new      auth=10.53.0.150                  resolver=
```

예시 출력 — 약 60초 뒤:

```text
api      auth=10.53.0.102                  resolver=10.53.0.102
static   auth=10.53.0.121                  resolver=10.53.0.120
db       auth=db-replica.lab.internal.     resolver=db-replica.lab.internal. 10.53.0.112
new      auth=10.53.0.150                  resolver=10.53.0.150
```

(`auth` 열의 `db`는 권한 서버가 `lab.internal` 존 안의 CNAME 대상을 함께 줄 수도 있다. 출력 형태는 다를 수 있다.)

| 이름 | 레코드 TTL | 리졸버 반영 | 해석 |
|---|---|---|---|
| `db` | 10 | 약 10초 이내 | TTL이 짧은 CNAME. 장애 조치용 엔드포인트가 짧은 TTL을 쓰는 이유 |
| `api` | 30 | 최대 30초 | 캐시된 남은 TTL만큼 |
| `new` | (없음 → SOA 60) | 최대 60초 | 부정 캐시. 4-2 |
| `static` | 86400 | **최대 하루** | TTL을 미리 낮추지 않은 레코드. 권한 서버를 바꿔도 캐시가 만료될 때까지 옛 IP |

`static`의 남은 TTL을 확인해본다.

```text
[client] # dig @10.53.0.53 static.lab.internal +noall +answer
static.lab.internal.	86290	IN	A	10.53.0.120
```

이론 10절 "이전 전에 옛 TTL만큼 미리 TTL을 낮춘다"가 필요한 이유다. 지금 이 레코드의 TTL을 60으로 바꿔도, 이미 캐시된 항목은 86290초 뒤에야 만료된다.

**JVM 터미널을 본다.** 예시 출력:

```text
[10:01:10] api.lab.internal -> 10.53.0.101  (22 us)     ← 변경 시각
[10:01:12] api.lab.internal -> 10.53.0.101  (19 us)
...
[10:01:30] api.lab.internal -> 10.53.0.101  (980 us)    ← JVM 캐시 만료, 리졸버에 물었으나 리졸버 캐시가 아직 옛 값
...
[10:01:40] (리졸버 캐시 만료)
...
[10:02:00] api.lab.internal -> 10.53.0.102  (1,050 us)  ← 다음 JVM 캐시 만료 때 비로소 새 값
```

- 권한 서버 변경부터 JVM 반영까지 **최대 약 60초**가 걸린다 = 리졸버 캐시(최대 30초) + JVM 캐시(30초). 레코드 TTL이 30초라고 해서 30초 안에 바뀌지 않는다.
- 실제 앱이라면 여기에 **이미 맺은 커넥션의 수명**이 더해진다(이론 6-4, 9-1).
- 정확한 시각은 각 캐시가 언제 채워졌는지에 따라 달라진다. 이 시나리오의 핵심은 "각 계층 캐시 시간의 합"이라는 구조다.

### 4-2. 방금 만든 레코드가 "없다" — 부정 캐싱

4-1에서 이미 재현됐다. 변경 직후 `new`는 권한 서버에 있는데 리졸버는 NXDOMAIN을 줬다. 자세히 본다. 변경 후 60초가 지났다면 다른 없는 이름으로 다시 해본다.

```text
[client] # dig @10.53.0.53 new2.lab.internal +noall +comments +authority | grep -E 'status|SOA'
```

이제 호스트에서 **활성 존 파일** `zones/db.lab.internal`(지금은 v2 내용)을 편집기로 열어 `new2` 레코드를 추가하고 serial을 올린다. 템플릿인 `.v1`, `.v2` 파일은 건드리지 않는다.

```text
                    2026091903 ; serial        ← 02를 03으로
...
new2        60    IN A      10.53.0.151      ← 맨 아래에 추가
```

```text
[client] # dig @10.53.0.10 +short new2.lab.internal
10.53.0.151
[client] # dig @10.53.0.53 new2.lab.internal +noall +comments +authority | grep -E 'status|SOA'
;; ->>HEADER<<- opcode: QUERY, status: NXDOMAIN, id: 3321
lab.internal.		41	IN	SOA	ns1.lab.internal. admin.lab.internal. 2026091902 3600 600 86400 60
```

- 권한 서버는 `10.53.0.151`을 주는데 리졸버는 여전히 `NXDOMAIN`이다.
- 부정 응답에 딸려 온 SOA의 serial은 **옛 값(…02)**이고 TTL(41)이 줄어드는 중이다. 부정 캐시에서 나온 응답이라는 증거다.
- TTL이 0이 되면 리졸버가 다시 묻고 새 레코드를 받는다. 캐시 시간은 SOA의 마지막 필드(MINIMUM = 60)와 SOA 레코드 TTL 중 작은 값이다(RFC 2308).

JVM에서도 해본다. JVM 부정 캐시는 기본 10초다.

```text
[java] # java DnsWatch.java new3.lab.internal
```

실행 중에 존 파일에 `new3`를 추가하고 serial을 올리면(…04), 리졸버 부정 캐시 60초가 지난 뒤 JVM 부정 캐시(최대 10초)가 지나야 성공으로 바뀐다. 예시 출력:

```text
[10:05:00] new3.lab.internal -> UnknownHostException: new3.lab.internal: Name or service not known  (3,120 us)
[10:05:02] new3.lab.internal -> UnknownHostException: new3.lab.internal  (15 us)
...
```

두 번째 줄부터 메시지 뒤의 원인 문구(`Name or service not known`)가 사라지고 시간도 수십 us다. **JVM 부정 캐시에서 나온 예외**라는 신호다(이론 6-2).

### 4-3. 쿠버네티스식 `ndots:5` — 조회 1번에 질의 8번

client의 `resolv.conf`를 쿠버네티스 Pod와 같은 형태로 바꾼다.

```text
[client] # cp /lab/resolv/k8s.conf /etc/resolv.conf
[client] # cat /etc/resolv.conf
nameserver 10.53.0.53
search default.svc.cluster.local svc.cluster.local cluster.local
options ndots:5

[client] # getent ahosts pay.partner.lab.internal
```

`logs -f` 터미널의 resolver 로그. 예시 출력(순서는 A/AAAA가 섞여 나올 수 있음):

```text
resolver-1 | ... "A IN pay.partner.lab.internal.default.svc.cluster.local. udp ..." NXDOMAIN ...
resolver-1 | ... "AAAA IN pay.partner.lab.internal.default.svc.cluster.local. udp ..." NXDOMAIN ...
resolver-1 | ... "A IN pay.partner.lab.internal.svc.cluster.local. udp ..." NXDOMAIN ...
resolver-1 | ... "AAAA IN pay.partner.lab.internal.svc.cluster.local. udp ..." NXDOMAIN ...
resolver-1 | ... "A IN pay.partner.lab.internal.cluster.local. udp ..." NXDOMAIN ...
resolver-1 | ... "AAAA IN pay.partner.lab.internal.cluster.local. udp ..." NXDOMAIN ...
resolver-1 | ... "A IN pay.partner.lab.internal. udp ..." NOERROR ...
resolver-1 | ... "AAAA IN pay.partner.lab.internal. udp ..." NOERROR ...
```

세어 본다.

```text
$ docker compose logs resolver --since 30s | Select-String "pay.partner" | Measure-Object     # PowerShell
$ docker compose logs resolver --since 30s | grep -c "pay.partner"                           # bash
```

- `pay.partner.lab.internal`은 점이 3개로 `ndots:5`보다 적다. glibc는 **검색 도메인 3개를 먼저 붙여 보고**(6번 NXDOMAIN), 마지막에야 원래 이름을 묻는다(2번).
- 조회 1번 = 질의 **8번**, 그중 **6번이 쓸모없는 NXDOMAIN**이다. 마지막 AAAA는 NOERROR지만 답이 비어 있다(NODATA, IPv4만 있으므로).
- `dig pay.partner.lab.internal`은 기본으로 검색 도메인을 쓰지 않으므로 질의 1번만 나간다. `dig +search`로 하면 앱과 비슷해진다.

### 4-4. 첫 nameserver가 죽어 있다 — 정확히 5초

```text
[client] # cp /lab/resolv/dead.conf /etc/resolv.conf
[client] # cat /etc/resolv.conf
nameserver 10.53.0.99
nameserver 10.53.0.53

[client] # time getent ahosts api.lab.internal
```

예시 출력:

```text
10.53.0.102     STREAM api.lab.internal
10.53.0.102     DGRAM
10.53.0.102     RAW

real	0m5.012s
user	0m0.001s
sys	0m0.003s
```

- 결과는 맞다. 다만 **5초**가 걸렸다. 첫 서버(blackhole)에 A·AAAA를 보내고 `timeout` 기본값 5초를 기다린 뒤 두 번째 서버로 넘어갔다.
- `getent`를 다시 실행해도 매번 5초다. glibc는 "첫 서버가 죽었다"는 사실을 프로세스 간에 기억하지 않는다. 오래 사는 JVM 프로세스 안에서도 조회마다 반복된다(glibc 내부 상태에 따라 다를 수 있음, 확인 필요).

tcpdump로 확인한다.

```text
[client] # tcpdump -nni eth0 'udp port 53' & sleep 1; getent ahosts api.lab.internal > /dev/null; sleep 1; kill %1
```

예시 출력:

```text
10:10:00.000100 IP 10.53.0.20.51234 > 10.53.0.99.53: 1234+ A? api.lab.internal. (34)
10:10:00.000150 IP 10.53.0.20.51234 > 10.53.0.99.53: 5678+ AAAA? api.lab.internal. (34)
10:10:05.005300 IP 10.53.0.20.44211 > 10.53.0.53.53: 1234+ A? api.lab.internal. (34)
10:10:05.005350 IP 10.53.0.20.44211 > 10.53.0.53.53: 5678+ AAAA? api.lab.internal. (34)
10:10:05.006100 IP 10.53.0.53.53 > 10.53.0.20.44211: 1234* 1/0/0 A 10.53.0.102 (66)
10:10:05.006200 IP 10.53.0.53.53 > 10.53.0.20.44211: 5678* 0/1/0 (91)
```

- 첫 두 줄: .99로 보낸 질의에 응답이 없다.
- 정확히 **5초 뒤** .53으로 같은 질의를 다시 보냈다. 타임스탬프 차이가 원인을 확정한다.
- `+`는 재귀 요청(RD), `1/0/0`은 답/권한/추가 섹션 레코드 수다. AAAA의 `0/1/0`은 답 없이 SOA만 있는 NODATA다.

이 5초는 pgJDBC `connectTimeout`이나 HTTP 클라이언트의 connect 타임아웃에 잡히지 않는다(이론 6-3).

## 5. 개선된 구성

### 5-1. JVM 캐시 TTL을 명시하고 짧게

4-1의 DnsWatch를 `Ctrl+C`로 멈추고 JVM TTL 5초로 다시 실행한다. 실무에서는 `Security.setProperty`(이론 6-1)나 `-Djava.security.properties`를 쓰고, 여기서는 가장 짧은 방법인 시스템 속성을 쓴다.

```text
[java] # java -Dsun.net.inetaddr.ttl=5 DnsWatch.java api.lab.internal
```

그다음 존을 v1으로 되돌린다(serial이 달라지므로 재적재된다).

```text
$ docker compose exec client cp /zones/db.lab.internal.v1 /zones/db.lab.internal
```

예시 출력:

```text
system    sun.net.inetaddr.ttl              = 5
[10:20:00] api.lab.internal -> 10.53.0.102  (1,020 us)
[10:20:02] api.lab.internal -> 10.53.0.102  (20 us)
[10:20:04] api.lab.internal -> 10.53.0.102  (18 us)
[10:20:06] api.lab.internal -> 10.53.0.102  (950 us)   ← 5초마다 OS에 다시 물음
...
[10:20:3x] api.lab.internal -> 10.53.0.101  (990 us)   ← 리졸버 캐시(최대 30초) 만료 후 5초 이내 반영
```

- 반영 시간이 "리졸버 캐시 + 30초"에서 "리졸버 캐시 + 5초"로 줄었다.
- 대가: OS 리졸버 호출이 6배 늘었다. resolver 로그에서 질의 빈도를 비교해본다. 조회가 잦은 앱에서 TTL을 0으로 두면 리졸버 부하와 장애 전파 위험이 커진다(이론 6-1, 9-7).
- **커넥션 풀의 기존 커넥션에는 이 설정이 아무 효과가 없다.** 이론 Q4를 다시 읽어본다.

### 5-2. `ndots` 낮추기 또는 FQDN 사용

```text
[client] # cp /lab/resolv/k8s-ndots2.conf /etc/resolv.conf
[client] # getent ahosts pay.partner.lab.internal > /dev/null
[client] # cp /lab/resolv/k8s.conf /etc/resolv.conf
[client] # getent ahosts pay.partner.lab.internal. > /dev/null          # 끝에 점 = FQDN
```

resolver 로그 예시:

```text
# ndots:2 → 점 3개 ≥ 2 이므로 절대 이름부터
... "A IN pay.partner.lab.internal. udp ..." NOERROR ...
... "AAAA IN pay.partner.lab.internal. udp ..." NOERROR ...

# ndots:5 + FQDN → 끝의 점 때문에 검색 도메인을 아예 쓰지 않음
... "A IN pay.partner.lab.internal. udp ..." NOERROR ...
... "AAAA IN pay.partner.lab.internal. udp ..." NOERROR ...
```

두 방법 모두 질의가 8번에서 2번으로 줄었다. 쿠버네티스에서는 Pod 스펙의 `dnsConfig.options`로 `ndots`를 바꾼다(23장). FQDN 방식은 HTTP `Host` 헤더·TLS SNI에 점이 섞일 수 있어 라이브러리 동작을 확인해야 한다(이론 9-2).

`ndots:2`의 부작용도 확인한다. 클러스터 내부 서비스를 `svc.ns`(점 1개)처럼 부르면 여전히 검색 도메인이 붙어 잘 동작한다. 반면 `a.b.c`처럼 점이 2개 이상인 **내부** 짧은 이름은 절대 이름부터 시도하므로 외부로 먼저 새어 나간다. 팀 내 명명 규칙과 함께 정한다.

### 5-3. 죽은 서버 제거, timeout 단축

```text
[client] # cp /lab/resolv/dead-fast.conf /etc/resolv.conf
[client] # time getent ahosts api.lab.internal > /dev/null
[client] # cp /lab/resolv/direct.conf /etc/resolv.conf
[client] # time getent ahosts api.lab.internal > /dev/null
```

예시 출력:

```text
real	0m1.008s     ← timeout:1 → 1초 뒤 다음 서버
real	0m0.004s     ← 죽은 서버 제거 → 수 ms
```

- `timeout:1`은 응급 처치다. 응답이 1초 이상 걸리는 정상 상황(원거리 리졸버, 부하)에서 불필요한 재질의를 만들 수 있다.
- 근본 해결은 **응답하지 않는 서버를 목록에서 빼는 것**이다. 운영에서는 DHCP·cloud-init·쿠버네티스가 `resolv.conf`를 만들므로 그 원천을 고친다.

마지막으로 원래 설정을 복원한다.

```text
[client] # cp /tmp/resolv.docker.conf /etc/resolv.conf
```

## 6. 전체 구성 파일

```text
labs/04-dns/
├── docker-compose.yml
├── coredns/
│   ├── Corefile.auth
│   ├── Corefile.resolver
│   └── Corefile.blackhole
├── zones/
│   ├── db.lab.internal         ← 활성 파일 (처음엔 v1과 같음)
│   ├── db.lab.internal.v1
│   ├── db.lab.internal.v2
│   └── db.cluster.local
├── client/
│   ├── Dockerfile
│   └── resolv/
│       ├── direct.conf
│       ├── k8s.conf
│       ├── k8s-ndots2.conf
│       ├── dead.conf
│       └── dead-fast.conf
└── java/
    └── DnsWatch.java
```

### `docker-compose.yml`

```yaml
# 4장 실습: DNS 조회 경로, TTL, 캐시 계층, 부정 캐싱, ndots, 5초 지연
#
#   auth      10.53.0.10  권한 서버 (CoreDNS file 플러그인, 존: lab.internal / cluster.local)
#   resolver  10.53.0.53  캐시 리졸버 (CoreDNS forward + cache)
#   blackhole 10.53.0.99  질의를 받고 절대 응답하지 않는 "죽은" DNS 서버 (CoreDNS erratic)
#   client    10.53.0.20  Debian(glibc) + dig/getent/tcpdump
#   java      10.53.0.30  Temurin JDK 21: JVM InetAddress 캐시 관찰
#
# 10.53.0.0/24가 회사망·VPN과 겹치면 이 파일, Corefile.resolver, client/resolv/*.conf의 "10.53.0." 을 함께 바꾼다.
name: ch04-lab

networks:
  dnsnet:
    ipam:
      config:
        - subnet: 10.53.0.0/24
          gateway: 10.53.0.1

services:
  auth:
    image: coredns/coredns:1.11.3
    command: ["-conf", "/etc/coredns/Corefile"]
    volumes:
      - ./coredns/Corefile.auth:/etc/coredns/Corefile:ro
      - ./zones:/zones:ro
    networks:
      dnsnet: { ipv4_address: 10.53.0.10 }

  resolver:
    image: coredns/coredns:1.11.3
    command: ["-conf", "/etc/coredns/Corefile"]
    volumes:
      - ./coredns/Corefile.resolver:/etc/coredns/Corefile:ro
    networks:
      dnsnet: { ipv4_address: 10.53.0.53 }

  blackhole:
    image: coredns/coredns:1.11.3
    command: ["-conf", "/etc/coredns/Corefile"]
    volumes:
      - ./coredns/Corefile.blackhole:/etc/coredns/Corefile:ro
    networks:
      dnsnet: { ipv4_address: 10.53.0.99 }

  client:
    build: ./client
    image: ch04-client:1
    # NET_RAW/NET_ADMIN: tcpdump
    cap_add: [NET_ADMIN, NET_RAW]
    init: true
    # Docker 내장 DNS(127.0.0.11)가 컨테이너 이름 외의 질의를 이 서버로 넘긴다
    dns: [10.53.0.53]
    volumes:
      - ./client/resolv:/lab/resolv:ro
      # 읽기/쓰기: 실습 4-1에서 존 파일을 v2로 교체한다 (auth는 같은 디렉터리를 읽기 전용으로 본다)
      - ./zones:/zones
    command: ["sleep", "infinity"]
    networks:
      dnsnet: { ipv4_address: 10.53.0.20 }

  java:
    # glibc 기반(Ubuntu jammy) JDK. 버전 고정: JVM DNS 캐시 기본값이 버전에 따라 달라질 수 있으므로
    image: eclipse-temurin:21.0.4_7-jdk-jammy
    init: true
    dns: [10.53.0.53]
    working_dir: /lab
    volumes:
      - ./java:/lab:ro
    command: ["sleep", "infinity"]
    networks:
      dnsnet: { ipv4_address: 10.53.0.30 }
```

### `coredns/Corefile.auth`

```text
# 권한 서버: 존 파일의 원본 데이터로만 응답한다. 재귀 조회도, 캐시도 하지 않는다.
lab.internal:53 {
    file /zones/db.lab.internal {
        # 2초마다 파일을 확인하고 SOA serial이 바뀌었으면 다시 읽는다 (기본 1m)
        reload 2s
    }
    log
    errors
}

# 쿠버네티스 검색 도메인 흉내용 빈 존: 여기 아래 이름은 전부 NXDOMAIN
cluster.local:53 {
    file /zones/db.cluster.local
    log
    errors
}
```

### `coredns/Corefile.resolver`

```text
# 캐시 리졸버: 실습 존은 권한 서버(auth)로 넘기고, 받은 응답을 캐시한다.
lab.internal:53 cluster.local:53 {
    # forward는 IP만 받는다 (컨테이너 이름 불가) → auth를 고정 IP로 둔 이유
    forward . 10.53.0.10
    # 캐시 최대 3600초. 실제 캐시 시간 = min(레코드 TTL, 3600). 부정 응답도 캐시한다
    cache 3600
    log
    errors
}

# 그 밖의 이름(example.com 등)은 컨테이너의 기본 리졸버(Docker 내장 DNS → 호스트)로
.:53 {
    forward . /etc/resolv.conf
    cache 3600
    log
    errors
}
```

### `coredns/Corefile.blackhole`

```text
# "죽은" DNS 서버: UDP 53은 열려 있어 ICMP 에러도 돌려주지 않지만, 어떤 질의에도 응답하지 않는다.
# (포트가 닫혀 있으면 ICMP Port Unreachable이 돌아가 클라이언트가 즉시 다음 서버로 넘어가므로 재현이 안 된다)
.:53 {
    erratic {
        # AMOUNT개 중 1개를 버림. 1이면 전부 버림
        drop 1
    }
}
```

### `zones/db.lab.internal.v1`

`zones/db.lab.internal`(활성 파일)은 처음에 이 파일과 내용이 같다.

```text
; lab.internal 존 — v1 (초기 상태)
; 실습 4-1에서 db.lab.internal.v2 로 교체한다. 활성 파일은 db.lab.internal.
$ORIGIN lab.internal.
$TTL 3600
@           IN SOA  ns1.lab.internal. admin.lab.internal. (
                    2026091901 ; serial  - 이 값이 바뀌어야 CoreDNS가 파일을 다시 읽는다
                    3600       ; refresh
                    600        ; retry
                    86400      ; expire
                    60 )       ; minimum - 부정 응답(NXDOMAIN) 캐시 시간 (RFC 2308)
            IN NS   ns1.lab.internal.
ns1         IN A    10.53.0.10

; TTL 30초: 자주 바뀔 수 있는 서비스 주소
api         30    IN A      10.53.0.101
; TTL 1일: "이전 전에 TTL을 미리 낮추지 않은" 레코드
static      86400 IN A      10.53.0.120
; DB 엔드포인트: RDS/Aurora처럼 CNAME으로 실제 인스턴스를 가리킴
db          10    IN CNAME  db-primary.lab.internal.
db-primary  10    IN A      10.53.0.111
db-replica  10    IN A      10.53.0.112
; ndots 실습용 (점이 3개인 이름): pay.partner.lab.internal
pay.partner 60    IN A      10.53.0.140
; new.lab.internal 은 v1에 없다 → 부정 캐싱 실습
```

### `zones/db.lab.internal.v2`

```text
; lab.internal 존 — v2 (변경 후)
; v1 대비 변경: serial 증가, api/static IP 변경, db CNAME을 replica로 전환(장애 조치 흉내), new 레코드 추가
$ORIGIN lab.internal.
$TTL 3600
@           IN SOA  ns1.lab.internal. admin.lab.internal. (
                    2026091902 ; serial  - v1(…01)과 달라야 CoreDNS가 다시 읽는다
                    3600       ; refresh
                    600        ; retry
                    86400      ; expire
                    60 )       ; minimum - 부정 응답(NXDOMAIN) 캐시 시간 (RFC 2308)
            IN NS   ns1.lab.internal.
ns1         IN A    10.53.0.10

api         30    IN A      10.53.0.102
static      86400 IN A      10.53.0.121
db          10    IN CNAME  db-replica.lab.internal.
db-primary  10    IN A      10.53.0.111
db-replica  10    IN A      10.53.0.112
pay.partner 60    IN A      10.53.0.140
new         60    IN A      10.53.0.150
```

### `zones/db.cluster.local`

```text
; 쿠버네티스 검색 도메인 흉내용 빈 존. SOA와 NS만 있으므로 이 아래 이름은 전부 NXDOMAIN.
$ORIGIN cluster.local.
$TTL 3600
@   IN SOA  ns1.lab.internal. admin.lab.internal. 2026091901 3600 600 86400 30
    IN NS   ns1.lab.internal.
```

### `client/Dockerfile`

```dockerfile
# glibc 리졸버 동작을 보기 위해 Debian 기반 (Alpine은 musl이라 동작이 다름 → 변형 실습 V2)
# dnsutils: dig / iproute2: ss, ip / tcpdump: 53번 포트 관찰
FROM debian:12.7-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends dnsutils iproute2 tcpdump \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /lab
```

### `client/resolv/direct.conf`

```text
nameserver 10.53.0.53
```

### `client/resolv/k8s.conf`

```text
nameserver 10.53.0.53
search default.svc.cluster.local svc.cluster.local cluster.local
options ndots:5
```

### `client/resolv/k8s-ndots2.conf`

```text
nameserver 10.53.0.53
search default.svc.cluster.local svc.cluster.local cluster.local
options ndots:2
```

### `client/resolv/dead.conf`

```text
nameserver 10.53.0.99
nameserver 10.53.0.53
```

### `client/resolv/dead-fast.conf`

```text
nameserver 10.53.0.99
nameserver 10.53.0.53
options timeout:1 attempts:2
```

### `java/DnsWatch.java`

JDK 18부터 소스 인코딩 기본값이 UTF-8이다. Java 8~17에서 직접 컴파일하면 한글 주석 때문에 `javac -encoding UTF-8`이 필요할 수 있다.

```java
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 2초마다 같은 이름을 InetAddress로 조회해 결과와 걸린 시간을 출력한다.
 * JVM InetAddress 캐시가 언제 OS 리졸버에 다시 묻는지(= 걸린 시간이 늘어나는 순간)를 관찰한다.
 *
 * 실행 (JDK 11+ 단일 파일 실행):
 *   java DnsWatch.java api.lab.internal
 *   java -Dsun.net.inetaddr.ttl=5 DnsWatch.java api.lab.internal
 *
 * Java 8 문법만 사용했다. Java 8에서는 javac DnsWatch.java 후 java DnsWatch api.lab.internal 로 실행한다.
 */
public class DnsWatch {

    public static void main(String[] args) throws InterruptedException {
        String host = args.length > 0 ? args[0] : "api.lab.internal";

        // null이면 JVM 기본값을 쓴다: 성공 30초(SecurityManager가 없을 때), 실패 10초(java.security 파일 기본)
        System.out.println("security  networkaddress.cache.ttl          = " + Security.getProperty("networkaddress.cache.ttl"));
        System.out.println("security  networkaddress.cache.negative.ttl = " + Security.getProperty("networkaddress.cache.negative.ttl"));
        System.out.println("system    sun.net.inetaddr.ttl              = " + System.getProperty("sun.net.inetaddr.ttl"));
        System.out.println("java.version                                = " + System.getProperty("java.version"));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        while (true) {
            long start = System.nanoTime();
            String result;
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                result = Arrays.stream(addrs)
                        .map(InetAddress::getHostAddress)
                        .collect(Collectors.joining(", "));
            } catch (UnknownHostException e) {
                result = "UnknownHostException: " + e.getMessage();
            }
            long micros = (System.nanoTime() - start) / 1_000;
            // 캐시 hit는 수십 us, OS 리졸버까지 다녀오면 수백 us~수 ms (us = 마이크로초. 컨테이너 로캘 문제로 µ 대신 사용)
            System.out.printf("[%s] %s -> %s  (%,d us)%n", LocalTime.now().format(fmt), host, result, micros);
            Thread.sleep(2000);
        }
    }
}
```


## 7. 실행 결과와 해석

> 이 문서의 출력은 모두 **예시 출력**이다. 작성 시점에 작성자 환경에서 Docker 데몬이 실행 중이 아니어서 직접 실행 결과를 첨부하지 못했다. 다음 값은 환경마다 반드시 달라진다: 질의 ID, 클라이언트 포트, 소요 시간(ms·us), 타임스탬프, 남은 TTL 값(조회 시점에 따라), EDNS 버퍼 크기, 3-4의 외부 서버 IP와 TTL, CoreDNS·dig 로그 문구(버전별). 구조(어느 계층이 옛 값을 주는지, 질의 수, 5초 간격)는 같아야 한다. 다르면 그것 자체가 조사할 거리다.

| 단계 | 봐야 할 출력 | 정상 해석 |
|---|---|---|
| 3-1 | `flags: qr aa rd`, TTL 고정 | 권한 서버의 원본 응답 |
| 3-2 | `flags: qr rd ra`, TTL 감소, auth 로그 1회 | 리졸버 캐시 |
| 3-3 | CNAME + A | 체인의 각 레코드가 따로 캐시됨 |
| 3-4 | 루트 → TLD → 권한 서버 순 응답 | 반복 조회와 위임 |
| 3-5 | `getent` ≠ `dig` | `/etc/hosts`는 앱 경로에만 적용 |
| 4-1 | auth 즉시, resolver는 TTL만큼, JVM은 추가 30초, static은 하루 | 캐시 계층의 합 |
| 4-2 | resolver NXDOMAIN + 옛 serial SOA, TTL 감소 | 부정 캐시 |
| 4-3 | `pay.partner` 질의 8건 | ndots 검색 도메인 증폭 |
| 4-4 | `real 0m5.0xs`, tcpdump 5초 간격 | glibc timeout 기본값 |
| 5-1 | 5초마다 us → ms 튐 | JVM TTL 적용 |
| 5-2 | 질의 2건 | ndots/FQDN 효과 |
| 5-3 | 1초 / 수 ms | timeout 단축 / 죽은 서버 제거 |

## 8. 검증

| # | 명령 | 기대 결과 |
|---|---|---|
| 1 | `[client] # dig @10.53.0.10 api.lab.internal \| grep flags` | `aa` 포함, `ra` 없음 |
| 2 | `[client] # dig @10.53.0.53 api.lab.internal \| grep flags` | `ra` 포함, `aa` 없음 |
| 3 | 3-2 반복 후 auth 로그 | 리졸버 캐시 유효 기간 동안 auth에는 1건 |
| 4 | 4-1 변경 직후 `dig @10.53.0.53 +short static.lab.internal` | 옛 값 `10.53.0.120` (TTL 수만 초 남음) |
| 5 | 4-1 변경 직후 `dig @10.53.0.53 new.lab.internal` | `status: NXDOMAIN` (auth는 NOERROR) |
| 6 | 4-3 후 `logs resolver` 의 `pay.partner` 건수 | 8 |
| 7 | 4-4 `time getent ahosts api.lab.internal` | `real` ≈ 5초 |
| 8 | 5-2 후 `pay.partner` 추가 건수 | 2 |
| 9 | 5-3 `dead-fast.conf` | `real` ≈ 1초 |
| 10 | 5-1 DnsWatch 출력 | 약 5초 주기로 ms 단위 조회 |

애플리케이션 코드를 검증하는 실습이 아니므로 JUnit·Testcontainers 테스트는 두지 않는다. HikariCP 커넥션이 DNS 변경 후에도 옛 IP에 남는 현상은 13장(커넥션 관리)에서 Spring Boot 앱으로 재현한다.

## 9. 변형 실습

**V1.** `Corefile.resolver`의 두 `cache 3600`을 `cache 5`로 바꾸고 `docker compose restart resolver` 한 뒤 `dig @10.53.0.53 static.lab.internal +noall +answer`를 두 번 실행하면 TTL은 얼마로 보일까?

<details><summary>예상 결과</summary>

최대 5로 보이고, 5초마다 auth에 다시 묻는다. CoreDNS `cache`의 인자는 캐시 최대 시간이라 레코드 TTL(86400)보다 작으면 그 값으로 잘린다. 응답의 TTL도 잘린 값으로 나가므로 하위 캐시도 짧게 캐시한다. 쿠버네티스 CoreDNS 기본 `cache 30`이 클러스터 안에서 TTL 상한을 30초로 만드는 원리다. 장점은 변경이 빨리 퍼지는 것, 단점은 상위 서버 질의 증가다. `restart`로 기존 캐시도 비워졌다는 점에 유의한다(V3). 확인 후 3600으로 되돌린다.
</details>

**V2.** musl(Alpine) 리졸버는 죽은 nameserver를 어떻게 다룰까? 다음을 실행해 `real` 시간을 4-4와 비교한다.

```text
$ docker run --rm --network ch04-lab_dnsnet -v "${PWD}/client/resolv:/r:ro" alpine:3.20 sh -c "cp /r/dead.conf /etc/resolv.conf && time getent hosts api.lab.internal"
```

<details><summary>예상 결과</summary>

수 ms 안에 끝날 가능성이 높다. musl은 `resolv.conf`의 nameserver **모두에 동시에** 질의하고 먼저 온 답을 쓰기 때문에, 죽은 서버를 기다리지 않는다(이론 5절 musl 비교표). 겉보기엔 glibc보다 나아 보이지만, 성격이 다른 서버(내부 전용 DNS와 공용 DNS)를 섞어 두면 매번 먼저 온 쪽의 답을 쓰게 되어 결과가 들쭉날쭉해진다(이론 9-6). busybox `time`의 출력 형식은 bash와 다르다. 네트워크 이름 `ch04-lab_dnsnet`은 `docker network ls`로 확인한다.
</details>

**V3.** 4-1처럼 존을 바꾼 직후 `docker compose restart resolver`를 실행하면 `static`과 JVM의 `api`는 각각 언제 새 값이 될까?

<details><summary>예상 결과</summary>

`static`은 **즉시** 새 값이 된다. 리졸버 프로세스가 재시작되며 메모리 캐시가 비었기 때문이다. TTL 86400이라는 약속은 캐시가 살아 있을 때만 의미가 있다. 반면 JVM은 자기 캐시가 만료될 때까지(최대 30초) 옛 `api` 값을 계속 준다. 계층마다 캐시가 독립적이라서 한 계층을 비워도 아래 계층은 그대로다. 실무에서 "DNS 캐시를 비웠는데도 안 바뀐다"는 대개 다른 계층(JVM, 커넥션 풀)을 비우지 않은 경우다.
</details>

## 10. 정리 (Clean-up)

```text
$ cd "C:\Study\Network\네트워크 기초\labs\04-dns"
$ docker compose down
$ Copy-Item zones\db.lab.internal.v1 zones\db.lab.internal       # PowerShell: 존 파일 초기 상태로
$ cp zones/db.lab.internal.v1 zones/db.lab.internal                # bash
```

- `down`: 컨테이너 5개와 네트워크 `ch04-lab_dnsnet` 삭제. client 안에서 바꾼 `/etc/resolv.conf`, `/etc/hosts`도 함께 사라진다.
- 볼륨은 만들지 않았다. 4-2에서 편집한 존 파일은 위 복사로 되돌린다. V1에서 `Corefile.resolver`를 바꿨다면 `cache 3600`으로 되돌린다.

남은 것 확인:

```text
$ docker ps -a --filter "name=ch04-lab"        # 아무것도 없어야 함
$ docker network ls --filter "name=ch04-lab"   # 아무것도 없어야 함
```

이미지 삭제(선택):

```text
$ docker image rm ch04-client:1 coredns/coredns:1.11.3 eclipse-temurin:21.0.4_7-jdk-jammy alpine:3.20
```

호스트 DNS 설정은 바꾸지 않았으므로 원복할 것은 없다.
