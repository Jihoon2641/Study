---
장: 2
주제: IP 주소, 서브넷, CIDR — 실습
분류: 네트워크 / 네트워크 계층
난이도: 초급
관련표준: RFC 791, RFC 4632, RFC 1918
실습환경: Docker Desktop(WSL2 백엔드) 또는 Linux Docker, Linux 컨테이너(alpine 3.20, postgres 16.4)
비용발생: 없음
작성일: 2026-09-19
선행지식: [IP 주소, 서브넷, CIDR 이론](./02-IP-서브넷-CIDR-이론.md)
---

# 02. IP 주소, 서브넷, CIDR — 실습

> 관련 문서: [이론](./02-IP-서브넷-CIDR-이론.md) · 실습 파일: [`labs/02-subnet/`](./labs/02-subnet/)

## 1. 실습 개요

Docker 브리지 네트워크 두 개를 서로 다른 서브넷으로 만들고, 그 사이에 라우터 역할 컨테이너를 둔다. 이 작은 망에서 다음을 **직접 관찰하고 일부러 망가뜨린다**.

| 단계 | 하는 일 | 확인하는 이론 |
|---|---|---|
| 3. 관찰 | CIDR 계산, 라우팅 테이블 읽기, 온링크 vs 게이트웨이 판단, 목적지 IP와 목적지 MAC의 차이 | 이론 3·4·5절 |
| 4-1 | 경로가 없는 서브넷으로 통신 | 기본 게이트웨이 |
| 4-2 | 한쪽에만 경로를 추가 → 요청은 도착, 응답은 소실 | 9-3 비대칭 라우팅 |
| 4-3 | 서브넷 마스크를 /16으로 잘못 설정 → ARP 실패 | 9-2 마스크 불일치 |
| 4-4 | `pg_hba.conf`의 /28 경계 계산 착오 → `no pg_hba.conf entry` | 9-4 |
| 5 | 하나씩 고치며 최장 접두사 일치, TTL 감소, 구간별 MAC 교체를 tcpdump로 확인 | 4절 흐름 전체 |

```text
          lab1 (10.10.1.0/24)                        lab2 (10.10.2.0/24)
  ┌───────────────────────────────────┐      ┌──────────────────────────────┐
  │ a   10.10.1.10   (앱 역할)         │      │                              │
  │ b   10.10.1.20   (앱 역할 2)       │      │ c   10.10.2.30  (다른 서브넷) │
  │ db  10.10.1.50   (PostgreSQL)     │      │                              │
  │ router 10.10.1.254 ───────────────┼──────┼─ router 10.10.2.254          │
  │ (Docker 게이트웨이 10.10.1.1)      │      │ (Docker 게이트웨이 10.10.2.1) │
  └───────────────────────────────────┘      └──────────────────────────────┘
```

Docker는 서로 다른 브리지 네트워크 간 통신을 기본적으로 차단한다(호스트 iptables의 격리 규칙). 그래서 lab1↔lab2 통신은 **router 컨테이너를 경유할 때만** 가능하다. 실제 사내망에서 서로 다른 VLAN이 라우터를 통해서만 통신하는 구조와 같다.

## 2. 환경과 준비물

> 💰 **비용 발생 없음.** 로컬 Docker만 사용한다.

| 항목 | 요구 사항 |
|---|---|
| Windows 11 | Docker Desktop (WSL2 백엔드). 작성 시점에 Docker Engine 29.x, Compose v5.x 환경을 기준으로 했다 |
| macOS | Docker Desktop. 명령 동일 |
| Linux | Docker Engine + Compose 플러그인. 명령 동일. 단, 호스트 라우팅 테이블에 `br-xxxx` 경로가 생기는 것을 추가로 관찰할 수 있다 |
| 인터넷 | 컨테이너 기동 시 `apk add`로 도구를 설치하므로 필요 |

모든 네트워크 조작(`ip addr`, `ip route`, `tcpdump`)은 **컨테이너 안에서만** 한다. 호스트 네트워크 설정은 바꾸지 않는다.

**프롬프트 표기**

| 표기 | 실행 위치 |
|---|---|
| `$` | 호스트 터미널. PowerShell 또는 WSL2 bash 기준. Git Bash는 경로 자동 변환 때문에 `docker run -v`가 깨질 수 있으니 `MSYS_NO_PATHCONV=1`을 앞에 붙인다 |
| `[a] #` | 컨테이너 `a` 안의 root 셸 (`$ docker compose exec a sh` 로 진입) |
| `lab=#` | db 컨테이너의 psql |

### 사전 점검: 실습 대역이 내 네트워크와 겹치지 않는가

실습은 `10.10.1.0/24`, `10.10.2.0/24`를 쓴다. 회사망·VPN이 이 대역을 쓰면 실습 중 그 대역의 서버에 접근할 수 없게 된다(이론 9-1 함정 그 자체다).

```text
$ ipconfig                          # Windows: 모든 어댑터의 IPv4 주소/서브넷 마스크 확인
$ docker network ls -q | ForEach-Object { docker network inspect -f '{{.Name}} {{range .IPAM.Config}}{{.Subnet}}{{end}}' $_ }   # PowerShell
$ docker network ls -q | xargs docker network inspect -f '{{.Name}} {{range .IPAM.Config}}{{.Subnet}}{{end}}'                    # bash
```

