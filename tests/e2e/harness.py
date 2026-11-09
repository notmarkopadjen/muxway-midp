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
# The media and file APIs the MIDP 2.0 build is compiled against; the file one is
# also the emulator's implementation, registered only for a phone given files.
JSR135 = ROOT / "lib" / "jsr135.jar"
JSR75 = ROOT / "lib" / "jsr75.jar"
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
        # A pane split from here starts in this client's directory: the project under the root, when there is one.
        return subprocess.run(["tmux", "-L", self.tmux, *args], capture_output=True, text=True, timeout=10,
                              cwd=self.preview / "project" if self.preview else None).stdout.strip()

    def start(self, expose="--write", inbox=False, preview=False, sessions=False, close=False):
        """preview: the agent gets a preview root and the shell starts in a project folder under it;
        sessions and close: the agent's --allow-sessions and --allow-close."""
        self.inbox = self.dir / "inbox" if inbox else None
        self.preview = self.dir / "roots" if preview else None
        self.sessions, self.closes = sessions, close
        self.hosts = []
        shell = "env -i PATH=/usr/bin:/bin TERM=xterm LANG=C.UTF-8 PS1='$ ' bash --norc --noprofile"
        where = []
        if self.preview:
            (self.preview / "project").mkdir(parents=True)
            where = ["-c", str(self.preview / "project")]
        self.tmux_cmd("-f", "/dev/null", "new-session", "-d", "-s", "work", "-n", "shell", "-x", "80", "-y", "24", *where, shell)
        self.tmux_cmd("select-pane", "-t", "work:0.0", "-T", "test-pane")
        # A pane the hub makes would be titled with this machine's name by tmux: never that in a log.
        self.tmux_cmd("set-hook", "-g", "after-new-session", "select-pane -T new-pane")
        self.tmux_cmd("set-hook", "-g", "after-split-window", "select-pane -T split-pane")
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
        args = ["agent", "run", "--dir", str(self.dir / "agent"), "--tmux-name", self.tmux]
        if self.inbox:
            args += ["--inbox", str(self.inbox)]
        if self.preview:
            args += ["--preview", str(self.preview)]
        if self.sessions:
            args.append("--allow-sessions")
        if self.closes:
            args.append("--allow-close")
        self.spawn("agent", *args)
        wait_for("agent online", lambda: f"{HOST}\tonline" in self.cli("hub", "agents", "--dir", str(self.dir / "hub")))

    def add_host(self, name, expose=False):
        """A second host on the hub: its own agent and tmux server; a pane exposed when asked."""
        code = self.cli("hub", "pair-code", "--dir", str(self.dir / "hub"), "--host", name)
        self.cli("agent", "pair", "--dir", str(self.dir / f"agent-{name}"), code)
        tmux = f"{self.tmux}-{name}"
        subprocess.run(["tmux", "-L", tmux, "-f", "/dev/null", "new-session", "-d", "-s", "work", "-x", "80", "-y", "24",
                        "env -i PATH=/usr/bin:/bin TERM=xterm PS1='$ ' bash --norc --noprofile"], check=True, timeout=10)
        subprocess.run(["tmux", "-L", tmux, "select-pane", "-t", "work:0.0", "-T", f"{name}-pane"], check=True, timeout=10)
        self.spawn(f"agent-{name}", "agent", "run", "--dir", str(self.dir / f"agent-{name}"), "--tmux-name", tmux)
        wait_for(f"{name} online", lambda: f"{name}\tonline" in self.cli("hub", "agents", "--dir", str(self.dir / "hub")))
        if expose:
            self.cli("expose", "--tmux-name", tmux, "-t", "work:0.0", "--write")
        self.hosts.append(name)
        time.sleep(1.2)  # the hub lists from a one-second cache

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

    def alias(self, target, name):
        """Names a pane on the host, as the owner does with muxway expose --name or from the web."""
        self.cli("expose", "--tmux-name", self.tmux, "-t", target, "--write", "--name", name)

    def pane(self):
        return self.tmux_cmd("capture-pane", "-p", "-t", "work:0.0")

    def command(self):
        return self.tmux_cmd("display-message", "-p", "-t", "work:0.0", "#{pane_current_command}")

    def pane_id(self, target="work:0.0"):
        return self.tmux_cmd("display-message", "-p", "-t", target, "#{pane_id}")

    def notify(self, kind, text="", target="work:0.0"):
        """What a harness's hook does on the host: the pane needs its owner, or works again."""
        self.cli("notify", "--dir", str(self.dir / "agent"), "--pane", self.pane_id(target), "--kind", kind, "--text", text)

    def ask(self, text, choices, target="work:0.0", timeout="10m"):
        """Runs muxway ask inside the pane, so the answer and the exit code land on its screen."""
        cmd = (f"{self.bin} ask --dir {self.dir / 'agent'} --pane {self.pane_id(target)} --text '{text}' "
               + " ".join(f"--choice {c}" for c in choices) + f" --timeout {timeout}; echo EXIT=$?")
        self.tmux_cmd("send-keys", "-t", target, "-l", cmd)
        self.tmux_cmd("send-keys", "-t", target, "Enter")

    def later(self, tok, address, on):
        """Parks a pane or wakes it on the hub, as another client would."""
        c = http.client.HTTPConnection(self.url.replace("http://", ""), timeout=10)
        c.request("POST", f"/v1/panes/{address}/later", body=json.dumps({"later": on}),
                  headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
        r = c.getresponse()
        r.read()
        c.close()
        if r.status != 204:
            raise RuntimeError(f"later {address} {on}: {r.status}")

    def post(self, tok, path, body, origin=None):
        """A JSON request as another client makes it; origin names the pane it comes from (its tmux id)."""
        if origin:
            body = {**body, "from": {"host": HOST, "pane_id": origin}}
        c = http.client.HTTPConnection(self.url.replace("http://", ""), timeout=10)
        c.request("POST", path, body=json.dumps(body),
                  headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, data

    def desk(self, tok, desk):
        """Writes the desk as the web or Android does: PUT /v1/desk under the token."""
        c = http.client.HTTPConnection(self.url.replace("http://", ""), timeout=10)
        c.request("PUT", "/v1/desk", body=json.dumps(desk),
                  headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, data

    def get(self, tok, path):
        """A text-mode read as the phone makes it."""
        c = http.client.HTTPConnection(self.url.replace("http://", ""), timeout=10)
        c.request("GET", path, headers={"Authorization": f"Bearer {tok}", "Accept": "text/plain"})
        r = c.getresponse()
        body = r.read().decode()
        c.close()
        return r.status, body

    def mail(self, tok, address, text, origin=None):
        """Mails a pane, from a client, or from the pane whose id origin is."""
        status, data = self.post(tok, f"/v1/panes/{address}/mail", {"text": text}, origin)
        if status != 201:
            raise RuntimeError(f"mail {address}: {status} {data[:80]}")
        time.sleep(1.2)  # the hub lists from a one-second cache

    def say(self, tok, address, text, origin):
        """Types into a pane from another pane, so the feed carries who said it."""
        status, data = self.post(tok, f"/v1/panes/{address}/input", {"text": text}, origin)
        if status != 204:
            raise RuntimeError(f"say {address}: {status} {data[:80]}")

    def waiting(self, tok):
        """The hub's inbox in text mode, one row per waiting pane."""
        c = http.client.HTTPConnection(self.url.replace("http://", ""), timeout=10)
        c.request("GET", "/v1/inbox", headers={"Authorization": f"Bearer {tok}", "Accept": "text/plain"})
        r = c.getresponse()
        body = r.read().decode()
        c.close()
        return [l for l in body.split("\n") if l]

    def close(self):
        for name in list(self.procs):
            self.stop(name)
        self.tmux_cmd("kill-server")
        for name in self.hosts:
            subprocess.run(["tmux", "-L", f"{self.tmux}-{name}", "kill-server"], capture_output=True, timeout=10)
        for key in ("hub/hub.key", "agent/agent.key"):
            p = self.dir / key
            if p.exists():
                self.run.secrets.append(p.read_text().strip())
        shutil.rmtree(self.dir, ignore_errors=True)


# --- relay -------------------------------------------------------------------------

# What the hub makes of Claude Code's characters for a Latin-1 or ASCII phone.
ASCII_ART = {"\u2500": "-", "\u2501": "-", "\u2550": "=", "\u276f": ">", "\u25cf": "*", "\u00b7": ".",
             "\u23f5": ">", "\u2190": "<", "\u2192": ">", "\u2193": "v", "\u2026": "...", "\u273b": "*",
             "\u2733": "*"}


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
        # The list names the program claude, so Auto picks the lens by it.
        self.says_claude = False
        # The columns the core adds for the keypad phone, until a hub writes them itself: the hosts'
        # flags by host, what waits under a parked pane (pending) by address, replied by mail id.
        self.hosts_flags = {}
        self.pending = {}
        self.replied = {}
        # late mode: the first read of the first pane is held this long, then answered with marked rows.
        self.late = 60
        self.held = False
        self.answered_late = False
        # lie-desk mode: the desks forged, one per request, round and round.
        self.desks = 0
        # lie-pane mode: the Muxway-Pane header forged on pane reads, one lie per read, the last one kept.
        self.lies = 0
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
                if relay.mode == "late" and self.command == "GET" and self.path.startswith(f"/v1/panes/{HOST}/work/0.0?") and not relay.held:
                    # The phone's patience is 45 s: this answer comes after it gave up and went elsewhere.
                    relay.held = True
                    time.sleep(relay.late)
                    relay.answered_late = True
                    return self.reply(200, b"LATE ANSWER\n", {"Content-Type": "text/plain; charset=utf-8"})
                if relay.mode == "slow" and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    time.sleep(3)  # long enough for a look at the view before its rows come
                if relay.mode == "lie-desk" and self.path == "/v1/desk":
                    # Desks a hub would never send: a path up, a stranger's pane, 300 letters with control
                    # characters, a window that is a path; then one that names the list's second pane.
                    forged = ["../../admin", "bob-desktop/work/0.0", "a" * 300 + "\u001b[2J\u0007", None]
                    pick = forged[relay.desks % len(forged)]
                    relay.desks += 1
                    desk = {"view": "window", "window": "../"} if pick is None else {"view": "panes", "panes": [pick], "focused": pick}
                    return self.reply(200, json.dumps({"desk": desk}).encode(), {"Content-Type": "application/json"})
                if relay.mode == "big" and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    return self.reply(200, b"x" * (1 << 20) + b"\n", {})
                if relay.mode == "garbage" and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    return self.reply(200, b"ok \xff\xfe\xc3 \x1b[31mred\x1b[0m \x07bell \x00nul\r\nend\n", {})
                if relay.mode in ("claude", "claude-norules", "claude-empty") and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    screen = (FIXTURES / "claude-code.txt").read_text()
                    if relay.mode != "claude":
                        # No rules; claude-empty has nothing typed in the box either.
                        screen = "\n".join(r for r in screen.split("\n") if not r.startswith("\u2500"))
                    if relay.mode == "claude-empty":
                        screen = screen.replace("\u276f fix the typo in README", "\u276f")
                    return self.reply(200, *spelt(screen, self.path))
                if relay.mode == "claude-hostile" and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    return self.reply(200, hostile_claude_screen(), {"Content-Type": "text/plain"})
                if relay.mode.startswith("upload-") and self.command == "POST" and self.path.endswith("/files"):
                    # A hub or relay that answers an upload badly: a status, or a 201 with a path to distrust.
                    what = relay.mode[len("upload-"):]
                    if what.isdigit():
                        return self.reply(int(what), b'{"error":"test"}\n', {"Content-Type": "application/json"})
                    bad = {"long": b'{"path":"/' + b"a" * 2000 + b'","bytes":1}',
                           "control": b'{"path":"/tmp/x\\u001b[2Jy\\n\\u0007z.jpg","bytes":1}',
                           "notstring": b'{"path":12,"bytes":1}',
                           "notjson": b"\xff\xfe<html>not json"}
                    return self.reply(201, bad[what], {"Content-Type": "application/json"})
                if relay.mode == "charset-hostile" and self.command == "GET" and self.path.startswith("/v1/panes/"):
                    # A relay that lies about the charset: the app must decode as UTF-8 and stay up.
                    n = len(relay.requests) % 4
                    lie = ["text/plain; charset=utf-7", "text/plain; charset=../../etc", "text/plain; charset=" + "x" * 300,
                           "text/plain; charset=utf-8"][n]
                    screen = (FIXTURES / "claude-code.txt").read_text()
                    # The last lie is the one a real relay told: Latin-1 bytes labelled UTF-8.
                    body = spelt(screen, "?charset=latin1")[0] if n == 3 else screen.encode()
                    return self.reply(200, body, {"Content-Type": lie})
                headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection")}
                path = self.path
                is_input = self.command == "POST" and path.split("?")[0].endswith("/input")
                if relay.mode in ("old-hub", "wap") and is_input:
                    # A hub older than the screen on an input: read=1 never reaches it, so it answers 204.
                    path = path.replace("?read=1&", "?").replace("?read=1", "")
                c = http.client.HTTPConnection(relay.hub, timeout=20)
                c.request(self.command, path, body=body or None, headers=headers)
                r = c.getresponse()
                data = r.read()
                if relay.mode == "wap" and r.status == 204:
                    # The relay page's WAP rule: a 204 becomes 200 with a short body, and no ETag to go with it.
                    return self.reply(200, b"ok", {"Content-Type": "text/plain"})
                if relay.mode == "lie-input" and is_input and "read=1" in path:
                    # The key landed; the answer is the relay's own: a megabyte with control characters under an
                    # ETag, then a screen a stranger answered, then the hub's own.
                    lie, relay.lies = relay.lies, relay.lies + 1
                    if lie == 0:
                        return self.reply(200, b"x" * (1 << 20) + b"\x1b[2J\x07 BIG\n", {"Content-Type": "text/plain", "ETag": '"forged"'})
                    if lie == 1:
                        return self.reply(200, b"FORGED SCREEN\n$ \n", {"Content-Type": "text/plain", "ETag": '"forged"', "Muxway-Pane": "%999999"})
                if relay.mode == "lie-writable" and self.path.split("?")[0] == "/v1/panes":
                    data = data.replace(b"\tread\t", b"\twrite\t")
                if relay.says_claude and self.path.split("?")[0] == "/v1/panes":
                    data = data.replace(b"\tbash\t", b"\tclaude\t")  # the program column says claude
                if relay.mode == "lie-inbox" and self.path.split("?")[0] == "/v1/inbox":
                    # Choices a hub would never send: nine of them, one too long, one with a control character.
                    choices = ["x" * 41, "bad\x07ctl"] + [f"c{i}" for i in range(9)]
                    data = f"{HOST}/work/0.0\tquestion\t1\tpick one\t{'|'.join(choices)}\n".encode()
                if relay.mode in ("talk", "lie-talk") and self.path.split("?")[0] == "/v1/panes":
                    data = relay.columns(data)
                if relay.mode == "lie-names" and self.path.split("?")[0] == "/v1/panes":
                    # An alias and a folder a hub would never send: 300 letters, control characters,
                    # a path up, a stranger's address, a folder ending in a slash.
                    forged = {f"{HOST}/work/0.0": ("a" * 300, "/x/\x1b[2J\x07y"),
                              f"{HOST}/work/0.1": ("../../etc", "/tmp/../.."),
                              f"{HOST}/work/0.2": ("bob/work/0.0", "/home/bob/")}
                    rows = []
                    for row in data.decode("latin-1").split("\n"):
                        f = row.split("\t")
                        if f[0] in forged:
                            f = (f + [""] * 7)[:7] + list(forged[f[0]])
                        rows.append("\t".join(f))
                    data = "\n".join(rows).encode("latin-1")
                if relay.mode == "lie-later" and self.path.split("?")[0] == "/v1/panes":
                    # The kind column says later on a pane the hub lists awake.
                    rows = []
                    for row in data.decode("latin-1").split("\n"):
                        f = row.split("\t")
                        if f[0] == f"{HOST}/work/0.1" and len(f) > 4:
                            f[4] = "later"
                        rows.append("\t".join(f))
                    data = "\n".join(rows).encode("latin-1")
                where = self.path.split("?")[0]
                if where == "/v1/hosts" and (relay.hosts_flags or relay.mode == "lie-hosts"):
                    data = relay.flags(data)
                if relay.pending and where in ("/v1/panes", "/v1/inbox"):
                    data = set_column(data, 9 if where == "/v1/panes" else 5, lambda f: relay.pending.get(f[0], ""))
                if relay.replied and where.endswith("/mail"):
                    data = set_column(data, 6, lambda f: relay.replied.get(f[0], ""))
                if relay.mode == "talk-across" and where == "/v1/panes":
                    # The first pane talked with the second host's pane: a group across two hosts.
                    pair = {f"{HOST}/work/0.0": "bob-desktop/work/0.0", "bob-desktop/work/0.0": f"{HOST}/work/0.0"}
                    data = set_column(data, 6, lambda f: pair.get(f[0], ""))
                if relay.mode == "lie-feed" and where == "/v1/talk":
                    # Feed rows a hub never writes: a seq of letters, a sender of ../admin, a kind that is not one,
                    # a stranger as the recipient with 300 characters and control characters, and the next row.
                    long = "x" * 300 + "\x1b[2J\x07"
                    rows = [f"1\t1767225600\t{HOST}/work/0.0\tx/y/0.0\ttext\t{long}",
                            f"abc\t1767225600\t{HOST}/work/0.0\t{HOST}/work/0.1\ttext\tletters",
                            f"2\t1767225600\t../admin\t{HOST}/work/0.1\ttext\tfrom nowhere",
                            f"3\t1767225600\t{HOST}/work/0.0\t{HOST}/work/0.1\tbogus\tno kind",
                            "next\t5"]
                    data = ("\n".join(rows) + "\n").encode("latin-1")
                if relay.mode == "lie-feed" and where.endswith("/mail") and self.command == "GET":
                    # Mail rows the same way: an id of letters, a sender of ../admin, an actor of 300 letters.
                    rows = [f"7\t1767225600\t../admin\tactor\t0\tfrom nowhere",
                            f"x\t1767225600\t\tactor\t0\tletters",
                            f"8\t1767225600\t\t{'a' * 300}\t0\t{'y' * 300}\x1b[2J"]
                    data = ("\n".join(rows) + "\n").encode("latin-1")
                if relay.mode == "lie-title" and self.path.split("?")[0] == "/v1/panes":
                    # Titles a program could choose: one that looks like a writable entry, one too long.
                    data = (f"{HOST}/work/0.0\tread\tbash\t* {HOST}/work/0.0 claude\twrite\tx\n"
                            f"{HOST}/work/0.0\twrite\tbash\t{'x' * 300}\t\n").encode()
                headers = {k: v for k, v in r.getheaders() if k.lower() in ("etag", "content-type", "muxway-pane")}
                if relay.mode == "lie-pane" and self.command == "GET" and where.startswith("/v1/panes/"):
                    # Headers a hub never sends: a path, letters, three hundred digits; then a well-formed id
                    # of a pane the list does not hold, kept for every read after.
                    forged = ["../x", "%abc", "%" + "9" * 300, "%999999"]
                    headers["Muxway-Pane"] = forged[min(relay.lies, len(forged) - 1)]
                    relay.lies += 1
                self.reply(r.status, data, headers)

            def reply(self, status, data, headers):
                relay.log.write(f"    -> {status} {len(data)} bytes\n")
                relay.log.flush()  # a test may read the log while the relay lives; the last answer's line too
                self.send_response(status)
                for k, v in headers.items():
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST = forward

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def columns(self, data):
        """The hub's rows with the mail and talk columns a newer hub writes: in talk mode the first
        pane holds two mails and talked with the third; in lie-talk mode both columns are forged."""
        out = []
        for row in data.decode("latin-1").split("\n"):
            if not row:
                continue
            # A hub that writes the two columns itself gets them replaced, an older one gets them added;
            # the columns after them (alias, cwd, pending, pane id) stay as the hub wrote them.
            fields = row.split("\t")
            fields += [""] * (7 - len(fields))
            address = fields[0]
            mail = talk = ""
            if self.mode == "talk":
                if address == f"{HOST}/work/0.0":
                    mail, talk = "2", f"{HOST}/work/0.2"
                elif address == f"{HOST}/work/0.2":
                    talk = f"{HOST}/work/0.0"
            elif address == f"{HOST}/work/0.0":
                # Letters in the count; itself, a path and a stranger as partners.
                mail, talk = "12abc", f"{HOST}/work/0.0|../admin|x/y/0.0"
            else:
                mail, talk = "3" * 300, f"{HOST}/work/0.9|\x1b[2J"
            fields[5], fields[6] = mail, talk
            out.append("\t".join(fields))
        return ("\n".join(out) + "\n").encode("latin-1")

    def flags(self, data):
        """The hosts' rows with a flags column: the ones set on the relay, or in lie-hosts mode words a hub
        never writes, three hundred columns, and a host the list does not hold."""
        if self.mode == "lie-hosts":
            rows = [f"{HOST}\tonline\tsessions|close|bogus|\x1b[2J|sessions=0" + "\tx" * 300, "ghost\tonline\tsessions"]
            return ("\n".join(rows) + "\n").encode("latin-1")
        return set_column(data, 2, lambda f: self.hosts_flags.get(f[0], ""))

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def set_column(data, index, value):
    """Every row of a text answer with the column at index set to value(fields), rows padded to reach it;
    a hub that writes the column itself gets it replaced."""
    out = []
    for row in data.decode("latin-1").split("\n"):
        if not row:
            continue
        f = row.split("\t")
        f += [""] * (index + 1 - len(f))
        f[index] = value(f)
        out.append("\t".join(f))
    return ("\n".join(out) + "\n").encode("latin-1")


# --- the phone ---------------------------------------------------------------------

# Where the emulator's skin buttons are, in window coordinates, and where to
# click to give a text box focus, per device. The keypad is the same on both.
KEYPAD = {"k1": (45, 363), "k2": (116, 363), "k3": (183, 363), "k5": (116, 399), "k7": (45, 432), "k8": (114, 432), "k9": (183, 432),
          "k0": (114, 467), "star": (45, 464), "pound": (183, 463)}
SKINS = {
    "default": {"left": (43, 320), "right": (183, 320), "select": (113, 320), "text": (110, 150), **KEYPAD},
    "n96x65": {"left": (43, 320), "right": (183, 320), "select": (113, 320), "text": (88, 95), **KEYPAD},
}
# The writable pane's left-key menu as the emulator orders it: by priority, then as added.
# Wake takes Later's place while the pane is parked.
# Mail is there on a host that holds mail (the test agent does), Commands on a shell or Claude pane,
# Close on an agent run with --allow-close.
PANE_MENU = {"midp1": ["Keys", "Back", "Refresh", "Auto", "Pane", "Settings", "Exit"],
             "midp2": ["Keys", "Back", "Image", "Refresh", "Auto", "Mail", "Commands", "Pane", "Later", "Settings", "Exit"]}
# The pane list's menu the same way; Refresh sits on the right soft key.
# + session and + pane follow Settings where some host or row allows them (the test agent's flags, or the relay's).
# Desk comes first on the 2.0 build: the view every client shares.
PANES_MENU = {"midp1": ["Settings", "Exit"], "midp2": ["Desk", "Inbox", "Messages", "Auto", "Settings", "Exit"]}
PANES_MENU_PLUS = ["Desk", "Inbox", "Messages", "Auto", "Settings", "+ session", "+ pane", "Exit"]


class Phone:
    def __init__(self, run, test, profile, props, home=None, files=None):
        """home: a directory for the record store, so a second Phone with the same home is a reinstall.
        files: {name: bytes} to put in the phone's memory, which the emulator keeps under its home."""
        self.run, self.test, self.profile = run, test, profile
        self.device = "n96x65" if profile == "midp1" else "default"
        # What the pane list's trace starts with: the 2.0 build heads the rows with the host's line.
        self.list = "Panes: " if profile == "midp1" else f"Panes: = {HOST} online|"
        self.lp = re.escape(self.list)
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
        cmd = [JAVA, "-Djavax.accessibility.assistive_technologies=", "-cp", f"{EMULATOR}:{DEVICES}:{JSR135}:{JSR75}"]
        if files is not None:
            # The emulator's file API roots at <home>/.microemulator/filesystem/c; the
            # photo folder property points the gallery straight at it.
            home = home or Path(run.tmp) / f"home-{secrets.token_hex(4)}"
            root = Path(home) / ".microemulator" / "filesystem" / "c"
            root.mkdir(parents=True, exist_ok=True)
            for file, data in files.items():
                (root / file).write_bytes(data)
            cmd += ["-Dfileconn.dir.photos=file:///c/"]
        if home:
            cmd += [f"-Duser.home={home}", "org.microemu.app.Main", "--rms", "file"]
        else:
            cmd += ["org.microemu.app.Main", "--rms", "memory"]
        if files is not None:
            cmd += ["--impl", "org.microemu.cldc.file.FileSystem"]
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
        time.sleep(2.5)  # a Down pressed before the menu is up lands on the first entry, or on none; a full run is slow, and slower beside another suite
        for _ in range(index):
            self.key("Down")
        self.click("right")  # Select

    def pick(self, entry, menu=None):
        """An entry of the writable pane's menu, by name; menu when the pane's differs from the usual one."""
        self.menu((menu or PANE_MENU[self.profile]).index("Later" if entry == "Wake" else entry))

    def pick_list(self, entry, menu=None):
        """An entry of the pane list's menu, by name; menu when the list's differs from the usual one."""
        self.menu((menu or PANES_MENU[self.profile]).index(entry))

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

    def expect(self, pattern, what, timeout=25, after=0):
        """Waits for a screen matching the pattern; after skips that many earlier trace lines."""
        rx = re.compile(pattern, re.S)
        try:
            wait_for(what, lambda: any(rx.search(l) for l in self.trace()[after:]), timeout)
        except AssertionError:
            raise Failure(f"{what}: no screen matched {pattern!r}; last: {self.trace()[-3:]}")
        return next(l for l in self.trace()[after:] if rx.search(l))

    def close(self):
        self.proc.terminate()
        try:
            self.proc.wait(5)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        shutil.rmtree(self.dir, ignore_errors=True)
