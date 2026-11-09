#!/usr/bin/env python3
"""CloudIDE sandbox server: lets the app's AI assistant run commands and work with files on YOUR server.

Standard library only. One workspace folder per session (the app sends X-Session); every path stays inside it.
Run it inside the Docker container from this folder (see README.md): the container is the sandbox, this script is only the door.

Environment:
  SANDBOX_TOKEN   required: the secret the app sends as "Authorization: Bearer <token>"
  SANDBOX_ROOT    workspaces folder (default /work)
  PORT            default 8080
  MAX_TIMEOUT     longest command in seconds (default 600)
"""
import hmac
import io
import json
import os
import re
import resource
import shutil
import signal
import subprocess
import sys
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ROOT = os.path.realpath(os.environ.get("SANDBOX_ROOT", "/work"))
TOKEN = os.environ.get("SANDBOX_TOKEN", "")
PORT = int(os.environ.get("PORT", "8080"))
MAX_TIMEOUT = int(os.environ.get("MAX_TIMEOUT", "600"))
MAX_OUT = 200_000            # characters of output returned
MAX_BODY = 300 * 1024 * 1024  # largest upload
MAX_UNZIPPED = 500 * 1024 * 1024
MAX_ENTRIES = 50_000
MAX_WORKSPACE_BYTES = 2 * 1024 * 1024 * 1024
SESSION_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
LOCKS = {}
LOCKS_GUARD = threading.Lock()


def lock_for(session):
    with LOCKS_GUARD:
        return LOCKS.setdefault(session, threading.Lock())


def workspace(session):
    if not SESSION_RE.match(session):
        raise ValueError("bad session id")
    p = os.path.join(ROOT, session)
    os.makedirs(p, exist_ok=True)
    return os.path.realpath(p)


def resolve(ws, rel):
    rel = (rel or "").replace("\\", "/").strip()
    if rel.startswith("/") or re.match(r"^[A-Za-z]:", rel) or "\0" in rel or ".." in rel.split("/"):
        raise ValueError("the path must stay inside the workspace")
    p = os.path.realpath(os.path.join(ws, rel)) if rel not in ("", ".") else ws
    if p != ws and not p.startswith(ws + os.sep):
        raise ValueError("the path must stay inside the workspace")
    return p


def human(n):
    if n < 1024:
        return "%d B" % n
    if n < 1024 * 1024:
        return "%.1f KB" % (n / 1024)
    return "%.1f MB" % (n / 1024 / 1024)


def tree(ws, d, depth, maxn=400):
    lines, count = [], [0]

    def walk(p, level):
        try:
            names = sorted(os.listdir(p), key=lambda x: (not os.path.isdir(os.path.join(p, x)), x.lower()))
        except OSError:
            return
        for n in names:
            if level == 0 and p == ws and n == ".tmp":
                continue
            f = os.path.join(p, n)
            count[0] += 1
            if count[0] <= maxn:
                if os.path.isdir(f) and not os.path.islink(f):
                    lines.append("  " * level + n + "/")
                else:
                    try:
                        lines.append("  " * level + n + "  (" + human(os.path.getsize(f)) + ")")
                    except OSError:
                        lines.append("  " * level + n)
            if os.path.isdir(f) and not os.path.islink(f) and level + 1 < depth:
                walk(f, level + 1)

    walk(d, 0)
    if count[0] > maxn:
        lines.append("... and more (%d not shown)" % (count[0] - maxn))
    return "\n".join(lines) + "\n" if lines else "(empty)\n"


def workspace_size(ws):
    total = 0
    for dp, _, fs in os.walk(ws):
        for f in fs:
            try:
                total += os.lstat(os.path.join(dp, f)).st_size
            except OSError:
                pass
    return total


