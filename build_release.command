#!/bin/bash
# Builds the signed release APK of ObsStarGet, the file you attach to a release on GitHub.
# Double-click in Finder. Terminal asks for the password of the release key (made once by make_release_key.command).
#
# What it does: refreshes the build copy in ~/Observability-android, runs the unit tests, builds the release APK
# signed with your key, checks the signature, and copies the APK to release-apk/ObsStarGet-<version>.apk in this
# folder. The full output (never the password) is written to build_release.log next to this script.

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/Observability-android"
LOG="$SRC/build_release.log"
KS="$HOME/ObsStarGet-signing/obsstarget-release.jks"
OUT="$SRC/release-apk"
VERSION="$(sed -n -E 's/^ *versionName = "([^"]+)".*/\1/p' "$SRC/app/build.gradle.kts" | head -1)"
FINAL="$OUT/ObsStarGet-$VERSION.apk"

echo "=== ObsStarGet $VERSION: release build ==="
if grep -q "YOUR-GITHUB-USERNAME" "$SRC/app/src/main/java/cl/astro/observability/MainActivity.kt" "$SRC/README.md"; then
    echo "The project's GitHub address is not filled in yet (used by the Feedback button)."
    echo "Tell Claude your GitHub user name first."
    exit 1
fi
if [ ! -f "$KS" ]; then
    echo "There is no release key yet. Double-click make_release_key.command first."
    exit 1
fi
read -r -s -p "Password of the release key (nothing is shown while you type): " OBS_KEY_PASSWORD
echo
if [ -z "$OBS_KEY_PASSWORD" ]; then
    echo "No password given; nothing was built."
    exit 1
fi
export OBS_KEY_PASSWORD
rm -f "$FINAL"

{
    echo "=== ObsStarGet $VERSION release build, $(date) ==="
    if [ "$SRC" != "$DEST" ]; then
        bash "$SRC/prepare_android.command" | head -1
    fi
    cd "$DEST" || { echo "cannot enter $DEST"; echo "=== done ==="; exit 1; }
    source tools/java_home.sh
    if ! JAVA_HOME="$(pick_java_home)"; then
        echo "No Java 21 or 17 found. In Android Studio: Settings > Build, Execution, Deployment > Build Tools > Gradle >"
        echo "Gradle JDK > Download JDK... > version 21, then run this again."
        echo "=== done ==="
        exit 1
    fi
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    echo "Building in: $DEST"
    echo "JAVA_HOME=$JAVA_HOME"

    APK="$DEST/app/build/outputs/apk/release/app-release.apk"
    rm -f "$APK"
    ./gradlew --console=plain --no-daemon testReleaseUnitTest assembleRelease
    status=$?
    echo
    echo "=== gradle exit code: $status ==="
    if [ -d "$DEST/app/build/test-results/testReleaseUnitTest" ]; then
        echo "=== unit test results ==="
        grep -h -o '<testsuite [^>]*' "$DEST/app/build/test-results/testReleaseUnitTest/"*.xml 2>/dev/null
    fi
    if [ "$status" -ne 0 ] || [ ! -f "$APK" ]; then
        echo
        echo "The release APK was NOT made. If the messages above mention 'password was incorrect',"
        echo "run this again and check the password. Otherwise tell Claude: the details are in build_release.log."
        echo "=== done ==="
        exit 1
    fi

    echo "=== signature check ==="
    APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
    if [ -n "$APKSIGNER" ]; then
        "$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1 | grep -E "Verified using|Signer #1 certificate (DN|SHA-256)|Number of signers|WARNING|ERROR|DOES NOT VERIFY"
        if ! "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -q "CN=Gaspar Galaz"; then
            echo "The APK is not signed with the release key; it was not copied."
            echo "=== done ==="
            exit 1
        fi
    else
        echo "(apksigner not found in $ANDROID_HOME/build-tools; signature not checked)"
    fi

    mkdir -p "$OUT"
    cp "$APK" "$FINAL"
    echo
    echo "=== release APK ==="
    ls -la "$FINAL"
    echo "SHA-256: $(shasum -a 256 "$FINAL" | cut -d' ' -f1)"
    echo "=== done ==="
} 2>&1 | tee "$LOG"

unset OBS_KEY_PASSWORD
if [ -f "$FINAL" ]; then
    echo
    echo "Ready: $FINAL"
    echo "This is the file to attach to the GitHub release."
    open "$OUT"
fi
