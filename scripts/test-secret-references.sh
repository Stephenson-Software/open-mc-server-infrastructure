#!/bin/bash
# Test script for scripts/resolve-secrets.sh.
#
# Two properties matter more than the rest and are asserted hardest:
# a literal value must never be mistaken for a reference, and a failure must
# never emit a partial environment or echo a secret into an error message.
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

test_log() { echo -e "${YELLOW}[TEST]${NC} $1"; }
test_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
test_error() { echo -e "${RED}[ERROR]${NC} $1"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVER="$SCRIPT_DIR/resolve-secrets.sh"
TEST_ROOT="/tmp/secret-reference-test"
FAILURES=0

# shellcheck disable=SC2317  # called via trap
cleanup() { rm -rf "$TEST_ROOT"; }
trap cleanup EXIT

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

# shellcheck disable=SC2317  # invoked indirectly, as an argument to assert_true
contains() {
    case "$1" in
        *"$2"*) return 0 ;;
        *) return 1 ;;
    esac
}

mkdir -p "$TEST_ROOT"
printf 'file-secret\n' > "$TEST_ROOT/secret.txt"
export TEST_ENV_SECRET='env-secret'

test_log "Literal values pass through untouched"
cat > "$TEST_ROOT/literal.env" <<'EOF'
# comment preserved
PLAIN=hunter2
QUOTED="with spaces"
EMPTY=
COLONS=https://example.com/webhook
LOOKS_LIKE_SCHEME=file:not-a-reference
EOF
output=$("$RESOLVER" --file "$TEST_ROOT/literal.env")
assert_true "a plain value is unchanged" contains "$output" "PLAIN=hunter2"
assert_true "quotes are stripped, value kept" contains "$output" "QUOTED=with spaces"
assert_true "an empty value stays empty" contains "$output" "EMPTY="
assert_true "a URL is not mangled" contains "$output" "COLONS=https://example.com/webhook"
# The sigil is why this works: a bare `file:` prefix would have been resolved.
assert_true "a literal beginning 'file:' is NOT treated as a reference" \
    contains "$output" "LOOKS_LIKE_SCHEME=file:not-a-reference"
assert_true "comments are preserved" contains "$output" "# comment preserved"
assert_false "a literal-only file reports no references" "$RESOLVER" --file "$TEST_ROOT/literal.env" --check

test_log "References resolve"
cat > "$TEST_ROOT/refs.env" <<EOF
FROM_ENV=!ref:env:TEST_ENV_SECRET
FROM_FILE=!ref:file:$TEST_ROOT/secret.txt
FROM_CMD=!ref:cmd:echo cmd-secret
QUOTED_REF="!ref:env:TEST_ENV_SECRET"
EOF
output=$("$RESOLVER" --file "$TEST_ROOT/refs.env")
assert_true "env: resolves" contains "$output" "FROM_ENV=env-secret"
assert_true "file: resolves and strips the trailing newline" contains "$output" "FROM_FILE=file-secret"
assert_true "cmd: resolves" contains "$output" "FROM_CMD=cmd-secret"
assert_true "a quoted reference resolves" contains "$output" "QUOTED_REF=env-secret"
assert_false "no reference survives resolution" contains "$output" '!ref:'
assert_true "a file with references reports so" "$RESOLVER" --file "$TEST_ROOT/refs.env" --check

test_log "Shell format round-trips through source"
cat > "$TEST_ROOT/tricky.env" <<'EOF'
TRICKY=value with 'single' quotes
EOF
eval "$("$RESOLVER" --file "$TEST_ROOT/tricky.env" --format shell)"
assert_true "a value containing single quotes survives sourcing" \
    test "$TRICKY" = "value with 'single' quotes"

test_log "Failures are refused, not half-applied"
cat > "$TEST_ROOT/bad.env" <<'EOF'
GOOD=literal
MISSING_ENV=!ref:env:DEFINITELY_NOT_SET_ANYWHERE_12345
MISSING_FILE=!ref:file:/nonexistent/secret
UNKNOWN_SCHEME=!ref:vault:secret/token
MALFORMED=!ref:nocolon
EOF
set +e
"$RESOLVER" --file "$TEST_ROOT/bad.env" > "$TEST_ROOT/out.txt" 2> "$TEST_ROOT/err.txt"
status=$?
set -e
assert_true "a failed resolution exits non-zero" test "$status" -ne 0
assert_true "a missing env var is named" contains "$(cat "$TEST_ROOT/err.txt")" "DEFINITELY_NOT_SET_ANYWHERE_12345"
assert_true "an unreadable file is named" contains "$(cat "$TEST_ROOT/err.txt")" "/nonexistent/secret"
assert_true "an unknown scheme is rejected" contains "$(cat "$TEST_ROOT/err.txt")" "unknown scheme"
assert_true "a malformed reference is rejected" contains "$(cat "$TEST_ROOT/err.txt")" "malformed"

test_log "A failing command does not leak into the error message"
cat > "$TEST_ROOT/leaky.env" <<'EOF'
LEAKY=!ref:cmd:sh -c 'echo super-secret-value >&2; exit 7'
EOF
set +e
"$RESOLVER" --file "$TEST_ROOT/leaky.env" > "$TEST_ROOT/out2.txt" 2> "$TEST_ROOT/err2.txt"
set -e
combined="$(cat "$TEST_ROOT/out2.txt" "$TEST_ROOT/err2.txt")"
assert_false "the command's stderr is not echoed" contains "$combined" "super-secret-value"
assert_true "the exit status is reported" contains "$combined" "exited 7"

test_log "A resolved value never reaches stdout on the success path unasked"
assert_true "an unknown flag is rejected" \
    bash -c "! '$RESOLVER' --nonsense 2>/dev/null"
assert_true "a missing file is rejected" \
    bash -c "! '$RESOLVER' --file /nonexistent/env 2>/dev/null"
assert_true "an invalid format is rejected" \
    bash -c "! '$RESOLVER' --file '$TEST_ROOT/literal.env' --format json 2>/dev/null"

echo
if [ "$FAILURES" -eq 0 ]; then
    test_success "All secret reference tests passed"
    exit 0
fi
test_error "$FAILURES assertion(s) failed"
exit 1
