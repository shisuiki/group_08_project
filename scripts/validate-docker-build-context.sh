#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT"

if [ ! -f .dockerignore ]; then
    printf '.dockerignore is missing\n' >&2
    exit 1
fi

for path in \
    Dockerfile \
    pom.xml \
    src \
    frontend/tradingview-lightweight/index.html \
    frontend/tradingview-lightweight/app.js \
    frontend/tradingview-lightweight/styles.css \
    ops/docker/s3-recording-sync.Dockerfile; do
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

frontend_build_copy_line="$(grep -nE '^[[:space:]]*COPY[[:space:]]+frontend[[:space:]]+\./frontend[[:space:]]*$' Dockerfile \
    | head -n1 | cut -d: -f1 || true)"
maven_build_line="$(grep -nE '^[[:space:]]*RUN[[:space:]]+mvn[[:space:]]+-B[[:space:]]+\$\{MAVEN_PACKAGE_ARGS\}' Dockerfile \
    | head -n1 | cut -d: -f1 || true)"
if [ -z "$frontend_build_copy_line" ] \
    || [ -z "$maven_build_line" ] \
    || [ "$frontend_build_copy_line" -ge "$maven_build_line" ]; then
    printf 'Dockerfile must copy frontend assets into the Maven build stage before running Maven tests\n' >&2
    exit 1
fi

if ! grep -Eq '^[[:space:]]*COPY[[:space:]]+frontend/tradingview-lightweight[[:space:]]+/app/frontend/tradingview-lightweight[[:space:]]*$' Dockerfile; then
    printf 'Dockerfile does not copy TradingView frontend assets into the runtime image\n' >&2
    exit 1
fi

printf 'PASS docker_build_context required ignore patterns present\n'
