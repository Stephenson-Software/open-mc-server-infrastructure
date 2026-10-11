#!/bin/bash
set -euo pipefail

SERVER_DIR="/mcserver"
BUILD_DIR="/mcserver-build"

# Function: Log a message with the [SERVER-SETUP] prefix
log() {
    local message="$1"
    echo "[SERVER-SETUP] $message"
}

# Function: Send alert to alert-manager
send_alert() {
    local title="$1"
    local message="$2"
    local level="${3:-INFO}"
    local source="server-setup"
    local alert_toggle="${4:-}"
    
    # Check if this type of alert is enabled (if toggle variable is provided)
    if [ -n "$alert_toggle" ]; then
        local toggle_value="${!alert_toggle:-true}"
        if [ "$toggle_value" != "true" ]; then
            return 0
        fi
    fi
    
    # Determine the alert manager URL based on environment
    local alert_url
    if [ -n "${ALERT_MANAGER_URL:-}" ]; then
        alert_url="$ALERT_MANAGER_URL"
    elif [ -f /.dockerenv ] || grep -q docker /proc/1/cgroup 2>/dev/null; then
        alert_url="http://alert-manager:8090/api/alerts"
    else
        alert_url="http://localhost:8090/api/alerts"
    fi
    
    # Try to send alert, but don't fail if it doesn't work
    if command -v curl >/dev/null 2>&1; then
        curl -X POST "$alert_url" \
          -H "Content-Type: application/json" \
          --max-time 5 \
          --connect-timeout 5 \
          -d "{\"title\":\"$title\",\"message\":\"$message\",\"level\":\"$level\",\"source\":\"$source\"}" \
          >/dev/null 2>&1 || true
    fi
}

# Function: Validate required environment variables
validate_environment() {
    local warnings=false
    local warning_messages=""
    
    if [ "$OPERATOR_UUID" = "YOUR_UUID_HERE" ] || [ -z "$OPERATOR_UUID" ]; then
        log "WARNING: OPERATOR_UUID is not set properly. Consider setting it to your actual UUID from https://mcuuid.net/"
        log "Server will continue with default operator configuration."
        warnings=true
        warning_messages="OPERATOR_UUID not configured"
    fi
    
    if [ "$OPERATOR_NAME" = "YOUR_USERNAME_HERE" ] || [ -z "$OPERATOR_NAME" ]; then
        log "WARNING: OPERATOR_NAME is not set properly. Consider setting it to your actual Minecraft username."
        log "Server will continue with default operator configuration."
        warnings=true
        if [ -n "$warning_messages" ]; then
            warning_messages="$warning_messages, OPERATOR_NAME not configured"
        else
            warning_messages="OPERATOR_NAME not configured"
        fi
    fi
    
    if [ "$warnings" = false ]; then
        log "Environment validation passed."
    else
        log "Server starting with configuration warnings - please check your .env file."
        send_alert "Server Configuration Warning" "Server started with configuration issues: $warning_messages" "WARNING" "ALERTS_CONFIG_WARNING"
    fi
}

