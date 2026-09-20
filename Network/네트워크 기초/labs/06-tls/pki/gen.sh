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
