# HTTP vs HTTPS 중간자(MITM) 학습 랩

> **목표**: HTTP(평문)와 HTTPS(TLS)의 차이를 "중간자 관점"에서 직접 패킷으로 확인한다.
> 소켓 → TCP → HTTP 평문 → TLS 암호화 → CA 신뢰체인 → TrustStore → MITM 방어/우회까지
> **하나의 줄기로** 이해하는 것이 최종 목표.
>
> 모든 실습은 **내 PC(localhost) 안에서 내가 띄운 프로그램끼리만** 진행한다. (교육 목적)

---

## 핵심 한 줄

> **HTTP = 평문이라 중간자가 다 읽는다 / HTTPS = TLS로 암호화돼서 중간자가 봐도 쓰레기값이다.**
> 그리고 **HTTPS가 MITM에 강한 이유는 "인증서 검증(CA + TrustStore)"** 때문이다.

## 실습 구조

```
[Client] ──▶ [MITM Proxy : 오가는 바이트를 화면에 찍음] ──▶ [Server]
              (8000)                                        (9000)
```

프록시가 "중간자" 역할. 같은 프록시로:
- **평문 트래픽** → 내용이 다 보임 (= HTTP)
- **TLS 트래픽** → 암호문만 보임 (= HTTPS)

이 **대비(對比)**가 학습의 핵심.

---

## 개념 미리보기: KeyStore vs TrustStore (Java에서 제일 헷갈리는 것)

| 구분 | 담는 것 | 누가 갖나 | 용도 |
|------|---------|-----------|------|
| **KeyStore** | 내 개인키 + 내 인증서 | 서버 | 자기 신원 증명 |
| **TrustStore** | 내가 믿는 CA 인증서들 | 클라이언트 | 상대(서버) 검증 |

신뢰 체인:
```
[Root CA] --서명--> [서버 인증서]
   ↑
클라이언트 TrustStore에 "이 CA는 믿어"라고 등록됨
   ↓
서버가 인증서 제시 → 클라가 "내가 믿는 CA가 서명했네" → 신뢰 성립
```

---

# 진행 체크리스트

각 단계는 **[이론]으로 개념 잡기 → [실무]로 눈으로 확인**하는 순서.

## 0단계 — 환경 준비 (실무)
- [ ] JDK 설치 확인 (`java -version`, `javac -version`)
- [ ] Wireshark 설치
- [ ] Npcap의 **loopback 캡처** 확인 (Windows에서 localhost 패킷 보려면 필수)
- [ ] 작업 폴더 `C:\Study\Network` 준비

## 1단계 — 소켓 / TCP 기초 (이론 + 실무)
- [ ] **[이론]** OSI / TCP-IP 계층, "소켓 = IP:포트 양 끝점", 스트림 개념
- [ ] **[이론]** TCP 3-way handshake (SYN → SYN/ACK → ACK)
- [ ] **[실무]** `PlainServer` + `PlainClient` (echo 서버) 작성 후 직접 붙여 메시지 왕복
- [ ] **[관찰]** Wireshark로 3-way handshake 눈으로 확인

## 2단계 — 중간자(프록시) 만들기 (이론 + 실무)
- [ ] **[이론]** MITM이 왜 가능한가 — "경로 위에 앉으면 평문은 다 읽힌다"
- [ ] **[이론]** 정상 프록시 vs 공격의 차이
- [ ] **[실무]** `MitmProxy` 작성: 8000에서 받아 9000으로 중계하며 **양방향 바이트를 hex+텍스트로 콘솔 덤프**
- [ ] **[관찰]** Client → Proxy → Server 로 경로 변경, `password=1234` 보내서 **프록시에 그대로 다 찍히는 것** 확인 → "이게 HTTP다"

## 3단계 — HTTP 본격 관찰 (이론 + 실무)
- [ ] **[이론]** HTTP 메시지 구조 (request line, 헤더, 바디)
- [ ] **[실무]** 서버를 HTTP 응답 흉내내게 수정하거나 `curl http://127.0.0.1:8000` 을 프록시로 통과
- [ ] **[관찰]** Wireshark "Follow TCP Stream"으로 요청/응답 전문 읽기

