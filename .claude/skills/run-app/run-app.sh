#!/usr/bin/env bash
# Runs seedkeeper in dev mode against DemoTransmission, a fake Transmission with realistic torrents, in the background.
#   run-app.sh start    fresh demo data, start both, wait until the app listens, print the URL and the login
#   run-app.sh stop     stop both
#   run-app.sh status   running or not, and the URL
# State and logs: $SEEDKEEPER_RUN_DIR, default ${TMPDIR:-/tmp}/seedkeeper-run-app. Port: $SEEDKEEPER_RUN_PORT, default 8187.
# Screenshots: $SEEDKEEPER_SCREENSHOTS, default /tmp/screenshots, which the agent workflows upload; start creates it.
set -euo pipefail

cd "$(dirname "$0")/../../.."
dir="${SEEDKEEPER_RUN_DIR:-${TMPDIR:-/tmp}/seedkeeper-run-app}"
dir="${dir%/}"
port="${SEEDKEEPER_RUN_PORT:-8187}"
url="http://localhost:$port"
username="admin"
password=ui-password
screenshots="${SEEDKEEPER_SCREENSHOTS:-/tmp/screenshots}"

# Each background process leads its own process group, so stop also reaches the JVM that mvnw starts.
set -m

running() { [ -f "$dir/$1.pid" ] && kill -0 "$(cat "$dir/$1.pid")" 2>/dev/null; }

stop_one() {
  if running "$1"; then
    kill -TERM -- "-$(cat "$dir/$1.pid")" 2>/dev/null || true
    for _ in $(seq 1 20); do running "$1" || break; sleep 0.5; done
    if running "$1"; then kill -KILL -- "-$(cat "$dir/$1.pid")" 2>/dev/null || true; fi
  fi
  rm -f "$dir/$1.pid"
}

summary() {
  cat <<EOF
seedkeeper runs at $url
  log in:     $username / $password  (form at $url/login)
  API key:    api-key  (header X-Api-Key)
  app log:    $dir/app.log   (dev mode: an edit to a template, CSS or Java is picked up on the next request)
  fake log:   $dir/transmission.log
  files:      $dir/root/downloads, $dir/root/library   data: $dir/root/data
  trackers:   tracker-a (tracker-a.example), tracker-b (tracker-b.example, tracker-b.example.org, minimum seed time 14d)
  screenshots: $screenshots/
EOF
}

fail() {
  echo "run-app: $1" >&2
  for log in transmission app; do
    if [ -f "$dir/$log.log" ]; then echo "--- last lines of $dir/$log.log" >&2; tail -n 40 "$dir/$log.log" >&2; fi
  done
  stop_one app
  stop_one transmission
  exit 1
}

start() {
  if running app; then echo "Already running."; summary; return; fi
  stop_one transmission

  java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  version="$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
  [ "${version:-0}" -ge 25 ] 2>/dev/null || fail "needs Java 25 or newer, found '${version:-none}' at $java: set JAVA_HOME."

  rm -rf "$dir/root" "$dir/app.log" "$dir/transmission.log"
  mkdir -p "$dir" "$screenshots"

  echo "Compiling and resolving the test classpath..."
  ./mvnw -q -B test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$dir/classpath.txt" \
    > "$dir/build.log" 2>&1 || { tail -n 40 "$dir/build.log" >&2; fail "the build failed, see $dir/build.log."; }

  "$java" -cp "target/test-classes:target/classes:$(cat "$dir/classpath.txt")" \
    io.github.ghostwritertje.seedkeeper.transmission.DemoTransmission "$dir/root" \
    > "$dir/transmission.log" 2>&1 < /dev/null &
  echo $! > "$dir/transmission.pid"
  for _ in $(seq 1 60); do
    grep -q '^URL ' "$dir/transmission.log" && break
    running transmission || fail "DemoTransmission exited."
    sleep 0.5
  done
  rpc="$(sed -n 's/^URL //p' "$dir/transmission.log")"
  [ -n "$rpc" ] || fail "DemoTransmission printed no URL within 30 s."

  echo "Starting seedkeeper in dev mode..."
  ./mvnw -B quarkus:dev -Dquarkus.http.port="$port" -Ddebug=false -Dquarkus.console.enabled=false \
    -Dquarkus.analytics.disabled=true \
    -Dseedkeeper.transmission.url="$rpc" -Dseedkeeper.transmission.username=transmission-user \
    -Dseedkeeper.transmission.password=transmission-password \
    -Dseedkeeper.auth.username="$username" -Dseedkeeper.auth.password="$password" -Dseedkeeper.api-key=api-key \
    -Dquarkus.http.auth.session.encryption-key=a-session-encryption-key-for-dev-mode \
    -Dseedkeeper.downloads-root="$dir/root/downloads" -Dseedkeeper.library-roots="$dir/root/library" \
    -Dseedkeeper.data-dir="$dir/root/data" -Dseedkeeper.time-zone=Europe/Brussels "-Dseedkeeper.schedule=0 0 6 * * ?" \
    -Dseedkeeper.trackers.tracker-a.hosts=tracker-a.example \
    -Dseedkeeper.trackers.tracker-b.hosts=tracker-b.example,tracker-b.example.org \
    -Dseedkeeper.trackers.tracker-b.minimum-seed-time=14d -Dseedkeeper.public.minimum-seed-time=1d \
    > "$dir/app.log" 2>&1 < /dev/null &
  echo $! > "$dir/app.pid"
  for _ in $(seq 1 360); do
    grep -q 'Listening on' "$dir/app.log" && { summary; return; }
    grep -q 'BUILD FAILURE\|Failed to start' "$dir/app.log" && fail "seedkeeper failed to start."
    running app || fail "seedkeeper exited."
    sleep 0.5
  done
  fail "seedkeeper didn't listen within 3 minutes."
}

case "${1:-}" in
  start) start ;;
  stop) stop_one app; stop_one transmission; echo "Stopped." ;;
  status) if running app; then echo "Running."; summary; else echo "Not running."; fi ;;
  *) echo "Usage: $0 start|stop|status" >&2; exit 2 ;;
esac
