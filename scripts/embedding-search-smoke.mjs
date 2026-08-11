import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const backendPort = Number(process.env.REPOPILOT_EMBEDDING_SMOKE_BACKEND_PORT ?? "18082");
const backendUrl = `http://127.0.0.1:${backendPort}`;
const email = process.env.REPOPILOT_EMBEDDING_SMOKE_EMAIL ?? `embedding-search-smoke-${Date.now()}@example.test`;
const password = "password123";
const logDir = process.env.REPOPILOT_EMBEDDING_SMOKE_LOG_DIR ?? join(repoRoot, "target", "embedding-search-smoke", "logs");
const artifactDir = process.env.REPOPILOT_EMBEDDING_SMOKE_ARTIFACT_DIR ?? join(repoRoot, "output", "embedding-search-smoke");
const workspace = process.env.REPOPILOT_EMBEDDING_SMOKE_WORKSPACE ?? join(repoRoot, "target", "embedding-search-smoke", "workspace");
const repositoryUrl = pathToFileURL(join(repoRoot, "examples", "demo-spring-repo")).toString();

await mkdir(logDir, { recursive: true });
await mkdir(artifactDir, { recursive: true });
await rm(workspace, { recursive: true, force: true });
await mkdir(workspace, { recursive: true });

let failEmbeddingRequests = false;
const embeddingServer = createServer(async (request, response) => {
  if (request.method !== "POST" || request.url !== "/v1/embeddings") {
    respond(response, 404, { error: "not found" });
    return;
  }
  if (failEmbeddingRequests) {
    respond(response, 503, { error: "temporary fixture failure" });
    return;
  }
  try {
    const body = JSON.parse(await readBody(request));
    const inputs = Array.isArray(body.input) ? body.input : [];
    respond(response, 200, {
      object: "list",
      model: "repopilot-smoke-embedding",
      data: inputs.map((input, index) => ({
        object: "embedding",
        index,
        embedding: vectorFor(String(input))
      }))
    });
  } catch (error) {
    respond(response, 400, { error: error instanceof Error ? error.message : String(error) });
  }
});
await listen(embeddingServer);
const embeddingPort = embeddingServer.address().port;
const embeddingBaseUrl = `http://127.0.0.1:${embeddingPort}/v1`;

const backendLog = createWriteStream(join(logDir, "backend.log"), { flags: "w" });
const backend = spawn(
  "mvn",
  ["-q", "-Dmaven.repo.local=../.m2", "spring-boot:run"],
  {
    cwd: join(repoRoot, "backend"),
    env: {
      ...process.env,
      BACKEND_PORT: String(backendPort),
      REPOPILOT_WORKSPACE_ROOT: workspace,
      REPOPILOT_EMBEDDING_MODE: "openai-compatible",
      REPOPILOT_EMBEDDING_API_BASE_URL: embeddingBaseUrl,
      REPOPILOT_EMBEDDING_API_KEY: "",
      REPOPILOT_EMBEDDING_MODEL: "repopilot-smoke-embedding",
      REPOPILOT_EMBEDDING_BATCH_SIZE: "16",
      REPOPILOT_EMBEDDING_MINIMUM_SIMILARITY: "0.20"
    },
    stdio: ["ignore", "pipe", "pipe"]
  }
);
backend.stdout.pipe(backendLog);
backend.stderr.pipe(backendLog);

