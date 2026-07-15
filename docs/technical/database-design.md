# 数据库设计

## 1. 数据库选择

MVP 使用 PostgreSQL 作为主数据库，并启用 pgvector 存储代码向量。Redis 用于任务锁、短期事件缓存和异步状态协调。

## 2. 表分组

| 分组 | 表 |
| --- | --- |
| 用户与项目 | `app_user`, `project`, `repository_snapshot`, `controller_api_doc_snapshot` |
| 代码索引 | `code_file`, `code_symbol`, `code_chunk`, `code_embedding` |
| Agent 任务 | `agent_task`, `agent_run`, `agent_step`, `agent_run_report_snapshot` |
| 工具与模型日志 | `tool_call_log`, `model_call_log` |
| Patch 与测试 | `patch_record`, `test_run` |
| 审批与 PR | `approval_record`, `pull_request_record` |

## 3. 核心表

### `app_user`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `email` | varchar(255) | 邮箱，唯一 |
| `password_hash` | varchar(255) | 密码哈希 |
| `display_name` | varchar(100) | 显示名 |
| `role` | varchar(50) | `USER`、`ADMIN` |
| `created_at` | timestamp | 创建时间 |
| `updated_at` | timestamp | 更新时间 |

### `project`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `owner_user_id` | bigint | 用户 ID |
| `repo_url` | text | GitHub 仓库地址 |
| `repo_full_name` | varchar(255) | `owner/name` |
| `default_branch` | varchar(100) | 默认分支 |
| `local_path` | text | 本地受控路径 |
| `status` | varchar(50) | `CREATED`、`CLONING`、`READY`、`FAILED` |
| `last_indexed_at` | timestamp | 最近索引时间 |
| `created_at` | timestamp | 创建时间 |
| `updated_at` | timestamp | 更新时间 |

### `repository_snapshot`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `project_id` | bigint | 项目 ID |
| `branch` | varchar(100) | 分支 |
| `commit_sha` | varchar(80) | commit |
| `file_count` | int | 文件数 |
| `java_file_count` | int | Java 文件数 |
| `created_at` | timestamp | 创建时间 |

### `controller_api_doc_snapshot`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `project_id` | bigint | 项目 ID，项目删除时级联删除 |
| `generated_by_user_id` | bigint | 生成快照的用户 ID |
| `repo_full_name` | varchar(255) | 生成时的仓库名 |
| `generated_at` | timestamp | Markdown 文档生成时间 |
| `route_count` | int | 文档中包含的路由数量 |
| `filtered_count` | bigint | 当前筛选命中的路由总数 |
| `risk_level` | varchar(50) | 生成文档时使用的风险等级筛选，可为空 |
| `risk_code` | varchar(120) | 生成文档时使用的风险码筛选，可为空 |
| `markdown` | text | 生成的 Controller API Markdown 文档 |
| `created_at` | timestamp | 快照记录创建时间 |

### `code_file`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `project_id` | bigint | 项目 ID |
| `snapshot_id` | bigint | 快照 ID |
| `path` | text | 相对路径 |
| `language` | varchar(50) | `JAVA`、`XML`、`YAML` |
| `sha256` | varchar(64) | 内容哈希 |
| `size_bytes` | int | 文件大小 |
| `created_at` | timestamp | 创建时间 |

### `code_symbol`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `project_id` | bigint | 项目 ID |
| `code_file_id` | bigint | 文件 ID |
| `symbol_type` | varchar(50) | `CLASS`、`METHOD`、`FIELD`、`CONTROLLER`、`SERVICE`、`MAPPER`、`ENTITY` |
| `name` | varchar(255) | 符号名 |
| `qualified_name` | text | 全限定名 |
| `annotations` | jsonb | 注解 |
| `start_line` | int | 起始行 |
| `end_line` | int | 结束行 |

### `code_chunk`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `project_id` | bigint | 项目 ID |
| `code_file_id` | bigint | 文件 ID |
| `symbol_id` | bigint | 可为空，关联类或方法符号 |
| `chunk_type` | varchar(50) | `FILE`、`CLASS`、`METHOD` |
| `content` | text | chunk 内容 |
| `summary` | text | 检索摘要 |
| `start_line` | int | 起始行 |
| `end_line` | int | 结束行 |
| `created_at` | timestamp | 创建时间 |

