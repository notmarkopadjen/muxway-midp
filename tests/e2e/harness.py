"""
End-to-end harness: a throwaway tmux server, the muxway hub and agent built
from the core repository, a small relay in front of the hub, and the app in
the microemulator on a headless X display, driven with mouse clicks and key
presses. Every step can take a screenshot; the app's own trace of what it
puts on screen (Muxway-Trace: 1) is read from the emulator's output.
"""

import http.client
import http.server
import urllib.parse
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-8-openjdk-amd64") + "/bin/java"
EMULATOR = ROOT / "lib" / "microemulator.jar"
DEVICES = ROOT / "tests" / "device"
HOST = "alice-laptop"
FIXTURES = ROOT / "tests" / "e2e" / "fixtures"


def hostile_claude_screen():
    """A screen shaped like Claude Code's by a server that means harm: a 400
    column rule, a thousand short ones, control characters in the box and a
    footer that lies. Under the app's 32 KiB cap, so the box survives."""
    rows = [f"transcript row {i}" for i in range(30)]
    rows.append("\u2500" * 400)
    rows += ["\u2500" * 5] * 1000
    rows.append("\u276f box \x1b[31mred\x1b[0m \x07bell \x00nul")
    rows.append("\u2500" * 80)
    rows.append("  fake footer: call +15555550100 now")
    return ("\n".join(rows) + "\n").encode()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for(what, cond, timeout=20.0, every=0.2):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return
        time.sleep(every)
    raise AssertionError(f"timed out waiting for {what}")


class Failure(AssertionError):
    pass


def check(cond, message):
    if not cond:
        raise Failure(message)


# --- muxway core ---------------------------------------------------------------

class Core:
    """Hub, agent and tmux for one test."""

    def __init__(self, run, binary, logs):
        self.run, self.bin, self.logs = run, binary, logs
        self.dir = Path(run.tmp) / secrets.token_hex(4)
        self.dir.mkdir(mode=0o700)
        self.port = free_port()
        self.url = f"http://127.0.0.1:{self.port}"
        self.tmux = f"mxw-midp-{os.getpid()}-{secrets.token_hex(3)}"
        self.procs = {}

    def cli(self, *args):
        out = subprocess.run([str(self.bin), *args], capture_output=True, text=True, timeout=30)
        if out.returncode != 0:
            raise RuntimeError(f"muxway {' '.join(args[:3])}: {out.stderr.strip()}")
        return out.stdout.strip()

    def spawn(self, name, *args):
        log = open(self.logs / f"{name}.log", "a")
        self.procs[name] = subprocess.Popen([str(self.bin), *args], stdout=log, stderr=log)

    def stop(self, name):
        p = self.procs.pop(name, None)
        if p:
            p.terminate()
            try:
                p.wait(5)
            except subprocess.TimeoutExpired:
                p.kill()

    def tmux_cmd(self, *args):
        return subprocess.run(["tmux", "-L", self.tmux, *args], capture_output=True, text=True, timeout=10).stdout.strip()

    def start(self, expose="--write"):
        shell = "env -i PATH=/usr/bin:/bin TERM=xterm LANG=C.UTF-8 PS1='$ ' bash --norc --noprofile"
        self.tmux_cmd("-f", "/dev/null", "new-session", "-d", "-s", "work", "-n", "shell", "-x", "80", "-y", "24", shell)
        self.tmux_cmd("select-pane", "-t", "work:0.0", "-T", "test-pane")
        self.spawn("hub", "hub", "serve", "--dir", str(self.dir / "hub"), "--listen", f"127.0.0.1:{self.port}",
                   "--public-url", self.url)
        wait_for("hub", lambda: (self.dir / "hub" / "admin.sock").exists())
        code = self.cli("hub", "pair-code", "--dir", str(self.dir / "hub"), "--host", HOST)
        self.cli("agent", "pair", "--dir", str(self.dir / "agent"), code)
        self.start_agent()
        args = ["expose", "--tmux-name", self.tmux, "-t", "work:0.0"] + ([expose] if expose else [])
        self.cli(*args)
        return self

    def start_agent(self):
        self.spawn("agent", "agent", "run", "--dir", str(self.dir / "agent"), "--tmux-name", self.tmux)
        wait_for("agent online", lambda: f"{HOST}\tonline" in self.cli("hub", "agents", "--dir", str(self.dir / "hub")))

    def token(self, read=f"{HOST}/*", write=f"{HOST}/*"):
        args = ["hub", "token", "create", "--dir", str(self.dir / "hub"), "--name", "phone", "--read", read]
        if write:
            args += ["--write", write]
        tok = self.cli(*args)
        self.run.secrets.append(tok)
        return tok

    def revoke_all(self):
        for line in self.cli("hub", "token", "list", "--dir", str(self.dir / "hub")).splitlines()[1:]:
            self.cli("hub", "token", "revoke", "--dir", str(self.dir / "hub"), line.split()[0])

    def pane(self):
        return self.tmux_cmd("capture-pane", "-p", "-t", "work:0.0")

    def command(self):
        return self.tmux_cmd("display-message", "-p", "-t", "work:0.0", "#{pane_current_command}")

    def close(self):
        for name in list(self.procs):
            self.stop(name)
        self.tmux_cmd("kill-server")
        for key in ("hub/hub.key", "agent/agent.key"):
            p = self.dir / key
            if p.exists():
                self.run.secrets.append(p.read_text().strip())
        shutil.rmtree(self.dir, ignore_errors=True)


