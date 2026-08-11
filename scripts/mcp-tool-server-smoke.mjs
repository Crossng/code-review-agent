import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const logDir = process.env.REPOPILOT_MCP_TOOL_SERVER_SMOKE_LOG_DIR
  ?? join(repoRoot, "target", "mcp-tool-server-smoke", "logs");
const artifactDir = process.env.REPOPILOT_MCP_TOOL_SERVER_SMOKE_ARTIFACT_DIR
  ?? join(repoRoot, "output", "mcp-tool-server-smoke");
const timeoutMs = Number(process.env.REPOPILOT_MCP_TOOL_SERVER_SMOKE_TIMEOUT_MS ?? 180_000);

let child;

await mkdir(logDir, { recursive: true });
await mkdir(artifactDir, { recursive: true });

try {
  const port = await freePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  console.log(`MCP 工具目录服务端口: ${port}`);

  child = startServer(port);
  await waitForJson(`${baseUrl}/actuator/health`, (data) => data.status === "UP", "mcp-tool-server");

  const toolsResponse = await apiGet(`${baseUrl}/api/mcp/tools`);
  assert(toolsResponse.success === true, "工具目录响应 success 应为 true。");
  const catalog = toolsResponse.data;
  assert(catalog.service === "RepoPilot MCP 工具目录服务", "工具目录服务名称不符合中文约定。");
  assert(catalog.protocolVersion === "REPOPILOT_MCP_CONTRACT_V1", "工具协议版本不符合预期。");
  assert(catalog.toolCount >= 16, `工具数量过少: ${catalog.toolCount}`);
  const toolNames = new Set(catalog.tools.map((tool) => tool.name));
  for (const expected of ["list_project_files", "read_file", "search_code", "run_maven_test", "create_pull_request"]) {
    assert(toolNames.has(expected), `工具目录缺少 ${expected}`);
  }

  const readFileResponse = await apiGet(`${baseUrl}/api/mcp/tools/read_file`);
  assert(readFileResponse.data.title === "读取项目文件", "read_file 中文标题不符合预期。");
  assert(readFileResponse.data.accessMode === "READ", "read_file 应为 READ 工具。");

  const validRead = await apiPost(`${baseUrl}/api/mcp/tools/read_file/validate`, {
    arguments: {
      runId: 7,
      path: "src\\main\\java\\com\\example\\UserController.java"
    }
  });
  assert(validRead.data.valid === true, `read_file 合法路径未通过: ${JSON.stringify(validRead.data)}`);
  assert(
    validRead.data.normalizedArguments.path === "src/main/java/com/example/UserController.java",
    "read_file 没有规范化反斜杠路径。"
  );

  const unsafeRead = await apiPost(`${baseUrl}/api/mcp/tools/read_file/validate`, {
    arguments: {
      runId: 7,
      path: "../secret.txt"
    }
  });
  assert(unsafeRead.data.valid === false, "read_file 应拒绝路径穿越。");
  assert(unsafeRead.data.status === "BLOCKED", "read_file 越权路径应返回 BLOCKED。");

  const invalidSearch = await apiPost(`${baseUrl}/api/mcp/tools/search_code/validate`, {
    arguments: {
      runId: "7",
      query: "User Controller",
      limit: 21,
      unexpected: true
    }
  });
  assert(invalidSearch.data.valid === false, "search_code 应拒绝错误类型、越界 limit 和未知参数。");
  assert(invalidSearch.data.status === "BLOCKED", "search_code 错误参数应返回 BLOCKED。");
  assert(
    invalidSearch.data.messages.includes("参数 runId 必须是 number。"),
    "search_code 没有拦截错误 runId 类型。"
  );
  assert(
    invalidSearch.data.messages.includes("未知参数：unexpected"),
    "search_code 没有拦截未知参数。"
  );
  assert(
    invalidSearch.data.messages.includes("limit 必须是 1 到 20 之间的整数。"),
    "search_code 没有拦截越界 limit。"
  );

  const pullRequestValidation = await apiPost(`${baseUrl}/api/mcp/tools/create_pull_request/validate`, {
    arguments: {
      taskId: 9,
      title: "RepoPilot：新增接口",
      body: "由 RepoPilot 准备。"
    }
  });
  assert(pullRequestValidation.data.valid === false, "create_pull_request 缺少人工审批时不应通过。");
  assert(
    pullRequestValidation.data.status === "NEEDS_HUMAN_APPROVAL",
    "create_pull_request 缺少人工审批时状态应为 NEEDS_HUMAN_APPROVAL。"
  );

  const artifact = {
    generatedAt: new Date().toISOString(),
    baseUrl,
    protocolVersion: catalog.protocolVersion,
    toolCount: catalog.toolCount,
    checkedTools: [...toolNames].filter((name) =>
      ["list_project_files", "read_file", "search_code", "run_maven_test", "create_pull_request"].includes(name)
    ),
    validation: {
      readFileStatus: validRead.data.status,
      unsafeReadStatus: unsafeRead.data.status,
      invalidSearchStatus: invalidSearch.data.status,
      createPullRequestStatus: pullRequestValidation.data.status
    }
  };
  const artifactPath = join(artifactDir, "last-run.json");
  await writeFile(artifactPath, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");

  console.log("MCP 工具目录 smoke 通过。");
  console.log(`工具数量: ${catalog.toolCount}`);
  console.log(`证据文件: ${artifactPath}`);
} catch (error) {
  console.error(`MCP 工具目录 smoke 失败: ${error.message}`);
  if (error.details) {
    console.error(error.details);
  }
  process.exitCode = 1;
} finally {
  await cleanup();
}

function startServer(port) {
  const logStream = createWriteStream(join(logDir, "mcp-tool-server.log"), { flags: "a" });
  const serverProcess = spawn(
    "mvn",
    ["-q", "-Dmaven.repo.local=../.m2", "spring-boot:run"],
    {
      cwd: join(repoRoot, "mcp-tool-server"),
      env: {
        ...process.env,
        MCP_TOOL_SERVER_PORT: String(port)
      },
      stdio: ["ignore", "pipe", "pipe"]
    }
  );
  serverProcess.stdout.pipe(logStream, { end: false });
  serverProcess.stderr.pipe(logStream, { end: false });
  serverProcess.on("exit", (code, signal) => {
    logStream.write(`\n[mcp-tool-server exited code=${code} signal=${signal}]\n`);
  });
  return serverProcess;
}

async function apiGet(url) {
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  return readJsonResponse(response, "GET", url);
}

async function apiPost(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(body)
  });
  return readJsonResponse(response, "POST", url);
}

async function readJsonResponse(response, method, url) {
  const text = await response.text();
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    const error = new Error(`${method} ${url} 返回非 JSON，HTTP ${response.status}`);
    error.details = text.slice(0, 1_200);
    throw error;
  }
  if (!response.ok) {
    const error = new Error(`${method} ${url} 请求失败，HTTP ${response.status}`);
    error.details = text.slice(0, 1_200);
    throw error;
  }
  return payload;
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

async function freePort() {
  const server = createServer();
  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  const port = server.address().port;
  await new Promise((resolveClose) => server.close(resolveClose));
  return port;
}

async function cleanup() {
  if (!child || child.exitCode !== null || child.signalCode !== null) {
    return;
  }
  child.kill();
  await new Promise((resolveWait) => child.once("exit", resolveWait));
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}
