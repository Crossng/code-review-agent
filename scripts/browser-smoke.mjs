import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { mkdir, readFile } from "node:fs/promises";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const requireFromFrontend = createRequire(join(repoRoot, "frontend", "package.json"));
const { chromium } = requireFromFrontend("playwright");

const frontendUrl = process.env.REPOPILOT_FRONTEND_URL ?? "http://127.0.0.1:5173/";
const email = process.env.REPOPILOT_SMOKE_EMAIL ?? `browser-smoke-${Date.now()}@example.test`;
const password = process.env.REPOPILOT_SMOKE_PASSWORD ?? "password123";
const displayName = process.env.REPOPILOT_SMOKE_DISPLAY_NAME ?? "Browser Smoke";
const repoUrl = process.env.REPOPILOT_SMOKE_REPO_URL
  ?? pathToFileURL(join(repoRoot, "examples", "demo-spring-repo")).toString();
const artifactDir = process.env.REPOPILOT_SMOKE_ARTIFACT_DIR ?? join(repoRoot, "output", "playwright");
const headless = process.env.PLAYWRIGHT_HEADED !== "1";
const frontendOrigin = new URL(frontendUrl).origin;

await mkdir(artifactDir, { recursive: true });

const browser = await chromium.launch({ headless });
const context = await browser.newContext({ acceptDownloads: true, viewport: { width: 1440, height: 1100 } });
await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: frontendOrigin });
const page = await context.newPage();
page.setDefaultTimeout(240_000);

