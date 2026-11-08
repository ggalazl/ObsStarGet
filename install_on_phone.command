#!/bin/bash
# Builds the development version of the app and installs it, as "ObsStarGet dev", on the Android phone connected
# by USB (USB debugging on).
# Double-click this file in Finder, or run it from Terminal. Android Studio does not need to be open.
# Run from the Dropbox folder, it first refreshes the build copy in ~/Observability-android.

set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/Observability-android"
if [ "$SRC" != "$DEST" ]; then
    bash "$SRC/prepare_android.command" >/dev/null
fi
cd "$DEST"
source tools/java_home.sh

# Gradle 8.14.3 needs Java 17-24: use a Java 21 (e.g. the jbr-21 that Android Studio downloaded).
if ! JAVA_HOME="$(pick_java_home)"; then
    echo "No Java 21 or 17 found. In Android Studio: Settings > Build, Execution, Deployment > Build Tools > Gradle >"
    echo "Gradle JDK > Download JDK... > version 21, then run this again."
    exit 1
fi
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
echo "Building in: $DEST"
echo "Using Java: $JAVA_HOME"

if [ ! -x "$ADB" ]; then
    echo "adb not found in $ANDROID_HOME/platform-tools. Install 'Android SDK Platform-Tools' from Android Studio's SDK Manager."
    exit 1
fi

echo "Connected devices:"
"$ADB" devices -l
if [ "$("$ADB" devices | grep -c -w device)" -eq 0 ]; then
    echo
    echo "No phone found. Connect it by USB, unlock it and accept 'Allow USB debugging', then run this again."
    exit 1
fi

./gradlew installDebug
# Development builds have their own id (".debug"), so they install next to the published app as "ObsStarGet dev".
"$ADB" shell am start -n cl.astro.observability.debug/cl.astro.observability.MainActivity
echo
echo "Done: ObsStarGet dev (the development build) is installed and started on the phone."
