#!/bin/bash
# Test script to verify shutdown alert is sent when server stops via /stop command
set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to log test output
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
# shellcheck disable=SC2317,SC2329  # Function called via signal trap
cleanup() {
    test_log "Cleaning up test environment..."
    pkill -f "mock-minecraft-server" 2>/dev/null || true
    rm -rf /tmp/stop-command-test
    test_log "Cleanup completed"
}

# Set up cleanup trap
trap cleanup EXIT

test_log "🚀 Starting /stop command alert test..."

# Create test environment
TEST_DIR="/tmp/stop-command-test"
mkdir -p "$TEST_DIR"

# Create mock Minecraft server that exits normally with code 0 when receiving stop command
cat > "$TEST_DIR/mock-minecraft-server.jar" <<'EOF'
#!/bin/bash
echo "[MOCK-SERVER] Mock Minecraft server starting..."
echo "[MOCK-SERVER] Server started on localhost:25565"
echo "[MOCK-SERVER] Ready for connections!"

# Simulate server reading commands from stdin
while read -r line; do
    echo "[MOCK-SERVER] Command received: '$line'"
    case "$line" in
        "stop")
            echo "[MOCK-SERVER] Stopping server gracefully..."
            echo "[MOCK-SERVER] Saving world data..."
            echo "[MOCK-SERVER] Server stopped."
            exit 0  # Normal exit code when using /stop
            ;;
        *)
            echo "[MOCK-SERVER] Unknown command: $line"
            ;;
    esac
done

echo "[MOCK-SERVER] Input stream closed, stopping server..."
exit 0
EOF

# Create mock java executable that can run our mock server
cat > "$TEST_DIR/java" <<'EOF'
#!/bin/bash
# Mock java that executes JAR files

# Find the -jar parameter and execute the jar
for ((i=1; i<=$#; i++)); do
    if [ "${!i}" = "-jar" ]; then
        j=$((i+1))
        JAR_FILE="${!j}"
        # Make jar path absolute if needed
        if [[ "$JAR_FILE" != /* ]]; then
            JAR_FILE="$PWD/$JAR_FILE"
        fi
        # Skip to arguments after jar file
        shift $j
        exec "$JAR_FILE" "$@"
    fi
done

echo "Mock java: No -jar parameter found"
exit 1
EOF

chmod +x "$TEST_DIR/mock-minecraft-server.jar" "$TEST_DIR/java"

# Test: Verify wrapper sends alert when server stops via /stop command
test_log "Test: Verifying wrapper sends alert when server exits normally (e.g., /stop command)..."
cd "$(dirname "$0")/.."  # Go to repo root
export PATH="$TEST_DIR:$PATH"

# Start wrapper in background and capture output
./resources/minecraft-wrapper.sh mock-minecraft-server.jar "$TEST_DIR" "-Xmx1G" > "$TEST_DIR/wrapper_output.log" 2>&1 &
WRAPPER_PID=$!

# Give it time to start
sleep 5

# Check if wrapper is running
if ! kill -0 "$WRAPPER_PID" 2>/dev/null; then
    test_error "Wrapper failed to start"
    cat "$TEST_DIR/wrapper_output.log"
    exit 1
fi

test_success "Wrapper started successfully"

# Wait for server to be ready
sleep 2

# Send stop command to server via the FIFO (simulating /stop command)
test_log "Sending 'stop' command to server (simulating operator using /stop)..."
INPUT_FIFO="$TEST_DIR/server_input"
echo "stop" > "$INPUT_FIFO"

# Wait for server to shutdown
sleep 5

# Wait for wrapper to finish
wait "$WRAPPER_PID" 2>/dev/null || true

# Check wrapper output for shutdown alert
test_log "Checking for shutdown alert in wrapper output..."
cat "$TEST_DIR/wrapper_output.log"

# Verify shutdown alert was sent
TESTS_PASSED=0
TESTS_TOTAL=0

if grep -q "\[WRAPPER\] Minecraft server process exited with code: 0" "$TEST_DIR/wrapper_output.log"; then
    test_success "✓ Server exited with code 0 (normal shutdown)"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    test_error "✗ Server did not exit with code 0"
fi
TESTS_TOTAL=$((TESTS_TOTAL + 1))

if grep -q "\[WRAPPER\] Sending alert to.*Minecraft Server Stopped" "$TEST_DIR/wrapper_output.log"; then
    test_success "✓ Shutdown alert was sent when server stopped via /stop command"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    test_error "✗ Shutdown alert was NOT sent when server stopped via /stop command"
fi
TESTS_TOTAL=$((TESTS_TOTAL + 1))

# Verify that crash alert was NOT sent (since exit code was 0)
if ! grep -q "Minecraft Server Crashed" "$TEST_DIR/wrapper_output.log"; then
    test_success "✓ Crash alert was NOT sent (correct behavior for normal shutdown)"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    test_error "✗ Crash alert was incorrectly sent for normal shutdown"
fi
TESTS_TOTAL=$((TESTS_TOTAL + 1))

# Final results
test_log "📊 Test Results:"
test_log "Tests passed: $TESTS_PASSED/$TESTS_TOTAL"

if [ "$TESTS_PASSED" -eq "$TESTS_TOTAL" ]; then
    test_success "🎉 All /stop command alert tests passed!"
    test_success "The wrapper correctly sends shutdown alert when server stops via /stop command"
    exit 0
else
    test_error "❌ Some /stop command alert tests failed"
    test_error "The wrapper is not sending the correct alert when server stops via /stop command"
    exit 1
fi
