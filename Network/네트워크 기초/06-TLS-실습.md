---
장: 6
주제: TLS/HTTPS — 실습 (사설 CA 체인 구성과 검증 실패 재현)
분류: 네트워크 / 보안 · 응용 계층
난이도: 중급
관련표준: RFC 8446, RFC 5280, RFC 6125
실습환경: Docker Desktop(WSL2 백엔드) 또는 Linux Docker, Linux 컨테이너(OpenSSL 3.x, nginx 1.27-alpine, Temurin JDK 21.0.4)
비용발생: 없음
작성일: 2026-09-20
선행지식: [TLS 이론](./06-TLS-이론.md), [HTTP](./05-HTTP-이론.md)
---

# 06. TLS/HTTPS — 실습

> 관련 문서: [이론](./06-TLS-이론.md) · 실습 파일: [`labs/06-tls/`](./labs/06-tls/)
>
> 이 실습은 **인증서와 신뢰 경로**에 집중한다. "중간자가 평문을 읽는 것 대 암호문만 보는 것"을 Java 소켓과 Wireshark로 확인하는 내용은 [`PLAN.md`](../PLAN.md)의 MITM 실습 계획에 있다. 둘은 겹치지 않고 이어진다. 이 문서의 5-2(신뢰 저장소 구성)가 그 계획의 5~6단계와 같은 지점이다.

## 1. 실습 개요

루트 CA → 중간 CA → 서버 인증서를 직접 발급해 Nginx에 붙이고, **검증이 깨지는 세 가지 상황**을 포트별로 재현한다. 같은 상황을 `openssl`, `curl`, JVM 세 가지 클라이언트로 각각 확인해 오류 메시지가 어떻게 다르게 보이는지 익힌다.

| 단계 | 하는 일 | 확인하는 이론 |
|---|---|---|
| 3-1 | 발급된 인증서의 필드 해부 (Subject, Issuer, SAN, 확장) | 5절 X.509 필드 |
| 3-2 | 오프라인 체인 검증 (`openssl verify`) | 4-2 체인 구성 |
| 3-3 | 실제 핸드셰이크와 서버가 보내는 체인 관찰 | 4-1 |
| 3-4 | TLS 1.3과 1.2 비교 | 7절 |
| 3-5 | 세션 재개 확인 | 4-3, 9-8 |
| 4-1 | 중간 CA 누락 → `unable to get local issuer certificate` | 9-1 |
| 4-2 | SAN 불일치 → `No name matching web found` | 9-3 |
| 4-3 | 만료 인증서 → `CertificateExpiredException` | 9-2 |
| 4-4 | 사설 CA를 JVM이 모름 → `PKIX path building failed` | 9-4 |
| 5 | 풀체인 구성, 신뢰 저장소 구성, 만료 점검 자동화 | 6-2, 10절 |

```text
  pki (1회 실행)                        web (nginx)                   클라이언트
  ┌───────────────────────┐   ┌──────────────────────────────┐   ┌──────────────────┐
  │ Lab Root CA           │   │ :8443 리프만 (체인 누락)       │   │ client           │
  │   └ Lab Intermediate  │──▶│ :8444 풀체인 (정상)           │◀──│  openssl, curl   │
  │       ├ web (정상)     │   │ :8445 이름 불일치              │   │ java             │
  │       ├ wrong.example │   │ :8446 만료                    │◀──│  keytool,        │
  │       └ web (만료)     │   │ :8448 풀체인 + TLSv1.2 only   │   │  TlsCheck.java   │
  └───────────────────────┘   └──────────────────────────────┘   └──────────────────┘
        모든 키·인증서는 이름 있는 볼륨 pki 에만 존재 (리포지터리에 남지 않음)
```

## 2. 환경과 준비물

> 💰 **비용 발생 없음.** 로컬 Docker만 사용한다. 외부 CA에 요청하지 않는다.

| 항목 | 요구 사항 |
|---|---|
| Windows 11 | Docker Desktop (WSL2 백엔드) |
| macOS / Linux | Docker Desktop 또는 Docker Engine + Compose 플러그인. 명령 동일 |
| 인터넷 | 이미지 다운로드와 `apk add` |

여기서 만드는 CA와 인증서는 **이 실습 전용**이다. 만든 루트 CA를 OS나 브라우저의 신뢰 저장소에 설치하지 않는다. 실습이 끝나면 `docker compose down -v`로 키가 든 볼륨까지 지운다.

**프롬프트 표기**

| 표기 | 실행 위치 |
|---|---|
| `$` | 호스트 터미널 (PowerShell 또는 WSL2 bash) |
| `[client] #` | `$ docker compose exec client sh` |
| `[java] #` | `$ docker compose exec java bash` |

### 기동

```text
$ cd "C:\Study\Network\네트워크 기초\labs\06-tls"
$ docker compose up -d --build
$ docker compose logs pki
```

예시 출력 (일련번호·날짜는 실행 시점에 따라 다름):

```text
pki-1  | == 1) 루트 CA (자기 자신을 서명한다) ==
pki-1  | == 2) 중간 CA (키+CSR 생성 → 루트가 서명) ==
pki-1  | Certificate request self-signature ok
pki-1  | subject=O = Lab, CN = Lab Intermediate CA
pki-1  | == 3) 서버 인증서: CSR 생성 ==
pki-1  | == 4) 중간 CA가 서명 (openssl ca) ==
pki-1  | Using configuration from /scripts/ca.cnf
pki-1  | Check that the request matches the signature
pki-1  | Signature ok
pki-1  | Certificate is to be certified until Dec 19 ... 2026 GMT (90 days)
pki-1  | == 생성 결과 ==
pki-1  | --- out/good.crt
pki-1  | subject=O = Lab, CN = web
pki-1  | issuer=O = Lab, CN = Lab Intermediate CA
pki-1  | notBefore=Sep 20 ... 2026 GMT
pki-1  | notAfter=Dec 19 ... 2026 GMT
pki-1  | X509v3 Subject Alternative Name:
pki-1  |     DNS:web, DNS:localhost, IP Address:127.0.0.1
```

