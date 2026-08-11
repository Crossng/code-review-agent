#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/target/mcp-tool-server-smoke/logs"
ARTIFACT_DIR="$ROOT_DIR/output/mcp-tool-server-smoke"

mkdir -p "$LOG_DIR" "$ARTIFACT_DIR"

echo "RepoPilot MCP 工具目录 smoke"

REPOPILOT_MCP_TOOL_SERVER_SMOKE_LOG_DIR="$LOG_DIR" \
REPOPILOT_MCP_TOOL_SERVER_SMOKE_ARTIFACT_DIR="$ARTIFACT_DIR" \
  node "$ROOT_DIR/scripts/mcp-tool-server-smoke.mjs"