`10.10.x.x`가 보이면 `docker-compose.yml`과 `pg/pg_hba.conf`의 `10.10.`을 `10.201.` 등으로 일괄 치환하고, 이하 문서의 주소도 같이 바꿔 읽는다.

### 기동

```text
$ cd "C:\Study\Network\네트워크 기초\labs\02-subnet"
$ docker compose up -d
$ docker compose logs a b c router | Select-String ready     # PowerShell (bash: | grep ready)
```

예시 출력 — `ready`가 4줄 나오면 준비 완료:

```text
a-1       | ready
b-1       | ready
c-1       | ready
router-1  | ready
```

## 3. 관찰 먼저

### 3-1. 계산부터: 실습에 나올 CIDR을 미리 풀어두기

```text
$ docker run --rm -v "${PWD}:/w" python:3.12-alpine python /w/calc.py 10.10.1.0/28 10.10.1.10 10.10.1.20
```

예시 출력:

```text
입력          : 10.10.1.0/28
네트워크      : 10.10.1.0/28    00001010.00001010.00000001.00000000
서브넷 마스크 : 255.255.255.240    11111111.11111111.11111111.11110000
브로드캐스트  : 10.10.1.15
주소 수       : 16
호스트 범위   : 10.10.1.1 ~ 10.10.1.14 (14개)
  10.10.1.10      -> 포함
  10.10.1.20      -> 미포함
```

- 마스크 2진수의 **1과 0의 경계**가 네트워크/호스트의 경계다. /28은 마지막 옥텟에서 상위 4비트까지 네트워크다.
- `10.10.1.20`이 미포함이라는 결과를 기억해둔다. 4-4에서 PostgreSQL이 똑같은 판단을 한다.

`10.10.1.0/24`, `10.10.0.0/16`도 넣어 보며 크기가 어떻게 바뀌는지 본다.

### 3-2. 컨테이너 a의 주소와 라우팅 테이블

```text
$ docker compose exec a sh
[a] # ip -4 addr show eth0
[a] # ip route
```

예시 출력 (인터페이스 번호 `@if12` 등은 환경마다 다름):

```text
2: eth0@if12: <BROADCAST,MULTICAST,UP,LOWER_UP,M-DOWN> mtu 1500 qdisc noqueue state UP
    inet 10.10.1.10/24 brd 10.10.1.255 scope global eth0
       valid_lft forever preferred_lft forever

default via 10.10.1.1 dev eth0
10.10.1.0/24 dev eth0 proto kernel scope link src 10.10.1.10
```

| 필드 | 의미 |
|---|---|
| `inet 10.10.1.10/24` | 주소 + 접두사 길이. 마스크는 이 `/24` 하나로만 저장된다 |
| `brd 10.10.1.255` | 브로드캐스트 주소 = 호스트 비트 전부 1 |
| `mtu 1500` | 1장에서 본 이더넷 MTU |
| `default via 10.10.1.1` | `/0` 경로. Docker가 브리지에 만든 게이트웨이(호스트 쪽)로 향한다 |
| `10.10.1.0/24 ... proto kernel scope link` | 주소를 붙이는 순간 커널이 자동 생성한 **온링크 경로**. 게이트웨이(`via`)가 없다 |

### 3-3. 커널의 판단을 직접 묻기: `ip route get`

```text
[a] # ip route get 10.10.1.20
[a] # ip route get 10.10.2.30
[a] # ip route get 1.1.1.1
```

예시 출력:

```text
10.10.1.20 dev eth0 src 10.10.1.10 uid 0
    cache
10.10.2.30 via 10.10.1.1 dev eth0 src 10.10.1.10 uid 0
    cache
1.1.1.1 via 10.10.1.1 dev eth0 src 10.10.1.10 uid 0
    cache
```

- `10.10.1.20`: `via` 없음 → **온링크**. 게이트웨이 없이 직접 보낸다.
- `10.10.2.30`: 같은 `10.10`으로 시작하지만 /24 밖이다 → 기본 게이트웨이 경유. "앞자리가 비슷하면 같은 망"이 아니라 **마스크가 정한다**.
- `src 10.10.1.10`: 상대가 보게 될 출발지 IP. `pg_hba.conf`·보안 그룹이 판단하는 값이다.

### 3-4. 목적지 IP ≠ 목적지 MAC

```text
[a] # ping -c 2 10.10.1.20
[a] # ping -c 2 1.1.1.1
[a] # ip neigh
```

예시 출력 (MAC 값은 환경마다 다름. 구버전 Docker는 `02:42:` + IP의 16진수 형태, 최근 버전은 무작위일 수 있음):

```text
10.10.1.20 dev eth0 lladdr 02:42:0a:0a:01:14 REACHABLE
10.10.1.1 dev eth0 lladdr 02:42:9c:3e:11:7a REACHABLE
```

**`1.1.1.1`이 이웃 테이블에 없다.** 1.1.1.1로 핑을 보냈는데 ARP로 찾은 것은 게이트웨이 `10.10.1.1`의 MAC뿐이다. 온링크가 아닌 목적지는 MAC을 알 필요도, 알 방법도 없다.

tcpdump `-e`(L2 헤더 출력)로 프레임을 직접 본다:

```text
[a] # tcpdump -eni eth0 -c 2 icmp & sleep 1; ping -c 1 1.1.1.1; wait
```

예시 출력 (Docker Desktop 환경에서 외부 ICMP가 막혀 있으면 reply가 없을 수 있다. request 한 줄만 봐도 충분하다):

