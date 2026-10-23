#!/usr/bin/env bash
# Builds the app twice from one source:
#   muxway.jar / .jad    MIDP 2.0, CLDC 1.1
#   muxway1.jar / .jad   MIDP 1.0, CLDC 1.0, for the smallest phones
# then checks that the MIDP 1.0 build calls nothing newer and fits in 30 KiB.
# Tools are downloaded once into lib/ and verified by SHA-256.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(cat version)
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}
MAVEN=https://repo1.maven.org/maven2/org/microemu
PROGUARD_ZIP=https://github.com/Guardsquare/proguard/releases/download/v7.4.2/proguard-7.4.2.zip
MAX_JAR=30720

fetch() { # url file sha256
    if [ ! -f "lib/$2" ]; then
        curl -sfL -o "lib/$2.tmp" "$1"
        mv "lib/$2.tmp" "lib/$2"
    fi
    echo "$3  lib/$2" | sha256sum -c --quiet - || { echo "checksum mismatch: lib/$2" >&2; rm -f "lib/$2"; exit 1; }
}
mkdir -p lib build out
fetch $MAVEN/cldcapi10/2.0.3/cldcapi10-2.0.3.jar cldc10.jar 80bfffd50bfc991f10ce6bde692cbc71c2faf37ea282faf61c83eada7d64ffd3
fetch $MAVEN/cldcapi11/2.0.3/cldcapi11-2.0.3.jar cldc11.jar c98e74432dbb035a07d75fdd0515bcc1352933e6b3df401dd536de148c935497
fetch $MAVEN/midpapi20/2.0.3/midpapi20-2.0.3.jar midp20.jar 62267c1c2127bb0447d999c1e64ec493b17c4baace1017bd80bed208aaae1e25
if [ ! -f lib/proguard.jar ]; then
    curl -sfL -o build/proguard.zip "$PROGUARD_ZIP"
    unzip -q -j -o build/proguard.zip proguard-7.4.2/lib/proguard.jar -d lib
fi
echo "cf7231e5501da015d400b55e6a3bea42548509840865b62f3baec1dad1a3d8bb  lib/proguard.jar" | sha256sum -c --quiet - \
    || { echo "checksum mismatch: lib/proguard.jar" >&2; rm -f lib/proguard.jar; exit 1; }

# build <name> <profile> <manifest> <api jars>
build() {
    local name=$1 profile=$2 manifest=$3 api=$4 work=build/$1
    rm -rf "$work"
    mkdir -p "$work/classes" "$work/jar"
    # Java 1.1 class files: what the phones' KVM loads.
    "$JAVA_HOME/bin/javac" -nowarn -source 1.3 -target 1.1 -bootclasspath "$api" \
        -d "$work/classes" src/muxway/*.java "profiles/$profile/muxway/"*.java
    # ProGuard preverifies for CLDC and drops what is unused.
    "$JAVA_HOME/bin/java" -jar lib/proguard.jar -injars "$work/classes" -outjars "$work/jar" \
        -libraryjars "$api" -microedition -dontoptimize -dontobfuscate -dontnote -dontwarn \
        -keep 'public class muxway.Muxway' >/dev/null
    cp res/*.png "$work/jar/"
    sed "s/@VERSION@/$VERSION/" "manifest/$manifest" > "$work/MANIFEST.MF"
    "$JAVA_HOME/bin/jar" cfm "out/$name.jar" "$work/MANIFEST.MF" -C "$work/jar" .
    {
        sed "s/@VERSION@/$VERSION/" "manifest/$manifest"
        echo "MIDlet-Jar-URL: $name.jar"
        echo "MIDlet-Jar-Size: $(stat -c %s "out/$name.jar")"
    } > "out/$name.jad"
}

build muxway midp2 midp2.in lib/cldc11.jar:lib/midp20.jar
# No MIDP 1.0 stub is published, so this build compiles against CLDC 1.0 and
# the MIDP 2.0 stubs; check-midp1.sh then refuses anything 1.0 lacks.
build muxway1 midp1 midp1.in lib/cldc10.jar:lib/midp20.jar
scripts/check-midp1.sh out/muxway1.jar
size=$(stat -c %s out/muxway1.jar)
if [ "$size" -gt "$MAX_JAR" ]; then
    echo "out/muxway1.jar is $size bytes, over the $MAX_JAR limit of the smallest phones" >&2
    exit 1
fi
ls -l out/