`pki` 컨테이너는 할 일을 마치고 종료된다(`Exited (0)`). 정상이다.

## 3. 관찰 먼저

### 3-1. 인증서 해부

```text
[client] # openssl x509 -in /pki/out/good.crt -noout -subject -issuer -dates -serial
[client] # openssl x509 -in /pki/out/good.crt -noout -ext subjectAltName,basicConstraints,keyUsage,extendedKeyUsage
```

예시 출력:

```text
subject=O = Lab, CN = web
issuer=O = Lab, CN = Lab Intermediate CA
notBefore=Sep 20 09:00:00 2026 GMT
notAfter=Dec 19 09:00:00 2026 GMT
serial=1000

X509v3 Subject Alternative Name:
    DNS:web, DNS:localhost, IP Address:127.0.0.1
X509v3 Basic Constraints: critical
    CA:FALSE
X509v3 Key Usage: critical
    Digital Signature, Key Encipherment
X509v3 Extended Key Usage:
    TLS Web Server Authentication
```

| 필드 | 의미 | 왜 중요한가 |
|---|---|---|
| `subject` | 이 인증서의 주인 | **표시용이다.** 호스트명 검증에는 쓰지 않는다 |
| `issuer` | 서명한 CA | 체인을 위로 잇는 고리. 여기서는 중간 CA |
| `notBefore`/`notAfter` | 유효기간 | 4-3에서 여기가 과거인 인증서를 쓴다 |
| `Subject Alternative Name` | 유효한 이름 목록 | **호스트명 검증의 유일한 근거**(이론 9-3) |
| `CA:FALSE` | 이 인증서로 다른 인증서를 서명할 수 없다 | 리프 인증서로 가짜 인증서를 만들지 못하게 막는다 |
| `TLS Web Server Authentication` | 서버 인증 용도 | 용도가 다르면 거부된다 |

중간 CA와 비교해 본다.

```text
[client] # openssl x509 -in /pki/out/intermediate.crt -noout -subject -issuer -ext basicConstraints
```

예시 출력:

```text
subject=O = Lab, CN = Lab Intermediate CA
issuer=O = Lab, CN = Lab Root CA
X509v3 Basic Constraints: critical
    CA:TRUE, pathlen:0
```

중간 CA의 `issuer`가 루트다. **리프 → 중간 → 루트로 Issuer를 따라가면 체인**이 된다. `pathlen:0`은 "내 아래로는 CA를 더 만들 수 없다"는 제한이다.

### 3-2. 오프라인 체인 검증 — 중간 CA가 왜 필요한가

```text
[client] # openssl verify -CAfile /pki/out/ca.crt /pki/out/good.crt
[client] # openssl verify -CAfile /pki/out/ca.crt -untrusted /pki/out/intermediate.crt /pki/out/good.crt
```

예시 출력:

```text
O = Lab, CN = web
error 20 at 0 depth lookup: unable to get local issuer certificate
error /pki/out/good.crt: verification failed

/pki/out/good.crt: OK
```

- 첫 명령: 루트는 믿지만 **중간 CA를 모르니** 경로를 잇지 못한다. `error 20`이 바로 그 뜻이다.
- 둘째 명령: `-untrusted`로 중간 CA를 함께 주면 루트까지 이어진다. **이것이 서버가 핸드셰이크에서 중간 CA를 함께 보내야 하는 이유다.**
- 4-1에서 이 상황을 실제 TLS 연결로 재현한다.

### 3-3. 핸드셰이크와 서버가 보내는 체인

```text
[client] # openssl s_client -connect web:8444 -servername web -CAfile /pki/out/ca.crt </dev/null 2>/dev/null | head -30
```

예시 출력 (인증서 내용·세션 ID는 환경마다 다름):

```text
CONNECTED(00000003)
---
Certificate chain
 0 s:O = Lab, CN = web
   i:O = Lab, CN = Lab Intermediate CA
 1 s:O = Lab, CN = Lab Intermediate CA
   i:O = Lab, CN = Lab Root CA
---
Server certificate
-----BEGIN CERTIFICATE-----
...
---
SSL handshake has read 2800 bytes and written 400 bytes
---
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Verify return code: 0 (ok)
```

| 출력 | 의미 |
|---|---|
| `Certificate chain` 항목 2개 | 서버가 **리프 + 중간**을 보냈다. 루트는 보내지 않는 것이 정상(이론 4-1 단계 4) |
| `s:`(subject) / `i:`(issuer) | 0번의 issuer가 1번의 subject다. 체인이 이어진다 |
| `New, TLSv1.3, Cipher is ...` | 협상된 버전과 암호 스위트 |
| `Verify return code: 0 (ok)` | **검증 성공.** 이 줄이 실습 내내 가장 중요한 한 줄이다 |

`-CAfile`을 빼고 실행하면 같은 서버인데도 `Verify return code: 19 (self-signed certificate in certificate chain)` 같은 실패가 나온다. 신뢰 저장소가 검증 결과를 바꾼다는 것을 바로 확인할 수 있다.

### 3-4. TLS 1.3과 TLS 1.2

```text
[client] # openssl s_client -connect web:8444 -servername web -CAfile /pki/out/ca.crt </dev/null 2>/dev/null | grep -E 'New,|Verify return'
[client] # openssl s_client -connect web:8448 -servername web -CAfile /pki/out/ca.crt </dev/null 2>/dev/null | grep -E 'New,|Verify return'
[client] # curl -s -o /dev/null --cacert /pki/out/ca.crt -w 'tcp=%{time_connect} tls=%{time_appconnect} ver=%{ssl_verify_result}\n' https://web:8444/
[client] # curl -s -o /dev/null --cacert /pki/out/ca.crt -w 'tcp=%{time_connect} tls=%{time_appconnect} ver=%{ssl_verify_result}\n' https://web:8448/
```

예시 출력:

```text
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Verify return code: 0 (ok)
New, TLSv1.2, Cipher is ECDHE-RSA-AES256-GCM-SHA384
Verify return code: 0 (ok)
tcp=0.000412 tls=0.010235 ver=0
tcp=0.000398 tls=0.013118 ver=0
```

- 8448은 `ssl_protocols TLSv1.2;`라 1.2로 내려간다. 암호 스위트 이름 형식도 다르다(1.3은 키 교환·인증이 스위트 이름에서 빠졌다).
- `tls - tcp`가 핸드셰이크에 든 시간이다. **같은 호스트 안이라 RTT가 거의 0이므로 1-RTT와 2-RTT 차이는 잘 드러나지 않는다.** 원거리 서버에서는 이 차이가 수십 ms가 된다. 여기서는 "측정하는 방법"을 익히는 데 목적을 둔다.
- `ssl_verify_result=0`이 검증 성공이다.

### 3-5. 세션 재개

```text
[client] # openssl s_client -connect web:8444 -servername web -CAfile /pki/out/ca.crt -reconnect </dev/null 2>/dev/null | grep -E 'New,|Reused'
```

예시 출력:

```text
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Reused, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Reused, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Reused, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Reused, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
```

첫 연결만 `New`이고 이후는 `Reused`다. 서버가 준 세션 티켓으로 핸드셰이크를 줄였다(이론 4-3). 운영에서 서버가 여러 대인데 티켓 키·세션 캐시가 공유되지 않으면 여기서 계속 `New`가 나온다(이론 9-8).

## 4. 문제 상황 재현

### 4-1. 중간 CA 누락 (포트 8443)

먼저 `openssl`로 본다.

```text
[client] # openssl s_client -connect web:8443 -servername web -CAfile /pki/out/ca.crt </dev/null 2>/dev/null | grep -E '^ [0-9] s:|^   i:|Verify return'
```

예시 출력:

```text
 0 s:O = Lab, CN = web
   i:O = Lab, CN = Lab Intermediate CA
Verify return code: 20 (unable to get local issuer certificate)
```

**인증서가 1장뿐**이다(8444는 2장이었다). 리프의 issuer인 중간 CA가 없으니 루트까지 이을 수 없다. 3-2의 첫 번째 명령과 같은 상황이다.

curl에서:

```text
[client] # curl -sS --cacert /pki/out/ca.crt https://web:8443/ ; echo "exit=$?"
```

예시 출력:

```text
curl: (60) SSL certificate problem: unable to get local issuer certificate
...
exit=60
```

Java에서 (신뢰 저장소는 5-2에서 만들지만, 여기서는 먼저 만들어 두고 실행한다):

```text
[java] # keytool -importcert -noprompt -alias lab-root -file /pki/out/ca.crt \
         -keystore /tmp/truststore.p12 -storetype PKCS12 -storepass changeit
[java] # java -Djavax.net.ssl.trustStore=/tmp/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit \
         TlsCheck.java https://web:8443/
```

예시 출력:

```text
target      = https://web:8443/
trustStore  = /tmp/truststore.p12
java.version= 21.0.4
---
검증 실패
  javax.net.ssl.SSLHandshakeException: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
  Caused by: sun.security.validator.ValidatorException: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
  Caused by: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
```

- **같은 원인인데 클라이언트마다 메시지가 다르다.** `unable to get local issuer certificate`(OpenSSL/curl)와 `PKIX path building failed`(Java)는 같은 말이다.
- 이론 9-1의 "브라우저는 되는데 서버에서만 실패"가 여기서 갈린다. 브라우저는 인증서의 AIA 확장을 보고 중간 CA를 내려받지만(이 실습의 사설 CA에는 AIA URL이 없다), Java와 curl은 그러지 않는다.

### 4-2. 호스트명 불일치 (포트 8445)

```text
[client] # openssl s_client -connect web:8445 -servername web -CAfile /pki/out/ca.crt </dev/null 2>/dev/null | grep -E '^ 0 s:|Verify return'
[client] # curl -sS --cacert /pki/out/ca.crt https://web:8445/ ; echo "exit=$?"
[java]   # java -Djavax.net.ssl.trustStore=/tmp/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit TlsCheck.java https://web:8445/
```

예시 출력:

```text
 0 s:O = Lab, CN = wrong.example.com
Verify return code: 0 (ok)

curl: (60) SSL: no alternative certificate subject name matches target host name 'web'
exit=60

검증 실패
  javax.net.ssl.SSLHandshakeException: No name matching web found
  Caused by: java.security.cert.CertificateException: No name matching web found
```

- **`openssl s_client`의 `Verify return code: 0 (ok)`에 주의한다.** 체인 검증은 통과했다. `s_client`는 기본적으로 **호스트명을 검사하지 않는다**(`-verify_hostname web`을 줘야 검사한다). curl과 Java는 검사하므로 실패한다.
- 즉 "openssl로는 되는데 앱에서는 안 된다"가 나올 수 있다. 검사 항목이 다르기 때문이다(이론 4-2 흐름도에서 SAN 단계).

호스트명까지 검사하게 해본다.

```text
[client] # openssl s_client -connect web:8445 -servername web -CAfile /pki/out/ca.crt -verify_hostname web </dev/null 2>/dev/null | grep -E 'Verify return'
```

예시 출력:

```text
Verify return code: 62 (hostname mismatch)
```

### 4-3. 만료된 인증서 (포트 8446)

```text
[client] # openssl s_client -connect web:8446 -servername web -CAfile /pki/out/ca.crt </dev/null 2>/dev/null | grep -E 'Verify return'
[client] # openssl x509 -in /pki/out/expired.crt -noout -dates
[client] # curl -sS --cacert /pki/out/ca.crt https://web:8446/ ; echo "exit=$?"
[java]   # java -Djavax.net.ssl.trustStore=/tmp/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit TlsCheck.java https://web:8446/
```

예시 출력:

```text
Verify return code: 10 (certificate has expired)

notBefore=Jan  1 00:00:00 2024 GMT
notAfter=Apr  1 00:00:00 2024 GMT

curl: (60) SSL certificate problem: certificate has expired
exit=60

검증 실패
  javax.net.ssl.SSLHandshakeException: PKIX path validation failed: java.security.cert.CertPathValidatorException: validity check failed
  Caused by: java.security.cert.CertPathValidatorException: validity check failed
  Caused by: java.security.cert.CertificateExpiredException: NotAfter: Mon Apr 01 00:00:00 UTC 2024
```

- Java 메시지의 마지막 줄 `NotAfter:`가 결정적이다. **원인 체인의 가장 안쪽을 봐야** 한다.
- 만료는 배포와 무관하게 **모든 클라이언트에서 동시에** 실패한다는 점이 다른 장애와 구별되는 특징이다(이론 9-2).

만료 임박을 점검하는 방법도 확인해 둔다.

```text
[client] # openssl x509 -in /pki/out/good.crt -checkend 2592000 ; echo "exit=$?"
[client] # openssl x509 -in /pki/out/expired.crt -checkend 0 ; echo "exit=$?"
```

예시 출력:

```text
Certificate will not expire
exit=0
Certificate will expire
exit=1
```

종료 코드가 0이 아니면 알림을 보내는 식으로 모니터링에 넣는다.

### 4-4. 사설 CA를 JVM이 모른다 (포트 8444, 정상 인증서)

신뢰 저장소를 지정하지 않고 실행한다.

```text
[java] # java TlsCheck.java https://web:8444/
```

예시 출력:

```text
target      = https://web:8444/
trustStore  = (JVM 기본 cacerts)
---
검증 실패
  javax.net.ssl.SSLHandshakeException: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
```

- 인증서도 정상이고 체인도 완전한데 실패한다. **JVM 기본 `cacerts`에 우리 루트 CA가 없기 때문**이다.
- 4-1과 오류 메시지가 **똑같다.** 체인 누락인지 신뢰 저장소 문제인지는 메시지로 구분되지 않는다. 그래서 `openssl s_client`로 서버가 보내는 인증서 개수를 먼저 확인하는 순서가 필요하다(이론 9-1, 9-4).
- OS 신뢰 저장소에 넣어도 JVM은 보지 않는다. JVM은 자기 `cacerts` 또는 지정된 신뢰 저장소만 본다.

curl도 같은 상황이다.

```text
[client] # curl -sS https://web:8444/ ; echo "exit=$?"
```

예시 출력:

```text
curl: (60) SSL certificate problem: unable to get local issuer certificate
exit=60
```

## 5. 개선된 구성

### 5-1. 풀체인 제시 (8443 → 8444)

`nginx/default.conf`에서 두 서버 블록의 차이는 한 줄뿐이다.

```nginx
ssl_certificate /pki/out/good.crt;            # 8443: 리프만 → 4-1의 실패
ssl_certificate /pki/out/good-fullchain.crt;  # 8444: 리프 + 중간 → 성공
```

풀체인 파일의 내용을 직접 확인한다.

```text
[client] # grep -c 'BEGIN CERTIFICATE' /pki/out/good.crt /pki/out/good-fullchain.crt
[client] # openssl crl2pkcs7 -nocrl -certfile /pki/out/good-fullchain.crt | openssl pkcs7 -print_certs -noout
```

예시 출력:

```text
/pki/out/good.crt:1
/pki/out/good-fullchain.crt:2

subject=O = Lab, CN = web
issuer=O = Lab, CN = Lab Intermediate CA

subject=O = Lab, CN = Lab Intermediate CA
issuer=O = Lab, CN = Lab Root CA
```

**순서가 중요하다.** 리프가 먼저, 그다음 중간이다. 순서를 바꾸면 Nginx는 첫 번째 인증서를 서버 인증서로 보고 개인키와 맞지 않아 기동에 실패한다(변형 실습 V3). ACME 도구가 만들어 주는 `fullchain.pem`이 이 순서다.

### 5-2. 신뢰 저장소 구성 (JVM)

```text
[java] # keytool -importcert -noprompt -alias lab-root -file /pki/out/ca.crt \
         -keystore /tmp/truststore.p12 -storetype PKCS12 -storepass changeit
[java] # keytool -list -keystore /tmp/truststore.p12 -storepass changeit
[java] # java -Djavax.net.ssl.trustStore=/tmp/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit \
         TlsCheck.java https://web:8444/
```

예시 출력:

```text
키 저장소 유형: PKCS12
키 저장소 제공자: SUN
키 저장소에 1개의 항목이 포함되어 있습니다.
lab-root, 2026. 9. 20, trustedCertEntry,
인증서 지문(SHA-256): AB:CD:...

target      = https://web:8444/
trustStore  = /tmp/truststore.p12
---
검증 성공
status      = 200
body        = ok: 8444 (good)
protocol    = TLSv1.3
cipher      = TLS_AES_256_GCM_SHA384
서버가 보낸 인증서 수 = 2 (루트는 보내지 않는 것이 정상)
  [0] subject = O=Lab, CN=web
      issuer  = O=Lab, CN=Lab Intermediate CA
      valid   = ... ~ ...
  [1] subject = O=Lab, CN=Lab Intermediate CA
      issuer  = O=Lab, CN=Lab Root CA
```

- 코드는 한 줄도 바꾸지 않았다. **신뢰 저장소에 루트 CA를 넣은 것만으로** 같은 서버가 신뢰 대상이 됐다. 신뢰의 근원이 어디인지 보여주는 지점이다.
- 저장소에는 **루트만** 넣었다. 중간 CA는 서버가 핸드셰이크에서 보내준다.
- 운영에서는 이 저장소를 Secret·ConfigMap으로 마운트하거나 Spring Boot SSL 번들로 선언한다(이론 6-2). 이미지 빌드 시 `cacerts`에 주입하는 방식은 베이스 이미지 교체 때 사라질 수 있다(이론 9-4).

핸드셰이크 내부를 보고 싶으면 디버그 로그를 켠다. 출력이 매우 길다.

