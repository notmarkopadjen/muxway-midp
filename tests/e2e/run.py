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
from harness import HOST, PANES_MENU_PLUS, Core, Failure, Phone, Relay, check, wait_for  # noqa: E402

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
          props=None, revoke=False, before=None, says_claude=False, inbox=False, files=None, preview=False,
          sessions=False, close=False, flags=None):
    """flags: the hosts' flags column the relay writes, set before the phone's first read."""
    logs = run.logs(name)
    core = Core(run, run.binary, logs).start(expose=expose, inbox=inbox, preview=preview, sessions=sessions, close=close)
    run.cleanups.append(core.close)
    tok = core.token(read=read, write=write)
    if revoke:
        core.revoke_all()
    relay = Relay(core.url, logs, mode)
    relay.says_claude = says_claude
    if flags:
        relay.hosts_flags = {HOST: flags}
    run.cleanups.append(relay.close)
    if before:
        before(core)  # the tmux side a test wants in place before the phone looks
    all_props = {"Muxway-Server": relay.url, "Muxway-Token": tok}
    all_props.update(props or {})
    phone = Phone(run, name, profile, all_props, files=files)
    run.cleanups.append(phone.close)
    return core, relay, phone, tok


def open_pane(phone):
    phone.expect(r"^Panes: .*test-pane \(bash\)", "pane list")
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
    phone.pick_list("Settings")
    phone.expect(r"^Settings: ", "settings")
    phone.shot("settings")
    check(all(tok not in line and tok[4:20] not in line for line in phone.trace()), "token shown on screen")


@test("S04", "Read-only pane: no Reply; a relay that lies about it gets Read only", "e2e-security")
def s04(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, expose=None, mode="lie-writable")
    phone.expect("^" + phone.lp + r"\* ", "the lying relay's list")
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
    phone.expect("^" + phone.lp + r"  ", "honest list without a star")
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
    phone.pick("Refresh")
    phone.expect(r"^work/0\.0: ok ", "garbage content")
    shown = [l for l in phone.trace() if l.startswith("work/0.0: ok")][-1]
    check(not re.search(r"[\x00-\x08\x0b-\x1f\x7f]", shown), f"control characters shown: {shown!r}")
    phone.shot("garbage")
    relay.mode = "normal"
    phone.pick("Refresh")
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
            phone.pick("Refresh")  # the next lie
            rows = phone.rows_after(n, f"lie {i} drawn")
        else:
            rows = phone.rows_after(0, "drawn")
        check(any(r.startswith(("\u276f fix the typo", "> fix the typo")) for r in rows), f"lie {i}: not decoded: {rows}")
    check(not any("Exception" in line or "Error" in line for line in phone.trace()), "the app failed")
    phone.shot("lied-to")


@test("S09", "A relay that forges pane titles: the mark follows the mode column, long titles are cut", "e2e-security")
def s09(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="lie-title")
    phone.expect(r"^Panes: ", "pane list")
    labels = panes_shown(phone)
    check(len(labels) == 2, f"{len(labels)} entries, expected 2: {labels}")
    # The title's own "* " is dropped as decoration; the mark stays the hub's read-only blank.
    check(labels[0].startswith("  " + HOST + "/work/0.0 claude"), f"a star in the title was taken for the mark: {labels[0]!r}")
    check(labels[1].startswith("* xxxx") and len(labels[1]) <= 66, f"the long title was not cut: {labels[1]!r}")
    phone.shot("forged")


@test("S10", "A relay that says a shell runs claude: the lens applied by program hides nothing of a shell", "e2e-security")
def s10(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, says_claude=True)
    phone.expect(r"^Panes: .*\(claude\)" if profile == "midp1" else r"^Panes: .*Claude \u00b7 test-pane", "list says claude")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    rows = phone.rows_after(0, "drawn")
    check(rows[-1].startswith("$"), f"the shell's prompt is not the last row: {rows}")
    phone.shot("shell-as-claude")


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
    phone.expect("^" + phone.lp + r"  ", "list without a star")
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
    phone.pick("Keys")
    phone.shot("keys")
    phone.key("Down")  # Ctrl-C is second
    phone.click("select")
    wait_for("sleep interrupted", lambda: core.command() == "bash")
    phone.shot("interrupted")


@test("F10", "Auto refresh: unchanged pane answered 304", "e2e")
def f10(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, props={"Muxway-Refresh": "2"})
    open_pane(phone)
    phone.pick("Auto")
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
    phone.pick("Refresh")
    phone.expect(r"^Alert: Host offline", "offline")
    phone.shot("offline")
    dismiss(phone)
    core.start_agent()
    n = len(phone.trace())
    phone.pick("Refresh")
    wait_for("content again", lambda: any(l.startswith("work/0.0: ") for l in phone.trace()[n:]), 25)
    phone.shot("recovered")
    relay.close()
    phone.pick("Refresh")
    phone.expect(r"^Alert: No connection", "relay gone")
    phone.shot("no-connection")


CHROME = ("shift+tab", "Update installed", "Crunched for")  # each short enough to survive wrapping


