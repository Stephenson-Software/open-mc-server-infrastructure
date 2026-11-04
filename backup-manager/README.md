# Backup Manager

An automated backup management system for the Minecraft server infrastructure. This Spring Boot application runs as a separate container and manages scheduled backups and cleanup.

## Features

- **Scheduled Backups**: Automatically creates backups of the Minecraft server once a day (default: 2 AM)
- **Size Management**: Monitors backup directory size and removes oldest backups when exceeding limit
- **Filesystem Integration**: Uses `tar` directly on the mounted `/mcserver` directory to create compressed tar.gz backups — no Docker CLI or Docker socket required
- **Alert Integration**: Sends notifications to the alert-manager for backup success and failures
- **Configurable**: Customize backup schedule, size limits, and paths via environment variables
- **Containerized**: Runs in its own Docker container; the Minecraft server data is mounted read-only at `/mcserver`

## Configuration

### Environment Variables

The following environment variables can be configured in `.env`:

- `BACKUP_CONTAINER_NAME`: Container name (default: `open-mc-backup-manager`)
- `BACKUP_MAX_SIZE_MB`: Maximum size of backups directory in MB (default: `10240` = 10GB)
- `BACKUP_SCHEDULE`: Cron expression for backup schedule (default: `0 0 2 * * ?` = 2 AM daily). Set to `-` (Spring's `Scheduled.CRON_DISABLED` marker) to disable scheduled backups; the manual `POST /api/backups/trigger` endpoint is unaffected.
- `SOURCE_DIRECTORY`: Path to the mounted Minecraft server data directory (default: `/mcserver`)
- `ALERTS_BACKUP_SUCCESS`: Enable alerts for successful backups (default: `true`)
- `ALERTS_BACKUP_FAILURE`: Enable alerts for failed backups (default: `true`)

### Cron Expression Format

The backup schedule uses standard cron format:
```
second minute hour day-of-month month day-of-week
```

Examples:
- `0 0 2 * * ?` - 2 AM every day (default)
- `0 0 */6 * * ?` - Every 6 hours
- `0 30 1 * * ?` - 1:30 AM every day
- `0 0 0 * * SUN` - Midnight every Sunday

## How It Works

1. **Scheduled Execution**: The backup manager uses Spring's `@Scheduled` annotation to trigger backups
2. **Filesystem Backup**: Runs `tar czf` directly on the mounted source directory (`/mcserver`) to create a compressed tar.gz backup
3. **Size Monitoring**: After each backup, checks the total size of the backups directory
4. **Cleanup**: If the directory exceeds the size limit, removes oldest backups first until under limit
5. **Alerts**: Sends notifications to the alert-manager for both successful and failed backup operations

## Building

Build the backup-manager application:

```bash
cd backup-manager
./gradlew clean build
```

## Testing

Run tests:

```bash
./gradlew test
```

## Running

The backup-manager is automatically started with the rest of the infrastructure:

```bash
cd ..
./up.sh
```

### Viewing Logs

To view backup-manager logs:

```bash
docker logs -f open-mc-backup-manager
```

Or use your custom container name:

```bash
docker logs -f ${BACKUP_CONTAINER_NAME}
```

## Manual Backup Trigger

### Option 1: Using the Trigger Script (Recommended)

The easiest way to trigger a manual backup is using the included script:

```bash
./trigger-backup.sh
```

This script calls the backup-manager REST API to initiate an immediate backup. The backup will be created and old backups will be cleaned up according to size limits.

### Option 2: Using the REST API Directly

You can also trigger a backup using curl or any HTTP client:

```bash
curl -X POST http://localhost:8091/api/backups/trigger
```

**Response (success):**
```json
{
  "success": true,
  "message": "Backup completed successfully",
  "backupPath": "/backups/backup-20241211-120000"
}
```

**Response (failure):**
```json
{
  "success": false,
  "message": "Backup failed: Source directory '/mcserver' is unavailable (missing, empty, or unreadable)."
}
```

Restarting the container does not create a backup: backups only run on the `BACKUP_SCHEDULE` cron or through the trigger endpoint.

## REST API

The backup-manager exposes a REST API on port 8091 (configurable via `BACKUP_PORT`).

### Endpoints

**POST /api/backups/trigger**
- Triggers an immediate backup
- Returns JSON response with backup status and location
- HTTP 200 on success, 500 on failure

**GET /api/backups/latest**
- Returns metadata about the most recent backup. Always HTTP 200; when no
  backup has been performed yet the response body is
  `{"available": false, "message": "No backup has been performed yet"}`.

## Volume Mounts

The backup-manager container has access to:

- `/mcserver` - Read-only access to the Minecraft server data (mounted from the mcserver PVC or named volume)
- `/backups` - Read-write access to the backups directory on the host

## Security Notes

- The Minecraft server volume is mounted read-only for safety
- The REST API listens on port 8091 inside the container. By default Docker
  Compose publishes it as `${BACKUP_PORT:-8091}:8091` with no bind address,
  which means **all host interfaces** — not just localhost. If you don't need
  external access to the backup API, restrict the binding (e.g.
  `"127.0.0.1:${BACKUP_PORT:-8091}:8091"`) or block the port at your firewall.

## Troubleshooting

### Backups Not Running

1. Check container logs: `docker logs open-mc-backup-manager`
2. Verify the cron expression is correct
3. Ensure the container is running: `docker ps | grep backup-manager`

### Size Limit Not Enforced

1. Check the `BACKUP_MAX_SIZE_MB` setting in `.env`
2. Verify backups are being created with correct naming pattern (`backup-*`)
3. Check container logs for cleanup messages

### Backup Creation Fails

1. Verify the Minecraft server volume/PVC is mounted at `/mcserver` and contains data
2. Ensure the container has read access to `/mcserver` (check volume mount permissions)
3. Ensure sufficient disk space for backups

