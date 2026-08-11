#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/target/agent-worker-real-coder-demo/logs"
ARTIFACT_DIR="$ROOT_DIR/output/agent-worker-real-coder-demo"
WORKSPACE_DIR="$ROOT_DIR/target/agent-worker-real-coder-demo/workspace"
DEMO_EMAIL="${REPOPILOT_WORKER_REAL_CODER_DEMO_EMAIL:-agent-worker-real-coder-demo-$(date +%s)-$$@example.test}"
DEMO_PASSWORD="${REPOPILOT_WORKER_REAL_CODER_DEMO_PASSWORD:-password123}"

usage() {
  cat <<'EOF'
RepoPilot Agent Worker 真实 Coder 演示

用法:
  ./scripts/agent-worker-real-coder-demo.sh

需要:
  Docker Desktop 已启动。
  当前 shell 配置 Worker Coder 真实模型：
    export REPOPILOT_WORKER_CODER_MODEL_MODE=openai-compatible
    export REPOPILOT_WORKER_CODER_MODEL_API_KEY=...
    export REPOPILOT_WORKER_CODER_MODEL_NAME=...

说明:
  - 脚本会启动独立端口的真实 Spring Boot 后端和真实 FastAPI Agent Worker。
  - 演示任务只要求模型新增 .repopilot/worker-real-coder-demo-note.md，避免 Java 业务 diff 不稳定。
  - 成功条件包括 WORKER_PRIMARY、LLM_CODER_DRAFT、OPENAI_COMPATIBLE、沙箱 mvn test 通过和人工审批暂停点。
  - 脚本不会打印模型 key、GitHub token 或 Authorization header。
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

is_true_worker_coder_mode() {
  local normalized
  normalized="$(printf '%s' "${REPOPILOT_WORKER_CODER_MODEL_MODE:-}" | tr '[:upper:]' '[:lower:]')"
  [ "$normalized" = "openai" ] || [ "$normalized" = "openai-compatible" ]
}

require_worker_coder_env() {
  local missing=0
  if ! is_true_worker_coder_mode; then
    echo "缺少 Worker Coder 真实模型模式：请设置 REPOPILOT_WORKER_CODER_MODEL_MODE=openai-compatible" >&2
    missing=1
  fi
  if ! configured_any "REPOPILOT_WORKER_CODER_MODEL_API_KEY" "OPENAI_API_KEY"; then
    echo "缺少 Worker Coder 模型 key：请设置 REPOPILOT_WORKER_CODER_MODEL_API_KEY 或 OPENAI_API_KEY" >&2
    missing=1
  fi
  if [ -z "${REPOPILOT_WORKER_CODER_MODEL_NAME:-}" ]; then
    echo "缺少 Worker Coder 模型名：请设置 REPOPILOT_WORKER_CODER_MODEL_NAME" >&2
    missing=1
  fi
  if [ "$missing" -ne 0 ]; then
    echo "可先运行 ./scripts/real-token-demo-check.sh 查看完整环境状态。" >&2
    exit 2
  fi
}

cleanup_demo_data() {
  docker compose exec -T postgres \
    psql \
      -U "${POSTGRES_USER:-repopilot}" \
      -d "${POSTGRES_DB:-repopilot}" \
      -v "demo_email=$DEMO_EMAIL" >/dev/null <<'SQL'
create temp table demo_user_ids as
  select id from app_user where email = :'demo_email';
create temp table demo_project_ids as
  select id from project where owner_user_id in (select id from demo_user_ids);
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
delete from project where id in (select id from demo_project_ids);
delete from app_user where id in (select id from demo_user_ids);
SQL
}

cleanup() {
  cleanup_demo_data || true
  rm -rf "$WORKSPACE_DIR"
}
trap cleanup EXIT

require_worker_coder_env
mkdir -p "$LOG_DIR" "$ARTIFACT_DIR" "$WORKSPACE_DIR"

echo "RepoPilot Agent Worker 真实 Coder 演示"
echo "临时用户: $DEMO_EMAIL"
echo "日志目录: $LOG_DIR"

docker compose up -d postgres redis

REPOPILOT_WORKER_REAL_CODER_DEMO_EMAIL="$DEMO_EMAIL" \
REPOPILOT_WORKER_REAL_CODER_DEMO_PASSWORD="$DEMO_PASSWORD" \
REPOPILOT_WORKER_REAL_CODER_DEMO_LOG_DIR="$LOG_DIR" \
REPOPILOT_WORKER_REAL_CODER_DEMO_ARTIFACT_DIR="$ARTIFACT_DIR" \
REPOPILOT_WORKER_REAL_CODER_DEMO_WORKSPACE="$WORKSPACE_DIR" \
  node "$ROOT_DIR/scripts/agent-worker-real-coder-demo.mjs"
