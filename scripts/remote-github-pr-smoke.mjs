import { spawn, spawnSync } from "node:child_process";
import { createWriteStream } from "node:fs";
import { cp, mkdir, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const logDir = process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_LOG_DIR
  ?? join(repoRoot, "target", "remote-github-pr-smoke", "logs");
const artifactDir = process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_ARTIFACT_DIR
  ?? join(repoRoot, "output", "remote-github-pr-smoke");
const workspaceRoot = process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_WORKSPACE
  ?? join(repoRoot, "target", "remote-github-pr-smoke", "workspace");
const gitRoot = process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_GIT_ROOT
  ?? join(repoRoot, "target", "remote-github-pr-smoke", "git");
const email = process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_EMAIL
  ?? `remote-github-pr-smoke-${Date.now()}@example.test`;
const password = process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_PASSWORD ?? "password123";
const displayName = process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_DISPLAY_NAME ?? "远端 PR 本地替身 Smoke";
const repoOwner = "repopilot-smoke";
const repoName = "demo-spring-repo";
const repoFullName = `${repoOwner}/${repoName}`;
const repoUrl = `https://github.com/${repoFullName}.git`;
const defaultBranch = "main";
const fakeGithubToken = process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_GITHUB_TOKEN
  ?? "remote-github-pr-smoke-token";
const timeoutMs = Number(process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_TIMEOUT_MS ?? 420_000);
const pollMs = Number(process.env.REPOPILOT_REMOTE_GITHUB_PR_SMOKE_POLL_MS ?? 1_000);

const failureStatuses = new Set([
  "FAILED_REPO_CLONE",
  "FAILED_INDEXING",
  "FAILED_CONTEXT_RETRIEVAL",
  "FAILED_PATCH_GENERATION",
  "FAILED_TEST",
  "FAILED_PR_CREATION",
  "CANCELLED"
]);
const secretValues = [
  fakeGithubToken,
  process.env.REPOPILOT_GITHUB_TOKEN,
  process.env.GITHUB_TOKEN,
  process.env.REPOPILOT_CODER_API_KEY,
  process.env.OPENAI_API_KEY
].filter((value) => value && value.length >= 4);

const children = [];
const githubRequests = [];
let githubServer;

await mkdir(logDir, { recursive: true });
await mkdir(artifactDir, { recursive: true });
await mkdir(workspaceRoot, { recursive: true });
await mkdir(gitRoot, { recursive: true });

try {
  const gitFixture = await prepareLocalGitHubRepository();
  githubServer = await startGitHubApiStub();
  const githubApiBaseUrl = `http://127.0.0.1:${githubServer.address().port}`;
  const backendPort = await freePort();
  const backendUrl = `http://127.0.0.1:${backendPort}`;
  const apiBase = `${backendUrl}/api`;

  console.log(`后端端口: ${backendPort}`);
  console.log(`GitHub API stub: ${githubApiBaseUrl}`);
  console.log(`GitHub 仓库替身: ${gitFixture.bareRepository}`);
  console.log(`临时用户: ${email}`);

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
        REPOPILOT_AGENT_WORKER_ENABLED: "false",
        REPOPILOT_CODER_MODE: "disabled",
        REPOPILOT_GITHUB_ENABLED: "true",
        REPOPILOT_GITHUB_TOKEN: fakeGithubToken,
        REPOPILOT_GITHUB_API_BASE_URL: githubApiBaseUrl,
        REPOPILOT_MAVEN_CACHE: "../.m2",
        REPOPILOT_SANDBOX_TIMEOUT_SECONDS: "600",
        GIT_CONFIG_COUNT: "1",
        GIT_CONFIG_KEY_0: `url.${gitFixture.bareRepositoryUrl}.insteadOf`,
        GIT_CONFIG_VALUE_0: repoUrl
      }
    }
  );
  await waitForJson(`${backendUrl}/actuator/health`, (data) => data.status === "UP", "backend");

  const auth = await registerOrLogin(apiBase);
  const token = auth.token;
  console.log(`登录成功: ${auth.user.email}`);

  const githubSettings = await apiGet(apiBase, "/settings/github", token);
  assertGitHubSettings(githubSettings);
  console.log(`GitHub 发布配置: ${githubSettings.publishMode}`);

  const project = await apiPost(apiBase, "/projects", token, {
    repoUrl,
    accessToken: "",
    defaultBranch
  });
  console.log(`项目已创建: #${project.id} ${project.repoFullName}`);

  const cloneResult = await apiPost(apiBase, `/projects/${project.id}/clone`, token, {});
  console.log(`仓库已克隆: ${cloneResult.branch} ${cloneResult.commitSha}`);
  await configureLocalRewrite(cloneResult.localPath, gitFixture.bareRepositoryUrl);

  const indexResult = await apiPost(apiBase, `/projects/${project.id}/index`, token, {});
  console.log(`索引完成: files=${indexResult.fileCount}, symbols=${indexResult.symbolCount}, chunks=${indexResult.chunkCount}`);

  const task = await apiPost(apiBase, "/agent/tasks", token, {
    projectId: project.id,
    taskType: "FEATURE",
    title: "远端 PR smoke：新增 User count API",
    description: "请给 User 模块新增 count API，返回当前用户总数，并补充对应 Service 单元测试。"
  });
  console.log(`任务已创建: #${task.id}`);

  const run = await apiPost(apiBase, `/agent/tasks/${task.id}/run`, token, {});
  console.log(`运行已启动: #${run.id}`);

  const approvalReadyTask = await waitForTaskStatus(apiBase, token, task.id, "WAITING_HUMAN_APPROVAL");
  console.log(`任务进入人工审批: ${approvalReadyTask.status}`);

  const [steps, patch, testRuns] = await Promise.all([
    apiGet(apiBase, `/agent/tasks/${task.id}/steps`, token),
    apiGet(apiBase, `/tasks/${task.id}/patches/latest`, token),
    apiGet(apiBase, `/agent/runs/${run.id}/test-runs`, token)
  ]);
  assertStepSucceeded(steps, "generate_patch");
  assertStepSucceeded(steps, "validate_patch_safety");
  assertStepSucceeded(steps, "run_tests");
  assertStepSucceeded(steps, "review_patch");
  assertRecipePatch(patch);
  assertTestRuns(testRuns);
  console.log(`补丁可审批: #${patch.id} ${patch.generationMode}`);

  await apiPost(apiBase, `/tasks/${task.id}/approval/approve`, token, {
    patchId: patch.id,
    comment: "远端 GitHub PR 本地替身 smoke 自动审批：沙箱测试已通过。"
  });
  console.log("补丁已审批，准备检查远端 PR preflight。");

  const preflight = await apiGet(apiBase, `/tasks/${task.id}/pull-request/preflight`, token);
  assertPreflight(preflight);
  console.log("PR preflight 通过: 远端 GitHub PR 将执行。");

  const pullRequest = await apiPost(apiBase, `/tasks/${task.id}/pull-request`, token, {});
  assertPullRequest(pullRequest);
  assertGitHubRequest(githubRequests, pullRequest);
  const pushedSha = verifyPushedBranch(gitFixture.bareRepository, pullRequest);

  const finalTask = await apiGet(apiBase, `/agent/tasks/${task.id}`, token);
  if (finalTask.status !== "DONE") {
    throw new Error(`PR 创建后任务状态=${finalTask.status}，预期 DONE。`);
  }

  const artifact = {
    generatedAt: new Date().toISOString(),
    backendUrl,
    githubApiBaseUrl,
    repoUrl,
    repoFullName,
    defaultBranch,
    email,
    projectId: project.id,
    taskId: task.id,
    runId: run.id,
    clone: {
      localPath: cloneResult.localPath,
      branch: cloneResult.branch,
      commitSha: cloneResult.commitSha
    },
    localGitHubStub: {
      bareRepository: gitFixture.bareRepository,
      bareRepositoryUrl: gitFixture.bareRepositoryUrl,
      sourceCommitSha: gitFixture.sourceCommitSha,
      pushedBranchSha: pushedSha
    },
    patch: {
      id: patch.id,
      status: patch.status,
      generationMode: patch.generationMode,
      generationProvider: patch.generationProvider,
      changedFiles: patch.changedFiles
    },
    preflight: {
      canPrepare: preflight.canPrepare,
      publishMode: preflight.publishMode,
      remotePublishingWillRun: preflight.remotePublishingWillRun,
      repositoryEligible: preflight.repositoryEligible,
      tokenConfigured: preflight.tokenConfigured
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
    githubApiRequest: {
      requestCount: githubRequests.length,
      method: githubRequests[0]?.method,
      path: githubRequests[0]?.path,
      authorizationHeaderPresent: Boolean(githubRequests[0]?.authorization),
      authorizationHeaderValue: githubRequests[0]?.authorization ? "<redacted>" : null,
      accept: githubRequests[0]?.accept,
      apiVersion: githubRequests[0]?.apiVersion,
      body: githubRequests[0]?.body
    },
    testRuns: testRuns.map((testRun) => ({
      id: testRun.id,
      status: testRun.status,
      command: testRun.command,
      exitCode: testRun.exitCode,
      durationMs: testRun.durationMs
    }))
  };
  const artifactPath = join(artifactDir, "last-run.json");
  const artifactText = JSON.stringify(artifact, null, 2);
  assertNoSecretLeak(artifactText);
  await writeFile(artifactPath, `${artifactText}\n`, "utf8");

  console.log("远端 GitHub PR 本地替身 smoke 通过。");
  console.log(`PR: ${pullRequest.url}`);
  console.log(`分支: ${pullRequest.targetBranch}`);
  console.log(`证据文件: ${artifactPath}`);
} catch (error) {
  console.error(redact(`远端 GitHub PR 本地替身 smoke 失败: ${error.message}`));
  if (error.details) {
    console.error(redact(error.details));
  }
  process.exitCode = 1;
} finally {
  await cleanup();
}

