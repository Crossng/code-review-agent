#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STRICT=false
START_DEPS=false

usage() {
  cat <<'EOF'
RepoPilot 真实演示环境检查

用法:
  ./scripts/real-token-demo-check.sh [--strict] [--start-deps]

选项:
  --strict      正式演示前使用。真实 Coder、远端 GitHub PR、Docker 依赖缺项会返回非 0。
  --start-deps 先执行 docker compose up -d postgres redis，再检查依赖状态。
  -h, --help   显示帮助。

说明:
  默认模式只做只读检查，缺少真实 token 时给出下一步，不会失败。
  脚本只展示环境变量是否配置，不打印 GitHub token、模型 key 或 Authorization header。
EOF
}

for arg in "$@"; do
  case "$arg" in
    --strict)
      STRICT=true
      ;;
    --start-deps)
      START_DEPS=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $arg" >&2
      usage >&2
      exit 2
      ;;
  esac
done

pass_count=0
warn_count=0
miss_count=0
strict_failures=0

line() {
  local status="$1"
  local label="$2"
  local detail="$3"
  printf '%-6s %-24s %s\n' "$status" "$label" "$detail"
}

pass() {
  line "PASS" "$1" "$2"
  pass_count=$((pass_count + 1))
}

warn() {
  line "WARN" "$1" "$2"
  warn_count=$((warn_count + 1))
}

miss() {
  line "MISS" "$1" "$2"
  miss_count=$((miss_count + 1))
}