try {
  await page.goto(frontendUrl, { waitUntil: "domcontentloaded" });

  const registerForm = page.locator("form").filter({ hasText: "创建本地账号" });
  await registerForm.getByLabel("显示名称").fill(displayName);
  await registerForm.getByLabel("邮箱").fill(email);
  await registerForm.getByLabel("密码").fill(password);
  await registerForm.getByRole("button", { name: "注册" }).click();
  await page.getByRole("button", { name: "退出登录" }).waitFor();
  const overview = page.locator(".dashboardSummaryPanel");
  await overview.getByText("工作台概览").waitFor();
  await expectDashboardMetric(page, overview, "项目", "0/0 就绪");
  await expectDashboardMetric(page, overview, "任务", "0 个任务");
  await expectDashboardMetric(page, overview, "待审批", "0");
  const runMetrics = page.locator(".dashboardRunMetricsPanel");
  await runMetrics.getByText("Agent 运行表现").waitFor();
  await runMetrics.locator(".sectionHeader").getByText(/最近 7 天，/).waitFor();
  await expectDashboardMetric(page, runMetrics, "运行次数", "0");
  await expectDashboardMetric(page, runMetrics, "成功率", "0%");
  const runMetricsWindowResponse = waitForRunMetricsQuery(page, "14");
  await runMetrics.getByLabel("运行指标窗口").selectOption("14");
  await runMetricsWindowResponse;
  await runMetrics.locator(".sectionHeader").getByText(/最近 14 天，/).waitFor();
  await page.waitForFunction(() => new URLSearchParams(window.location.search).get("runMetricsDays") === "14");
  await expectDashboardMetric(page, runMetrics, "运行次数", "0");
  const reloadRunMetricsWindowResponse = waitForRunMetricsQuery(page, "14");
  await page.reload({ waitUntil: "domcontentloaded" });
  await reloadRunMetricsWindowResponse;
  await runMetrics.locator(".sectionHeader").getByText(/最近 14 天，/).waitFor();
  await page.waitForFunction(() => {
    const select = document.querySelector('[aria-label="运行指标窗口"]');
    return select?.value === "14";
  });
  const activity = page.locator(".dashboardActivityPanel");
  await activity.getByText("最近任务活动").waitFor();
  await activity.locator(".sectionHeader").getByText("最近 10 条中的 0 条").waitFor();
  await activity.getByText("还没有任务活动。").waitFor();
  const activityLimitResponse = waitForActivityQuery(page, "25");
  await activity.getByLabel("活动数量").selectOption("25");
  await activityLimitResponse;
  await activity.locator(".sectionHeader").getByText("最近 25 条中的 0 条").waitFor();
  await page.waitForFunction(() => new URLSearchParams(window.location.search).get("activityLimit") === "25");
  const reloadActivityLimitResponse = waitForActivityQuery(page, "25");
  await page.reload({ waitUntil: "domcontentloaded" });
  await reloadActivityLimitResponse;
  await activity.locator(".sectionHeader").getByText("最近 25 条中的 0 条").waitFor();
  await page.waitForFunction(() => {
    const select = document.querySelector('[aria-label="活动数量"]');
    return select?.value === "25";
  });
  await overview.getByRole("button", { name: "复制概览链接" }).click();
  await overview.getByText("概览链接已复制").waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) => {
      if (!text) {
        return false;
      }
      const url = new URL(text);
      return url.hash === "#overview"
        && url.searchParams.get("runMetricsDays") === "14"
        && url.searchParams.get("activityLimit") === "25";
    }).catch(() => false)
  );
  const coderSettings = page.locator(".coderSettingsPanel");
  await coderSettings.getByText("Coder 配置").waitFor();
  await coderSettings.getByText("模型提供方状态").waitFor();
  await coderSettings.locator(".badge").filter({ hasText: /^NONE$/ }).waitFor();
  await coderSettings.locator(".badge").filter({ hasText: /^READY$/ }).waitFor();
  await coderSettings.getByText("Recipe 与安全兜底模式").waitFor();
  await coderSettings.getByText("密钥由环境变量管理").waitFor();
  const githubSettings = page.locator(".githubSettingsPanel");
  await githubSettings.getByText("GitHub 发布").waitFor();
  await githubSettings.getByText("Pull Request 发布").waitFor();
  await githubSettings.locator(".badge").filter({ hasText: /^GITHUB$/ }).waitFor();
  await githubSettings.locator(".badge").filter({ hasText: /^READY$/ }).waitFor();
  await githubSettings.getByText("LOCAL_DRAFT_ONLY").waitFor();
  await githubSettings.getByText("本地 PR 草稿准备").waitFor();
  await githubSettings.getByText("GitHub token 由环境变量管理").waitFor();
  const sandboxSettings = page.locator(".sandboxSettingsPanel");
  await sandboxSettings.getByText("沙箱运行时").waitFor();
  await sandboxSettings.getByText("Docker 沙箱").waitFor();
  await sandboxSettings.locator(".badge").filter({ hasText: /^READY$/ }).waitFor();
  await sandboxSettings.getByText("maven:3.9-eclipse-temurin-17").waitFor();
  await sandboxSettings.getByText("沙箱就绪检查").waitFor();
  await sandboxSettings.getByText("Maven 缓存路径：").waitFor();

  const projectForm = page.locator("form").filter({ hasText: "添加项目" });
  await projectForm.getByLabel("仓库地址").fill(repoUrl);
  await projectForm.getByLabel("默认分支").fill("main");
  await projectForm.getByRole("button", { name: "创建项目" }).click();
  await page.locator(".projectRow").filter({ hasText: "CREATED" }).waitFor();
  await page.getByRole("button", { name: "克隆" }).first().waitFor();

  await clickAndWaitForIdle(page, page.getByRole("button", { name: "克隆" }).first());
  await page.locator(".projectRow").filter({ hasText: "READY" }).waitFor();

  const projectFilters = page.getByLabel("项目筛选");
  await projectFilters.getByLabel("搜索项目").fill("demo-spring-repo");
  await clickAndWaitForIdle(page, projectFilters.getByRole("button", { name: "应用筛选" }));
  await page.locator(".projectRow").filter({ hasText: "READY" }).waitFor();
  await projectFilters.getByLabel("项目状态筛选").selectOption("READY");
  await clickAndWaitForIdle(page, projectFilters.getByRole("button", { name: "应用筛选" }));
  await page.locator(".projectRow").filter({ hasText: "READY" }).waitFor();
  await page.waitForFunction(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get("projectStatus") === "READY"
      && params.get("projectQuery") === "demo-spring-repo"
      && params.has("projectId");
  });
  const reloadFilteredProjects = waitForProjectListQuery(page, { status: "READY", query: "demo-spring-repo" });
  await page.reload({ waitUntil: "domcontentloaded" });
  await reloadFilteredProjects;
  await page.locator(".projectRow").filter({ hasText: "READY" }).waitFor();
  await page.waitForFunction(() => {
    const status = document.querySelector('[aria-label="项目状态筛选"]');
    return status?.value === "READY";
  });
  const restoredProjectId = await page.evaluate(() => new URLSearchParams(window.location.search).get("projectId"));
  await page.waitForFunction((projectId) => {
    const insightProject = document.querySelector('[aria-label="洞察项目"]');
    return projectId !== null && insightProject?.value === projectId;
  }, restoredProjectId);
  const restoredProjectQuery = await projectFilters.getByLabel("搜索项目").inputValue();
  if (restoredProjectQuery !== "demo-spring-repo") {
    throw new Error(`Expected restored project query demo-spring-repo, got ${restoredProjectQuery}`);
  }
  await projectFilters.getByRole("button", { name: "复制项目视图链接" }).click();
  await projectFilters.getByText("项目链接已复制").waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) => {
      if (!text) {
        return false;
      }
      const url = new URL(text);
      return url.searchParams.get("projectStatus") === "READY"
        && url.searchParams.get("projectQuery") === "demo-spring-repo"
        && url.searchParams.has("projectId");
    }).catch(() => false)
  );
  await clickAndWaitForIdle(page, projectFilters.getByRole("button", { name: "重置" }));
  await page.locator(".projectRow").filter({ hasText: "READY" }).waitFor();
  await page.waitForFunction(() => {
    const params = new URLSearchParams(window.location.search);
    return !params.has("projectStatus") && !params.has("projectQuery");
  });

  await clickAndWaitForIdle(page, page.getByRole("button", { name: "索引" }).first());
  await expectDashboardMetric(page, overview, "项目", "1/1 就绪");
  await clickAndWaitForIdle(page, page.getByRole("button", { name: "刷新地图" }));

  const insight = page.locator(".projectInsightPanel");
  await insight.getByText("SERVICE 1").waitFor();
  await insight.getByText("src/main/java/com/example/demo/user/UserService.java").first().waitFor();
  const mediumApiResponse = waitForControllerApiQuery(page, { riskLevel: "MEDIUM" });
  await insight.getByLabel("Controller 接口风险概览").getByRole("button", { name: /MEDIUM/ }).click();
  await mediumApiResponse;
  await page.waitForFunction(() => {
    const riskLevel = document.querySelector('[aria-label="风险等级"]');
    return riskLevel?.value === "MEDIUM";
  });
  await insight.getByLabel("风险等级").selectOption("MEDIUM");
  const filteredApiResponse = waitForControllerApiQuery(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION"
  });
  await insight.getByLabel("风险码").selectOption("NO_SECURITY_ANNOTATION");
  await filteredApiResponse;
  await insight.getByText("2 / 2 个接口").waitFor();
  await page.waitForFunction(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get("controllerRiskLevel") === "MEDIUM"
      && params.get("controllerRiskCode") === "NO_SECURITY_ANNOTATION";
  });
  await insight.getByRole("button", { name: "复制风险视图链接" }).click();
  await insight.getByText("链接已复制").waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) => {
      if (!text) {
        return false;
      }
      const url = new URL(text);
      return url.searchParams.get("controllerRiskLevel") === "MEDIUM"
        && url.searchParams.get("controllerRiskCode") === "NO_SECURITY_ANNOTATION";
    }).catch(() => false)
  );
  const reloadFilteredApiResponse = waitForControllerApiQuery(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION"
  });
  await page.reload({ waitUntil: "domcontentloaded" });
  await reloadFilteredApiResponse;
  await insight.getByText("2 / 2 个接口").waitFor();
  await page.waitForFunction(() => {
    const riskLevel = document.querySelector('[aria-label="风险等级"]');
    const riskCode = document.querySelector('[aria-label="风险码"]');
    return riskLevel?.value === "MEDIUM" && riskCode?.value === "NO_SECURITY_ANNOTATION";
  });
  await insight.getByRole("button", { name: "复制路由链接" }).first().click();
  await insight.getByText("路由链接已复制").waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) => {
      if (!text) {
        return false;
      }
      const url = new URL(text);
      return url.hash.startsWith("#controller-api-")
        && url.searchParams.get("controllerRiskLevel") === "MEDIUM"
        && url.searchParams.get("controllerRiskCode") === "NO_SECURITY_ANNOTATION";
    }).catch(() => false)
  );
  const controllerApiDocsResponse = waitForControllerApiDocsQuery(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION",
    limit: "2"
  });
  await insight.getByRole("button", { name: "复制接口文档" }).click();
  await controllerApiDocsResponse;
  await insight.getByText("接口文档已复制").waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) =>
      text.includes("# Controller API docs:")
      && text.includes("demo-spring-repo")
      && text.includes("Current filters: Risk level: MEDIUM, Risk code: NO_SECURITY_ANNOTATION.")
      && text.includes("## GET /api/users")
      && text.includes("NO_SECURITY_ANNOTATION")
    ).catch(() => false)
  );
  const controllerApiDocsDownloadResponse = waitForControllerApiDocsQuery(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION",
    limit: "2"
  });
  const apiDocsDownloadPromise = page.waitForEvent("download");
  await insight.getByRole("button", { name: "下载接口文档" }).click();
  await controllerApiDocsDownloadResponse;
  const apiDocsDownload = await apiDocsDownloadPromise;
  await insight.getByText("接口文档已下载").waitFor();
  const apiDocsFilename = apiDocsDownload.suggestedFilename();
  if (!apiDocsFilename.endsWith(".md") || !apiDocsFilename.includes("controller-api-docs")) {
    throw new Error(`Expected controller API docs markdown filename, got ${apiDocsFilename}`);
  }
  const apiDocsPath = await apiDocsDownload.path();
  if (apiDocsPath === null) {
    throw new Error("Controller API docs download path was unavailable.");
  }
  const downloadedApiDocs = await readFile(apiDocsPath, "utf8");
  if (
    !downloadedApiDocs.includes("# Controller API docs:")
    || !downloadedApiDocs.includes("Current filters: Risk level: MEDIUM, Risk code: NO_SECURITY_ANNOTATION.")
    || !downloadedApiDocs.includes("## GET /api/users")
  ) {
    throw new Error("Downloaded Controller API docs did not contain expected Markdown content.");
  }
  const controllerApiDocsSnapshotResponse = waitForControllerApiDocsSnapshotCreate(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION",
    limit: "2"
  });
  await insight.getByRole("button", { name: "保存接口文档快照" }).click();
  const snapshotResponse = await controllerApiDocsSnapshotResponse;
  const snapshotPayload = await snapshotResponse.json();
  const snapshotId = snapshotPayload.data.id;
  if (!Number.isInteger(snapshotId) || snapshotId <= 0) {
    throw new Error(`Expected saved Controller API docs snapshot id, got ${snapshotId}`);
  }
  await insight.getByText(`接口文档快照 #${snapshotId} 已保存`).waitFor();
  const apiDocSnapshots = insight.getByLabel("接口文档快照");
  await apiDocSnapshots.getByText(`快照 #${snapshotId}`).waitFor();
  await apiDocSnapshots.getByText("2 / 2 个接口").waitFor();
  await apiDocSnapshots.getByText("风险 MEDIUM / NO_SECURITY_ANNOTATION").waitFor();
  const snapshotCopyResponse = waitForControllerApiDocsSnapshotDetail(page, snapshotId);
  await apiDocSnapshots.getByRole("button", { name: "复制快照" }).click();
  await snapshotCopyResponse;
  await apiDocSnapshots.getByText(`快照 #${snapshotId} 已复制`).waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) =>
      text.includes("# Controller API docs:")
      && text.includes("Current filters: Risk level: MEDIUM, Risk code: NO_SECURITY_ANNOTATION.")
      && text.includes("## GET /api/users")
    ).catch(() => false)
  );
  const snapshotDownloadResponse = waitForControllerApiDocsSnapshotDetail(page, snapshotId);
  const snapshotDownloadPromise = page.waitForEvent("download");
  await apiDocSnapshots.getByRole("button", { name: "下载快照" }).click();
  await snapshotDownloadResponse;
  const snapshotDownload = await snapshotDownloadPromise;
  await apiDocSnapshots.getByText(`快照 #${snapshotId} 已下载`).waitFor();
  const snapshotFilename = snapshotDownload.suggestedFilename();
  if (!snapshotFilename.endsWith(".md") || !snapshotFilename.includes("controller-api-docs")) {
    throw new Error(`Expected saved snapshot markdown filename, got ${snapshotFilename}`);
  }
  const snapshotPath = await snapshotDownload.path();
  if (snapshotPath === null) {
    throw new Error("Controller API docs snapshot download path was unavailable.");
  }
  const downloadedSnapshot = await readFile(snapshotPath, "utf8");
  if (
    !downloadedSnapshot.includes("# Controller API docs:")
    || !downloadedSnapshot.includes("Current filters: Risk level: MEDIUM, Risk code: NO_SECURITY_ANNOTATION.")
    || !downloadedSnapshot.includes("## GET /api/users")
  ) {
    throw new Error("Downloaded Controller API docs snapshot did not contain expected Markdown content.");
  }
  const snapshotDeleteResponse = waitForControllerApiDocsSnapshotDelete(page, snapshotId);
  await apiDocSnapshots.getByRole("button", { name: "删除快照" }).click();
  await snapshotDeleteResponse;
  await apiDocSnapshots.getByText(`快照 #${snapshotId} 已删除`).waitFor();
  await apiDocSnapshots.getByText("还没有保存接口文档快照。").waitFor();
  const firstClearSnapshotResponse = waitForControllerApiDocsSnapshotCreate(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION",
    limit: "2"
  });
  await insight.getByRole("button", { name: "保存接口文档快照" }).click();
  const firstClearSnapshotPayload = await (await firstClearSnapshotResponse).json();
  const firstClearSnapshotId = firstClearSnapshotPayload.data.id;
  await apiDocSnapshots.getByText(`快照 #${firstClearSnapshotId}`).waitFor();
  const secondClearSnapshotResponse = waitForControllerApiDocsSnapshotCreate(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION",
    limit: "2"
  });
  await insight.getByRole("button", { name: "保存接口文档快照" }).click();
  const secondClearSnapshotPayload = await (await secondClearSnapshotResponse).json();
  const secondClearSnapshotId = secondClearSnapshotPayload.data.id;
  if (secondClearSnapshotId === firstClearSnapshotId) {
    throw new Error("Expected a distinct second Controller API docs snapshot id.");
  }
  await apiDocSnapshots.getByText(`快照 #${secondClearSnapshotId}`).waitFor();
  const snapshotClearResponse = waitForControllerApiDocsSnapshotClear(page);
  await apiDocSnapshots.getByRole("button", { name: "清空快照" }).click();
  const snapshotClearPayload = await (await snapshotClearResponse).json();
  if (snapshotClearPayload.data.deletedCount !== 2) {
    throw new Error(`Expected clearing two Controller API docs snapshots, got ${snapshotClearPayload.data.deletedCount}`);
  }
  await apiDocSnapshots.getByText("已清空 2 份快照").waitFor();
  await apiDocSnapshots.getByText("还没有保存接口文档快照。").waitFor();

  await insight.getByLabel("代码搜索").fill("UserService");
  await clickAndWaitForIdle(page, insight.getByRole("button", { name: "搜索" }));
  await insight.getByText("class UserService").first().waitFor();

  const taskForm = page.locator("form").filter({ hasText: "创建任务" });
  await clickAndWaitForIdle(page, taskForm.getByRole("button", { name: "创建任务" }));
  await page.locator(".taskListItem").filter({ hasText: "Add User pagination API" }).waitFor();
  const taskFilters = page.getByLabel("任务筛选");
  await taskFilters.getByLabel("搜索任务").fill("pagination");
  await clickAndWaitForIdle(page, taskFilters.getByRole("button", { name: "应用筛选" }));
  await page.locator(".taskListItem").filter({ hasText: "Add User pagination API" }).waitFor();
  await page.waitForFunction(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get("taskQuery") === "pagination" && params.has("taskId");
  });
  const reloadFilteredTasks = waitForTaskListQuery(page, { query: "pagination" });
  await page.reload({ waitUntil: "domcontentloaded" });
  await reloadFilteredTasks;
  await page.locator(".taskListItem").filter({ hasText: "Add User pagination API" }).waitFor();
  const restoredTaskQuery = await taskFilters.getByLabel("搜索任务").inputValue();
  if (restoredTaskQuery !== "pagination") {
    throw new Error(`Expected restored task query pagination, got ${restoredTaskQuery}`);
  }
  const taskDetail = page.locator(".detailStack");
  await taskDetail.getByRole("heading", { name: /#\d+ Add User pagination API/ }).waitFor();

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "运行任务" }));
  await taskDetail.getByText(/正在连接实时流|实时流/).first().waitFor({ timeout: 15000 });
  await waitForBadge(taskDetail, "WAITING_HUMAN_APPROVAL");
  await expectDashboardMetric(page, overview, "待审批", "1");
  await expectDashboardMetric(page, runMetrics, "运行次数", "1");
  await expectDashboardMetric(page, runMetrics, "成功率", "100%");
  await activity.locator(".activityTitle strong").filter({ hasText: /^waiting_human_approval$/ }).waitFor();
  await taskFilters.getByLabel("任务状态筛选").selectOption("WAITING_HUMAN_APPROVAL");
  await clickAndWaitForIdle(page, taskFilters.getByRole("button", { name: "应用筛选" }));
  await page.locator(".taskListItem").filter({ hasText: "Add User pagination API" }).waitFor();
  await page.waitForFunction(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get("taskStatus") === "WAITING_HUMAN_APPROVAL"
      && params.get("taskQuery") === "pagination"
      && params.has("taskId");
  });
  const reloadStatusFilteredTasks = waitForTaskListQuery(page, {
    status: "WAITING_HUMAN_APPROVAL",
    query: "pagination"
  });
  await page.reload({ waitUntil: "domcontentloaded" });
  await reloadStatusFilteredTasks;
  await page.locator(".taskListItem").filter({ hasText: "Add User pagination API" }).waitFor();
  await page.waitForFunction(() => {
    const status = document.querySelector('[aria-label="任务状态筛选"]');
    return status?.value === "WAITING_HUMAN_APPROVAL";
  });
  const restoredStatusTaskQuery = await taskFilters.getByLabel("搜索任务").inputValue();
  if (restoredStatusTaskQuery !== "pagination") {
    throw new Error(`Expected restored task query pagination after status reload, got ${restoredStatusTaskQuery}`);
  }
  await taskFilters.getByRole("button", { name: "复制任务视图链接" }).click();
  await taskFilters.getByText("任务链接已复制").waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) => {
      if (!text) {
        return false;
      }
      const url = new URL(text);
      return url.searchParams.get("taskStatus") === "WAITING_HUMAN_APPROVAL"
        && url.searchParams.get("taskQuery") === "pagination"
        && url.searchParams.has("taskId");
    }).catch(() => false)
  );
  await clickAndWaitForIdle(page, taskFilters.getByRole("button", { name: "重置" }));
  await page.waitForFunction(() => {
    const params = new URLSearchParams(window.location.search);
    return !params.has("taskStatus")
      && !params.has("taskQuery")
      && !params.has("taskType")
      && !params.has("taskProjectId")
      && params.has("taskId");
  });
  await taskDetail.getByText("load_task_context").first().waitFor();
  await taskDetail.getByText("plan_task").first().waitFor();
  await taskDetail.getByText("generate_patch").first().waitFor();
  await taskDetail.getByText("waiting_human_approval").first().waitFor();
  await taskDetail.getByText("Agent 执行证据").waitFor();
  await taskDetail.getByText("任务规划").waitFor();
  await taskDetail.getByText("检索到的代码上下文").waitFor();
  await taskDetail.getByText("生成的补丁产物").waitFor();
  await taskDetail.getByText("沙箱测试结果").waitFor();
  await taskDetail.getByText("自动补丁审查").waitFor();
  await taskDetail.getByText("人工审批检查点").waitFor();
  await taskDetail.getByRole("button", { name: "复制当前报告" }).click();
  await taskDetail.getByText("已复制当前运行报告").waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) =>
      text.includes("# RepoPilot Agent 运行报告")
        && text.includes("## 任务规划")
        && text.includes("## 检索到的代码上下文")
        && text.includes("SPRING_USER_PAGINATION_RECIPE")
    ).catch(() => false)
  );
  const runReportDownloadPromise = page.waitForEvent("download");
  await taskDetail.getByRole("button", { name: "下载当前报告" }).click();
  const runReportDownload = await runReportDownloadPromise;
  await taskDetail.getByText("已下载当前运行报告").waitFor();
  const runReportFilename = runReportDownload.suggestedFilename();
  if (!runReportFilename.includes("agent-run-report") || !runReportFilename.endsWith(".md")) {
    throw new Error(`Unexpected Agent run report filename: ${runReportFilename}`);
  }
  const runReportPath = await runReportDownload.path();
  if (!runReportPath) {
    throw new Error("Agent run report download path was unavailable.");
  }
  const downloadedRunReport = await readFile(runReportPath, "utf8");
  if (
    !downloadedRunReport.includes("# RepoPilot Agent 运行报告")
    || !downloadedRunReport.includes("## 沙箱测试结果")
    || !downloadedRunReport.includes("## 自动补丁审查")
  ) {
    throw new Error("Downloaded Agent run report did not include the expected Markdown sections.");
  }
  const runReportSnapshotResponse = waitForAgentRunReportSnapshotCreate(page);
  await taskDetail.getByRole("button", { name: "保存报告快照" }).click();
  const runReportSnapshotPayload = await (await runReportSnapshotResponse).json();
  const runReportSnapshotId = runReportSnapshotPayload.data.id;
  await taskDetail.getByText(`已保存运行报告快照 #${runReportSnapshotId}`).waitFor();
  const runReportSnapshots = taskDetail.getByLabel("运行报告快照");
  await runReportSnapshots.getByText(`快照 #${runReportSnapshotId}`).waitFor();
  await runReportSnapshots.getByText("7 个段落").waitFor();
  const copiedRunReportSnapshotResponse = waitForAgentRunReportSnapshotDetail(page, runReportSnapshotId);
  await runReportSnapshots.getByRole("button", { name: "复制快照" }).click();
  await copiedRunReportSnapshotResponse;
  await taskDetail.getByText(`已复制快照 #${runReportSnapshotId}`).waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) =>
      text.includes("# RepoPilot Agent 运行报告")
        && text.includes("## 任务规划")
        && text.includes("## 自动补丁审查")
    ).catch(() => false)
  );
  const snapshotReportDownloadResponse = waitForAgentRunReportSnapshotDetail(page, runReportSnapshotId);
  const snapshotReportDownloadPromise = page.waitForEvent("download");
  await runReportSnapshots.getByRole("button", { name: "下载快照" }).click();
  await snapshotReportDownloadResponse;
  const snapshotReportDownload = await snapshotReportDownloadPromise;
  await taskDetail.getByText(`已下载快照 #${runReportSnapshotId}`).waitFor();
  const snapshotReportFilename = snapshotReportDownload.suggestedFilename();
  if (!snapshotReportFilename.includes("agent-run-report-snapshot") || !snapshotReportFilename.endsWith(".md")) {
    throw new Error(`Unexpected Agent run report snapshot filename: ${snapshotReportFilename}`);
  }
  const snapshotReportPath = await snapshotReportDownload.path();
  if (!snapshotReportPath) {
    throw new Error("Agent run report snapshot download path was unavailable.");
  }
  const downloadedRunReportSnapshot = await readFile(snapshotReportPath, "utf8");
  if (
    !downloadedRunReportSnapshot.includes("# RepoPilot Agent 运行报告")
    || !downloadedRunReportSnapshot.includes("## 沙箱测试结果")
    || !downloadedRunReportSnapshot.includes("SPRING_USER_PAGINATION_RECIPE")
  ) {
    throw new Error("Downloaded Agent run report snapshot did not include the expected Markdown content.");
  }
  await taskDetail.getByText("工具调用审计").waitFor();
  await taskDetail.getByText("模型调用审计").waitFor();
  await taskDetail.getByText("新增 GET /api/users/page").first().waitFor();
  await taskDetail.getByText("SPRING_USER_PAGINATION_RECIPE").first().waitFor();
  await taskDetail.getByText("Maven 测试运行").waitFor();
  await taskDetail.getByText("PASSED").first().waitFor();
  await taskDetail.getByText("run_maven_test").first().waitFor();
  await taskDetail.getByText("validate_patch_safety").first().waitFor();
  await taskDetail.getByText("review_patch").first().waitFor();
  const prPanel = taskDetail.locator("#pr");
  await prPanel.getByText("发布前置检查").waitFor();
  await prPanel.getByText("Approve the tested patch before preparing a pull request.").first().waitFor();
  await assertTaskStreamSnapshot(page);
  await taskDetail.getByRole("button", { name: "重新生成" }).waitFor();
  await page.waitForFunction(() => {
    const buttons = Array.from(document.querySelectorAll("button"));
    return buttons.some((button) => button.textContent?.trim() === "重新生成" && !button.disabled);
  });

  const taskSummary = taskDetail.locator("section.panel").filter({ hasText: "任务详情" }).first();
  const patchPanel = taskDetail.locator("#patch");
  const testPanel = taskDetail.locator("section.panel").filter({ hasText: "Maven 测试运行" }).first();
  const firstRunId = await metaValue(taskSummary, "运行");
  const firstPatchId = await metaValue(patchPanel, "补丁");
  const firstTestRunId = await metaValue(testPanel, "测试运行");

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "重新生成" }));
  await waitForBadge(taskDetail, "WAITING_HUMAN_APPROVAL");
  await waitForMetaValueChange(page, taskSummary, "运行", firstRunId);
  await waitForMetaValueChange(page, patchPanel, "补丁", firstPatchId);
  await waitForMetaValueChange(page, testPanel, "测试运行", firstTestRunId);
  await taskDetail.getByText("新增 GET /api/users/page").first().waitFor();
  await taskDetail.getByText("PASSED").first().waitFor();
  const automatedReview = patchPanel.getByLabel("自动审查");
  await automatedReview.getByText("NEW_CONTROLLER_ENDPOINT_WITHOUT_AUTH").waitFor();
  await automatedReview.getByText("PAGINATION_BOUNDS_NORMALIZED").waitFor();
  await automatedReview.getByText("TEST_COVERAGE_PRESENT").waitFor();
  const changedFilesSummary = patchPanel.getByLabel("变更文件");
  await changedFilesSummary.getByText("src/main/java/com/example/demo/user/UserController.java").waitFor();
  await changedFilesSummary.getByText("src/test/java/com/example/demo/user/UserServiceTest.java").waitFor();
  await changedFilesSummary.getByText("ADDED").waitFor();
  await assertLatestPaginationPatchChangedFiles(page);

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "通过审批" }));
  await waitForBadge(taskDetail, "CREATING_PULL_REQUEST");
  await taskDetail.getByText("APPROVE").first().waitFor();
  await prPanel.getByText("Local branch and commit can be prepared.").waitFor();
  await prPanel.getByText("Remote GitHub publishing is disabled; RepoPilot will stop at DRAFT_READY.").waitFor();
  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "准备 PR" }));
  await waitForBadge(taskDetail, "DRAFT_READY");
  await taskDetail.getByText("repopilot/task-").first().waitFor();
  await taskDetail.getByText("未打开").first().waitFor();
  await taskDetail.getByText("Prepared by RepoPilot.").first().waitFor();
  await prPanel.getByText("Pull request record has already been prepared.").waitFor();

  await taskForm.getByLabel("标题").fill("Fix User id validation bug");
  await taskForm
    .getByLabel("任务描述")
    .fill("修复 User 模块 getUser 参数校验 bug，拒绝空 id 和非正数 id。");
  await clickAndWaitForIdle(page, taskForm.getByRole("button", { name: "创建任务" }));
  await page.locator(".taskListItem").filter({ hasText: "Fix User id validation bug" }).waitFor();
  await taskDetail.getByRole("heading", { name: /#\d+ Fix User id validation bug/ }).waitFor();

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "运行任务" }));
  await waitForBadge(taskDetail, "WAITING_HUMAN_APPROVAL");
  await taskDetail.getByText("新增 User id 参数校验保护").first().waitFor();
  await taskDetail.getByText("User id must be positive").first().waitFor();
  await taskDetail.getByText("getUserRejectsNonPositiveId").first().waitFor();
  await taskDetail.getByText("PASSED").first().waitFor();
  const validationReview = patchPanel.getByLabel("自动审查");
  await validationReview.getByText("TEST_COVERAGE_PRESENT").waitFor();
  const validationChangedFiles = patchPanel.getByLabel("变更文件");
  await validationChangedFiles.getByText("src/main/java/com/example/demo/user/UserService.java").waitFor();
  await validationChangedFiles.getByText("src/test/java/com/example/demo/user/UserServiceTest.java").waitFor();
  await validationChangedFiles.getByText("ADDED").waitFor();
  await assertLatestValidationPatchChangedFiles(page);

  await taskForm.getByLabel("标题").fill("Add User count API");
  await taskForm
    .getByLabel("任务描述")
    .fill("给 User 模块新增统计用户总数接口。");
  await clickAndWaitForIdle(page, taskForm.getByRole("button", { name: "创建任务" }));
  await page.locator(".taskListItem").filter({ hasText: "Add User count API" }).waitFor();
  await taskDetail.getByRole("heading", { name: /#\d+ Add User count API/ }).waitFor();

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "运行任务" }));
  await waitForBadge(taskDetail, "WAITING_HUMAN_APPROVAL");
  await taskDetail.getByText("新增 GET /api/users/count").first().waitFor();
  await taskDetail.getByText("SPRING_USER_COUNT_RECIPE").first().waitFor();
  await taskDetail.getByText("countUsersReturnsTotalNumberOfUsers").first().waitFor();
  await taskDetail.getByText("PASSED").first().waitFor();
  const countChangedFiles = patchPanel.getByLabel("变更文件");
  await countChangedFiles.getByText("src/main/java/com/example/demo/user/UserController.java").waitFor();
  await countChangedFiles.getByText("src/main/java/com/example/demo/user/UserService.java").waitFor();
  await countChangedFiles.getByText("src/main/java/com/example/demo/user/UserMapper.java").waitFor();
  await countChangedFiles.getByText("src/test/java/com/example/demo/user/UserServiceTest.java").waitFor();
  await assertLatestCountPatchChangedFiles(page);

  await taskForm.getByLabel("标题").fill("Add User create API");
  await taskForm
    .getByLabel("任务描述")
    .fill("给 User 模块新增创建用户接口，接收 name 并返回创建结果。");
  await clickAndWaitForIdle(page, taskForm.getByRole("button", { name: "创建任务" }));
  await page.locator(".taskListItem").filter({ hasText: "Add User create API" }).waitFor();
  await taskDetail.getByRole("heading", { name: /#\d+ Add User create API/ }).waitFor();

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "运行任务" }));
  await waitForBadge(taskDetail, "WAITING_HUMAN_APPROVAL");
  await taskDetail.getByText("新增 POST /api/users").first().waitFor();
  await taskDetail.getByText("SPRING_USER_CREATE_RECIPE").first().waitFor();
  await taskDetail.getByText("CreateUserRequest").first().waitFor();
  await taskDetail.getByText("createUserReturnsCreatedUser").first().waitFor();
  await taskDetail.getByText("User name must not be blank").first().waitFor();
  await taskDetail.getByText("PASSED").first().waitFor();
  const createChangedFiles = patchPanel.getByLabel("变更文件");
  await createChangedFiles.getByText("src/main/java/com/example/demo/user/UserController.java").waitFor();
  await createChangedFiles.getByText("src/main/java/com/example/demo/user/CreateUserRequest.java").waitFor();
  await createChangedFiles.getByText("src/main/java/com/example/demo/user/UserService.java").waitFor();
  await createChangedFiles.getByText("src/main/java/com/example/demo/user/UserMapper.java").waitFor();
  await createChangedFiles.getByText("src/test/java/com/example/demo/user/UserServiceTest.java").waitFor();
  await assertLatestCreatePatchChangedFiles(page);

  const screenshotPath = join(artifactDir, "repopilot-browser-smoke.png");
  await page.screenshot({ path: screenshotPath, fullPage: true });
  console.log(`Browser smoke passed for ${email}`);
  console.log(`Screenshot: ${screenshotPath}`);
} catch (error) {
  const failurePath = join(artifactDir, "repopilot-browser-smoke-failure.png");
  await page.screenshot({ path: failurePath, fullPage: true }).catch(() => {});
  console.error(`Browser smoke failed. Failure screenshot: ${failurePath}`);
  throw error;
} finally {
  await context.close();
  await browser.close();
}

