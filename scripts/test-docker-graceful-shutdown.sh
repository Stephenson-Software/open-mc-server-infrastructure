#!/bin/bash
# Test script to verify graceful shutdown works in Docker environment
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
    docker rm -f test-mc-server-graceful 2>/dev/null || true
    rm -rf /tmp/docker-graceful-test
    test_log "Cleanup completed"
}

# Set up cleanup trap
trap cleanup EXIT

test_log "🚀 Starting Docker graceful shutdown test..."

# Create test environment
TEST_DIR="/tmp/docker-graceful-test"
mkdir -p "$TEST_DIR"

# Create a minimal Dockerfile for testing
cat > "$TEST_DIR/Dockerfile" <<'EOF'
FROM ubuntu:22.04

# Install dependencies
RUN apt-get update && apt-get install -y openjdk-21-jdk

# Copy wrapper script
COPY minecraft-wrapper.sh /resources/minecraft-wrapper.sh
COPY post-create.sh /resources/post-create.sh
COPY mock-server.jar /mcserver-build/mock-server.jar
COPY java /usr/local/bin/java

RUN chmod +x /resources/post-create.sh /resources/minecraft-wrapper.sh /mcserver-build/mock-server.jar /usr/local/bin/java

WORKDIR /mcserver
ENTRYPOINT exec /resources/post-create.sh
EOF

# Copy the actual wrapper script
cp /home/runner/work/open-mc-server-infrastructure/open-mc-server-infrastructure/resources/minecraft-wrapper.sh "$TEST_DIR/"

# Copy the actual post-create.sh (to get the latest changes)
cp /home/runner/work/open-mc-server-infrastructure/open-mc-server-infrastructure/resources/post-create.sh "$TEST_DIR/post-create-original.sh"

# Create a simplified post-create.sh for testing (using exec like the fix)
cat > "$TEST_DIR/post-create.sh" <<'EOF'
#!/bin/bash
set -euo pipefail

SERVER_DIR="/mcserver"
BUILD_DIR="/mcserver-build"

echo "[SERVER-SETUP] Starting test server setup..."

# Copy mock server
cp "$BUILD_DIR/mock-server.jar" "$SERVER_DIR/mock-server.jar"

# Accept EULA
echo "eula=true" > "$SERVER_DIR/eula.txt"

# Start server with wrapper - using exec to forward signals
echo "[SERVER-SETUP] Starting server with graceful shutdown wrapper..."
exec /resources/minecraft-wrapper.sh \
    "mock-server.jar" \
    "$SERVER_DIR" \
    "-Xmx1G"
EOF

# Create mock Minecraft server JAR
cat > "$TEST_DIR/mock-server.jar" <<'EOF'
#!/bin/bash
echo "[MOCK-SERVER] Mock Minecraft server starting..."
echo "[MOCK-SERVER] Server started on localhost:25565"
echo "[MOCK-SERVER] Ready for connections!"

# Simulate server reading commands from stdin
while read -r line; do
    echo "[MOCK-SERVER] Command received: '$line'"
    case "$line" in
        say*)
            # Extract the message after "say"
            message="${line#say }"
            echo "[MOCK-SERVER] Broadcasting to all players: $message"
            ;;
        "stop")
            echo "[MOCK-SERVER] Stopping server gracefully..."
            echo "[MOCK-SERVER] Saving world data..."
            echo "[MOCK-SERVER] Disabling plugins..."
            echo "[MOCK-SERVER] SimpleSkills: Saving player data..."
            sleep 2  # Simulate plugin save time
            echo "[MOCK-SERVER] SimpleSkills: Data saved successfully!"
            echo "[MOCK-SERVER] Server stopped."
            exit 0
            ;;
        *)
            echo "[MOCK-SERVER] Unknown command: $line"
            ;;
    esac
done

echo "[MOCK-SERVER] Input stream closed, stopping server..."
exit 0
EOF

