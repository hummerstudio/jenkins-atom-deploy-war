#!/usr/bin/env python3
"""A Tomcat Manager "text" endpoint, written from the protocol description, not from the atom.

It exists so the smoke test can check what the atom actually sends and what the container would answer,
without a Tomcat installation on the machine. It reproduces the parts that matter:

  · the endpoints the atom uses (PUT /manager/text/deploy?path=...&update=..., GET /manager/text/list);
  · HTTP 200 for failures: Tomcat signals "application already exists" with a 200 and a FAIL line;
  · the reply prefix is localised: --locale zh (the default here) answers "失败 - ...", en answers "FAIL - ...",
    exactly as a Chinese-locale server does, so an atom that greps for "FAIL" is caught;
  · the WAR bytes are hashed as they arrive and written to disk, so the test can prove the file that
    landed is byte-for-byte the file that was on the workspace, and can open what was deployed.

Failure shapes are switches, not code paths: --deny, --no-register, --stopped, --conflict, --status 500,
--short-write, --manager-path. Every request is appended to <state>/requests.jsonl.
"""
import argparse
import base64
import hashlib
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HEADERS = {
    "en": ("OK - Listed applications for virtual host [{host}]", "OK - Deployed application at context path {path}",
           "FAIL - Application already exists at path [{path}]", "FAIL - Invalid context path [{path}]"),
    "zh": ("OK - 虚拟主机[{host}]下的应用程序列表", "OK - 以应用path [{path}] 部署应用",
           "失败 - 应用程序已存在于路径 [{path}]", "失败 - 指定了无效到上下文路径[{path}]"),
}


class Manager:
    def __init__(self, args):
        self.args = args
        self.apps = {}          # context path ("/" for ROOT) -> record of what was deployed
        self.requests = []
        os.makedirs(args.state, exist_ok=True)
        os.makedirs(os.path.join(args.state, "apps"), exist_ok=True)

    def record(self, entry):
        self.requests.append(entry)
        with open(os.path.join(self.args.state, "requests.jsonl"), "a", encoding="utf-8") as fh:
            fh.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def snapshot(self):
        with open(os.path.join(self.args.state, "apps.json"), "w", encoding="utf-8") as fh:
            json.dump(self.apps, fh, ensure_ascii=False, indent=1, sort_keys=True)


def make_handler(manager):
    args = manager.args
    text = HEADERS[args.locale]

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *a):
            pass

        def _send(self, code, body):
            payload = body.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "text/plain;charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            if code == 401:
                self.send_header("WWW-Authenticate", 'Basic realm="Tomcat Manager Application"')
            self.end_headers()
            self.wfile.write(payload)

        def _authorized(self):
            header = self.headers.get("Authorization", "")
            if not header.startswith("Basic "):
                return False, ""
            try:
                raw = base64.b64decode(header[6:]).decode("utf-8")
            except Exception:
                return False, ""
            user, _, password = raw.partition(":")
            if args.deny:
                return False, user
            return (user == args.user and password == args.password), user

        def _path(self):
            parsed = urlparse(self.path)
            return parsed.path, parse_qs(parsed.query, keep_blank_values=True)

        def do_PUT(self):
            path, query = self._path()
            if path != args.manager_path + "/deploy":
                manager.record({"method": "PUT", "path": path, "note": "wrong endpoint"})
                self._send(404, "<html>404</html>")
                return
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            digest = hashlib.sha256(body).hexdigest()
            ok, user = self._authorized()
            ctx = query.get("path", [""])[0]
            update = query.get("update", [""])[0].lower() in ("true", "1", "yes")
            manager.record({
                "method": "PUT",
                "path": path,
                "params": {"path": ctx, "update": update},
                "authorized": ok,
                "user": user,
                "bytesReceived": len(body),
                "sha256": digest,
                "contentType": self.headers.get("Content-Type", ""),
                "locale": args.locale,
            })
            if not ok:
                self._send(401, "401 Unauthorized")
                return
            if args.status >= 500:
                self._send(args.status, "internal error while deploying")
                return
            if ctx and not ctx.startswith("/"):
                self._send(200, text[3].format(path=ctx) + "\n")
                return
            key = ctx if ctx else "/"
            if key in manager.apps and not update:
                self._send(200, text[2].format(path=key) + "\n")
                return
            if args.conflict:
                self._send(200, text[2].format(path=key) + "\n")
                return
            stored = body[:-100] if args.short_write else body
            war_name = (key.strip("/") or "ROOT") + ".war"
            with open(os.path.join(args.state, "apps", war_name), "wb") as fh:
                fh.write(stored)
            if not args.no_register:
                manager.apps[key] = {
                    "state": "stopped" if args.stopped else "running",
                    "sha256": hashlib.sha256(stored).hexdigest(),
                    "bytes": len(stored),
                    "war": war_name,
                    "displayName": war_name,
                }
                manager.snapshot()
            self._send(200, text[1].format(path=key) + "\n")

        def do_GET(self):
            path, query = self._path()
            if path != args.manager_path + "/list":
                manager.record({"method": "GET", "path": path, "note": "wrong endpoint"})
                self._send(404, "<html>404</html>")
                return
            ok, user = self._authorized()
            manager.record({"method": "GET", "path": path, "authorized": ok, "user": user})
            if not ok:
                self._send(401, "401 Unauthorized")
                return
            if args.status >= 500:
                self._send(args.status, "internal error while listing")
                return
            lines = [text[0].format(host="localhost")]
            for key, app in manager.apps.items():
                lines.append("{p}:{s}:0:{n}".format(p=key, s=app["state"], n=app["displayName"]))
            body = "\n".join(lines) + "\n"
            with open(os.path.join(args.state, "list.txt"), "w", encoding="utf-8") as fh:
                fh.write(body)
            self._send(200, body)

        def do_POST(self):
            manager.record({"method": "POST", "path": self.path})
            self._send(405, "405")

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", required=True)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--user", default="deployer")
    parser.add_argument("--password", default="s3cret-deploy")
    parser.add_argument("--manager-path", default="/manager/text")
    parser.add_argument("--locale", choices=["en", "zh"], default="zh")
    parser.add_argument("--deny", action="store_true", help="answer 401 to every request")
    parser.add_argument("--no-register", action="store_true", help="answer OK but do not register the app")
    parser.add_argument("--stopped", action="store_true", help="register the app in state stopped")
    parser.add_argument("--conflict", action="store_true", help="always answer 'already exists'")
    parser.add_argument("--status", type=int, default=200)
    parser.add_argument("--short-write", action="store_true", help="store the WAR minus its last 100 bytes")
    args = parser.parse_args()

    manager = Manager(args)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(manager))
    print(json.dumps({"port": server.server_address[1], "managerPath": args.manager_path,
                      "locale": args.locale}), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