async function prepareLocalGitHubRepository() {
  const demoRepository = join(repoRoot, "examples", "demo-spring-repo");
  const sourceRepository = join(gitRoot, "source");
  const bareRepository = join(gitRoot, "demo-spring-repo.git");
  const bareRepositoryUrl = pathToFileURL(bareRepository).toString();

  await rm(sourceRepository, { recursive: true, force: true });
  await rm(bareRepository, { recursive: true, force: true });
  await mkdir(gitRoot, { recursive: true });
  await cp(demoRepository, sourceRepository, {
    recursive: true,
    filter: (source) => !relative(demoRepository, source).split(/[\\/]/).includes(".git")
  });

  runGit(sourceRepository, ["init"]);
  runGit(sourceRepository, ["checkout", "-B", defaultBranch]);
  runGit(sourceRepository, ["config", "user.name", "RepoPilot Smoke"]);
  runGit(sourceRepository, ["config", "user.email", "repopilot-smoke@example.local"]);
  runGit(sourceRepository, ["add", "."]);
  runGit(sourceRepository, ["commit", "-m", "初始化远端 PR smoke 仓库"]);
  const sourceCommitSha = runGit(sourceRepository, ["rev-parse", "HEAD"]).trim();

  runGit(gitRoot, ["init", "--bare", bareRepository]);
  runGit(sourceRepository, ["remote", "add", "origin", bareRepository]);
  runGit(sourceRepository, ["push", "origin", defaultBranch]);

  return {
    sourceRepository,
    bareRepository,
    bareRepositoryUrl,
    sourceCommitSha
  };
}

