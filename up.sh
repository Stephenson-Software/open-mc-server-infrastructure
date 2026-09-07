#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "ERROR: .env not found — copy sample.env to .env and configure it." >&2
    exit 1
fi

# A .env made entirely of literal values is started exactly as before: no temp
# file, no resolution, nothing to go wrong. The extra step happens only when the
# file actually contains a `!ref:` value.
COMPOSE_ENV_ARGS=()
RESOLVED_ENV=""

# shellcheck disable=SC2317  # called via trap
cleanup() {
    # An `if` rather than `[ ... ] && rm`, so the function still returns 0 when
    # there is nothing to remove. A trap that ends on a false test is a way to
    # lose an exit status.
    if [ -n "$RESOLVED_ENV" ]; then
        rm -f "$RESOLVED_ENV"
    fi
}
trap cleanup EXIT

if ./scripts/resolve-secrets.sh --file .env --check; then
    echo "Resolving secret references in .env..."
    # Docker Compose reads an env file from disk for substitution — it cannot be
    # handed values on stdin — so the resolved copy has to exist briefly. It is
    # created empty under a 077 umask before anything is written to it, so the
    # secrets are never world-readable even for an instant, and it is removed on
    # exit including on failure.
    RESOLVED_ENV="$(umask 077 && mktemp "${TMPDIR:-/tmp}/omcsi-env.XXXXXXXX")"
    if ! ./scripts/resolve-secrets.sh --file .env --format env > "$RESOLVED_ENV"; then
        echo "ERROR: could not resolve every secret reference — nothing was started." >&2
        exit 1
    fi
    COMPOSE_ENV_ARGS=(--env-file "$RESOLVED_ENV")
fi

docker compose "${COMPOSE_ENV_ARGS[@]}" up -d --build

echo "Services started — run 'docker compose ps' to check status."