try {
  await waitForHealth(`${backendUrl}/actuator/health`, backend, 120_000);
  const auth = await api("POST", "/api/auth/register", null, {
    email,
    password,
    displayName: "Embedding Search Smoke"
  });
  const token = auth.token;

  const settings = await api("GET", "/api/settings/embedding", token);
  assert(settings.embeddingAvailable === true, "Embedding settings should be ready");
  assert(settings.mode === "openai-compatible", `Unexpected embedding mode: ${settings.mode}`);
  assert(settings.model === "repopilot-smoke-embedding", `Unexpected embedding model: ${settings.model}`);

  const project = await api("POST", "/api/projects", token, {
    repoUrl: repositoryUrl,
    defaultBranch: "main"
  });
  await api("POST", `/api/projects/${project.id}/clone`, token, {});
  const indexResult = await api("POST", `/api/projects/${project.id}/index`, token, {});
  assert(indexResult.embeddingStatus === "INDEXED", `Unexpected index status: ${indexResult.embeddingStatus}`);
  assert(indexResult.embeddingCount > 0, "Index should persist code embeddings");
  assert(indexResult.embeddingDimension === 3, `Unexpected embedding dimension: ${indexResult.embeddingDimension}`);
  assert(indexResult.embeddingCount === indexResult.chunkCount, "Every indexed chunk should have an embedding");

  const semanticSearch = await api(
    "GET",
    `/api/projects/${project.id}/search?query=${encodeURIComponent("查找用户业务逻辑")}&limit=12`,
    token
  );
  assert(semanticSearch.retrievalMode === "VECTOR", `Expected VECTOR mode, got ${semanticSearch.retrievalMode}`);
  assert(semanticSearch.embeddingStatus === "READY", `Unexpected semantic status: ${semanticSearch.embeddingStatus}`);
  assert(
    semanticSearch.results.some((result) => result.filePath.endsWith("UserService.java") && result.matchType === "VECTOR"),
    "Semantic search should find UserService without keyword overlap"
  );

  const hybridSearch = await api(
    "GET",
    `/api/projects/${project.id}/search?query=UserService&limit=12`,
    token
  );
  assert(hybridSearch.retrievalMode === "HYBRID", `Expected HYBRID mode, got ${hybridSearch.retrievalMode}`);
  assert(hybridSearch.results.some((result) => result.matchType === "HYBRID"), "Hybrid search should expose merged results");
  assert(
    hybridSearch.results.every((result) => typeof result.combinedScore === "number"),
    "Hybrid search results should expose combined scores"
  );

  failEmbeddingRequests = true;
  const fallbackSearch = await api(
    "GET",
    `/api/projects/${project.id}/search?query=UserService&limit=5`,
    token
  );
  assert(
    fallbackSearch.retrievalMode === "KEYWORD_FALLBACK",
    `Expected KEYWORD_FALLBACK mode, got ${fallbackSearch.retrievalMode}`
  );
  assert(fallbackSearch.embeddingStatus === "FAILED", `Unexpected fallback status: ${fallbackSearch.embeddingStatus}`);
  assert(fallbackSearch.results.length > 0, "Keyword fallback should preserve matching results");

  const evidence = {
    generatedAt: new Date().toISOString(),
    projectId: project.id,
    settings: {
      mode: settings.mode,
      provider: settings.provider,
      model: settings.model,
      keywordWeight: settings.keywordWeight,
      vectorWeight: settings.vectorWeight,
      minimumSimilarity: settings.minimumSimilarity
    },
    index: {
      chunkCount: indexResult.chunkCount,
      embeddingCount: indexResult.embeddingCount,
      embeddingStatus: indexResult.embeddingStatus,
      embeddingDimension: indexResult.embeddingDimension
    },
    semanticSearch: summarizeSearch(semanticSearch),
    hybridSearch: summarizeSearch(hybridSearch),
    fallbackSearch: summarizeSearch(fallbackSearch)
  };
  await writeFile(join(artifactDir, "last-run.json"), `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  console.log(JSON.stringify(evidence, null, 2));
  console.log(`Embedding search smoke passed. Evidence: ${join(artifactDir, "last-run.json")}`);
} finally {
  backend.kill("SIGTERM");
  await waitForExit(backend, 10_000);
  backendLog.end();
  embeddingServer.close();
}
function vectorFor(input) {
  const normalized = input.toLowerCase();
  if (normalized.includes("userservice") || normalized.includes("查找用户业务逻辑")) {
    return [1.0, 0.0, 0.0];
  }
  if (normalized.includes("usercontroller")) {
    return [0.8, 0.2, 0.0];
  }
  return [0.0, 1.0, 0.0];
}

function summarizeSearch(search) {
  return {
    query: search.query,
    retrievalMode: search.retrievalMode,
    retrievalModeLabel: search.retrievalModeLabel,
    embeddingStatus: search.embeddingStatus,
    resultCount: search.results.length,
    results: search.results.slice(0, 5).map((result) => ({
      chunkId: result.chunkId,
      filePath: result.filePath,
      matchType: result.matchType,
      keywordScore: result.keywordScore,
      vectorScore: result.vectorScore,
      combinedScore: result.combinedScore
    }))
  };
}

async function api(method, path, token, body) {
  const headers = { Accept: "application/json" };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const options = { method, headers };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(`${backendUrl}${path}`, options);
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload?.success !== true) {
    throw new Error(`${method} ${path} failed with ${response.status}: ${JSON.stringify(payload)}`);
  }
  return payload.data;
}

async function waitForHealth(url, process, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (process.exitCode !== null) {
      throw new Error(`Backend exited before becoming healthy with code ${process.exitCode}`);
    }
    try {
      const response = await fetch(url);
      if (response.ok) {
        return;
      }
    } catch {
      // Backend is still starting.
    }
    await delay(500);
  }
  throw new Error(`Timed out waiting for backend at ${url}`);
}

async function waitForExit(process, timeoutMs) {
  if (process.exitCode !== null) {
    return;
  }
  await Promise.race([
    new Promise((resolveExit) => process.once("exit", resolveExit)),
    delay(timeoutMs).then(() => process.kill("SIGKILL"))
  ]);
}

function listen(server) {
  return new Promise((resolveListen, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolveListen);
  });
}

function readBody(request) {
  return new Promise((resolveBody, reject) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => resolveBody(Buffer.concat(chunks).toString("utf8")));
    request.on("error", reject);
  });
}

function respond(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload) });
  response.end(payload);
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function delay(milliseconds) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));
}
