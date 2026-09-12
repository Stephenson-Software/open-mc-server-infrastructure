# Minecraft Wrapper Service

A Spring Boot service that manages the Minecraft server lifecycle, providing graceful shutdown capabilities and REST API endpoints for server management.

## Overview

This module replaces the original `minecraft-wrapper.sh` bash script with a testable, maintainable Spring Boot application. It is integrated into the main Minecraft server container and provides:

- **Testable Logic**: All wrapper functionality is covered by unit and controller tests
- **REST API**: Exposes endpoints for server management, plugin deployment, and messaging
- **Service Integration**: Other services can interact with the wrapper via HTTP

## Features

- **Server Lifecycle Management**: Automatically starts and manages the Minecraft server process
- **Graceful Shutdown**: Warns players with countdown messages before shutting down
- **Alert Integration**: Sends alerts to the alert-manager service for server events (start, stop, crash)
- **REST API**: Exposes endpoints for server status, command execution, message sending, plugin deployment, and log/metrics retrieval
- **Unit & Controller Tests**: Comprehensive test coverage across service and controller layers

## Deployment

The Spring Boot wrapper is built as part of the main Minecraft server Docker image and runs inside the `minecraft-wrapper` container. It starts automatically when the container starts and manages the Minecraft server process.

## REST API Endpoints

### Server Management

- `GET /api/server/status` - Get current server status
  ```json
  {
    "running": true,
    "pid": 12345,
    "serverJar": "spigot-26.2.jar",
    "serverDirectory": "/mcserver"
  }
  ```

- `POST /api/server/start` - Start the Minecraft server
  ```bash
  curl -X POST http://localhost:8092/api/server/start
  ```

- `POST /api/server/stop` - Stop the Minecraft server gracefully
  ```bash
  curl -X POST http://localhost:8092/api/server/stop
  ```

- `POST /api/server/restart` - Restart the Minecraft server
  ```bash
  curl -X POST http://localhost:8092/api/server/restart
  ```

- `POST /api/server/command` - Send a command to the Minecraft server
  ```bash
  curl -X POST http://localhost:8092/api/server/command \
    -H "Content-Type: text/plain" \
    -d "say Hello, World!"
  ```

- `POST /api/server/shutdown` - Initiate graceful server shutdown (stops container)
  ```bash
  curl -X POST http://localhost:8092/api/server/shutdown
  ```

- `GET /api/server/logs` - Retrieve recent server log lines
  ```bash
  curl http://localhost:8092/api/server/logs
  ```

- `GET /api/server/metrics` - Retrieve server resource metrics (memory, TPS)
  ```bash
  curl http://localhost:8092/api/server/metrics
  ```

### Messaging

- `POST /api/messages` - Send a message to players
  ```bash
  curl -X POST http://localhost:8092/api/messages \
    -H "Content-Type: application/json" \
    -d '{"text": "Server maintenance in 5 minutes", "destination": "MINECRAFT"}'
  ```

  The wrapper does not deliver messages itself: it forwards them to the
  alert manager (`alert.manager.url`), which fans them out to Discord and to
  the game console over RCON. That forward is synchronous, and its outcome is
  what the response reports:

  | Status | Meaning |
  |---|---|
  | `200` | the alert manager accepted the message |
  | `400` | `text` was blank, or `destination` was not `MINECRAFT` or `DISCORD` |
  | `502` | the alert manager refused the message, or could not be reached — **nothing was delivered** |

  A `200` means the alert manager took responsibility for the message, not
  that a player saw it. Delivery to each destination is best-effort inside the
  alert manager: it logs an RCON or Discord failure and moves on to the next
  destination. `GET /api/alerts` on the alert manager shows what it received.

### Plugin Deployment

- `POST /api/plugins/deploy` - Deploy a plugin JAR to the server's `plugins/` directory.
  Used by the [`deploy-plugin.yml`](../docs/github-actions/deploy-plugin.yml)
  GitHub Actions workflow to ship plugin builds from CI to the server. See the
  [`PluginDeployController`](src/main/java/com/openmc/minecraftwrapper/controller/PluginDeployController.java)
  source for the request schema and authentication requirements.