```text
[java] # java -Djavax.net.debug=ssl:handshake -Djavax.net.ssl.trustStore=/tmp/truststore.p12 \
         -Djavax.net.ssl.trustStorePassword=changeit TlsCheck.java https://web:8444/ 2>&1 | head -40
```

예시 출력(일부):

```text
javax.net.ssl|DEBUG|...|ClientHello: ... session id: ..., cipher suites: [TLS_AES_256_GCM_SHA384, ...]
javax.net.ssl|DEBUG|...|"server_name": [type=host_name (0), value=web]
javax.net.ssl|DEBUG|...|ServerHello: ... "cipher suite": TLS_AES_256_GCM_SHA384
javax.net.ssl|DEBUG|...|Consuming server Certificate handshake message ...
javax.net.ssl|DEBUG|...|Found trusted certificate: ... CN=Lab Root CA
```

`server_name`(SNI)과 `Found trusted certificate`가 이론 4-1의 단계와 그대로 대응한다.

### 5-3. 이름·만료 문제의 해결은 재발급이다

버퍼나 설정으로 우회할 수 없다. 인증서를 다시 발급해야 한다.

```text
[client] # openssl x509 -in /pki/out/good.crt    -noout -ext subjectAltName
[client] # openssl x509 -in /pki/out/badname.crt -noout -ext subjectAltName
```

예시 출력:

```text
X509v3 Subject Alternative Name:
    DNS:web, DNS:localhost, IP Address:127.0.0.1
X509v3 Subject Alternative Name:
    DNS:wrong.example.com
```

- 접속에 쓰는 **모든 이름**(서비스명, FQDN, 필요하면 IP)을 SAN에 넣어 CSR을 만들어야 한다. `pki/gen.sh`의 `make_csr` 호출이 그 예다.
- 만료는 갱신 자동화로 해결한다. 이 실습의 인증서는 90일짜리다. 운영이라면 ACME(cert-manager, certbot)나 ACM이 갱신하고, **갱신 후 reload까지** 자동화되어 있어야 한다.

경로 전체를 한 번에 점검하는 명령을 만들어 두면 유용하다.

```text
[client] # for p in 8443 8444 8445 8446 8448; do \
             echo "--- :$p"; \
             echo | openssl s_client -connect web:$p -servername web -CAfile /pki/out/ca.crt 2>/dev/null \
               | openssl x509 -noout -subject -enddate 2>/dev/null; \
           done
```

예시 출력:

```text
--- :8443
subject=O = Lab, CN = web
notAfter=Dec 19 09:00:00 2026 GMT
--- :8444
subject=O = Lab, CN = web
notAfter=Dec 19 09:00:00 2026 GMT
--- :8445
subject=O = Lab, CN = wrong.example.com
notAfter=Dec 19 09:00:00 2026 GMT
--- :8446
subject=O = Lab, CN = web
notAfter=Apr  1 00:00:00 2024 GMT
--- :8448
subject=O = Lab, CN = web
notAfter=Dec 19 09:00:00 2026 GMT
```

**디스크의 파일이 아니라 서버가 실제로 제시하는 인증서**를 확인하는 방식이라는 점이 중요하다. 갱신했는데 reload를 안 한 경우를 이 방법으로만 잡을 수 있다(이론 9-2).

## 6. 전체 구성 파일

```text
labs/06-tls/
├── docker-compose.yml
├── pki/
│   ├── gen.sh          ← 루트 CA → 중간 CA → 서버 인증서 발급
│   └── ca.cnf          ← openssl ca 설정 (중간 CA가 서명할 때 사용)
├── nginx/
│   └── default.conf
└── java/
    └── TlsCheck.java
```

### `docker-compose.yml`

```yaml
# 6장 실습: 인증서 체인, 핸드셰이크, 검증 실패 재현
#
#   pki    : 루트 CA → 중간 CA → 서버 인증서(정상/이름불일치/만료)를 만들고 종료 (1회성)
#   web    : nginx — 포트마다 다른 인증서를 제시한다
#              :8443 리프 인증서만 (체인 누락)      :8444 풀체인 (정상)
#              :8445 이름 불일치                    :8446 만료
#              :8448 풀체인 + TLSv1.2 only
#   client : openssl s_client / curl
#   java   : Temurin JDK 21 — keytool, TlsCheck.java (JVM 신뢰 저장소 동작 확인)
#
# 키와 인증서는 리포지터리에 남지 않도록 이름 있는 볼륨(pki)에만 만든다.
name: ch06-lab

volumes:
  pki:

services:
  pki:
    image: alpine:3.20
    volumes:
      - pki:/pki
      - ./pki:/scripts:ro
    command: ["sh", "-c", "apk add --no-cache openssl >/dev/null && sh /scripts/gen.sh"]

  web:
    image: nginx:1.27-alpine
    hostname: web
    depends_on:
      pki:
        condition: service_completed_successfully
    volumes:
      - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
      - pki:/pki:ro
    ports:
      - "8443:8443"
      - "8444:8444"
      - "8445:8445"
      - "8446:8446"
      - "8448:8448"

  client:
    image: alpine:3.20
    hostname: client
    depends_on:
      pki:
        condition: service_completed_successfully
    volumes:
      - pki:/pki:ro
    # 도구 설치 후 대기. "ready"가 로그에 보이면 사용 가능
    command: ["sh", "-c", "apk add --no-cache openssl curl >/dev/null && echo ready && sleep infinity"]

  java:
    # glibc 기반 JDK. keytool과 TlsCheck 실행에 사용한다
    image: eclipse-temurin:21.0.4_7-jdk-jammy
    hostname: javaclient
    depends_on:
      pki:
        condition: service_completed_successfully
    working_dir: /lab
    volumes:
      - ./java:/lab:ro
      - pki:/pki:ro
    init: true
    command: ["sleep", "infinity"]
```

### `pki/gen.sh`

