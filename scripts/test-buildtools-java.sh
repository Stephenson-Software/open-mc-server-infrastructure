#!/bin/bash
# Test script for resources/buildtools-java.sh, the builder stage's choice of JDK per
# Minecraft version. BuildTools refuses a JDK outside the range a version supports, so a
# wrong choice fails the image build for that version; this pins the boundaries (1.20.5,
# where 21 becomes required, and 26.x, which needs 25) without building Spigot.
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

test_log() { echo -e "${YELLOW}[TEST]${NC} $1"; }
test_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
test_error() { echo -e "${RED}[ERROR]${NC} $1"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PICK="$SCRIPT_DIR/../resources/buildtools-java.sh"
TEST_ROOT="$(mktemp -d)"
FAILURES=0

# shellcheck disable=SC2317  # called via trap
cleanup() { rm -rf "$TEST_ROOT"; }
trap cleanup EXIT

# A fake /usr/lib/jvm with 17 and 21 for one architecture, and a fake default `java` (25).
for v in 17 21; do
    mkdir -p "$TEST_ROOT/jvm/java-$v-openjdk-arm64/bin"
    printf '#!/bin/sh\n' > "$TEST_ROOT/jvm/java-$v-openjdk-arm64/bin/java"
    chmod +x "$TEST_ROOT/jvm/java-$v-openjdk-arm64/bin/java"
done
mkdir -p "$TEST_ROOT/bin"
printf '#!/bin/sh\n' > "$TEST_ROOT/bin/java"
chmod +x "$TEST_ROOT/bin/java"

JDK17="$TEST_ROOT/jvm/java-17-openjdk-arm64/bin/java"
JDK21="$TEST_ROOT/jvm/java-21-openjdk-arm64/bin/java"
JDK25="$TEST_ROOT/bin/java"

expect() {
    local version="$1" want="$2" got
    got=$(JVM_DIR="$TEST_ROOT/jvm" PATH="$TEST_ROOT/bin:$PATH" sh "$PICK" "$version" 2>/dev/null) || got="(refused)"
    if [ "$got" = "$want" ]; then
        test_success "$version -> ${got#"$TEST_ROOT"/}"
    else
        test_error "$version: expected ${want#"$TEST_ROOT"/}, got ${got#"$TEST_ROOT"/}"
        FAILURES=$((FAILURES + 1))
    fi
}

test_log "JDK chosen per Minecraft version"
expect 1.17 "$JDK17"
expect 1.17.1 "$JDK17"
expect 1.19.4 "$JDK17"
expect 1.20.4 "$JDK17"
expect 1.20.5 "$JDK21"
expect 1.20.6 "$JDK21"
expect 1.21 "$JDK21"
expect 1.21.11 "$JDK21"
expect 26.1 "$JDK25"
expect 26.2 "$JDK25"

test_log "Versions the image cannot build are refused"
expect 1.16.5 "(refused)"
expect 1.x "(refused)"

test_log "A missing JDK is reported, not replaced by another"
rm -rf "$TEST_ROOT/jvm/java-17-openjdk-arm64"
expect 1.19.4 "(refused)"

if [ "$FAILURES" -ne 0 ]; then
    test_error "$FAILURES check(s) failed"
    exit 1
fi
test_success "All buildtools-java.sh checks passed"