## Configuration

The service is configured via environment variables or `application.properties`:

```properties
# Server port
server.port=8092

# Minecraft server configuration
minecraft.server.jar=/mcserver/spigot-26.2.jar
minecraft.server.directory=/mcserver
minecraft.java.opts=-Xmx2G -Xms1G
minecraft.auto.start=true
minecraft.auto.restart=false

# Alert manager configuration
alert.manager.url=http://alert-manager:8090/api/alerts
alerts.server.start=true
alerts.server.stop=true
alerts.server.crash=true

# Usage reporting (one "startup" event per start; see the root README)
usage-reporting.enabled=true
usage-reporting.endpoint=https://trace.danielstephenson.dev
usage-reporting.tags=
```

### Environment Variables

- `MINECRAFT_SERVER_JAR`: Path to the Minecraft server JAR file (default: `/mcserver/spigot-26.2.jar`)
- `MINECRAFT_SERVER_DIRECTORY`: Directory where the server runs (default: `/mcserver`)
- `JAVA_OPTS`: Java options for the server (default: `-Xmx2G -Xms1G`)
- `MINECRAFT_AUTO_START`: Auto-start server on wrapper startup (default: `true`)
- `MINECRAFT_AUTO_RESTART`: Auto-restart server if it crashes (default: `false`)
- `ALERT_MANAGER_URL`: URL for alert-manager API (default: `http://alert-manager:8090/api/alerts`)
- `ALERTS_SERVER_START`: Enable server start alerts (default: `true`)
- `ALERTS_SERVER_STOP`: Enable server stop alerts (default: `true`)
- `ALERTS_SERVER_CRASH`: Enable server crash alerts (default: `true`)
- `USAGE_REPORTING_ENABLED`: Send one `startup` event (program name, version and `USAGE_REPORTING_TAGS` only) to the trace usage service once the wrapper is up. Logged as one `INFO` line on every start; set to `false` to turn it off (default: `true`)
- `USAGE_REPORTING_ENDPOINT`: Where that event goes (default: `https://trace.danielstephenson.dev`)
- `USAGE_REPORTING_KEY`: Program key (default: the project's bundled write-only key)
- `USAGE_REPORTING_TAGS`: Comma-separated `k=v` pairs attached to the event, e.g. `ci=true`; `version` is reserved (default: empty)

## Building

```bash
./gradlew build
```

## Running Tests

```bash
./gradlew test
```

Tests are split across the service and controller layers — run `./gradlew test`
to see the current count and breakdown. The current suite covers, at minimum:

- Service layer: `AlertServiceTest`, `MessageServiceTest`, `ShutdownServiceTest`,
  `PluginDeployServiceTest`, `UsageReportingServiceTest` (against a stub trace
  server on a loopback port — no test ever reaches the real service, and the
  Gradle `test` task sets `USAGE_REPORTING_ENABLED=false` for every other test)
- Vendored trace client: `TraceClientTest`
- Controller layer: `ServerControllerTest`, `MessageControllerTest`,
  `PluginDeployControllerTest`
- Application bootstrapping: `MinecraftWrapperApplicationTest`

## Integration with Infrastructure

The wrapper service is built and deployed as part of the main Minecraft server container. It:

1. **Integrates with Alert Manager**: Sends lifecycle alerts (start, stop, crash) via REST API
2. **Manages Minecraft Server**: Controls the server process and sends commands via FIFO
3. **Provides REST API**: Exposes endpoints for external management on port 8092
4. **Runs inside minecraft-wrapper container**: Accessible as the `minecraft-wrapper` service in Docker Compose

## Advantages of Spring Boot Implementation

The Spring Boot wrapper replaces the previous bash script with these benefits:

- ✅ **Unit tested**: service and controller layers covered by JUnit tests
- ✅ **REST API**: Remote management capabilities
- ✅ **Better error handling**: Structured logging and exception handling
- ✅ **Maintainable**: Easier to extend and modify
- ✅ **Type-safe**: Configuration via Spring properties
- ✅ **Spring Boot ecosystem**: Actuator, health checks, metrics