```bash
#!/bin/sh
# 루트 CA → 중간 CA → 서버 인증서(정상 / 이름 불일치 / 만료)를 만든다.
# busybox sh에서 동작하도록 bash 전용 문법(프로세스 치환 등)은 쓰지 않는다.
set -eu

PKI=/pki
CNF=/scripts/ca.cnf
cd "$PKI"

if [ -f out/good-fullchain.crt ]; then
    echo "이미 생성되어 있습니다. 다시 만들려면 'docker compose down -v' 후 재실행하세요."
    ls -l "$PKI/out"
    exit 0
fi

mkdir -p root int int/newcerts out
: > int/index.txt
# 같은 CN(web)으로 정상·만료 두 장을 발급하므로 중복 주체를 허용한다
echo "unique_subject = no" > int/index.txt.attr
echo 1000 > int/serial

echo "== 1) 루트 CA (자기 자신을 서명한다) =="
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
    -keyout root/ca.key -out root/ca.crt \
    -subj "/O=Lab/CN=Lab Root CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:1" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash"

echo "== 2) 중간 CA (키+CSR 생성 → 루트가 서명) =="
openssl req -newkey rsa:2048 -nodes \
    -keyout int/int.key -out int/int.csr \
    -subj "/O=Lab/CN=Lab Intermediate CA"

cat > int/int.ext <<'EXT'
basicConstraints=critical,CA:TRUE,pathlen:0
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always
EXT

openssl x509 -req -in int/int.csr -sha256 -days 1825 \
    -CA root/ca.crt -CAkey root/ca.key -CAcreateserial \
    -extfile int/int.ext -out int/int.crt

echo "== 3) 서버 인증서: CSR 생성 =="
# $1=파일이름 $2=CN $3=SAN
make_csr() {
    openssl req -newkey rsa:2048 -nodes \
        -keyout "out/$1.key" -out "out/$1.csr" \
        -subj "/O=Lab/CN=$2" \
        -addext "subjectAltName=$3"
}
make_csr good    "web"               "DNS:web,DNS:localhost,IP:127.0.0.1"
make_csr badname "wrong.example.com" "DNS:wrong.example.com"
make_csr expired "web"               "DNS:web,DNS:localhost,IP:127.0.0.1"

echo "== 4) 중간 CA가 서명 (openssl ca) =="
openssl ca -config "$CNF" -batch -notext -extensions v3_server -days 90 \
    -in out/good.csr -out out/good.crt
openssl ca -config "$CNF" -batch -notext -extensions v3_server -days 90 \
    -in out/badname.csr -out out/badname.crt
# 만료 인증서: 유효기간을 과거로 지정한다 (-startdate/-enddate 는 YYYYMMDDHHMMSSZ)
openssl ca -config "$CNF" -batch -notext -extensions v3_server \
    -startdate 20240101000000Z -enddate 20240401000000Z \
    -in out/expired.csr -out out/expired.crt

echo "== 5) 풀체인 파일 구성 (리프 → 중간 순서) =="
# 루트는 넣지 않는다. 클라이언트가 이미 갖고 있어야 하는 것이 루트다.
cat out/good.crt    int/int.crt > out/good-fullchain.crt
cat out/badname.crt int/int.crt > out/badname-fullchain.crt
cat out/expired.crt int/int.crt > out/expired-fullchain.crt
cp root/ca.crt out/ca.crt
cp int/int.crt out/intermediate.crt

# 실습 편의를 위해 읽기 권한을 준다. 운영에서 개인키는 600, 소유자만 읽어야 한다.
chmod 644 out/*.crt out/*.key

echo "== 생성 결과 =="
for f in out/good.crt out/badname.crt out/expired.crt; do
    echo "--- $f"
    openssl x509 -in "$f" -noout -subject -issuer -dates -ext subjectAltName
done
ls -l "$PKI/out"
```

### `pki/ca.cnf`

```ini
# 중간 CA가 서버 인증서를 발급할 때 쓰는 최소 설정 (openssl ca)
# 실제 CA 운영 설정은 훨씬 길다. 여기서는 "CSR을 받아 서명한다"는 흐름만 담았다.

[ ca ]
default_ca = CA_default

[ CA_default ]
dir               = /pki/int
database          = $dir/index.txt      # 발급 이력 (openssl ca가 직접 관리)
serial            = $dir/serial         # 다음 일련번호
new_certs_dir     = $dir/newcerts
certificate       = $dir/int.crt        # 서명하는 주체 = 중간 CA
private_key       = $dir/int.key
default_md        = sha256
default_days      = 90
policy            = policy_any
email_in_dn       = no
rand_serial       = no
# CSR에 담긴 확장(여기서는 SAN)을 인증서로 복사한다.
# 주의: 실제 CA에서 이 옵션을 켜면 CSR이 basicConstraints=CA:TRUE 같은 확장을 요청할 수 있어 위험하다.
# 아래 v3_server 확장이 함께 적용되므로 실습 범위에서는 안전하지만, 운영에서는 SAN만 골라 복사하는 방식을 쓴다.
copy_extensions   = copy

[ policy_any ]
commonName = supplied

# 서버 인증서용 확장
[ v3_server ]
basicConstraints     = critical,CA:FALSE          # 이 인증서로 다른 인증서를 서명할 수 없다
keyUsage             = critical,digitalSignature,keyEncipherment
extendedKeyUsage     = serverAuth                  # 서버 인증 용도
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
```

### `nginx/default.conf`

