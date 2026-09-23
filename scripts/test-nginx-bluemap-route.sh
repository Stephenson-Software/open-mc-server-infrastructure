#!/bin/bash
# Test script to verify the optional BlueMap proxy route, and the upload size
# limit and timeouts the entrypoint substitutes into nginx.conf, on Docker
# Compose.
#
# No other check builds or runs the nginx image — "Validate Code and
# Configuration" only runs shellcheck and `docker compose config`, and "Test
# Server Run" starts alert-manager and minecraft-wrapper alone — so nothing else
# catches a broken include, a fragment that fails to render, or a route that
# survives being turned off. This script covers that gap. It is run in CI by the
# "nginx Configuration Test" job (.github/workflows/ci.yml) and locally by
# scripts/ci-local.sh whenever a Docker daemon is reachable; run it directly
# after touching nginx/nginx.conf, nginx/entrypoint.sh or nginx/Dockerfile.
#
# The Kubernetes equivalent of these assertions lives in
# helm/omcsi/tests/nginx_test.yaml and runs under `helm unittest`.
set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

IMAGE="test-omcsi-nginx-bluemap"

TESTS=0
PASSED=0

test_log() {
    echo -e "${YELLOW}[TEST]${NC} $1"
}

test_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

test_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Cleanup function
# shellcheck disable=SC2317  # Function called via signal trap
cleanup() {
    test_log "Cleaning up test environment..."
    docker image rm -f "$IMAGE" > /dev/null 2>&1 || true
    test_log "Cleanup completed"
}

trap cleanup EXIT

# Assert a string is present in the rendered config.
assert_contains() {
    local haystack="$1" needle="$2" description="$3"
    TESTS=$((TESTS + 1))
    if echo "$haystack" | grep -qF -- "$needle"; then
        test_success "✓ $description"
        PASSED=$((PASSED + 1))
    else
        test_error "✗ $description (expected to find: $needle)"
    fi
}

# Assert a string is absent from the rendered config.
assert_absent() {
    local haystack="$1" needle="$2" description="$3"
    TESTS=$((TESTS + 1))
    if echo "$haystack" | grep -qF -- "$needle"; then
        test_error "✗ $description (unexpectedly found: $needle)"
    else
        test_success "✓ $description"
        PASSED=$((PASSED + 1))
    fi
}

