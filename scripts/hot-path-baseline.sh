#!/bin/sh
set -eu
if (set -o pipefail) 2>/dev/null; then
  set -o pipefail
fi

usage() {
  cat <<'EOF'
Usage: scripts/hot-path-baseline.sh [--help]

Builds the shaded jar and runs HotPathProfileCli in all baseline modes.

Environment:
  HOTPATH_ITERATIONS      measured messages per mode (default: 100000)
  HOTPATH_WARMUP          warmup messages per mode (default: 20000)
  HOTPATH_MARKETS         synthetic market count (default: 1)
  HOTPATH_OUTPUT_DIR      output directory (default: target/hotpath-baselines/<timestamp>)
  HOTPATH_PRINT_METRICS   true/false; include profiler metrics output (default: false)
EOF
}

case "${1:-}" in
  --help|-h)
    usage
    exit 0
    ;;
  "")
    ;;
  *)
    echo "Unknown argument: $1" >&2
    usage >&2
    exit 2
    ;;
esac

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT"

ITERATIONS="${HOTPATH_ITERATIONS:-100000}"
WARMUP="${HOTPATH_WARMUP:-20000}"
MARKETS="${HOTPATH_MARKETS:-1}"
PRINT_METRICS="${HOTPATH_PRINT_METRICS:-false}"
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUTPUT_DIR="${HOTPATH_OUTPUT_DIR:-target/hotpath-baselines/$TIMESTAMP}"

case "$OUTPUT_DIR" in
  /*) ;;
  *) OUTPUT_DIR="$REPO_ROOT/$OUTPUT_DIR" ;;
esac

case "$PRINT_METRICS" in
  true|TRUE|1|yes|YES)
    METRICS_ARG="--print-metrics"
    ;;
  false|FALSE|0|no|NO|"")
    METRICS_ARG=""
    ;;
  *)
    echo "HOTPATH_PRINT_METRICS must be true or false, got: $PRINT_METRICS" >&2
    exit 2
    ;;
esac

if [ -x "$REPO_ROOT/mvnw" ]; then
  MVN="$REPO_ROOT/mvnw"
else
  if ! command -v mvn >/dev/null 2>&1; then
    echo "mvn is required when ./mvnw is not present." >&2
    exit 127
  fi
  MVN="mvn"
fi

mkdir -p "$OUTPUT_DIR"

echo "Building shaded jar with: $MVN package -DskipTests"
"$MVN" package -DskipTests

JAR="$REPO_ROOT/target/kalshi-project-1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "Expected shaded jar not found: $JAR" >&2
  exit 1
fi

GIT_COMMIT=$(git rev-parse HEAD 2>/dev/null || printf 'unknown')
SUMMARY="$OUTPUT_DIR/summary.txt"
MODES="parse-only parse-book processor-noop processor-serialize"

{
  printf 'timestamp=%s\n' "$TIMESTAMP"
  printf 'git_commit=%s\n' "$GIT_COMMIT"
  printf 'iterations=%s\n' "$ITERATIONS"
  printf 'warmup=%s\n' "$WARMUP"
  printf 'markets=%s\n' "$MARKETS"
  printf 'print_metrics=%s\n' "$PRINT_METRICS"
  printf 'output_dir=%s\n' "$OUTPUT_DIR"
  printf 'jar=%s\n' "$JAR"
  printf 'build_command=%s package -DskipTests\n' "$MVN"
  printf 'mode_outputs:\n'
} > "$SUMMARY"

for mode in $MODES; do
  output_file="$OUTPUT_DIR/$mode.txt"
  echo "Running $mode -> $output_file"
  java -cp "$JAR" \
    edu.illinois.group8.profile.HotPathProfileCli \
    --mode="$mode" \
    --iterations="$ITERATIONS" \
    --warmup="$WARMUP" \
    --markets="$MARKETS" \
    $METRICS_ARG > "$output_file"
  printf '%s=%s\n' "$mode" "$output_file" >> "$SUMMARY"
done

echo "Hot-path baseline complete: $SUMMARY"