@test("F14", "Claude Code lens: rules, summary, notice and footer hidden, the typed box stays at the bottom while scrolling, * shows all",
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
    check(not any("\u2500" in r or "----" in r or "demo" in r for r in rows), f"a rule is shown: {rows}")
    check(not any(c in r for r in rows for c in CHROME), f"chrome shown: {rows}")
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
    # * shows the chrome in place, footer last, no rule.
    n = len(phone.trace())
    phone.click("star")
    shown = phone.rows_after(n, "revealed")
    check("agents" in shown[-1] and any("shift+tab" in r for r in shown[-3:]), f"the footer is not the last row: {shown}")
    check(not any("\u2500" in r or "----" in r for r in shown), f"a rule is shown when revealed: {shown}")
    phone.shot("revealed")
    n = len(phone.trace())
    for _ in range(6):
        phone.click("k2")
    up = phone.rows_after(n, "scrolled up when revealed")
    check(all(any(c in r for r in shown + up) for c in CHROME[1:]), f"summary and notice not reachable: {shown + up}")
    n = len(phone.trace())
    phone.click("pound")
    phone.expect(r"^Info on", "overlay")
    time.sleep(0.5)
    phone.shot("info")


@test("F15", "Keypad: 5 opens Reply, 0 opens Keys, 8 scrolls back, 1 refreshes", "e2e-ui")
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
    reads = lambda: len([r for r in relay.requests if r["path"].startswith("/v1/panes/" + HOST)])
    before = reads()
    phone.click("k1")
    wait_for("1 read the pane again", lambda: reads() > before)
    phone.shot("refreshed-by-1")


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


def second_pane(core, title="test-pane-2"):
    core.tmux_cmd("split-window", "-t", "work:0.0", "bash --norc --noprofile")
    core.tmux_cmd("select-pane", "-t", "work:0.1", "-T", title)  # never the machine's own name
    core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.1", "--write")


def panes_shown(phone):
    """The entries of the pane list as last drawn."""
    lists = [l for l in phone.trace() if l.startswith("Panes: ")]
    check(lists, "no pane list drawn")
    return [e for e in lists[-1][7:].split("|") if e and not e.startswith("= ")]


@test("F17", "Pane: View set to Plain for one pane shows its rules, is kept, and leaves other panes alone",
      "e2e-ui")
def f17(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="claude", before=second_pane)
    phone.expect(r"^Panes: .*test-pane-2", "pane list with two panes")
    phone.click("select")
    phone.expect(r"^work/0\.0: .*Claude Code", "content")
    rows = phone.rows_after(0, "drawn")
    check(not any("shift+tab" in r for r in rows), f"Auto did not hide the footer: {rows}")
    phone.pick("Pane")
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
    phone.pick("Back")
    phone.expect(r"^Panes: ", "pane list again")
    n = len(phone.trace())
    phone.click("select")
    rows = phone.rows_after(n, "reopened")
    check(any("shift+tab" in r for r in rows), f"the pane forgot Plain: {rows}")
    phone.pick("Back")
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
    phone.pick("Pane")
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


def host_named_pane(core):
    second_pane(core, HOST)  # tmux's default title is the machine's name, and a host is often named after it


@test("F20", "Panes listed by title and program: a title like the host falls back to the address, a program's own title shows",
      "e2e-ui")
def f20(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, before=host_named_pane)
    phone.expect("^" + phone.lp + r"\* test-pane \(bash\)\|", "first pane by title")
    labels = panes_shown(phone)
    check(labels == ["* test-pane (bash)", "* work/0.1 (bash)"], f"list: {labels}")
    phone.shot("list")
    # The program in the first pane names itself the way Claude Code does.
    core.tmux_cmd("send-keys", "-t", "work:0.0", "-l", "printf '\\033]2;\u2733 build\\a'")
    core.tmux_cmd("send-keys", "-t", "work:0.0", "Enter")
    wait_for("title set", lambda: "build" in core.tmux_cmd("display-message", "-p", "-t", "work:0.0", "#{pane_title}"))
    time.sleep(1.2)  # the hub lists panes from a one-second cache
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    phone.click("pound")
    time.sleep(0.5)
    phone.shot("overlay")
    phone.pick("Back")
    # The spark says Claude on the 2.0 build, as on every client; the small build drops it as decoration.
    phone.expect("^" + phone.lp + (r"\* Claude \u00b7 build\|" if profile == "midp2" else r"\* build \(bash\)\|"), "the program's title, its mark dropped")
    phone.shot("titled")


@test("F21", "Auto picks the Claude Code lens by the program: no rules needed, the empty box is hidden until *", "e2e-ui")
def f21(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="claude-empty", says_claude=True)
    phone.expect(r"^Panes: ", "pane list")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    rows = phone.rows_after(0, "drawn")
    check(rows[-1].endswith("pong"), f"the last transcript row is not at the bottom: {rows}")
    check(not any(r.strip() in ("\u276f", ">") for r in rows), f"the empty box is shown: {rows}")
    check(not any(c in r for r in rows for c in CHROME), f"chrome shown: {rows}")
    phone.shot("auto-claude")
    n = len(phone.trace())
    phone.click("star")
    shown = phone.rows_after(n, "revealed")
    check("agents" in shown[-1] and any(r.strip() in ("\u276f", ">") for r in shown), f"* did not show box and footer: {shown}")
    phone.shot("revealed")


@test("J14", "A new install file replaces the saved token; the same one is taken once", "e2e")
def j14(run, name, profile):
    logs = run.logs(name)
    core = Core(run, run.binary, logs).start()
    run.cleanups.append(core.close)
    first, second = core.token(), core.token()
    relay = Relay(core.url, logs, "normal")
    run.cleanups.append(relay.close)
    home = Path(run.tmp) / f"home-{name}-{profile}"
    home.mkdir()
    phone = Phone(run, name, profile, {"Muxway-Server": relay.url, "Muxway-Token": first}, home=home)
    phone.expect(r"^Panes: ", "list with the first token")
    phone.close()
    n = len(relay.requests)
    phone = Phone(run, name, profile, {"Muxway-Server": relay.url, "Muxway-Token": second}, home=home)
    run.cleanups.append(phone.close)
    phone.expect(r"^Panes: ", "list after the reinstall")
    used = [r["headers"].get("X-Muxway-Token") for r in relay.requests]
    check(used[:n] and all(t == first for t in used[:n]), "the first install did not use its token")
    check(used[n:] and all(t == second for t in used[n:]), f"the reinstall kept the old token")


@test("J13", "Without settings in the install file the app opens Settings", "e2e-ui")
def j13(run, name, profile):
    phone = Phone(run, name, profile, {})
    run.cleanups.append(phone.close)
    phone.expect(r"^Settings: $", "settings first")
    phone.shot("settings")


# --- images ----------------------------------------------------------------------

def jpeg(width=64, height=48):
    """A synthetic JPEG from ImageMagick; nothing of anyone's."""
    return subprocess.run(["convert", "-size", f"{width}x{height}", "xc:gray50", "jpg:-"], capture_output=True, check=True).stdout


def png():
    return subprocess.run(["convert", "-size", "8x8", "xc:gray50", "png:-"], capture_output=True, check=True).stdout


def send_image(phone, shots=False):
    """3 on the pane, Gallery, the first file of the phone's memory; the upload is then on its way."""
    n = len(phone.trace())
    phone.click("k3")
    phone.expect(r"^Image: Gallery$", "the Camera / Gallery choice (no camera in the emulator)", after=n)
    if shots:
        phone.shot("source")
    phone.click("select")  # Gallery, the only entry
    phone.expect(r"^Gallery file:///c/: \.\.\|", "the photo folder", after=n)
    if shots:
        phone.shot("gallery")
    phone.key("Down")  # past ..
    phone.click("select")
    return n


def files_requests(relay):
    return [r for r in relay.requests if r["path"].split("?")[0].endswith("/files")]


@test("S11", "Read-only token: no Image in the menu, 3 does nothing", "e2e-security")
def s11(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, write=None, inbox=True)
    open_pane(phone)
    phone.click("k3")
    time.sleep(2)
    phone.shot("pane")
    check(not any(l.startswith("Image:") for l in phone.trace()), "Image opened on a read-only pane")
    check(not files_requests(relay), "a files request for a read-only pane")


@test("S12", "Host without an inbox: Image says so, no camera, no upload", "e2e-security", profiles=("midp2",))
def s12(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    phone.click("k3")
    phone.expect(r"^Alert: The host takes no files", "no inbox reported")
    phone.shot("no-inbox")
    reqs = files_requests(relay)
    check(reqs and all(r["method"] == "GET" for r in reqs), f"not just the probe: {[r['method'] for r in reqs]}")
    check(not any(l.startswith("Image:") for l in phone.trace()), "the Camera / Gallery choice opened anyway")


@test("S13", "Upload answered with a hostile path: cut, printable, in the box only; garbage is an error",
      "e2e-security", profiles=("midp2",))
def s13(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="upload-notstring", inbox=True, files={"photo.jpg": jpeg()})
    open_pane(phone)
    for mode in ("notstring", "notjson"):
        relay.mode = "upload-" + mode
        n = send_image(phone)
        phone.expect(r"^Alert: Unexpected answer\.", f"{mode}: an error, not a path", after=n)
        dismiss(phone)
    relay.mode = "upload-control"
    n = send_image(phone)
    line = phone.expect(r"^Reply ", "the box with the path", after=n)
    check(line == "Reply /tmp/x[2Jy z.jpg", f"control characters not dropped: {line!r}")
    phone.shot("control")
    phone.menu(1)  # Back, to the pane
    relay.mode = "upload-long"
    n = send_image(phone)
    line = phone.expect(r"^Reply /a+$", "the long path", after=n)
    check(len(line) == len("Reply ") + 256, f"path not cut to 256: {len(line)}")
    phone.shot("long")
    probes = [r for r in files_requests(relay) if r["method"] == "GET"]
    check(len(probes) == 1, f"the host was asked {len(probes)} times whether it takes files")


@test("S14", "A file over 1 MiB is refused on the phone, before any request", "e2e-security", profiles=("midp2",))
def s14(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, inbox=True, files={"big.jpg": jpeg() + b"\0" * (1 << 20)})
    open_pane(phone)
    n = send_image(phone)
    phone.expect(r"^Alert: Too big for the phone", "refused", after=n)
    phone.shot("too-big")
    check(all(r["method"] == "GET" for r in files_requests(relay)), "the file was sent")


@test("S15", "Every refusal of an upload has its message and leaves the pane usable", "e2e-security", profiles=("midp2",))
def s15(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="upload-429", inbox=True, files={"photo.jpg": jpeg()})
    open_pane(phone)
    said = {"429": "Too many tries", "413": r"Too big \(8 MiB", "415": "Not an image the host takes",
            "507": "The host's inbox is full", "404": "The host takes no files", "403": "Read only"}
    for status, text in said.items():
        relay.mode = "upload-" + status
        n = send_image(phone)
        phone.expect(r"^Alert: " + text, f"{status} reported", after=n)
        dismiss(phone)
    phone.shot("pane")
    sent = [r for r in files_requests(relay) if r["method"] == "POST"]
    check(len(sent) == len(said), f"{len(sent)} uploads for {len(said)} tries")


@test("S17", "A .jpg holding PNG bytes: the hub refuses it", "e2e-security", profiles=("midp2",))
def s17(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, inbox=True, files={"fake.jpg": png()})
    open_pane(phone)
    n = send_image(phone)
    phone.expect(r"^Alert: Not an image the host takes", "refused by the hub", after=n)
    phone.shot("refused")
    check(not list(core.inbox.glob("*")), "the file landed in the inbox")


@test("F22", "Image from the gallery: the file lands in the inbox, the path in the box, the reply in the pane",
      "e2e-ui", profiles=("midp2",))
def f22(run, name, profile):
    photo = jpeg()
    core, relay, phone, tok = setup(run, name, profile, inbox=True, files={"photo.jpg": photo})
    open_pane(phone)
    n = send_image(phone, shots=True)
    line = phone.expect(r"^Reply /\S+/inbox/\S+\.jpg$", "the box with the inbox path", after=n)
    path = line[len("Reply "):]
    time.sleep(1)
    phone.shot("reply")
    landed = list(core.inbox.glob("*.jpg"))
    check(len(landed) == 1 and landed[0].read_bytes() == photo, f"inbox holds {landed}")
    check(str(landed[0]) == path, f"the path shown is not the file's: {path}")
    sent = [r for r in files_requests(relay) if r["method"] == "POST"]
    check(len(sent) == 1 and tok not in sent[0]["body"].decode("latin-1"), "the token is in the upload's body")
    check(b'filename="file"' in sent[0]["body"] and b"photo.jpg" not in sent[0]["body"], "the phone's file name was sent")
    phone.click("text")
    phone.key("End")
    phone.xdo("type", "--delay", "80", "ok")
    phone.click("right")  # Send
    # The path may wrap in the 80-column pane; its file name does not.
    wait_for("the reply in the pane", lambda: Path(path).name in core.pane() and " ok" in core.pane())
    phone.expect(r"^work/0\.0: .*inbox/", "the pane shows the path", after=n)
    phone.shot("pane")


@test("F23", "Image from the menu, probed once; the overlay names the key", "e2e-ui", profiles=("midp2",))
def f23(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, inbox=True, files={"photo.jpg": jpeg()})
    open_pane(phone)
    phone.pick("Image")
    phone.expect(r"^Image: Gallery$", "the choice from the menu")
    phone.shot("source")
    phone.click("left")  # Back
    phone.expect(r"^work/0\.0: ", "back on the pane")
    n = len(phone.trace())
    phone.click("k3")
    phone.expect(r"^Image: Gallery$", "the choice from the key", after=n)
    probes = [r for r in files_requests(relay) if r["method"] == "GET"]
    check(len(probes) == 1, f"the host was asked {len(probes)} times whether it takes files")


@test("F25", "MIDP 1.0 build: no Image on a writable pane, 3 does nothing", "e2e", profiles=("midp1",))
def f25(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, inbox=True)
    open_pane(phone)
    phone.click("k3")
    time.sleep(2)
    check(not any(l.startswith("Image:") for l in phone.trace()), "Image opened on the MIDP 1.0 build")
    check(not files_requests(relay), "a files request from the MIDP 1.0 build")


# --- the inbox and answers ---------------------------------------------------------

ALLOWED = re.compile(r"^/v1/(hosts|panes|inbox|talk)(\?[^/]*)?$|^/v1/panes/[^/?]+/[^/?]+/[^/?]+(/(input|answer|files|later|mail|close))?(\?[^/]*)?$"
                     r"|^/v1/hosts/[^/?]+/sessions$|^/v1/windows/[^/?]+/[^/?]+/[^/?]+/panes$")
# A read-only pane's menu: Refresh takes the soft key, and nothing that writes is offered.
RO_MENU = ["Back", "Auto", "Mail", "Pane", "Settings", "Exit"]
MENU_WITH_CLOSE = ["Keys", "Back", "Image", "Refresh", "Auto", "Mail", "Commands", "Pane", "Later", "Settings", "Close", "Exit"]
# The list's menu with + pane alone (a writable pane, no host that starts sessions).
PANES_MENU_SPLIT = ["Inbox", "Messages", "Auto", "Settings", "+ pane", "Exit"]


def asks(core):
    core.ask("deploy?", ["yes", "no"])
    wait_for("the ask on the hub", lambda: core.command() != "bash", 10)
    time.sleep(1.2)  # the hub lists from a one-second cache


@test("S16", "Read-only token: the inbox row offers no choices and opens the pane; a relay that lies about the mode gets the hub's refusal",
      "e2e-security", profiles=("midp2",))
def s16(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, write=None, before=asks)
    phone.expect("^" + phone.lp + r" \?test-pane \(muxway\)", "a question, no star")
    phone.pick_list("Inbox")
    phone.expect(r"^Inbox: \? test-pane \(muxway\): deploy\?", "the inbox row")
    phone.shot("inbox")
    phone.click("select")
    phone.expect(r"^View " + HOST + r"/work/0\.0 read-only", "the row opened the pane, no choices offered")
    phone.expect(r"^work/0\.0: ", "content")
    check(not any(l.startswith("Answer:") for l in phone.trace()), "choices offered on a read-only pane")
    check(not any(r["method"] == "POST" for r in relay.requests), "something was posted")
    phone.shot("opened")
    # The relay says the pane is writable: the choices are offered, the hub refuses the answer.
    relay.mode = "lie-writable"
    phone.pick("Back", RO_MENU)
    phone.expect("^" + phone.lp + r"\*\?test-pane", "the lying list", after=1)
    n = len(phone.trace())
    phone.pick_list("Inbox", PANES_MENU_SPLIT)
    phone.expect(r"^Inbox: \? test-pane \(muxway\): deploy\?", "the inbox row again", after=n)
    phone.click("select")
    phone.expect(r"^Answer: yes\|no\|", "the choices", after=n)
    phone.shot("choices")
    phone.click("select")  # yes
    phone.expect(r"^Alert: Read only", "the hub's refusal")
    phone.shot("refused")
    check(not re.search(r"^EXIT=\d", core.pane(), re.M), "the ask ended")
    check(core.waiting(tok), "the question left the inbox")


@test("S18", "A relay that forges inbox choices: nine of them, one too long, one with a control character",
      "e2e-security", profiles=("midp2",))
def s18(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="lie-inbox")
    phone.expect(r"^Panes: ", "pane list")
    phone.pick_list("Inbox")
    phone.expect(r"^Inbox: \? test-pane \(bash\): pick one", "the forged row")
    phone.click("select")
    answer = phone.expect(r"^Answer: ", "the choices")
    shown = [e for e in answer[8:].split("|") if e]
    check(len(shown) == 8, f"{len(shown)} choices offered: {shown}")
    check(shown[0] == "x" * 40, f"the long choice is not cut: {shown}")
    check(shown[1] == "badctl", f"control character kept: {shown}")
    phone.shot("capped")


@test("S19", "The app requests only the paths a relay forwards: the inbox, an answer, the feed, mail, a session, a split and a close included",
      "e2e-security", profiles=("midp2",))
def s19(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, before=asks, sessions=True, close=True, flags="sessions|close|mail|later|ask")
    phone.expect("^" + phone.lp + r"\*\?test-pane", "pane list")
    phone.pick_list("Inbox")
    phone.expect(r"^Inbox: \? test-pane", "inbox")
    phone.click("select")
    phone.expect(r"^Answer: yes", "choices")
    phone.click("select")
    phone.expect(r"^Answered 204", "answered")
    wait_for("the ask printed the answer", lambda: "EXIT=0" in core.pane())
    phone.expect(r"^Inbox: $", "the inbox empty again", after=1)
    phone.menu(0)  # Back
    n = len(phone.trace())
    phone.pick_list("Messages", PANES_MENU_PLUS)
    phone.expect(r"^Messages: ", "the feed", after=n)
    phone.menu(0)  # Back
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    phone.pick("Mail", MENU_WITH_CLOSE)
    phone.expect(r"^Mail: ", "the pane's mail")
    phone.menu(0)  # Mail pane
    phone.type("a mail")
    phone.click("right")  # Send
    phone.expect(r"^Mailed .* 201", "mailed")
    dismiss(phone)
    phone.menu(1)  # Back to the pane
    phone.pick("Close", MENU_WITH_CLOSE)
    phone.expect(r"^Confirm Close", "the question")
    phone.click("select")  # Close
    phone.expect(r"^Close 204", "closed")
    n = len(phone.trace())
    phone.expect(r"^Panes: ", "the list again", after=n - 1)
    phone.pick_list("+ session", PANES_MENU_PLUS)
    phone.expect(r"^Session " + HOST + "$", "the session box")
    phone.type("scratch")
    phone.click("right")  # Start
    phone.expect(r"^Session 201", "started")
    phone.expect(r"^View " + HOST + r"/scratch/0\.0", "its pane opened")
    phone.pick("Back", MENU_WITH_CLOSE)
    n = len(phone.trace())
    phone.expect(r"^Panes: ", "the list once more", after=n - 1)
    phone.pick_list("+ pane", PANES_MENU_PLUS)  # the new session sorts first: its pane is under the cursor
    phone.expect(r"^Confirm New pane", "the question")
    phone.click("select")  # Beside
    phone.expect(r"^Split 201", "split")
    phone.expect(r"^View " + HOST + r"/scratch/0\.1", "the new pane opened")
    paths = [r["path"] for r in relay.requests]
    bad = [p for p in paths if not ALLOWED.match(p)]
    check(not bad, f"paths outside the relay's list: {bad}")
    for want in ("/v1/inbox", "/answer", "/v1/talk", "/mail", "/close", "/sessions", "/panes"):
        check(any(want in p for p in paths), f"{want} never requested: {paths}")


@test("S20", "A relay that forges the mail and talk columns: letters, 300 digits, itself, a path, a stranger",
      "e2e-security")
def s20(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="lie-talk", before=second_pane)
    shown = phone.expect(r"^Panes: .*test-pane-2", "pane list")
    check(shown == phone.list + "* test-pane (bash)|* test-pane-2 (bash)|", f"the forged columns left a mark: {shown}")
    phone.shot("intact")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "the first row opens its pane")


def three_panes(core):
    second_pane(core)
    core.tmux_cmd("split-window", "-t", "work:0.1", "bash --norc --noprofile")
    core.tmux_cmd("select-pane", "-t", "work:0.2", "-T", "test-pane-3")
    core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.2", "--write")


@test("F29", "Panes that talked are framed one under the other, unread mail counted after the name", "e2e-ui")
def f29(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="talk", before=three_panes)
    shown = phone.expect(r"^Panes: .*test-pane-3", "pane list")
    if profile == "midp2":
        want = phone.list + "[* test-pane \u2709 2 (bash)|]* test-pane-3 (bash)| * test-pane-2 (bash)|"
    else:
        # The 1.0 build has no room for the frame: the list as always.
        want = phone.list + "* test-pane (bash)|* test-pane-2 (bash)|* test-pane-3 (bash)|"
    check(shown == want, f"the list reads {shown!r}, want {want!r}")
    phone.shot("talk")


@test("F30", "A row pulled next to its partner opens the pane it names", "e2e-ui", profiles=("midp2",))
def f30(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="talk", before=three_panes)
    phone.expect("^" + phone.lp + r"\[\* test-pane .*\]\* test-pane-3", "the regrouped list")
    phone.key("Down")  # the partner, pulled up from the third place
    phone.click("select")
    phone.expect(r"^View " + HOST + r"/work/0\.2 writable", "the third pane opened")
    phone.expect(r"^work/0\.2: ", "content")
    phone.shot("partner")


@test("F24", "A pane that waits shows what for after its mark; working clears it and the pane opens plain", "e2e-ui")
def f24(run, name, profile):
    def question(core):
        core.notify("question", "deploy?")
        time.sleep(1.2)
    core, relay, phone, tok = setup(run, name, profile, before=question)
    phone.expect("^" + phone.lp + r"\*\?test-pane \(bash\)", "the question mark")
    phone.shot("question")
    core.notify("permission", "run it?")
    time.sleep(1.2)
    phone.click("right")  # Refresh
    phone.expect("^" + phone.lp + r"\*!test-pane \(bash\)", "the permission mark", after=1)
    core.notify("working")
    time.sleep(1.2)
    phone.click("right")
    phone.expect("^" + phone.lp + r"\* test-pane \(bash\)", "cleared", after=2)
    phone.shot("cleared")
    # A working pane is plain on the keypad: no glyph, no word, no paint (guideline 1.2).
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "the working pane opens")
    phone.click("pound")
    phone.expect(r"^Info on", "overlay on the working pane")
    time.sleep(1)
    phone.shot("working-open")