### `code_embedding`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `chunk_id` | bigint | chunk ID |
| `embedding_model` | varchar(100) | 向量模型 |
| `embedding` | vector | 向量 |
| `created_at` | timestamp | 创建时间 |

### `agent_task`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `project_id` | bigint | 项目 ID |
| `user_id` | bigint | 创建者 |
| `task_type` | varchar(50) | `FEATURE`、`BUGFIX`、`REVIEW`、`DOC` |
| `title` | varchar(255) | 标题 |
| `description` | text | 任务描述 |
| `status` | varchar(80) | 当前状态 |
| `current_run_id` | bigint | 当前 run |
| `created_at` | timestamp | 创建时间 |
| `updated_at` | timestamp | 更新时间 |

### `agent_run`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `agent_task_id` | bigint | 任务 ID |
| `status` | varchar(50) | `RUNNING`、`SUCCESS`、`FAILED`、`CANCELLED` |
| `started_at` | timestamp | 开始时间 |
| `finished_at` | timestamp | 结束时间 |
| `error_message` | text | 失败原因 |

### `agent_step`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `agent_run_id` | bigint | run ID |
| `step_name` | varchar(100) | 节点名 |
| `status` | varchar(50) | `PENDING`、`RUNNING`、`SUCCESS`、`FAILED` |
| `input_json` | jsonb | 输入摘要 |
| `output_json` | jsonb | 输出摘要 |
| `started_at` | timestamp | 开始时间 |
| `finished_at` | timestamp | 结束时间 |

### `agent_run_report_snapshot`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `agent_task_id` | bigint | 任务 ID，任务删除时级联删除 |
| `agent_run_id` | bigint | 生成报告时对应的 run，run 删除后置空 |
| `project_id` | bigint | 项目 ID |
| `generated_by_user_id` | bigint | 生成快照的用户 ID |
| `project_name` | varchar(255) | 生成时的项目/仓库名 |
| `task_title` | varchar(255) | 生成时的任务标题 |
| `task_type` | varchar(50) | 生成时的任务类型 |
| `task_status` | varchar(80) | 生成时的任务状态 |
| `run_status` | varchar(50) | 生成时的 run 状态 |
| `run_started_at` | timestamp | run 开始时间 |
| `run_finished_at` | timestamp | run 结束时间，可为空 |
| `report_generated_at` | timestamp | 报告生成时间 |
| `section_count` | int | 报告证据段落数量 |
| `markdown` | text | 固化保存的 Markdown 报告 |
| `created_at` | timestamp | 快照记录创建时间 |

### `patch_record`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `agent_task_id` | bigint | 任务 ID |
| `agent_run_id` | bigint | run ID |
| `base_branch` | varchar(100) | 基础分支 |
| `target_branch` | varchar(100) | 目标分支 |
| `diff_content` | text | unified diff |
| `summary` | text | 修改摘要 |
| `generation_mode` | varchar(100) | 生成路径，例如 `SPRING_USER_PAGINATION_RECIPE`、`SPRING_USER_ID_VALIDATION_RECIPE`、`SPRING_USER_COUNT_RECIPE`、`SPRING_USER_CREATE_RECIPE`、`LLM_CODER_DRAFT`、`SAFE_PLANNING_FALLBACK`、`REPAIR_MISSING_TEST_DEPENDENCY` |
| `status` | varchar(50) | `GENERATED`、`APPLIED`、`APPROVED`、`REJECTED` |
| `created_at` | timestamp | 创建时间 |

### `approval_record`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `agent_task_id` | bigint | 任务 ID |
| `patch_id` | bigint | patch ID |
| `user_id` | bigint | 审批人 |
| `action` | varchar(50) | `APPROVE`、`REJECT` |
| `comment` | text | 审批意见 |
| `created_at` | timestamp | 创建时间 |

