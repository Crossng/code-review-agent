import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const logDir = process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_LOG_DIR
  ?? join(repoRoot, "target", "agent-worker-business-smoke", "logs");
const artifactDir = process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_ARTIFACT_DIR
  ?? join(repoRoot, "output", "agent-worker-business-smoke");
const workspaceRoot = process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_WORKSPACE
  ?? join(repoRoot, "target", "agent-worker-business-smoke", "workspace");
const email = process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_EMAIL
  ?? `agent-worker-business-smoke-${Date.now()}@example.test`;
const password = process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_PASSWORD ?? "password123";
const displayName = process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_DISPLAY_NAME ?? "Worker 业务闭环演示";
const repoUrl = process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_REPO_URL
  ?? pathToFileURL(join(repoRoot, "examples", "demo-spring-repo")).toString();
const modelName = "gpt-worker-business-smoke";
const workerToken = "worker-business-smoke-token";
const workerApiKey = "worker-business-smoke-key";
const injectCoderRetry = process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_INJECT_CODER_RETRY !== "false";
const taskTitle = "Worker Coder 业务演示：新增 User 汇总接口";
const taskDescription = [
  "请基于 UserController 和 UserService 新增一个最小业务接口。",
  "目标：GET /api/users/summary 返回一个中文字符串，说明当前示例用户数量。",
  "只修改 UserController.java 和 UserService.java。",
  "输出必须是 raw unified diff，不要输出 Markdown fence 或解释文字。"
].join("\n");
const timeoutMs = Number(process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_TIMEOUT_MS ?? 420_000);
const pollMs = Number(process.env.REPOPILOT_WORKER_BUSINESS_SMOKE_POLL_MS ?? 1_000);
const expectedPaths = [
  "src/main/java/com/example/demo/user/UserController.java",
  "src/main/java/com/example/demo/user/UserService.java"
];
const failureStatuses = new Set([
  "FAILED_REPO_CLONE",
  "FAILED_INDEXING",
  "FAILED_CONTEXT_RETRIEVAL",
  "FAILED_PATCH_GENERATION",
  "FAILED_TEST",
  "FAILED_PR_CREATION",
  "CANCELLED"
]);

const children = [];
const modelRequests = [];
let modelServer;

await mkdir(logDir, { recursive: true });
await mkdir(artifactDir, { recursive: true });
await mkdir(workspaceRoot, { recursive: true });