@test("F26", "The inbox lists what waits, oldest first; a row without choices opens its pane", "e2e-ui",
      profiles=("midp2",))
def f26(run, name, profile):
    def two(core):
        second_pane(core, HOST)
        core.notify("done", "finished", "work:0.1")
        time.sleep(1.1)
        core.notify("question", "deploy?")
        time.sleep(1.2)
    core, relay, phone, tok = setup(run, name, profile, before=two)
    phone.expect("^" + phone.lp + r"\*\?test-pane", "pane list")
    phone.pick_list("Inbox")
    phone.expect(r"^Inbox: v work/0\.1 \(bash\): finished\|\? test-pane \(bash\): deploy\?", "oldest first")
    phone.shot("inbox")
    phone.click("select")
    phone.expect(r"^View " + HOST + r"/work/0\.1 writable", "the oldest pane opened")
    phone.expect(r"^work/0\.1: ", "content")
    phone.shot("opened")


@test("F27", "A question answered from the inbox reaches the asking process; one that ended says so", "e2e-ui",
      profiles=("midp2",))
def f27(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, before=asks)
    phone.expect("^" + phone.lp + r"\*\?test-pane", "pane list")
    phone.pick_list("Inbox")
    phone.expect(r"^Inbox: \? test-pane \(muxway\): deploy\?", "the question")
    phone.click("select")
    phone.expect(r"^Answer: yes\|no\|", "the choices")
    phone.key("Down")
    phone.click("select")  # no
    phone.expect(r"^Answered 204", "answered")
    wait_for("the ask printed the answer", lambda: "EXIT=0" in core.pane())
    check("\nno\n" in core.pane() or core.pane().endswith("no"), f"the answer is not on the pane: {core.pane()[-80:]}")
    phone.expect(r"^Inbox: $", "nothing waits", after=1)
    phone.shot("answered")
    # A question that ends before the phone answers: the hub says so.
    core.ask("still there?", ["yes", "no"], timeout="6s")
    wait_for("the ask on the hub", lambda: core.waiting(tok), 10)
    phone.click("right")  # Refresh
    phone.expect(r"^Inbox: \? test-pane \(muxway\): still there\?", "the second question", after=2)
    phone.click("select")
    phone.expect(r"^Answer: yes", "its choices", after=2)
    wait_for("the ask timed out", lambda: "EXIT=2" in core.pane(), 15)
    phone.click("select")
    phone.expect(r"^Alert: No question now", "the hub's 409 in words")
    phone.shot("gone")


