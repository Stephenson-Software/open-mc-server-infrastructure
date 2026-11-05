# Minecraft Server Upgrade Guide

This guide provides a comprehensive process for upgrading your Minecraft server to a newer version while ensuring data safety and the ability to rollback if needed.

## Table of Contents

- [Before You Begin](#before-you-begin)
- [Upgrade Process](#upgrade-process)
- [Rollback Procedure](#rollback-procedure)
- [Post-Upgrade Verification](#post-upgrade-verification)
- [Troubleshooting](#troubleshooting)

## Before You Begin

### Important Notes

⚠️ **Critical**: Always backup your server data before performing an upgrade. This allows you to restore your server if the upgrade fails or causes issues.

⚠️ **Compatibility**: Check that your installed plugins are compatible with the new Minecraft version. Incompatible plugins may cause server crashes or data corruption.

⚠️ **Downtime**: The upgrade process requires server downtime. Plan the upgrade during a maintenance window and notify your players in advance.

### Prerequisites

- Docker and Docker Compose installed and running
- Access to the server host machine
- Sufficient disk space for backups (at least 2x your current world size)
- Knowledge of the target Minecraft version you want to upgrade to
- A working JDK if you intend to rebuild any Spring Boot modules locally
  (`web-app`, `minecraft-wrapper`, `agent-manager`, etc.) outside of Docker.
  The service containers ship with a **Java 21 runtime**
  (`eclipse-temurin:21-jre`); the main Minecraft server image is based on
  `eclipse-temurin:25.0.3_9-jdk-noble` and builds Spigot with the JDK that
  `resources/java-for-minecraft.sh` picks for `MINECRAFT_VERSION` (17, 21 or
  25). If you only edit the `.env` file
  and rebuild via `./upgrade.sh` or `docker compose build`, no host JDK is
  required — Docker handles everything.

## Upgrade Process

### Automated Upgrade (Recommended)

For a streamlined upgrade experience, use the automated upgrade script that handles all steps:

```bash
./upgrade.sh
```

The script will:
1. ✅ Prompt for the new Minecraft version and ask for confirmation
2. ✅ Reuse the newest complete backup, or create one if none exists
3. ✅ Stop the server gracefully
4. ✅ Update the `.env` file
5. ✅ Rebuild the Docker image with the new version
6. ✅ Start the server, watch the logs for a successful startup, and show recent logs

**Note**: The newest complete backup is reused however old it is. If the last backup is not recent enough to fall back on, run `./trigger-backup.sh` before `./upgrade.sh`.

**Benefits:**
- Single command execution
- Automatic backup management
- Interactive prompts with confirmation
- Progress feedback at each step
- Summary with backup location

**Example usage:**
```bash
./upgrade.sh
# When prompted, enter the new version (e.g., 26.2)
# Confirm the upgrade when asked
# Script handles the rest automatically
```

**Preview a planned upgrade with `--dry-run`:**

Before committing to an upgrade, `--dry-run` shows what the script would do
without stopping the server, touching `.env`, or creating a backup:

```bash
./upgrade.sh --dry-run
# or, non-interactively:
./upgrade.sh --dry-run 26.2
```

This prints the current version, target version, current world data size,
and where the backup would be stored (the newest complete backup, or a new
one the script would create if there is none).

If you prefer manual control or need to understand each step, continue with the manual process below.

---

### Manual Upgrade Process

### Step 1: Stop the Server

First, gracefully stop the Minecraft server to ensure all data is properly saved:

```bash
./down.sh
```

Or using Docker Compose directly:

```bash
docker compose down
```

**Important**: The server includes graceful shutdown handling that automatically warns players and saves all data before stopping. Players will receive countdown warnings before the shutdown completes.

### Step 2: Backup Server Files

Create a backup of all server data from the persistent volume. This is your safety net in case something goes wrong.

#### Option A: Using Backup Manager (Recommended)

The backup-manager service automatically creates scheduled backups (default: 2 AM daily) into the `backups` Docker volume (a named volume, not a host directory — see `BACKUPS_VOLUME_NAME` in `.env`). Before upgrading, ensure a recent backup exists:

```bash
# List backups in the named volume (also shown by `./rollback.sh` with no args)
docker run --rm -v backups:/backups:ro alpine sh -c 'ls -lth /backups'

# If no recent backup, trigger one manually. Step 1 stopped backup-manager too,
# so start it first; this leaves the Minecraft server stopped.
docker compose up -d backup-manager
./trigger-backup.sh
docker compose down
```

**Benefits:**
- Automatic timestamping
- Integrated with alert system
- Compressed tarball format
- Size verification
- Built-in restoration instructions

#### Option B: Manual Backup to Local Directory

```bash
# Create a backup directory with timestamp
mkdir -p ./backups
BACKUP_DIR="./backups/backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"

# Copy server data from the container volume
docker run --rm \
  -v mcserver:/mcserver:ro \
  -v "$(pwd)/$BACKUP_DIR":/backup \
  ubuntu \
  tar czf /backup/mcserver-backup.tar.gz -C /mcserver .

echo "Backup created at: $BACKUP_DIR/mcserver-backup.tar.gz"
```

#### Option C: Quick Backup via Docker CP

```bash
# Create backup directory
BACKUP_DIR="./backups/backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"

# Start a temporary container to access the volume
docker run -d --name mcserver-backup \
  -v mcserver:/mcserver:ro \
  ubuntu sleep 300

# Copy the data
docker cp mcserver-backup:/mcserver "$BACKUP_DIR/"

# Cleanup temporary container
docker rm -f mcserver-backup
```

#### Option D: Using Deposit Box

```bash
# Copy world and important files to deposit box
docker run --rm \
  -v mcserver:/mcserver:ro \
  -v "$(pwd)/deposit-box":/deposit-box \
  ubuntu \
  bash -c "cp -r /mcserver/world /deposit-box/ && \
           cp -r /mcserver/plugins /deposit-box/ && \
           cp /mcserver/ops.json /deposit-box/ 2>/dev/null || true && \
           cp /mcserver/whitelist.json /deposit-box/ 2>/dev/null || true"
```

**Verify your backup** before proceeding:

```bash
# For Option A (backup script) — backups live in the named "backups" volume
docker run --rm -v backups:/backups:ro alpine sh -c 'ls -lh /backups/backup-*/mcserver-backup.tar.gz'

# For Option B (manual backup)
ls -lh "$BACKUP_DIR/mcserver-backup.tar.gz"

# For Option C (docker cp)
du -sh "$BACKUP_DIR/"
```

### Step 3: Update Minecraft Version

Edit your `.env` file to specify the new Minecraft version:

```bash
# Open .env in your preferred editor
nano .env
# or
vim .env
```

Update the `MINECRAFT_VERSION` variable:

```bash
# Change from (example):
MINECRAFT_VERSION=26.1

# To your target version (example):
MINECRAFT_VERSION=26.2
```

**Note**: The version must match an available Spigot build. Check [Spigot BuildTools](https://www.spigotmc.org/wiki/buildtools/) for supported versions.

**Important**: The Dockerfile now uses a build argument that automatically reads the `MINECRAFT_VERSION` from your `.env` file, so you only need to update the version in one place!

### Step 4: Rebuild Docker Image

Rebuild the Docker image with the new Minecraft version. This process will:
- Download and compile the new Spigot version
- Create a new server JAR file
- Take 10-15 minutes depending on your system

```bash
docker compose build --no-cache
```

**Note**: The `--no-cache` flag ensures a clean build without using old cached layers.

### Step 5: Replace Server JAR in Persistent Volume

The new server JAR needs to be placed in the persistent volume. The `post-create.sh` script handles this automatically based on the `OVERWRITE_EXISTING_SERVER` setting.

#### Option A: Preserve Existing World (Recommended)

If you want to keep your existing world and only update the server JAR:

```bash
# The server setup script will detect the new version and copy only the JAR
# Your world data remains intact
./up.sh
```

The `setup_server()` function in `post-create.sh` will:
- Detect existing server files
- Copy the new JAR if the version changed
- Preserve your world data, plugins, and configurations

#### Option B: Fresh Server Setup

If you want to completely reset the server (⚠️ This will delete your world):

```bash
# Edit .env to enable overwrite
echo "OVERWRITE_EXISTING_SERVER=true" >> .env

# Start the server (this will reset everything)
./up.sh

# After the server starts, disable overwrite for future restarts
sed -i 's/OVERWRITE_EXISTING_SERVER=true/OVERWRITE_EXISTING_SERVER=false/' .env
```

### Step 6: Start and Monitor the Server

Start the server and monitor the logs to ensure successful startup:

```bash
# Start the server
./up.sh

# Monitor server logs in real-time
docker logs -f open-mc-server
```

**Look for these indicators of successful startup:**
- `[SERVER-SETUP] Starting server with Spring Boot wrapper...`
- `Done (X.XXXs)! For help, type "help"`
- No errors about incompatible world format
- Plugins loading successfully

Press `Ctrl+C` to stop following the logs (server continues running).

## Rollback Procedure

If the upgrade fails or causes issues, you can restore your server from the backup.

### Automated Rollback (Recommended)

`./rollback.sh` automates restoring a backup-manager backup (Option A above)
from the named `backups` volume: it stops the server, replaces the contents
of the `mcserver` volume with the chosen backup, and starts the server again.

```bash
# List available backups (name, size, complete/incomplete)
./rollback.sh

# Restore a specific backup — the "backup-" prefix is optional
./rollback.sh 20260115-020000
```

You'll be asked to type `yes` to confirm before anything is overwritten. Use
the manual procedure below for Option B/C/D backups (which live in a local
directory rather than the `backups` volume) or if you need finer control.

### Manual Rollback

### Step 1: Stop the Server

```bash
./down.sh
```

### Step 2: Remove Current Server Volume

**Warning**: This will delete the current server data. Make sure you have a backup!

```bash
# Remove the volume
docker volume rm mcserver
```

### Step 3: Restore from Backup

#### If you used Option A (backup-manager, named volume):

```bash
# Specify your backup directory name (see: docker run --rm -v backups:/backups:ro alpine ls /backups)
BACKUP_NAME="backup-YYYYMMDD-HHMMSS"

# Restore the data directly from the "backups" volume into the "mcserver" volume.
# Step 2 removed the volume, so this command recreates it owned by root: extract
# as root, then hand it to the server's user (UID 1000).
docker run --rm \
  -v backups:/backups:ro \
  -v mcserver:/mcserver \
  alpine sh -c "tar xzf /backups/${BACKUP_NAME}/mcserver-backup.tar.gz -C /mcserver && chown -R 1000:1000 /mcserver"
```

#### If you used Option B (tarball in a local directory):

```bash
# Specify your backup directory
BACKUP_DIR="./backups/backup-YYYYMMDD-HHMMSS"

# Extract the tarball into the volume, then hand it to the server's user (UID 1000)
docker run --rm \
  -v mcserver:/mcserver \
  -v "$(pwd)/$BACKUP_DIR":/backup:ro \
  ubuntu \
  sh -c "tar xzf /backup/mcserver-backup.tar.gz -C /mcserver && chown -R 1000:1000 /mcserver"
```

#### If you used Option C (directory copy):

```bash
# Specify your backup directory
BACKUP_DIR="./backups/backup-YYYYMMDD-HHMMSS"

# Start a temporary container
docker run -d --name mcserver-restore \
  -v mcserver:/mcserver \
  ubuntu sleep 300

# Copy the data back. docker cp creates the files as root, so hand them to the
# server's user (UID 1000) afterward.
docker cp "$BACKUP_DIR/mcserver/." mcserver-restore:/mcserver/
docker exec mcserver-restore chown -R 1000:1000 /mcserver

# Cleanup
docker rm -f mcserver-restore
```

### Step 4: Revert Configuration

Restore your previous `MINECRAFT_VERSION` in `.env`:

```bash
# Edit .env to restore old MINECRAFT_VERSION
nano .env
```

### Step 5: Rebuild and Restart

```bash
# Rebuild with the old version
docker compose build --no-cache

# Start the server
./up.sh
```

## Post-Upgrade Verification

After upgrading, verify that everything is working correctly:

### 1. Check Server Status

```bash
# Verify the container is running
docker ps | grep open-mc-server

# Check server logs
docker logs open-mc-server --tail 50
```

### 2. Test Server Connection

- Connect to the server using your Minecraft client
- Verify the server version matches your upgrade target
- Check the F3 debug screen (press F3) to see the server version

### 3. Verify World Data

- Check that your world loaded correctly
- Verify builds and structures are intact
- Test chunk loading and generation

### 4. Check Plugins

```bash
# Run the 'plugins' command through the wrapper's REST API (WRAPPER_PORT, default 8092)
curl -X POST http://localhost:8092/api/server/command \
  -H "Content-Type: text/plain" \
  -d "plugins"

# The command's output goes to the server logs
docker logs open-mc-server | grep -i plugin
```

Verify that:
- All expected plugins are loaded
- No plugin errors in the logs
- Plugin functionality works in-game

### 5. Test Core Functionality

- Player movement and interaction
- Block breaking and placing
- Inventory management
- Commands and permissions
- Chat and multiplayer features

### 6. Monitor Performance

```bash
# Check resource usage
docker stats open-mc-server

# Watch for errors
docker logs -f open-mc-server | grep -i error
```

## Troubleshooting

### Server Fails to Start After Upgrade

**Symptoms**: Container stops immediately or crashes on startup

**Solutions**:
1. Check the logs for specific errors:
   ```bash
   docker logs open-mc-server
   ```
2. Common issues:
   - Incompatible world format: Rollback to previous version
   - Incompatible plugins: Remove or update plugins
   - Insufficient memory: Adjust `JAVA_OPTS` in environment

### "Outdated Server" or "Outdated Client" Error

**Symptoms**: Players cannot connect, version mismatch errors

**Solutions**:
1. Verify the server is running the correct version:
   ```bash
   docker logs open-mc-server | grep "Starting minecraft server version"
   ```
2. Ensure `MINECRAFT_VERSION` in `.env` is the version you expect
3. Rebuild the image if the versions don't match:
   ```bash
   docker compose build --no-cache
   ```

### World Data Missing or Corrupted

**Symptoms**: Empty world, missing builds, or world won't load

**Solutions**:
1. Immediately stop the server:
   ```bash
   ./down.sh
   ```
2. Follow the [Rollback Procedure](#rollback-procedure)
3. Do not start the server until the rollback is complete

### Plugins Not Loading

**Symptoms**: Plugin features not working, plugin errors in logs

**Solutions**:
1. Check plugin compatibility with the new Minecraft version
2. Update plugins to compatible versions:
   ```bash
   # Copy updated plugin JARs to deposit-box
   docker exec open-mc-server bash -c "cp /deposit-box/*.jar /mcserver/plugins/"
   docker compose restart
   ```
3. Remove incompatible plugins and restart

### Performance Degradation

**Symptoms**: Lag, low TPS (ticks per second), high CPU/memory usage

**Solutions**:
1. Check resource usage:
   ```bash
   docker stats open-mc-server
   ```
2. Adjust memory allocation in your environment or `compose.yml`:
   ```yaml
   environment:
     - JAVA_OPTS=-Xmx4G -Xms2G  # Increase memory
   ```
3. Optimize server properties (view-distance, simulation-distance)
4. Update or remove resource-intensive plugins

### Docker Volume Issues

**Symptoms**: "Cannot start service" or volume mount errors

**Solutions**:
1. Verify the volume exists:
   ```bash
   docker volume ls | grep mcserver
   ```
2. Inspect volume details:
   ```bash
   docker volume inspect mcserver
   ```
3. If corrupted, restore from backup (see [Rollback Procedure](#rollback-procedure))

## Additional Resources

- [Spigot BuildTools Documentation](https://www.spigotmc.org/wiki/buildtools/)
- [Minecraft Version History](https://minecraft.fandom.com/wiki/Java_Edition_version_history)
- [Plugin Compatibility Checker](https://www.spigotmc.org/)
- [Server Configuration Guide](./README.md#configuration)

## Support

If you encounter issues not covered in this guide:

1. Check the [main README troubleshooting section](./README.md#troubleshooting)
2. Review server logs for specific error messages
3. Submit an issue on the [GitHub repository](https://github.com/Stephenson-Software/open-mc-server-infrastructure/issues)

---

**Remember**: Always backup before upgrading, test in a non-production environment when possible, and have a rollback plan ready.
