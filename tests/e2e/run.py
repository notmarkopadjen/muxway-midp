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
          props=None, revoke=False, before=None):
    logs = run.logs(name)
    core = Core(run, run.binary, logs).start(expose=expose)
    run.cleanups.append(core.close)
    tok = core.token(read=read, write=write)
    if revoke:
        core.revoke_all()
    relay = Relay(core.url, logs, mode)
    run.cleanups.append(relay.close)
    if before:
        before(core)  # the tmux side a test wants in place before the phone looks
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


@test("S08", "A relay that lies about the charset: decoded as UTF-8, the app stays up", "e2e-security")
def s08(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="charset-hostile")
    phone.expect(r"^Panes: ", "pane list")
    phone.click("select")
    phone.expect(r"^work/0\.0: .*Claude Code", "content")
    for i in range(4):
        if i:
            n = len(phone.trace())
            phone.menu(2)  # Refresh: the next lie
            rows = phone.rows_after(n, f"lie {i} drawn")
        else:
            rows = phone.rows_after(0, "drawn")
        check(any(r.startswith(("\u276f fix the typo", "> fix the typo")) for r in rows), f"lie {i}: not decoded: {rows}")
    check(not any("Exception" in line or "Error" in line for line in phone.trace()), "the app failed")
    phone.shot("lied-to")


@test("S06", "Hostile Claude-like screen: 400-column and a thousand rules, control characters, a lying footer",
      "e2e-security")
def s06(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="claude-hostile")
    phone.expect(r"^Panes: ", "pane list")
    phone.click("select")
    phone.expect(r"^work/0\.0: transcript row 0", "content")
    rows = phone.rows_after(0, "drawn")
    # The relay skips the hub, so the escape sequences lose only their ESC here.
    check(any(r.startswith("\u276f box ") for r in rows[-2:]), f"box not pinned: {rows[-2:]}")
    check(not any("fake footer" in r for r in rows), "the footer was shown without asking")
    check(not rows[-1].startswith("\u2500"), f"a rule on the last row: {rows[-1]}")
    phone.shot("hostile")
    n = len(phone.trace())
    phone.click("star")
    rows = phone.rows_after(n, "revealed")
    check(any("fake footer" in r for r in rows), f"* did not reveal the footer: {rows}")
    phone.shot("revealed")
    check("Exception" not in open(phone.out, errors="replace").read(), "the app threw")


@test("S07", "Read-only pane: keypad 5 and 0 open nothing and send nothing", "e2e-security")
def s07(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, expose=None)
    phone.expect(r"^Panes:   ", "list without a star")
    phone.click("select")
    phone.expect(r"^View .* read-only", "read-only view")
    phone.expect(r"^work/0\.0: ", "content")
    phone.click("k5")
    phone.click("k0")
    time.sleep(1)
    phone.shot("after-5-and-0")
    check(not any(l in ("Reply", "Keys") for l in phone.trace()), "Reply or Keys opened on a read-only pane")
    check(not any(r["method"] == "POST" for r in relay.requests), "a key reached the relay")


# --- features ----------------------------------------------------------------------

