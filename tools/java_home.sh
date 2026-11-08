# Sourced by test_build.command and install_on_phone.command.
# Picks a Java 21 (or 17) runtime for Gradle 8.14.3, which cannot run on Java 25 (the Java bundled with recent
# Android Studio versions). Looks in the places where macOS, Android Studio and IntelliJ keep JDKs.

java_major() {   # prints the major version of the java in $1/bin/java, e.g. 21
    "$1/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/'
}

pick_java_home() {
    local h v
    for v in 21 17; do
        h=$(/usr/libexec/java_home -v "$v" 2>/dev/null)
        if [ -n "$h" ] && [ -x "$h/bin/java" ] && [ "$(java_major "$h")" = "$v" ]; then echo "$h"; return 0; fi
    done
    for h in "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home /Library/Java/JavaVirtualMachines/*/Contents/Home \
             "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if [ -x "$h/bin/java" ]; then
            v=$(java_major "$h")
            if [ "$v" = "21" ] || [ "$v" = "17" ]; then echo "$h"; return 0; fi
        fi
    done
    return 1
}
