import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { mkdir, writeFile } from "node:fs/promises";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const backendUrl = trimTrailingSlash(process.env.REPOPILOT_BACKEND_URL ?? "http://127.0.0.1:8080");
const apiBase = `${backendUrl}/api`;
const email = process.env.REPOPILOT_REAL_CODER_DEMO_EMAIL ?? `real-coder-demo-${Date.now()}@example.test`;
const password = process.env.REPOPILOT_REAL_CODER_DEMO_PASSWORD ?? "password123";
const displayName = process.env.REPOPILOT_REAL_CODER_DEMO_DISPLAY_NAME ?? "真实 Coder 演示";
const repoUrl = process.env.REPOPILOT_REAL_CODER_DEMO_REPO_URL
  ?? pathToFileURL(join(repoRoot, "examples", "demo-spring-repo")).toString();
const expectedPath = process.env.REPOPILOT_REAL_CODER_DEMO_EXPECTED_PATH
  ?? ".repopilot/real-coder-demo-note.md";
const taskTitle = process.env.REPOPILOT_REAL_CODER_DEMO_TASK_TITLE
  ?? "真实 Coder 演示：新增 RepoPilot 运行说明文件";
const taskDescription = process.env.REPOPILOT_REAL_CODER_DEMO_TASK_DESCRIPTION
  ?? [
    "请完成一个非常小的安全改动，用来验证真实 OpenAI-compatible Coder 端到端链路。",
    `只新增 ${expectedPath}。`,
    "文件内容使用中文 Markdown，说明 RepoPilot 正在用真实模型生成 unified diff，并简要记录 UserService 当前负责读取用户示例数据。",
    "不要修改 Java 源码，不要修改 pom.xml，不要修改测试文件。",
    "只输出 raw unified diff，不要输出解释性文字。"
  ].join("\n");
const timeoutMs = Number(process.env.REPOPILOT_REAL_CODER_DEMO_TIMEOUT_MS ?? 360_000);
const pollMs = Number(process.env.REPOPILOT_REAL_CODER_DEMO_POLL_MS ?? 1_000);
const artifactDir = process.env.REPOPILOT_REAL_CODER_DEMO_ARTIFACT_DIR
  ?? join(repoRoot, "output", "real-coder-demo");