try {
  const backendPort = await freePort();
  const workerPort = await freePort();
  modelServer = await startModelServer();
  const modelPort = modelServer.address().port;
  const backendUrl = `http://127.0.0.1:${backendPort}`;
  const workerUrl = `http://127.0.0.1:${workerPort}`;
  const apiBase = `${backendUrl}/api`;

  console.log(`后端端口: ${backendPort}`);
  console.log(`Worker 端口: ${workerPort}`);
  console.log(`模型 stub 端口: ${modelPort}`);

  startProcess(
    "backend",
    "mvn",
    ["-q", "-Dmaven.repo.local=../.m2", "spring-boot:run"],
    {
      cwd: join(repoRoot, "backend"),
      env: {
        ...process.env,
        BACKEND_PORT: String(backendPort),
        REPOPILOT_WORKSPACE_ROOT: workspaceRoot,
        REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN: workerToken,
        REPOPILOT_AGENT_WORKER_ENABLED: "true",
        REPOPILOT_AGENT_WORKER_URL: workerUrl,
        REPOPILOT_CODER_MODE: "disabled",
        REPOPILOT_GITHUB_ENABLED: "false",
        REPOPILOT_MAVEN_CACHE: "../.m2",
        REPOPILOT_SANDBOX_TIMEOUT_SECONDS: "600"
      }
    }
  );
  await waitForJson(`${backendUrl}/actuator/health`, (data) => data.status === "UP", "backend");

  startProcess(
    "agent-worker",
    "python3",
    ["-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", String(workerPort)],
    {
      cwd: join(repoRoot, "agent-worker"),
      env: {
        ...process.env,
        PYTHONPATH: join(repoRoot, "agent-worker"),
        REPOPILOT_BACKEND_BASE_URL: backendUrl,
        REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN: workerToken,
        REPOPILOT_BACKEND_TIMEOUT_SECONDS: "600",
        REPOPILOT_WORKER_MODEL_MODE: "disabled",
        REPOPILOT_WORKER_CODER_MODEL_MODE: "openai-compatible",
        REPOPILOT_WORKER_CODER_MODEL_API_BASE_URL: `http://127.0.0.1:${modelPort}/v1`,
        REPOPILOT_WORKER_CODER_MODEL_API_KEY: workerApiKey,
        REPOPILOT_WORKER_CODER_MODEL_NAME: modelName,
        REPOPILOT_WORKER_CODER_MODEL_MAX_COMPLETION_TOKENS: "900",
        REPOPILOT_WORKER_CODER_MODEL_ORGANIZATION: "org-worker-business-smoke",
        REPOPILOT_WORKER_CODER_MODEL_PROJECT: "proj-worker-business-smoke",
        REPOPILOT_WORKER_RETRY_MAX_ATTEMPTS: "2",
        REPOPILOT_WORKER_RETRY_BACKOFF_SECONDS: "0"
      }
    }
  );
  const workerHealth = await waitForJson(`${workerUrl}/health`, (data) => data.status === "UP", "agent-worker");

  const auth = await registerOrLogin(apiBase);
  const token = auth.token;
  console.log(`登录成功: ${auth.user.email}`);

  const project = await apiPost(apiBase, "/projects", token, {
    repoUrl,
    accessToken: "",
    defaultBranch: "main"
  });
  console.log(`项目已创建: #${project.id} ${project.repoFullName}`);

  const cloneResult = await apiPost(apiBase, `/projects/${project.id}/clone`, token, {});
  console.log(`仓库已克隆: ${cloneResult.branch} ${cloneResult.commitSha}`);

  const indexResult = await apiPost(apiBase, `/projects/${project.id}/index`, token, {});
  console.log(`索引完成: files=${indexResult.fileCount}, symbols=${indexResult.symbolCount}, chunks=${indexResult.chunkCount}`);

  const task = await apiPost(apiBase, "/agent/tasks", token, {
    projectId: project.id,
    taskType: "FEATURE",
    title: taskTitle,
    description: taskDescription
  });
  console.log(`任务已创建: #${task.id}`);

  const run = await apiPost(apiBase, `/agent/tasks/${task.id}/run`, token, {});
  const runId = run.id;
  console.log(`后端 Worker bridge run 已启动: #${runId}`);

  const approvalReadyTask = await waitForWorkerPatchReady(apiBase, token, task.id, runId);
  console.log(`任务进入人工审批: ${approvalReadyTask.status}`);

  const [steps, patches, testRuns, modelCalls, toolCalls, runReport] = await Promise.all([
    apiGet(apiBase, `/agent/tasks/${task.id}/steps`, token),
    apiGet(apiBase, `/tasks/${task.id}/patches`, token),
    apiGet(apiBase, `/agent/runs/${runId}/test-runs`, token),
    apiGet(apiBase, `/agent/runs/${runId}/model-calls`, token),
    apiGet(apiBase, `/agent/runs/${runId}/tool-calls`, token),
    apiGet(apiBase, `/agent/tasks/${task.id}/run-report`, token)
  ]);
  const { workerPatch, workerStart, generateCall, retryReportSection } = assertWorkerEvidence({
    steps,
    patches,
    testRuns,
    modelCalls,
    toolCalls,
    runReport
  });
  assertModelRequest();
  console.log(`Worker patch 可审批: #${workerPatch.id} ${workerPatch.generationProvider}/${workerPatch.generationModel}`);

  await apiPost(apiBase, `/tasks/${task.id}/approval/approve`, token, {
    patchId: workerPatch.id,
    comment: "Worker 业务闭环 smoke 自动审批：沙箱测试和风险审查已通过。"
  });
  const preflight = await apiGet(apiBase, `/tasks/${task.id}/pull-request/preflight`, token);
  if (!preflight.canPrepare || preflight.publishMode !== "LOCAL_DRAFT_ONLY") {
    throw new Error(`PR preflight 未通过: ${JSON.stringify(preflight)}`);
  }
  const pullRequest = await apiPost(apiBase, `/tasks/${task.id}/pull-request`, token, {});
  if (pullRequest.status !== "DRAFT_READY" || !pullRequest.commitSha || !pullRequest.targetBranch) {
    throw new Error(`本地 PR 草稿不完整: ${JSON.stringify(pullRequest)}`);
  }
  const finalTask = await apiGet(apiBase, `/agent/tasks/${task.id}`, token);
  if (finalTask.status !== "DONE") {
    throw new Error(`PR 准备后任务状态=${finalTask.status}，预期 DONE。`);
  }

  const artifact = {
    generatedAt: new Date().toISOString(),
    backendUrl,
    workerUrl,
    repoUrl,
    email,
    projectId: project.id,
    taskId: task.id,
    runId,
    workerHealth,
    workerStart,
    taskStatus: finalTask.status,
    patch: {
      id: workerPatch.id,
      status: "APPROVED",
      generationMode: workerPatch.generationMode,
      generationProvider: workerPatch.generationProvider,
      generationModel: workerPatch.generationModel,
      changedFiles: workerPatch.changedFiles
    },
    retryAudit: generateCall.retryAudit,
    pullRequest: {
      id: pullRequest.id,
      status: pullRequest.status,
      baseBranch: pullRequest.baseBranch,
      targetBranch: pullRequest.targetBranch,
      commitSha: pullRequest.commitSha
    },
    modelCallCount: modelCalls.length,
    toolCallCount: toolCalls.length,
    testRuns: testRuns.map((testRun) => ({
      id: testRun.id,
      status: testRun.status,
      command: testRun.command,
      exitCode: testRun.exitCode,
      durationMs: testRun.durationMs
    })),
    runReportSectionCount: runReport.sections.length,
    retryReportSection: retryReportSection ? {
      key: retryReportSection.key,
      title: retryReportSection.title,
      summary: retryReportSection.summary,
      facts: retryReportSection.facts,
      highlights: retryReportSection.highlights
    } : null,
    modelRequest: {
      requestCount: modelRequests.length,
      injectedRetry: injectCoderRetry,
      statuses: modelRequests.map((request) => request.responseStatus),
      path: modelRequests.at(-1)?.path,
      model: modelRequests.at(-1)?.body?.model,
      maxCompletionTokens: modelRequests.at(-1)?.body?.max_completion_tokens,
      organizationConfigured: Boolean(modelRequests.at(-1)?.organization),
      projectConfigured: Boolean(modelRequests.at(-1)?.project)
    }
  };
  const artifactPath = join(artifactDir, "last-run.json");
  await writeFile(artifactPath, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");

  console.log("Agent Worker 业务闭环 smoke 通过。");
  console.log(`Patch: #${workerPatch.id} ${workerPatch.generationMode} / ${workerPatch.generationModel}`);
  console.log(`PR 草稿: ${pullRequest.targetBranch} ${pullRequest.commitSha}`);
  console.log(`证据文件: ${artifactPath}`);
} catch (error) {
  console.error(redact(`Agent Worker 业务闭环 smoke 失败: ${error.message}`));
  if (error.details) {
    console.error(redact(error.details));
  }
  process.exitCode = 1;
} finally {
  await cleanup();
}

async function startModelServer() {
  const server = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
      response.writeHead(404);
      response.end();
      return;
    }
    const rawBody = await readRequestBody(request);
    const body = JSON.parse(rawBody);
    const responseStatus = injectCoderRetry && modelRequests.length === 0 ? 429 : 200;
    modelRequests.push({
      path: request.url,
      authorization: request.headers.authorization,
      organization: request.headers["openai-organization"],
      project: request.headers["openai-project"],
      body,
      responseStatus
    });
    if (responseStatus === 429) {
      const encoded = Buffer.from(JSON.stringify({ error: { message: "worker business smoke rate limited once" } }), "utf8");
      response.writeHead(429, {
        "Content-Type": "application/json",
        "Content-Length": encoded.length
      });
      response.end(encoded);
      return;
    }
    const payload = {
      model: modelName,
      usage: {
        prompt_tokens: 91,
        completion_tokens: 57,
        total_tokens: 148
      },
      choices: [
        {
          message: {
            content: businessDiff()
          }
        }
      ]
    };
    const encoded = Buffer.from(JSON.stringify(payload), "utf8");
    response.writeHead(200, {
      "Content-Type": "application/json",
      "Content-Length": encoded.length
    });
    response.end(encoded);
  });
  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  return server;
}