def limits():
    # applied in the child before exec: a runaway command cannot eat the machine
    resource.setrlimit(resource.RLIMIT_CPU, (MAX_TIMEOUT + 30, MAX_TIMEOUT + 30))
    resource.setrlimit(resource.RLIMIT_FSIZE, (1024 * 1024 * 1024, 1024 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_NPROC, (512, 512))
    os.setsid()


def run(ws, command, cwd, timeout):
    env = {
        "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
        "HOME": ws, "LANG": "C.UTF-8", "TERM": "dumb", "PYTHONUNBUFFERED": "1", "TMPDIR": os.path.join(ws, ".tmp"),
        "CI": "1", "DEBIAN_FRONTEND": "noninteractive",
    }
    os.makedirs(env["TMPDIR"], exist_ok=True)
    p = subprocess.Popen(["/bin/sh", "-c", command], cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, preexec_fn=limits)
    out = []
    size = [0]

    def pump():
        while True:
            chunk = p.stdout.read(4096)
            if not chunk:
                break
            if size[0] < MAX_OUT * 4:
                out.append(chunk)
                size[0] += len(chunk)

    t = threading.Thread(target=pump, daemon=True)
    t.start()
    timed_out = False
    try:
        p.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except OSError:
            p.kill()
        p.wait()
    t.join(5)
    try:  # background children that keep the pipe open
        os.killpg(p.pid, signal.SIGKILL)
    except OSError:
        pass
    text = b"".join(out).decode("utf-8", "replace")
    if len(text) > MAX_OUT:
        text = text[:MAX_OUT // 2] + "\n[... output cut ...]\n" + text[-MAX_OUT // 2:]
    return text, p.returncode if p.returncode is not None else 1, timed_out


def safe_unzip(zpath, dest):
    base = os.path.realpath(dest)
    os.makedirs(base, exist_ok=True)
    files = dirs = skipped = 0
    total = 0
    with zipfile.ZipFile(zpath) as z:
        infos = z.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ValueError("the zip has too many entries (%d)" % len(infos))
        for i in infos:
            name = i.filename.replace("\\", "/")
            if name.startswith("/") or re.match(r"^[A-Za-z]:", name) or "\0" in name or ".." in name.split("/"):
                skipped += 1
                continue
            if (i.external_attr >> 16) & 0o170000 == 0o120000:  # a symlink entry
                skipped += 1
                continue
            out = os.path.realpath(os.path.join(base, name))
            if out != base and not out.startswith(base + os.sep):
                skipped += 1
                continue
            if i.is_dir():
                os.makedirs(out, exist_ok=True)
                dirs += 1
                continue
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with z.open(i) as src, open(out, "wb") as dst:
                while True:
                    b = src.read(65536)
                    if not b:
                        break
                    total += len(b)
                    if total > MAX_UNZIPPED:
                        raise ValueError("the zip is larger than %s unpacked" % human(MAX_UNZIPPED))
                    dst.write(b)
            if i.external_attr >> 16 & 0o111:
                os.chmod(out, 0o755)
            files += 1
    s = "%d file%s" % (files, "" if files == 1 else "s")
    if dirs:
        s += ", %d folder%s" % (dirs, "" if dirs == 1 else "s")
    s += ", " + human(total)
    if skipped:
        s += ", %d entries skipped (unsafe path or link)" % skipped
    return s


def zip_to_bytes(src):
    buf = io.BytesIO()
    total = 0
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        if os.path.isdir(src):
            for dp, dn, fs in os.walk(src):
                dn[:] = [d for d in dn if not os.path.islink(os.path.join(dp, d))]
                for f in sorted(fs):
                    fp = os.path.join(dp, f)
                    if os.path.islink(fp):
                        continue
                    total += os.path.getsize(fp)
                    if total > 500 * 1024 * 1024:
                        raise ValueError("the result is too large to zip")
                    z.write(fp, os.path.relpath(fp, src))
        else:
            z.write(src, os.path.basename(src))
    return buf.getvalue()


class H(BaseHTTPRequestHandler):
    server_version = "CloudIDEsandbox/1"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def send(self, code, obj=None, raw=None, ctype="application/json", extra=None):
        body = raw if raw is not None else json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def err(self, code, msg):
        self.send(code, {"error": msg})

    def authed(self):
        got = self.headers.get("Authorization", "")
        want = "Bearer " + TOKEN
        return bool(TOKEN) and hmac.compare_digest(got.encode(), want.encode())

    def body(self, limit):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("the body is too large")
        return self.rfile.read(n)

    def handle_any(self, method):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if not self.authed():
            # drain nothing: close the connection so a large unauthorised upload is not read
            self.close_connection = True
            return self.err(401, "bad or missing token")
        session = self.headers.get("X-Session", "default")
        try:
            ws = workspace(session)
        except ValueError as e:
            return self.err(400, str(e))
        route = (method, u.path)
        try:
            if route == ("GET", "/health"):
                return self.send(200, {"ok": True, "info": "CloudIDE sandbox server, workspace %s" % session})
            with lock_for(session) if route[1] in ("/exec", "/unzip", "/reset") else _null():
                if route == ("POST", "/exec"):
                    d = json.loads(self.body(1_000_000) or b"{}")
                    cmd = str(d.get("command", ""))
                    if not cmd.strip():
                        return self.err(400, "command is empty")
                    cwd = resolve(ws, str(d.get("cwd", "")))
                    if not os.path.isdir(cwd):
                        return self.err(400, "cwd is not a folder")
                    timeout = max(1, min(MAX_TIMEOUT, int(d.get("timeout", 120))))
                    if workspace_size(ws) > MAX_WORKSPACE_BYTES:
                        return self.err(507, "the workspace is full: call reset or delete files")
                    out, code, to = run(ws, cmd, cwd, timeout)
                    return self.send(200, {"out": out, "code": code, "timed_out": to})
                if route == ("GET", "/list"):
                    d = resolve(ws, q.get("path", ""))
                    if not os.path.isdir(d):
                        return self.err(404, "not a folder")
                    return self.send(200, {"text": tree(ws, d, max(1, min(6, int(q.get("depth", "2")))))})
                if route == ("GET", "/read"):
                    f = resolve(ws, q.get("path", ""))
                    if not os.path.isfile(f):
                        return self.err(404, "not a file")
                    mx = max(1000, min(400_000, int(q.get("max", "100000"))))
                    with open(f, "rb") as fh:
                        data = fh.read(mx * 4 + 1)
                    if b"\0" in data[:8000]:
                        return self.err(415, "binary file (%s)" % human(os.path.getsize(f)))
                    text = data.decode("utf-8", "replace")
                    if len(text) > mx or os.path.getsize(f) > len(data):
                        text = text[:mx] + "\n[cut: %s in all]" % human(os.path.getsize(f))
                    return self.send(200, {"text": text})
                if route == ("PUT", "/file"):
                    f = resolve(ws, q.get("path", ""))
                    if f == ws or os.path.isdir(f):
                        return self.err(400, "give a file path")
                    n = int(self.headers.get("Content-Length") or 0)
                    if n > MAX_BODY:
                        self.close_connection = True
                        return self.err(413, "the upload is too large")
                    os.makedirs(os.path.dirname(f), exist_ok=True)
                    left = n
                    with open(f, "wb") as fh:
                        while left > 0:
                            b = self.rfile.read(min(65536, left))
                            if not b:
                                break
                            fh.write(b)
                            left -= len(b)
                    return self.send(200, {"ok": True, "bytes": n})
                if route == ("POST", "/unzip"):
                    d = json.loads(self.body(100_000) or b"{}")
                    zp = resolve(ws, str(d.get("path", "")))
                    dest = resolve(ws, str(d.get("to", "")) or os.path.splitext(os.path.basename(zp))[0])
                    if not os.path.isfile(zp):
                        return self.err(404, "the zip is not there")
                    try:
                        return self.send(200, {"summary": safe_unzip(zp, dest)})
                    except zipfile.BadZipFile:
                        return self.err(400, "that is not a valid zip file")
                if route == ("GET", "/download"):
                    f = resolve(ws, q.get("path", ""))
                    if not os.path.exists(f):
                        return self.err(404, "no such file or folder")
                    if os.path.isdir(f):
                        return self.send(200, raw=zip_to_bytes(f), ctype="application/zip")
                    with open(f, "rb") as fh:
                        return self.send(200, raw=fh.read(), ctype="application/octet-stream")
                if route == ("POST", "/reset"):
                    shutil.rmtree(ws, ignore_errors=True)
                    workspace(session)
                    return self.send(200, {"ok": True})
            return self.err(404, "unknown endpoint")
        except ValueError as e:
            return self.err(400, str(e))
        except Exception as e:  # never leak a traceback to the client
            sys.stderr.write("error: %r\n" % (e,))
            return self.err(500, "server error")

    def do_GET(self): self.handle_any("GET")

    def do_POST(self): self.handle_any("POST")

    def do_PUT(self): self.handle_any("PUT")


class _null:
    def __enter__(self): return self

    def __exit__(self, *a): return False


def main():
    if not TOKEN or len(TOKEN) < 16:
        sys.exit("Set SANDBOX_TOKEN to a secret of at least 16 characters (for example: openssl rand -hex 24).")
    os.makedirs(ROOT, exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), H)
    srv.daemon_threads = True
    print("CloudIDE sandbox server on port %d, workspaces in %s" % (PORT, ROOT), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
