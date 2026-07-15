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

  const registerForm = page.locator("form").filter({ hasText: "Create a local account" });
  await registerForm.getByLabel("Display name").fill(displayName);
  await registerForm.getByLabel("Email").fill(email);
  await registerForm.getByLabel("Password").fill(password);
  await registerForm.getByRole("button", { name: "Register" }).click();
  await page.getByRole("button", { name: "退出登录" }).waitFor();
  const overview = page.locator(".dashboardSummaryPanel");
  await overview.getByText("Workspace overview").waitFor();
  await expectDashboardMetric(page, overview, "Projects", "0/0 ready");
  await expectDashboardMetric(page, overview, "Tasks", "0 total");
  await expectDashboardMetric(page, overview, "Waiting approval", "0");
  const runMetrics = page.locator(".dashboardRunMetricsPanel");
  await runMetrics.getByText("Agent run performance").waitFor();
  await runMetrics.locator(".sectionHeader").getByText(/Last 7 days,/).waitFor();
  await expectDashboardMetric(page, runMetrics, "Runs", "0");
  await expectDashboardMetric(page, runMetrics, "Success rate", "0%");
  const runMetricsWindowResponse = waitForRunMetricsQuery(page, "14");
  await runMetrics.getByLabel("Run metrics window").selectOption("14");
  await runMetricsWindowResponse;
  await runMetrics.locator(".sectionHeader").getByText(/Last 14 days,/).waitFor();
  await page.waitForFunction(() => new URLSearchParams(window.location.search).get("runMetricsDays") === "14");
  await expectDashboardMetric(page, runMetrics, "Runs", "0");
  const reloadRunMetricsWindowResponse = waitForRunMetricsQuery(page, "14");
  await page.reload({ waitUntil: "domcontentloaded" });
  await reloadRunMetricsWindowResponse;
  await runMetrics.locator(".sectionHeader").getByText(/Last 14 days,/).waitFor();
  await page.waitForFunction(() => {
    const select = document.querySelector('[aria-label="Run metrics window"]');
    return select?.value === "14";
  });
  const activity = page.locator(".dashboardActivityPanel");
  await activity.getByText("Recent task activity").waitFor();
  await activity.locator(".sectionHeader").getByText("0 of latest 10 events").waitFor();
  await activity.getByText("No task activity yet.").waitFor();
  const activityLimitResponse = waitForActivityQuery(page, "25");
  await activity.getByLabel("Activity limit").selectOption("25");
  await activityLimitResponse;
  await activity.locator(".sectionHeader").getByText("0 of latest 25 events").waitFor();
  await page.waitForFunction(() => new URLSearchParams(window.location.search).get("activityLimit") === "25");
  const reloadActivityLimitResponse = waitForActivityQuery(page, "25");
  await page.reload({ waitUntil: "domcontentloaded" });
  await reloadActivityLimitResponse;
  await activity.locator(".sectionHeader").getByText("0 of latest 25 events").waitFor();
  await page.waitForFunction(() => {
    const select = document.querySelector('[aria-label="Activity limit"]');
    return select?.value === "25";
  });
  await overview.getByRole("button", { name: "Copy overview link" }).click();
  await overview.getByText("Overview link copied").waitFor();
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
  await coderSettings.getByText("Coder configuration").waitFor();
  await coderSettings.getByText("Model provider status").waitFor();
  await coderSettings.locator(".badge").filter({ hasText: /^NONE$/ }).waitFor();
  await coderSettings.locator(".badge").filter({ hasText: /^READY$/ }).waitFor();
  await coderSettings.getByText("Recipe and safe fallback mode").waitFor();
  await coderSettings.getByText("Secrets stay environment-driven").waitFor();
  const githubSettings = page.locator(".githubSettingsPanel");
  await githubSettings.getByText("GitHub publishing").waitFor();
  await githubSettings.getByText("Pull request publishing").waitFor();
  await githubSettings.locator(".badge").filter({ hasText: /^GITHUB$/ }).waitFor();
  await githubSettings.locator(".badge").filter({ hasText: /^READY$/ }).waitFor();
  await githubSettings.getByText("LOCAL_DRAFT_ONLY").waitFor();
  await githubSettings.getByText("Local draft PR preparation").waitFor();
  await githubSettings.getByText("GitHub token stays environment-driven").waitFor();
  const sandboxSettings = page.locator(".sandboxSettingsPanel");
  await sandboxSettings.getByText("Sandbox runtime").waitFor();
  await sandboxSettings.getByText("Docker sandbox").waitFor();
  await sandboxSettings.locator(".badge").filter({ hasText: /^READY$/ }).waitFor();
  await sandboxSettings.getByText("maven:3.9-eclipse-temurin-17").waitFor();
  await sandboxSettings.getByText("Sandbox readiness checks").waitFor();
  await sandboxSettings.getByText("Maven cache path:").waitFor();

  const projectForm = page.locator("form").filter({ hasText: "Add project" });
  await projectForm.getByLabel("Repository URL").fill(repoUrl);
  await projectForm.getByLabel("Default branch").fill("main");
  await projectForm.getByRole("button", { name: "Create project" }).click();
  await page.locator(".projectRow").filter({ hasText: "CREATED" }).waitFor();
  await page.getByRole("button", { name: "Clone" }).first().waitFor();

  await clickAndWaitForIdle(page, page.getByRole("button", { name: "Clone" }).first());
  await page.locator(".projectRow").filter({ hasText: "READY" }).waitFor();

  const projectFilters = page.getByLabel("Project filters");
  await projectFilters.getByLabel("Search projects").fill("demo-spring-repo");
  await clickAndWaitForIdle(page, projectFilters.getByRole("button", { name: "Apply filters" }));
  await page.locator(".projectRow").filter({ hasText: "READY" }).waitFor();
  await projectFilters.getByLabel("Project status filter").selectOption("READY");
  await clickAndWaitForIdle(page, projectFilters.getByRole("button", { name: "Apply filters" }));
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
    const status = document.querySelector('[aria-label="Project status filter"]');
    return status?.value === "READY";
  });
  const restoredProjectId = await page.evaluate(() => new URLSearchParams(window.location.search).get("projectId"));
  await page.waitForFunction((projectId) => {
    const insightProject = document.querySelector('[aria-label="Insight project"]');
    return projectId !== null && insightProject?.value === projectId;
  }, restoredProjectId);
  const restoredProjectQuery = await projectFilters.getByLabel("Search projects").inputValue();
  if (restoredProjectQuery !== "demo-spring-repo") {
    throw new Error(`Expected restored project query demo-spring-repo, got ${restoredProjectQuery}`);
  }
  await projectFilters.getByRole("button", { name: "Copy project view link" }).click();
  await projectFilters.getByText("Project link copied").waitFor();
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
  await clickAndWaitForIdle(page, projectFilters.getByRole("button", { name: "Reset" }));
  await page.locator(".projectRow").filter({ hasText: "READY" }).waitFor();
  await page.waitForFunction(() => {
    const params = new URLSearchParams(window.location.search);
    return !params.has("projectStatus") && !params.has("projectQuery");
  });

  await clickAndWaitForIdle(page, page.getByRole("button", { name: "Index" }).first());
  await expectDashboardMetric(page, overview, "Projects", "1/1 ready");
  await clickAndWaitForIdle(page, page.getByRole("button", { name: "Refresh map" }));

  const insight = page.locator(".projectInsightPanel");
  await insight.getByText("SERVICE 1").waitFor();
  await insight.getByText("src/main/java/com/example/demo/user/UserService.java").first().waitFor();
  const mediumApiResponse = waitForControllerApiQuery(page, { riskLevel: "MEDIUM" });
  await insight.getByLabel("Controller API risk summary").getByRole("button", { name: /MEDIUM/ }).click();
  await mediumApiResponse;
  await page.waitForFunction(() => {
    const riskLevel = document.querySelector('[aria-label="Risk level"]');
    return riskLevel?.value === "MEDIUM";
  });
  await insight.getByLabel("Risk level").selectOption("MEDIUM");
  const filteredApiResponse = waitForControllerApiQuery(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION"
  });
  await insight.getByLabel("Risk code").selectOption("NO_SECURITY_ANNOTATION");
  await filteredApiResponse;
  await insight.getByText("2 of 2 routes").waitFor();
  await page.waitForFunction(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get("controllerRiskLevel") === "MEDIUM"
      && params.get("controllerRiskCode") === "NO_SECURITY_ANNOTATION";
  });
  await insight.getByRole("button", { name: "Copy risk view link" }).click();
  await insight.getByText("Link copied").waitFor();
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
  await insight.getByText("2 of 2 routes").waitFor();
  await page.waitForFunction(() => {
    const riskLevel = document.querySelector('[aria-label="Risk level"]');
    const riskCode = document.querySelector('[aria-label="Risk code"]');
    return riskLevel?.value === "MEDIUM" && riskCode?.value === "NO_SECURITY_ANNOTATION";
  });
  await insight.getByRole("button", { name: "Copy route link" }).first().click();
  await insight.getByText("Route link copied").waitFor();
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
  await insight.getByRole("button", { name: "Copy API docs" }).click();
  await controllerApiDocsResponse;
  await insight.getByText("API docs copied").waitFor();
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
  await insight.getByRole("button", { name: "Download API docs" }).click();
  await controllerApiDocsDownloadResponse;
  const apiDocsDownload = await apiDocsDownloadPromise;
  await insight.getByText("API docs downloaded").waitFor();
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
  await insight.getByRole("button", { name: "Save API docs snapshot" }).click();
  const snapshotResponse = await controllerApiDocsSnapshotResponse;
  const snapshotPayload = await snapshotResponse.json();
  const snapshotId = snapshotPayload.data.id;
  if (!Number.isInteger(snapshotId) || snapshotId <= 0) {
    throw new Error(`Expected saved Controller API docs snapshot id, got ${snapshotId}`);
  }
  await insight.getByText(`API docs snapshot #${snapshotId} saved`).waitFor();
  const apiDocSnapshots = insight.getByLabel("API doc snapshots");
  await apiDocSnapshots.getByText(`Snapshot #${snapshotId}`).waitFor();
  await apiDocSnapshots.getByText("2 of 2 routes").waitFor();
  await apiDocSnapshots.getByText("Risk MEDIUM / NO_SECURITY_ANNOTATION").waitFor();
  const snapshotCopyResponse = waitForControllerApiDocsSnapshotDetail(page, snapshotId);
  await apiDocSnapshots.getByRole("button", { name: "Copy snapshot" }).click();
  await snapshotCopyResponse;
  await apiDocSnapshots.getByText(`Snapshot #${snapshotId} copied`).waitFor();
  await page.waitForFunction(() =>
    navigator.clipboard.readText().then((text) =>
      text.includes("# Controller API docs:")
      && text.includes("Current filters: Risk level: MEDIUM, Risk code: NO_SECURITY_ANNOTATION.")
      && text.includes("## GET /api/users")
    ).catch(() => false)
  );
  const snapshotDownloadResponse = waitForControllerApiDocsSnapshotDetail(page, snapshotId);
  const snapshotDownloadPromise = page.waitForEvent("download");
  await apiDocSnapshots.getByRole("button", { name: "Download snapshot" }).click();
  await snapshotDownloadResponse;
  const snapshotDownload = await snapshotDownloadPromise;
  await apiDocSnapshots.getByText(`Snapshot #${snapshotId} downloaded`).waitFor();
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
  await apiDocSnapshots.getByRole("button", { name: "Delete snapshot" }).click();
  await snapshotDeleteResponse;
  await apiDocSnapshots.getByText(`Snapshot #${snapshotId} deleted`).waitFor();
  await apiDocSnapshots.getByText("No API doc snapshots saved yet.").waitFor();
  const firstClearSnapshotResponse = waitForControllerApiDocsSnapshotCreate(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION",
    limit: "2"
  });
  await insight.getByRole("button", { name: "Save API docs snapshot" }).click();
  const firstClearSnapshotPayload = await (await firstClearSnapshotResponse).json();
  const firstClearSnapshotId = firstClearSnapshotPayload.data.id;
  await apiDocSnapshots.getByText(`Snapshot #${firstClearSnapshotId}`).waitFor();
  const secondClearSnapshotResponse = waitForControllerApiDocsSnapshotCreate(page, {
    riskLevel: "MEDIUM",
    riskCode: "NO_SECURITY_ANNOTATION",
    limit: "2"
  });
  await insight.getByRole("button", { name: "Save API docs snapshot" }).click();
  const secondClearSnapshotPayload = await (await secondClearSnapshotResponse).json();
  const secondClearSnapshotId = secondClearSnapshotPayload.data.id;
  if (secondClearSnapshotId === firstClearSnapshotId) {
    throw new Error("Expected a distinct second Controller API docs snapshot id.");
  }
  await apiDocSnapshots.getByText(`Snapshot #${secondClearSnapshotId}`).waitFor();
  const snapshotClearResponse = waitForControllerApiDocsSnapshotClear(page);
  await apiDocSnapshots.getByRole("button", { name: "Clear snapshots" }).click();
  const snapshotClearPayload = await (await snapshotClearResponse).json();
  if (snapshotClearPayload.data.deletedCount !== 2) {
    throw new Error(`Expected clearing two Controller API docs snapshots, got ${snapshotClearPayload.data.deletedCount}`);
  }
  await apiDocSnapshots.getByText("Cleared 2 snapshots").waitFor();
  await apiDocSnapshots.getByText("No API doc snapshots saved yet.").waitFor();

  await insight.getByLabel("Code search").fill("UserService");
  await clickAndWaitForIdle(page, insight.getByRole("button", { name: "Search" }));
  await insight.getByText("class UserService").first().waitFor();

  const taskForm = page.locator("form").filter({ hasText: "Create task" });
  await clickAndWaitForIdle(page, taskForm.getByRole("button", { name: "Create task" }));
  await page.locator(".taskListItem").filter({ hasText: "Add User pagination API" }).waitFor();
  const taskFilters = page.getByLabel("Task filters");
  await taskFilters.getByLabel("Search tasks").fill("pagination");
  await clickAndWaitForIdle(page, taskFilters.getByRole("button", { name: "Apply filters" }));
  await page.locator(".taskListItem").filter({ hasText: "Add User pagination API" }).waitFor();
  await page.waitForFunction(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get("taskQuery") === "pagination" && params.has("taskId");
  });
  const reloadFilteredTasks = waitForTaskListQuery(page, { query: "pagination" });
  await page.reload({ waitUntil: "domcontentloaded" });
  await reloadFilteredTasks;
  await page.locator(".taskListItem").filter({ hasText: "Add User pagination API" }).waitFor();
  const restoredTaskQuery = await taskFilters.getByLabel("Search tasks").inputValue();
  if (restoredTaskQuery !== "pagination") {
    throw new Error(`Expected restored task query pagination, got ${restoredTaskQuery}`);
  }
  const taskDetail = page.locator(".detailStack");
  await taskDetail.getByRole("heading", { name: /#\d+ Add User pagination API/ }).waitFor();

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "运行任务" }));
  await taskDetail.getByText(/Connecting stream|Live stream/).first().waitFor({ timeout: 15000 });
  await waitForBadge(taskDetail, "WAITING_HUMAN_APPROVAL");
  await expectDashboardMetric(page, overview, "Waiting approval", "1");
  await expectDashboardMetric(page, runMetrics, "Runs", "1");
  await expectDashboardMetric(page, runMetrics, "Success rate", "100%");
  await activity.locator(".activityTitle strong").filter({ hasText: /^waiting_human_approval$/ }).waitFor();
  await taskFilters.getByLabel("Task status filter").selectOption("WAITING_HUMAN_APPROVAL");
  await clickAndWaitForIdle(page, taskFilters.getByRole("button", { name: "Apply filters" }));
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
    const status = document.querySelector('[aria-label="Task status filter"]');
    return status?.value === "WAITING_HUMAN_APPROVAL";
  });
  const restoredStatusTaskQuery = await taskFilters.getByLabel("Search tasks").inputValue();
  if (restoredStatusTaskQuery !== "pagination") {
    throw new Error(`Expected restored task query pagination after status reload, got ${restoredStatusTaskQuery}`);
  }
  await taskFilters.getByRole("button", { name: "Copy task view link" }).click();
  await taskFilters.getByText("Task link copied").waitFor();
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
  await clickAndWaitForIdle(page, taskFilters.getByRole("button", { name: "Reset" }));
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
      text.includes("# RepoPilot Agent Run Report")
        && text.includes("## Planner task plan")
        && text.includes("## Retrieved code context")
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
    !downloadedRunReport.includes("# RepoPilot Agent Run Report")
    || !downloadedRunReport.includes("## Sandbox test result")
    || !downloadedRunReport.includes("## Automated patch review")
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
      text.includes("# RepoPilot Agent Run Report")
        && text.includes("## Planner task plan")
        && text.includes("## Automated patch review")
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
    !downloadedRunReportSnapshot.includes("# RepoPilot Agent Run Report")
    || !downloadedRunReportSnapshot.includes("## Sandbox test result")
    || !downloadedRunReportSnapshot.includes("SPRING_USER_PAGINATION_RECIPE")
  ) {
    throw new Error("Downloaded Agent run report snapshot did not include the expected Markdown content.");
  }
  await taskDetail.getByText("工具调用审计").waitFor();
  await taskDetail.getByText("模型调用审计").waitFor();
  await taskDetail.getByText("Adds GET /api/users/page").first().waitFor();
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
  await taskDetail.getByText("Adds GET /api/users/page").first().waitFor();
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

  await taskForm.getByLabel("Title").fill("Fix User id validation bug");
  await taskForm
    .getByLabel("Description")
    .fill("修复 User 模块 getUser 参数校验 bug，拒绝空 id 和非正数 id。");
  await clickAndWaitForIdle(page, taskForm.getByRole("button", { name: "Create task" }));
  await page.locator(".taskListItem").filter({ hasText: "Fix User id validation bug" }).waitFor();
  await taskDetail.getByRole("heading", { name: /#\d+ Fix User id validation bug/ }).waitFor();

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "运行任务" }));
  await waitForBadge(taskDetail, "WAITING_HUMAN_APPROVAL");
  await taskDetail.getByText("Adds User id validation guard with unit tests.").first().waitFor();
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

  await taskForm.getByLabel("Title").fill("Add User count API");
  await taskForm
    .getByLabel("Description")
    .fill("给 User 模块新增统计用户总数接口。");
  await clickAndWaitForIdle(page, taskForm.getByRole("button", { name: "Create task" }));
  await page.locator(".taskListItem").filter({ hasText: "Add User count API" }).waitFor();
  await taskDetail.getByRole("heading", { name: /#\d+ Add User count API/ }).waitFor();

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "运行任务" }));
  await waitForBadge(taskDetail, "WAITING_HUMAN_APPROVAL");
  await taskDetail.getByText("Adds GET /api/users/count").first().waitFor();
  await taskDetail.getByText("SPRING_USER_COUNT_RECIPE").first().waitFor();
  await taskDetail.getByText("countUsersReturnsTotalNumberOfUsers").first().waitFor();
  await taskDetail.getByText("PASSED").first().waitFor();
  const countChangedFiles = patchPanel.getByLabel("变更文件");
  await countChangedFiles.getByText("src/main/java/com/example/demo/user/UserController.java").waitFor();
  await countChangedFiles.getByText("src/main/java/com/example/demo/user/UserService.java").waitFor();
  await countChangedFiles.getByText("src/main/java/com/example/demo/user/UserMapper.java").waitFor();
  await countChangedFiles.getByText("src/test/java/com/example/demo/user/UserServiceTest.java").waitFor();
  await assertLatestCountPatchChangedFiles(page);

  await taskForm.getByLabel("Title").fill("Add User create API");
  await taskForm
    .getByLabel("Description")
    .fill("给 User 模块新增创建用户接口，接收 name 并返回创建结果。");
  await clickAndWaitForIdle(page, taskForm.getByRole("button", { name: "Create task" }));
  await page.locator(".taskListItem").filter({ hasText: "Add User create API" }).waitFor();
  await taskDetail.getByRole("heading", { name: /#\d+ Add User create API/ }).waitFor();

  await clickAndWaitForIdle(page, taskDetail.getByRole("button", { name: "运行任务" }));
  await waitForBadge(taskDetail, "WAITING_HUMAN_APPROVAL");
  await taskDetail.getByText("Adds POST /api/users").first().waitFor();
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
