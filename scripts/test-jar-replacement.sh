#!/bin/bash
# Test script for setup_server()'s server JAR replacement.
#
# The behaviour under test is that the existing server JAR is never removed
# until a verified replacement is in place. A version/image mismatch must leave
# the server directory exactly as it was, because the alternative is a world
# with no JAR to run it.
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

test_log() { echo -e "${YELLOW}[TEST]${NC} $1"; }
test_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
test_error() { echo -e "${RED}[ERROR]${NC} $1"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POST_CREATE="$SCRIPT_DIR/../resources/post-create.sh"
TEST_ROOT="/tmp/jar-replacement-test"
FAILURES=0

# shellcheck disable=SC2317  # called via trap
cleanup() { rm -rf "$TEST_ROOT"; }
trap cleanup EXIT

# --- assertions -------------------------------------------------------------
# Conditions are passed as commands rather than as strings to eval, so the
# script stays readable to shellcheck and a typo in a condition is a command
# that fails loudly rather than one that silently evaluates false.

assert_true() {
    local description="$1"; shift
    if "$@"; then
        test_success "$description"
    else
        test_error "$description"
        FAILURES=$((FAILURES + 1))
    fi
}

assert_false() {
    local description="$1"; shift
    if "$@"; then
        test_error "$description"
        FAILURES=$((FAILURES + 1))
    else
        test_success "$description"
    fi
}

contains() {
    case "$1" in
        *"$2"*) return 0 ;;
        *) return 1 ;;
    esac
}

no_staged_files() {
    [ -z "$(find "$TEST_ROOT/server" -name '.incoming-*' 2>/dev/null)" ]
}

# --- harness ----------------------------------------------------------------

# setup_server() is defined inside a script that starts a server at the bottom,
# so the function is extracted rather than the file sourced.
extract_setup_server() {
    local extracted="$TEST_ROOT/setup_server.sh"
    sed -n '/^setup_server() {/,/^}/p' "$POST_CREATE" > "$extracted"
    if [ ! -s "$extracted" ]; then
        test_error "could not extract setup_server() from $POST_CREATE"
        exit 1
    fi
    {
        # shellcheck disable=SC2016  # a literal function definition, not an expansion
        echo 'log() { echo "[SERVER-SETUP] $1"; }'
        cat "$extracted"
    } > "$extracted.harness"
    echo "$extracted.harness"
}

# run_case <image_version> <requested_version> <existing_jar_version|none>
# Prints the function's output followed by EXIT:<status>.
run_case() {
    local image_version="$1" requested_version="$2" existing="$3"
    rm -rf "$TEST_ROOT/build" "$TEST_ROOT/server"
    mkdir -p "$TEST_ROOT/build" "$TEST_ROOT/server"
    echo "spigot-jar-contents-$image_version" > "$TEST_ROOT/build/spigot-$image_version.jar"
    if [ "$existing" != "none" ]; then
        echo "spigot-jar-contents-$existing" > "$TEST_ROOT/server/spigot-$existing.jar"
        mkdir -p "$TEST_ROOT/server/world" "$TEST_ROOT/server/plugins"
        echo "world-data" > "$TEST_ROOT/server/world/level.dat"
    fi
    (
        set +e
        # These are read by the extracted function, not by this subshell.
        # shellcheck disable=SC2034
        SERVER_DIR="$TEST_ROOT/server"
        # shellcheck disable=SC2034
        BUILD_DIR="$TEST_ROOT/build"
        # shellcheck disable=SC2034
        MINECRAFT_VERSION="$requested_version"
        # shellcheck disable=SC2034
        OVERWRITE_EXISTING_SERVER="false"
        # shellcheck disable=SC1090
        source "$HARNESS"
        setup_server
        echo "EXIT:$?"
    )
}

mkdir -p "$TEST_ROOT"
HARNESS="$(extract_setup_server)"

# The destructive path needs BOTH a version the server directory does not have
# (so the upgrade branch is entered) AND a version the image does not carry (so
# the replacement is missing). That is what a MINECRAFT_VERSION bumped ahead of
# its image tag looks like.
test_log "Case 1: MINECRAFT_VERSION is ahead of the image (regression for #268)"
output=$(run_case "26.1" "26.2" "26.1")
assert_true "setup_server fails rather than proceeding" contains "$output" "EXIT:1"
assert_true "the existing server JAR is left in place" test -f "$TEST_ROOT/server/spigot-26.1.jar"
assert_true "the world is untouched" test -f "$TEST_ROOT/server/world/level.dat"
assert_true "the error names the missing version" contains "$output" "does not contain spigot-26.2.jar"
assert_true "the error lists what the image does contain" contains "$output" "spigot-26.1.jar"
assert_true "no staged file is left behind" no_staged_files

test_log "Case 2: a genuine version upgrade"
output=$(run_case "26.2" "26.2" "26.1")
assert_true "setup_server succeeds" contains "$output" "EXIT:0"
assert_true "the new JAR is in place" test -f "$TEST_ROOT/server/spigot-26.2.jar"
assert_false "the old JAR is removed" test -f "$TEST_ROOT/server/spigot-26.1.jar"
assert_true "the new JAR has the right contents" \
    grep -q "spigot-jar-contents-26.2" "$TEST_ROOT/server/spigot-26.2.jar"
assert_true "the world survives the upgrade" test -f "$TEST_ROOT/server/world/level.dat"
assert_true "no staged file is left behind" no_staged_files

test_log "Case 3: the running version already matches"
output=$(run_case "26.2" "26.2" "26.2")
assert_true "setup_server succeeds" contains "$output" "EXIT:0"
assert_true "it reports the JAR as up to date" contains "$output" "up to date"
assert_true "the JAR is still present" test -f "$TEST_ROOT/server/spigot-26.2.jar"

test_log "Case 4: a fresh server directory"
output=$(run_case "26.2" "26.2" "none")
assert_true "setup_server succeeds" contains "$output" "EXIT:0"
assert_true "the JAR is installed" test -f "$TEST_ROOT/server/spigot-26.2.jar"
assert_true "a plugins directory is created" test -d "$TEST_ROOT/server/plugins"

echo
if [ "$FAILURES" -eq 0 ]; then
    test_success "All JAR replacement tests passed"
    exit 0
fi
test_error "$FAILURES assertion(s) failed"
exit 1