## 4단계 — HTTPS(TLS) 도입 (이론 + 실무) ⭐
- [ ] **[이론]** TLS handshake (ClientHello / ServerHello, 인증서, 키 교환, 대칭키 전환)
- [ ] **[이론]** 왜 암호화되는가 (세션키 협상 원리)
- [ ] **[실무]** `keytool`로 self-signed 인증서 생성 → `TlsServer` + `TlsClient` (`SSLSocket`)
- [ ] **[관찰]** 같은 MITM 프록시로 통과 → 콘솔엔 암호문 바이트만
- [ ] **[관찰]** Wireshark에서 `Application Data`가 암호화돼 내용 안 보임 → "이게 HTTPS다"

## 5단계 — 사설 CA로 신뢰 체인 만들기 (이론 + 실무)
- [ ] **[이론]** X.509 인증서, 서명, 신뢰 체인, CA의 역할
- [ ] **[실무]** `keytool`로:
  - [ ] root CA 키쌍 + CA 인증서 생성
  - [ ] 서버 키쌍 생성 → CSR(인증서 서명 요청) 생성
  - [ ] root CA가 서버 CSR에 **서명** → 서버 인증서 발급
  - [ ] 서버 KeyStore에 [서버 개인키 + 서버 인증서 + CA 인증서] 체인 구성
  - [ ] 클라이언트 **TrustStore에 root CA 인증서만** 등록

## 6단계 — 정상 HTTPS 성립 확인 (실무)
- [ ] **[실무]** `TlsServer`(KeyStore) ↔ `TlsClient`(우리 TrustStore) 연결
- [ ] **[관찰]** 에러 없이 handshake 성공 → "CA를 믿으니 서버도 믿는다"
- [ ] **[관찰]** TrustStore를 JVM 기본(`cacerts`)으로 바꾸면 → 다시 신뢰 실패 → **TrustStore가 신뢰의 근원**임을 확인

## 7단계 — MITM 재시도 & CA의 방어 (이론 + 실무) ⭐⭐
- [ ] **[이론]** MITM이 TLS를 가로채려면 "서버인 척" 인증서 필요 → **누가 서명하느냐**가 관건
- [ ] **[실무] 시나리오 1**: 프록시가 self-signed 제시 → TrustStore에 없음 → `SSLHandshakeException` (공격 실패) ✅
- [ ] **[실무] 시나리오 2**: 프록시가 우리 CA로 서명받은 가짜 인증서 제시 → (CA 키 유출 가정 시) 성공 → **"CA 키 보안이 전부다"**
- [ ] **[실무] 시나리오 3**: 클라 TrustStore에 프록시의 CA를 심으면 → 가로채기 성공 → **"기업 프록시/사내 MITM의 원리"**

## 8단계 — 정리 (이론)
- [ ] 브라우저 자물쇠 = "신뢰 저장소에 있는 CA가 서명한 유효 인증서"라는 뜻
- [ ] 인증서 경고를 무시하면 왜 위험한지
- [ ] HSTS / 인증서 고정(pinning)이 무엇을 막는지

---

# 최종적으로 남는 것
- TCP 핸드셰이크 → HTTP 평문 → TLS 암호화 → 인증서 검증까지 **한 줄기로 꿰어진 멘탈모델**
- Wireshark로 패킷 읽는 실무 감각
- 인증서 에러를 만났을 때 **KeyStore 문제인지 TrustStore 문제인지** 바로 판단
- "왜 HTTP는 위험하고 HTTPS는 안전한가"를 코드와 패킷 근거로 설명 가능

---

# 결정 사항 (진행 전 확정)
- **범위**: 1~8단계 전부
- **인증서 도구**: `keytool` 위주 (Java 서버 실습이라 자연스러움), 필요 시 `openssl` 보조
- **패킷 도구**: Wireshark + 프록시 콘솔 로그 병행
- **환경**: Windows 11 / localhost 전용 / 교육 목적

---

# 진행 로그
> 한 단계씩 끝날 때마다 여기 기록.

- (아직 없음)
