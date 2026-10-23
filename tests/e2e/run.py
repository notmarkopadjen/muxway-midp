#!/usr/bin/env python3
"""Runs the end-to-end suite and writes the run directory:
summary.md, results.xml (JUnit), report/index.html, logs/, screens/.

    tests/e2e/run.py <run directory> <muxway binary> [test id ...]
"""

import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import traceback
import xml.sax.saxutils as sax
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness  # noqa: E402
from harness import HOST, Core, Failure, Phone, Relay, check, wait_for  # noqa: E402

TESTS = []


def test(tid, title, kind, profiles=("midp1", "midp2")):
    def register(fn):
        TESTS.append({"id": tid, "title": title, "kind": kind, "profiles": profiles, "fn": fn})
        return fn
    return register


class Run:
    def __init__(self, out, binary):
        self.out = Path(out)
        self.binary = binary
        self.tmp = tempfile.mkdtemp(prefix="mxw-midp-")
        os.chmod(self.tmp, 0o700)
        self.secrets = []
        self.shots = {}
        self.cleanups = []
        self.display = f":{90 + os.getpid() % 900}"
        self.xvfb = subprocess.Popen(["Xvfb", self.display, "-screen", "0", "800x600x24", "-nolisten", "tcp"],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1)

    def logs(self, test):
        p = self.out / "logs" / test
        p.mkdir(parents=True, exist_ok=True)
        return p

    def screens(self, test, profile):
        p = self.out / "screens" / test / profile
        p.mkdir(parents=True, exist_ok=True)
        return p

    def add_shot(self, test, profile, path):
        self.shots.setdefault((test, profile), []).append(path)

    def close(self):
        self.xvfb.terminate()
        shutil.rmtree(self.tmp, ignore_errors=True)


def setup(run, name, profile, mode="normal", expose="--write", read=f"{HOST}/*", write=f"{HOST}/*",
          props=None, revoke=False):
    logs = run.logs(name)
    core = Core(run, run.binary, logs).start(expose=expose)
    run.cleanups.append(core.close)
    tok = core.token(read=read, write=write)
    if revoke:
        core.revoke_all()
    relay = Relay(core.url, logs, mode)
    run.cleanups.append(relay.close)
    all_props = {"Muxway-Server": relay.url, "Muxway-Token": tok}
    all_props.update(props or {})
    phone = Phone(run, name, profile, all_props)
    run.cleanups.append(phone.close)
    return core, relay, phone, tok


def open_pane(phone):
    phone.expect(r"^Panes: .*work/0\.0", "pane list")
    phone.shot("panes")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "pane content")
    time.sleep(1)
    phone.shot("pane")


def dismiss(phone):
    phone.click("right")
    time.sleep(0.5)


# --- security ---------------------------------------------------------------------

@test("S01", "Revoked token: Token refused, back to Settings, token cleared", "e2e-security")
def s01(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, revoke=True)
    phone.expect(r"^Alert: Token refused", "refusal")
    phone.shot("refused")
    dismiss(phone)
    phone.shot("settings")
    check(all(tok not in line for line in phone.trace()), "token shown on screen")