function businessDiff() {
  return [
    "diff --git a/src/main/java/com/example/demo/user/UserController.java b/src/main/java/com/example/demo/user/UserController.java",
    "index 924c6d2..2222222 100644",
    "--- a/src/main/java/com/example/demo/user/UserController.java",
    "+++ b/src/main/java/com/example/demo/user/UserController.java",
    "@@ -25,5 +25,10 @@ public class UserController {",
    "     @GetMapping(\"/{id}\")",
    "     public UserEntity getUser(@PathVariable Long id) {",
    "         return userService.getUser(id);",
    "     }",
    "+",
    "+    @GetMapping(\"/summary\")",
    "+    public String summarizeUsers() {",
    "+        return userService.summarizeUsers();",
    "+    }",
    " }",
    "diff --git a/src/main/java/com/example/demo/user/UserService.java b/src/main/java/com/example/demo/user/UserService.java",
    "index 486208d..3333333 100644",
    "--- a/src/main/java/com/example/demo/user/UserService.java",
    "+++ b/src/main/java/com/example/demo/user/UserService.java",
    "@@ -20,4 +20,8 @@ public class UserService {",
    "     public UserEntity getUser(Long id) {",
    "         return userMapper.findById(id);",
    "     }",
    "+",
    "+    public String summarizeUsers() {",
    "+        return \"示例用户数量：\" + userMapper.findAll().size();",
    "+    }",
    " }",
    ""
  ].join("\n");
}