async function startGitHubApiStub() {
  const server = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== `/repos/${repoOwner}/${repoName}/pulls`) {
      response.writeHead(404);
      response.end();
      return;
    }
    const rawBody = await readRequestBody(request);
    const body = JSON.parse(rawBody);
    githubRequests.push({
      method: request.method,
      path: request.url,
      authorization: request.headers.authorization,
      accept: request.headers.accept,
      apiVersion: request.headers["x-github-api-version"],
      contentType: request.headers["content-type"],
      body
    });
    const payload = {
      number: 987,
      html_url: `https://github.com/${repoFullName}/pull/987`
    };
    const encoded = Buffer.from(JSON.stringify(payload), "utf8");
    response.writeHead(201, {
      "Content-Type": "application/json",
      "Content-Length": encoded.length
    });
    response.end(encoded);
  });
  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  return server;
}

async function configureLocalRewrite(localPath, bareRepositoryUrl) {
  if (!localPath) {
    throw new Error("clone 响应缺少 localPath，无法配置本地 GitHub 替身。");
  }
  runGit(localPath, ["config", `url.${bareRepositoryUrl}.insteadOf`, repoUrl]);
  const configuredValue = runGit(localPath, ["config", "--get", `url.${bareRepositoryUrl}.insteadOf`]).trim();
  if (configuredValue !== repoUrl) {
    throw new Error(`本地 git insteadOf 配置失败: ${configuredValue}`);
  }
}