```nginx
# 포트마다 다른 인증서를 제시한다. 설정은 같고 인증서만 다르다.
# ssl_certificate 에는 "리프 → 중간" 순서로 이어 붙인 파일을 넣는 것이 원칙이며,
# 8443만 일부러 리프 1장만 지정해 체인 누락을 재현한다.

# 1) 체인 누락: 리프 인증서만 제시 (4-1)
server {
    listen 8443 ssl;
    server_name web;
    ssl_certificate     /pki/out/good.crt;
    ssl_certificate_key /pki/out/good.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    location / { return 200 "ok: 8443 (chain incomplete)\n"; }
}

# 2) 정상: 풀체인 (5장 기준점)
server {
    listen 8444 ssl;
    server_name web;
    ssl_certificate     /pki/out/good-fullchain.crt;
    ssl_certificate_key /pki/out/good.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    # 세션 재개 관찰용. 기본값은 none(캐시 없음), 티켓은 기본 on
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1h;

    location / { return 200 "ok: 8444 (good)\n"; }
}

# 3) 호스트명 불일치: SAN이 wrong.example.com 뿐인 인증서 (4-2)
server {
    listen 8445 ssl;
    server_name web;
    ssl_certificate     /pki/out/badname-fullchain.crt;
    ssl_certificate_key /pki/out/badname.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    location / { return 200 "ok: 8445 (name mismatch)\n"; }
}

# 4) 만료: 유효기간이 과거인 인증서 (4-3)
server {
    listen 8446 ssl;
    server_name web;
    ssl_certificate     /pki/out/expired-fullchain.crt;
    ssl_certificate_key /pki/out/expired.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    location / { return 200 "ok: 8446 (expired)\n"; }
}

# 5) 정상 인증서 + TLSv1.2만 허용: 1.2와 1.3 핸드셰이크 비교용 (3-4)
server {
    listen 8448 ssl;
    server_name web;
    ssl_certificate     /pki/out/good-fullchain.crt;
    ssl_certificate_key /pki/out/good.key;
    ssl_protocols TLSv1.2;

    location / { return 200 "ok: 8448 (TLSv1.2 only)\n"; }
}
```

### `java/TlsCheck.java`

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;

import javax.net.ssl.SSLSession;

/**
 * 지정한 HTTPS URL에 접속해서 협상 결과와 서버가 보낸 인증서 체인을 출력한다.
 * 실패하면 예외 원인 체인(Caused by)을 끝까지 출력한다. TLS 오류는 맨 안쪽 원인이 핵심이다.
 *
 * 실행 (JDK 11+ 단일 파일 실행):
 *   java TlsCheck.java https://web:8444/
 *   java -Djavax.net.ssl.trustStore=/tmp/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit TlsCheck.java https://web:8444/
 *   java -Djavax.net.debug=ssl:handshake TlsCheck.java https://web:8444/   # 핸드셰이크 상세 로그
 *
 * 문법은 Java 8 호환이지만 java.net.http.HttpClient는 Java 11부터 제공된다.
 * Java 8이라면 HttpsURLConnection으로 같은 검증 동작을 확인할 수 있다.
 */
public class TlsCheck {

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "https://web:8444/";
        System.out.println("target      = " + url);
        System.out.println("trustStore  = " + System.getProperty("javax.net.ssl.trustStore", "(JVM 기본 cacerts)"));
        System.out.println("java.version= " + System.getProperty("java.version"));
        System.out.println("---");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("검증 성공");
            System.out.println("status      = " + response.statusCode());
            System.out.println("body        = " + response.body().trim());

            SSLSession session = response.sslSession().orElse(null);
            if (session != null) {
                System.out.println("protocol    = " + session.getProtocol());
                System.out.println("cipher      = " + session.getCipherSuite());
                Certificate[] chain = session.getPeerCertificates();
                System.out.println("서버가 보낸 인증서 수 = " + chain.length + " (루트는 보내지 않는 것이 정상)");
                for (int i = 0; i < chain.length; i++) {
                    X509Certificate cert = (X509Certificate) chain[i];
                    System.out.println("  [" + i + "] subject = " + cert.getSubjectX500Principal());
                    System.out.println("      issuer  = " + cert.getIssuerX500Principal());
                    System.out.println("      valid   = " + cert.getNotBefore() + "  ~  " + cert.getNotAfter());
                }
            }
        } catch (Exception e) {
            System.out.println("검증 실패");
            System.out.println("  " + e.getClass().getName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                System.out.println("  Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
        }
    }
}
```


## 7. 실행 결과와 해석

> 이 문서의 출력은 모두 **예시 출력**이다. 작성 시점에 작성자 환경에서 Docker 데몬이 실행 중이 아니어서 직접 실행 결과를 첨부하지 못했다. 다음 값은 환경마다 반드시 달라진다: 인증서 일련번호와 지문, 유효기간 날짜(발급 시점 기준 90일), 세션 ID, 협상된 암호 스위트, 핸드셰이크 소요 시간, `keytool` 출력의 한글·영문 여부(로캘), OpenSSL·JDK 버전 문자열. 구조(인증서 개수, `Verify return code` 값, 예외 클래스 이름)는 같아야 한다. 다르면 그것 자체가 조사할 거리다.

| 단계 | 봐야 할 출력 | 정상 해석 |
|---|---|---|
| 3-1 | SAN, `CA:FALSE`, `serverAuth` | 리프 인증서의 용도 제한 |
| 3-2 | `error 20` → `OK` | 중간 CA가 있어야 체인이 이어진다 |
| 3-3 | 체인 2장, `Verify return code: 0` | 서버는 리프+중간을 보낸다 |
| 3-4 | `TLSv1.3` vs `TLSv1.2` | 서버 설정이 버전을 결정한다 |
| 3-5 | `New` 1회 후 `Reused` | 세션 재개 동작 |
| 4-1 | 체인 1장, `error 20` / `PKIX path building failed` | 중간 CA 누락 |
| 4-2 | `Verify return code: 0`인데 curl·Java는 실패 | 호스트명 검증은 별도 단계 |
| 4-3 | `NotAfter:` 가 과거 | 만료 |
| 4-4 | 4-1과 **같은 메시지** | 신뢰 저장소 문제와 체인 누락은 메시지로 구분되지 않는다 |
| 5-2 | `검증 성공`, 인증서 2장 | 루트만 신뢰해도 충분하다 |

## 8. 검증

| # | 명령 | 기대 결과 |
|---|---|---|
| 1 | `[client] # openssl verify -CAfile /pki/out/ca.crt /pki/out/good.crt` | `error 20` (중간 없음) |
| 2 | `[client] # openssl verify -CAfile /pki/out/ca.crt -untrusted /pki/out/intermediate.crt /pki/out/good.crt` | `OK` |
| 3 | `[client] # openssl s_client -connect web:8444 -servername web </dev/null 2>/dev/null \| grep -c '^ [0-9] s:'` | `2` |
| 4 | `[client] # openssl s_client -connect web:8443 -servername web </dev/null 2>/dev/null \| grep -c '^ [0-9] s:'` | `1` |
| 5 | `[client] # curl -s -o /dev/null -w '%{ssl_verify_result}\n' --cacert /pki/out/ca.crt https://web:8444/` | `0` |
| 6 | `[client] # curl -sS --cacert /pki/out/ca.crt https://web:8445/` | `no alternative certificate subject name matches` |
| 7 | `[client] # openssl x509 -in /pki/out/expired.crt -checkend 0; echo $?` | `1` |
| 8 | `[java] # java TlsCheck.java https://web:8444/` (신뢰 저장소 없이) | `PKIX path building failed` |
| 9 | `[java] # java -Djavax.net.ssl.trustStore=/tmp/truststore.p12 ... TlsCheck.java https://web:8444/` | `검증 성공`, 인증서 2장 |
| 10 | `[java] # ... TlsCheck.java https://web:8446/` | `CertificateExpiredException: NotAfter:` |

