#!/usr/bin/env bash
# Builds the frontend (static production bundle for nginx) and backend (runnable jar), then
# restarts the backend process. Run this from the EC2 instance after extracting/pulling a new
# version of the code -- it's the "deploy a later version" step that AWS_POC_DEPLOYMENT.md
# section 20 does by hand, now for the nginx-fronted setup instead of exposing `ng serve` directly.
#
# Usage: ./deploy/build.sh
#
# What it does NOT do: install nginx, write the nginx config, open security-group ports, or start
# PostgreSQL -- those are one-time setup steps (see deploy/nginx/pharos-dashboard.conf and
# AWS_POC_DEPLOYMENT.md sections 7-10 for Docker/DDL/mock-data setup, which is unchanged).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/backend"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
JAR_NAME="compliance-dashboard-api-1.0.0-SNAPSHOT.jar"
PID_FILE="$BACKEND_DIR/backend.pid"
LOG_FILE="$BACKEND_DIR/backend.log"

echo "==> Building frontend (production bundle)"
cd "$FRONTEND_DIR"
npm ci
npm run build
echo "    Output: $FRONTEND_DIR/dist/dashboard/browser"
echo "    (nginx serves this directly -- no restart needed for a frontend-only change once nginx is running)"

echo "==> Building backend"
cd "$BACKEND_DIR"
mvn clean package -DskipTests

echo "==> Restarting backend"
if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "    Stopping existing backend (pid $(cat "$PID_FILE"))"
  kill "$(cat "$PID_FILE")"
  # Give it a moment to release port 8085 before the new instance binds it.
  for _ in $(seq 1 10); do
    kill -0 "$(cat "$PID_FILE")" 2>/dev/null || break
    sleep 1
  done
fi

nohup java -jar "$BACKEND_DIR/target/$JAR_NAME" > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
echo "    Started backend (pid $(cat "$PID_FILE")), logging to $LOG_FILE"

echo "==> Waiting for backend health check"
for _ in $(seq 1 20); do
  if curl -sf -o /dev/null http://localhost:8085/actuator/health; then
    echo "    Backend is UP"
    break
  fi
  sleep 1
done
curl -s http://localhost:8085/actuator/health || echo "    Backend did not respond -- check $LOG_FILE"

if command -v nginx >/dev/null 2>&1; then
  echo "==> Reloading nginx (picks up any config change; static files are served fresh on every request already)"
  sudo nginx -t && sudo systemctl reload nginx
else
  echo "==> nginx not found on this machine -- skipping reload. See deploy/nginx/pharos-dashboard.conf for one-time setup."
fi

echo "==> Done. Verify at http://<EC2-PUBLIC-IP>/batches"