function verifyPushedBranch(bareRepository, pullRequest) {
  const pushedSha = runCommand(
    "git",
    ["--git-dir", bareRepository, "rev-parse", `refs/heads/${pullRequest.targetBranch}`],
    { cwd: repoRoot }
  ).trim();
  if (pushedSha !== pullRequest.commitSha) {
    throw new Error(`远端替身分支 commit=${pushedSha}，预期 ${pullRequest.commitSha}。`);
  }
  return pushedSha;
}

function assertGitHubSettings(settings) {
  if (settings.provider !== "GITHUB") {
    throw new Error(`GitHub provider=${settings.provider}，需要 GITHUB。`);
  }
  if (!settings.enabled || !settings.remotePublishingEnabled || settings.publishMode !== "REMOTE_GITHUB_PR") {
    throw new Error(`GitHub 发布未启用远端模式: ${JSON.stringify(settings)}`);
  }
  if (!settings.ready || !settings.tokenConfigured) {
    throw new Error(`GitHub 发布配置未就绪: ${JSON.stringify(settings)}`);
  }
  const serialized = JSON.stringify(settings);
  assertNoSecretLeak(serialized);
}

function assertStepSucceeded(steps, stepName) {
  const step = steps.find((candidate) => candidate.stepName === stepName);
  if (!step) {
    throw new Error(`缺少 Agent step: ${stepName}`);
  }
  if (step.status !== "SUCCESS") {
    throw new Error(`Agent step 未成功: ${stepName}=${step.status}`);
  }
}

function assertRecipePatch(patch) {
  if (patch.generationMode !== "SPRING_USER_COUNT_RECIPE") {
    throw new Error(`Patch generationMode=${patch.generationMode}，需要 SPRING_USER_COUNT_RECIPE。`);
  }
  if (patch.generationProvider !== "LOCAL_RECIPE_CATALOG") {
    throw new Error(`Patch generationProvider=${patch.generationProvider}，需要 LOCAL_RECIPE_CATALOG。`);
  }
  if (!["GENERATED", "APPLIED"].includes(patch.status)) {
    throw new Error(`Patch status=${patch.status}，需要 GENERATED 或 APPLIED。`);
  }
  const changedPaths = new Set((patch.changedFiles ?? []).map((file) => file.path));
  for (const expectedPath of [
    "src/main/java/com/example/demo/user/UserController.java",
    "src/main/java/com/example/demo/user/UserService.java",
    "src/main/java/com/example/demo/user/UserMapper.java",
    "src/test/java/com/example/demo/user/UserServiceTest.java"
  ]) {
    if (!changedPaths.has(expectedPath)) {
      throw new Error(`Patch changedFiles 未包含预期文件: ${expectedPath}`);
    }
  }
}

function assertTestRuns(testRuns) {
  if (!Array.isArray(testRuns) || testRuns.length === 0) {
    throw new Error("缺少沙箱 test_run 记录。");
  }
  const failed = testRuns.find((testRun) => testRun.status !== "PASSED" || testRun.exitCode !== 0);
  if (failed) {
    throw new Error(`沙箱测试未通过: ${failed.command} status=${failed.status} exit=${failed.exitCode}`);
  }
}

function assertPreflight(preflight) {
  if (!preflight.canPrepare) {
    throw new Error(`PR preflight 未通过: ${(preflight.blockers ?? []).join("; ")}`);
  }
  if (preflight.publishMode !== "REMOTE_GITHUB_PR") {
    throw new Error(`PR publishMode=${preflight.publishMode}，需要 REMOTE_GITHUB_PR。`);
  }
  if (!preflight.remotePublishingWillRun || !preflight.repositoryEligible || !preflight.tokenConfigured) {
    throw new Error(`PR preflight 未确认远端 GitHub 发布: ${JSON.stringify(preflight)}`);
  }
}

function assertPullRequest(pullRequest) {
  if (pullRequest.status !== "OPEN") {
    throw new Error(`PR status=${pullRequest.status}，需要 OPEN。错误: ${pullRequest.errorMessage ?? ""}`);
  }
  if (pullRequest.prNumber !== 987 || pullRequest.url !== `https://github.com/${repoFullName}/pull/987`) {
    throw new Error(`PR 编号或 URL 不符合 stub 响应: ${JSON.stringify(pullRequest)}`);
  }
  if (!pullRequest.remotePushedAt || !pullRequest.openedAt) {
    throw new Error("PR 响应缺少 remotePushedAt 或 openedAt。");
  }
  if (!pullRequest.targetBranch?.startsWith("repopilot/task-")) {
    throw new Error(`PR targetBranch=${pullRequest.targetBranch} 不符合 RepoPilot 分支约定。`);
  }
}

