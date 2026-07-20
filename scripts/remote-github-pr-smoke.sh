#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/target/remote-github-pr-smoke/logs"
ARTIFACT_DIR="$ROOT_DIR/output/remote-github-pr-smoke"
WORKSPACE_DIR="$ROOT_DIR/target/remote-github-pr-smoke/workspace"
GIT_ROOT="$ROOT_DIR/target/remote-github-pr-smoke/git"
SMOKE_EMAIL="${REPOPILOT_REMOTE_GITHUB_PR_SMOKE_EMAIL:-remote-github-pr-smoke-$(date +%s)-$$@example.test}"

mkdir -p "$LOG_DIR" "$ARTIFACT_DIR" "$WORKSPACE_DIR" "$GIT_ROOT"

cleanup_smoke_data() {
  docker compose exec -T postgres \
    psql \
      -U "${POSTGRES_USER:-repopilot}" \
      -d "${POSTGRES_DB:-repopilot}" \
      -v "smoke_email=$SMOKE_EMAIL" >/dev/null <<'SQL'
create temp table smoke_user_ids as
  select id from app_user where email = :'smoke_email';
create temp table smoke_project_ids as
  select id from project where owner_user_id in (select id from smoke_user_ids);
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
delete from project where id in (select id from smoke_project_ids);
delete from app_user where id in (select id from smoke_user_ids);
SQL
}

cleanup() {
  cleanup_smoke_data || true
  rm -rf "$WORKSPACE_DIR" "$GIT_ROOT"
}
trap cleanup EXIT

echo "RepoPilot 远端 GitHub PR 本地替身 smoke"
docker compose up -d postgres redis

REPOPILOT_REMOTE_GITHUB_PR_SMOKE_EMAIL="$SMOKE_EMAIL" \
REPOPILOT_REMOTE_GITHUB_PR_SMOKE_LOG_DIR="$LOG_DIR" \
REPOPILOT_REMOTE_GITHUB_PR_SMOKE_ARTIFACT_DIR="$ARTIFACT_DIR" \
REPOPILOT_REMOTE_GITHUB_PR_SMOKE_WORKSPACE="$WORKSPACE_DIR" \
REPOPILOT_REMOTE_GITHUB_PR_SMOKE_GIT_ROOT="$GIT_ROOT" \
  node "$ROOT_DIR/scripts/remote-github-pr-smoke.mjs"
