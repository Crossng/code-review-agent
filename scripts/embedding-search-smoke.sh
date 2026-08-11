#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/target/embedding-search-smoke/logs"
ARTIFACT_DIR="$ROOT_DIR/output/embedding-search-smoke"
WORKSPACE_DIR="$ROOT_DIR/target/embedding-search-smoke/workspace"
SMOKE_EMAIL="${REPOPILOT_EMBEDDING_SMOKE_EMAIL:-embedding-search-smoke-$(date +%s)-$$@example.test}"

mkdir -p "$LOG_DIR" "$ARTIFACT_DIR" "$WORKSPACE_DIR"

cleanup_smoke_data() {
  docker compose exec -T postgres \
    psql \
      -U "${POSTGRES_USER:-repopilot}" \
      -d "${POSTGRES_DB:-repopilot}" \
      -v "smoke_email=$SMOKE_EMAIL" >/dev/null <<'SQL'
delete from project
  where owner_user_id in (select id from app_user where email = :'smoke_email');
delete from app_user where email = :'smoke_email';
SQL
}

cleanup() {
  cleanup_smoke_data || true
  rm -rf "$WORKSPACE_DIR"
}
trap cleanup EXIT

echo "RepoPilot 代码向量混合检索 smoke"
docker compose up -d postgres redis

REPOPILOT_EMBEDDING_SMOKE_EMAIL="$SMOKE_EMAIL" \
REPOPILOT_EMBEDDING_SMOKE_LOG_DIR="$LOG_DIR" \
REPOPILOT_EMBEDDING_SMOKE_ARTIFACT_DIR="$ARTIFACT_DIR" \
REPOPILOT_EMBEDDING_SMOKE_WORKSPACE="$WORKSPACE_DIR" \
  node "$ROOT_DIR/scripts/embedding-search-smoke.mjs"
