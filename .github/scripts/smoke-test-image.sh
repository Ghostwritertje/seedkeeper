#!/usr/bin/env bash
# Starts the image as an arbitrary non-root uid:gid and waits for /q/health to report UP.
# Usage: smoke-test-image.sh <image>
set -euo pipefail

image="$1"
name="seedkeeper-smoke-$$"

docker run -d --name "$name" --user 1029:65536 -p 127.0.0.1:8080:8080 "$image" >/dev/null
trap 'docker logs "$name" || true; docker rm -f "$name" >/dev/null' EXIT

for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/q/health | grep -q '"status": *"UP"'; then
    echo "health is UP"
    exit 0
  fi
  sleep 1
done

echo "health did not report UP within 30 s" >&2
exit 1