async function waitForWorkerPatchReady(apiBase, token, taskId, runId) {
  const startedAt = Date.now();
  let lastStatus = "";
  while (Date.now() - startedAt < timeoutMs) {
    const task = await apiGet(apiBase, `/agent/tasks/${taskId}`, token);
    if (task.status !== lastStatus) {
      console.log(`任务状态: ${task.status}`);
      lastStatus = task.status;
    }
    const patches = await apiGet(apiBase, `/tasks/${taskId}/patches`, token).catch(() => []);
    const workerPatch = patches.find((patch) => isExpectedWorkerPatch(patch, runId));
    if (task.status === "WAITING_HUMAN_APPROVAL" && workerPatch) {
      return task;
    }
    if (failureStatuses.has(task.status)) {
      const steps = await apiGet(apiBase, `/agent/tasks/${taskId}/steps`, token).catch(() => []);
      const latestStep = [...steps].reverse().find((step) => step.status === "FAILED") ?? steps.at(-1);
      const error = new Error(`任务进入失败状态: ${task.status}`);
      error.details = latestStep
        ? `最近步骤: ${latestStep.stepName} ${latestStep.status} ${latestStep.errorMessage ?? ""}`
        : `runId=${runId}`;
      throw error;
    }
    await sleep(pollMs);
  }
  throw new Error(`等待 Worker patch 进入人工审批超时: ${timeoutMs}ms`);
}

function assertWorkerEvidence({ steps, patches, testRuns, modelCalls, toolCalls, runReport }) {
  const stepNames = [
    "agent_worker_start",
    "load_task_context",
    "ensure_index",
    "plan_task",
    "retrieve_context",
    "generate_patch",
    "validate_patch_safety",
    "apply_patch",
    "run_tests",
    "review_patch"
  ];
  for (const stepName of stepNames) {
    assertStepSucceeded(steps, stepName);
  }
  const workerStartStep = steps.find((step) => step.stepName === "agent_worker_start" && step.status === "SUCCESS");
  const workerStart = parseStepOutput(workerStartStep, "agent_worker_start");
  if (workerStart.execution_mode !== "WORKER_PRIMARY" || workerStart.accepted !== true || workerStart.status !== "QUEUED") {
    throw new Error(`后端 Worker bridge 未进入主执行模式: ${JSON.stringify(workerStart)}`);
  }
  const approvalStep = steps.find((step) => step.stepName === "waiting_human_approval");
  if (!approvalStep || approvalStep.status !== "PENDING") {
    throw new Error("缺少 waiting_human_approval PENDING step。");
  }
  const workerPatch = patches.find((patch) => isExpectedWorkerPatch(patch));
  if (!workerPatch) {
    throw new Error("缺少 Worker Coder 生成的 OPENAI_COMPATIBLE / LLM_CODER_DRAFT patch。");
  }
  if (patches.length !== 1) {
    throw new Error(`当前任务产生了 ${patches.length} 个 patch，预期只有 Worker primary 生成的 1 个。`);
  }
  if (workerPatch.status !== "APPLIED") {
    throw new Error(`Worker patch status=${workerPatch.status}，预期 APPLIED。`);
  }
  if (!workerPatch.diffContent.startsWith("diff --git ")) {
    throw new Error("Worker patch 不是 raw unified diff。");
  }
  for (const expectedPath of expectedPaths) {
    if (!workerPatch.diffContent.includes(expectedPath)) {
      throw new Error(`Worker patch 缺少预期路径: ${expectedPath}`);
    }
  }
  const passedTest = testRuns.find((testRun) =>
    testRun.patchId === workerPatch.id && testRun.status === "PASSED" && testRun.exitCode === 0
  );
  if (!passedTest) {
    throw new Error("缺少 Worker patch 对应的 PASSED 沙箱 test_run。");
  }
  const generateCall = modelCalls.find((call) =>
    call.stepName === "generate_patch"
      && call.modelProvider === "OPENAI_COMPATIBLE"
      && call.modelName === modelName
      && call.status === "SUCCESS"
  );
  if (!generateCall || generateCall.totalTokens !== 148) {
    throw new Error(`缺少 Worker Coder generate_patch 模型调用审计: ${JSON.stringify(generateCall)}`);
  }
  if (injectCoderRetry) {
    if (
      !generateCall.retryAudit
      || generateCall.retryAudit.attemptCount !== 1
      || generateCall.retryAudit.recovered !== true
      || generateCall.retryAudit.firstFailureType !== "WorkerModelError"
      || !String(generateCall.retryAudit.firstFailureMessage ?? "").includes("HTTP 429")
    ) {
      throw new Error(`模型调用缺少结构化 retryAudit: ${JSON.stringify(generateCall.retryAudit)}`);
    }
  }
  const serializedModelCall = JSON.stringify(generateCall);
  if (serializedModelCall.includes(workerApiKey) || serializedModelCall.includes("Authorization")) {
    throw new Error("模型调用审计泄漏了 API key 或 Authorization header。");
  }
  for (const toolName of ["load_run_context", "list_project_files", "search_code", "read_project_file"]) {
    if (!toolCalls.some((call) => call.toolName === toolName && call.status === "SUCCESS")) {
      throw new Error(`缺少成功工具调用审计: ${toolName}`);
    }
  }
  if (!Array.isArray(runReport.sections) || runReport.sections.length < 5) {
    throw new Error("运行报告没有生成足够的 Agent evidence sections。");
  }
  const retryReportSection = assertRunReportRetryEvidence(runReport);
  return { workerPatch, workerStart, generateCall, retryReportSection };
}

