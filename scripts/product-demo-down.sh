#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)"

usage() {
    printf 'Usage: %s <evidence-json-or-compose-project>\n' "$0" >&2
}

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi

target="$1"
if [ -f "$target" ]; then
    project="$(
        python3 - "$target" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
print(body.get("compose_project_name") or body.get("project") or "")
PY
    )"
else
    project="$target"
fi

case "$project" in
    "")
        printf 'product demo cleanup target has no compose project name\n' >&2
        exit 2
        ;;
    group_08_project|kalshi|default)
        printf 'refusing to clean non-isolated compose project: %s\n' "$project" >&2
        exit 2
        ;;
esac

cd "$REPO_ROOT"

COMPOSE_PROJECT_NAME="$project" docker compose --profile db-primary-product down --remove-orphans -v
printf 'PASS product_demo_down project=%s\n' "$project"
