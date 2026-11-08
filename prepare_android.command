#!/bin/bash
# Copies the project out of Dropbox to ~/Observability-android, where Android Studio and Gradle build it.
# Gradle cannot copy files out of Dropbox's macOS File Provider folder (~/Library/CloudStorage/...):
# resource packaging fails with "Operation not permitted". Same approach as the aviation app's android:prepare.
#
# Edit the project in Dropbox (this folder) and run this again to refresh the copy; build/ and Android Studio
# settings (.idea, .gradle) in the copy are kept. The copy is also the folder that GitHub Desktop publishes:
# its .git/ folder (the GitHub history) is kept too, and release APKs stay out of it.

set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/Observability-android"

if [ "$SRC" = "$DEST" ]; then
    echo "This is already the build copy ($DEST); nothing to do."
    exit 0
fi

mkdir -p "$DEST"
# Keep the copy's GitHub history (.git) out of rsync's way, whatever rsync version macOS has, and put it back.
GITKEEP="$HOME/.Observability-android-git-keep"
if [ -d "$DEST/.git" ]; then
    rm -rf "$GITKEEP"
    mv "$DEST/.git" "$GITKEEP"
fi
trap 'if [ -d "$GITKEEP" ] && [ ! -e "$DEST/.git" ]; then mv "$GITKEEP" "$DEST/.git"; fi' EXIT
rsync -a --delete \
    --exclude '.gradle/' --exclude '.idea/' --exclude 'build/' --exclude '.kotlin/' \
    --exclude '*.log' --exclude '.DS_Store' --exclude 'captures/' \
    --exclude '.git/' --exclude 'release-apk/' --exclude '/.github/' --exclude '/github_forms/' \
    "$SRC/" "$DEST/"
# GitHub reads the issue forms from .github/ISSUE_TEMPLATE; they are kept in github_forms/ in Dropbox.
mkdir -p "$DEST/.github/ISSUE_TEMPLATE"
rsync -a --delete --exclude 'README.md' "$SRC/github_forms/" "$DEST/.github/ISSUE_TEMPLATE/"
chmod +x "$DEST/gradlew" "$DEST"/*.command
echo "ObsStarGet: project copied to $DEST"
echo "Next step: open Android Studio, choose 'Open' and select the folder:"
echo "  $DEST"
echo "With the phone connected by USB (USB debugging on): Run button (▶)."
echo "The test APK is left in: $DEST/app/build/outputs/apk/debug/app-debug.apk"