@test("S02", "The token never travels in a URL", "e2e-security")
def s02(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    check(relay.requests, "no requests reached the relay")
    for r in relay.requests:
        check(tok not in r["path"], f"token in URL {r['path'][:40]}")
        check(r["headers"].get("X-Muxway-Token") == tok, "token not in X-Muxway-Token")
        check("Authorization" not in r["headers"], "app sent Authorization, which WAP stacks rewrite")


@test("S03", "The token never reaches the screen, Settings shows it empty", "e2e-security")
def s03(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    phone.expect(r"^Panes: ", "pane list")
    phone.menu(0)  # Settings
    phone.expect(r"^Settings: ", "settings")
    phone.shot("settings")
    check(all(tok not in line and tok[4:20] not in line for line in phone.trace()), "token shown on screen")


@test("S04", "Read-only pane: no Reply; a relay that lies about it gets Read only", "e2e-security")
def s04(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, expose=None, mode="lie-writable")
    phone.expect(r"^Panes: \* ", "the lying relay's list")
    before = core.pane()
    phone.click("select")
    phone.expect(r"^View .*writable", "view")
    phone.expect(r"^work/0\.0: ", "content")
    phone.click("right")  # Reply, offered because the relay lied
    phone.type("echo nope")
    phone.shot("typed")
    phone.click("right")  # Send
    phone.expect(r"^Alert: Read only", "hub's refusal shown")
    phone.shot("read-only")
    check(core.pane() == before, "pane changed")
    # A fresh start against an honest relay: no star, a read-only view.
    relay.mode = "normal"
    phone.close()
    phone = Phone(run, name, profile, {"Muxway-Server": relay.url, "Muxway-Token": tok})
    run.cleanups.append(phone.close)
    phone.expect(r"^Panes:   ", "honest list without a star")
    time.sleep(1)
    phone.click("select")
    phone.expect(r"^View .* read-only", "read-only view")
    phone.shot("read-only-view")


@test("S05", "Hostile server: 1 MiB answer, invalid UTF-8, control characters", "e2e-security")
def s05(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="big")
    phone.expect(r"^Panes: ", "pane list")
    phone.click("select")
    phone.expect(r"^work/0\.0: x", "capped content")
    shown = [l for l in phone.trace() if l.startswith("work/0.0: ")][-1]
    check(len(shown) <= 32 * 1024 + 20, f"showed {len(shown)} characters of a 1 MiB answer")
    phone.shot("big")
    relay.mode = "garbage"
    phone.menu(2)  # Refresh
    phone.expect(r"^work/0\.0: ok ", "garbage content")
    shown = [l for l in phone.trace() if l.startswith("work/0.0: ok")][-1]
    check(not re.search(r"[\x00-\x08\x0b-\x1f\x7f]", shown), f"control characters shown: {shown!r}")
    phone.shot("garbage")
    relay.mode = "normal"
    phone.menu(2)
    phone.expect(r"^work/0\.0: \$", "back to normal")
    check("Exception" not in open(phone.out, errors="replace").read(), "the app threw")


# --- features ----------------------------------------------------------------------

@test("F07", "List, open, read at the phone's width", "e2e-ui")
def f07(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    widths = [int(m.group(1)) for r in relay.requests for m in [re.search(r"[?&]width=(\d+)", r["path"])] if m]
    check(widths, "the app never asked for a width")
    lo, hi = (12, 20) if profile == "midp1" else (25, 40)
    check(lo <= widths[0] <= hi, f"asked for width {widths[0]}, expected {lo} to {hi} on this screen")
    phone.expect(r"^View " + HOST + r"/work/0\.0 writable", "writable view")


@test("F08", "Reply: text and Enter reach the pane", "e2e-ui")
def f08(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    phone.click("right")  # Reply
    # No character twice in a row: the emulator drops the second one.
    phone.type("echo hi-$(expr 40 + 2)")
    phone.shot("typed")
    phone.click("right")  # Send
    wait_for("echo output", lambda: "hi-42" in core.pane())
    phone.expect(r"^work/0\.0: .*hi-42", "refreshed pane shows the output")
    phone.shot("output")


@test("F09", "Keys: Ctrl-C stops a running command", "e2e-ui")
def f09(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    core.tmux_cmd("send-keys", "-t", "work:0.0", "-l", "sleep 100")
    core.tmux_cmd("send-keys", "-t", "work:0.0", "Enter")
    wait_for("sleep running", lambda: core.command() == "sleep")
    open_pane(phone)
    phone.menu(0)  # Keys
    phone.shot("keys")
    phone.key("Down")  # Ctrl-C is second
    phone.click("select")
    wait_for("sleep interrupted", lambda: core.command() == "bash")
    phone.shot("interrupted")


@test("F10", "Auto refresh: unchanged pane answered 304", "e2e")
def f10(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, props={"Muxway-Refresh": "2"})
    open_pane(phone)
    phone.menu(3)  # Auto
    phone.expect(r"^Alert: Auto refresh every 2 s", "auto on")
    dismiss(phone)
    time.sleep(7)
    conditional = [r for r in relay.requests if r["headers"].get("If-None-Match")]
    check(len(conditional) >= 2, f"{len(conditional)} conditional reads in 7 s")
    check(" -> 304 " in (run.out / "logs" / name / "relay.log").read_text(), "no 304 answers")


@test("F11", "Host offline and hub down are reported; the app recovers", "e2e-ui")
def f11(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    core.stop("agent")
    wait_for("host offline", lambda: f"{HOST}\toffline" in core.cli("hub", "agents", "--dir", str(core.dir / "hub")))
    phone.menu(2)  # Refresh
    phone.expect(r"^Alert: Host offline", "offline")
    phone.shot("offline")
    dismiss(phone)
    core.start_agent()
    n = len(phone.trace())
    phone.menu(2)
    wait_for("content again", lambda: any(l.startswith("work/0.0: ") for l in phone.trace()[n:]), 25)
    phone.shot("recovered")
    relay.close()
    phone.menu(2)
    phone.expect(r"^Alert: No connection", "relay gone")
    phone.shot("no-connection")


@test("J13", "Without settings in the install file the app opens Settings", "e2e-ui")
def j13(run, name, profile):
    phone = Phone(run, name, profile, {})
    run.cleanups.append(phone.close)
    phone.expect(r"^Settings: $", "settings first")
    phone.shot("settings")


# --- runner -------------------------------------------------------------------------

def unit_tests(run, results):
    """Java SE tests of the text helpers: what the screens show and what is sent."""
    root = harness.ROOT
    build = Path(run.tmp) / "unit"
    build.mkdir()
    javac = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-8-openjdk-amd64") + "/bin/javac"
    cp = f"{root}/lib/midp20.jar:{root}/lib/cldc11.jar"
    start = time.time()
    comp = subprocess.run([javac, "-nowarn", "-cp", cp, "-d", str(build), str(root / "src/muxway/Text.java"),
                           str(root / "src/muxway/Api.java"), str(root / "src/muxway/Prefs.java"),
                           str(root / "src/muxway/Trace.java"), str(root / "profiles/midp2/muxway/Ui.java"),
                           str(root / "tests/unit/UnitTest.java")], capture_output=True, text=True)
    out = comp.stdout + comp.stderr
    if comp.returncode == 0:
        r = subprocess.run([javac.replace("javac", "java"), "-cp", f"{build}:{cp}", "muxway.UnitTest"],
                           capture_output=True, text=True, timeout=60)
        out = r.stdout + r.stderr
    for line in out.splitlines():
        m = re.match(r"(PASS|FAIL) (\S+)(.*)", line)
        if m:
            results.append({"id": "U06", "test": "U06_" + m.group(2), "title": m.group(2), "kind": "unit",
                            "profile": "java-se", "ok": m.group(1) == "PASS", "message": m.group(3).strip(),
                            "seconds": time.time() - start})
    if not any(r["kind"] == "unit" for r in results):
        results.append({"id": "U06", "test": "U06_compile", "title": "unit tests compile", "kind": "unit",
                        "profile": "java-se", "ok": False, "message": out[-2000:], "seconds": 0})


def secrets_in_logs(run, results):
    found = []
    for path in (run.out / "logs").rglob("*"):
        if path.is_file():
            text = path.read_text(errors="replace")
            found += [f"{path.relative_to(run.out)} ({s[:6]}...)" for s in run.secrets if len(s) > 12 and s in text]
    results.append({"id": "S99", "test": "S99_no_secret_in_any_log", "title": "No token or key in any log",
                    "kind": "e2e-security", "profile": "all", "ok": not found, "message": "; ".join(found),
                    "seconds": 0})


def report(run, results):
    passed = sum(r["ok"] for r in results)
    failed = len(results) - passed
    order = lambda r: (0 if r["kind"] == "e2e-security" else 1 if r["kind"] == "unit" else 2, r["id"], r["profile"])
    results.sort(key=order)
    when = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    md = [f"## Test run {run.out.name}", "",
          f"**{'passed' if not failed else 'failed'}**: {len(results)} tests, {failed} failed. {when}", "",
          "| Test | Build | Kind | Result | Time |", "|---|---|---|---|---|"]
    for r in results:
        md.append(f"| {r['test']} | {r['profile']} | {r['kind']} | {'pass' if r['ok'] else '**FAIL**'} | {r['seconds']:.1f}s |")
    (run.out / "summary.md").write_text("\n".join(md) + "\n")
    cases = []
    for r in results:
        body = "" if r["ok"] else f'<failure message="failed">{sax.escape(r["message"])}</failure>'
        cases.append(f'  <testcase name="{sax.escape(r["test"])} [{r["profile"]}]" classname="muxway-midp" '
                     f'time="{r["seconds"]:.3f}">{body}</testcase>')
    (run.out / "results.xml").write_text(
        f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuites tests="{len(results)}" failures="{failed}" errors="0" '
        f'skipped="0">\n <testsuite name="muxway-midp" tests="{len(results)}" failures="{failed}" errors="0" '
        f'skipped="0">\n' + "\n".join(cases) + "\n </testsuite>\n</testsuites>\n")
    # The HTML report: one section per test, its screenshots in order.
    rep = run.out / "report"
    rep.mkdir(exist_ok=True)
    parts = [f"<!doctype html><meta charset=utf-8><title>muxway-midp test run</title><style>"
             "body{background:#000;color:#3f3;font:14px/1.4 monospace;margin:16px}"
             "h1,h2{font-weight:normal;border-bottom:1px solid #3f3}.fail{color:#f33}"
             "img{border:1px solid #3f3;margin:4px;image-rendering:pixelated}pre{white-space:pre-wrap;color:#fa3}"
             f"</style><h1>muxway-midp test run {html.escape(run.out.name)}</h1>"
             f"<p>{len(results)} tests, {failed} failed, {when}</p>"]
    for r in results:
        cls = "" if r["ok"] else " class=fail"
        parts.append(f"<h2{cls}>[{'PASS' if r['ok'] else 'FAIL'}] {html.escape(r['test'])} "
                     f"({html.escape(r['profile'])}): {html.escape(r['title'])}</h2>")
        if not r["ok"]:
            parts.append(f"<pre>{html.escape(r['message'])}</pre>")
        for shot in run.shots.get((r["test"], r["profile"]), []):
            rel = os.path.relpath(shot, rep)
            parts.append(f'<figure style="display:inline-block"><img src="{html.escape(rel)}" width=226>'
                         f"<figcaption>{html.escape(shot.stem)}</figcaption></figure>")
    (rep / "index.html").write_text("\n".join(parts) + "\n")
    return failed


def main():
    out, binary, only = sys.argv[1], sys.argv[2], set(sys.argv[3:])
    run = Run(out, binary)
    results = []
    try:
        unit_tests(run, results)
        for t in TESTS:
            if only and t["id"] not in only:
                continue
            for profile in t["profiles"]:
                name = f"{t['id']}_{t['fn'].__name__}"
                start = time.time()
                ok, message = True, ""
                try:
                    t["fn"](run, name, profile)
                except Exception as e:
                    ok = False
                    message = f"{e}\n{traceback.format_exc(limit=3)}" if not isinstance(e, Failure) else str(e)
                    try:
                        Phone.shot  # a last screenshot of what the phone shows
                    except Exception:
                        pass
                finally:
                    for fn in reversed(run.cleanups):
                        try:
                            fn()
                        except Exception:
                            pass
                    run.cleanups.clear()
                results.append({"id": t["id"], "test": name, "title": t["title"], "kind": t["kind"],
                                "profile": profile, "ok": ok, "message": message, "seconds": time.time() - start})
                print(f"{'PASS' if ok else 'FAIL'} {name} [{profile}] {message.splitlines()[0] if message else ''}",
                      flush=True)
        secrets_in_logs(run, results)
    finally:
        run.close()
    sys.exit(1 if report(run, results) else 0)


if __name__ == "__main__":
    main()