# --- relay -------------------------------------------------------------------------

# What the hub makes of Claude Code's characters for a Latin-1 or ASCII phone.
ASCII_ART = {"\u2500": "-", "\u2501": "-", "\u2550": "=", "\u276f": ">", "\u25cf": "*", "\u00b7": ".",
             "\u23f5": ">", "\u2190": "<", "\u2192": ">", "\u2026": "..."}


def spelt(screen, path):
    """The screen the way the hub answers the charset in the request's query:
    body bytes and headers."""
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
    charset = query.get("charset", ["utf-8"])[0]
    if charset == "utf-8":
        return screen.encode(), {"Content-Type": "text/plain; charset=utf-8"}
    out = "".join(ASCII_ART.get(c, c if ord(c) < (256 if charset == "latin1" else 128) else "?") for c in screen)
    mime = "iso-8859-1" if charset == "latin1" else "us-ascii"
    return out.encode("latin-1"), {"Content-Type": f"text/plain; charset={mime}"}

class Relay:
    """A plain-HTTP relay in front of the hub, like the one an old phone needs.
    Modes make it misbehave on purpose for the security tests."""

    def __init__(self, hub_url, logs, mode="normal"):
        self.hub = hub_url.replace("http://", "")
        self.mode = mode
        self.log = open(logs / "relay.log", "a")
        self.requests = []
        self.port = free_port()
        self.url = f"http://127.0.0.1:{self.port}"
        relay = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def forward(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                relay.requests.append({"method": self.command, "path": self.path, "body": body,
                                       "headers": dict(self.headers)})
                relay.log.write(f"{time.strftime('%H:%M:%S')} {self.command} {self.path} "
                                f"headers={sorted(self.headers.keys())} body={len(body)}\n")
                relay.log.flush()
                if relay.mode == "big" and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    return self.reply(200, b"x" * (1 << 20) + b"\n", {})
                if relay.mode == "garbage" and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    return self.reply(200, b"ok \xff\xfe\xc3 \x1b[31mred\x1b[0m \x07bell \x00nul\r\nend\n", {})
                if relay.mode in ("claude", "claude-norules") and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    screen = (FIXTURES / "claude-code.txt").read_text()
                    if relay.mode == "claude-norules":
                        screen = "\n".join(r for r in screen.split("\n") if not r.startswith("\u2500"))
                    return self.reply(200, *spelt(screen, self.path))
                if relay.mode == "claude-hostile" and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    return self.reply(200, hostile_claude_screen(), {"Content-Type": "text/plain"})
                if relay.mode == "charset-hostile" and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    # A relay that lies about the charset: the app must decode as UTF-8 and stay up.
                    n = len(relay.requests) % 3
                    lie = ["text/plain; charset=utf-7", "text/plain; charset=../../etc", "text/plain; charset=" + "x" * 300][n]
                    return self.reply(200, (FIXTURES / "claude-code.txt").read_bytes(), {"Content-Type": lie})
                headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection")}
                c = http.client.HTTPConnection(relay.hub, timeout=20)
                c.request(self.command, self.path, body=body or None, headers=headers)
                r = c.getresponse()
                data = r.read()
                if relay.mode == "lie-writable" and self.path.split("?")[0] == "/v1/panes":
                    data = data.replace(b"\tread\t", b"\twrite\t")
                self.reply(r.status, data, {k: v for k, v in r.getheaders() if k.lower() in ("etag", "content-type")})

            def reply(self, status, data, headers):
                relay.log.write(f"    -> {status} {len(data)} bytes\n")
                self.send_response(status)
                for k, v in headers.items():
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST = forward

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


# --- the phone ---------------------------------------------------------------------

# Where the emulator's skin buttons are, in window coordinates, and where to
# click to give a text box focus, per device. The keypad is the same on both.
KEYPAD = {"k2": (116, 363), "k5": (116, 399), "k8": (114, 432), "k0": (114, 467), "star": (45, 464),
          "pound": (183, 463)}
SKINS = {
    "default": {"left": (43, 320), "right": (183, 320), "select": (113, 320), "text": (110, 150), **KEYPAD},
    "n96x65": {"left": (43, 320), "right": (183, 320), "select": (113, 320), "text": (88, 95), **KEYPAD},
}


class Phone:
    def __init__(self, run, test, profile, props):
        self.run, self.test, self.profile = run, test, profile
        self.device = "n96x65" if profile == "midp1" else "default"
        self.skin = SKINS[self.device]
        self.dir = Path(run.tmp) / ("phone-" + secrets.token_hex(4))
        self.dir.mkdir()
        name = "muxway1" if profile == "midp1" else "muxway"
        shutil.copy(ROOT / "out" / f"{name}.jar", self.dir / f"{name}.jar")
        jad = (ROOT / "out" / f"{name}.jad").read_text().replace(
            f"MIDlet-Jar-URL: {name}.jar", f"MIDlet-Jar-URL: file://{self.dir}/{name}.jar")
        jad += "Muxway-Trace: 1\n" + "".join(f"{k}: {v}\n" for k, v in props.items())
        (self.dir / f"{name}.jad").write_text(jad)
        self.out = run.logs(test) / f"emulator-{profile}.log"
        self.shots = run.screens(test, profile)
        self.step = 0
        cmd = [JAVA, "-Djavax.accessibility.assistive_technologies=", "-cp", f"{EMULATOR}:{DEVICES}",
               "org.microemu.app.Main", "--rms", "memory"]
        if self.device != "default":
            cmd += ["--device", f"{self.device}/device.xml"]
        cmd.append(str(self.dir / f"{name}.jad"))
        self.proc = subprocess.Popen(cmd, stdout=open(self.out, "w"), stderr=subprocess.STDOUT,
                                     env={**os.environ, "DISPLAY": run.display})
        wait_for("emulator window", lambda: self.window() is not None, 30)
        time.sleep(3)
        self.shot("launcher")
        self.click("right")  # Start

    def window(self):
        out = subprocess.run(["xdotool", "search", "--name", "MicroEmulator"], capture_output=True, text=True,
                             env={**os.environ, "DISPLAY": self.run.display})
        ids = out.stdout.split()
        self.win = ids[0] if ids else None
        return self.win

    def xdo(self, *args):
        subprocess.run(["xdotool", *args], env={**os.environ, "DISPLAY": self.run.display}, check=True)

    def click(self, where):
        x, y = self.skin[where]
        self.xdo("mousemove", "--window", self.win, str(x), str(y), "click", "1")
        time.sleep(0.6)

    def key(self, *names):
        for n in names:
            self.xdo("key", n)
            time.sleep(0.3)

    def type(self, text):
        self.click("text")
        # The emulator drops the second of two identical characters in a row,
        # however slowly they are typed: test texts avoid doubled characters.
        self.xdo("type", "--delay", "80", text)
        time.sleep(0.5)

    def menu(self, index):
        """Open the left soft key's menu and choose its index-th entry."""
        self.click("left")
        for _ in range(index):
            self.key("Down")
        self.click("right")  # Select

    def shot(self, name):
        self.step += 1
        path = self.shots / f"{self.step:02d}-{name}.png"
        subprocess.run(["import", "-window", "root", "-crop", "226x300+0+0", str(path)],
                       env={**os.environ, "DISPLAY": self.run.display}, check=False)
        self.run.add_shot(self.test, self.profile, path)

    def trace(self):
        try:
            return [l[5:].rstrip("\n") for l in open(self.out, errors="replace") if l.startswith("SHOW ")]
        except OSError:
            return []

    def rows(self):
        """The rows on the pane screen as last drawn, top to bottom."""
        drawn = [l for l in self.trace() if l.startswith("Rows: ")]
        check(drawn, "the pane view drew nothing")
        return drawn[-1][6:].split("|")

    def rows_after(self, n, what):
        """Waits for a new drawing after the n-th trace line and returns its rows."""
        wait_for(what, lambda: any(l.startswith("Rows: ") for l in self.trace()[n:]), 10)
        return self.rows()

    def expect(self, pattern, what, timeout=25):
        rx = re.compile(pattern, re.S)
        try:
            wait_for(what, lambda: any(rx.search(l) for l in self.trace()), timeout)
        except AssertionError:
            raise Failure(f"{what}: no screen matched {pattern!r}; last: {self.trace()[-3:]}")

    def close(self):
        self.proc.terminate()
        try:
            self.proc.wait(5)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        shutil.rmtree(self.dir, ignore_errors=True)