Spring Boot 앱에서 같은 것을 확인하려면 `spring.ssl.bundle.pem.internal-ca.truststore.certificate`에 `ca.crt`를 지정하고 `RestClient`로 호출하면 된다(이론 6-2의 클래스). 이 장에서는 오류 메시지와 검증 단계의 대응을 보는 것이 목적이라 별도 애플리케이션은 두지 않았다. 인증서를 쓰는 Spring Boot 구성은 17장(Docker Compose 구성)에서 다룬다.

## 9. 변형 실습

**V1.** `curl -k`(검증 생략)로 8443·8445·8446에 접속하면 어떻게 될까? 그리고 그것이 왜 위험한가?

<details><summary>예상 결과</summary>

세 포트 모두 **200을 받는다.** 체인이 끊겼든, 이름이 다르든, 만료됐든 전부 무시하기 때문이다. 암호화는 여전히 되지만 "상대가 누구인지"를 확인하지 않으므로 중간자가 자기 인증서를 내밀어도 그대로 통신하게 된다(이론 2절의 위장·변조 위협이 되살아난다). Java의 `TrustAllCerts` 구현도 같은 효과다. 테스트 편의로 넣은 코드가 운영에 남는 사고가 반복되므로, 임시로 쓰더라도 커밋하지 않는 습관이 필요하다. `PLAN.md`의 7단계 시나리오 3(클라이언트가 공격자의 CA를 신뢰하면 가로채기가 성공한다)이 이것의 확장판이다.
</details>

**V2.** 신뢰 저장소에 루트 대신 **중간 CA만** 넣으면 8444 접속은 성공할까?

<details><summary>예상 결과</summary>

성공한다. 검증은 "신뢰 저장소에 있는 인증서까지 경로가 닿으면" 끝나므로, 중간 CA를 신뢰 앵커(trust anchor)로 삼으면 리프 → 중간에서 검증이 완료된다. 그런데도 실무에서 루트를 넣는 이유는 **중간 CA가 교체되기 때문**이다. CA는 중간 인증서를 주기적으로 갱신하는데, 중간을 앵커로 박아두면 그때 전면 장애가 난다. 루트는 수명이 길고 잘 바뀌지 않는다. `keytool -delete -alias lab-root ...` 후 `-alias lab-int -file /pki/out/intermediate.crt`로 직접 확인해볼 수 있다.
</details>

**V3.** `nginx/default.conf`의 8444 블록에서 풀체인 대신 순서를 뒤집은 파일을 쓰면? (`[client] # cat /pki/out/intermediate.crt /pki/out/good.crt > /tmp/reversed.crt` — 볼륨이 읽기 전용이므로 파일 생성 위치와 마운트 방식을 바꿔야 한다)

<details><summary>예상 결과</summary>

Nginx는 파일의 **첫 번째 인증서를 서버 인증서로** 간주한다. 순서를 뒤집으면 중간 CA 인증서가 서버 인증서가 되고, 지정한 개인키(`good.key`)와 맞지 않아 설정 검사에서 실패한다. `nginx -t`에 `SSL_CTX_use_PrivateKey_file(... ) failed (SSL: ... key values mismatch)` 같은 오류가 나온다. "체인 파일은 리프가 먼저"라는 규칙을 기억하는 방법이다. 이 실습에서는 `pki` 볼륨이 읽기 전용으로 마운트되어 있으므로, 확인하려면 compose에서 web의 `pki` 마운트를 쓰기 가능하게 바꾸거나 별도 파일을 마운트해야 한다.
</details>

## 10. 정리 (Clean-up)

```text
$ cd "C:\Study\Network\네트워크 기초\labs\06-tls"
$ docker compose down -v
```

- **`-v`가 필수다.** 이름 있는 볼륨 `ch06-lab_pki`에 CA 개인키와 서버 개인키가 들어 있다. 실습이 끝나면 반드시 삭제한다.
- 실습용 CA를 OS·브라우저 신뢰 저장소에 설치했다면(이 문서에서는 권하지 않았다) 반드시 제거한다. 남겨두면 그 CA 키를 가진 사람이 어떤 도메인이든 위조할 수 있다.

남은 것 확인:

```text
$ docker ps -a --filter "name=ch06-lab"        # 아무것도 없어야 함
$ docker volume ls --filter "name=ch06-lab"    # 아무것도 없어야 함
```

이미지 삭제(선택):

```text
$ docker image rm nginx:1.27-alpine alpine:3.20 eclipse-temurin:21.0.4_7-jdk-jammy
```

호스트 설정은 바꾸지 않았다. 호스트 포트 8443~8448을 쓰는 다른 프로세스가 있으면 기동 시 충돌하므로, 그때는 compose의 `ports`에서 호스트 쪽 번호만 바꾼다.
