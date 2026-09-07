#!/bin/bash
# Resolve secret references in an environment file.
#
# A value written as `!ref:<scheme>:<target>` is fetched at the moment it is
# needed instead of being stored in the file. Everything else is passed through
# untouched, so an existing .env full of literals resolves to itself and nothing
# that works today stops working.
#
#   RCON_PASSWORD=!ref:cmd:sops -d --extract '["rcon"]' secrets.enc.yaml
#   HCLOUD_TOKEN=!ref:file:~/.secrets/hcloud-token
#   DISCORD_WEBHOOK_URL=!ref:env:OMCSI_DISCORD_WEBHOOK
#
# Why a `!ref:` sigil rather than a bare `file:` prefix: these are password
# fields, and a password is exactly the place where guessing whether a value is
# a reference or a literal must never happen. `!ref:` cannot occur by accident.
#
# Usage:
#   scripts/resolve-secrets.sh [--file .env] [--format env|shell] [--check]
#
#   --format env    KEY=value lines, for `docker compose --env-file`
#   --format shell  export KEY='value' lines, for `source <(...)`
#   --check         resolve nothing; exit 0 if the file contains any reference
#
# Resolved values are written to stdout. Never redirect them anywhere that is
# not either a pipe or a file you have created with restrictive permissions.
set -euo pipefail

ENV_FILE=".env"
FORMAT="env"
CHECK_ONLY=false

usage() {
    sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --file) ENV_FILE="${2:?--file needs a path}"; shift 2 ;;
        --format) FORMAT="${2:?--format needs a value}"; shift 2 ;;
        --check) CHECK_ONLY=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

case "$FORMAT" in
    env|shell) ;;
    *) echo "ERROR: --format must be 'env' or 'shell', got '$FORMAT'" >&2; exit 2 ;;
esac

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: no such env file: $ENV_FILE" >&2
    exit 2
fi

# Strip one layer of matching surrounding quotes, so KEY="!ref:..." works.
unquote() {
    local value="$1"
    case "$value" in
        \"*\") value="${value:1:${#value}-2}" ;;
        \'*\') value="${value:1:${#value}-2}" ;;
    esac
    printf '%s' "$value"
}

# Resolve one reference. The scheme and target are never echoed on the success
# path, and on failure only the scheme and a short reason are — a command line
# and a command's stderr are both places a credential routinely appears.
resolve_ref() {
    local key="$1" body="$2" scheme target
    scheme="${body%%:*}"
    target="${body#*:}"

    if [ "$scheme" = "$body" ] || [ -z "$target" ]; then
        echo "ERROR: $key: malformed reference — expected !ref:<scheme>:<target>" >&2
        return 1
    fi

    case "$scheme" in
        env)
            if [ -z "${!target+set}" ]; then
                echo "ERROR: $key: environment variable $target is not set" >&2
                return 1
            fi
            printf '%s' "${!target}"
            ;;
        file)
            local path="${target/#\~/$HOME}"
            if [ ! -r "$path" ]; then
                echo "ERROR: $key: cannot read $path" >&2
                return 1
            fi
            # Trailing newlines are almost always an artifact of how the file
            # was written, not part of the secret.
            printf '%s' "$(cat "$path")"
            ;;
        cmd)
            local output status program
            program="${target%% *}"
            set +e
            output=$(eval "$target" 2>/dev/null)
            status=$?
            set -e
            if [ "$status" -ne 0 ]; then
                echo "ERROR: $key: command '$program' exited $status" >&2
                return 1
            fi
            printf '%s' "$output"
            ;;
        *)
            echo "ERROR: $key: unknown scheme '$scheme' — expected env, file, or cmd" >&2
            return 1
            ;;
    esac
}

# Single-quote a value for safe `source`ing, escaping embedded single quotes.
shell_quote() {
    printf "'%s'" "${1//\'/\'\\\'\'}"
}

found_reference=false
failures=0

while IFS= read -r line || [ -n "$line" ]; do
    # Comments and blanks are preserved in env format so the output stays
    # readable, and dropped in shell format where only assignments matter.
    case "$line" in
        ''|\#*)
            [ "$FORMAT" = "env" ] && [ "$CHECK_ONLY" = false ] && printf '%s\n' "$line"
            continue
            ;;
    esac

    stripped="${line#export }"
    key="${stripped%%=*}"
    if [ "$key" = "$stripped" ]; then
        # Not an assignment; pass it through rather than guessing.
        [ "$FORMAT" = "env" ] && [ "$CHECK_ONLY" = false ] && printf '%s\n' "$line"
        continue
    fi
    raw_value="${stripped#*=}"
    value="$(unquote "$raw_value")"

    if [ "${value#!ref:}" != "$value" ]; then
        found_reference=true
        [ "$CHECK_ONLY" = true ] && continue
        if ! resolved="$(resolve_ref "$key" "${value#!ref:}")"; then
            failures=$((failures + 1))
            continue
        fi
    else
        [ "$CHECK_ONLY" = true ] && continue
        resolved="$value"
    fi

    if [ "$FORMAT" = "shell" ]; then
        printf 'export %s=%s\n' "$key" "$(shell_quote "$resolved")"
    else
        printf '%s=%s\n' "$key" "$resolved"
    fi
done < "$ENV_FILE"

if [ "$CHECK_ONLY" = true ]; then
    [ "$found_reference" = true ] && exit 0
    exit 1
fi

if [ "$failures" -gt 0 ]; then
    echo "ERROR: $failures reference(s) could not be resolved; nothing was started." >&2
    exit 1
fi
