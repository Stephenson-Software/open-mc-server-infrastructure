#!/bin/bash

# Entrypoint script for nginx container
# Generates self-signed SSL certificates if they don't exist and applies the
# configurable upload size limit

set -e

SSL_CERT="/etc/nginx/ssl/cert.pem"
SSL_KEY="/etc/nginx/ssl/key.pem"
NGINX_CONF="/etc/nginx/nginx.conf"

# Check if SSL certificates exist
if [ ! -f "$SSL_CERT" ] || [ ! -f "$SSL_KEY" ]; then
    echo "SSL certificates not found. Generating self-signed certificates..."
    
    # Generate self-signed certificate
    openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout "$SSL_KEY" \
        -out "$SSL_CERT" \
        -subj "/C=US/ST=State/L=City/O=Organization/CN=localhost"
    
    echo "Self-signed SSL certificates generated successfully."
    echo "For production, replace with certificates from a trusted CA."
else
    echo "Using existing SSL certificates."
fi

# Apply the configurable upload size limit.
#
# Under Docker Compose nginx.conf is baked into the image and writable by the
# container user, so NGINX_MAX_BODY_SIZE is substituted in here. On Kubernetes
# nginx.conf is a read-only ConfigMap subPath mount that the Helm chart has
# already rendered from .Values.nginx.maxBodySize, so the substitution is
# skipped rather than failing on the read-only filesystem.
if [ -w "$NGINX_CONF" ]; then
    NGINX_MAX_BODY_SIZE="${NGINX_MAX_BODY_SIZE:-100M}"
    # Rewritten by truncating the file rather than with `sed -i`: /etc/nginx is
    # root-owned, so the container user (UID 1000) cannot create sed's temporary
    # file alongside it, but it can rewrite the file itself.
    sed "s/client_max_body_size [^;]*;/client_max_body_size ${NGINX_MAX_BODY_SIZE};/" \
        "$NGINX_CONF" > /tmp/nginx.conf.tmp
    cat /tmp/nginx.conf.tmp > "$NGINX_CONF"
    rm -f /tmp/nginx.conf.tmp
    echo "Maximum request body size set to ${NGINX_MAX_BODY_SIZE}."

    # Upload-route timeouts. Only the two lines tagged "# upload" are rewritten,
    # so the dashboard's own 60s proxy timeouts are left alone.
    NGINX_UPLOAD_TIMEOUT="${NGINX_UPLOAD_TIMEOUT:-3600s}"
    sed -E "s/(proxy_(send|read)_timeout) [^;]*; # upload/\1 ${NGINX_UPLOAD_TIMEOUT}; # upload/" \
        "$NGINX_CONF" > /tmp/nginx.conf.tmp
    cat /tmp/nginx.conf.tmp > "$NGINX_CONF"
    rm -f /tmp/nginx.conf.tmp
    echo "Upload route timeout set to ${NGINX_UPLOAD_TIMEOUT}."
else
    echo "$NGINX_CONF is not writable; using its configured client_max_body_size."
fi

# Execute the command passed to the container
exec "$@"