```text
14:02:11.101 02:42:0a:0a:01:0a > 02:42:9c:3e:11:7a, ethertype IPv4 (0x0800), length 98: 10.10.1.10 > 1.1.1.1: ICMP echo request, id 9, seq 0, length 64
14:02:11.109 02:42:9c:3e:11:7a > 02:42:0a:0a:01:0a, ethertype IPv4 (0x0800), length 98: 1.1.1.1 > 10.10.1.10: ICMP echo reply, id 9, seq 0, length 64
```

읽는 법: `출발 MAC > 목적 MAC, ethertype ..., 출발 IP > 목적 IP`. 목적 MAC `02:42:9c:3e:11:7a`는 `ip neigh`의 **10.10.1.1(게이트웨이)** 과 같다. 목적 IP는 1.1.1.1. 1장에서 말한 "IP는 끝까지 그대로, MAC은 구간마다 바뀐다"의 첫 구간이다. `ethertype IPv4 (0x0800)`은 1장 5절의 EtherType 필드다.

## 4. 문제 상황 재현

### 4-1. 경로가 없는 다른 서브넷

```text
[a] # ping -c 3 -W 1 10.10.2.30
```

예시 출력:

```text
PING 10.10.2.30 (10.10.2.30): 56 data bytes

--- 10.10.2.30 ping statistics ---
3 packets transmitted, 0 packets received, 100% packet loss
```

3-3에서 봤듯이 a는 이 패킷을 기본 게이트웨이(Docker 호스트 측)로 보낸다. 호스트는 lab1 → lab2 전달을 Docker 격리 규칙으로 버린다(격리 규칙의 구현 방식은 Docker 버전마다 다르지만 기본 동작이 "브리지 간 차단"인 것은 같다). **a의 라우팅은 정상이지만 게이트웨이가 전달해주지 않는** 상황이다. 실무에서는 "게이트웨이 라우터에 해당 대역 경로가 없다" 또는 "방화벽이 막는다"에 해당한다.

### 4-2. 한쪽에만 경로 추가 — 비대칭 라우팅

a에만 "10.10.2.0/24는 router(10.10.1.254)로 보내라"는 경로를 추가한다.

```text
[a] # ip route add 10.10.2.0/24 via 10.10.1.254
[a] # ip route get 10.10.2.30
10.10.2.30 via 10.10.1.254 dev eth0 src 10.10.1.10 uid 0
```

기본 경로(`/0`)와 새 경로(`/24`) 둘 다 10.10.2.30에 매칭되지만 **더 긴 /24가 선택**됐다. 최장 접두사 일치다.

터미널을 하나 더 열어 c에서 캡처하고, a에서 핑을 보낸다.

```text
$ docker compose exec c tcpdump -eni eth0 icmp          # 터미널 2 (c)
[a] # ping -c 3 -W 1 10.10.2.30                          # 터미널 1 (a)
```

예시 출력 — a:

```text
3 packets transmitted, 0 packets received, 100% packet loss
```

예시 출력 — c (MAC 값은 환경마다 다름):

```text
14:10:01.001 02:42:0a:0a:02:fe > 02:42:0a:0a:02:1e, ethertype IPv4 (0x0800), length 98: 10.10.1.10 > 10.10.2.30: ICMP echo request, id 11, seq 0, length 64
14:10:01.001 02:42:0a:0a:02:1e > 02:42:5d:71:a0:03, ethertype IPv4 (0x0800), length 98: 10.10.2.30 > 10.10.1.10: ICMP echo reply, id 11, seq 0, length 64
```

- 첫 줄: 요청이 **router의 lab2 쪽 MAC**에서 왔다. 요청 경로는 정상이다.
- 둘째 줄: c는 응답을 **보냈다**. 그런데 목적 MAC이 router가 아니라 **c의 기본 게이트웨이 10.10.2.1**(Docker 호스트 측)이다. c의 라우팅 테이블에는 10.10.1.0/24 경로가 없어서 `/0`을 탔기 때문이다. 이 응답은 4-1과 같은 이유로 호스트에서 버려진다.

```text
$ docker compose exec c ip route get 10.10.1.10
10.10.1.10 via 10.10.2.1 dev eth0 src 10.10.2.30 uid 0
```

**서버에서 tcpdump를 뜨면 요청도 들어오고 응답도 나가는데 클라이언트는 타임아웃** — 이론 9-3의 증상 그대로다. 응답 경로는 응답하는 쪽의 라우팅 테이블이 정한다. TCP였다면 클라이언트 쪽 예외는 `SocketTimeoutException: Connect timed out`이었을 것이다.

> 이 상태는 5-1에서 고친다. 그대로 둔다.

### 4-3. 서브넷 마스크를 잘못 설정 (/24 → /16)

"10.10으로 시작하면 다 우리 망이니까 /16이면 되겠지"라고 설정한 상황이다.

```text
[a] # ip addr del 10.10.1.10/24 dev eth0
[a] # ip addr add 10.10.1.10/16 dev eth0
[a] # ip route add default via 10.10.1.1
[a] # ip route
```

예시 출력:

```text
default via 10.10.1.1 dev eth0
10.10.0.0/16 dev eth0 proto kernel scope link src 10.10.1.10
```

