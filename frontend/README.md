# RepoPilot 前端

RepoPilot 的 Vite + React 中文工程工作台。页面按真实操作顺序组织仓库接入、任务创建、Agent 执行、代码洞察、运行数据和系统配置，并保留后端状态枚举、命令、路径等工程原文。

## 当前能力

- 中文工作区导航和“任务 -> 补丁 -> 沙箱测试 -> Pull Request”执行链路。
- 仓库接入、项目筛选、代码洞察和 Controller API 风险视图。
- Agent 任务、运行证据、模型/工具审计、补丁、测试、审批和 PR 结果。
- 桌面、平板和移动端响应式布局；移动端导航独立横向滚动，不撑宽页面。
- 跳到主要内容、可见键盘焦点、`aria-live` 状态提示和破坏性操作确认。

## 本地运行

```bash
npm install
npm run dev
```

## 构建

```bash
npm run build
```

## 浏览器验收

从仓库根目录运行：

```bash
./scripts/browser-smoke.sh
```

脚本会执行完整业务闭环，并在 `output/playwright/` 生成桌面端、移动端和全页截图。
