# glibc 리졸버 동작을 보기 위해 Debian 기반 (Alpine은 musl이라 동작이 다름 → 변형 실습 V2)
# dnsutils: dig / iproute2: ss, ip / tcpdump: 53번 포트 관찰
FROM debian:12.7-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends dnsutils iproute2 tcpdump \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /lab