주소를 지우는 순간 그 주소에 의존하던 경로(기본 경로, 4-2에서 추가한 경로)도 커널이 함께 지웠다. 기본 경로만 다시 넣었다. (`ip route add default`가 `RTNETLINK answers: File exists`를 내면 기본 경로가 남아 있던 것이니 무시한다. 출력에 `10.10.2.0/24 via 10.10.1.254`가 남아 있으면 `ip route del 10.10.2.0/24`로 지운다. 남겨두면 변형 실습 V1의 상황이 된다.) 이제 커널의 판단을 묻는다:

```text
[a] # ip route get 10.10.2.30
10.10.2.30 dev eth0 src 10.10.1.10 uid 0
```

`via`가 사라졌다. a는 10.10.2.30을 **온링크**로 판단한다. 캡처하면서 핑을 보낸다:

```text
[a] # tcpdump -eni eth0 -c 3 arp & sleep 1; ping -c 3 -W 1 10.10.2.30; wait
[a] # ip neigh show 10.10.2.30
```

예시 출력:

```text
14:20:01.000 02:42:0a:0a:01:0a > ff:ff:ff:ff:ff:ff, ethertype ARP (0x0806), length 42: Request who-has 10.10.2.30 tell 10.10.1.10, length 28
14:20:02.010 02:42:0a:0a:01:0a > ff:ff:ff:ff:ff:ff, ethertype ARP (0x0806), length 42: Request who-has 10.10.2.30 tell 10.10.1.10, length 28
14:20:03.020 02:42:0a:0a:01:0a > ff:ff:ff:ff:ff:ff, ethertype ARP (0x0806), length 42: Request who-has 10.10.2.30 tell 10.10.1.10, length 28
PING 10.10.2.30 (10.10.2.30): 56 data bytes
...100% packet loss                 (busybox ping 버전에 따라 "sendto: Host is unreachable"로 끝날 수도 있다)
10.10.2.30 dev eth0  FAILED
```

- 목적 MAC `ff:ff:ff:ff:ff:ff` = L2 브로드캐스트. lab1 브리지의 모든 컨테이너에 "10.10.2.30 누구야?"를 묻는다.
- c는 다른 브리지(lab2)에 있으므로 이 질문을 듣지 못한다. 응답 없음 → `FAILED`.
- ICMP 패킷은 **한 번도 나가지 않았다**. L3 판단(온링크) 때문에 L2(ARP)에서 멈췄다. TCP였다면 `java.net.NoRouteToHostException: No route to host`다.

**생각해볼 것**: 이 상태에서 a → db(10.10.1.50)는 될까? 된다. db는 실제로 같은 L2에 있으므로 "온링크"라는 잘못된 판단이 우연히 맞다. 이것이 마스크 오류가 "특정 서버에서만 안 됨"으로 나타나는 이유다.

> 이 상태도 5-1에서 고친다.

### 4-4. `pg_hba.conf`의 CIDR 경계 착오

현재 `pg/pg_hba.conf`에는 `host all all 10.10.1.0/28 scram-sha-256` 한 줄만 있다. 작성자는 "앱 서버 대역 허용"을 의도했다. 3-1에서 계산했듯 /28은 `.0`~`.15`다.

b(10.10.1.20)에서 접속:

```text
$ docker compose exec b sh
[b] # PGPASSWORD=labpass psql -h 10.10.1.50 -U lab -d lab -c "select inet_client_addr();"
```

예시 출력:

```text
psql: error: connection to server at "10.10.1.50", port 5432 failed: FATAL:  no pg_hba.conf entry for host "10.10.1.20", user "lab", database "lab", no encryption
```

a(10.10.1.10)에서 접속 (a의 마스크가 /16인 상태여도 db는 같은 L2에 있으므로 연결된다):

```text
[a] # PGPASSWORD=labpass psql -h 10.10.1.50 -U lab -d lab -c "select inet_client_addr();"
 inet_client_addr
------------------
 10.10.1.10
(1 row)
```

서버 로그:

```text
$ docker compose logs db --tail 6
```

예시 출력:

```text
db-1  | LOG:  connection received: host=10.10.1.20 port=45712
db-1  | FATAL:  no pg_hba.conf entry for host "10.10.1.20", user "lab", database "lab", no encryption
db-1  | LOG:  connection received: host=10.10.1.10 port=39018
db-1  | LOG:  connection authenticated: identity="lab" method=scram-sha-256 (/etc/postgresql/custom/pg_hba.conf:7)
db-1  | LOG:  connection authorized: user=lab database=lab application_name=psql
```

읽는 법:

- `connection received: host=10.10.1.20` — **TCP 연결은 성공했다**(L4 정상). PostgreSQL이 연결을 받은 뒤 거부한 것이다. `Connection refused`와 전혀 다른 문제다.
- `host "10.10.1.20"` — DB가 본 클라이언트 IP. CIDR은 이 값을 기준으로 작성한다.
- `(pg_hba.conf:7)` — 성공한 접속이 몇 번째 줄에 매칭됐는지(PostgreSQL 16 로그 형식). 줄 번호는 파일 내용에 따라 다르다.

Spring Boot 앱이었다면 기동 시 이렇게 나온다:

```text
org.postgresql.util.PSQLException: FATAL: no pg_hba.conf entry for host "10.10.1.20", user "lab", database "lab", no encryption
```

## 5. 개선된 구성

### 5-1. 라우팅 복구: 마스크 원복 + 양방향 경로

