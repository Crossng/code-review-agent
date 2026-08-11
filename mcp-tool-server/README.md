# RepoPilot MCP 工具目录服务

`mcp-tool-server` 是 RepoPilot 的独立工具目录与参数校验服务。当前版本先把工具清单、输入 schema、安全规则和写型工具审批门独立跑起来；真实工具执行仍由 Spring Boot backend 和 Agent Worker 内部工具桥承担。等工具契约稳定后，再接入 Spring AI MCP Server 传输层。

## 当前能力

- `GET /actuator/health`：服务健康检查。
- `GET /api/mcp/tools`：返回 RepoPilot 工具目录、协议版本和全部工具定义。
- `GET /api/mcp/tools/{toolName}`：返回单个工具定义。
- `POST /api/mcp/tools/{toolName}/validate`：校验工具参数、安全边界和人工审批门。

当前工具目录包含 17 个工具，覆盖：

- 仓库读取：`list_project_files`、`read_file`、`search_code`
- Java 结构分析：`get_class_structure`、`find_controller_api`、`find_call_chain`
- Patch 操作：`validate_unified_diff`、`apply_patch`
- 构建测试：`run_maven_compile`、`run_maven_test`
- Git 与 GitHub：`create_branch`、`get_git_diff`、`commit_changes`、`create_pull_request`
- 审计辅助：`query_db_schema`、`generate_api_doc`、`run_security_check`

## 安全约束

- 文件路径必须是项目工作区内相对路径。
- 拒绝绝对路径、`..` 路径穿越和 `.git` 内部路径。
- 参数必须匹配工具声明的 `string`、`number`、`boolean` 类型，未知参数会被拒绝。
- `limit`、`maxDepth` 等边界参数做整数范围校验。
- `apply_patch`、`run_maven_test`、`commit_changes`、`create_pull_request` 等写型工具默认要求 `approvedByHuman=true`。
- 所有工具定义都标记 `auditRequired=true`，后续执行时必须进入 `tool_call_log` 审计。

## 本地运行

```bash
cd mcp-tool-server
mvn -Dmaven.repo.local=../.m2 spring-boot:run
```

默认端口为 `8095`，可通过环境变量覆盖：

```bash
MCP_TOOL_SERVER_PORT=8096 mvn -Dmaven.repo.local=../.m2 spring-boot:run
```

## 测试

```bash
mvn -q -Dmaven.repo.local=../.m2 test
```

从仓库根目录运行 smoke：

```bash
./scripts/mcp-tool-server-smoke.sh
```

smoke 会临时启动服务，验证工具目录、`read_file` 路径规范化、路径穿越拒绝、`search_code` 类型/未知参数/`limit` 边界拒绝和 `create_pull_request` 人工审批门，并把证据写入 `output/mcp-tool-server-smoke/last-run.json`。