function assertRunReportRetryEvidence(runReport) {
  if (!injectCoderRetry) {
    return null;
  }
  const section = (runReport.sections ?? []).find((candidate) =>
    candidate.key === "worker_retry" || candidate.title === "Worker 重试恢复证据"
  );
  if (!section) {
    throw new Error("运行报告缺少 Worker 重试恢复证据 section。");
  }
  const facts = section.facts ?? [];
  for (const expectedFact of ["重试失败尝试：1", "恢复调用：1/1", "模型调用：1"]) {
    if (!facts.includes(expectedFact)) {
      throw new Error(`Worker 重试恢复证据 facts 缺少 ${expectedFact}: ${JSON.stringify(facts)}`);
    }
  }
  const serializedHighlights = JSON.stringify(section.highlights ?? []);
  if (!serializedHighlights.includes("模型 generate_patch") || !serializedHighlights.includes("HTTP 429")) {
    throw new Error(`Worker 重试恢复证据 highlights 不完整: ${serializedHighlights}`);
  }
  if (!String(section.summary ?? "").includes("1 次可恢复失败尝试")) {
    throw new Error(`Worker 重试恢复证据 summary 不完整: ${section.summary}`);
  }
  const markdown = String(runReport.markdown ?? "");
  for (const expected of ["## Worker 重试恢复证据", "模型 generate_patch", "HTTP 429", "写型 callback 仍不做透明重试"]) {
    if (!markdown.includes(expected)) {
      throw new Error(`运行报告 Markdown 缺少 ${expected}。`);
    }
  }
  return section;
}

function parseStepOutput(step, stepName) {
  try {
    return JSON.parse(step.outputJson);
  } catch (error) {
    throw new Error(`${stepName} step output 不是合法 JSON: ${error.message}`);
  }
}

function assertModelRequest() {
  const expectedRequestCount = injectCoderRetry ? 2 : 1;
  if (modelRequests.length !== expectedRequestCount) {
    throw new Error(`模型 stub 请求数量=${modelRequests.length}，预期 ${expectedRequestCount}。`);
  }
  if (injectCoderRetry && (modelRequests[0].responseStatus !== 429 || modelRequests[1].responseStatus !== 200)) {
    throw new Error(`模型 stub 没有先 429 后恢复: ${JSON.stringify(modelRequests.map((request) => request.responseStatus))}`);
  }
  const request = modelRequests.at(-1);
  if (request.authorization !== `Bearer ${workerApiKey}`) {
    throw new Error("Worker Coder 没有通过 Authorization header 传递模型 key。");
  }
  if (request.organization !== "org-worker-business-smoke" || request.project !== "proj-worker-business-smoke") {
    throw new Error("Worker Coder 没有传递 organization/project header。");
  }
  if (request.body.model !== modelName || request.body.max_completion_tokens !== 900) {
    throw new Error(`模型请求参数不符合预期: ${JSON.stringify(request.body)}`);
  }
  const serialized = JSON.stringify(request.body);
  for (const expected of ["RepoPilot CoderAgent", "Return only one raw unified diff", "UserController", "UserService"]) {
    if (!serialized.includes(expected)) {
      throw new Error(`模型请求缺少上下文: ${expected}`);
    }
  }
  if (serialized.includes(workerApiKey)) {
    throw new Error("模型 prompt 泄漏了 API key。");
  }
}