a를 원래 상태로 되돌리는 가장 확실한 방법은 컨테이너 재생성이다. 컨테이너 안에서 `ip`로 바꾼 설정은 재생성하면 사라진다(이론 4절 "상태" 표 — 설정에 없는 경로는 재생성 시 소멸).

```text
$ docker compose up -d --force-recreate a
$ docker compose logs a | Select-String ready          # ready 확인 (bash: grep)
```

수동으로 되돌리려면:

```text
[a] # ip addr del 10.10.1.10/16 dev eth0
[a] # ip addr add 10.10.1.10/24 dev eth0
[a] # ip route add default via 10.10.1.1
```

이제 **양쪽 모두**에 경로를 추가한다.

```text
[a] # ip route add 10.10.2.0/24 via 10.10.1.254
[c] # ip route add 10.10.1.0/24 via 10.10.2.254
[a] # ping -c 3 10.10.2.30
[a] # traceroute -n 10.10.2.30
```

예시 출력 (지연 시간은 환경마다 다름):

```text
PING 10.10.2.30 (10.10.2.30): 56 data bytes
64 bytes from 10.10.2.30: seq=0 ttl=63 time=0.112 ms
64 bytes from 10.10.2.30: seq=1 ttl=63 time=0.095 ms
64 bytes from 10.10.2.30: seq=2 ttl=63 time=0.101 ms

traceroute to 10.10.2.30 (10.10.2.30), 30 hops max, 46 byte packets
 1  10.10.1.254  0.051 ms  0.030 ms  0.027 ms
 2  10.10.2.30  0.066 ms  0.041 ms  0.038 ms
```

- `ttl=63`: c가 64로 보낸 응답이 router를 한 번 지나며 1 줄었다. 같은 서브넷의 b에 핑을 보내면 `ttl=64`다. **TTL로 거친 라우터 수를 알 수 있다.**
- traceroute 1번 홉이 router(10.10.1.254)다. traceroute는 TTL을 1, 2, ...로 늘려 보내며 TTL이 0이 된 지점의 라우터가 돌려주는 ICMP Time Exceeded로 경로를 알아낸다.

### 5-2. 라우터가 하는 일을 눈으로 보기

router에서 두 인터페이스를 동시에 캡처한다.

```text
$ docker compose exec router sh
[router] # ip -br -4 addr
[router] # tcpdump -ni any -e -v icmp
```

`ip -br -4 addr` 예시 출력 (어느 쪽이 eth0/eth1인지는 환경마다 다를 수 있으니 여기서 확인):

```text
lo               UNKNOWN        127.0.0.1/8
eth0@if14        UP             10.10.1.254/24
eth1@if16        UP             10.10.2.254/24
```

다른 터미널에서 `[a] # ping -c 1 10.10.2.30`을 보내면 예시 출력:

```text
eth0  In  ifindex 2 02:42:0a:0a:01:0a ethertype IPv4 (0x0800), length 104: (tos 0x0, ttl 64, id 51123, offset 0, flags [DF], proto ICMP (1), length 84)
    10.10.1.10 > 10.10.2.30: ICMP echo request, id 13, seq 0, length 64
eth1  Out ifindex 3 02:42:0a:0a:02:fe ethertype IPv4 (0x0800), length 104: (tos 0x0, ttl 63, id 51123, offset 0, flags [DF], proto ICMP (1), length 84)
    10.10.1.10 > 10.10.2.30: ICMP echo request, id 13, seq 0, length 64
eth1  In  ifindex 3 02:42:0a:0a:02:1e ethertype IPv4 (0x0800), length 104: (tos 0x0, ttl 64, ...)
    10.10.2.30 > 10.10.1.10: ICMP echo reply, id 13, seq 0, length 64
eth0  Out ifindex 2 02:42:0a:0a:01:fe ethertype IPv4 (0x0800), length 104: (tos 0x0, ttl 63, ...)
    10.10.2.30 > 10.10.1.10: ICMP echo reply, id 13, seq 0, length 64
```

`-i any`에서 `-e`가 보여주는 MAC은 **그 프레임의 출발 MAC**이다(ifindex·flags·id 값은 환경마다 다름).

| 관찰 지점 | eth0 In → eth1 Out 비교 | 의미 |
|---|---|---|
| IP 출발/목적 | `10.10.1.10 > 10.10.2.30` 그대로 | L3 주소는 종단 간 유지 |
| 출발 MAC | a의 MAC → router eth1의 MAC | 라우터가 L2 헤더를 새로 만들었다 |
| TTL | 64 → 63 | 라우터가 L3 헤더를 수정했다 (체크섬도 재계산) |
| `id` | 같음 | 같은 패킷이 전달된 것 |

이론 4절 시퀀스 다이어그램의 "TTL 64→63, dst MAC=DB"를 실제 패킷으로 확인한 것이다.

### 5-3. `pg_hba.conf` 수정과 무중단 반영

호스트에서 `labs/02-subnet/pg/pg_hba.conf`의 마지막 줄을 수정한다. 선택지:

| 수정안 | 의미 | 판단 |
|---|---|---|
| `10.10.1.0/24` | lab1 서브넷 전체 | 앱 서버가 이 서브넷에 모여 있다면 가장 자연스러움 |
| `10.10.1.0/27` | `.0`~`.31` | 필요한 만큼만. 대신 앱 서버가 `.32` 이상에 추가되면 또 같은 장애 |
| `10.10.1.20/32` 줄 추가 | b 하나만 | 인스턴스가 고정 IP일 때만. 오토스케일 환경에서는 부적합 |

