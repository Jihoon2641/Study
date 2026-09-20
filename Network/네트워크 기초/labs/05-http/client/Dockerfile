# curl: HTTP/1.1·HTTP/2 클라이언트 / nghttp2: h2load(부하), nghttp(프레임 관찰)
# tcpdump, iproute2: 커넥션과 패킷 관찰 (3장 도구와 동일)
# 알파인 저장소에서 nghttp2 도구 패키지 이름이 버전에 따라 다를 수 있어 둘 중 되는 쪽을 설치한다
FROM alpine:3.20
RUN apk add --no-cache curl tcpdump iproute2 \
 && (apk add --no-cache nghttp2 || apk add --no-cache nghttp2-tools)
WORKDIR /lab
