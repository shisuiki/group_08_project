#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT"

if [ ! -f .dockerignore ]; then
    printf '.dockerignore is missing\n' >&2
    exit 1
fi

for path in Dockerfile pom.xml src ops/docker/s3-recording-sync.Dockerfile; do
    if [ ! -e "$path" ]; then
        printf 'required Docker build input is missing: %s\n' "$path" >&2
        exit 1
    fi
done

for pattern in \
    ".git/" \
    "target/" \
    "logs/" \
    "journal/" \
    "recordings/" \
    "secrets/" \
    "keys/" \
    ".deploy-state/" \
    "uploads/" \
    ".env" \
    ".env.*" \
    "*.pem" \
    "*.key" \
    "*.zip"; do
    if ! grep -Fxq "$pattern" .dockerignore; then
        printf '.dockerignore is missing required pattern: %s\n' "$pattern" >&2
        exit 1
    fi
done

if grep -Eq '^[[:space:]]*(COPY|ADD)[[:space:]]+\.[[:space:]]' Dockerfile; then
    printf 'Dockerfile uses broad COPY/ADD .; update context validation before allowing this.\n' >&2
    exit 1
fi

printf 'PASS docker_build_context required ignore patterns present\n'