여기서는 `/24`로 바꾼다.

```text
host    all       all   10.10.1.0/24    scram-sha-256
```

재시작 없이 반영하고, 서버가 파일을 어떻게 읽었는지 확인한다.

```text
$ docker compose exec db psql -U lab -d lab
lab=# SELECT pg_reload_conf();
lab=# SELECT line_number, type, database, user_name, address, netmask, auth_method, error FROM pg_hba_file_rules;
```

예시 출력:

```text
 pg_reload_conf
----------------
 t

 line_number | type  | database | user_name | address   | netmask       | auth_method   | error
-------------+-------+----------+-----------+-----------+---------------+---------------+-------
           3 | local | {all}    | {all}     |           |               | trust         |
           7 | host  | {all}    | {all}     | 10.10.1.0 | 255.255.255.0 | scram-sha-256 |
```

- `netmask 255.255.255.0` — PostgreSQL이 `/24`를 마스크로 풀어서 저장했다. `/28`이었다면 `255.255.255.240`.
- `error` 열이 비어 있어야 한다. 오타가 있으면 여기에 이유가 나오고, 그 줄은 무시된다.

같은 판단을 SQL로 계산해볼 수도 있다:

```text
lab=# SELECT '10.10.1.20'::inet << '10.10.1.0/28'::cidr AS in_28,
lab-#        '10.10.1.20'::inet << '10.10.1.0/24'::cidr AS in_24;
 in_28 | in_24
-------+-------
 f     | t
```

b에서 다시 접속:

```text
[b] # PGPASSWORD=labpass psql -h 10.10.1.50 -U lab -d lab -c "select inet_client_addr();"
 inet_client_addr
------------------
 10.10.1.20
(1 row)
```

## 6. 전체 구성 파일

실습 디렉터리 구조:

```text
labs/02-subnet/
├── docker-compose.yml
├── calc.py
└── pg/
    └── pg_hba.conf
```

### `docker-compose.yml`

```yaml
# 2장 실습: 서브넷, 라우팅, pg_hba.conf CIDR
#
#   lab1 (10.10.1.0/24)                     lab2 (10.10.2.0/24)
#   a      10.10.1.10  (앱 역할)            c  10.10.2.30  (다른 서브넷의 서버 역할)
#   b      10.10.1.20  (앱 역할 2)
#   db     10.10.1.50  (PostgreSQL)
#   router 10.10.1.254 ──────────────────── router 10.10.2.254
#
# 대역이 회사망·VPN과 겹치면 이 파일과 pg/pg_hba.conf의 "10.10." 을 다른 대역으로 일괄 치환한다.
name: ch02-lab

x-tools: &tools
  # latest 금지: 패키지 이름·버전이 바뀌면 실습이 깨진다
  image: alpine:3.20
  # NET_ADMIN: ip addr/route 변경, NET_RAW: tcpdump·ping용 raw 소켓
  cap_add: [NET_ADMIN, NET_RAW]
  # 기동 시 도구 설치 후 대기. "ready" 가 로그에 찍히면 사용 가능
  command: ["sh", "-c", "apk add --no-cache iproute2 tcpdump postgresql16-client >/dev/null && echo ready && sleep infinity"]
  init: true   # sleep이 PID 1이 되면 docker stop 신호를 무시해 10초씩 기다리므로 tini를 둔다

services:
  a:
    <<: *tools
    hostname: a
    networks:
      lab1: { ipv4_address: 10.10.1.10 }

  b:
    <<: *tools
    hostname: b
    networks:
      lab1: { ipv4_address: 10.10.1.20 }

  c:
    <<: *tools
    hostname: c
    networks:
      lab2: { ipv4_address: 10.10.2.30 }

  router:
    <<: *tools
    hostname: router
    # 컨테이너 네트워크 네임스페이스는 기본 ip_forward=0 이다.
    # 1이어야 자기에게 온 게 아닌 패킷을 다른 인터페이스로 넘긴다(= L3 라우터).
    # /proc/sys는 컨테이너 안에서 읽기 전용이라 실행 중에는 바꿀 수 없고 여기서 지정해야 한다.
    sysctls:
      net.ipv4.ip_forward: "1"
    networks:
      lab1: { ipv4_address: 10.10.1.254 }
      lab2: { ipv4_address: 10.10.2.254 }

  db:
    image: postgres:16.4-alpine
    hostname: db
    environment:
      POSTGRES_USER: lab
      POSTGRES_PASSWORD: labpass
      POSTGRES_DB: lab
    # hba_file: 데이터 디렉터리의 기본 pg_hba.conf 대신 마운트한 파일을 쓴다
    # log_connections: 접속 시도마다 클라이언트 IP를 로그에 남긴다 (기본 off)
    command: ["postgres", "-c", "hba_file=/etc/postgresql/custom/pg_hba.conf", "-c", "log_connections=on"]
    # 파일이 아니라 디렉터리를 마운트: 에디터가 파일을 새로 만들어 저장해도(inode 변경) 컨테이너에 반영된다
    volumes:
      - ./pg:/etc/postgresql/custom:ro
    networks:
      lab1: { ipv4_address: 10.10.1.50 }

networks:
  lab1:
    ipam:
      config:
        # 명시하지 않으면 Docker가 default-address-pools(기본 172.17~31.x.x 등)에서 임의로 고른다
        - subnet: 10.10.1.0/24
          gateway: 10.10.1.1
  lab2:
    ipam:
      config:
        - subnet: 10.10.2.0/24
          gateway: 10.10.2.1
```

