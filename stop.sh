#!/usr/bin/env bash
#
# Stop the running Artus backend: SIGTERM first (graceful, shuts sessions down),
# SIGKILL if it is still alive after 10 seconds. Also cleans up a stray listener
# on the port and orphaned sidecars. Linux and macOS only.
# See CLAUDE.md "Run the project" / "Stop / kill" for background.
#
# Usage: ./stop.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

PID_FILE=/tmp/artus.pid
PORT=8080
TIMEOUT=10

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

# terminate <pid> <label>: SIGTERM, wait up to $TIMEOUT s showing progress, then SIGKILL.
terminate() {
  local pid="$1" label="$2" waited=0 tenths=0
  kill -0 "$pid" 2>/dev/null || return 0
  log "Stopping $label (pid $pid)..."
  kill "$pid" 2>/dev/null || true
  # Poll every 0.2 s; the status line is redrawn in place on a terminal.
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$tenths" -ge $((TIMEOUT * 10)) ]; then
      [ -t 1 ] && printf '\r\033[K'
      log "Still running after ${TIMEOUT}s, sending SIGKILL..."
      kill -9 "$pid" 2>/dev/null || true
      sleep 0.5
      return 0
    fi
    if [ -t 1 ]; then
      local frames='|/-\' f
      f="${frames:$(( (tenths / 2) % 4 )):1}"
      printf '\r\033[K  %s waiting for shutdown... %ds/%ds' "$f" "$((tenths / 10))" "$TIMEOUT"
    elif [ $((tenths % 10)) -eq 0 ] && [ "$tenths" -gt 0 ]; then
      log "  waiting for shutdown... $((tenths / 10))s/${TIMEOUT}s"
    fi
    sleep 0.2
    tenths=$((tenths + 2))
  done
  [ -t 1 ] && printf '\r\033[K'
  waited=$((tenths / 10))
  log "Stopped $label after ~${waited}s."
}

if [ -f "$PID_FILE" ]; then
  terminate "$(cat "$PID_FILE")" "backend"
  rm -f "$PID_FILE"
fi

# Fallback: anything still listening on $PORT (stale/missing pid file, or
# a backend started outside start.sh).
port_pid=""
if command -v lsof >/dev/null 2>&1; then
  port_pid="$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null | head -1 || true)"
elif command -v ss >/dev/null 2>&1; then
  port_pid="$(ss -tlnp 2>/dev/null | grep ":$PORT " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)"
fi
if [ -n "$port_pid" ]; then
  terminate "$port_pid" "process listening on :$PORT"
fi

# Orphaned sidecar processes, if any survive the backend shutdown.
# (bracket in the pattern keeps this pkill from matching its own command line)
pkill -f "dist/index[.]js --cwd" 2>/dev/null || true

log "Backend is stopped."
