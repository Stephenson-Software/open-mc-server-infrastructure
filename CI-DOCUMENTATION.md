# CI Pipeline Documentation

This document details the automated checks performed by the CI/CD pipeline to ensure code quality, security, and functionality.

## Overview

The CI pipeline runs automatically on:
- Every push to `main` and `develop` branches
- Every pull request to `main`

## CI Jobs

The pipeline (`.github/workflows/ci.yml`) is composed of several parallel jobs.
The end-to-end server run lives in a separate workflow
(`.github/workflows/test-server-run.yml`).

### 1. Validate Code and Configuration (`validate`)

The main per-PR validation job. It runs the following steps in order:

#### Shell Script Validation
- **Syntax Checking** (`bash -n`): `up.sh`, `down.sh`, `upgrade.sh`,
  `rollback.sh`, `trigger-backup.sh`, `resources/post-create.sh`.
- **ShellCheck Linting**: the same six scripts plus everything under
  `scripts/*.sh`.

#### Docker Configuration Validation
- **Dockerfile Validation**: verifies the root `Dockerfile` exists and contains
  a `FROM` directive, then builds the `base` stage to confirm syntax.
- **Docker Compose Validation**: writes a temporary `.env` with placeholder
  values, then runs `docker compose config` to verify the compose file parses
  and every service/volume reference resolves.

#### Environment Configuration Validation
Asserts `sample.env` declares each of these keys (`grep -q KEY=`):
`MINECRAFT_VERSION`, `OPERATOR_UUID`, `OPERATOR_NAME`, `SERVER_MOTD`.

#### Java Module Builds and Tests
Sets up JDK 21 (Temurin), then runs `./gradlew build` and `./gradlew test`
for each Spring Boot module that has its own Gradle project:
- `web-app`
- `minecraft-wrapper`
- `agent-manager`
- `alert-manager`
- `backup-manager`

`scripts/ci-local.sh` runs `./gradlew test` for this same list — keep the two
in sync when a module is added or removed.

#### Documentation Validation
- Confirms `README.md` exists and contains the expected H1
  (`# Open Minecraft Server Infrastructure`).
- Confirms `LICENSE` is present.

#### Helm Chart Linting
- Sets up Helm and runs `helm lint helm/omcsi --set secrets.rconPassword=ci
  --set secrets.adminPassword=ci` against the chart. The two `--set` values are
  required: `templates/secret.yaml` wraps them in `required` and they default to
  `""` in `values.yaml`, so lint fails without them.

### 2. Helm Unit Tests (`helm-unit-test`)

Includes `tests/rbac_test.yaml`, which guards the property that makes the viewer
role worth having: it asserts that the viewer Role contains no `secrets` and no
`pods/exec` rule. A role that quietly gains either stops being safe to hand out,
and nothing else would notice.