### `pg/pg_hba.conf` (실습 시작 상태)

```text
# TYPE  DATABASE  USER  ADDRESS         METHOD
# 컨테이너 내부 Unix 소켓 접속(초기화 스크립트, docker compose exec db psql)용
local   all       all                   trust

# 의도: "앱 서버 대역만 허용" — 그런데 /28 은 10.10.1.0 ~ 10.10.1.15 뿐이다.
# 실습 4-4에서 이 줄 때문에 b(10.10.1.20)가 거부되는 것을 확인한 뒤 고친다.
host    all       all   10.10.1.0/28    scram-sha-256
```

`local ... trust`는 컨테이너 안 Unix 소켓 전용이다. 공식 이미지의 초기화 스크립트가 이 소켓으로 접속하므로 지우면 첫 기동이 실패한다. TCP(`host`) 줄에는 `trust`를 쓰지 않는다.

### `calc.py`

```python
"""CIDR 계산기 (Python 3.8+ 표준 라이브러리만 사용)

사용법: python calc.py <CIDR> [포함 여부를 확인할 IP ...]
예    : python calc.py 10.10.1.0/28 10.10.1.10 10.10.1.20
"""
import ipaddress
import sys


def bits(addr):
    """IPv4 주소를 옥텟별 2진수로 표시"""
    return ".".join(format(int(octet), "08b") for octet in str(addr).split("."))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    # strict=False: 10.10.1.20/28 처럼 호스트 비트가 켜진 입력도 받아 네트워크 주소로 내린다.
    # (strict=True 이면 ValueError — PostgreSQL cidr 타입이 같은 입력을 거부하는 것과 같은 규칙)
    net = ipaddress.ip_network(sys.argv[1], strict=False)
    if net.version != 4:
        print("IPv4만 지원")
        sys.exit(1)

    print("입력          :", sys.argv[1])
    print("네트워크      :", net, "  ", bits(net.network_address))
    print("서브넷 마스크 :", net.netmask, "  ", bits(net.netmask))
    print("브로드캐스트  :", net.broadcast_address)
    print("주소 수       :", net.num_addresses)

    if net.prefixlen <= 30:
        first = net.network_address + 1
        last = net.broadcast_address - 1
        print("호스트 범위   :", first, "~", last, "(" + str(net.num_addresses - 2) + "개)")
    elif net.prefixlen == 31:
        print("호스트 범위   :", net.network_address, "~", net.broadcast_address, "(RFC 3021 점대점, 2개)")
    else:
        print("호스트 범위   :", net.network_address, "(단일 호스트)")

    for ip in sys.argv[2:]:
        verdict = "포함" if ipaddress.ip_address(ip) in net else "미포함"
        print("  {:<15} -> {}".format(ip, verdict))


if __name__ == "__main__":
    main()
```

## 7. 실행 결과와 해석

> 이 문서의 출력은 모두 **예시 출력**이다. 작성 시점에 작성자 환경에서 Docker 데몬이 실행 중이 아니어서 직접 실행 결과를 첨부하지 못했다. 다음 값은 환경에 따라 반드시 달라진다: MAC 주소, 인터페이스 번호(`@if12`, `ifindex`), 타임스탬프, 지연 시간(`time=`), ICMP `id`, 클라이언트 포트 번호. 구조(어느 줄에 `via`가 있는지, TTL 값, 에러 메시지 문구)는 같아야 한다. 다르면 그것 자체가 조사할 거리다.

단계별로 관찰해야 할 핵심을 한 표로 모으면:

| 단계 | 봐야 할 출력 | 정상 해석 |
|---|---|---|
| 3-3 | `ip route get 10.10.2.30` | `via 10.10.1.1` — 앞자리가 같아도 /24 밖이면 게이트웨이 |
| 3-4 | `ip neigh` | 1.1.1.1은 없고 게이트웨이만 있음 |
| 3-4 | tcpdump `-e` | 목적 MAC = 게이트웨이, 목적 IP = 1.1.1.1 |
| 4-2 | c의 tcpdump | echo reply가 **나가는데** a는 100% loss, reply의 목적 MAC이 router가 아님 |
| 4-3 | `ip route get`, tcpdump arp | `via` 없음, `who-has 10.10.2.30` 반복, `FAILED` |
| 4-4 | db 로그 | `connection received`(L4 성공) 다음 `no pg_hba.conf entry` |
| 5-1 | ping `ttl=`, traceroute | ttl=63, 1번 홉 10.10.1.254 |
| 5-2 | router tcpdump | IP 동일, 출발 MAC 변경, TTL 64→63 |
| 5-3 | `pg_hba_file_rules` | `netmask 255.255.255.0`, `error` 비어 있음 |

## 8. 검증

아래 체크리스트를 5단계까지 마친 상태에서 확인한다.