function isExpectedWorkerPatch(patch, runId = null) {
  return patch.generationMode === "LLM_CODER_DRAFT"
    && patch.generationProvider === "OPENAI_COMPATIBLE"
    && patch.generationModel === modelName
    && (runId === null || patch.agentRunId === runId)
    && expectedPaths.every((expectedPath) =>
      (patch.changedFiles ?? []).some((changedFile) => changedFile.path === expectedPath)
    );
}

function assertStepSucceeded(steps, stepName) {
  const step = steps.find((candidate) => candidate.stepName === stepName && candidate.status === "SUCCESS");
  if (!step) {
    throw new Error(`缺少成功 Agent step: ${stepName}`);
  }
}

async function registerOrLogin(apiBase) {
  try {
    return await apiPost(apiBase, "/auth/register", null, {
      email,
      password,
      displayName
    });
  } catch (error) {
    if (error.status !== 409) {
      throw error;
    }
    return apiPost(apiBase, "/auth/login", null, { email, password });
  }
}

async function apiGet(apiBase, path, token) {
  return apiRequest(apiBase, path, { method: "GET", token });
}

async function apiPost(apiBase, path, token, body) {
  return apiRequest(apiBase, path, { method: "POST", token, body });
}

async function apiRequest(apiBase, path, { method, token, body }) {
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
      const error = new Error(`接口返回非 JSON: ${method} ${path} HTTP ${response.status}`);
      error.status = response.status;
      error.details = text.slice(0, 1200);
      throw error;
    }
  }
  if (!response.ok || payload?.success === false) {
    const error = new Error(payload?.message ?? `接口请求失败: ${method} ${path} HTTP ${response.status}`);
    error.status = response.status;
    error.code = payload?.code ?? null;
    error.details = text.slice(0, 1600);
    throw error;
  }
  return payload?.data;
}

async function waitForJson(url, predicate, label) {
  const startedAt = Date.now();
  let lastError;
  while (Date.now() - startedAt < timeoutMs) {
    try {
      const response = await fetch(url);
      if (response.ok) {
        const data = await response.json();
        if (predicate(data)) {
          return data;
        }
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(500);
  }
  throw new Error(`等待 ${label} 超时: ${lastError?.message ?? url}`);
}

function startProcess(label, command, args, options) {
  const logStream = createWriteStream(join(logDir, `${label}.log`), { flags: "a" });
  const child = spawn(command, args, {
    ...options,
    stdio: ["ignore", "pipe", "pipe"]
  });
  child.stdout.pipe(logStream, { end: false });
  child.stderr.pipe(logStream, { end: false });
  child.on("exit", (code, signal) => {
    logStream.write(`\n[${label} exited code=${code} signal=${signal}]\n`);
  });
  children.push({ label, child, logStream });
  return child;
}

async function cleanup() {
  if (modelServer) {
    await new Promise((resolveClose) => modelServer.close(resolveClose));
  }
  await Promise.all(children.map(({ child }) => stopChild(child)));
  for (const { logStream } of children) {
    await new Promise((resolveEnd) => logStream.end(resolveEnd));
  }
}

async function stopChild(child) {
  if (child.exitCode !== null || child.signalCode !== null) {
    return;
  }
  child.kill("SIGTERM");
  await Promise.race([
    new Promise((resolveExit) => child.once("exit", resolveExit)),
    sleep(5000).then(() => {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill("SIGKILL");
      }
    })
  ]);
}

async function freePort() {
  const server = createServer();
  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  const port = server.address().port;
  await new Promise((resolveClose) => server.close(resolveClose));
  return port;
}

async function readRequestBody(request) {
  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function redact(text) {
  return String(text).split(workerApiKey).join("<redacted>").split(workerToken).join("<redacted>");
}