@test("F07", "List, open, read: unwrapped rows asked for, wrapped to the whole screen", "e2e-ui")
def f07(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    widths = [int(m.group(1)) for r in relay.requests for m in [re.search(r"[?&]width=(\d+)", r["path"])] if m]
    check(widths and widths[0] == 400, f"asked for width {widths[:1]}, expected 400 (rows as they are)")
    rows = phone.rows_after(0, "drawn")
    cols = max(len(r) for r in rows)
    lo, hi, rlo, rhi = (12, 20, 5, 7) if profile == "midp1" else (25, 40, 10, 24)
    check(rlo <= len(rows) <= rhi, f"{len(rows)} rows on screen, expected {rlo} to {rhi}")
    check(cols <= hi, f"a row of {cols} characters, more than fit")
    check(rows[-1].startswith("$"), f"the prompt is not on the last row: {rows[-1]!r}")
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


@test("F14", "Claude Code lens: rules gone, footer hidden, the input box stays at the bottom while scrolling",
      "e2e-ui")
def f14(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="claude")
    phone.expect(r"^Panes: ", "pane list")
    phone.click("select")
    phone.expect(r"^work/0\.0: .*Claude Code", "content")
    rows = phone.rows_after(0, "drawn")
    # The small build asks for Latin-1, so the hub (here the relay) spells the prompt as >.
    box = [i for i, r in enumerate(rows) if r.startswith(("\u276f fix the typo", "> fix the typo"))]
    check(box and box[0] >= len(rows) - 2, f"input box not at the bottom: {rows}")
    check(not any("\u2500" in r or "----" in r for r in rows), f"a rule is shown: {rows}")
    check(not any("shift+tab" in r or "/effort" in r for r in rows), f"footer or status shown: {rows}")
    check(rows[box[0] - 1].strip(), f"blank row above the box: {rows}")
    time.sleep(1)
    phone.shot("claude")
    n = len(phone.trace())
    phone.click("k2")
    phone.click("k2")
    scrolled = phone.rows_after(n, "scrolled")
    check(scrolled[:box[0]] != rows[:box[0]], "2 did not scroll")
    check(scrolled[box[0]] == rows[box[0]], f"the box moved while scrolling: {scrolled}")
    phone.shot("scrolled")
    n = len(phone.trace())
    phone.click("star")
    revealed = phone.rows_after(n, "revealed")
    check(any("shift+tab" in r for r in revealed), f"* did not reveal the footer: {revealed}")
    phone.shot("revealed")
    phone.click("pound")
    phone.expect(r"^Info on", "overlay")
    time.sleep(0.5)
    phone.shot("info")


@test("F15", "Keypad: 5 opens Reply, 0 opens Keys, 8 scrolls back", "e2e-ui")
def f15(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    phone.click("k5")
    phone.expect(r"^Reply$", "reply by 5")
    phone.type("seq 2 30")  # more rows than any screen, so there is something to scroll
    phone.shot("reply-by-5")
    phone.click("right")  # Send
    wait_for("seq output", lambda: "29\n30" in core.pane())
    phone.expect(r"^work/0\.0: .*29\|30", "refreshed pane shows the output")
    phone.click("k0")
    phone.expect(r"^Keys$", "keys by 0")
    phone.shot("keys-by-0")
    phone.click("select")  # Enter, the first key
    time.sleep(2)
    rows = phone.rows()
    n = len(phone.trace())
    phone.click("k2")
    up = phone.rows_after(n, "scrolled up")
    check(up != rows, "2 did not scroll")
    n = len(phone.trace())
    phone.click("k8")
    down = phone.rows_after(n, "scrolled down")
    check(down == rows, f"8 did not scroll back: {down}")


@test("F16", "Keys: Shift-Tab reaches the pane", "e2e-ui")
def f16(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    # cat echoes what it gets, so Shift-Tab shows as ^[[Z; a shell would swallow it.
    core.tmux_cmd("send-keys", "-t", "work:0.0", "-l", "cat")
    core.tmux_cmd("send-keys", "-t", "work:0.0", "Enter")
    wait_for("cat running", lambda: core.command() == "cat")
    open_pane(phone)
    phone.click("k0")
    phone.expect(r"^Keys$", "keys by 0")
    phone.key("Down", "Down", "Down", "Down")  # Enter, Ctrl-C, Escape, Tab, Shift-Tab
    phone.shot("shift-tab")
    phone.click("select")
    wait_for("shift-tab typed", lambda: "^[[Z" in core.pane())
    phone.expect(r"^work/0\.0: .*\^\[\[Z", "pane shows the escape")
    phone.shot("typed")


def second_pane(core):
    core.tmux_cmd("split-window", "-t", "work:0.0", "bash --norc --noprofile")
    core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1", "--write")


@test("F17", "Pane: View set to Plain for one pane shows its rules, is kept, and leaves other panes alone",
      "e2e-ui")
def f17(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="claude", before=second_pane)
    phone.expect(r"^Panes: .*work/0\.1", "pane list with two panes")
    phone.click("select")
    phone.expect(r"^work/0\.0: .*Claude Code", "content")
    rows = phone.rows_after(0, "drawn")
    check(not any("shift+tab" in r for r in rows), f"Auto did not hide the footer: {rows}")
    phone.menu(4)  # Pane
    time.sleep(1)
    phone.shot("pane-view")
    phone.key("Down")  # Plain
    n = len(phone.trace())
    phone.click("select")
    phone.expect(r"^Pane view 1$", "plain chosen")
    rows = phone.rows_after(n, "plain")
    check(any("shift+tab" in r for r in rows), f"Plain still hides the footer: {rows}")
    phone.shot("plain")
    # Kept across a reopen; the second pane is still Auto.
    phone.menu(1)  # Back
    phone.expect(r"^Panes: ", "pane list again")
    n = len(phone.trace())
    phone.click("select")
    rows = phone.rows_after(n, "reopened")
    check(any("shift+tab" in r for r in rows), f"the pane forgot Plain: {rows}")
    phone.menu(1)
    phone.expect(r"^Panes: ", "pane list once more")
    phone.key("Down")
    n = len(phone.trace())
    phone.click("select")
    phone.expect(r"^View " + HOST + r"/work/0\.1", "second pane")
    rows = phone.rows_after(n, "second pane drawn")
    check(not any("shift+tab" in r for r in rows), f"Plain leaked to another pane: {rows}")


@test("F18", "Pane: View set to Claude Code pins the prompt row of a screen without rules", "e2e-ui")
def f18(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="claude-norules")
    phone.expect(r"^Panes: ", "pane list")
    phone.click("select")
    phone.expect(r"^work/0\.0: .*Claude Code", "content")
    rows = phone.rows_after(0, "drawn")
    check(any("shift+tab" in r for r in rows), f"Auto hid rows of a screen it cannot know: {rows}")
    phone.menu(4)  # Pane
    time.sleep(1)
    phone.key("Down", "Down")  # Claude Code
    n = len(phone.trace())
    phone.click("select")
    phone.expect(r"^Pane view 2$", "lens chosen")
    rows = phone.rows_after(n, "forced")
    box = [i for i, r in enumerate(rows) if r.startswith(("\u276f fix the typo", "> fix the typo"))]
    check(box and box[0] >= len(rows) - 2, f"prompt row not pinned: {rows}")
    check(not any("shift+tab" in r for r in rows), f"footer shown: {rows}")
    phone.shot("forced")
    n = len(phone.trace())
    phone.click("star")
    rows = phone.rows_after(n, "revealed")
    check(any("shift+tab" in r for r in rows), f"* did not reveal the footer: {rows}")


@test("F19", "Text: the small build asks for Latin-1 and reads the hub's spelling, the large one asks for nothing",
      "e2e")
def f19(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="claude")
    phone.expect(r"^Panes: ", "pane list")
    phone.click("select")
    phone.expect(r"^work/0\.0: .*Claude Code", "content")
    charsets = [m.group(1) for r in relay.requests for m in [re.search(r"[?&]charset=([a-z0-9-]+)", r["path"])] if m]
    rows = phone.rows_after(0, "drawn")
    if profile == "midp1":
        check(charsets and charsets[0] == "latin1", f"asked for {charsets[:1]}, expected latin1")
        check(any(r.startswith("> fix the typo") for r in rows), f"the ASCII prompt is not shown: {rows}")
        check(not any("\u276f" in r or "\u2500" in r for r in rows), f"Unicode on a Latin-1 phone: {rows}")
    else:
        check(not charsets, f"asked for a charset: {charsets}")
        check(any(r.startswith("\u276f fix the typo") for r in rows), f"the prompt is not shown: {rows}")


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
                           str(root / "src/muxway/Trace.java"), str(root / "src/muxway/Lens.java"),
                           str(root / "src/muxway/ClaudeCode.java"), str(root / "profiles/midp2/muxway/Ui.java"),
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
