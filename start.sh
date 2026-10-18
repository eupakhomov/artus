#!/usr/bin/env bash
#
# Stop any running Artus backend and start it again from the existing jar.
# Build first with ./install.sh. Linux and macOS only.
# See CLAUDE.md "Run the project" / "Stop / kill" for background.
#
# Usage: ./start.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

PID_FILE=/tmp/artus.pid
TOKEN_FILE=/tmp/artus.token
LOG_FILE=/tmp/artus.log
PORT=8080

case "${1:-}" in
  "") ;;
  -h|--help)
    grep '^#' "$0" | sed -n '2,7p' | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "Unknown option: $1" >&2
    exit 1
    ;;
esac

log() { printf '%s\n' "$*"; }

stop_running() {
  if [ -f "$PID_FILE" ]; then
    local pid
    pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      log "Stopping running backend (pid $pid)..."
      kill "$pid"
      for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      if kill -0 "$pid" 2>/dev/null; then
        log "Backend did not stop gracefully within 30s, sending SIGKILL..."
        kill -9 "$pid" 2>/dev/null || true
      fi
    fi
    rm -f "$PID_FILE"
  fi

  # Fallback: anything still listening on $PORT (stale/missing pid file, or
  # a backend started outside this script).
  local port_pid=""
  if command -v lsof >/dev/null 2>&1; then
    port_pid="$(lsof -ti tcp:"$PORT" 2>/dev/null || true)"
  elif command -v ss >/dev/null 2>&1; then
    port_pid="$(ss -tlnp 2>/dev/null | grep ":$PORT " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)"
  fi
  if [ -n "$port_pid" ]; then
    log "Killing process still listening on :$PORT (pid $port_pid)..."
    kill "$port_pid" 2>/dev/null || true
    sleep 2
    kill -9 "$port_pid" 2>/dev/null || true
  fi

  # Orphaned sidecar processes, if any survive the backend shutdown.
  # (bracket in the pattern keeps this pkill from matching its own command line)
  pkill -f "dist/index[.]js --cwd" 2>/dev/null || true
}

ensure_db() {
  if command -v docker >/dev/null 2>&1; then
    log "Ensuring Postgres is up (docker compose)..."
    docker compose up -d || log "Warning: 'docker compose up -d' failed — make sure Postgres is running manually (see CLAUDE.md 'Database')."
  else
    log "docker not found on PATH — make sure Postgres is running manually (see CLAUDE.md 'Database')."
  fi
}

start_app() {
  local jar
  # newest first (-t): a version bump leaves the previous jar in target/ until `mvn clean`, and a
  # name sort would pick artus-0.0.1-SNAPSHOT.jar over artus-1.0.0.jar
  jar="$(ls -t target/artus-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
  if [ -z "$jar" ]; then
    echo "No jar found in target/ — run ./install.sh first." >&2
    exit 1
  fi

  local token
  if [ -n "${ARTUS_TOKEN:-}" ]; then
    token="$ARTUS_TOKEN"
    log "Token: $token (from ARTUS_TOKEN env — reused, not regenerated)"
  else
    token="$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 20)"
    log "Token: $token"
  fi
  echo "$token" > "$TOKEN_FILE"

  log "Starting backend from $jar..."
  ARTUS_TOKEN="$token" nohup java -jar "$jar" --server.address=0.0.0.0 > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"

  log "Waiting for backend to become healthy..."
  local pid
  pid="$(cat "$PID_FILE")"
  for i in $(seq 1 120); do
    if curl -sf "http://localhost:$PORT/actuator/health" >/dev/null 2>&1; then
      log ""
      log "UI:    http://localhost:$PORT"
      log "Token: $token"
      return
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "Backend process (pid $pid) died while starting up. Last 40 lines of $LOG_FILE:" >&2
      echo "---" >&2
      tail -n 40 "$LOG_FILE" >&2
      echo "---" >&2
      rm -f "$PID_FILE"
      exit 1
    fi
    if [ $((i % 15)) -eq 0 ]; then
      log "...still waiting (${i}s elapsed, pid $pid still running)"
    fi
    sleep 1
  done

  echo "Backend did not become healthy within 120s — check $LOG_FILE" >&2
  exit 1
}

stop_running
ensure_db
start_app