@test("F28", "Key 9 opens the inbox from a pane on the MIDP 2.0 build; the 1.0 build has no inbox", "e2e-ui")
def f28(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    phone.click("k9")
    time.sleep(2)
    opened = any(l.startswith("Inbox: ") for l in phone.trace())
    asked = any(r["path"].startswith("/v1/inbox") for r in relay.requests)
    if profile == "midp2":
        check(opened and asked, "9 did not open the inbox")
        phone.shot("inbox-by-9")
    else:
        check(not opened and not asked, "the MIDP 1.0 build asked for the inbox")


# --- later -------------------------------------------------------------------------

def later_posts(relay):
    return [r for r in relay.requests if r["method"] == "POST" and r["path"].endswith("/later")]


def parked(core, target="work:0.0"):
    """Another client parks the pane before the phone looks."""
    core.later(core.token(), f"{HOST}/{target.replace(':', '/')}", True)
    time.sleep(1.2)  # the hub lists from a one-second cache


@test("S21", "Read-only token: a parked pane shows its mark and opens without a wake; a forged later column is a mark only",
      "e2e-security")
def s21(run, name, profile):
    def two(core):
        second_pane(core)
        parked(core)
    core, relay, phone, tok = setup(run, name, profile, write=None, mode="lie-later", before=two)
    shown = phone.expect("^" + phone.lp + r" zz test-pane \(bash\)", "the parked mark on a read-only row")
    check(shown == phone.list + " zz test-pane (bash)| zz test-pane-2 (bash)|", f"list: {shown!r}")
    phone.shot("marks")
    phone.click("select")  # the pane the hub parked: a reader opens it, nothing is posted
    phone.expect(r"^View " + HOST + r"/work/0\.0 read-only", "read-only view")
    phone.expect(r"^work/0\.0: ", "content of the parked pane")
    time.sleep(1)
    check(not any(r["method"] == "POST" for r in relay.requests), f"a reader posted: {[r['path'] for r in relay.requests]}")
    rows = core.waiting(tok)
    check(len(rows) == 1 and "\tlater\t" in rows[0], f"the pane is not parked any more: {rows}")
    phone.shot("opened-read-only")


@test("F31", "Later on the pane's menu parks it: zz in the list, last in the inbox; opening it from the inbox wakes it; Wake wakes it",
      "e2e-ui", profiles=("midp2",))
def f31(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, before=host_named_pane)
    open_pane(phone)
    phone.pick("Later")
    phone.expect(r"^Later true 204", "parked on the hub")
    phone.expect(r"^Alert: Parked for later", "told so")
    phone.shot("parked")
    dismiss(phone)
    rows = core.waiting(tok)
    check(len(rows) == 1 and rows[0].startswith(f"{HOST}/work/0.0\tlater\t"), f"the hub's inbox: {rows}")
    phone.click("pound")
    phone.expect(r"^Info on", "overlay")
    time.sleep(1)
    phone.shot("overlay")
    n = len(phone.trace())
    phone.pick("Back")
    phone.expect("^" + phone.lp + r"\*zz test-pane \(bash\)\|\* work/0\.1 \(bash\)\|$", "the mark in the list", after=n)
    phone.shot("parked-list")
    # A question on the other pane, younger than the parking: the inbox lists it first all the same.
    core.notify("question", "deploy?", "work:0.1")
    time.sleep(1.2)
    phone.pick_list("Inbox")
    phone.expect(r"^Inbox: \? work/0\.1 \(bash\): deploy\?\|zz test-pane \(bash\)\|$", "awake first, parked last")
    phone.shot("parked-inbox")
    phone.key("Down")
    n = len(phone.trace())
    phone.click("select")  # the parked row: open, and wake
    phone.expect(r"^View " + HOST + r"/work/0\.0 writable", "the parked pane opened", after=n)
    phone.expect(r"^Later false 204", "woken as it opened", after=n)
    phone.expect(r"^work/0\.0: ", "content", after=n)
    posts = later_posts(relay)
    check(len(posts) == 2 and posts[1]["body"] == b'{"later":false}', f"the wake: {[(p['path'], p['body']) for p in posts]}")
    phone.shot("woken")
    n = len(phone.trace())
    phone.pick("Back")
    phone.expect("^" + phone.lp + r"\* test-pane \(bash\)\|\*\?work/0\.1", "plain again, the question still there", after=n)
    # Later, then Wake from the menu.
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content", after=n)
    phone.pick("Later")
    phone.expect(r"^Later true 204", "parked again", after=n)
    dismiss(phone)
    n = len(phone.trace())
    phone.pick("Wake")
    phone.expect(r"^Later false 204", "woken from the menu", after=n)
    phone.expect(r"^Alert: Awake", "told so", after=n)
    phone.shot("awake")
    rows = core.waiting(tok)
    check(len(rows) == 1 and "\tquestion\t" in rows[0], f"the hub's inbox after the wake: {rows}")


@test("F32", "A pane parked elsewhere: zz on both builds; opening it wakes it on the 2.0 build and leaves it on the 1.0 build", "e2e-ui")
def f32(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, before=parked)
    phone.expect("^" + phone.lp + r"\*zz test-pane \(bash\)", "the parked mark")
    phone.shot("parked")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    time.sleep(1)
    posts = later_posts(relay)
    rows = core.waiting(tok)
    if profile == "midp2":
        check(len(posts) == 1 and posts[0]["body"] == b'{"later":false}', f"the open did not wake the pane: {posts}")
        check(not rows, f"still in the hub's inbox: {rows}")
        n = len(phone.trace())
        phone.pick("Back")
        phone.expect("^" + phone.lp + r"\* test-pane \(bash\)", "plain again", after=n)
    else:
        check(not posts, "the 1.0 build posted a wake")
        check(len(rows) == 1 and "\tlater\t" in rows[0], f"the pane is not parked any more: {rows}")
    phone.shot("after-open")


def named(core):
    """The titled pane gets an alias, the second pane none."""
    second_pane(core)
    core.alias("work:0.0", "review")


@test("S22", "A relay that forges the alias and folder columns: 300 letters, control characters, a path up, a stranger's address",
      "e2e-security")
def s22(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="lie-names", before=three_panes)
    phone.expect(r"^Panes: ", "pane list")
    labels = panes_shown(phone)
    check(len(labels) == 3, f"{len(labels)} entries, expected 3: {labels}")
    check(all(len(l) <= 66 and all(c >= " " for c in l) for l in labels), f"a row too long or not printable: {labels!r}")
    check(labels[0].startswith("* " + "a" * 60), f"the forged alias is not the name, cut: {labels[0]!r}")
    # The stranger's address is a name like any other: the row opens the hub's pane, not the stranger's.
    check(labels[2].startswith("* bob/work/0.0"), f"the third row: {labels[2]!r}")
    if profile == "midp2":
        check(labels[1] == "* ../../etc /.. \u00b7 test-pane-2 (bash)", f"the path up is text: {labels[1]!r}")
    else:
        check(labels[1] == "* ../../etc (bash)", f"no folder on the small build: {labels[1]!r}")
    phone.shot("forged")
    phone.key("Down", "Down")
    phone.click("select")
    phone.expect(r"^View " + HOST + r"/work/0\.2 writable", "the row opens the pane of its first column")
    phone.expect(r"^work/0\.2: ", "content")
    check(not any(r["method"] == "POST" for r in relay.requests), f"something was posted: {[r['path'] for r in relay.requests]}")


@test("S23", "A read-only token sees no folder: the hub keeps the path from a reader", "e2e-security", profiles=("midp2",))
def s23(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, write=None, preview=True, before=named)
    shown = phone.expect(r"^Panes: .*test-pane-2", "pane list")
    check(shown == phone.list + "  review \u00b7 test-pane (bash)|  test-pane-2 (bash)|", f"a reader was shown a folder: {shown!r}")
    check("/project" not in "".join(l for l in phone.trace()), "the folder reached the phone")
    phone.shot("reader")


@test("F33", "The alias is the name: in the list, the overlay and the inbox; the folder and the status follow on the 2.0 build", "e2e-ui")
def f33(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, preview=True, before=named)
    if profile == "midp2":
        want = phone.list + "* review /project \u00b7 test-pane (bash)|* test-pane-2 /project (bash)|"
    else:
        want = phone.list + "* review (bash)|* test-pane-2 (bash)|"
    shown = phone.expect("^" + phone.lp + r"\* review", "the alias first")
    check(shown == want, f"list: {shown!r}, want {want!r}")
    phone.shot("named")
    phone.click("select")
    phone.expect(r"^View " + HOST + r"/work/0\.0 writable", "the named pane opens")
    phone.expect(r"^work/0\.0: ", "content")
    phone.click("pound")
    phone.expect(r"^Info on", "overlay")
    time.sleep(1)
    phone.shot("overlay")
    if profile == "midp2":
        n = len(phone.trace())
        phone.pick("Back")
        phone.expect("^" + phone.lp + r"\* review", "the list again", after=n)
        core.notify("question", "deploy?")
        time.sleep(1.2)
        phone.pick_list("Inbox")
        phone.expect(r"^Inbox: \? review /project \u00b7 test-pane \(bash\): deploy\?\|$", "the inbox names the alias")
        phone.shot("inbox")


@test("F34", "A parked pane's name slants in the overlay and straightens when woken", "e2e-ui", profiles=("midp2",))
def f34(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    phone.click("pound")
    phone.expect(r"^Info on: > test-pane \(bash\)$", "overlay, plain")
    phone.click("pound")
    phone.pick("Later")
    phone.expect(r"^Later true 204", "parked")
    dismiss(phone)
    n = len(phone.trace())
    phone.click("pound")
    phone.expect(r"^Info on italic: > zz test-pane \(bash\)$", "the name in the italic face", after=n)
    time.sleep(1)
    phone.shot("slanted")
    phone.click("pound")
    n = len(phone.trace())
    phone.pick("Wake")
    phone.expect(r"^Later false 204", "woken", after=n)
    dismiss(phone)
    n = len(phone.trace())
    phone.click("pound")
    phone.expect(r"^Info on: > test-pane \(bash\)$", "plain again", after=n)
    time.sleep(1)
    phone.shot("straight")


# --- messages and mail -------------------------------------------------------------------

def mail_address(target="work:0.0"):
    return f"{HOST}/{target.replace(':', '/')}"


@test("S24", "Read-only token: the pane's mail lists, nothing offers to mail; a relay that lies about the mode gets the hub's refusal",
      "e2e-security", profiles=("midp2",))
def s24(run, name, profile):
    def mails(core):
        second_pane(core)
        t = core.token()
        core.mail(t, mail_address(), "run the tests")
        core.mail(t, mail_address(), "and tell me", origin=core.pane_id("work:0.1"))
    core, relay, phone, tok = setup(run, name, profile, write=None, before=mails)
    phone.expect(r"^Panes: ", "pane list")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    phone.pick("Mail", RO_MENU)
    shown = phone.expect(r"^Mail: ", "the mail list")
    check("< phone " in shown and "unread: run the tests" in shown and "< test-pane-2 (bash) " in shown, f"the rows: {shown!r}")
    phone.shot("mail")
    phone.click("select")  # the client's mail: nobody to mail back
    phone.expect(r"^Alert: No sender to mail back", "nothing to aim at")
    dismiss(phone)
    phone.key("Down")
    phone.click("select")  # the pane's mail: its sender is read-only too
    phone.expect(r"^Alert: No sender to mail back", "a read-only sender is not offered")
    dismiss(phone)
    check(not any(r["method"] == "POST" for r in relay.requests), "something was posted")
    # The relay says every pane is writable: the box opens, the hub refuses.
    relay.mode = "lie-writable"
    phone.menu(0)  # Back, to the pane
    phone.pick("Back", RO_MENU)
    phone.expect("^" + phone.lp + r".*\* test-pane \u2709", "the lying list", after=1)
    n = len(phone.trace())
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content again", after=n)
    phone.pick("Mail")
    phone.expect(r"^Mail: ", "the list again", after=n)
    phone.key("Down")
    phone.click("select")
    phone.expect(r"^Mail box " + HOST + r"/work/0\.1", "the box, aimed at the sender", after=n)
    phone.type("thanks")
    phone.click("right")  # Send
    phone.expect(r"^Mailed .* 403", "refused by the hub")
    phone.expect(r"^Alert: Read only", "said so")
    phone.shot("refused")


@test("S25", "A relay that forges the feed and the mail rows: letters, strangers, a path, no kind, 300 characters with control characters",
      "e2e-security", profiles=("midp2",))
def s25(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="lie-feed", before=second_pane)
    phone.expect(r"^Panes: ", "pane list")
    phone.pick_list("Messages")
    shown = phone.expect(r"^Messages: ", "the forged feed")
    rows = [r for r in shown[10:].split("|") if r]
    check(len(rows) == 1, f"{len(rows)} rows shown of four forged and the next row: {rows}")
    check(rows[0].startswith("test-pane (bash) \u2192 y/0.0 ") and len(rows[0]) <= 240 and all(c >= " " for c in rows[0]),
          f"the stranger's row: {rows[0]!r}")
    phone.shot("feed")
    phone.click("select")  # a stranger: nothing opens, nothing is asked
    time.sleep(2)
    check(not any(l.startswith("View ") for l in phone.trace()), "a stranger's pane was opened")
    phone.menu(0)  # Back
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    phone.pick("Mail")
    shown = phone.expect(r"^Mail: ", "the forged mail")
    rows = [r for r in shown[6:].split("|") if r]
    check(len(rows) == 1 and rows[0].startswith("< " + "a" * 32 + " ") and len(rows[0]) <= 242 and all(c >= " " for c in rows[0]),
          f"the forged mail rows: {rows!r}")
    phone.shot("mail")
    check(not any(r["method"] == "POST" for r in relay.requests), "something was posted")


@test("F35", "Messages: what panes said to panes, oldest first, by the list's names; 7 opens it; a typed row opens its pane, a mail row its mail",
      "e2e-ui", profiles=("midp2",))
def f35(run, name, profile):
    def talk(core):
        second_pane(core)
        t = core.token()
        core.say(t, mail_address("work:0.1"), "echo hello", core.pane_id("work:0.0"))
        core.mail(t, mail_address(), "look at the log", origin=core.pane_id("work:0.1"))
    core, relay, phone, tok = setup(run, name, profile, before=talk)
    phone.expect(r"^Panes: ", "pane list")
    phone.pick_list("Messages")
    shown = phone.expect(r"^Messages: ", "the feed")
    want = r"^Messages: test-pane \(bash\) \u2192 test-pane-2 \(bash\) \d\d:\d\d: echo hello\|test-pane-2 \(bash\) \u2192 test-pane \(bash\) \d\d:\d\d: \u2709 look at the log\|$"
    check(re.match(want, shown), f"the feed reads {shown!r}")
    phone.shot("messages")
    phone.click("select")  # the typed row opens the pane it was said to
    phone.expect(r"^View " + HOST + r"/work/0\.1 writable", "the second pane")
    phone.expect(r"^work/0\.1: ", "its content")
    n = len(phone.trace())
    phone.click("k7")
    phone.expect(r"^Messages: ", "the feed by 7", after=n)
    phone.shot("from-7")
    phone.key("Down")
    phone.click("select")  # the mail row opens the mail of the pane it went to
    shown = phone.expect(r"^Mail: ", "the first pane's mail", after=n)
    check(re.match(r"^Mail: < test-pane-2 \(bash\) \d\d:\d\d unread: look at the log\|$", shown), f"the mail reads {shown!r}")
    phone.shot("mail")


@test("F36", "Mail: what came in and what the pane sent, merged by time; Mail pane and mail back reach their mailboxes",
      "e2e-ui", profiles=("midp2",))
def f36(run, name, profile):
    def mails(core):
        second_pane(core)
        t = core.token()
        core.mail(t, mail_address(), "run the tests")
        core.mail(t, mail_address("work:0.1"), "ok", origin=core.pane_id("work:0.0"))
        core.mail(t, mail_address(), "and tell me", origin=core.pane_id("work:0.1"))
    core, relay, phone, tok = setup(run, name, profile, before=mails)
    phone.expect(r"^Panes: .*test-pane \u2709 2", "the list, the unread mail counted")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    phone.pick("Mail")
    shown = phone.expect(r"^Mail: ", "the mail list")
    rows = [r for r in shown[6:].split("|") if r]
    check(len(rows) == 3 and re.match(r"^< phone \d\d:\d\d unread: run the tests$", rows[0])
          and re.match(r"^> test-pane-2 \(bash\) \d\d:\d\d: ok$", rows[1])
          and re.match(r"^< test-pane-2 \(bash\) \d\d:\d\d unread: and tell me$", rows[2]), f"the rows: {rows!r}")
    phone.shot("mail")
    phone.menu(0)  # Mail pane
    phone.expect(r"^Mail box " + HOST + r"/work/0\.0", "the box, aimed at the pane")
    phone.type("done, two failures")
    phone.shot("box")
    phone.click("right")  # Send
    phone.expect(r"^Mailed " + HOST + r"/work/0\.0 201", "mailed")
    phone.expect(r"^Alert: Mailed", "said so")
    dismiss(phone)
    shown = [l for l in phone.trace() if l.startswith("Mail: ")][-1]
    check("unread: done, two failures" in shown, f"the new mail is not listed: {shown!r}")
    phone.shot("mailed")
    phone.key("Down", "Down")
    phone.click("select")  # the pane's mail: mail back
    phone.expect(r"^Mail box " + HOST + r"/work/0\.1", "the box, aimed at the sender")
    phone.type("thanks")
    phone.click("right")
    phone.expect(r"^Mailed " + HOST + r"/work/0\.1 201", "mailed back")
    status, box = core.get(tok, f"/v1/panes/{mail_address('work:0.1')}/mail")
    check("\tthanks" in box, f"the sender's mailbox: {box!r}")
    sent = [r for r in relay.requests if r["method"] == "POST" and r["path"].endswith("/mail")]
    check(all(b"from" not in r["body"] for r in sent), f"a mail claimed an origin: {[r['body'] for r in sent]}")


# --- hosts, the overlay, next and previous ----------------------------------------------

@test("S26", "A relay that forges the hosts' flags: unknown words, 300 columns, a ghost host; sessions claimed get the hub's refusal",
      "e2e-security")
def s26(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="lie-hosts")
    shown = phone.expect(r"^Panes: .*test-pane", "pane list")
    if profile == "midp1":
        check(shown == "Panes: * test-pane (bash)|", f"the forged hosts left a mark on the small build: {shown!r}")
        return
    check(shown == f"Panes: = {HOST} online|* test-pane (bash)|= ghost nothing exposed|", f"list: {shown!r}")
    phone.shot("forged")
    phone.pick_list("+ session", PANES_MENU_PLUS)
    phone.expect(r"^Session " + HOST + "$", "the session box")
    phone.type("scratch")
    phone.click("right")  # Start
    phone.expect(r"^Session 404$", "the hub does not start sessions here")
    phone.expect(r"^Alert: This host does not start sessions", "said so")
    phone.shot("refused")
    posts = [r for r in relay.requests if r["method"] == "POST"]
    check(len(posts) == 1 and posts[0]["path"] == f"/v1/hosts/{HOST}/sessions" and posts[0]["body"] == b'{"name":"scratch"}',
          f"posted: {[(p['path'], p['body']) for p in posts]}")


@test("F37", "A line per host with its state; a group across two hosts is framed under each; an agent gone reads offline",
      "e2e-ui", profiles=("midp2",))
def f37(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, mode="talk-across", read="*/*", write="*/*",
                                    before=lambda c: c.add_host("bob-desktop", expose=True))
    shown = phone.expect(r"^Panes: .*bob-desktop", "two hosts")
    check(shown == f"Panes: = {HOST} online|[* test-pane (bash)|= bob-desktop online|[* bob-desktop-pane (bash)|", f"list: {shown!r}")
    phone.shot("hosts")
    phone.click("select")  # the cursor starts on the first pane, under the line
    phone.expect(r"^View " + HOST + r"/work/0\.0 writable", "the first pane opened")
    phone.expect(r"^work/0\.0: ", "content")
    phone.pick("Back")
    core.stop("agent-bob-desktop")
    wait_for("bob offline", lambda: "bob-desktop\toffline" in core.cli("hub", "agents", "--dir", str(core.dir / "hub")))
    n = len(phone.trace())
    phone.click("right")  # Refresh
    shown = phone.expect(r"^Panes: .*bob-desktop offline", "the host offline", after=n)
    check(shown == f"Panes: = {HOST} online|* test-pane (bash)|= bob-desktop offline|", f"list: {shown!r}")
    phone.shot("offline")


@test("F38", "Auto on the list re-reads it on the timer without a key, the cursor kept", "e2e-ui", profiles=("midp2",))
def f38(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, props={"Muxway-Refresh": "2"}, before=second_pane)
    phone.expect(r"^Panes: .*test-pane-2", "two panes")
    phone.pick_list("Auto")
    phone.expect(r"^Alert: Auto refresh every 2 s", "auto on")
    dismiss(phone)
    phone.key("Down")  # the second pane
    core.tmux_cmd("split-window", "-t", "work:0.1", "bash --norc --noprofile")
    core.tmux_cmd("select-pane", "-t", "work:0.2", "-T", "test-pane-3")
    core.cli("expose", "--tmux-name", core.tmux, "-t", "work:0.2", "--write")
    n = len(phone.trace())
    phone.expect(r"^Panes: .*test-pane-3", "the third pane listed by the timer", after=n)
    lists = [r for r in relay.requests if r["path"].split("?")[0] == "/v1/panes"]
    check(len(lists) >= 2, f"{len(lists)} list reads")
    phone.shot("auto-list")
    phone.click("select")
    phone.expect(r"^View " + HOST + r"/work/0\.1 writable", "the cursor stayed on the second pane")


@test("F39", "D-pad right and left open the next and the previous pane, round at the ends", "e2e-ui")
def f39(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, before=three_panes)
    phone.expect(r"^Panes: .*test-pane-3", "three panes")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "the first pane")
    for key, pane in (("Right", "0.1"), ("Right", "0.2"), ("Right", "0.0"), ("Left", "0.2")):
        n = len(phone.trace())
        phone.key(key)
        phone.expect(r"^View " + HOST + "/work/" + pane.replace(".", r"\."), f"{key} to {pane}", after=n)
        phone.expect(r"^work/" + pane.replace(".", r"\.") + ": ", "its content", after=n)
    phone.shot("next")


@test("F40", "Claude named as every client names it, its title the status; an alias leads, the harness after it", "e2e-ui")
def f40(run, name, profile):
    def named_second(core):
        second_pane(core)
        core.alias("work:0.1", "review")
    core, relay, phone, tok = setup(run, name, profile, says_claude=True, before=named_second)
    shown = phone.expect(r"^Panes: .*review", "pane list")
    if profile == "midp2":
        want = phone.list + "* Claude \u00b7 test-pane|* review \u00b7 Claude \u00b7 test-pane-2|"
    else:
        want = phone.list + "* test-pane (claude)|* review (claude)|"
    check(shown == want, f"list: {shown!r}, want {want!r}")
    phone.shot("harness")


@test("F41", "The overlay carries the kind's glyph and read-only on the 2.0 build", "e2e-ui")
def f41(run, name, profile):
    def question(core):
        core.notify("question", "deploy?")
        time.sleep(1.2)
    core, relay, phone, tok = setup(run, name, profile, expose=None, before=question)
    phone.expect(r"^Panes: .* \?test-pane", "the question on a read-only row")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    phone.click("pound")
    want = r"^Info on:   \? test-pane \(bash\) read-only$" if profile == "midp2" else r"^Info on$"
    phone.expect(want, "the overlay")
    time.sleep(1)
    phone.shot("overlay-read-only")


@test("F43", "Theme: green and paper chosen in Settings paint the pane; the choice is kept across a restart", "e2e-ui", profiles=("midp2",))
def f43(run, name, profile):
    logs = run.logs(name)
    core = Core(run, run.binary, logs).start()
    run.cleanups.append(core.close)
    tok = core.token()
    relay = Relay(core.url, logs, "normal")
    run.cleanups.append(relay.close)
    home = Path(run.tmp) / f"home-{name}"
    home.mkdir()
    phone = Phone(run, name, profile, {"Muxway-Server": relay.url, "Muxway-Token": tok}, home=home)
    run.cleanups.append(phone.close)
    open_pane(phone)
    phone.expect(r"^Theme 0$", "amber by default")
    phone.shot("amber")
    for downs, theme, shot in ((8, 1, "green"), (9, 2, "paper")):
        phone.pick("Settings")
        phone.expect(r"^Settings: ", "settings")
        phone.key(*(["Down"] * downs))  # server, token, width, refresh, the three Text entries, then the themes
        phone.click("select")
        phone.click("right")  # OK
        phone.expect(r"^Theme " + str(theme) + "$", f"theme {theme} saved")
        n = len(phone.trace())
        phone.expect(r"^Panes: ", "the list", after=n - 1)
        phone.click("select")
        phone.expect(r"^work/0\.0: ", "content", after=n)
        time.sleep(1)
        phone.shot(shot)
    phone.close()
    phone = Phone(run, name, profile, {"Muxway-Server": relay.url, "Muxway-Token": tok}, home=home)
    run.cleanups.append(phone.close)
    open_pane(phone)
    phone.expect(r"^Theme 2$", "paper kept across the restart")


@test("F44", "The inbox on a read-only pane with choices: the row opens the pane, nothing is offered", "e2e-ui", profiles=("midp2",))
def f44(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, write=None, before=asks)
    phone.expect("^" + phone.lp + r" \?test-pane", "the question")
    phone.pick_list("Inbox")
    phone.expect(r"^Inbox: \? test-pane \(muxway\): deploy\?", "the inbox row")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "the pane opened")
    check(not any(l.startswith("Answer:") for l in phone.trace()), "the Answer list opened")
    phone.shot("opened")