const secretValues = [
  process.env.REPOPILOT_CODER_API_KEY,
  process.env.OPENAI_API_KEY,
  process.env.REPOPILOT_GITHUB_TOKEN,
  process.env.GITHUB_TOKEN
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
  console.log("开始真实 Coder API 演示。");
  console.log(`后端: ${backendUrl}`);
  console.log(`仓库: ${repoUrl}`);
  console.log(`临时用户: ${email}`);

  const auth = await registerOrLogin();
  const token = auth.token;
  console.log(`登录成功：${auth.user.email}`);

  const coderSettings = await apiGet("/settings/coder", token);
  assertCoderReady(coderSettings);
  console.log(`真实 Coder 就绪：${coderSettings.provider} / ${coderSettings.model}`);

  const project = await apiPost("/projects", token, {
    repoUrl,
    accessToken: "",
    defaultBranch: "main"
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

  const finalTask = await waitForTaskApproval(token, task.id, run.id);
  console.log(`任务进入人工审批：${finalTask.status}`);

  const [steps, patch, testRuns, modelCalls, runReport] = await Promise.all([
    apiGet(`/agent/tasks/${task.id}/steps`, token),
    apiGet(`/tasks/${task.id}/patches/latest`, token),
    apiGet(`/agent/runs/${run.id}/test-runs`, token),
    apiGet(`/agent/runs/${run.id}/model-calls`, token),
    apiGet(`/agent/tasks/${task.id}/run-report`, token)
  ]);

  assertStepSucceeded(steps, "generate_patch");
  assertStepSucceeded(steps, "validate_patch_safety");
  assertStepSucceeded(steps, "run_tests");
  assertStepSucceeded(steps, "review_patch");
  assertPatch(patch);
  assertModelCalls(modelCalls, coderSettings.model);
  assertTestRuns(testRuns);

  const artifact = {
    generatedAt: new Date().toISOString(),
    backendUrl,
    repoUrl,
    email,
    projectId: project.id,
    taskId: task.id,
    runId: run.id,
    taskStatus: finalTask.status,
    patch: {
      id: patch.id,
      status: patch.status,
      generationMode: patch.generationMode,
      generationProvider: patch.generationProvider,
      generationModel: patch.generationModel,
      changedFiles: patch.changedFiles
    },
    testRuns: testRuns.map((testRun) => ({
      id: testRun.id,
      status: testRun.status,
      command: testRun.command,
      exitCode: testRun.exitCode,
      durationMs: testRun.durationMs
    })),
    modelCalls: modelCalls.map((call) => ({
      id: call.id,
      stepName: call.stepName,
      modelProvider: call.modelProvider,
      modelName: call.modelName,
      status: call.status,
      durationMs: call.durationMs,
      totalTokens: call.totalTokens
    })),
    reportSectionCount: runReport.sections.length
  };
  const artifactPath = join(artifactDir, "last-run.json");
  await writeFile(artifactPath, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");

  console.log("真实 Coder 演示通过。");
  console.log(`Patch: #${patch.id} ${patch.generationProvider} / ${patch.generationModel}`);
  console.log(`测试: ${testRuns.map((testRun) => `${testRun.command}=${testRun.status}`).join(", ")}`);
  console.log(`证据文件: ${artifactPath}`);
} catch (error) {
  console.error(redact(`真实 Coder 演示失败：${error.message}`));
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

function assertCoderReady(settings) {
  if (settings.provider !== "OPENAI_COMPATIBLE") {
    throw new Error(`当前后端 Coder provider=${settings.provider}，需要 OPENAI_COMPATIBLE。请用真实 Coder 环境变量重启后端。`);
  }
  if (!settings.ready || !settings.apiKeyConfigured || !settings.model) {
    const missing = Array.isArray(settings.missingRequirements) ? settings.missingRequirements.join(", ") : "unknown";
    throw new Error(`当前后端 Coder 尚未就绪：missing=${missing || "unknown"}。`);
  }
  if (!["openai", "openai-compatible"].includes(settings.mode)) {
    throw new Error(`当前后端 Coder mode=${settings.mode}，需要 openai-compatible。`);
  }
}

async function waitForTaskApproval(token, taskId, runId) {
  const startedAt = Date.now();
  let lastStatus = "";
  while (Date.now() - startedAt < timeoutMs) {
    const task = await apiGet(`/agent/tasks/${taskId}`, token);
    if (task.status !== lastStatus) {
      console.log(`任务状态：${task.status}`);
      lastStatus = task.status;
    }
    if (task.status === "WAITING_HUMAN_APPROVAL") {
      return task;
    }
    if (failureStatuses.has(task.status)) {
      const steps = await apiGet(`/agent/tasks/${taskId}/steps`, token).catch(() => []);
      const latestStep = [...steps].reverse().find((step) => step.status === "FAILED") ?? steps.at(-1);
      const detail = latestStep
        ? `最近步骤：${latestStep.stepName} ${latestStep.status} ${latestStep.errorMessage ?? ""}`
        : `runId=${runId}`;
      const error = new Error(`任务进入失败状态：${task.status}`);
      error.details = detail;
      throw error;
    }
    await sleep(pollMs);
  }
  throw new Error(`等待任务进入人工审批超时：${timeoutMs}ms`);
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

function assertPatch(patch) {
  if (patch.generationMode !== "LLM_CODER_DRAFT") {
    throw new Error(`Patch generationMode=${patch.generationMode}，需要 LLM_CODER_DRAFT。`);
  }
  if (patch.generationProvider !== "OPENAI_COMPATIBLE") {
    throw new Error(`Patch generationProvider=${patch.generationProvider}，需要 OPENAI_COMPATIBLE。`);
  }
  if (patch.status !== "GENERATED") {
    throw new Error(`Patch status=${patch.status}，需要 GENERATED。`);
  }
  if (!patch.diffContent?.startsWith("diff --git ")) {
    throw new Error("Patch diffContent 不是 raw unified diff。");
  }
  const changedPaths = new Set((patch.changedFiles ?? []).map((file) => file.path));
  if (!changedPaths.has(expectedPath)) {
    throw new Error(`Patch changedFiles 未包含预期文件：${expectedPath}`);
  }
}

function assertModelCalls(modelCalls, expectedModel) {
  const generateCall = modelCalls.find((call) =>
    call.stepName === "generate_patch"
      && call.modelProvider === "OPENAI_COMPATIBLE"
      && call.status === "SUCCESS"
  );
  if (!generateCall) {
    throw new Error("缺少成功的 OPENAI_COMPATIBLE generate_patch model call。");
  }
  if (expectedModel && generateCall.modelName !== expectedModel) {
    throw new Error(`模型调用 modelName=${generateCall.modelName}，预期 ${expectedModel}。`);
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
