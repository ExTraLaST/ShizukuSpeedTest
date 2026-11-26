#!/usr/bin/env python3
"""
Shizuku测速 本地局域网测速服务端 (local_speed_server.py)

在电脑上运行本脚本后，在手机 App「设置 -> 测速节点」中添加「本地服务」节点：
  http://<电脑局域网IP>:8899

支持接口（与 SpeedNode.PROTOCOL_LOCAL 完全对齐）：
  - GET  /ping             延迟探测
  - GET  /down?bytes=<N>   下载流式测试（最大单次 512 MB）
  - POST /up               上传流式测试（支持 chunked 传输编码与 Content-Length，客户端超时断开不报错）
"""

import argparse
import os
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

CHUNK_SIZE = 65536
MAX_DOWN_BYTES = 512 * 1024 * 1024
PAYLOAD_CHUNK = os.urandom(CHUNK_SIZE)


def get_lan_ips() -> list[str]:
    ips: list[str] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            if ip and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return ips


class SpeedTestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args) -> None:
        return

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/ping":
            body = b"ok"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            return

        if parsed.path == "/down":
            params = parse_qs(parsed.query)
            try:
                total = int(params.get("bytes", ["10485760"])[0])
            except ValueError:
                total = 10485760
            total = max(0, min(total, MAX_DOWN_BYTES))

            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(total))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()

            remaining = total
            try:
                while remaining > 0:
                    if remaining >= CHUNK_SIZE:
                        self.wfile.write(PAYLOAD_CHUNK)
                        remaining -= CHUNK_SIZE
                    else:
                        self.wfile.write(PAYLOAD_CHUNK[:remaining])
                        remaining = 0
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            return

        body = b"ShizukuSpeedTest local server. Endpoints: /ping, /down?bytes=N, /up\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def _drain_chunked(self) -> int:
        total = 0
        while True:
            line = self.rfile.readline(65536)
            if not line:
                break
            size_str = line.split(b";", 1)[0].strip()
            if not size_str:
                continue
            chunk_len = int(size_str, 16)
            if chunk_len == 0:
                while True:
                    trailer = self.rfile.readline(65536)
                    if not trailer or trailer in (b"\r\n", b"\n"):
                        break
                break
            remaining = chunk_len
            while remaining > 0:
                data = self.rfile.read(min(CHUNK_SIZE, remaining))
                if not data:
                    return total
                remaining -= len(data)
                total += len(data)
            self.rfile.read(2)  # trailing CRLF
        return total

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/up":
            self.send_error(404, "Not Found")
            return

        received = 0
        try:
            te = (self.headers.get("Transfer-Encoding") or "").lower()
            if "chunked" in te:
                received = self._drain_chunked()
            else:
                length = int(self.headers.get("Content-Length") or "0")
                remaining = max(0, length)
                while remaining > 0:
                    data = self.rfile.read(min(CHUNK_SIZE, remaining))
                    if not data:
                        break
                    remaining -= len(data)
                    received += len(data)
        except (BrokenPipeError, ConnectionResetError, ValueError, OSError):
            # App v1.8+ disconnects directly when upload duration expires
            return

        body = f"{received}\n".encode("utf-8")
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass


def main() -> None:
    parser = argparse.ArgumentParser(description="Shizuku测速 本地局域网测速服务")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址（默认 0.0.0.0）")
    parser.add_argument("--port", type=int, default=8899, help="监听端口（默认 8899）")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), SpeedTestHandler)
    print(f"Shizuku测速 本地服务已启动，监听 {args.host}:{args.port}")
    lan_ips = get_lan_ips()
    if lan_ips:
        print("请在手机 App「设置 -> 测速节点」中添加本地服务节点：")
        for ip in lan_ips:
            print(f"  http://{ip}:{args.port}")
    print("按 Ctrl+C 停止服务。")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已停止。")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