chmod +x "$TEST_DIR/mock-server.jar"

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

chmod +x "$TEST_DIR/java"

# Build test Docker image
test_log "Building test Docker image..."
cd "$TEST_DIR"
if ! docker build -t test-mc-server-graceful . > /dev/null 2>&1; then
    test_error "Failed to build Docker image"
    exit 1
fi

test_success "Docker image built successfully"

# Start container
test_log "Starting test container..."
docker run -d --name test-mc-server-graceful test-mc-server-graceful

# Give it time to start
sleep 5

# Check if container is running
if ! docker ps | grep -q test-mc-server-graceful; then
    test_error "Container failed to start"
    docker logs test-mc-server-graceful
    exit 1
fi

test_success "Container started successfully"

# Verify server logs
test_log "Checking server startup logs..."
docker logs test-mc-server-graceful

# Test graceful shutdown
test_log "Testing graceful shutdown with docker stop (sends SIGTERM)..."
docker stop test-mc-server-graceful

# Get logs after shutdown
test_log "Checking shutdown logs..."
LOGS=$(docker logs test-mc-server-graceful 2>&1)
echo "$LOGS"

# Verify shutdown sequence
SHUTDOWN_TESTS=0
SHUTDOWN_PASSED=0

if echo "$LOGS" | grep -q "\[WRAPPER\] Received shutdown signal"; then
    test_success "✓ Shutdown signal received by wrapper"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ Shutdown signal not received by wrapper"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

if echo "$LOGS" | grep -q "\[WRAPPER\] Warning players of impending shutdown"; then
    test_success "✓ Wrapper initiated player warning sequence"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ Wrapper did not initiate player warnings"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

if echo "$LOGS" | grep -q "Server is shutting down in 30 seconds"; then
    test_success "✓ 30-second warning sent to players"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ 30-second warning not sent"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

if echo "$LOGS" | grep -q "Server is shutting down in 20 seconds"; then
    test_success "✓ 20-second warning sent to players"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ 20-second warning not sent"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

if echo "$LOGS" | grep -q "Server is shutting down in 10 seconds"; then
    test_success "✓ 10-second warning sent to players"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ 10-second warning not sent"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

if echo "$LOGS" | grep -q "Server is shutting down in 5 seconds"; then
    test_success "✓ 5-second warning sent to players"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ 5-second warning not sent"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

if echo "$LOGS" | grep -q "\[WRAPPER\] Sending 'stop' command to Minecraft server"; then
    test_success "✓ Stop command sent to server"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ Stop command not sent to server"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

if echo "$LOGS" | grep -q "\[MOCK-SERVER\] Stopping server gracefully"; then
    test_success "✓ Server received stop command"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ Server did not receive stop command"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

if echo "$LOGS" | grep -q "\[MOCK-SERVER\] SimpleSkills: Data saved successfully"; then
    test_success "✓ Plugin data saved during graceful shutdown"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ Plugin data not saved during shutdown"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

if echo "$LOGS" | grep -q "\[WRAPPER\] Server shutdown gracefully"; then
    test_success "✓ Wrapper confirmed graceful shutdown"
    SHUTDOWN_PASSED=$((SHUTDOWN_PASSED + 1))
else
    test_error "✗ Wrapper did not confirm graceful shutdown"
fi
SHUTDOWN_TESTS=$((SHUTDOWN_TESTS + 1))

# Final results
test_log "📊 Test Results:"
test_log "Shutdown tests passed: $SHUTDOWN_PASSED/$SHUTDOWN_TESTS"

if [ "$SHUTDOWN_PASSED" -eq "$SHUTDOWN_TESTS" ]; then
    test_success "🎉 All Docker graceful shutdown tests passed!"
    test_success "The wrapper correctly warns players and handles SIGTERM in Docker containers"
    exit 0
else
    test_error "❌ Some Docker graceful shutdown tests failed"
    test_error "The fix may not be working correctly in Docker environments"
    exit 1
fi