strict_miss() {
  miss "$1" "$2"
  strict_failures=$((strict_failures + 1))
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

is_true() {
  local normalized
  normalized="$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')"
  [ "$normalized" = "true" ] || [ "$normalized" = "1" ] || [ "$normalized" = "yes" ] || [ "$normalized" = "on" ]
}

is_real_coder_mode() {
  local normalized
  normalized="$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')"
  [ "$normalized" = "openai" ] || [ "$normalized" = "openai-compatible" ]
}

check_command() {
  local command_name="$1"
  if command -v "$command_name" >/dev/null 2>&1; then
    pass "$command_name" "$(command -v "$command_name")"
  else
    if [ "$STRICT" = true ]; then
      strict_miss "$command_name" "未找到命令，正式演示前需要安装"
    else
      miss "$command_name" "未找到命令"
    fi
  fi
}

check_file() {
  local file_path="$1"
  if [ -f "$ROOT_DIR/$file_path" ]; then
    pass "$file_path" "存在"
  else
    if [ "$STRICT" = true ]; then
      strict_miss "$file_path" "缺少项目文件"
    else
      miss "$file_path" "缺少项目文件"
    fi
  fi
}

check_port() {
  local port="$1"
  local label="$2"
  if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    pass "$label" "端口 $port 已有进程监听"
  else
    warn "$label" "端口 $port 当前未监听，演示脚本可按需启动"
  fi
}

section() {
  printf '\n## %s\n' "$1"
}

echo "RepoPilot 真实演示环境检查"
echo "仓库: $ROOT_DIR"
if [ "$STRICT" = true ]; then
  echo "模式: strict，缺少真实演示关键项会失败"
else
  echo "模式: default，只提示缺项，不因为未配置 token 失败"
fi

section "项目文件"
check_file ".env.example"
check_file "docker-compose.yml"
check_file "backend/pom.xml"
check_file "frontend/package.json"

section "本机命令"
check_command "git"
check_command "java"
check_command "mvn"
check_command "npm"
check_command "docker"

docker_ready=false
docker_compose_ready=false
if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then
    docker_ready=true
    pass "Docker daemon" "可访问"
  else
    if [ "$STRICT" = true ]; then
      strict_miss "Docker daemon" "不可访问，请先启动 Docker Desktop"
    else
      miss "Docker daemon" "不可访问，请先启动 Docker Desktop"
    fi
  fi

  if docker compose version >/dev/null 2>&1; then
    docker_compose_ready=true
    pass "Docker Compose" "$(docker compose version --short 2>/dev/null || echo "可用")"
  else
    if [ "$STRICT" = true ]; then
      strict_miss "Docker Compose" "docker compose 子命令不可用"
    else
      miss "Docker Compose" "docker compose 子命令不可用"
    fi
  fi
fi

if [ "$START_DEPS" = true ]; then
  section "启动基础依赖"
  if [ "$docker_compose_ready" = true ]; then
    (cd "$ROOT_DIR" && docker compose up -d postgres redis)
    pass "PostgreSQL/Redis" "已请求启动"
  else
    strict_miss "PostgreSQL/Redis" "无法启动，Docker Compose 不可用"
  fi
fi

section "Docker 依赖"
if [ "$docker_ready" = true ] && [ "$docker_compose_ready" = true ]; then
  if (cd "$ROOT_DIR" && docker compose ps postgres redis >/dev/null 2>&1); then
    pass "Compose services" "postgres/redis 可查询；需要启动时可加 --start-deps"
  else
    warn "Compose services" "暂未查询到 postgres/redis 状态；需要启动时可加 --start-deps"
  fi
fi

sandbox_image="${REPOPILOT_SANDBOX_IMAGE:-maven:3.9-eclipse-temurin-17}"
sandbox_timeout="${REPOPILOT_SANDBOX_TIMEOUT_SECONDS:-600}"
maven_cache="${REPOPILOT_MAVEN_CACHE:-../.m2}"
pass "Sandbox image" "$sandbox_image"
pass "Sandbox timeout" "${sandbox_timeout}s"

maven_cache_candidates=()
if [[ "$maven_cache" = /* ]]; then
  maven_cache_candidates=("$maven_cache")
else
  maven_cache_candidates=("$ROOT_DIR/$maven_cache" "$ROOT_DIR/backend/$maven_cache" "$maven_cache")
fi

maven_cache_resolved=""
for candidate in "${maven_cache_candidates[@]}"; do
  if [ -d "$candidate" ]; then
    maven_cache_resolved="$(cd "$candidate" && pwd)"
    break
  fi
done

if [ -n "$maven_cache_resolved" ]; then
  pass "Maven cache" "$maven_cache -> $maven_cache_resolved"
else
  warn "Maven cache" "$maven_cache 当前不存在，首次沙箱测试会创建或下载依赖"
fi

section "真实 Coder"
coder_mode="${REPOPILOT_CODER_MODE:-disabled}"
coder_base_url="${REPOPILOT_CODER_API_BASE_URL:-https://api.openai.com/v1}"
coder_model="${REPOPILOT_CODER_MODEL:-}"
coder_key_ready=false
coder_ready=false

if configured_any "REPOPILOT_CODER_API_KEY" "OPENAI_API_KEY"; then
  coder_key_ready=true
fi

pass "Coder mode" "$coder_mode"
pass "Coder API base URL" "$coder_base_url"
if [ -n "$coder_model" ]; then
  pass "Coder model" "$coder_model"
else
  warn "Coder model" "未配置 REPOPILOT_CODER_MODEL"
fi
if [ "$coder_key_ready" = true ]; then
  pass "Coder key" "已配置 REPOPILOT_CODER_API_KEY 或 OPENAI_API_KEY"
else
  warn "Coder key" "未配置 REPOPILOT_CODER_API_KEY / OPENAI_API_KEY"
fi

if is_real_coder_mode "$coder_mode" && [ "$coder_key_ready" = true ] && [ -n "$coder_model" ]; then
  coder_ready=true
  pass "真实模型演示" "可演示；模型输出仍会经过 parser、安全预检、沙箱测试和人工审批"
else
  detail="当前会使用本地 recipe 或安全规划回退；真实模型需 REPOPILOT_CODER_MODE=openai-compatible、key 和 model"
  if [ "$STRICT" = true ]; then
    strict_miss "真实模型演示" "$detail"
  else
    warn "真实模型演示" "$detail"
  fi
fi

section "远端 GitHub PR"
github_enabled="${REPOPILOT_GITHUB_ENABLED:-false}"
github_base_url="${REPOPILOT_GITHUB_API_BASE_URL:-https://api.github.com}"
github_token_ready=false
github_ready=false

if configured_any "REPOPILOT_GITHUB_TOKEN" "GITHUB_TOKEN"; then
  github_token_ready=true
fi

pass "GitHub API base URL" "$github_base_url"
if is_true "$github_enabled"; then
  pass "GitHub remote PR" "已启用 REPOPILOT_GITHUB_ENABLED=true"
else
  warn "GitHub remote PR" "未启用；默认只生成本地 DRAFT_READY 分支和 commit"
fi
if [ "$github_token_ready" = true ]; then
  pass "GitHub token" "已配置 REPOPILOT_GITHUB_TOKEN 或 GITHUB_TOKEN"
else
  warn "GitHub token" "未配置 REPOPILOT_GITHUB_TOKEN / GITHUB_TOKEN"
fi

if is_true "$github_enabled" && [ "$github_token_ready" = true ]; then
  github_ready=true
  pass "远端 PR 演示" "可演示；审批后会 push target branch 并调用 GitHub PR API"
else
  detail="本地草稿模式可演示；远端 PR 需 REPOPILOT_GITHUB_ENABLED=true 和 GitHub token"
  if [ "$STRICT" = true ]; then
    strict_miss "远端 PR 演示" "$detail"
  else
    warn "远端 PR 演示" "$detail"
  fi
fi

section "端口"
check_port "${BACKEND_PORT:-8080}" "Backend"
check_port "${FRONTEND_PORT:-5173}" "Frontend"

section "推荐命令"
cat <<'EOF'
本地闭环演示:
  ./scripts/browser-smoke.sh

检查并启动 PostgreSQL/Redis:
  ./scripts/real-token-demo-check.sh --start-deps

正式真实 token 演示前:
  export REPOPILOT_CODER_MODE=openai-compatible
  export REPOPILOT_CODER_API_KEY=...
  export REPOPILOT_CODER_MODEL=...
  export REPOPILOT_GITHUB_ENABLED=true
  export REPOPILOT_GITHUB_TOKEN=...
  ./scripts/real-token-demo-check.sh --strict
EOF

section "结果"
printf 'PASS=%s WARN=%s MISS=%s\n' "$pass_count" "$warn_count" "$miss_count"

if [ "$STRICT" = true ]; then
  if [ "$docker_ready" != true ]; then
    strict_failures=$((strict_failures + 1))
  fi
  if [ "$coder_ready" != true ]; then
    :
  fi
  if [ "$github_ready" != true ]; then
    :
  fi
  if [ "$strict_failures" -gt 0 ]; then
    echo "strict 检查未通过：还有 $strict_failures 个关键缺项。"
    exit 1
  fi
fi

echo "检查完成。"
