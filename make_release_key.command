#!/bin/bash
# Creates, ONCE, the release key that signs the ObsStarGet APKs you publish on GitHub.
# Double-click in Finder. Terminal asks you for a password (twice); nothing is shown while you type.
#
# The key is saved in ~/ObsStarGet-signing/obsstarget-release.jks, outside Dropbox and outside the project,
# so it is never uploaded to GitHub. Keep the key AND its password safe for as long as you publish the app:
# every update must be signed with this same key, otherwise colleagues cannot install it over the old version.

SRC="$(cd "$(dirname "$0")" && pwd)"
DIR="$HOME/ObsStarGet-signing"
KS="$DIR/obsstarget-release.jks"
ALIAS="obsstarget"

echo "=== ObsStarGet: release key ==="
if [ -f "$KS" ]; then
    echo "A release key already exists, so nothing was changed:"
    echo "  $KS"
    echo "(Never make a second key: updates must be signed with this one.)"
    open "$DIR"
    exit 0
fi

source "$SRC/tools/java_home.sh"
if ! JAVA_HOME="$(pick_java_home)"; then
    echo "No Java 21 or 17 found (it is needed for the 'keytool' program)."
    echo "In Android Studio: Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK >"
    echo "Download JDK... > version 21, then run this again."
    exit 1
fi
KEYTOOL="$JAVA_HOME/bin/keytool"

mkdir -p "$DIR"
chmod 700 "$DIR"
echo
echo "Choose a password for the key: at least 8 characters. Write it down somewhere safe"
echo "(for example in the Passwords app). You will type it each time you build a release."
echo "Nothing appears on the screen while you type; press Return at the end."
echo
if ! "$KEYTOOL" -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" \
        -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Gaspar Galaz, C=CL"; then
    echo
    echo "The key was not created (password too short, or the two entries differ?). Run this again."
    rm -f "$KS"
    exit 1
fi
chmod 600 "$KS"

echo
echo "Done. The release key is:"
echo "  $KS"
echo
echo "Keep a backup copy of this file (USB stick, or a private folder of your own) together with the password."
echo "Do not put it in the project folder and do not send it to anyone."
open "$DIR"
