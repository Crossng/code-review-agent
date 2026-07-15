# Scripts

后续放本地开发、索引、演示、清理脚本。MVP 阶段优先保持脚本短小、可读、可重复执行。

## Browser Smoke

```bash
./scripts/browser-smoke.sh
```

该脚本会：

- 启动 PostgreSQL 和 Redis。
- 若本地 8080/5173 未运行，则临时启动后端和前端。
- 安装/确认 Playwright Chromium。
- 在真实浏览器中注册 smoke 用户、创建本地 demo 项目、Clone、Index、刷新代码地图、验证 Controller API 风险筛选并搜索 `UserService`。
- 创建 Agent 任务，运行 Agent，验证步骤、模型调用、工具调用、任务 SSE 快照流、真实 `GET /api/users/page` patch、文件级 `changedFiles` 摘要、自动风险审查、Maven 沙箱测试，点击 Regenerate 并校验新 Run/Patch/Test run 编号，再完成人工审批和本地 `DRAFT_READY` PR 准备记录。
- 创建第二个 User id 参数校验任务，验证真实 guard patch、`UserServiceTest` 覆盖、文件级 `changedFiles` 摘要、自动风险审查和 Maven 沙箱测试。
- 将截图写入 `output/playwright/repopilot-browser-smoke.png`。
- 清理本次 smoke 创建的用户、项目、任务、运行、补丁、测试、审批、PR 数据和专用 workspace。
