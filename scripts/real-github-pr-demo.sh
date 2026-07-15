#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/target/real-github-pr-demo/logs"
WORKSPACE_DIR="$ROOT_DIR/target/real-github-pr-demo/workspace"
BACKEND_URL="${REPOPILOT_BACKEND_URL:-http://127.0.0.1:8080}"
DEMO_EMAIL="${REPOPILOT_REAL_GITHUB_PR_DEMO_EMAIL:-real-github-pr-demo-$(date +%s)-$$@example.test}"
DEMO_PASSWORD="${REPOPILOT_REAL_GITHUB_PR_DEMO_PASSWORD:-password123}"

backend_pid=""
started_backend=false

usage() {
  cat <<'EOF'
RepoPilot 真实 GitHub PR 发布演示

用法:
  ./scripts/real-github-pr-demo.sh

必须显式确认:
  export REPOPILOT_REAL_GITHUB_PR_CONFIRM=create-pr
  export REPOPILOT_REAL_GITHUB_PR_REPO_URL=https://github.com/<owner>/<demo-repo>.git
  export REPOPILOT_GITHUB_ENABLED=true
  export REPOPILOT_GITHUB_TOKEN=...

说明:
  - 该脚本会在真实 GitHub 仓库创建远端分支和 PR，请使用可丢弃的公开演示仓库。
  - 仓库内容应与 examples/demo-spring-repo 结构一致，默认任务会生成 User count API patch。
  - 当前 clone 阶段不注入 token；私有仓库需要本机 Git 已有读取凭据。
  - 脚本不会打印 GitHub token、模型 key 或 Authorization header。
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

configured_any() {
  local name
  local value
  for name in "$@"; do
    value="$(eval "printf '%s' \"\${$name:-}\"")"
    if [ -n "$value" ]; then
      return 0
    fi
  done
  return 1
}

is_true() {
  local normalized
  normalized="$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')"
  [ "$normalized" = "true" ] || [ "$normalized" = "1" ] || [ "$normalized" = "yes" ] || [ "$normalized" = "on" ]
}

require_remote_pr_env() {
  local missing=0
  if [ "${REPOPILOT_REAL_GITHUB_PR_CONFIRM:-}" != "create-pr" ]; then
    echo "缺少显式确认：请设置 REPOPILOT_REAL_GITHUB_PR_CONFIRM=create-pr" >&2
    missing=1
  fi
  if [ -z "${REPOPILOT_REAL_GITHUB_PR_REPO_URL:-}" ]; then
    echo "缺少演示仓库：请设置 REPOPILOT_REAL_GITHUB_PR_REPO_URL=https://github.com/<owner>/<demo-repo>.git" >&2
    missing=1
  fi
  if ! is_true "${REPOPILOT_GITHUB_ENABLED:-false}"; then
    echo "缺少远端发布开关：请设置 REPOPILOT_GITHUB_ENABLED=true" >&2
    missing=1
  fi
  if ! configured_any "REPOPILOT_GITHUB_TOKEN" "GITHUB_TOKEN"; then
    echo "缺少 GitHub token：请设置 REPOPILOT_GITHUB_TOKEN 或 GITHUB_TOKEN" >&2
    missing=1
  fi
  if [ "$missing" -ne 0 ]; then
    echo "该脚本会真实创建 GitHub PR，因此必须显式提供仓库、token 和确认开关。" >&2
    echo "可先运行 ./scripts/real-token-demo-check.sh --strict 查看环境状态。" >&2
    exit 2
  fi
}

wait_for_url() {
  local url="$1"
  local label="$2"
  local attempts="${3:-120}"
  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "等待 $label 超时：$url" >&2
  return 1
}

cleanup_demo_data() {
  docker compose exec -T postgres \
    psql \
      -U "${POSTGRES_USER:-repopilot}" \
      -d "${POSTGRES_DB:-repopilot}" \
      -v "demo_email=$DEMO_EMAIL" >/dev/null <<'SQL'
create temp table demo_user_ids as
  select id from app_user where email = :'demo_email';
create temp table demo_task_ids as
  select id from agent_task where user_id in (select id from demo_user_ids);
create temp table demo_run_ids as
  select id from agent_run where agent_task_id in (select id from demo_task_ids);
create temp table demo_patch_ids as
  select id from patch_record where agent_task_id in (select id from demo_task_ids);

delete from pull_request_record
  where agent_task_id in (select id from demo_task_ids)
     or patch_id in (select id from demo_patch_ids);
delete from approval_record
  where agent_task_id in (select id from demo_task_ids)
     or patch_id in (select id from demo_patch_ids)
     or user_id in (select id from demo_user_ids);
delete from test_run
  where agent_run_id in (select id from demo_run_ids)
     or patch_id in (select id from demo_patch_ids);
delete from tool_call_log where agent_run_id in (select id from demo_run_ids);
delete from model_call_log where agent_run_id in (select id from demo_run_ids);
delete from agent_step where agent_run_id in (select id from demo_run_ids);
delete from patch_record where id in (select id from demo_patch_ids);
update agent_task set current_run_id = null where id in (select id from demo_task_ids);
delete from agent_run where id in (select id from demo_run_ids);
delete from agent_task where id in (select id from demo_task_ids);
delete from project where owner_user_id in (select id from demo_user_ids);
delete from app_user where id in (select id from demo_user_ids);
SQL
}

cleanup() {
  if [ -n "$backend_pid" ]; then
    kill "$backend_pid" >/dev/null 2>&1 || true
    wait "$backend_pid" >/dev/null 2>&1 || true
  fi
  cleanup_demo_data || true
  if [ "$started_backend" = true ]; then
    rm -rf "$WORKSPACE_DIR"
  fi
}
trap cleanup EXIT

require_remote_pr_env
mkdir -p "$LOG_DIR" "$WORKSPACE_DIR" "$ROOT_DIR/output/real-github-pr-demo"

echo "RepoPilot 真实 GitHub PR 发布演示"
echo "后端: $BACKEND_URL"
echo "演示仓库: $REPOPILOT_REAL_GITHUB_PR_REPO_URL"
echo "临时用户: $DEMO_EMAIL"

docker compose up -d postgres redis

if curl -fsS "$BACKEND_URL/actuator/health" >/dev/null 2>&1; then
  echo "使用已有后端：$BACKEND_URL"
else
  echo "启动 GitHub PR 演示后端..."
  (
    cd "$ROOT_DIR/backend"
    REPOPILOT_WORKSPACE_ROOT="../target/real-github-pr-demo/workspace" \
      REPOPILOT_GITHUB_ENABLED=true \
      mvn -q -Dmaven.repo.local=../.m2 spring-boot:run
  ) >"$LOG_DIR/backend.log" 2>&1 &
  backend_pid="$!"
  started_backend=true
  wait_for_url "$BACKEND_URL/actuator/health" "backend" 120
fi

REPOPILOT_BACKEND_URL="$BACKEND_URL" \
  REPOPILOT_REAL_GITHUB_PR_DEMO_EMAIL="$DEMO_EMAIL" \
  REPOPILOT_REAL_GITHUB_PR_DEMO_PASSWORD="$DEMO_PASSWORD" \
  node "$ROOT_DIR/scripts/real-github-pr-demo.mjs"