@test("S27", "A session name is checked on the phone: a path, 33 letters, a capital; nothing is asked", "e2e-security", profiles=("midp2",))
def s27(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, flags="sessions")
    phone.expect(r"^Panes: ", "pane list")
    phone.pick_list("+ session", PANES_MENU_PLUS)
    phone.expect(r"^Session " + HOST + "$", "the box")
    for bad in ("../x", "abcdefghijklmnopqrstuvwxyz0123456", "Scratch"):
        phone.click("text")
        phone.key("ctrl+a", "BackSpace")
        phone.xdo("type", "--delay", "80", bad)
        n = len(phone.trace())
        phone.click("right")  # Start
        phone.expect(r"^Alert: A name is a-z, 0-9, - and _, up to 32", f"{bad!r} refused", after=n)
        dismiss(phone)
    phone.shot("refused")
    check(not any(r["method"] == "POST" for r in relay.requests), "a bad name reached the relay")


@test("S28", "A relay that lies about the mode on a host that closes: + pane and Close are offered, the hub refuses both",
      "e2e-security", profiles=("midp2",))
def s28(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, expose=None, mode="lie-writable", close=True, flags="close|mail")
    phone.expect("^" + phone.lp + r"\* ", "the lying list")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    phone.pick("Close", MENU_WITH_CLOSE)
    phone.expect(r"^Confirm Close", "the question")
    phone.click("select")
    phone.expect(r"^Close 403$", "refused")
    phone.expect(r"^Alert: Read only", "said so")
    dismiss(phone)
    phone.pick("Back", MENU_WITH_CLOSE)
    phone.expect("^" + phone.lp + r"\* ", "the list again", after=1)
    phone.pick_list("+ pane", PANES_MENU_SPLIT)
    phone.expect(r"^Confirm New pane", "the question")
    phone.click("select")
    phone.expect(r"^Split 403$", "refused too")
    phone.expect(r"^Alert: Read only", "said so")
    phone.shot("refused")
    check(core.pane(), "the pane is gone")