| # | 명령 | 기대 결과 |
|---|---|---|
| 1 | `[a] # ip -4 addr show eth0` | `inet 10.10.1.10/24` |
| 2 | `[a] # ip route get 10.10.2.30` | `via 10.10.1.254` 포함 |
| 3 | `[c] # ip route get 10.10.1.10` | `via 10.10.2.254` 포함 (양방향 경로) |
| 4 | `[a] # ping -c 3 10.10.2.30` | `0% packet loss`, `ttl=63` |
| 5 | `[a] # ping -c 3 10.10.1.20` | `ttl=64` (같은 서브넷, 라우터 경유 없음) |
| 6 | `[a] # traceroute -n 10.10.2.30` | 2개 홉, 1번이 `10.10.1.254` |
| 7 | `[b] # PGPASSWORD=labpass psql -h 10.10.1.50 -U lab -d lab -tAc "select inet_client_addr()"` | `10.10.1.20` |
| 8 | `lab=# SELECT count(*) FROM pg_hba_file_rules WHERE error IS NOT NULL;` | `0` |

애플리케이션 코드가 개입하는 실습이 아니므로 JUnit·Testcontainers 테스트는 두지 않는다. Testcontainers로 `pg_hba.conf`를 검증하는 방식은 17장(Docker Compose로 Spring Boot + PostgreSQL + Nginx)에서 다룬다.

## 9. 변형 실습

**V1.** a의 마스크를 /16으로 잘못 둔 상태에서, 4-2처럼 `ip route add 10.10.2.0/24 via 10.10.1.254`를 **추가로** 넣으면 a → c 핑은 어떻게 될까? (c 쪽 복귀 경로는 있다고 가정)

<details><summary>예상 결과</summary>

성공한다. 10.10.2.30에는 `10.10.0.0/16`(온링크)과 `10.10.2.0/24`(via router) 두 경로가 매칭되는데, 최장 접두사 일치로 /24가 선택된다. `ip route get 10.10.2.30`에 다시 `via 10.10.1.254`가 나온다. 마스크 오류가 더 구체적인 경로에 가려져 드러나지 않는 것이다. 실무에서는 이런 "우연히 동작하는 잘못된 설정"이 나중에 경로 하나를 정리할 때 터진다.
</details>

**V2.** `pg_hba.conf`의 주소 열에 CIDR 대신 `samenet`을 쓰면 b의 접속은 어떻게 될까? c(10.10.2.30)가 router를 거쳐 db에 접속한다면?

<details><summary>예상 결과</summary>

`samenet`은 **서버 자신의 인터페이스가 속한 서브넷**을 뜻한다. db는 10.10.1.50/24이므로 `10.10.1.0/24`와 같아지고 b는 허용된다. c는 10.10.2.0/24이므로 거부된다(`no pg_hba.conf entry for host "10.10.2.30"`). 다만 c → db를 시도하려면 db 컨테이너에도 10.10.2.0/24 복귀 경로가 필요한데, db에는 `NET_ADMIN` 권한이 없어 경로를 추가할 수 없다. 이 경우 c의 SYN은 db에 도착하지만 SYN-ACK가 기본 게이트웨이로 나가 버려지는 4-2와 같은 비대칭 상황이 되고, 클라이언트 쪽에서는 `pg_hba` 에러가 아니라 **연결 타임아웃**이 난다. 에러 종류로 계층을 구분하는 1장 내용과 연결해보자. `samenet`은 서버의 인터페이스 구성에 따라 의미가 바뀌므로 운영에서는 명시적 CIDR이 낫다.
</details>

**V3.** `docker-compose.yml`에서 router의 `net.ipv4.ip_forward`를 `"0"`으로 바꾸고 `docker compose up -d router`로 router만 재생성하면? (a·c의 경로는 그대로 둔다)

<details><summary>예상 결과</summary>

a → c 핑은 100% 손실. router의 tcpdump(`-ni any icmp`)에는 `eth0 In` 요청만 보이고 `eth1 Out`이 없다. 라우터가 목적지가 자신이 아닌 패킷을 받았지만 전달 기능이 꺼져 있어 조용히 버린 것이다. ICMP 오류를 돌려주지 않으므로 송신 측에서는 원인을 알 수 없다. 리눅스 서버를 게이트웨이·NAT로 쓰는 구성(NAT 인스턴스, 쿠버네티스 노드)에서 `ip_forward`가 꺼지면 이런 증상이 난다. 확인 후 `"1"`로 되돌리고 다시 재생성한다. router를 재생성하면 MAC이 바뀔 수 있어 a·c의 이웃 캐시가 갱신될 때까지 몇 초간 손실이 있을 수 있다.
</details>

## 10. 정리 (Clean-up)

```text
$ cd "C:\Study\Network\네트워크 기초\labs\02-subnet"
$ docker compose down -v
```

- `down`: 컨테이너 5개와 네트워크 `ch02-lab_lab1`, `ch02-lab_lab2` 삭제. 컨테이너 안에서 추가한 경로·주소 변경도 함께 사라진다.
- `-v`: postgres 이미지가 만든 익명 볼륨(`/var/lib/postgresql/data`) 삭제.

남은 것 확인:

```text
$ docker ps -a --filter "name=ch02-lab"          # 아무것도 없어야 함
$ docker network ls --filter "name=ch02-lab"     # 아무것도 없어야 함
```

이미지까지 지우려면 (다른 실습에서 재사용하므로 선택):

```text
$ docker image rm alpine:3.20 postgres:16.4-alpine python:3.12-alpine
```

호스트 설정은 바꾸지 않았으므로 원복할 것은 없다. `pg/pg_hba.conf`를 다시 실습하려면 마지막 줄을 `10.10.1.0/28`로 되돌린다.
