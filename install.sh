#!/usr/bin/env bash
#
# Build everything: both sidecars, the frontend and the backend jar.
# Linux and macOS only. Does not start anything — run ./start.sh afterwards.
#
# Usage: ./install.sh [--backend-only]
#   --backend-only  Only rebuild the backend jar, reusing frontend/dist and the
#                   existing sidecar builds (fast; for backend-only changes).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

PID_FILE=/tmp/artus.pid

BACKEND_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --backend-only) BACKEND_ONLY=1 ;;
    -h|--help)
      grep '^#' "$0" | sed -n '2,9p' | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 1
      ;;
  esac
done

log() { printf '%s\n' "$*"; }

# A live JVM holds the jar open and the spring-boot repackage then fails half-written.
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Backend is running (pid $(cat "$PID_FILE")) — stop it first (./stop.sh) or use ./start.sh after installing elsewhere." >&2
  exit 1
fi

build_sidecar() {
  local dir="$1"
  log "Building $dir..."
  # npm ci installs exactly what the lockfile pins, so a stale install can't survive an update.
  (cd "$dir" && npm ci && npm run build)
}

build_backend() {
  local mvn_cmd="./mvnw"
  if [ "$(uname)" = "Darwin" ] && command -v mvn >/dev/null 2>&1; then
    # mvnw loses its executable bit across some git checkouts on macOS; use the system mvn.
    mvn_cmd="mvn"
  fi

  local build_args=(package -DskipTests)
  if [ "$BACKEND_ONLY" = 1 ]; then
    build_args+=(-Dskip.installnodenpm -Dskip.npm)
    log "Building backend (fast: reusing frontend/dist)..."
  else
    log "Building backend + frontend..."
  fi
  "$mvn_cmd" "${build_args[@]}"
}

if [ "$BACKEND_ONLY" = 0 ]; then
  build_sidecar sidecar
  build_sidecar sidecar-codex
fi
build_backend

log ""
log "Install complete. Run ./start.sh to (re)start the backend."
