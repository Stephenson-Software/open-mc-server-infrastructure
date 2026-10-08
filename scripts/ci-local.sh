#!/bin/bash

# Local CI validation script
# This script runs the same checks that the CI pipeline will run.
#
# Checks that depend on an optional tool (ShellCheck, Helm, the helm-unittest
# plugin, Terraform) are skipped with a warning when that tool is missing
# locally, and every skip is reported again in the summary at the end. A skipped
# check is still enforced by .github/workflows/ci.yml, so a clean local run with
# skips is weaker than a green CI run.

set -e

# Names of checks that were skipped because a required tool is unavailable.
SKIPPED_CHECKS=()

have() {
    command -v "$1" > /dev/null 2>&1
}

# The docker CLI being on PATH is not enough for the checks that build or run a
# container — a reachable daemon is required too, which is a separate failure on
# a machine where the CLI is installed but the engine is not running. The result
# is cached because `docker info` blocks until its connection attempt times out
# when there is no daemon, and more than one check asks.
DOCKER_AVAILABLE=""

have_docker() {
    if [ -z "${DOCKER_AVAILABLE}" ]; then
        if have docker && docker info > /dev/null 2>&1; then
            DOCKER_AVAILABLE="yes"
        else
            DOCKER_AVAILABLE="no"
        fi
    fi
    [ "${DOCKER_AVAILABLE}" = "yes" ]
}

skip() {
    SKIPPED_CHECKS+=("$1")
    echo "⚠️  Skipping $1"
}

echo "🚀 Running local CI validation..."

echo "📝 Checking shell scripts..."
bash -n up.sh
bash -n down.sh
bash -n upgrade.sh
bash -n rollback.sh
bash -n trigger-backup.sh
bash -n resources/post-create.sh
echo "✅ Shell script syntax validation passed"

echo "🔍 Linting shell scripts..."
if have shellcheck; then
    # Keep this file list in sync with the "Validate shell scripts" step in
    # .github/workflows/ci.yml.
    shellcheck up.sh down.sh upgrade.sh rollback.sh trigger-backup.sh resources/post-create.sh scripts/*.sh
    echo "✅ ShellCheck passed"
else
    skip "ShellCheck (shellcheck is not installed)"
fi

echo "🐳 Checking Docker configuration..."
if have_docker; then
    # Validate against a throwaway env file rather than the repo's .env — that
    # file is gitignored and holds real credentials (RCON/admin passwords,
    # Discord webhook, Anthropic API key), so it must never be overwritten or
    # deleted here.
    CI_ENV_FILE="$(mktemp)"
    trap 'rm -f "${CI_ENV_FILE}"' EXIT
    sed -e 's/YOUR_UUID_HERE/test-uuid/g' \
        -e 's/YOUR_USERNAME_HERE/testuser/g' \
        sample.env > "${CI_ENV_FILE}"
    docker compose --env-file "${CI_ENV_FILE}" config > /dev/null
    echo "✅ Docker Compose validation passed"
else
    skip "docker compose config (no reachable Docker daemon)"
fi

echo "🔀 Checking nginx configuration..."
# Mirrors the nginx-config-test job in .github/workflows/ci.yml. It builds the
# nginx image and asserts the configuration nginx actually resolves, which no
# other check covers on the Docker Compose target.
if have_docker; then
    ./scripts/test-nginx-config.sh
    echo "✅ nginx configuration passed"
else
    skip "nginx configuration test (no reachable Docker daemon)"
fi

echo "⚙️ Checking environment configuration..."
test -f sample.env
grep -q "MINECRAFT_VERSION=" sample.env
grep -q "OPERATOR_UUID=" sample.env
grep -q "OPERATOR_NAME=" sample.env
echo "✅ Environment configuration validation passed"

echo "📚 Checking documentation..."
test -f README.md
grep -q "# Open Minecraft Server Infrastructure" README.md
test -f LICENSE
echo "✅ Documentation validation passed"

echo "🔐 Checking file permissions..."
test -x up.sh
test -x down.sh
test -x upgrade.sh
test -x rollback.sh
test -x trigger-backup.sh
test -x resources/post-create.sh
echo "✅ File permissions validation passed"

echo "⎈ Linting Helm chart..."
if have helm; then
    # secrets.rconPassword and secrets.adminPassword are `required` in
    # templates/secret.yaml, so they must be supplied for lint to succeed.
    helm lint helm/omcsi --set secrets.rconPassword=ci --set secrets.adminPassword=ci
    echo "✅ Helm chart lint passed"
else
    skip "helm lint (helm is not installed)"
fi

echo "⎈ Running Helm unit tests..."
if ! have helm; then
    skip "helm unittest (helm is not installed)"
elif ! helm plugin list 2> /dev/null | grep -q "^unittest"; then
    skip "helm unittest (the helm-unittest plugin is not installed — install it with: helm plugin install https://github.com/helm-unittest/helm-unittest --version v1.1.0)"
else
    helm unittest helm/omcsi
    echo "✅ Helm unit tests passed"
fi

echo "🏗️ Validating Terraform configurations..."
if have terraform; then
    # Every target the terraform-validate job in .github/workflows/ci.yml
    # checks. terraform/modules/ is shared by linode, aws and existing-cluster,
    # so editing one target can break another — all four are always validated.
    for target in linode aws existing-cluster hetzner; do
        echo "  🔍 ${target}..."
        terraform -chdir="terraform/${target}" fmt -check -diff
        terraform -chdir="terraform/${target}" init -backend=false > /dev/null
        terraform -chdir="terraform/${target}" validate
    done
    echo "✅ Terraform validation passed"
else
    skip "Terraform fmt/init/validate (terraform is not installed)"
fi

# Every Gradle module the CI pipeline tests. Keep this list in sync with the
# "Run <module> tests" steps in .github/workflows/ci.yml.
for module in web-app minecraft-wrapper agent-manager alert-manager backup-manager; do
    echo "🧪 Running ${module} tests..."
    (cd "${module}" && ./gradlew test --no-daemon)
    echo "✅ ${module} tests passed"
done

echo "🐍 Running Python client tests..."
if have python3; then
    # Run against src/ on PYTHONPATH rather than pip-installing, so a local run
    # never writes to the developer's environment. CI installs the package
    # instead, which additionally proves pyproject.toml is correct — see the
    # python-client-test job in .github/workflows/ci.yml.
    (cd clients/python && PYTHONPATH=src python3 -m unittest discover -s tests -t tests)
    echo "✅ Python client tests passed"
else
    skip "Python client tests (python3 is not installed)"
fi

if [ ${#SKIPPED_CHECKS[@]} -eq 0 ]; then
    echo "🎉 All local CI checks passed!"
else
    echo "🎉 All local CI checks that could be run passed, but ${#SKIPPED_CHECKS[@]} were skipped:"
    for check in "${SKIPPED_CHECKS[@]}"; do
        echo "  ⚠️  ${check}"
    done
    echo "These are still enforced by CI — install the missing tools to catch failures before pushing."
fi
