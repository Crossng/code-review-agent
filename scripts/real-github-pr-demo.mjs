import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { mkdir, writeFile } from "node:fs/promises";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const backendUrl = trimTrailingSlash(process.env.REPOPILOT_BACKEND_URL ?? "http://127.0.0.1:8080");
const apiBase = `${backendUrl}/api`;
const repoUrl = process.env.REPOPILOT_REAL_GITHUB_PR_REPO_URL ?? "";
const defaultBranch = process.env.REPOPILOT_REAL_GITHUB_PR_DEFAULT_BRANCH ?? "main";
const email = process.env.REPOPILOT_REAL_GITHUB_PR_DEMO_EMAIL ?? `real-github-pr-demo-${Date.now()}@example.test`;
const password = process.env.REPOPILOT_REAL_GITHUB_PR_DEMO_PASSWORD ?? "password123";
const displayName = process.env.REPOPILOT_REAL_GITHUB_PR_DEMO_DISPLAY_NAME ?? "真实 GitHub PR 演示";
const taskTitle = process.env.REPOPILOT_REAL_GITHUB_PR_TASK_TITLE ?? "新增 User count API";
const taskDescription = process.env.REPOPILOT_REAL_GITHUB_PR_TASK_DESCRIPTION
  ?? "请给 User 模块新增 count API，返回当前用户总数，并补充对应 Service 单元测试。";
const timeoutMs = Number(process.env.REPOPILOT_REAL_GITHUB_PR_TIMEOUT_MS ?? 420_000);
const pollMs = Number(process.env.REPOPILOT_REAL_GITHUB_PR_POLL_MS ?? 1_000);
const artifactDir = process.env.REPOPILOT_REAL_GITHUB_PR_ARTIFACT_DIR
  ?? join(repoRoot, "output", "real-github-pr-demo");

const secretValues = [
  process.env.REPOPILOT_GITHUB_TOKEN,
  process.env.GITHUB_TOKEN,
  process.env.REPOPILOT_CODER_API_KEY,
  process.env.OPENAI_API_KEY
].filter((value) => value && value.length >= 4);

const failureStatuses = new Set([
  "FAILED_REPO_CLONE",
  "FAILED_INDEXING",
  "FAILED_CONTEXT_RETRIEVAL",
  "FAILED_PATCH_GENERATION",
  "FAILED_TEST",
  "FAILED_PR_CREATION",
  "CANCELLED"
]);

await mkdir(artifactDir, { recursive: true });

