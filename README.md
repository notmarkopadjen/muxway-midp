```
┌─ muxway-midp ─┐
```

Java ME client for [muxway](https://github.com/notmarkopadjen/muxway): tmux panes on a MIDP 1.0 or 2.0 phone.

> Early development: the build works, the screens are next.

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