@test("S29", "A relay that forges what waits under a pane: letters, 300 of them, a kind on an awake pane", "e2e-security")
def s29(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, write=None, before=lambda c: (second_pane(c), parked(c)))
    phone.expect(r"^Panes: ", "the list")
    relay.pending = {f"{HOST}/work/0.0": "x" * 300, f"{HOST}/work/0.1": "question"}
    n = len(phone.trace())
    phone.click("right")  # Refresh, with the forged column
    shown = phone.expect(r"^Panes: .*test-pane-2", "the list again", after=n)
    check(shown == phone.list + " zz test-pane (bash)|  test-pane-2 (bash)|", f"the forged column left a mark: {shown!r}")
    relay.pending = {f"{HOST}/work/0.0": "\x1b[2J"}
    n = len(phone.trace())
    phone.click("right")
    shown = phone.expect(r"^Panes: .*test-pane-2", "the list once more", after=n)
    check(shown == phone.list + " zz test-pane (bash)|  test-pane-2 (bash)|", f"the forged column left a mark: {shown!r}")
    phone.shot("intact")


@test("F42", "Commands: a shell's starters sent with Enter; Claude Code's slash commands when the program is claude; /rename waits in Reply",
      "e2e-ui", profiles=("midp2",))
def f42(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile)
    open_pane(phone)
    phone.pick("Commands")
    phone.expect(r"^Commands 29$", "the shell's starters")
    phone.shot("commands")
    for _ in range(23):
        phone.key("Down")  # pwd, whose one line stays on the screen
    phone.click("select")
    wait_for("pwd ran", lambda: re.search(r"^\$ pwd\n/", core.pane(), re.M))
    phone.expect(r"^work/0\.0: .*\$ pwd\|/", "the pane shows it")
    phone.shot("sent")
    relay.says_claude = True
    phone.pick("Back")
    phone.expect("^" + phone.lp + r"\* Claude", "the list says claude", after=1)
    n = len(phone.trace())
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content", after=n)
    phone.pick("Commands")
    phone.expect(r"^Commands 24$", "the slash commands", after=n)
    phone.key("Down", "Down", "Down")  # /rename
    phone.click("select")
    phone.expect(r"^Reply$", "waits in Reply", after=n)
    phone.shot("rename")
    sent = [r for r in relay.requests if r["method"] == "POST"]
    check(len(sent) == 2 and sent[0]["body"] == b'{"text":"pwd"}' and sent[1]["body"] == b'{"key":"Enter"}', f"posted: {[r['body'] for r in sent]}")


