import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const logDir = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_LOG_DIR
  ?? join(repoRoot, "target", "agent-worker-real-coder-demo", "logs");
const artifactDir = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_ARTIFACT_DIR
  ?? join(repoRoot, "output", "agent-worker-real-coder-demo");
const workspaceRoot = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_WORKSPACE
  ?? join(repoRoot, "target", "agent-worker-real-coder-demo", "workspace");
const email = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_EMAIL
  ?? `agent-worker-real-coder-demo-${Date.now()}@example.test`;
const password = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_PASSWORD ?? "password123";
const displayName = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_DISPLAY_NAME ?? "Worker 真实 Coder 演示";
const repoUrl = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_REPO_URL
  ?? pathToFileURL(join(repoRoot, "examples", "demo-spring-repo")).toString();
const expectedPath = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_EXPECTED_PATH
  ?? ".repopilot/worker-real-coder-demo-note.md";
const taskTitle = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_TASK_TITLE
  ?? "Worker 真实 Coder 演示：新增 RepoPilot Worker 运行说明文件";
const taskDescription = process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_TASK_DESCRIPTION
  ?? [
    "请完成一个非常小的安全改动，用来验证 Python Agent Worker 的真实 OpenAI-compatible Coder 链路。",
    `只新增 ${expectedPath}。`,
    "文件内容使用中文 Markdown，说明 RepoPilot 正在由 Agent Worker 调用真实模型生成 unified diff。",
    "请提到当前检索上下文包含 UserController 和 UserService，但不要修改任何 Java 源码。",
    "不要修改 pom.xml，不要修改测试文件，不要创建 Pull Request。",
    "只输出 raw unified diff，不要输出 Markdown fence、解释性文字或多个方案。"
  ].join("\n");