A separate job that installs the
[`helm-unittest`](https://github.com/helm-unittest/helm-unittest) plugin and
runs `helm unittest helm/omcsi`. The test suites live under
`helm/omcsi/tests/` and cover NetworkPolicies, PodDisruptionBudgets,
ServiceMonitors, security contexts, and per-service Deployment/Service
templates.

### 3. Helm Deploy Smoke Test (`helm-deploy-test`)

Spins up a `kind` cluster, installs the chart with minimal required values,
and verifies the release creates the expected Kubernetes objects and key
deployment fields. Because CI does not build/load the service images into
`kind` and installs with `--wait=false`, this job does **not** prove the
workloads become Ready/healthy; it catches install-time/template regressions
that lint and unit tests miss (for example selector mismatches, missing PVCs,
or probe/init-container wiring mistakes).

### 4. Terraform Validate (`terraform-validate`)

Runs `terraform fmt -check`, `terraform init -backend=false`, and
`terraform validate` for each Terraform stack under `terraform/`
(`aws`, `linode`, `existing-cluster`, and `hetzner`) to catch
formatting issues, syntax errors, and provider errors before they
hit a real deployment.

### 5. Security Scanning (`security-scan`)

Performs security vulnerability scanning using Trivy:

- **Vulnerability Detection**: Scans entire repository for known security vulnerabilities
- **SARIF Report Generation**: Creates standardized security report format
- **GitHub Security Integration**: Uploads results to GitHub Security tab for review
- **Non-blocking**: Continues pipeline execution even if vulnerabilities are found (informational)

### 6. nginx Configuration Test (`nginx-config-test`)

Builds the `nginx/` image and runs `scripts/test-nginx-bluemap-route.sh`
against it. The script asks nginx for the configuration it actually resolves
(`nginx -T`, includes and all) in four states — BlueMap disabled, enabled at the
default path, enabled at a custom path, and disabled again after having been
enabled — and asserts the resulting routes.

This is the only job that builds or runs the nginx image, so it is what catches
a `location` block that does not parse, an `include` pointing at a path the
Dockerfile does not create, a fragment whose nginx variables were expanded away
during generation, and a route that survives its feature flag being turned back
off. The Kubernetes side of the same routes is covered separately by
`helm/omcsi/tests/nginx_test.yaml` in the Helm Unit Tests job.

### 7. Secret Reference Test (`secret-reference-test`)

Runs `scripts/test-secret-references.sh` against `scripts/resolve-secrets.sh`.

Two properties are worth protecting here. A literal value must never be mistaken
for a reference — these are password fields, and a wrong guess is a silent
misconfiguration rather than a visible error, which is why the `!ref:` sigil
exists and why the suite asserts that a value beginning `file:` stays a literal.
And a failed resolution must neither emit a partial environment nor echo a
secret: one case deliberately runs a command that writes a secret to stderr and
exits non-zero, and asserts that secret appears in neither stdout nor the error
message.

The remaining cases cover the three schemes, quoted values, values containing
single quotes surviving a round trip through `source`, and argument validation.

### 8. Server JAR Replacement Test (`jar-replacement-test`)

Runs `scripts/test-jar-replacement.sh`, which extracts `setup_server()` from
`resources/post-create.sh` and exercises it against fixture directories.

The behaviour being protected is that **an existing server JAR is never removed
until a verified replacement has been staged**. The upgrade branch previously
deleted the running JAR and then copied the replacement without checking, so a
`MINECRAFT_VERSION` ahead of its image tag left a persistent volume holding a
world, plugins, and nothing to run them with — recoverable only by hand.

Four cases are covered: a version the image does not carry (which must fail and
change nothing), a genuine upgrade, a version that already matches, and a fresh
server directory.

No other job reaches this code path. Every other job starts from an empty server
directory, which takes the first branch of `setup_server()` and never touches
JAR replacement at all.

### 9. Python Client Tests (`python-client-test`)

Installs `clients/python` with `pip install -e` and runs its `unittest` suite
on a matrix of Python 3.9 and 3.13 — the floor and the ceiling of the
`requires-python` range the package advertises.

Two details are deliberate:

- The package is **installed** rather than run off the source tree, so a broken
  `pyproject.toml`, a missing `py.typed`, or a package that does not actually
  ship its modules fails here rather than at release time.
- A final step asserts the installed distribution declares **no runtime
  dependencies**. That is the client's whole selling point, and CI installs
  into a fresh environment where an accidental dependency would resolve
  silently.

The tests themselves need no OMCSI deployment: they stand up a real
`http.server` on localhost and point the client at it, so the transport —
headers, multipart framing, status handling, timeouts — is exercised for real
rather than mocked.

### 10. End-to-End Server Run (`test-server-run.yml`)

A separate workflow that performs end-to-end testing by actually running
the Minecraft server in a containerized environment.

#### Server Build and Startup
- **Docker Image Build**: Builds the complete Minecraft server Docker image with Spigot
- **Server Initialization**: Starts the server with test configuration and waits for full initialization
- **Startup Verification**: Monitors server logs to confirm successful startup sequence completion

#### Functionality Testing
- **Port Verification**: Confirms server is listening on Minecraft port (25565) and RCON port (25575)
- **File System Check**: Verifies critical server files are created (server.properties, world directory, etc.)
- **Container Health**: Validates the Docker container is running properly

#### Graceful Shutdown Testing
- **Shutdown Sequence**: Tests proper server shutdown using Docker Compose
- **Log Validation**: Confirms graceful shutdown signals are processed correctly
- **Resource Cleanup**: Ensures all resources are properly cleaned up after testing

This workflow provides real-world verification that the server can actually
start, run, and stop correctly in a production-like environment.

## Local Testing

You can run the same validation checks locally using:

```bash
./scripts/ci-local.sh
```

This script mirrors the CI pipeline checks and helps catch issues before submitting changes.
It validates the compose file against a `mktemp` copy of `sample.env` rather than your
`.env`, so your real credentials are left untouched. Because it now runs the test suite
for all five Gradle modules, expect it to take several minutes on a cold Gradle cache.
It also runs the Python client's test suite, off `PYTHONPATH=src` rather than
installing the package, so a local run never writes to your Python environment.

Alongside those, it runs ShellCheck, `helm lint`, `helm unittest`,
`scripts/test-nginx-bluemap-route.sh`, and
`terraform fmt -check`/`init -backend=false`/`validate` for the `linode`, `aws`,
`existing-cluster`, and `hetzner` targets. ShellCheck, Helm, the helm-unittest
plugin, Terraform, and a reachable Docker daemon are all optional: a check whose
tool is unavailable is skipped with a warning and repeated in the summary
printed at the end of the run. Skipped checks are still enforced by CI, so a
local run with skips is a weaker signal than a green pipeline.

## What Gets Checked

### ✅ Code Quality
- Shell script syntax (`bash -n`)
- ShellCheck linting compliance
- Java module compilation and unit tests for `web-app`,
  `minecraft-wrapper`, `agent-manager`, `alert-manager`, and `backup-manager`
- Python client installs from `clients/python` and passes its unit tests on
  Python 3.9 and 3.13, with no runtime dependencies

### ✅ Configuration Integrity
- Dockerfile parses and the `base` stage builds
- `docker compose config` resolves the full stack
- `sample.env` declares all required keys
- Terraform stacks pass `terraform validate`
- The nginx image builds and the routes it resolves match the ones its
  environment variables ask for

### ✅ Kubernetes / Helm
- `helm lint` against `helm/omcsi`
- `helm unittest` covering NetworkPolicies, PDBs, ServiceMonitors,
  security contexts, and per-service templates
- Smoke deploy of the chart into a `kind` cluster

### ✅ End-to-End (separate workflow)
- Real Spigot build + server boot
- Port availability (25565, 25575) and file-system initialization
- Graceful shutdown via Compose

### ✅ Security Assessment
- Trivy vulnerability scanning
- Results uploaded to the GitHub Security tab

### ✅ Documentation Standards
- `README.md` exists with the expected H1
- `LICENSE` is present

## CI/CD Plugin Deployment

The `minecraft-wrapper` exposes a secure HTTP endpoint that allows automated deployment of Spigot/Paper plugin JARs from GitHub Actions directly into a running omcsi instance.

### Endpoint

```
POST /api/plugins/deploy
Authorization: Bearer <DEPLOY_AUTH_TOKEN>
Content-Type: multipart/form-data

pluginName=<existing-plugin-filename.jar>
file=@<path-to-new-jar>
```

- **`pluginName`** – The filename of the plugin JAR already installed on the server (e.g. `MyPlugin.jar`).  The new file is written under that same name in the configured plugins directory.
- **`file`** – The new JAR file to deploy.

#### HTTP Responses

| Code | Meaning |
|------|---------|
| `200 OK` | Plugin deployed successfully |
| `400 Bad Request` | Invalid `pluginName` or non-JAR file |
| `401 Unauthorized` | Missing, wrong, or unconfigured token |
| `500 Internal Server Error` | I/O failure during file replacement |

### Configuration

Set the following variables in your omcsi `.env` file (see `sample.env` for reference):

| Variable | Description |
|----------|-------------|
| `DEPLOY_AUTH_TOKEN` | Shared secret used to authenticate deploy requests. Leave empty to disable the endpoint entirely. The web app is given the same value and presents it when forwarding a dashboard world upload to the wrapper, so leaving it empty also disables world upload from the dashboard. |
| `PLUGINS_DIRECTORY` | Absolute path to the Minecraft plugins directory inside the container (default: `/mcserver/plugins`). |

### Reference Workflow for Spigot Plugin Repositories

A ready-to-use workflow is provided at `docs/github-actions/deploy-plugin.yml`. Copy it into your plugin repository at `.github/workflows/deploy-plugin.yml` and configure the following:

**Repository Secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
|--------|-------|
| `OMCSI_URL` | Base URL of your omcsi instance, e.g. `https://mc.example.com:8092` |
| `OMCSI_DEPLOY_TOKEN` | The same value as `DEPLOY_AUTH_TOKEN` in your omcsi `.env` |

**Repository Variables** (Settings → Secrets and variables → Actions):

| Variable | Example | Description |
|----------|---------|-------------|
| `PLUGIN_JAR_NAME` | `MyPlugin.jar` | Filename of the plugin to replace on the server |
| `DEPLOY_BRANCH` | `main` | Branch that triggers a deployment |
| `BUILD_COMMAND` | `./gradlew build` | Command used to build the JAR (optional) |
| `JAR_PATH` | `build/libs/*.jar` | Glob path to the built JAR (optional) |

The workflow:
1. Builds the plugin JAR using Gradle (or a custom build command)
2. Locates the built JAR (excluding `-sources` / `-javadoc` artefacts)
3. Sends it to the omcsi deploy endpoint via `curl`
4. Fails the workflow if deployment is rejected

## CI Pipeline Benefits

1. **Early Issue Detection**: Catches problems before they reach production
2. **Consistent Quality**: Ensures all code meets the same standards
3. **Security Awareness**: Identifies potential vulnerabilities
4. **Functionality Assurance**: Validates core features work correctly
5. **Documentation Compliance**: Maintains documentation standards

## Troubleshooting CI Failures

### ShellCheck Failures
- Review ShellCheck warnings and fix syntax issues
- Use `shellcheck <filename>` locally to debug

### Docker Build Failures
- Ensure Dockerfile syntax is correct
- Verify all required files are present
- Test `docker build` locally

### Java Module Build / Test Failures
- Run `./gradlew build` and `./gradlew test` locally inside the affected
  module (`web-app`, `minecraft-wrapper`, `agent-manager`, `alert-manager`,
  or `backup-manager`) to reproduce.
- Confirm you're on JDK 21 — older or newer JDKs may produce class-version
  errors that look unrelated.

### Helm Lint / Unit Test Failures
- `helm lint helm/omcsi --set secrets.rconPassword=ci --set secrets.adminPassword=ci`
  reproduces lint output locally. Omitting the `--set` flags produces a
  `secrets.rconPassword is required` error rather than real lint findings.
- `helm unittest helm/omcsi` reproduces template assertions; look at
  `helm/omcsi/tests/*.yaml` for which template a failing assertion belongs to.

### Helm Deploy Smoke Test Failures
- Usually a selector mismatch, missing required value, or PVC binding
  failure on the kind cluster. Render with `helm template omcsi helm/omcsi
  --values <your-overrides>.yaml` to see what would be applied.

### Environment Configuration Failures
- Ensure all required variables are defined in `sample.env`
- Check variable naming consistency

### Server Run Test Failures
- Check Docker logs for server startup errors
- Verify server initialization completes within timeout (10 minutes)
- Ensure sufficient resources are available for server build
- Check for port conflicts or networking issues
- Review server configuration in test environment

### Graceful Shutdown Test Failures (end-to-end workflow)
- Check that `minecraft-wrapper` handles signals correctly
- Verify FIFO communication works properly between the wrapper and the
  Spigot process
- Test the shutdown sequence manually with
  `scripts/test-docker-graceful-shutdown.sh`

### nginx Configuration Test Failures
The job builds the nginx image and asserts the configuration nginx resolves,
so a failure is either a build failure or a route assertion.
- An empty render ("nginx rejected its configuration") means `nginx -T` refused
  the configuration outright — reproduce with
  `docker run --rm --user 0 <image> nginx -T` and read the error it prints
- A failed route assertion names the string it expected; check
  `nginx/entrypoint.sh`'s fragment generation and `nginx/nginx.conf`'s
  `include /etc/nginx/omcsi.d/*.conf;` line
- Reproduce the whole job locally with `scripts/test-nginx-bluemap-route.sh`
  (also run by `scripts/ci-local.sh` when a Docker daemon is reachable)
- The Kubernetes side of the same routes is covered by
  `helm/omcsi/tests/nginx_test.yaml` under `helm unittest`, so a route that
  fails here but passes there points at the Compose-only generation path in
  `nginx/entrypoint.sh`

### Python Client Test Failures
- Reproduce with `cd clients/python && PYTHONPATH=src python3 -m unittest discover -s tests -t tests -v`
- A failure on only one matrix entry is a version-compatibility problem, not a
  logic one; check what the newer or older stdlib does differently before
  changing the test
- A failure in the "no runtime dependencies" step means something was added to
  `dependencies` in `clients/python/pyproject.toml`. That list is meant to stay
  empty — the client is standard library only by design
- A failure importing `omcsi_client` after a green install usually means a new
  module was added under `src/omcsi_client/` but the package was not
  reinstalled locally; CI installs fresh every run

## Releasing the Python client (`python-client-publish.yml`)

A separate `workflow_dispatch`-only workflow builds `clients/python` and
uploads it to PyPI (or TestPyPI, via a choice input). It never runs on push: the
version lives in `clients/python/pyproject.toml` and cutting a release is a
decision, not a side effect of merging.

Before uploading it re-runs the test suite, checks the artifacts with
`twine check`, and asserts that `pyproject.toml`'s version matches
`omcsi_client.__version__` — a mismatch would ship a package that misreports
itself.

It needs a `PYPI_API_TOKEN` repository secret (`TEST_PYPI_API_TOKEN` for the
TestPyPI target). The job declares `environment: pypi`, so attaching a
repository environment of that name adds a required approval before the upload.

## Performance

The CI pipeline includes multiple workflows:
- **Validation Pipeline**: Typically completes in under 5 minutes
- **Server Run Test**: Takes 15-25 minutes due to Spigot build time
  - First-time Spigot compilation: ~10-15 minutes
  - Server initialization: ~2-5 minutes
  - Testing and cleanup: ~2-3 minutes

The server run test runs in parallel with other checks to provide comprehensive validation without significantly increasing total CI time.