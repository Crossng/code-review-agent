# RepoPilot 后端

RepoPilot 的 Spring Boot 主平台，负责鉴权、仓库工作区、Java AST/代码索引、混合检索、Agent 状态机、补丁、Docker 沙箱、审批、PR 和审计数据。

## 代码检索

默认 `REPOPILOT_EMBEDDING_MODE=disabled`，系统直接使用关键词检索，不需要外部密钥。启用 OpenAI-compatible Embedding 后，项目索引会把 chunk 批量向量化并保存到 PostgreSQL `code_embedding.embedding vector`，搜索会融合关键词与 cosine 向量候选。

```bash
export REPOPILOT_EMBEDDING_MODE=openai-compatible
export REPOPILOT_EMBEDDING_API_BASE_URL=http://127.0.0.1:11434/v1
export REPOPILOT_EMBEDDING_MODEL=your-embedding-model
# 本地兼容端点可不设 key；OpenAI 模式需要配置。
export REPOPILOT_EMBEDDING_API_KEY=
```

可调参数见根目录 `.env.example`。模型调用失败不会中断项目索引或 Agent 检索，API 会返回 `FALLBACK` / `KEYWORD_FALLBACK` 并保留关键词结果。

## 运行

```bash
mvn -Dmaven.repo.local=../.m2 spring-boot:run
```

## 测试

```bash
mvn -q -Dmaven.repo.local=../.m2 test
```

后端依赖根目录 `docker-compose.yml` 中的 PostgreSQL 服务。

真实 pgvector 运行时验证：

```bash
cd ..
./scripts/embedding-search-smoke.sh
```
