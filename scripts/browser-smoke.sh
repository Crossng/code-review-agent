#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/target/browser-smoke/logs"
ARTIFACT_DIR="$ROOT_DIR/output/playwright"
BACKEND_URL="${REPOPILOT_BACKEND_URL:-http://127.0.0.1:8080}"
FRONTEND_URL="${REPOPILOT_FRONTEND_URL:-http://127.0.0.1:5173/}"
SMOKE_EMAIL="${REPOPILOT_SMOKE_EMAIL:-browser-smoke-$(date +%s)-$$@example.test}"
SMOKE_PASSWORD="${REPOPILOT_SMOKE_PASSWORD:-password123}"

mkdir -p "$LOG_DIR" "$ARTIFACT_DIR" "$ROOT_DIR/target/browser-smoke/workspace"

backend_pid=""
frontend_pid=""

cleanup() {
  if [[ -n "$frontend_pid" ]]; then
    kill "$frontend_pid" >/dev/null 2>&1 || true
    wait "$frontend_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$backend_pid" ]]; then
    kill "$backend_pid" >/dev/null 2>&1 || true
    wait "$backend_pid" >/dev/null 2>&1 || true
  fi
  rm -rf "$ROOT_DIR/target/browser-smoke/workspace"
  cleanup_smoke_data || true
}
trap cleanup EXIT

cleanup_smoke_data() {
  docker compose exec -T postgres \
    psql \
      -U "${POSTGRES_USER:-repopilot}" \
      -d "${POSTGRES_DB:-repopilot}" \
      -v "smoke_email=$SMOKE_EMAIL" >/dev/null <<'SQL'
create temp table smoke_user_ids as
  select id from app_user where email = :'smoke_email';
create temp table smoke_task_ids as
  select id from agent_task where user_id in (select id from smoke_user_ids);
create temp table smoke_run_ids as
  select id from agent_run where agent_task_id in (select id from smoke_task_ids);
create temp table smoke_patch_ids as
  select id from patch_record where agent_task_id in (select id from smoke_task_ids);

delete from pull_request_record
  where agent_task_id in (select id from smoke_task_ids)
     or patch_id in (select id from smoke_patch_ids);
delete from approval_record
  where agent_task_id in (select id from smoke_task_ids)
     or patch_id in (select id from smoke_patch_ids)
     or user_id in (select id from smoke_user_ids);
delete from test_run
  where agent_run_id in (select id from smoke_run_ids)
     or patch_id in (select id from smoke_patch_ids);
delete from tool_call_log where agent_run_id in (select id from smoke_run_ids);
delete from model_call_log where agent_run_id in (select id from smoke_run_ids);
delete from agent_step where agent_run_id in (select id from smoke_run_ids);
delete from patch_record where id in (select id from smoke_patch_ids);
update agent_task set current_run_id = null where id in (select id from smoke_task_ids);
delete from agent_run where id in (select id from smoke_run_ids);
delete from agent_task where id in (select id from smoke_task_ids);
delete from project where owner_user_id in (select id from smoke_user_ids);
delete from app_user where id in (select id from smoke_user_ids);
SQL
}

wait_for_url() {
  local url="$1"
  local label="$2"
  local attempts="${3:-90}"
  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $label at $url" >&2
  return 1
}

docker compose up -d postgres redis

if curl -fsS "$BACKEND_URL/actuator/health" >/dev/null 2>&1; then
  echo "Using existing backend at $BACKEND_URL"
else
  echo "Starting backend..."
  (
    cd "$ROOT_DIR/backend"
    REPOPILOT_WORKSPACE_ROOT="../target/browser-smoke/workspace" \
      mvn -q -Dmaven.repo.local=../.m2 spring-boot:run
  ) >"$LOG_DIR/backend.log" 2>&1 &
  backend_pid="$!"
  wait_for_url "$BACKEND_URL/actuator/health" "backend" 120
fi

if curl -fsS "$FRONTEND_URL" >/dev/null 2>&1; then
  echo "Using existing frontend at $FRONTEND_URL"
else
  echo "Starting frontend..."
  (
    cd "$ROOT_DIR/frontend"
    npm run dev -- --port 5173
  ) >"$LOG_DIR/frontend.log" 2>&1 &
  frontend_pid="$!"
  wait_for_url "$FRONTEND_URL" "frontend" 90
fi

(
  cd "$ROOT_DIR/frontend"
  npx playwright install chromium
)

(
  cd "$ROOT_DIR/frontend"
  REPOPILOT_FRONTEND_URL="$FRONTEND_URL" \
    REPOPILOT_SMOKE_EMAIL="$SMOKE_EMAIL" \
    REPOPILOT_SMOKE_PASSWORD="$SMOKE_PASSWORD" \
    REPOPILOT_SMOKE_ARTIFACT_DIR="$ARTIFACT_DIR" \
    npm run smoke:browser
)
