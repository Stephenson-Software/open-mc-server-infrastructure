#!/bin/sh
# Prints the java binary BuildTools must run under to build the given Minecraft version.
#
# BuildTools refuses to build a version on a JDK newer than that version supports ("requires
# Java versions between [Java 17, Java 20], but you are using Java 25" for 1.19.4), so a
# single JDK cannot build both old and current versions. The builder stage installs 17, 21
# and 25; this picks the one each range accepts:
#   1.17 – 1.20.4  → 17
#   1.20.5 – 1.21.x → 21
#   26.x and later  → 25 (the image's default java)
# Versions before 1.17 need Java 8–16, which the image does not carry: they are refused here
# with a message rather than left to fail inside BuildTools.
set -eu

version="${1:?usage: buildtools-java.sh <minecraft-version>}"
# Overridable so scripts/test-buildtools-java.sh can point it at a fixture directory.
jvm_dir="${JVM_DIR:-/usr/lib/jvm}"

jdk() {
    # The JDK directory name carries the architecture (amd64, arm64), so match it.
    for dir in "$jvm_dir"/java-"$1"-openjdk-*; do
        if [ -x "$dir/bin/java" ]; then
            echo "$dir/bin/java"
            return 0
        fi
    done
    echo "buildtools-java.sh: JDK $1 is not installed in this image" >&2
    return 1
}

case "$version" in
    1.*)
        minor=$(echo "$version" | cut -d. -f2)
        patch=$(echo "$version" | cut -d. -f3)
        patch="${patch:-0}"
        case "$minor$patch" in
            *[!0-9]*)
                echo "buildtools-java.sh: cannot parse Minecraft version '$version'" >&2
                exit 1
                ;;
        esac
        if [ "$minor" -lt 17 ]; then
            echo "buildtools-java.sh: Minecraft $version needs a JDK older than 17, which this image does not carry" >&2
            exit 1
        elif [ "$minor" -lt 20 ] || { [ "$minor" -eq 20 ] && [ "$patch" -lt 5 ]; }; then
            jdk 17
        else
            jdk 21
        fi
        ;;
    *)
        command -v java
        ;;
esac
