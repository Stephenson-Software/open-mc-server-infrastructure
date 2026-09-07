#!/bin/bash
# Mint a kubeconfig for one of the ServiceAccounts created by rbac.enabled.
#
# The point is to stop day-to-day work needing the cluster-admin certificate
# that provisioning produced. That certificate cannot be revoked: Kubernetes has
# no certificate revocation list, so a leaked one stays valid until it expires
# whatever you change afterwards. What this produces is different in two ways --
# it expires on its own, and it can be revoked by deleting the ServiceAccount.
#
# Revocation is not instantaneous. The API server caches successful token
# authentications for a few seconds, so a deleted account's token keeps working
# briefly (measured at ~12s on a kubeadm 1.34 cluster). That is a rounding error
# next to a certificate valid for a year, but it is not zero.
#
# Usage:
#   scripts/service-account-kubeconfig.sh \
#       --account omcsi-viewer [--namespace omcsi] [--duration 8h] [--output PATH]
#
# With no --output the kubeconfig goes to stdout. It contains a bearer token, so
# redirect it only into a file you have created with restrictive permissions --
# or let --output do that for you, which is why --output exists.
set -euo pipefail

NAMESPACE="omcsi"
ACCOUNT=""
DURATION="8h"
OUTPUT=""

usage() {
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --account) ACCOUNT="${2:?--account needs a name}"; shift 2 ;;
        --namespace) NAMESPACE="${2:?--namespace needs a name}"; shift 2 ;;
        --duration) DURATION="${2:?--duration needs a value}"; shift 2 ;;
        --output) OUTPUT="${2:?--output needs a path}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [ -z "$ACCOUNT" ]; then
    echo "ERROR: --account is required (e.g. omcsi-viewer or omcsi-operator)" >&2
    usage >&2
    exit 2
fi

command -v kubectl >/dev/null 2>&1 || { echo "ERROR: kubectl is not installed" >&2; exit 2; }

if ! kubectl -n "$NAMESPACE" get serviceaccount "$ACCOUNT" >/dev/null 2>&1; then
    echo "ERROR: no ServiceAccount '$ACCOUNT' in namespace '$NAMESPACE'." >&2
    echo "       Enable it first: helm upgrade ... --set rbac.enabled=true" >&2
    echo "       (the operator account also needs --set rbac.operator.enabled=true)" >&2
    exit 1
fi

# A short-lived token from the TokenRequest API, not a long-lived Secret. This
# is the whole reason the script exists: a credential that expires by default is
# a different kind of object from one that does not.
if ! TOKEN="$(kubectl -n "$NAMESPACE" create token "$ACCOUNT" --duration="$DURATION" 2>/dev/null)"; then
    echo "ERROR: could not create a token for '$ACCOUNT'." >&2
    echo "       'kubectl create token' needs Kubernetes 1.24 or newer, and the" >&2
    echo "       cluster may cap --duration below the value requested ($DURATION)." >&2
    exit 1
fi

CONTEXT="$(kubectl config current-context)"
CLUSTER="$(kubectl config view -o jsonpath="{.contexts[?(@.name==\"$CONTEXT\")].context.cluster}")"
SERVER="$(kubectl config view -o jsonpath="{.clusters[?(@.name==\"$CLUSTER\")].cluster.server}")"
CA_DATA="$(kubectl config view --raw -o jsonpath="{.clusters[?(@.name==\"$CLUSTER\")].cluster.certificate-authority-data}")"

if [ -z "$SERVER" ]; then
    echo "ERROR: could not determine the API server address from context '$CONTEXT'." >&2
    exit 1
fi

# The CA is a public certificate, not a secret, but it may be a file path rather
# than inline data depending on how the kubeconfig was written.
if [ -z "$CA_DATA" ]; then
    CA_FILE="$(kubectl config view --raw -o jsonpath="{.clusters[?(@.name==\"$CLUSTER\")].cluster.certificate-authority}")"
    if [ -n "$CA_FILE" ] && [ -r "$CA_FILE" ]; then
        CA_DATA="$(base64 -w0 < "$CA_FILE" 2>/dev/null || base64 < "$CA_FILE" | tr -d '\n')"
    fi
fi

if [ -z "$CA_DATA" ]; then
    echo "ERROR: could not read the cluster CA certificate; refusing to emit a" >&2
    echo "       kubeconfig that would have to skip TLS verification." >&2
    exit 1
fi

render() {
    cat <<EOF
apiVersion: v1
kind: Config
clusters:
  - name: $CLUSTER
    cluster:
      server: $SERVER
      certificate-authority-data: $CA_DATA
contexts:
  - name: $ACCOUNT@$CLUSTER
    context:
      cluster: $CLUSTER
      namespace: $NAMESPACE
      user: $ACCOUNT
current-context: $ACCOUNT@$CLUSTER
users:
  - name: $ACCOUNT
    user:
      token: $TOKEN
EOF
}

if [ -n "$OUTPUT" ]; then
    # Created empty under a 077 umask before the token is written, so it is never
    # world-readable even briefly.
    ( umask 077 && : > "$OUTPUT" )
    render > "$OUTPUT"
    echo "Wrote $OUTPUT (expires in $DURATION)." >&2
    echo "Revoke early by deleting the ServiceAccount, which invalidates its tokens" >&2
    echo "within a few seconds (the API server briefly caches token authentication):" >&2
    echo "  kubectl -n $NAMESPACE delete serviceaccount $ACCOUNT" >&2
else
    render
fi