### `pull_request_record`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `agent_task_id` | bigint | 任务 ID |
| `patch_id` | bigint | patch ID |
| `provider` | varchar(50) | `GITHUB` |
| `pr_number` | int | GitHub PR 编号，未真实创建时为空 |
| `url` | text | GitHub PR 链接，未真实创建时为空 |
| `title` | varchar(255) | PR 标题 |
| `body` | text | PR 描述 |
| `base_branch` | varchar(100) | PR base 分支 |
| `target_branch` | varchar(100) | 本地准备好的 PR target 分支 |
| `commit_sha` | varchar(80) | 本地准备好的 commit |
| `commit_message` | text | 本地 commit message |
| `status` | varchar(50) | `DRAFT_READY`、`OPEN`、`FAILED` |
| `remote_pushed_at` | timestamp | target branch 推送到远端的时间 |
| `opened_at` | timestamp | GitHub PR 创建时间 |
| `error_message` | text | PR 创建失败原因 |
| `created_at` | timestamp | 创建时间 |
| `updated_at` | timestamp | 更新时间 |

### `test_run`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `agent_run_id` | bigint | run ID |
| `patch_id` | bigint | patch ID |
| `command` | text | 执行命令 |
| `exit_code` | int | 退出码 |
| `duration_ms` | int | 耗时 |
| `log_excerpt` | text | 日志摘要 |
| `status` | varchar(50) | `PASSED`、`FAILED` |
| `created_at` | timestamp | 创建时间 |

### `tool_call_log`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `agent_run_id` | bigint | run ID |
| `tool_name` | varchar(100) | 工具名，例如 `search_code`、`apply_patch`、`run_maven_test` |
| `input_json` | jsonb | 输入摘要，敏感字段脱敏 |
| `output_json` | jsonb | 输出摘要，超大 JSON 截断 |
| `status` | varchar(50) | `SUCCESS`、`FAILED` |
| `duration_ms` | int | 调用耗时 |
| `error_message` | text | 失败原因 |
| `started_at` | timestamp | 开始时间 |
| `finished_at` | timestamp | 结束时间 |

### `model_call_log`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint | 主键 |
| `agent_run_id` | bigint | run ID |
| `step_name` | varchar(100) | 模型型步骤名，例如 `plan_task`、`generate_patch`、`review_patch` |
| `model_provider` | varchar(100) | 模型供应商，MVP 占位为 `LOCAL_PLACEHOLDER` |
| `model_name` | varchar(100) | 模型名，MVP 占位为 `deterministic-mvp` |
| `prompt_json` | jsonb | 输入摘要，敏感字段脱敏 |
| `response_json` | jsonb | 输出摘要，超大 JSON 截断 |
| `status` | varchar(50) | `SUCCESS`、`FAILED` |
| `prompt_tokens` | int | 输入 token 数，占位模式为估算值 |
| `completion_tokens` | int | 输出 token 数，占位模式为估算值 |
| `total_tokens` | int | 总 token 数 |
| `duration_ms` | int | 调用耗时 |
| `error_message` | text | 失败原因 |
| `started_at` | timestamp | 开始时间 |
| `finished_at` | timestamp | 结束时间 |

## 4. 索引建议

| 表 | 索引 |
| --- | --- |
| `project` | `(owner_user_id)`, `(repo_full_name)` |
| `code_file` | `(project_id, path)`, `(snapshot_id)` |
| `code_symbol` | `(project_id, symbol_type)`, `(qualified_name)` |
| `code_chunk` | `(project_id, code_file_id)` |
| `code_embedding` | vector 相似度索引 |
| `agent_task` | `(project_id, status)`, `(user_id, created_at)` |
| `agent_step` | `(agent_run_id, started_at)` |
| `test_run` | `(agent_run_id, created_at)`, `(patch_id, created_at)`, `(status)` |
| `pull_request_record` | `(agent_task_id, created_at)`, `(patch_id, created_at)`, `(commit_sha)` |
| `tool_call_log` | `(agent_run_id, created_at)`, `(tool_name)` |
| `model_call_log` | `(agent_run_id, created_at)`, `(step_name)`, `(model_provider, model_name)` |

## 5. 数据保留策略

- MVP 默认保留所有 Agent run、patch 和日志。
- 大日志只保存摘要，完整日志后续可迁移到对象存储。
- 仓库重新索引时保留历史 snapshot，但最新查询默认使用最新 snapshot。
