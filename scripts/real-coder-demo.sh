#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/target/real-coder-demo/logs"
WORKSPACE_DIR="$ROOT_DIR/target/real-coder-demo/workspace"
BACKEND_URL="${REPOPILOT_BACKEND_URL:-http://127.0.0.1:8080}"
DEMO_EMAIL="${REPOPILOT_REAL_CODER_DEMO_EMAIL:-real-coder-demo-$(date +%s)-$$@example.test}"
DEMO_PASSWORD="${REPOPILOT_REAL_CODER_DEMO_PASSWORD:-password123}"

backend_pid=""
started_backend=false

usage() {
  cat <<'EOF'
RepoPilot 真实 Coder 端到端演示

用法:
  ./scripts/real-coder-demo.sh

需要:
  Docker Desktop 已启动。
  如 8080 没有后端进程，本脚本会启动后端，并要求当前 shell 配置真实 Coder：
    export REPOPILOT_CODER_MODE=openai-compatible
    export REPOPILOT_CODER_API_KEY=...
    export REPOPILOT_CODER_MODEL=...

说明:
  - 脚本会创建临时用户、项目、任务，运行真实 openai-compatible Coder。
  - 演示任务会要求模型只新增 .repopilot/real-coder-demo-note.md，避免 Java 代码不稳定。
  - 成功条件包括 LLM_CODER_DRAFT、OPENAI_COMPATIBLE、沙箱 mvn test 通过。
  - 脚本不会打印模型 key、GitHub token 或 Authorization header。
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

is_true_coder_mode() {
  local normalized
  normalized="$(printf '%s' "${REPOPILOT_CODER_MODE:-}" | tr '[:upper:]' '[:lower:]')"
  [ "$normalized" = "openai" ] || [ "$normalized" = "openai-compatible" ]
}

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

require_coder_env_for_backend_start() {
  local missing=0
  if ! is_true_coder_mode; then
    echo "缺少真实 Coder 模式：请设置 REPOPILOT_CODER_MODE=openai-compatible" >&2
    missing=1
  fi
  if ! configured_any "REPOPILOT_CODER_API_KEY" "OPENAI_API_KEY"; then
    echo "缺少模型 key：请设置 REPOPILOT_CODER_API_KEY 或 OPENAI_API_KEY" >&2
    missing=1
  fi
  if [ -z "${REPOPILOT_CODER_MODEL:-}" ]; then
    echo "缺少模型名：请设置 REPOPILOT_CODER_MODEL" >&2
    missing=1
  fi
  if [ "$missing" -ne 0 ]; then
    echo "后端未运行时，本脚本需要这些环境变量来启动真实 Coder 后端。" >&2
    echo "可先运行 ./scripts/real-token-demo-check.sh 查看完整环境状态。" >&2
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

mkdir -p "$LOG_DIR" "$WORKSPACE_DIR" "$ROOT_DIR/output/real-coder-demo"

echo "RepoPilot 真实 Coder 端到端演示"
echo "后端: $BACKEND_URL"
echo "临时用户: $DEMO_EMAIL"

docker compose up -d postgres redis

if curl -fsS "$BACKEND_URL/actuator/health" >/dev/null 2>&1; then
  echo "使用已有后端：$BACKEND_URL"
else
  require_coder_env_for_backend_start
  echo "启动真实 Coder 后端..."
  (
    cd "$ROOT_DIR/backend"
    REPOPILOT_WORKSPACE_ROOT="../target/real-coder-demo/workspace" \
      REPOPILOT_GITHUB_ENABLED="${REPOPILOT_GITHUB_ENABLED:-false}" \
      mvn -q -Dmaven.repo.local=../.m2 spring-boot:run
  ) >"$LOG_DIR/backend.log" 2>&1 &
  backend_pid="$!"
  started_backend=true
  wait_for_url "$BACKEND_URL/actuator/health" "backend" 120
fi

REPOPILOT_BACKEND_URL="$BACKEND_URL" \
  REPOPILOT_REAL_CODER_DEMO_EMAIL="$DEMO_EMAIL" \
  REPOPILOT_REAL_CODER_DEMO_PASSWORD="$DEMO_PASSWORD" \
  node "$ROOT_DIR/scripts/real-coder-demo.mjs"
