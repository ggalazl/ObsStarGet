#!/bin/bash
# Test build without a phone: compiles the app (debug APK) and runs the unit tests.
# Double-click in Finder or run from Terminal. The full output is also written to test_build.log
# in the folder this script was started from. Run from the Dropbox folder, it first refreshes the build copy
# in ~/Observability-android (Gradle cannot build inside Dropbox's File Provider folder).

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/Observability-android"
LOG="$SRC/test_build.log"

{
    echo "=== ObsStarGet test build, $(date) ==="
    if [ "$SRC" != "$DEST" ]; then
        bash "$SRC/prepare_android.command" | head -1
    fi
    cd "$DEST" || { echo "cannot enter $DEST"; echo "=== done ==="; exit 1; }
    source tools/java_home.sh
    # Gradle 8.14.3 needs Java 17-24: use a Java 21 (e.g. the jbr-21 that Android Studio downloaded).
    if ! JAVA_HOME="$(pick_java_home)"; then
        echo "No Java 21 or 17 found. In Android Studio: Settings > Build, Execution, Deployment > Build Tools > Gradle >"
        echo "Gradle JDK > Download JDK... > version 21, then run this again."
        echo "=== done ==="
        exit 1
    fi
    export JAVA_HOME
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    echo "Building in: $DEST"
    echo "JAVA_HOME=$JAVA_HOME"
    "$JAVA_HOME/bin/java" -version 2>&1 | head -1
    ./gradlew --console=plain assembleDebug testDebugUnitTest
    status=$?
    echo
    echo "=== gradle exit code: $status ==="
    ls -la "$DEST/app/build/outputs/apk/debug/" 2>&1
    if [ -d "$DEST/app/build/test-results/testDebugUnitTest" ]; then
        echo "=== unit test results ==="
        grep -h -o '<testsuite [^>]*' "$DEST/app/build/test-results/testDebugUnitTest/"*.xml 2>/dev/null
    fi
    echo "=== done ==="
} 2>&1 | tee "$LOG"
