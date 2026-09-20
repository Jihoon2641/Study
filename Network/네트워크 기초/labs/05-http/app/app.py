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
