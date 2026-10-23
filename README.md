```
┌─ muxway-midp ─┐
```

Java ME client for [muxway](https://github.com/notmarkopadjen/muxway): tmux panes on a MIDP 1.0 or 2.0 phone.

> Early development. Works on a Nokia 6310i: list panes, read one, reply, send keys.

Read panes and type into them from a phone with a 96x65 screen and GPRS. The
app runs on MIDP 1.0 and 2.0 phones and talks to a muxway hub through a
small relay, since phones of that age cannot do current HTTPS. The pane
fills the whole screen, the keypad does the common things, and a lens can
reshape one program's screen for the phone: the first one is for Claude
Code.

- [Install](docs/features/install.md)
- [The pane view and its keypad](docs/features/pane-view.md)
- [Lenses, and the Claude Code lens](docs/features/lenses.md)
- [Running a relay](docs/features/relay.md)
- [Architecture](docs/ARCHITECTURE.md)

## Build

Needs JDK 8 (`openjdk-8-jdk-headless`), `curl`, `unzip` and Python 3.

```sh
python3 tools/draw.py   # only after changing the pictures
scripts/build.sh        # out/muxway.jar (MIDP 2.0) and out/muxway1.jar (MIDP 1.0), with JADs
```

The build downloads the CLDC/MIDP stubs and ProGuard once into `lib/` and
verifies each by SHA-256. It then checks that the MIDP 1.0 JAR calls nothing
MIDP 1.0 lacks and stays under 30 KiB.

Contributors and AI agents: read [AGENTS.md](AGENTS.md) first.
Security issues: see [SECURITY.md](SECURITY.md).

## Tests

```sh
MUXWAY_CORE=../muxway scripts/e2e.sh        # everything, both builds
scripts/e2e.sh S04 F08                      # chosen tests
```

The suite builds the hub and agent from a checkout of the core repository,
starts a throwaway tmux server, a relay that can misbehave on purpose, and
the app in the microemulator on a headless display (the MIDP 1.0 build on a
96x65 screen). It drives the app with clicks and key presses and writes
`test-runs/<id>/` with a summary, JUnit results, logs, a screenshot per step
and `report/index.html`. Needs tmux, Xvfb, xdotool, ImageMagick, Go,
Python 3 and JDK 8.
