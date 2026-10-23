#!/usr/bin/env bash
# Builds both apps and the muxway hub and agent, then runs the end-to-end
# suite into test-runs/<id>/: summary.md, results.xml, report/index.html,
# logs/<test>/, screens/<test>/<build>/.
#   MUXWAY_CORE  checkout of the core repository (default: ../muxway)
#   scripts/e2e.sh [test id ...]
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
for tool in tmux Xvfb xdotool import go python3; do
    command -v "$tool" >/dev/null || { echo "the end-to-end tests need $tool" >&2; exit 1; }
done
core=${MUXWAY_CORE:-../muxway}
[ -f "$core/go.mod" ] || { echo "no muxway core checkout at $core (set MUXWAY_CORE)" >&2; exit 1; }

scripts/build.sh >/dev/null
if [ ! -f lib/microemulator.jar ]; then
    curl -sfL -o lib/microemulator.jar.tmp https://repo1.maven.org/maven2/org/microemu/microemulator/2.0.4/microemulator-2.0.4.jar
    mv lib/microemulator.jar.tmp lib/microemulator.jar
fi
echo "dbd5f3eb8365d3e839d6a203149e0e3776fc1a0585e16ac1fc23f76c9fcae1c6  lib/microemulator.jar" | sha256sum -c --quiet - \
    || { echo "checksum mismatch: lib/microemulator.jar" >&2; rm -f lib/microemulator.jar; exit 1; }

id="$(date -u +%Y%m%d-%H%M%S)-$(git rev-parse --short HEAD)"
run="$PWD/test-runs/$id"
mkdir -p "$run"
(cd "$core" && CGO_ENABLED=0 go build -o "$run/muxway" ./cmd/muxway)
{ echo "core $(git -C "$core" rev-parse --short HEAD)"; tmux -V; "${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}/bin/java" -version 2>&1 | head -1; uname -sm; } > "$run/environment.txt"
scripts/check-midp1.sh out/muxway1.jar > "$run/build-check.txt"
echo "muxway1.jar $(stat -c %s out/muxway1.jar) bytes, muxway.jar $(stat -c %s out/muxway.jar) bytes" >> "$run/build-check.txt"

status=0
python3 -I tests/e2e/run.py "$run" "$run/muxway" "$@" || status=$?
rm -f "$run/muxway"
sed -n 3p "$run/summary.md"
echo "run directory: test-runs/$id (report: report/index.html)"
exit "$status"
