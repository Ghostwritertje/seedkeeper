#!/usr/bin/env bash
# Starts the image as an arbitrary non-root uid:gid, with a seedkeeper.yaml mounted the way the README says and the
# secrets in the environment. There is no Transmission here, so it waits for liveness (readiness stays down) and
# then checks that the API (with the API key) reports the Transmission URL from the mounted file as unreachable.
# Usage: smoke-test-image.sh <image>
set -euo pipefail

image="$1"
name="seedkeeper-smoke-$$"
config="$(mktemp -d)"
chmod 755 "$config"

cat > "$config/seedkeeper.yaml" <<'YAML'
seedkeeper:
  transmission:
    url: http://127.0.0.1:9/transmission/rpc
  downloads-root: /downloads
  library-roots: [/library/movies, /library/tv]
  trackers:
    tracker-a:
      hosts: [tracker-a.example]
    tracker-b:
      hosts: [tracker-b.example, tracker-b.example.org]
      minimum-seed-time: 14d
  public:
    minimum-seed-time: 1d
YAML
chmod 644 "$config/seedkeeper.yaml"

docker run -d --name "$name" --user 1029:65536 -p 127.0.0.1:8080:8080 \
  -v "$config/seedkeeper.yaml:/work/config/application.yaml:ro" \
  -e SEEDKEEPER_TRANSMISSION_USERNAME=smoke \
  -e SEEDKEEPER_TRANSMISSION_PASSWORD=smoke \
  -e SEEDKEEPER_AUTH_PASSWORD=smoke \
  -e SEEDKEEPER_API_KEY=smoke \
  -e QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY=smoke-test-session-encryption-key \
  "$image" >/dev/null
trap 'docker logs "$name" || true; docker rm -f "$name" >/dev/null; rm -rf "$config"' EXIT

for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/q/health/live | grep -q '"status": *"UP"'; then
    echo "liveness is UP"
    system="$(curl -fsS -H 'X-Api-Key: smoke' http://127.0.0.1:8080/api/v1/system)"
    echo "$system"
    if echo "$system" | grep -q 'Transmission at http://127.0.0.1:9/transmission/rpc does not answer'; then
      echo "the mounted seedkeeper.yaml was read"
      exit 0
    fi
    echo "GET /api/v1/system does not report the Transmission URL from the mounted seedkeeper.yaml" >&2
    exit 1
  fi
  sleep 1
done

echo "liveness did not report UP within 30 s" >&2
exit 1