# Dump the configuration nginx actually resolves, includes and all.
#
# Runs as root because `nginx -T` aborts before printing the dump if it cannot
# create /var/cache/nginx/client_temp, which UID 1000 cannot. That failure is
# unrelated to the route under test and predates it.
render_config() {
    docker run --rm --user 0 \
        --add-host webapp:127.0.0.1 \
        --add-host minecraft-wrapper:127.0.0.1 \
        "$@" "$IMAGE" nginx -T 2>/dev/null
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

test_log "🚀 Starting nginx configuration test..."

test_log "Building nginx image..."
if ! docker build -t "$IMAGE" "$REPO_ROOT/nginx" > /dev/null 2>&1; then
    test_error "Failed to build nginx image"
    exit 1
fi
test_success "nginx image built successfully"

# --- Disabled by default -----------------------------------------------------
test_log "Checking the default (BlueMap disabled)..."
DISABLED_CONF="$(render_config)"

if [ -z "$DISABLED_CONF" ]; then
    test_error "nginx rejected its configuration with BlueMap disabled"
    exit 1
fi

assert_contains "$DISABLED_CONF" "include /etc/nginx/omcsi.d/*.conf;" \
    "The fragment include is present in nginx.conf"
assert_absent "$DISABLED_CONF" "location /map/" \
    "No BlueMap route is served when NGINX_BLUEMAP_ENABLED is unset"
assert_contains "$DISABLED_CONF" "location / {" \
    "The dashboard catch-all route is still served"

# --- Enabled at the default path ---------------------------------------------
test_log "Checking BlueMap enabled at the default path..."
ENABLED_CONF="$(render_config -e NGINX_BLUEMAP_ENABLED=true)"

if [ -z "$ENABLED_CONF" ]; then
    test_error "nginx rejected its configuration with BlueMap enabled"
    exit 1
fi

assert_contains "$ENABLED_CONF" "/etc/nginx/omcsi.d/bluemap.conf" \
    "nginx loaded the generated fragment"
assert_contains "$ENABLED_CONF" "location /map/" \
    "The BlueMap route is served at the default path"
# The trailing slash maps the configured path onto BlueMap's own root; without
# it every asset path is prefixed twice and 404s.
assert_contains "$ENABLED_CONF" "proxy_pass http://minecraft-wrapper:8100/;" \
    "The route proxies to the wrapper's BlueMap port with a trailing slash"
# These must reach nginx literally — the entrypoint's heredoc is quoted so the
# shell does not expand them while writing the fragment.
# shellcheck disable=SC2016  # The literal, unexpanded $host is what is asserted
assert_contains "$ENABLED_CONF" 'proxy_set_header Host $host;' \
    "nginx variables survived fragment generation unexpanded"

# --- Enabled at a custom path ------------------------------------------------
test_log "Checking a custom BlueMap path..."
CUSTOM_CONF="$(render_config -e NGINX_BLUEMAP_ENABLED=true -e NGINX_BLUEMAP_PATH=/bluemap/)"

assert_contains "$CUSTOM_CONF" "location /bluemap/" \
    "NGINX_BLUEMAP_PATH overrides the default path"
assert_absent "$CUSTOM_CONF" "location /map/" \
    "The default path is not served when overridden"

# --- Stale fragment cleanup --------------------------------------------------
# The fragment lives in the container's writable layer, so turning the flag off
# must remove it rather than leave the route behind across a restart.
test_log "Checking that disabling the route removes a previously written fragment..."
# Both states are reported so the "removed" assertion cannot pass vacuously on a
# tree where the fragment was never written in the first place.
CLEANUP_OUT="$(docker run --rm -e NGINX_BLUEMAP_ENABLED=true "$IMAGE" bash -c '
    echo "ENABLED:[$(ls -A /etc/nginx/omcsi.d/ 2>/dev/null)]"
    NGINX_BLUEMAP_ENABLED=false /entrypoint.sh true > /dev/null
    echo "DISABLED:[$(ls -A /etc/nginx/omcsi.d/ 2>/dev/null)]"
' 2>/dev/null)"

assert_contains "$CLEANUP_OUT" "ENABLED:[bluemap.conf]" \
    "The fragment is written while NGINX_BLUEMAP_ENABLED is true"
assert_contains "$CLEANUP_OUT" "DISABLED:[]" \
    "The fragment is removed when NGINX_BLUEMAP_ENABLED is turned back off"

# --- Upload size limit and timeouts ------------------------------------------
# Compose-only: on Kubernetes nginx.conf is a read-only ConfigMap the chart has
# already rendered, so the entrypoint skips these substitutions there. Overridden
# values are used because the entrypoint's defaults (100M, 3600s) match what is
# baked into nginx.conf, so asserting a default would pass even if no
# substitution ran.
test_log "Checking the upload size limit and timeout substitutions..."
LIMITS_CONF="$(render_config -e NGINX_MAX_BODY_SIZE=512M -e NGINX_UPLOAD_TIMEOUT=1800s)"

if [ -z "$LIMITS_CONF" ]; then
    test_error "nginx rejected its configuration with overridden upload limits"
    exit 1
fi

assert_contains "$LIMITS_CONF" "client_max_body_size 512M;" \
    "NGINX_MAX_BODY_SIZE is substituted into client_max_body_size"
assert_absent "$LIMITS_CONF" "client_max_body_size 100M;" \
    "The baked-in upload size limit is replaced"
assert_contains "$LIMITS_CONF" "proxy_send_timeout 1800s; # upload" \
    "NGINX_UPLOAD_TIMEOUT is substituted into the upload route's send timeout"
assert_contains "$LIMITS_CONF" "proxy_read_timeout 1800s; # upload" \
    "NGINX_UPLOAD_TIMEOUT is substituted into the upload route's read timeout"
# Negative controls: a substitution that rewrote every proxy timeout would still
# pass the two assertions above.
assert_contains "$LIMITS_CONF" "proxy_send_timeout 60s;" \
    "The dashboard's own send timeout is left alone"
assert_contains "$LIMITS_CONF" "proxy_read_timeout 60s;" \
    "The dashboard's own read timeout is left alone"

# --- Results -----------------------------------------------------------------
test_log "📊 Test Results:"
test_log "nginx configuration tests passed: $PASSED/$TESTS"

if [ "$PASSED" -eq "$TESTS" ]; then
    test_success "🎉 All nginx configuration tests passed!"
    exit 0
else
    test_error "❌ Some nginx configuration tests failed"
    exit 1
fi