async function clickAndWaitForIdle(page, locator) {
  await locator.click();
  await page.waitForTimeout(250);
  await page.waitForFunction(() => !document.querySelector(".mutedNotice"));
}

async function waitForBadge(scope, value) {
  await scope.locator(".badge").filter({ hasText: new RegExp(`^${escapeRegExp(value)}$`) }).waitFor();
}

function waitForRunMetricsQuery(page, days) {
  return page.waitForResponse((response) => {
    const url = new URL(response.url());
    return response.request().method() === "GET"
      && url.pathname.endsWith("/api/dashboard/run-metrics")
      && url.searchParams.get("days") === days
      && response.status() === 200;
  });
}

function waitForActivityQuery(page, limit) {
  return page.waitForResponse((response) => {
    const url = new URL(response.url());
    return response.request().method() === "GET"
      && url.pathname.endsWith("/api/dashboard/activity")
      && url.searchParams.get("limit") === limit
      && response.status() === 200;
  });
}

async function dashboardMetricValue(scope, label) {
  return (await scope
    .locator(".statusCard")
    .filter({ hasText: new RegExp(`^${escapeRegExp(label)}`) })
    .locator("strong")
    .first()
    .textContent()).trim();
}

async function expectDashboardMetric(page, scope, label, expectedValue) {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const currentValue = await dashboardMetricValue(scope, label).catch(() => "");
    if (currentValue === expectedValue) {
      return;
    }
    await page.waitForTimeout(500);
  }
  const currentValue = await dashboardMetricValue(scope, label).catch(() => "<missing>");
  throw new Error(`Dashboard metric ${label} expected ${expectedValue}, got ${currentValue}`);
}