try {
  assertGitHubRepoUrl(repoUrl);
  console.log("开始真实 GitHub PR 发布演示。");
  console.log(`后端: ${backendUrl}`);
  console.log(`仓库: ${repoUrl}`);
  console.log(`临时用户: ${email}`);

  const auth = await registerOrLogin();
  const token = auth.token;
  console.log(`登录成功：${auth.user.email}`);

  const githubSettings = await apiGet("/settings/github", token);
  assertGitHubReady(githubSettings);
  console.log(`GitHub 发布就绪：${githubSettings.publishMode}`);

  const project = await apiPost("/projects", token, {
    repoUrl,
    accessToken: "",
    defaultBranch
  });
  console.log(`项目已创建：#${project.id} ${project.repoFullName}`);

  const cloneResult = await apiPost(`/projects/${project.id}/clone`, token, {});
  console.log(`仓库已克隆：${cloneResult.branch ?? project.defaultBranch} ${cloneResult.commitSha ?? ""}`.trim());

  const indexResult = await apiPost(`/projects/${project.id}/index`, token, {});
  console.log(`索引完成：files=${indexResult.fileCount}, symbols=${indexResult.symbolCount}, chunks=${indexResult.chunkCount}`);

  const task = await apiPost("/agent/tasks", token, {
    projectId: project.id,
    taskType: "FEATURE",
    title: taskTitle,
    description: taskDescription
  });
  console.log(`任务已创建：#${task.id} ${task.title}`);

  const run = await apiPost(`/agent/tasks/${task.id}/run`, token, {});
  console.log(`运行已启动：run #${run.id}`);

  const approvalReadyTask = await waitForTaskStatus(token, task.id, "WAITING_HUMAN_APPROVAL");
  console.log(`任务进入人工审批：${approvalReadyTask.status}`);

  const [steps, patch, testRuns] = await Promise.all([
    apiGet(`/agent/tasks/${task.id}/steps`, token),
    apiGet(`/tasks/${task.id}/patches/latest`, token),
    apiGet(`/agent/runs/${run.id}/test-runs`, token)
  ]);
  assertStepSucceeded(steps, "generate_patch");
  assertStepSucceeded(steps, "validate_patch_safety");
  assertStepSucceeded(steps, "run_tests");
  assertStepSucceeded(steps, "review_patch");
  assertRecipePatch(patch);
  assertTestRuns(testRuns);
  console.log(`补丁可审批：#${patch.id} ${patch.generationMode}`);

  await apiPost(`/tasks/${task.id}/approval/approve`, token, {
    patchId: patch.id,
    comment: "真实 GitHub PR 演示脚本自动审批：沙箱测试已通过。"
  });
  console.log("补丁已审批，任务进入 PR 准备状态。");

  const preflight = await apiGet(`/tasks/${task.id}/pull-request/preflight`, token);
  assertPreflight(preflight);
  console.log("PR preflight 通过：远端 GitHub PR 将执行。");

  const pullRequest = await apiPost(`/tasks/${task.id}/pull-request`, token, {});
  assertPullRequest(pullRequest);

  const finalTask = await apiGet(`/agent/tasks/${task.id}`, token);
  if (finalTask.status !== "DONE") {
    throw new Error(`PR 创建后任务状态=${finalTask.status}，预期 DONE。`);
  }

  const artifact = {
    generatedAt: new Date().toISOString(),
    backendUrl,
    repoUrl,
    defaultBranch,
    email,
    projectId: project.id,
    taskId: task.id,
    runId: run.id,
    patch: {
      id: patch.id,
      status: patch.status,
      generationMode: patch.generationMode,
      generationProvider: patch.generationProvider,
      changedFiles: patch.changedFiles
    },
    pullRequest: {
      id: pullRequest.id,
      status: pullRequest.status,
      prNumber: pullRequest.prNumber,
      url: pullRequest.url,
      baseBranch: pullRequest.baseBranch,
      targetBranch: pullRequest.targetBranch,
      commitSha: pullRequest.commitSha,
      remotePushedAt: pullRequest.remotePushedAt,
      openedAt: pullRequest.openedAt
    },
    testRuns: testRuns.map((testRun) => ({
      id: testRun.id,
      status: testRun.status,
      command: testRun.command,
      exitCode: testRun.exitCode
    }))
  };
  const artifactPath = join(artifactDir, "last-run.json");
  await writeFile(artifactPath, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");

  console.log("真实 GitHub PR 发布演示通过。");
  console.log(`PR: ${pullRequest.url}`);
  console.log(`分支: ${pullRequest.targetBranch}`);
  console.log(`证据文件: ${artifactPath}`);
} catch (error) {
  console.error(redact(`真实 GitHub PR 发布演示失败：${error.message}`));
  if (error.details) {
    console.error(redact(error.details));
  }
  process.exitCode = 1;
}

async function registerOrLogin() {
  try {
    return await apiPost("/auth/register", null, {
      email,
      password,
      displayName
    });
  } catch (error) {
    if (error.status !== 409) {
      throw error;
    }
    return apiPost("/auth/login", null, { email, password });
  }
}

async function waitForTaskStatus(token, taskId, expectedStatus) {
  const startedAt = Date.now();
  let lastStatus = "";
  while (Date.now() - startedAt < timeoutMs) {
    const task = await apiGet(`/agent/tasks/${taskId}`, token);
    if (task.status !== lastStatus) {
      console.log(`任务状态：${task.status}`);
      lastStatus = task.status;
    }
    if (task.status === expectedStatus) {
      return task;
    }
    if (failureStatuses.has(task.status)) {
      const steps = await apiGet(`/agent/tasks/${taskId}/steps`, token).catch(() => []);
      const latestStep = [...steps].reverse().find((step) => step.status === "FAILED") ?? steps.at(-1);
      const detail = latestStep
        ? `最近步骤：${latestStep.stepName} ${latestStep.status} ${latestStep.errorMessage ?? ""}`
        : `taskId=${taskId}`;
      const error = new Error(`任务进入失败状态：${task.status}`);
      error.details = detail;
      throw error;
    }
    await sleep(pollMs);
  }
  throw new Error(`等待任务进入 ${expectedStatus} 超时：${timeoutMs}ms`);
}

function assertGitHubRepoUrl(value) {
  if (!value) {
    throw new Error("缺少 REPOPILOT_REAL_GITHUB_PR_REPO_URL。");
  }
  try {
    const url = new URL(value);
    if (url.hostname !== "github.com") {
      throw new Error();
    }
  } catch {
    if (!value.startsWith("git@github.com:")) {
      throw new Error("REPOPILOT_REAL_GITHUB_PR_REPO_URL 必须是 github.com 仓库地址。");
    }
  }
}

function assertGitHubReady(settings) {
  if (settings.provider !== "GITHUB") {
    throw new Error(`GitHub provider=${settings.provider}，需要 GITHUB。`);
  }
  if (!settings.enabled || !settings.remotePublishingEnabled || settings.publishMode !== "REMOTE_GITHUB_PR") {
    throw new Error("后端未启用远端 GitHub PR 发布，请用 REPOPILOT_GITHUB_ENABLED=true 重启后端。");
  }
  if (!settings.ready || !settings.tokenConfigured) {
    const missing = Array.isArray(settings.missingRequirements) ? settings.missingRequirements.join(", ") : "token";
    throw new Error(`GitHub 发布尚未就绪：missing=${missing || "token"}。`);
  }
}

function assertStepSucceeded(steps, stepName) {
  const step = steps.find((candidate) => candidate.stepName === stepName);
  if (!step) {
    throw new Error(`缺少 Agent step：${stepName}`);
  }
  if (step.status !== "SUCCESS") {
    throw new Error(`Agent step 未成功：${stepName}=${step.status}`);
  }
}

function assertRecipePatch(patch) {
  if (patch.generationMode !== "SPRING_USER_COUNT_RECIPE") {
    throw new Error(`Patch generationMode=${patch.generationMode}，需要 SPRING_USER_COUNT_RECIPE。`);
  }
  if (patch.generationProvider !== "LOCAL_RECIPE_CATALOG") {
    throw new Error(`Patch generationProvider=${patch.generationProvider}，需要 LOCAL_RECIPE_CATALOG。`);
  }
  if (patch.status !== "GENERATED") {
    throw new Error(`Patch status=${patch.status}，需要 GENERATED。`);
  }
  const changedPaths = new Set((patch.changedFiles ?? []).map((file) => file.path));
  for (const expectedPath of [
    "src/main/java/com/example/demo/user/UserController.java",
    "src/main/java/com/example/demo/user/UserService.java",
    "src/main/java/com/example/demo/user/UserMapper.java",
    "src/test/java/com/example/demo/user/UserServiceTest.java"
  ]) {
    if (!changedPaths.has(expectedPath)) {
      throw new Error(`Patch changedFiles 未包含预期文件：${expectedPath}`);
    }
  }
}

function assertTestRuns(testRuns) {
  if (!Array.isArray(testRuns) || testRuns.length === 0) {
    throw new Error("缺少沙箱 test_run 记录。");
  }
  const failed = testRuns.find((testRun) => testRun.status !== "PASSED" || testRun.exitCode !== 0);
  if (failed) {
    throw new Error(`沙箱测试未通过：${failed.command} status=${failed.status} exit=${failed.exitCode}`);
  }
}

function assertPreflight(preflight) {
  if (!preflight.canPrepare) {
    throw new Error(`PR preflight 未通过：${(preflight.blockers ?? []).join("；")}`);
  }
  if (preflight.publishMode !== "REMOTE_GITHUB_PR") {
    throw new Error(`PR publishMode=${preflight.publishMode}，需要 REMOTE_GITHUB_PR。`);
  }
  if (!preflight.remotePublishingWillRun || !preflight.repositoryEligible || !preflight.tokenConfigured) {
    throw new Error("PR preflight 未确认远端 GitHub 发布、仓库资格和 token。");
  }
}

function assertPullRequest(pullRequest) {
  if (pullRequest.status !== "OPEN") {
    throw new Error(`PR status=${pullRequest.status}，需要 OPEN。错误：${pullRequest.errorMessage ?? ""}`);
  }
  if (!pullRequest.url || !pullRequest.prNumber) {
    throw new Error("PR 响应缺少 URL 或编号。");
  }
  if (!pullRequest.remotePushedAt || !pullRequest.openedAt) {
    throw new Error("PR 响应缺少 remotePushedAt 或 openedAt。");
  }
  if (!pullRequest.targetBranch?.startsWith("repopilot/task-")) {
    throw new Error(`PR targetBranch=${pullRequest.targetBranch} 不符合 RepoPilot 分支约定。`);
  }
}

async function apiGet(path, token) {
  return apiRequest(path, { method: "GET", token });
}

async function apiPost(path, token, body) {
  return apiRequest(path, { method: "POST", token, body });
}

async function apiRequest(path, { method, token, body }) {
  const response = await fetch(`${apiBase}${path}`, {
    method,
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      const error = new Error(`接口返回非 JSON：${method} ${path} HTTP ${response.status}`);
      error.details = text.slice(0, 800);
      error.status = response.status;
      throw error;
    }
  }
  if (!response.ok || payload?.success === false) {
    const error = new Error(payload?.message ?? `接口请求失败：${method} ${path} HTTP ${response.status}`);
    error.status = response.status;
    error.code = payload?.code ?? null;
    error.details = text.slice(0, 1_200);
    throw error;
  }
  return payload?.data;
}

function trimTrailingSlash(value) {
  return value.endsWith("/") ? value.slice(0, -1) : value;
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function redact(text) {
  return secretValues.reduce((current, secret) => current.split(secret).join("<redacted>"), text);
}