function assertGitHubRequest(requests, pullRequest) {
  if (requests.length !== 1) {
    throw new Error(`GitHub API stub 收到 ${requests.length} 个 PR 请求，预期 1 个。`);
  }
  const request = requests[0];
  if (request.authorization !== `Bearer ${fakeGithubToken}`) {
    throw new Error("GitHub API stub 没有收到预期的 Authorization bearer header。");
  }
  if (request.accept !== "application/vnd.github+json") {
    throw new Error(`GitHub API Accept header=${request.accept}，不符合预期。`);
  }
  if (request.apiVersion !== "2022-11-28") {
    throw new Error(`GitHub API version=${request.apiVersion}，不符合预期。`);
  }
  if (request.body.title !== "RepoPilot：远端 PR smoke：新增 User count API") {
    throw new Error(`GitHub PR title=${request.body.title}，不符合中文标题约定。`);
  }
  if (request.body.head !== pullRequest.targetBranch || request.body.base !== defaultBranch) {
    throw new Error(`GitHub PR head/base 不符合预期: ${JSON.stringify(request.body)}`);
  }
  if (!String(request.body.body ?? "").includes("请给 User 模块新增 count API")) {
    throw new Error("GitHub PR body 缺少任务描述。");
  }
  assertNoSecretLeak(JSON.stringify({ ...request, authorization: "<redacted>" }));
}

async function waitForTaskStatus(apiBase, token, taskId, expectedStatus) {
  const startedAt = Date.now();
  let lastStatus = "";
  while (Date.now() - startedAt < timeoutMs) {
    const task = await apiGet(apiBase, `/agent/tasks/${taskId}`, token);
    if (task.status !== lastStatus) {
      console.log(`任务状态: ${task.status}`);
      lastStatus = task.status;
    }
    if (task.status === expectedStatus) {
      return task;
    }
    if (failureStatuses.has(task.status)) {
      const steps = await apiGet(apiBase, `/agent/tasks/${taskId}/steps`, token).catch(() => []);
      const latestStep = [...steps].reverse().find((step) => step.status === "FAILED") ?? steps.at(-1);
      const error = new Error(`任务进入失败状态: ${task.status}`);
      error.details = latestStep
        ? `最近步骤: ${latestStep.stepName} ${latestStep.status} ${latestStep.errorMessage ?? ""}`
        : `taskId=${taskId}`;
      throw error;
    }
    await sleep(pollMs);
  }
  throw new Error(`等待任务进入 ${expectedStatus} 超时: ${timeoutMs}ms`);
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
      error.details = text.slice(0, 1_200);
      throw error;
    }
  }
  if (!response.ok || payload?.success === false) {
    const error = new Error(payload?.message ?? `接口请求失败: ${method} ${path} HTTP ${response.status}`);
    error.status = response.status;
    error.code = payload?.code ?? null;
    error.details = text.slice(0, 1_600);
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
  children.push(child);
}

async function freePort() {
  const server = createServer();
  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  const port = server.address().port;
  await new Promise((resolveClose) => server.close(resolveClose));
  return port;
}

function runGit(cwd, args) {
  return runCommand("git", args, { cwd });
}

function runCommand(command, args, { cwd, env = process.env } = {}) {
  const result = spawnSync(command, args, {
    cwd,
    env,
    encoding: "utf8"
  });
  const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    const error = new Error(`${command} ${args.join(" ")} 失败，exit=${result.status}`);
    error.details = output.slice(0, 2_000);
    throw error;
  }
  return output;
}

async function readRequestBody(request) {
  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function cleanup() {
  for (const child of children.reverse()) {
    if (child.exitCode !== null || child.signalCode !== null) {
      continue;
    }
    if (!child.killed) {
      child.kill();
    }
    await new Promise((resolveWait) => child.once("exit", resolveWait));
  }
  if (githubServer) {
    await new Promise((resolveClose) => githubServer.close(resolveClose));
  }
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function assertNoSecretLeak(text) {
  for (const secret of secretValues) {
    if (text.includes(secret)) {
      throw new Error("运行证据泄漏了 token 或 key 原文。");
    }
  }
}

function redact(text) {
  return secretValues.reduce((current, secret) => current.split(secret).join("<redacted>"), text);
}
