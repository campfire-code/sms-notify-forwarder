#!/usr/bin/env python3
"""验收用的本地接收服务：把收到的 POST 请求体原样打印并追加到 received.jsonl。
用法：python3 samples/receiver.py [端口，默认 8765]
模拟器里访问宿主机用 http://10.0.2.2:8765/hook ；真机用电脑的局域网 IP。"""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8")
        print(f"[收到] {self.path} Content-Type={self.headers.get('Content-Type')}\n{body}\n", flush=True)
        with open("received.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps({"path": self.path, "body": json.loads(body)}, ensure_ascii=False) + "\n")
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(b'{"ok":true}')
    def log_message(self, *a): pass

HTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 8765), H).serve_forever()
