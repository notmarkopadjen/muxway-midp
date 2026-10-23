#!/usr/bin/env bash
# Fails if a JAR calls a Java ME member that MIDP 1.0 / CLDC 1.0 lacks.
# The build compiles the MIDP 1.0 variant against MIDP 2.0 stubs (there is no
# published 1.0 stub), so this list is what keeps it honest.
set -euo pipefail
jar=${1:?jar}
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
(cd "$tmp" && "$JAVA_HOME/bin/jar" xf "$OLDPWD/$jar")
refs=$(find "$tmp" -name '*.class' -print0 | xargs -0 "$JAVA_HOME/bin/javap" -c -p 2>/dev/null \
    | grep -oE '(Method|Field|class|InterfaceMethod) javax/microedition/[A-Za-z0-9_/$]+(\.[A-Za-z0-9_<>"]+)?' \
    | sed -E 's/^(Method|Field|class|InterfaceMethod) //' | sort -u)

# MIDP 2.0 additions, and members that moved up to Displayable in 2.0 (a call
# compiled against 2.0 binds there and fails on a 1.0 phone).
forbidden='
javax/microedition/lcdui/game/
javax/microedition/lcdui/CustomItem
javax/microedition/lcdui/Spacer
javax/microedition/lcdui/ItemCommandListener
javax/microedition/media/
javax/microedition/pki/
javax/microedition/io/HttpsConnection
javax/microedition/io/SecureConnection
javax/microedition/io/SocketConnection
javax/microedition/io/ServerSocketConnection
javax/microedition/io/UDPDatagramConnection
javax/microedition/io/CommConnection
javax/microedition/io/PushRegistry
javax/microedition/lcdui/Displayable.setTitle
javax/microedition/lcdui/Displayable.getTitle
javax/microedition/lcdui/Displayable.setTicker
javax/microedition/lcdui/Displayable.getTicker
javax/microedition/lcdui/Displayable.getWidth
javax/microedition/lcdui/Displayable.getHeight
javax/microedition/lcdui/Displayable.sizeChanged
javax/microedition/lcdui/Display.setCurrentItem
javax/microedition/lcdui/Display.vibrate
javax/microedition/lcdui/Display.flashBacklight
javax/microedition/lcdui/Display.getColor
javax/microedition/lcdui/Display.getBestImageWidth
javax/microedition/lcdui/Form.deleteAll
javax/microedition/lcdui/List.deleteAll
javax/microedition/lcdui/List.setFont
javax/microedition/lcdui/List.setFitPolicy
javax/microedition/lcdui/ChoiceGroup.deleteAll
javax/microedition/lcdui/Item.setLayout
javax/microedition/lcdui/Item.setPreferredSize
javax/microedition/lcdui/Item.addCommand
javax/microedition/lcdui/Item.setDefaultCommand
javax/microedition/lcdui/StringItem.setFont
javax/microedition/lcdui/StringItem.getAppearanceMode
javax/microedition/lcdui/TextField.setInitialInputMode
javax/microedition/lcdui/TextBox.setInitialInputMode
javax/microedition/lcdui/Alert.setIndicator
javax/microedition/lcdui/Alert.addCommand
javax/microedition/lcdui/Alert.setCommandListener
javax/microedition/lcdui/Graphics.drawRegion
javax/microedition/lcdui/Graphics.drawRGB
javax/microedition/lcdui/Graphics.fillTriangle
javax/microedition/lcdui/Graphics.copyArea
javax/microedition/lcdui/Image.createRGBImage
javax/microedition/lcdui/Image.getRGB
javax/microedition/lcdui/Canvas.setFullScreenMode
javax/microedition/midlet/MIDlet.platformRequest
javax/microedition/midlet/MIDlet.checkPermission
'
bad=0
while read -r ref; do
    [ -z "$ref" ] && continue
    while read -r f; do
        [ -z "$f" ] && continue
        case "$ref" in "$f"*) echo "MIDP 1.0 lacks: $ref" >&2; bad=1 ;; esac
    done <<< "$forbidden"
done <<< "$refs"
[ "$bad" -eq 0 ] && echo "MIDP 1.0 check: $(echo "$refs" | grep -c .) references, all available"
exit "$bad"