async function metaValue(scope, label) {
  const text = await scope
    .locator(".metaItem")
    .filter({ hasText: new RegExp(`^${escapeRegExp(label)}`) })
    .locator("strong")
    .first()
    .innerText();
  return text.trim();
}

async function waitForMetaValueChange(page, scope, label, previousValue) {
  for (let attempt = 0; attempt < 240; attempt += 1) {
    const currentValue = await metaValue(scope, label).catch(() => previousValue);
    if (currentValue !== previousValue) {
      return currentValue;
    }
    await page.waitForTimeout(500);
  }
  throw new Error(`${label} did not change from ${previousValue}`);
}

async function assertLatestPaginationPatchChangedFiles(page) {
  const changedFiles = await page.evaluate(async () => {
    const token = window.localStorage.getItem("repopilot.token");
    const heading = document.querySelector(".detailStack h2")?.textContent ?? "";
    const taskId = heading.match(/#(\d+)/)?.[1];
    if (!token || !taskId) {
      throw new Error("Unable to resolve token or task id for latest patch check");
    }
    const response = await fetch(`/api/tasks/${taskId}/patches/latest`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (!response.ok) {
      throw new Error(`Latest patch request failed with ${response.status}`);
    }
    const payload = await response.json();
    return payload.data.changedFiles;
  });
  const changedFileByPath = new Map(changedFiles.map((file) => [file.path, file]));
  for (const expectedPath of [
    "pom.xml",
    "src/main/java/com/example/demo/user/UserController.java",
    "src/main/java/com/example/demo/user/UserService.java",
    "src/main/java/com/example/demo/user/UserMapper.java",
    "src/test/java/com/example/demo/user/UserServiceTest.java"
  ]) {
    if (!changedFileByPath.has(expectedPath)) {
      throw new Error(`Latest patch changedFiles did not include ${expectedPath}`);
    }
  }
  const testFile = changedFileByPath.get("src/test/java/com/example/demo/user/UserServiceTest.java");
  if (testFile.changeType !== "ADDED" || testFile.addedLines <= 0) {
    throw new Error("UserServiceTest changedFiles entry should be an added file with added lines");
  }
}

async function assertTaskStreamSnapshot(page) {
  const stream = await page.evaluate(async () => {
    const token = window.localStorage.getItem("repopilot.token");
    const heading = document.querySelector(".detailStack h2")?.textContent ?? "";
    const taskId = heading.match(/#(\d+)/)?.[1];
    if (!token || !taskId) {
      throw new Error("Unable to resolve token or task id for task stream check");
    }
    const response = await fetch(`/api/agent/tasks/${taskId}/stream`, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "text/event-stream"
      }
    });
    if (!response.ok) {
      throw new Error(`Task stream request failed with ${response.status}`);
    }
    return response.text();
  });
  for (const expected of [
    "event:task_snapshot",
    "\"eventType\":\"TASK_SNAPSHOT\"",
    "event:step_snapshot",
    "\"stepName\":\"plan_task\"",
    "\"stepName\":\"review_patch\"",
    "event:stream_complete"
  ]) {
    if (!stream.includes(expected)) {
      throw new Error(`Task stream snapshot did not include ${expected}`);
    }
  }
}

async function assertLatestValidationPatchChangedFiles(page) {
  const changedFiles = await page.evaluate(async () => {
    const token = window.localStorage.getItem("repopilot.token");
    const heading = document.querySelector(".detailStack h2")?.textContent ?? "";
    const taskId = heading.match(/#(\d+)/)?.[1];
    if (!token || !taskId) {
      throw new Error("Unable to resolve token or task id for latest validation patch check");
    }
    const response = await fetch(`/api/tasks/${taskId}/patches/latest`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (!response.ok) {
      throw new Error(`Latest validation patch request failed with ${response.status}`);
    }
    const payload = await response.json();
    return payload.data.changedFiles;
  });
  const changedFileByPath = new Map(changedFiles.map((file) => [file.path, file]));
  for (const expectedPath of [
    "pom.xml",
    "src/main/java/com/example/demo/user/UserService.java",
    "src/test/java/com/example/demo/user/UserServiceTest.java"
  ]) {
    if (!changedFileByPath.has(expectedPath)) {
      throw new Error(`Latest validation patch changedFiles did not include ${expectedPath}`);
    }
  }
  const testFile = changedFileByPath.get("src/test/java/com/example/demo/user/UserServiceTest.java");
  if (testFile.changeType !== "ADDED" || testFile.addedLines <= 0) {
    throw new Error("Validation UserServiceTest changedFiles entry should be an added file with added lines");
  }
}

async function assertLatestCountPatchChangedFiles(page) {
  const patch = await page.evaluate(async () => {
    const token = window.localStorage.getItem("repopilot.token");
    const heading = document.querySelector(".detailStack h2")?.textContent ?? "";
    const taskId = heading.match(/#(\d+)/)?.[1];
    if (!token || !taskId) {
      throw new Error("Unable to resolve token or task id for latest count patch check");
    }
    const response = await fetch(`/api/tasks/${taskId}/patches/latest`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (!response.ok) {
      throw new Error(`Latest count patch request failed with ${response.status}`);
    }
    const payload = await response.json();
    return payload.data;
  });
  if (patch.generationMode !== "SPRING_USER_COUNT_RECIPE") {
    throw new Error(`Expected count generationMode, got ${patch.generationMode}`);
  }
  const changedFileByPath = new Map(patch.changedFiles.map((file) => [file.path, file]));
  for (const expectedPath of [
    "pom.xml",
    "src/main/java/com/example/demo/user/UserController.java",
    "src/main/java/com/example/demo/user/UserService.java",
    "src/main/java/com/example/demo/user/UserMapper.java",
    "src/test/java/com/example/demo/user/UserServiceTest.java"
  ]) {
    if (!changedFileByPath.has(expectedPath)) {
      throw new Error(`Latest count patch changedFiles did not include ${expectedPath}`);
    }
  }
}

async function assertLatestCreatePatchChangedFiles(page) {
  const patch = await page.evaluate(async () => {
    const token = window.localStorage.getItem("repopilot.token");
    const heading = document.querySelector(".detailStack h2")?.textContent ?? "";
    const taskId = heading.match(/#(\d+)/)?.[1];
    if (!token || !taskId) {
      throw new Error("Unable to resolve token or task id for latest create patch check");
    }
    const response = await fetch(`/api/tasks/${taskId}/patches/latest`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (!response.ok) {
      throw new Error(`Latest create patch request failed with ${response.status}`);
    }
    const payload = await response.json();
    return payload.data;
  });
  if (patch.generationMode !== "SPRING_USER_CREATE_RECIPE") {
    throw new Error(`Expected create generationMode, got ${patch.generationMode}`);
  }
  const changedFileByPath = new Map(patch.changedFiles.map((file) => [file.path, file]));
  for (const expectedPath of [
    "pom.xml",
    "src/main/java/com/example/demo/user/UserController.java",
    "src/main/java/com/example/demo/user/CreateUserRequest.java",
    "src/main/java/com/example/demo/user/UserService.java",
    "src/main/java/com/example/demo/user/UserMapper.java",
    "src/test/java/com/example/demo/user/UserServiceTest.java"
  ]) {
    if (!changedFileByPath.has(expectedPath)) {
      throw new Error(`Latest create patch changedFiles did not include ${expectedPath}`);
    }
  }
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function waitForControllerApiQuery(page, expectedParams) {
  return page.waitForResponse((response) => {
    if (!response.ok()) {
      return false;
    }
    const url = new URL(response.url());
    if (!url.pathname.endsWith("/controller-apis") || !url.pathname.includes("/api/projects/")) {
      return false;
    }
    return Object.entries(expectedParams).every(([key, value]) => url.searchParams.get(key) === value);
  });
}

function waitForControllerApiDocsQuery(page, expectedParams) {
  return page.waitForResponse((response) => {
    if (!response.ok()) {
      return false;
    }
    const url = new URL(response.url());
    if (!url.pathname.endsWith("/controller-apis/docs") || !url.pathname.includes("/api/projects/")) {
      return false;
    }
    return Object.entries(expectedParams).every(([key, value]) => url.searchParams.get(key) === value);
  });
}

function waitForControllerApiDocsSnapshotCreate(page, expectedParams) {
  return page.waitForResponse((response) => {
    if (!response.ok() || response.request().method() !== "POST") {
      return false;
    }
    const url = new URL(response.url());
    if (!url.pathname.endsWith("/controller-apis/docs/snapshots") || !url.pathname.includes("/api/projects/")) {
      return false;
    }
    return Object.entries(expectedParams).every(([key, value]) => url.searchParams.get(key) === value);
  });
}

function waitForControllerApiDocsSnapshotDetail(page, snapshotId) {
  return page.waitForResponse((response) => {
    if (!response.ok() || response.request().method() !== "GET") {
      return false;
    }
    const url = new URL(response.url());
    return url.pathname.endsWith(`/controller-apis/docs/snapshots/${snapshotId}`)
      && url.pathname.includes("/api/projects/");
  });
}

function waitForControllerApiDocsSnapshotDelete(page, snapshotId) {
  return page.waitForResponse((response) => {
    if (!response.ok() || response.request().method() !== "DELETE") {
      return false;
    }
    const url = new URL(response.url());
    return url.pathname.endsWith(`/controller-apis/docs/snapshots/${snapshotId}`)
      && url.pathname.includes("/api/projects/");
  });
}

function waitForControllerApiDocsSnapshotClear(page) {
  return page.waitForResponse((response) => {
    if (!response.ok() || response.request().method() !== "DELETE") {
      return false;
    }
    const url = new URL(response.url());
    return url.pathname.endsWith("/controller-apis/docs/snapshots")
      && url.pathname.includes("/api/projects/");
  });
}

function waitForAgentRunReportSnapshotCreate(page) {
  return page.waitForResponse((response) => {
    if (!response.ok() || response.request().method() !== "POST") {
      return false;
    }
    const url = new URL(response.url());
    return url.pathname.endsWith("/run-report/snapshots")
      && url.pathname.includes("/api/agent/tasks/");
  });
}

function waitForAgentRunReportSnapshotDetail(page, snapshotId) {
  return page.waitForResponse((response) => {
    if (!response.ok() || response.request().method() !== "GET") {
      return false;
    }
    const url = new URL(response.url());
    return url.pathname.endsWith(`/run-report/snapshots/${snapshotId}`)
      && url.pathname.includes("/api/agent/tasks/");
  });
}

function waitForProjectListQuery(page, expectedParams) {
  return page.waitForResponse((response) => {
    if (!response.ok()) {
      return false;
    }
    const url = new URL(response.url());
    if (!url.pathname.endsWith("/api/projects")) {
      return false;
    }
    return Object.entries(expectedParams).every(([key, value]) => url.searchParams.get(key) === value);
  });
}

function waitForTaskListQuery(page, expectedParams) {
  return page.waitForResponse((response) => {
    if (!response.ok()) {
      return false;
    }
    const url = new URL(response.url());
    if (!url.pathname.endsWith("/api/agent/tasks")) {
      return false;
    }
    return Object.entries(expectedParams).every(([key, value]) => url.searchParams.get(key) === value);
  });
}
