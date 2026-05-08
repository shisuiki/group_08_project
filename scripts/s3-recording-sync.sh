#!/bin/sh
set -eu

if [ -z "${S3_RECORDING_BUCKET:-}" ]; then
  echo "S3_RECORDING_BUCKET is not set; S3 recording sync disabled."
  sleep infinity
fi

ROOT="${S3_RECORDING_ROOT:-/app/recordings}"
PREFIX="${S3_RECORDING_PREFIX:-kalshi-normalized/}"
SUBTREES="${S3_RECORDING_SUBTREES:-canonical}"
INTERVAL="${S3_UPLOAD_INTERVAL_SECONDS:-60}"
MIN_AGE="${S3_UPLOAD_MIN_AGE_SECONDS:-120}"
STATE_DIR="${S3_UPLOAD_STATE_DIR:-/app/recordings/.s3-sync-state}"
STAGING_DIR="${S3_UPLOAD_STAGING_DIR:-/tmp/kalshi-s3-upload}"
DELETE_AFTER_UPLOAD="${S3_DELETE_AFTER_UPLOAD:-false}"

mkdir -p "$STATE_DIR" "$STAGING_DIR"
case "$PREFIX" in
  */) ;;
  *) PREFIX="${PREFIX}/" ;;
esac

object_key_for() {
  rel="${1#$ROOT/}"
  printf '%s%s.gz' "$PREFIX" "$rel"
}

state_file_for() {
  rel="${1#$ROOT/}"
  safe=$(printf '%s' "$rel" | tr '/:' '__')
  printf '%s/%s.state' "$STATE_DIR" "$safe"
}

delete_uploaded_file_if_enabled() {
  file="$1"
  case "$DELETE_AFTER_UPLOAD" in
    true|TRUE|1|yes|YES)
      rm -f "$file"
      echo "deleted local uploaded file $file"
      ;;
  esac
}

is_old_enough() {
  file="$1"
  now=$(date +%s)
  mtime=$(stat -c '%Y' "$file")
  age=$((now - mtime))
  [ "$age" -ge "$MIN_AGE" ]
}

signature_for() {
  stat -c '%s:%Y' "$1"
}

upload_file() {
  file="$1"
  [ -s "$file" ] || return 0
  is_old_enough "$file" || return 0

  signature=$(signature_for "$file")
  state_file=$(state_file_for "$file")
  if [ -f "$state_file" ] && [ "$(cat "$state_file")" = "$signature" ]; then
    delete_uploaded_file_if_enabled "$file"
    return 0
  fi

  key=$(object_key_for "$file")
  tmp="$STAGING_DIR/$(basename "$state_file").gz"
  rm -f "$tmp"
  if ! gzip -c "$file" > "$tmp"; then
    rm -f "$tmp"
    echo "failed to gzip $file" >&2
    return 1
  fi
  if ! aws s3 cp "$tmp" "s3://${S3_RECORDING_BUCKET}/${key}" \
    --content-type "application/x-ndjson" \
    --content-encoding "gzip" \
    --only-show-errors; then
    rm -f "$tmp"
    echo "failed to upload s3://${S3_RECORDING_BUCKET}/${key}" >&2
    return 1
  fi

  current_signature=$(signature_for "$file")
  if [ "$current_signature" != "$signature" ]; then
    rm -f "$tmp"
    echo "uploaded s3://${S3_RECORDING_BUCKET}/${key}, but $file changed during upload; leaving it for retry" >&2
    return 0
  fi

  mkdir -p "${state_file%/*}"
  printf '%s' "$signature" > "$state_file"
  rm -f "$tmp"
  echo "uploaded s3://${S3_RECORDING_BUCKET}/${key}"
  delete_uploaded_file_if_enabled "$file"
}

while true; do
  old_ifs="$IFS"
  IFS=","
  for subtree in $SUBTREES; do
    IFS="$old_ifs"
    subtree=$(printf '%s' "$subtree" | xargs)
    [ -n "$subtree" ] || continue
    find "$ROOT/$subtree" -type f -name '*.ndjson' ! -path '*/.s3-sync-state/*' 2>/dev/null | while IFS= read -r file; do
      upload_file "$file" || true
    done
    IFS=","
  done
  IFS="$old_ifs"
  sleep "$INTERVAL"
done