const timeoutMs = Number(process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_TIMEOUT_MS ?? 420_000);
const pollMs = Number(process.env.REPOPILOT_WORKER_REAL_CODER_DEMO_POLL_MS ?? 1_000);
const configuredWorkerCoderModel = process.env.REPOPILOT_WORKER_CODER_MODEL_NAME ?? "";
const workerToken = "worker-real-coder-demo-token";
const secretValues = [
  process.env.REPOPILOT_WORKER_CODER_MODEL_API_KEY,
  process.env.OPENAI_API_KEY,
  process.env.REPOPILOT_CODER_API_KEY,
  process.env.REPOPILOT_GITHUB_TOKEN,
  process.env.GITHUB_TOKEN,
  workerToken
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

const children = [];

await mkdir(logDir, { recursive: true });
await mkdir(artifactDir, { recursive: true });
await mkdir(workspaceRoot, { recursive: true });

try {
  const backendPort = await freePort();
  const workerPort = await freePort();
  const backendUrl = `http://127.0.0.1:${backendPort}`;
  const workerUrl = `http://127.0.0.1:${workerPort}`;
  const apiBase = `${backendUrl}/api`;

  console.log("开始 Agent Worker 真实 Coder 演示。");
  console.log(`后端端口: ${backendPort}`);
  console.log(`Worker 端口: ${workerPort}`);
  console.log(`仓库: ${repoUrl}`);

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
        REPOPILOT_SANDBOX_TIMEOUT_SECONDS: process.env.REPOPILOT_SANDBOX_TIMEOUT_SECONDS ?? "600"
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
        REPOPILOT_BACKEND_TIMEOUT_SECONDS: process.env.REPOPILOT_BACKEND_TIMEOUT_SECONDS ?? "600",
        REPOPILOT_WORKER_MODEL_MODE: process.env.REPOPILOT_WORKER_MODEL_MODE ?? "disabled"
      }
    }
  );
  const workerHealth = await waitForJson(`${workerUrl}/health`, (data) => data.status === "UP", "agent-worker");
  console.log(`Worker 健康: ${workerHealth.graph_engine ?? "unknown"}`);

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
  console.log(`仓库已克隆: ${cloneResult.branch ?? project.defaultBranch} ${cloneResult.commitSha ?? ""}`.trim());

  const indexResult = await apiPost(apiBase, `/projects/${project.id}/index`, token, {});
  console.log(`索引完成: files=${indexResult.fileCount}, symbols=${indexResult.symbolCount}, chunks=${indexResult.chunkCount}`);

  const task = await apiPost(apiBase, "/agent/tasks", token, {
    projectId: project.id,
    taskType: "FEATURE",
    title: taskTitle,
    description: taskDescription
  });
  console.log(`任务已创建: #${task.id} ${task.title}`);

  const run = await apiPost(apiBase, `/agent/tasks/${task.id}/run`, token, {});
  console.log(`Worker primary run 已启动: #${run.id}`);

  const finalTask = await waitForTaskApproval(apiBase, token, task.id, run.id);
  console.log(`任务进入人工审批: ${finalTask.status}`);

  const [steps, patches, testRuns, modelCalls, toolCalls, runReport] = await Promise.all([
    apiGet(apiBase, `/agent/tasks/${task.id}/steps`, token),
    apiGet(apiBase, `/tasks/${task.id}/patches`, token),
    apiGet(apiBase, `/agent/runs/${run.id}/test-runs`, token),
    apiGet(apiBase, `/agent/runs/${run.id}/model-calls`, token),
    apiGet(apiBase, `/agent/runs/${run.id}/tool-calls`, token),
    apiGet(apiBase, `/agent/tasks/${task.id}/run-report`, token)
  ]);

  const evidence = assertWorkerRealCoderEvidence({ steps, patches, testRuns, modelCalls, toolCalls, runReport });
  assertNoSecretLeak({ modelCalls, toolCalls, runReport });

  const artifact = {
    generatedAt: new Date().toISOString(),
    backendUrl,
    workerUrl,
    repoUrl,
    email,
    workerHealth,
    projectId: project.id,
    taskId: task.id,
    runId: run.id,
    taskStatus: finalTask.status,
    configuredWorkerCoderModel,
    workerStart: evidence.workerStart,
    patch: {
      id: evidence.patch.id,
      status: evidence.patch.status,
      generationMode: evidence.patch.generationMode,
      generationProvider: evidence.patch.generationProvider,
      generationModel: evidence.patch.generationModel,
      changedFiles: evidence.patch.changedFiles
    },
    generateModelCall: {
      id: evidence.generateCall.id,
      stepName: evidence.generateCall.stepName,
      modelProvider: evidence.generateCall.modelProvider,
      modelName: evidence.generateCall.modelName,
      status: evidence.generateCall.status,
      durationMs: evidence.generateCall.durationMs,
      promptTokens: evidence.generateCall.promptTokens,
      completionTokens: evidence.generateCall.completionTokens,
      totalTokens: evidence.generateCall.totalTokens
    },
    toolCallCount: toolCalls.length,
    testRuns: testRuns.map((testRun) => ({
      id: testRun.id,
      patchId: testRun.patchId,
      status: testRun.status,
      command: testRun.command,
      exitCode: testRun.exitCode,
      durationMs: testRun.durationMs
    })),
    runReportSectionCount: runReport.sections.length,
    outputFiles: {
      logs: logDir,
      json: join(artifactDir, "last-run.json")
    }
  };
  const artifactPath = join(artifactDir, "last-run.json");
  await writeFile(artifactPath, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");

  console.log("Agent Worker 真实 Coder 演示通过。");
  console.log(`Patch: #${evidence.patch.id} ${evidence.patch.generationProvider} / ${evidence.patch.generationModel}`);
  console.log(`测试: ${artifact.testRuns.map((testRun) => `${testRun.command}=${testRun.status}`).join(", ")}`);
  console.log(`证据文件: ${artifactPath}`);
} catch (error) {
  console.error(redact(`Agent Worker 真实 Coder 演示失败: ${error.message}`));
  if (error.details) {
    console.error(redact(error.details));
  }
  process.exitCode = 1;
} finally {
  await cleanup();
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

async function waitForTaskApproval(apiBase, token, taskId, runId) {
  const startedAt = Date.now();
  let lastStatus = "";
  while (Date.now() - startedAt < timeoutMs) {
    const task = await apiGet(apiBase, `/agent/tasks/${taskId}`, token);
    if (task.status !== lastStatus) {
      console.log(`任务状态: ${task.status}`);
      lastStatus = task.status;
    }
    const patches = await apiGet(apiBase, `/tasks/${taskId}/patches`, token).catch(() => []);
    const patch = patches.find((candidate) => isExpectedPatch(candidate));
    if (task.status === "WAITING_HUMAN_APPROVAL" && patch) {
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
  throw new Error(`等待 Worker 真实 Coder 进入人工审批超时: ${timeoutMs}ms`);
}

function assertWorkerRealCoderEvidence({ steps, patches, testRuns, modelCalls, toolCalls, runReport }) {
  for (const stepName of [
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
  ]) {
    assertStepSucceeded(steps, stepName);
  }
  const approvalStep = steps.find((step) => step.stepName === "waiting_human_approval");
  if (!approvalStep || approvalStep.status !== "PENDING") {
    throw new Error("缺少 waiting_human_approval PENDING step。");
  }
  const workerStartStep = steps.find((step) => step.stepName === "agent_worker_start" && step.status === "SUCCESS");
  const workerStart = parseStepOutput(workerStartStep, "agent_worker_start");
  if (workerStart.execution_mode !== "WORKER_PRIMARY" || workerStart.accepted !== true || workerStart.status !== "QUEUED") {
    throw new Error(`后端 Worker bridge 未进入主执行模式: ${JSON.stringify(workerStart)}`);
  }
  const patch = patches.find((candidate) => isExpectedPatch(candidate));
  if (!patch) {
    throw new Error(`缺少 Worker 真实 Coder 生成的 ${expectedPath} patch。`);
  }
  if (patches.length !== 1) {
    throw new Error(`当前任务产生了 ${patches.length} 个 patch，预期只有 Worker primary 生成的 1 个。`);
  }
  if (patch.status !== "APPLIED") {
    throw new Error(`Worker patch status=${patch.status}，预期 APPLIED。`);
  }
  if (!patch.diffContent?.startsWith("diff --git ")) {
    throw new Error("Worker patch 不是 raw unified diff。");
  }
  const passedTest = testRuns.find((testRun) =>
    testRun.patchId === patch.id && testRun.status === "PASSED" && testRun.exitCode === 0
  );
  if (!passedTest) {
    throw new Error("缺少 Worker 真实 Coder patch 对应的 PASSED 沙箱 test_run。");
  }
  const generateCall = modelCalls.find((call) =>
    call.stepName === "generate_patch"
      && call.modelProvider === "OPENAI_COMPATIBLE"
      && call.status === "SUCCESS"
  );
  if (!generateCall) {
    throw new Error("缺少成功的 Worker Coder OPENAI_COMPATIBLE generate_patch 模型调用审计。");
  }
  for (const toolName of ["load_run_context", "list_project_files", "search_code"]) {
    assertToolCallSucceeded(toolCalls, toolName);
  }
  if (!Array.isArray(runReport.sections) || runReport.sections.length < 5) {
    throw new Error("运行报告没有生成足够的 Agent evidence sections。");
  }
  return { patch, workerStart, generateCall };
}

function assertToolCallSucceeded(toolCalls, toolName) {
  if (!toolCalls.some((call) => call.toolName === toolName && call.status === "SUCCESS")) {
    throw new Error(`缺少成功工具调用审计: ${toolName}`);
  }
}

function isExpectedPatch(patch) {
  return patch.generationMode === "LLM_CODER_DRAFT"
    && patch.generationProvider === "OPENAI_COMPATIBLE"
    && (patch.changedFiles ?? []).some((changedFile) => changedFile.path === expectedPath);
}

function assertNoSecretLeak({ modelCalls, toolCalls, runReport }) {
  const serialized = JSON.stringify({ modelCalls, toolCalls, runReport });
  for (const secret of secretValues) {
    if (serialized.includes(secret)) {
      throw new Error("审计证据中出现了模型 key、GitHub token 或 Worker callback token。");
    }
  }
  if (serialized.includes("Authorization")) {
    throw new Error("审计证据中出现了 Authorization header。");
  }
}

function assertStepSucceeded(steps, stepName) {
  const step = steps.find((candidate) => candidate.stepName === stepName && candidate.status === "SUCCESS");
  if (!step) {
    throw new Error(`缺少成功 Agent step: ${stepName}`);
  }
}

function parseStepOutput(step, stepName) {
  try {
    return JSON.parse(step.outputJson);
  } catch (error) {
    throw new Error(`${stepName} step output 不是合法 JSON: ${error.message}`);
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

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function redact(text) {
  return secretValues.reduce((current, secret) => current.split(secret).join("<redacted>"), String(text));
}
