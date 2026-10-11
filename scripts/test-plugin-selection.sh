#!/bin/bash
# Test script for reconcile_plugin_selection() in resources/post-create.sh.
#
# The behaviours under test are the ones a hosted server's data depends on:
# jars are matched by their plugin.yml name rather than filename, so an
# in-game update or a hand upload is adopted instead of duplicated; only jars
# the selection installed or adopted are ever removed; a plugin's data folder
# survives its removal; and nothing the reconciler can hit stops the boot.
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
TEST_ROOT="/tmp/plugin-selection-test"
FAILURES=0

# shellcheck disable=SC2317  # called via trap
cleanup() { rm -rf "$TEST_ROOT"; }
trap cleanup EXIT

# --- assertions -------------------------------------------------------------

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
outcome_is() {
    [ "$(jq -r --arg s "$1" '.plugins[$s]' "$PLUGINS/.omcsi-applied.json")" = "$2" ]
}

# shellcheck disable=SC2317  # invoked indirectly, as an argument to assert_true
managed_has() {
    jq -e --arg s "$1" 'has($s)' "$PLUGINS/.omcsi-managed.json" >/dev/null
}

# shellcheck disable=SC2317  # invoked indirectly, as an argument to assert_true
jar_count_named() {
    local name="$1" expected="$2" count=0 jar
    for jar in "$PLUGINS"/*.jar; do
        [ -f "$jar" ] || continue
        if [ "$(unzip -p "$jar" plugin.yml | sed -n 's/^name: //p')" = "$name" ]; then
            count=$((count + 1))
        fi
    done
    [ "$count" -eq "$expected" ]
}

# shellcheck disable=SC2317  # invoked indirectly, as an argument to assert_true
no_staged_files() {
    [ -z "$(find "$PLUGINS" -name '.incoming-*' 2>/dev/null)" ]
}

# --- harness ----------------------------------------------------------------

# The functions live in a script that starts a server at the bottom, so they
# are extracted rather than the file sourced.
extract_functions() {
    local extracted="$TEST_ROOT/reconcile.sh"
    {
        # shellcheck disable=SC2016  # literal function definitions, not expansions
        echo 'log() { echo "[SERVER-SETUP] $1"; }'
        # shellcheck disable=SC2016
        echo 'send_alert() { echo "$1" >> "$TEST_ROOT/alerts"; }'
        sed -n '/^plugin_name_of() {/,/^}/p' "$POST_CREATE"
        sed -n '/^reconcile_plugin_selection() {/,/^}/p' "$POST_CREATE"
    } > "$extracted"
    if ! grep -q '^reconcile_plugin_selection() {' "$extracted" || ! grep -q '^plugin_name_of() {' "$extracted"; then
        test_error "could not extract the reconcile functions from $POST_CREATE"
        exit 1
    fi
    echo "$extracted"
}

# make_jar <path> <plugin name>: a jar whose plugin.yml declares that name.
# An empty name makes a zip with no plugin.yml.
make_jar() {
    python3 - "$1" "$2" <<'EOF'
import sys, zipfile
path, name = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(path, "w") as z:
    if name:
        z.writestr("plugin.yml", f"name: {name}\nversion: 1.0\nmain: x.Y\n")
    else:
        z.writestr("readme.txt", "not a plugin")
EOF
}

# select_plugins <slug=filename=source>...: write the selection file.
select_plugins() {
    local entries=() spec slug filename source
    for spec in "$@"; do
        IFS='=' read -r slug filename source <<< "$spec"
        entries+=("$(jq -n --arg s "$slug" --arg f "$filename" --arg u "file://$REPO/$source" \
            '{slug: $s, tag: "v1", filename: $f, url: $u}')")
    done
    if [ "${#entries[@]}" -eq 0 ]; then
        jq -n '{version: 1, plugins: []}' > "$PLUGIN_SELECTION_FILE"
    else
        printf '%s\n' "${entries[@]}" | jq -s '{version: 1, plugins: .}' > "$PLUGIN_SELECTION_FILE"
    fi
}

reconcile() {
    # Run in a subshell with the boot script's own options, so a failure that
    # would end a real boot fails the test the same way.
    (
        set -euo pipefail
        # shellcheck disable=SC1090
        source "$FUNCTIONS"
        reconcile_plugin_selection
    ) > "$TEST_ROOT/last.log" 2>&1
}

rm -rf "$TEST_ROOT"
mkdir -p "$TEST_ROOT"
FUNCTIONS="$(extract_functions)"
export TEST_ROOT
SERVER_DIR="$TEST_ROOT/server"
PLUGINS="$SERVER_DIR/plugins"
REPO="$TEST_ROOT/repo"
PLUGIN_SELECTION_FILE="$TEST_ROOT/selection.json"
export SERVER_DIR PLUGIN_SELECTION_FILE
mkdir -p "$PLUGINS" "$REPO"

make_jar "$REPO/mf.jar" "MedievalFactions"
make_jar "$REPO/fiefs.jar" "Fiefs"
make_jar "$REPO/dpm.jar" "DansPluginManager"
make_jar "$REPO/currencies.jar" "Currencies"
make_jar "$REPO/notaplugin.jar" ""

# --- no selection file --------------------------------------------------------

test_log "No selection file is a no-op"
assert_true "returns success" reconcile
assert_false "writes no manifest" test -e "$PLUGINS/.omcsi-managed.json"
assert_false "writes no applied report" test -e "$PLUGINS/.omcsi-applied.json"

# --- invalid selection ----------------------------------------------------------

test_log "An invalid selection changes nothing and does not stop the boot"
echo '{"plugins": "nope"}' > "$PLUGIN_SELECTION_FILE"
assert_true "returns success" reconcile
assert_false "writes no manifest" test -e "$PLUGINS/.omcsi-managed.json"
assert_true "raises an alert" grep -q "Plugin Selection Invalid" "$TEST_ROOT/alerts"

# --- fresh install ----------------------------------------------------------------

test_log "A fresh install downloads the jar under the selected filename"
# The download URL deliberately ends in something other than the filename, as
# a counting /download URL does.
cp "$REPO/mf.jar" "$REPO/download"
select_plugins "medieval-factions=MedievalFactions-5.8.1.jar=download"
assert_true "returns success" reconcile
assert_true "installs the jar under the selected filename" test -f "$PLUGINS/MedievalFactions-5.8.1.jar"
assert_false "does not name the jar after the URL" test -e "$PLUGINS/download"
assert_true "reports installed" outcome_is medieval-factions installed
assert_true "records it as managed" managed_has medieval-factions
assert_true "leaves no staged files" no_staged_files

test_log "Reconciling again changes nothing"
assert_true "returns success" reconcile
assert_true "reports present" outcome_is medieval-factions present
assert_true "still exactly one MedievalFactions jar" jar_count_named MedievalFactions 1

# --- in-game update renames the jar ----------------------------------------------

test_log "A jar renamed by an in-game update is kept, not duplicated"
mv "$PLUGINS/MedievalFactions-5.8.1.jar" "$PLUGINS/MedievalFactions-5.9.0.jar"
assert_true "returns success" reconcile
assert_true "reports present" outcome_is medieval-factions present
assert_true "exactly one MedievalFactions jar" jar_count_named MedievalFactions 1
assert_false "does not download the old filename again" test -e "$PLUGINS/MedievalFactions-5.8.1.jar"
assert_true "manifest follows the new filename" \
    test "$(jq -r '."medieval-factions".filename' "$PLUGINS/.omcsi-managed.json")" = "MedievalFactions-5.9.0.jar"

# --- hand-uploaded jar of a selected plugin --------------------------------------

test_log "A hand-uploaded jar of a newly selected plugin is adopted"
make_jar "$PLUGINS/Fiefs-handmade.jar" "Fiefs"
select_plugins "medieval-factions=MedievalFactions-5.8.1.jar=download" "fiefs=Fiefs-0.12.0.jar=fiefs.jar"
assert_true "returns success" reconcile
assert_true "reports adopted" outcome_is fiefs adopted
assert_true "exactly one Fiefs jar" jar_count_named Fiefs 1
assert_true "keeps the uploaded jar" test -f "$PLUGINS/Fiefs-handmade.jar"
assert_true "leaves no staged files" no_staged_files

# --- selected filename already present ---------------------------------------------

test_log "A jar already at the selected filename is adopted without a download"
cp "$REPO/dpm.jar" "$PLUGINS/DansPluginManager-0.7.0.jar"
select_plugins "medieval-factions=MedievalFactions-5.8.1.jar=download" "fiefs=Fiefs-0.12.0.jar=fiefs.jar" \
    "dans-plugin-manager=DansPluginManager-0.7.0.jar=does-not-exist.jar"
assert_true "returns success" reconcile
assert_true "reports adopted" outcome_is dans-plugin-manager adopted
assert_true "records it as managed" managed_has dans-plugin-manager

# --- removal ---------------------------------------------------------------------------

test_log "Removing a plugin deletes its jar, under its current name, and keeps its data"
mkdir -p "$PLUGINS/MedievalFactions"
echo "factions" > "$PLUGINS/MedievalFactions/data.json"
make_jar "$PLUGINS/Unmanaged.jar" "SomethingElse"
select_plugins "fiefs=Fiefs-0.12.0.jar=fiefs.jar" "dans-plugin-manager=DansPluginManager-0.7.0.jar=dpm.jar"
assert_true "returns success" reconcile
assert_true "reports removed" outcome_is medieval-factions removed
assert_true "no MedievalFactions jar left" jar_count_named MedievalFactions 0
assert_true "keeps the data folder" test -f "$PLUGINS/MedievalFactions/data.json"
assert_false "drops it from the manifest" managed_has medieval-factions
assert_true "leaves an unmanaged jar alone" test -f "$PLUGINS/Unmanaged.jar"
assert_true "leaves other managed plugins alone" jar_count_named Fiefs 1

test_log "Reinstalling a removed plugin finds its data again"
select_plugins "fiefs=Fiefs-0.12.0.jar=fiefs.jar" "dans-plugin-manager=DansPluginManager-0.7.0.jar=dpm.jar" \
    "medieval-factions=MedievalFactions-5.8.1.jar=download"
assert_true "returns success" reconcile
assert_true "reports installed" outcome_is medieval-factions installed
assert_true "data folder still there" test -f "$PLUGINS/MedievalFactions/data.json"

test_log "An unmanaged jar is never removed, even when nothing is selected"
select_plugins
assert_true "returns success" reconcile
assert_true "leaves the unmanaged jar" test -f "$PLUGINS/Unmanaged.jar"
assert_true "removes every managed jar" jar_count_named Fiefs 0
assert_true "manifest is empty" test "$(jq -c . "$PLUGINS/.omcsi-managed.json")" = "{}"

# --- failures never stop the boot -------------------------------------------------------

test_log "A failed download is reported and the rest still installs"
rm -f "$TEST_ROOT/alerts"
select_plugins "missing=Missing-1.0.jar=no-such-file.jar" "currencies=Currencies-2.1.0.jar=currencies.jar"
assert_true "returns success" reconcile
assert_true "reports the failure" outcome_is missing "failed: download"
assert_true "installs the other plugin" outcome_is currencies installed
assert_false "leaves no partial jar" test -e "$PLUGINS/Missing-1.0.jar"
assert_true "leaves no staged files" no_staged_files
assert_true "raises an alert" grep -q "Plugin Selection Incomplete" "$TEST_ROOT/alerts"

test_log "A download that is not a plugin is rejected"
select_plugins "junk=Junk-1.0.jar=notaplugin.jar"
assert_true "returns success" reconcile
assert_true "reports no plugin.yml" outcome_is junk "failed: no plugin.yml"
assert_false "does not install it" test -e "$PLUGINS/Junk-1.0.jar"
assert_true "leaves no staged files" no_staged_files

test_log "A selected filename taken by a different plugin is left alone"
make_jar "$PLUGINS/Taken-1.0.jar" "Occupant"
select_plugins "taken=Taken-1.0.jar=currencies.jar"
assert_true "returns success" reconcile
assert_true "does not overwrite it" jar_count_named Occupant 1

test_log "An entry with a path in its filename is refused"
jq -n --arg u "file://$REPO/currencies.jar" \
    '{version: 1, plugins: [{slug: "evil", filename: "../evil.jar", url: $u}]}' > "$PLUGIN_SELECTION_FILE"
assert_true "returns success" reconcile
assert_true "reports invalid entry" outcome_is evil "failed: invalid entry"
assert_false "writes nothing outside plugins/" test -e "$SERVER_DIR/evil.jar"

# --- summary ------------------------------------------------------------------------------

if [ "$FAILURES" -gt 0 ]; then
    test_error "$FAILURES assertion(s) failed"
    echo "--- last reconcile log ---"
    cat "$TEST_ROOT/last.log"
    exit 1
fi
test_success "All plugin selection tests passed"