# Function: Setup server
setup_server() {
    if [ -z "$(ls -A "$SERVER_DIR")" ] || [ "$OVERWRITE_EXISTING_SERVER" = "true" ]; then
        log "Setting up new server..."
        rm -rf "${SERVER_DIR:?}"/*
        cp "$BUILD_DIR"/spigot-"${MINECRAFT_VERSION}".jar "$SERVER_DIR"/spigot-"${MINECRAFT_VERSION}".jar
        mkdir -p "$SERVER_DIR"/plugins
    else
        log "Server is already set up."
        
        # Check if we need to update the server JAR for a version upgrade
        local new_jar="$BUILD_DIR/spigot-${MINECRAFT_VERSION}.jar"
        local expected_jar="$SERVER_DIR/spigot-${MINECRAFT_VERSION}.jar"
        
        # Check if the expected JAR for this version exists
        if [ ! -f "$expected_jar" ]; then
            # The image carries only the Spigot JAR it was built with, so
            # "$new_jar" is absent whenever MINECRAFT_VERSION names a version
            # this image was not built for. That is reachable whenever the image
            # tag is not pinned alongside the version.
            #
            # Refuse before touching anything. A server that will not start is
            # recoverable by correcting the version or the tag; a server
            # directory with the world still in it and no JAR to run it is a
            # manual restore.
            if [ ! -f "$new_jar" ]; then
                local available
                available=$(find "$BUILD_DIR" -maxdepth 1 -name 'spigot-*.jar' -exec basename {} \; 2>/dev/null | tr '\n' ' ')
                log "ERROR: MINECRAFT_VERSION is ${MINECRAFT_VERSION}, but this image does not contain spigot-${MINECRAFT_VERSION}.jar."
                log "ERROR: The image contains: ${available:-none}"
                log "ERROR: Pin the image tag to the same version as MINECRAFT_VERSION and redeploy."
                log "ERROR: The existing server JAR has been left untouched."
                return 1
            fi

            log "Detected version change - updating server JAR to ${MINECRAFT_VERSION}..."

            # Stage the replacement fully before removing anything, so an
            # interrupted or short copy cannot leave the directory with no JAR.
            # The staged name is dot-prefixed so it can never be caught by the
            # spigot-*.jar cleanup glob below.
            local staged_jar="$SERVER_DIR/.incoming-spigot-${MINECRAFT_VERSION}.jar"
            rm -f "$staged_jar"
            if ! cp "$new_jar" "$staged_jar"; then
                rm -f "$staged_jar"
                log "ERROR: failed to copy ${new_jar}. The existing server JAR has been left untouched."
                return 1
            fi
            # Guards against a truncated copy, which a full volume produces
            # without cp itself failing on every platform.
            if ! cmp -s "$new_jar" "$staged_jar"; then
                rm -f "$staged_jar"
                log "ERROR: staged JAR does not match ${new_jar} (out of disk space?). The existing server JAR has been left untouched."
                return 1
            fi

            # Only now is it safe to remove the old JARs.
            if ls "$SERVER_DIR"/spigot-*.jar >/dev/null 2>&1; then
                local old_jars
                old_jars=$(find "$SERVER_DIR" -name "spigot-*.jar" -exec basename {} \;)
                log "Removing old JAR(s): $old_jars"
                rm -f "$SERVER_DIR"/spigot-*.jar
            fi

            # Rename within the same directory, so the JAR appears atomically.
            mv "$staged_jar" "$expected_jar"
            log "Server JAR updated successfully to version ${MINECRAFT_VERSION}."
        else
            log "Server JAR is up to date (version ${MINECRAFT_VERSION})."
        fi
    fi
}

# Function: Install default plugins listed in DEFAULT_PLUGINS (comma-separated
# download URLs). Idempotent: a plugin already present under plugins/ (matched
# by the URL's filename) is left untouched, so manually installed or updated
# plugins are never overwritten.
install_default_plugins() {
    if [ -z "${DEFAULT_PLUGINS:-}" ]; then
        return 0
    fi

    mkdir -p "$SERVER_DIR/plugins"

    local IFS=','
    read -ra plugin_urls <<< "$DEFAULT_PLUGINS"
    for url in "${plugin_urls[@]}"; do
        # Trim leading/trailing whitespace
        url="${url#"${url%%[![:space:]]*}"}"
        url="${url%"${url##*[![:space:]]}"}"
        if [ -z "$url" ]; then
            continue
        fi

        local filename
        filename="$(basename "$url")"
        local dest="$SERVER_DIR/plugins/$filename"

        if [ -f "$dest" ]; then
            log "Default plugin already installed: $filename"
            continue
        fi

        log "Installing default plugin: $filename"
        if curl -fsSL --max-time 60 -o "$dest" "$url"; then
            log "Installed default plugin: $filename"
        else
            log "WARNING: Failed to download default plugin from $url"
            rm -f "$dest"
            send_alert "Default Plugin Install Failed" "Failed to download plugin from $url" "WARNING" "ALERTS_CONFIG_WARNING"
        fi
    done
}

# The plugin selection an operator platform wants on this server, if any:
#   {"version": 1, "plugins": [{"slug": "...", "filename": "X-1.0.jar", "url": "https://..."}]}
# On Kubernetes it is the optional <release>-plugins ConfigMap; with no file,
# reconcile_plugin_selection does nothing.
PLUGIN_SELECTION_FILE="${PLUGIN_SELECTION_FILE:-/etc/omcsi/plugins/selection.json}"

# Function: Print the name: declared in a jar's plugin.yml, or nothing when the
# jar has none (not a Bukkit plugin, or not a zip at all).
plugin_name_of() {
    # Always succeeds: under pipefail an unreadable jar must not end the boot.
    { unzip -p "$1" plugin.yml 2>/dev/null || true; } \
        | tr -d '\r' \
        | sed -n -e '/^name:/{' -e 's/^name:[[:space:]]*//' -e 's/[[:space:]]*$//' \
            -e "s/^[\"']//" -e "s/[\"']\$//" -e 'p' -e 'q' -e '}' \
        || true
}