@test("F45", "+ session from the host's line, + pane beside or below, Close: each where the host allows it, with its confirm list",
      "e2e-ui", profiles=("midp2",))
def f45(run, name, profile):
    core, relay, phone, tok = setup(run, name, profile, sessions=True, close=True, flags="sessions|close|mail")
    phone.expect(r"^Panes: ", "the list")
    phone.pick_list("+ session", PANES_MENU_PLUS)
    phone.expect(r"^Session " + HOST + "$", "the box")
    phone.type("scratch")
    phone.shot("session")
    phone.click("right")  # Start
    phone.expect(r"^Session 201$", "started")
    # The session's pane is titled with the machine's name by tmux, so the list is not read for it.
    phone.expect(r"^View " + HOST + r"/scratch/0\.0 writable", "its first pane opened")
    phone.expect(r"^scratch/0\.0: ", "content")
    phone.pick("Back", MENU_WITH_CLOSE)
    n = len(phone.trace())
    phone.expect(r"^Panes: ", "the list", after=n - 1)
    # The new session sorts first, so the cursor is on its pane.
    phone.pick_list("+ pane", PANES_MENU_PLUS)
    phone.expect(r"^Confirm New pane beside ", "the question")
    phone.shot("split")
    phone.key("Down")  # Below
    n = len(phone.trace())
    phone.click("select")
    phone.expect(r"^Split 201$", "split", after=n)
    phone.expect(r"^View " + HOST + r"/scratch/0\.1 writable", "the new pane opened", after=n)
    phone.expect(r"^scratch/0\.1: ", "its content", after=n)
    split = [r for r in relay.requests if r["path"].endswith("/panes") and r["method"] == "POST"]
    check(len(split) == 1 and split[0]["path"] == f"/v1/windows/{HOST}/scratch/0/panes" and split[0]["body"] == b'{"direction":"v"}',
          f"the split: {[(r['path'], r['body']) for r in split]}")
    phone.pick("Close", MENU_WITH_CLOSE)
    phone.expect(r"^Confirm Close the pane on " + HOST, "the question")
    phone.shot("close")
    n = len(phone.trace())
    phone.click("select")  # Close
    phone.expect(r"^Close 204$", "closed", after=n)
    shown = phone.expect(r"^Panes: ", "the list again", after=n)
    rows = [r for r in shown[7:].split("|") if r and not r.startswith("= ")]
    check(len(rows) == 2 and "split-pane" not in shown and "new-pane" in shown, f"the list after the close: {shown!r}")
    phone.shot("closed")
    # On a host line + pane asks for a pane first; on a host without the flag + session says so.
    phone.key("Up")
    phone.pick_list("+ pane", PANES_MENU_PLUS)
    phone.expect(r"^Alert: Pick a writable pane first", "a line is no pane")
    dismiss(phone)
    relay.hosts_flags = {HOST: "mail"}
    phone.click("right")  # Refresh
    time.sleep(2)
    phone.pick_list("+ pane", PANES_MENU_SPLIT)
    phone.expect(r"^Confirm New pane", "+ pane needs no flag")
    phone.menu(0)  # Back
    posts = [r for r in relay.requests if r["method"] == "POST"]
    check(len(posts) == 3, f"{len(posts)} writes, expected the session, the split and the close")


