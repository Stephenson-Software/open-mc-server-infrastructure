#!/bin/sh
# Prints the java binary to use for the given Minecraft version, both to build it with
# BuildTools and to run the server.
#
# Each Minecraft version accepts only a range of JDKs. BuildTools refuses to build 1.19.4 on
# Java 25 ("requires Java versions between [Java 17, Java 20]"), and the 1.19.4 server itself
# exits at start with "Unsupported Java detected (69.0). Only up to Java 20 is supported." So
# one JDK cannot serve both old and current versions:
#   1.17 – 1.20.4  → 17
#   1.20.5 – 1.21.x → 21
#   26.x and later  → 25 (the image's default java)
# Versions before 1.17 need Java 8–16, which the image does not carry: they are refused here
# with a message rather than left to fail inside BuildTools or the server.
#
# Usage: java-for-minecraft.sh [--major] <minecraft-version>
#   --major  print the JDK major version (17, 21, 25) instead of a binary; needs no JDK
#            installed, so the Dockerfile can decide what to install from it.
set -eu

major_only=false
if [ "${1:-}" = "--major" ]; then
    major_only=true
    shift
fi
version="${1:?usage: java-for-minecraft.sh [--major] <minecraft-version>}"
# Overridable so scripts/test-java-for-minecraft.sh can point it at a fixture directory.
jvm_dir="${JVM_DIR:-/usr/lib/jvm}"

case "$version" in
    1.*)
        minor=$(echo "$version" | cut -d. -f2)
        patch=$(echo "$version" | cut -d. -f3)
        patch="${patch:-0}"
        for part in "$minor" "$patch"; do
            case "$part" in
                ''|*[!0-9]*)
                    echo "java-for-minecraft.sh: cannot parse Minecraft version '$version'" >&2
                    exit 1
                    ;;
            esac
        done
        if [ "$minor" -lt 17 ]; then
            echo "java-for-minecraft.sh: Minecraft $version needs a JDK older than 17, which this image does not carry" >&2
            exit 1
        elif [ "$minor" -lt 20 ] || { [ "$minor" -eq 20 ] && [ "$patch" -lt 5 ]; }; then
            major=17
        else
            major=21
        fi
        ;;
    *)
        major=25
        ;;
esac

if [ "$major_only" = true ]; then
    echo "$major"
    exit 0
fi

if [ "$major" = 25 ]; then
    command -v java
    exit 0
fi

# The JDK directory name carries the architecture (amd64, arm64), so match it.
for dir in "$jvm_dir"/java-"$major"-openjdk-*; do
    if [ -x "$dir/bin/java" ]; then
        echo "$dir/bin/java"
        exit 0
    fi
done
echo "java-for-minecraft.sh: JDK $major is not installed in this image" >&2
exit 1