# Function: Reconcile plugins/ against PLUGIN_SELECTION_FILE.
#
# Jars are identified by the name in their plugin.yml, never by filename: an
# in-game updater (/dpm update) replaces a jar under a new filename, and a
# filename match would put the old version back beside it, which Bukkit
# refuses to load. plugins/.omcsi-managed.json records which plugin each
# selected slug installed or adopted; only those are ever removed. Jars the
# manifest does not name are never touched, and plugin data folders
# (plugins/<Name>/) are never deleted, so a reinstall finds its data again.
#
# Per-slug outcomes go to plugins/.omcsi-applied.json for the wrapper to serve.
# A failure leaves the previous state in place, logs and alerts; it never stops
# the server from booting.
reconcile_plugin_selection() {
    if [ ! -f "$PLUGIN_SELECTION_FILE" ]; then
        return 0
    fi
    local tool
    for tool in jq unzip; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            log "WARNING: $tool is not installed; skipping plugin selection."
            return 0
        fi
    done
    if ! jq -e '.version == 1 and (.plugins | type == "array")' "$PLUGIN_SELECTION_FILE" >/dev/null 2>&1; then
        log "WARNING: $PLUGIN_SELECTION_FILE is not a version 1 plugin selection; skipping."
        send_alert "Plugin Selection Invalid" "The plugin selection could not be read; plugins were left unchanged." "WARNING" "ALERTS_CONFIG_WARNING"
        return 0
    fi

    local plugins_dir="$SERVER_DIR/plugins"
    local manifest="$plugins_dir/.omcsi-managed.json"
    local applied="$plugins_dir/.omcsi-applied.json"
    mkdir -p "$plugins_dir"

    # A missing or unreadable manifest is an empty one: nothing is known to be
    # managed, so nothing can be removed. That is the safe direction to fail.
    local managed='{}'
    if [ -f "$manifest" ]; then
        if jq -e 'type == "object"' "$manifest" >/dev/null 2>&1; then
            managed="$(cat "$manifest")"
        else
            log "WARNING: $manifest is unreadable; treating no plugins as managed."
        fi
    fi

    # Index every jar by its plugin name.
    local -A name_of_jar=()
    local jar
    for jar in "$plugins_dir"/*.jar; do
        [ -f "$jar" ] || continue
        name_of_jar["$jar"]="$(plugin_name_of "$jar")"
    done

    local outcomes='{}' failures=0 slug name filename url

    # Removals: managed slugs that are no longer selected.
    local selected_slugs
    selected_slugs="$(jq -c '[.plugins[].slug]' "$PLUGIN_SELECTION_FILE")"
    while IFS=$'\t' read -r slug name; do
        [ -n "$slug" ] || continue
        for jar in "${!name_of_jar[@]}"; do
            if [ -n "$name" ] && [ "${name_of_jar[$jar]}" = "$name" ]; then
                rm -f "$jar"
                unset 'name_of_jar[$jar]'
                log "Removed plugin $name ($(basename "$jar")); its data folder is kept."
            fi
        done
        managed="$(jq --arg s "$slug" 'del(.[$s])' <<< "$managed")"
        outcomes="$(jq --arg s "$slug" '.[$s] = "removed"' <<< "$outcomes")"
    done < <(jq -r --argjson sel "$selected_slugs" \
        'to_entries[] | select(.key as $k | $sel | index($k) | not) | [.key, (.value.name // "")] | @tsv' <<< "$managed")

    # Installs: selected slugs whose plugin is not on disk.
    while IFS=$'\t' read -r slug filename url; do
        if ! [[ "$slug" =~ ^[a-z0-9][a-z0-9._-]*$ ]] || ! [[ "$filename" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]*\.jar$ ]] || [ -z "$url" ]; then
            log "WARNING: skipping invalid plugin selection entry: ${slug:-<no slug>}"
            outcomes="$(jq --arg s "${slug:-?}" '.[$s] = "failed: invalid entry"' <<< "$outcomes")"
            failures=$((failures + 1))
            continue
        fi

        # Already managed and present, under whatever filename it has now.
        name="$(jq -r --arg s "$slug" '.[$s].name // ""' <<< "$managed")"
        local present=""
        if [ -n "$name" ]; then
            for jar in "${!name_of_jar[@]}"; do
                if [ "${name_of_jar[$jar]}" = "$name" ]; then present="$jar"; break; fi
            done
        fi
        if [ -n "$present" ]; then
            managed="$(jq --arg s "$slug" --arg f "$(basename "$present")" '.[$s].filename = $f' <<< "$managed")"
            outcomes="$(jq --arg s "$slug" '.[$s] = "present"' <<< "$outcomes")"
            continue
        fi

        # The selected filename is already there (a default plugin, say): adopt
        # it without downloading.
        local candidate="" candidate_name=""
        if [ -f "$plugins_dir/$filename" ] && [ -n "${name_of_jar[$plugins_dir/$filename]:-}" ]; then
            candidate_name="${name_of_jar[$plugins_dir/$filename]}"
        else
            candidate="$(mktemp "$plugins_dir/.incoming-XXXXXX")"
            if ! curl -fsSL --max-time 120 -o "$candidate" "$url"; then
                rm -f "$candidate"
                log "WARNING: failed to download plugin $slug from $url"
                outcomes="$(jq --arg s "$slug" '.[$s] = "failed: download"' <<< "$outcomes")"
                failures=$((failures + 1))
                continue
            fi
            candidate_name="$(plugin_name_of "$candidate")"
            if [ -z "$candidate_name" ]; then
                rm -f "$candidate"
                log "WARNING: $url is not a Bukkit plugin (no plugin.yml name)"
                outcomes="$(jq --arg s "$slug" '.[$s] = "failed: no plugin.yml"' <<< "$outcomes")"
                failures=$((failures + 1))
                continue
            fi
        fi

        # The plugin may already be installed under another filename: by hand,
        # or by an in-game updater. Adopt that jar rather than add a second.
        present=""
        for jar in "${!name_of_jar[@]}"; do
            if [ "${name_of_jar[$jar]}" = "$candidate_name" ]; then present="$jar"; break; fi
        done
        if [ -n "$present" ]; then
            [ -z "$candidate" ] || rm -f "$candidate"
            managed="$(jq --arg s "$slug" --arg n "$candidate_name" --arg f "$(basename "$present")" \
                '.[$s] = {name: $n, filename: $f}' <<< "$managed")"
            outcomes="$(jq --arg s "$slug" '.[$s] = "adopted"' <<< "$outcomes")"
            log "Adopted plugin $candidate_name ($(basename "$present")) for $slug."
            continue
        fi

        if [ -f "$plugins_dir/$filename" ]; then
            rm -f "$candidate"
            log "WARNING: $filename already exists and is not plugin $candidate_name; leaving it alone."
            outcomes="$(jq --arg s "$slug" '.[$s] = "failed: filename taken"' <<< "$outcomes")"
            failures=$((failures + 1))
            continue
        fi
        mv "$candidate" "$plugins_dir/$filename"
        name_of_jar["$plugins_dir/$filename"]="$candidate_name"
        managed="$(jq --arg s "$slug" --arg n "$candidate_name" --arg f "$filename" \
            '.[$s] = {name: $n, filename: $f}' <<< "$managed")"
        outcomes="$(jq --arg s "$slug" '.[$s] = "installed"' <<< "$outcomes")"
        log "Installed plugin $candidate_name ($filename) for $slug."
    done < <(jq -r '.plugins[] | [(.slug // ""), (.filename // ""), (.url // "")] | @tsv' "$PLUGIN_SELECTION_FILE")

    # Write both files atomically: a half-written manifest would read as empty.
    local selection_hash
    selection_hash="$(sha256sum "$PLUGIN_SELECTION_FILE" | cut -d' ' -f1)"
    printf '%s\n' "$managed" > "$manifest.tmp" && mv "$manifest.tmp" "$manifest"
    jq -n --arg h "$selection_hash" --arg t "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson o "$outcomes" \
        '{selection_sha256: $h, applied_at: $t, plugins: $o}' > "$applied.tmp" && mv "$applied.tmp" "$applied"

    if [ "$failures" -gt 0 ]; then
        send_alert "Plugin Selection Incomplete" "$failures selected plugin(s) could not be installed; see the server setup log." "WARNING" "ALERTS_CONFIG_WARNING"
    fi
}

# Function: Setup ops.json file
setup_ops_file() {
    # Check if ops.json already exists - if so, preserve it to maintain runtime op changes
    if [ -f "$SERVER_DIR/ops.json" ]; then
        log "ops.json already exists - preserving existing operator configuration."
        return
    fi
    
    # Only create ops.json if we have valid operator information
    if [ "$OPERATOR_UUID" != "YOUR_UUID_HERE" ] && [ -n "$OPERATOR_UUID" ] && [ "$OPERATOR_NAME" != "YOUR_USERNAME_HERE" ] && [ -n "$OPERATOR_NAME" ]; then
        log "Creating ops.json file with operator: ${OPERATOR_NAME}"
        cat <<EOF > "$SERVER_DIR"/ops.json
[
  {
    "uuid": "${OPERATOR_UUID}",
    "name": "${OPERATOR_NAME}",
    "level": ${OPERATOR_LEVEL},
    "bypassesPlayerLimit": false
  }
]
EOF
    else
        log "Skipping ops.json creation - operator information not properly configured."
        log "You can add operators manually using the 'op <username>' command in the server console."
    fi
}

# Function: Accept EULA
accept_eula() {
    log "Accepting Minecraft EULA..."
    echo "eula=true" > "$SERVER_DIR"/eula.txt
}

# Function: Create server properties
#
# NOTE: this OVERWRITES $SERVER_DIR/server.properties in full on every container
# start, not just on first setup. Every line below is managed by OMCSI, so any
# hand edit to server.properties -- whether made with an editor or by an
# in-game/console command that persists to the file, such as `/whitelist on` --
# is reverted the next time the container starts. To make a setting stick, set
# the corresponding environment variable (see sample.env / values.yaml) instead
# of editing the file. Settings not listed here are left to the server's own
# defaults and are likewise reset. Data files that live alongside it on the
# persistent volume (whitelist.json, ops.json, banned-players.json, the world)
# are untouched.
create_server_properties() {
    log "Creating server.properties file..."
    cat <<EOF > "$SERVER_DIR"/server.properties
#Minecraft server properties
enable-jmx-monitoring=false
rcon.port=25575
level-seed=
gamemode=${GAMEMODE}
enable-command-block=false
enable-query=false
generator-settings={}
enforce-secure-profile=true
level-name=world
motd=${SERVER_MOTD}
query.port=25565
pvp=${PVP_ENABLED}
generate-structures=true
max-chained-neighbor-updates=1000000
difficulty=${DIFFICULTY}
network-compression-threshold=256
max-tick-time=60000
require-resource-pack=false
use-native-transport=true
max-players=${MAX_PLAYERS}
online-mode=${ONLINE_MODE}
enable-status=true
allow-flight=false
initial-disabled-packs=
broadcast-rcon-to-ops=true
view-distance=10
server-ip=
resource-pack-prompt=
allow-nether=true
server-port=25565
enable-rcon=true
sync-chunk-writes=true
op-permission-level=4
prevent-proxy-connections=false
hide-online-players=false
resource-pack=
entity-broadcast-range-percentage=100
simulation-distance=10
rcon.password=${RCON_PASSWORD}
player-idle-timeout=0
debug=false
force-gamemode=false
rate-limit=0
hardcore=false
white-list=${WHITELIST_ENABLED:-false}
broadcast-console-to-ops=true
spawn-npcs=true
spawn-animals=true
function-permission-level=2
initial-enabled-packs=vanilla
level-type=minecraft\:normal
text-filtering-config=
spawn-monsters=true
enforce-whitelist=${ENFORCE_WHITELIST:-false}
spawn-protection=16
resource-pack-sha1=
max-world-size=29999984
EOF
}

# Function: Start server
start_server() {
    log "Starting server with Spring Boot wrapper..."
    local wrapper_jar="${MINECRAFT_WRAPPER_JAR:-/app/minecraft-wrapper.jar}"
    
    # Verify wrapper JAR exists
    if [ ! -f "$wrapper_jar" ]; then
        log "ERROR: Minecraft wrapper JAR not found at: $wrapper_jar"
        exit 1
    fi
    
    # Export environment variables for Spring Boot to read
    export MINECRAFT_SERVER_JAR="spigot-${MINECRAFT_VERSION}.jar"
    export MINECRAFT_SERVER_DIRECTORY="$SERVER_DIR"
    export JAVA_OPTS="${JAVA_OPTS:--Xmx2G -Xms1G}"
    export MINECRAFT_AUTO_START=true
    
    # The server runs on the JDK its version accepts (resources/java-for-minecraft.sh);
    # the wrapper passes MINECRAFT_JAVA to the server process it starts.
    MINECRAFT_JAVA=$(/resources/java-for-minecraft.sh "$MINECRAFT_VERSION") || {
        log "ERROR: no Java runtime in this image can run Minecraft $MINECRAFT_VERSION"
        exit 1
    }
    export MINECRAFT_JAVA
    log "Using $MINECRAFT_JAVA for the Minecraft $MINECRAFT_VERSION server"

    # Spring Boot 3.2.0 has runtime compatibility issues with Java 25: embedded Tomcat
    # and Spring framework internals rely on APIs that were removed or restricted in
    # Java 25, causing request processing failures. Run the wrapper explicitly under
    # Java 21.  The Spigot server subprocess spawned by the wrapper runs on MINECRAFT_JAVA
    # (above): Java 25 from the temurin base image for 26.x, older JDKs for older versions.
    local java21_bin
    java21_bin=$(find /usr/lib/jvm -path '*/java-21-openjdk-*/bin/java' -print -quit 2>/dev/null || true)
    if [ -z "$java21_bin" ] || [ ! -x "$java21_bin" ]; then
        log "ERROR: Java 21 executable not found at /usr/lib/jvm/java-21-openjdk-*/bin/java. The image requires openjdk-21-jre-headless to run the Spring Boot wrapper."
        exit 1
    fi
    log "Using Java 21 for Spring Boot wrapper: $java21_bin"
    exec "$java21_bin" -jar "$wrapper_jar"
}

# Main Process
log "Running server setup script..."
validate_environment
setup_server
install_default_plugins
reconcile_plugin_selection
setup_ops_file
accept_eula
create_server_properties

# Start Server
start_server