@test("F46", "What waits under a parked pane: zz ! in the list, the inbox and the overlay (2.0); zz alone on the 1.0 build", "e2e-ui")
def f46(run, name, profile):
    def park(core):
        parked(core)
        core.notify("permission", "run it?")
        time.sleep(1.2)
    core, relay, phone, tok = setup(run, name, profile, write=None, before=park)
    phone.expect(r"^Panes: ", "the list without the column")
    relay.pending = {f"{HOST}/work/0.0": "permission"}
    n = len(phone.trace())
    phone.click("right")  # Refresh, with the column
    want = " zz ! test-pane (bash)|" if profile == "midp2" else " zz test-pane (bash)|"
    phone.expect("^" + phone.lp + re.escape(want) + "$", "the mark with what waits under it", after=n)
    phone.shot("under")
    phone.click("select")
    phone.expect(r"^work/0\.0: ", "content")
    phone.click("pound")
    phone.expect(r"^Info on italic:   zz ! test-pane \(bash\) read-only$" if profile == "midp2" else r"^Info on$", "the overlay")
    time.sleep(1)
    phone.shot("overlay")
    if profile == "midp2":
        phone.click("pound")
        phone.click("k9")
        phone.expect(r"^Inbox: zz ! test-pane \(bash\): run it\?\|$", "the inbox row")
        phone.shot("inbox")


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
                           str(root / "src/muxway/Api.java"), str(root / "src/muxway/Picture.java"), str(root / "src/muxway/Prefs.java"),
                           str(root / "src/muxway/Trace.java"), str(root / "src/muxway/Lens.java"), str(root / "src/muxway/Talk.java"),
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
