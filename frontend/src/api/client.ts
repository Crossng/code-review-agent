const API_BASE = "/api";

export type ApiResponse<T> = {
  success: boolean;
  data: T;
  code: string | null;
  message: string | null;
  traceId: string | null;
};

export class ApiError extends Error {
  code: string | null;
  status: number;
  traceId: string | null;

  constructor(message: string, status: number, code: string | null, traceId: string | null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.traceId = traceId;
  }
}

export type AuthResponse = {
  token: string;
  expiresInMinutes: number;
  user: {
    id: number;
    email: string;
    displayName: string;
    role: string;
  };
};

export type Project = {
  id: number;
  repoUrl: string;
  repoFullName: string;
  defaultBranch: string;
  localPath: string | null;
  status: string;
  lastIndexedAt: string | null;
  createdAt: string;
};

export type ProjectFilters = {
  status?: string;
  query?: string;
};

export type ProjectFile = {
  path: string;
  type: "DIRECTORY" | "FILE" | string;
  size: number;
};

export type ProjectSymbol = {
  id: number;
  filePath: string;
  symbolType: string;
  name: string;
  qualifiedName: string;
  annotations: string | null;
  startLine: number | null;
  endLine: number | null;
};

export type ControllerApiParameter = {
  name: string;
  source: "PATH" | "QUERY" | "BODY" | "HEADER" | "UNKNOWN" | string;
  type: string;
  required: boolean;
  defaultValue: string | null;
};

export type ControllerServiceCall = {
  receiverName: string;
  serviceType: string;
  methodName: string;
  line: number | null;
  downstreamCalls: ControllerDownstreamCall[];
};

export type ControllerDownstreamCall = {
  receiverName: string;
  componentType: string;
  methodName: string;
  line: number | null;
};

export type ControllerRiskHint = {
  severity: "HIGH" | "MEDIUM" | "LOW" | string;
  code: string;
  message: string;
  details: string[];
};

export type ControllerApi = {
  filePath: string;
  controllerName: string;
  qualifiedControllerName: string;
  methodName: string;
  httpMethod: string;
  path: string;
  requestType: string | null;
  parameters: ControllerApiParameter[];
  serviceCalls: ControllerServiceCall[];
  responseType: string;
  securityAnnotations: string[];
  riskScore: number;
  riskLevel: "HIGH" | "MEDIUM" | "LOW" | "NONE" | string;
  riskHints: ControllerRiskHint[];
  startLine: number | null;
  endLine: number | null;
};

export type ControllerApiRiskSummary = {
  total: number;
  byLevel: Record<string, number>;
};

export type ControllerApiListResponse = {
  items: ControllerApi[];
  filteredCount: number;
  riskSummary: ControllerApiRiskSummary;
  riskCodes: string[];
  filters: {
    riskLevel: string | null;
    riskCode: string | null;
  };
};

export type ControllerApiDocsResponse = {
  projectId: number;
  repoFullName: string;
  generatedAt: string;
  routeCount: number;
  filteredCount: number;
  filters: {
    riskLevel: string | null;
    riskCode: string | null;
  };
  markdown: string;
};

export type ControllerApiDocsSnapshotSummary = {
  id: number;
  projectId: number;
  generatedByUserId: number;
  repoFullName: string;
  generatedAt: string;
  routeCount: number;
  filteredCount: number;
  filters: {
    riskLevel: string | null;
    riskCode: string | null;
  };
  createdAt: string;
};

export type ControllerApiDocsSnapshot = ControllerApiDocsSnapshotSummary & {
  markdown: string;
};

export type ControllerApiDocsSnapshotClearResponse = {
  deletedCount: number;
};

export type ControllerApiFilters = {
  riskLevel?: string;
  riskCode?: string;
};

export type CodeSearchResult = {
  chunkId: number;
  filePath: string;
  chunkType: string;
  symbolType: string | null;
  symbolName: string | null;
  qualifiedName: string | null;
  startLine: number | null;
  endLine: number | null;
  summary: string | null;
  preview: string;
};

export type CodeSearchResponse = {
  query: string;
  limit: number;
  results: CodeSearchResult[];
};

export type AgentTask = {
  id: number;
  projectId: number;
  taskType: string;
  title: string;
  description: string;
  status: string;
  currentRunId: number | null;
  createdAt: string;
};

export type TaskFilters = {
  projectId?: number | "";
  status?: string;
  taskType?: string;
  query?: string;
};

export type AgentRun = {
  id: number;
  taskId: number;
  status: string;
  startedAt: string;
  finishedAt: string | null;
  errorMessage: string | null;
};

export type AgentStep = {
  id: number;
  stepName: string;
  status: string;
  inputJson: string | null;
  outputJson: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
};

export type AgentRunReportSection = {
  key: string;
  title: string;
  stepName: string;
  status: string;
  finishedAt: string | null;
  summary: string;
  facts: string[];
  highlights: string[];
};

export type AgentRunReport = {
  taskId: number;
  runId: number;
  projectId: number;
  projectName: string;
  taskType: string;
  taskTitle: string;
  taskStatus: string;
  runStatus: string;
  startedAt: string;
  finishedAt: string | null;
  generatedAt: string;
  sections: AgentRunReportSection[];
  markdown: string;
};

export type AgentRunReportSnapshotSummary = {
  id: number;
  taskId: number;
  runId: number | null;
  projectId: number;
  generatedByUserId: number;
  projectName: string;
  taskTitle: string;
  taskType: string;
  taskStatus: string;
  runStatus: string;
  runStartedAt: string;
  runFinishedAt: string | null;
  reportGeneratedAt: string;
  sectionCount: number;
  createdAt: string;
};

export type AgentRunReportSnapshot = AgentRunReportSnapshotSummary & {
  markdown: string;
};

export type AgentTaskEvent = {
  eventType: string;
  taskId: number;
  taskStatus: string;
  runId: number | null;
  runStatus: string | null;
  stepId: number | null;
  stepName: string | null;
  stepStatus: string | null;
  message: string | null;
  createdAt: string;
};

export type PatchChangedFile = {
  path: string;
  oldPath: string | null;
  changeType: "ADDED" | "MODIFIED" | "DELETED" | "RENAMED" | string;
  addedLines: number;
  deletedLines: number;
};

export type PatchRecord = {
  id: number;
  agentTaskId: number;
  agentRunId: number;
  baseBranch: string;
  targetBranch: string;
  diffContent: string;
  summary: string | null;
  generationMode: string;
  generationProvider: string;
  generationModel: string | null;
  changedFiles: PatchChangedFile[];
  status: string;
  createdAt: string;
};

export type ApprovalRecord = {
  id: number;
  agentTaskId: number;
  patchId: number;
  userId: number;
  action: "APPROVE" | "REJECT";
  comment: string | null;
  patchStatus: string;
  taskStatus: string;
  createdAt: string;
};

export type TestRun = {
  id: number;
  agentRunId: number;
  patchId: number;
  command: string;
  exitCode: number;
  durationMs: number;
  logExcerpt: string | null;
  status: string;
  createdAt: string;
};

export type RetryAuditSummary = {
  attemptCount: number;
  recovered: boolean;
  firstFailureType: string | null;
  firstFailureMessage: string | null;
};

export type ToolCallLog = {
  id: number;
  agentRunId: number;
  toolName: string;
  inputJson: string | null;
  outputJson: string | null;
  retryAudit: RetryAuditSummary | null;
  status: string;
  durationMs: number;
  errorMessage: string | null;
  startedAt: string;
  finishedAt: string;
};

export type ModelCallLog = {
  id: number;
  agentRunId: number;
  stepName: string;
  modelProvider: string;
  modelName: string;
  promptJson: string | null;
  responseJson: string | null;
  retryAudit: RetryAuditSummary | null;
  status: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  durationMs: number;
  errorMessage: string | null;
  startedAt: string;
  finishedAt: string;
};

export type PullRequestRecord = {
  id: number;
  agentTaskId: number;
  patchId: number;
  provider: string;
  prNumber: number | null;
  url: string | null;
  title: string;
  body: string | null;
  baseBranch: string | null;
  targetBranch: string | null;
  commitSha: string | null;
  commitMessage: string | null;
  status: string;
  remotePushedAt: string | null;
  openedAt: string | null;
  errorMessage: string | null;
  taskStatus: string;
  createdAt: string;
  updatedAt: string;
};

export type PullRequestPreflightCheck = {
  code: string;
  label: string;
  status: "PASS" | "PENDING" | "BLOCKED" | "WARN" | string;
  message: string;
};

export type PullRequestPreflight = {
  taskId: number;
  taskStatus: string;
  canPrepare: boolean;
  publishMode: string;
  localDraftReady: boolean;
  remotePublishingEnabled: boolean;
  remotePublishingWillRun: boolean;
  remoteReady: boolean;
  repositoryEligible: boolean;
  tokenConfigured: boolean;
  latestPatchStatus: string | null;
  latestTestStatus: string | null;
  existingPullRequestStatus: string | null;
  checks: PullRequestPreflightCheck[];
  blockers: string[];
};

export type CoderSettings = {
  mode: string;
  provider: string;
  enabled: boolean;
  ready: boolean;
  model: string | null;
  apiBaseUrl: string;
  apiKeyConfigured: boolean;
  fixtureConfigured: boolean;
  timeoutSeconds: number;
  maxCompletionTokens: number;
  instructionRole: string;
  organizationConfigured: boolean;
  projectConfigured: boolean;
  missingRequirements: string[];
  supportedModes: string[];
};

export type GitHubSettings = {
  provider: string;
  enabled: boolean;
  ready: boolean;
  publishMode: string;
  apiBaseUrl: string;
  tokenConfigured: boolean;
  remotePublishingEnabled: boolean;
  localDraftMode: boolean;
  missingRequirements: string[];
};

export type DashboardSummary = {
  totalProjects: number;
  readyProjects: number;
  failedProjects: number;
  totalTasks: number;
  createdTasks: number;
  runningTasks: number;
  waitingApprovalTasks: number;
  doneTasks: number;
  failedTasks: number;
  cancelledTasks: number;
  totalPullRequests: number;
  draftPullRequests: number;
  openPullRequests: number;
  failedPullRequests: number;
};

export type DashboardRunTrendPoint = {
  date: string;
  totalRuns: number;
  successRuns: number;
  failedRuns: number;
  cancelledRuns: number;
  runningRuns: number;
  averageDurationSeconds: number;
};

export type DashboardRunMetrics = {
  days: number;
  from: string;
  to: string;
  totalRuns: number;
  successRuns: number;
  failedRuns: number;
  cancelledRuns: number;
  runningRuns: number;
  completedRuns: number;
  averageDurationSeconds: number;
  successRatePercent: number;
  trend: DashboardRunTrendPoint[];
};

export type DashboardActivityItem = {
  stepId: number;
  runId: number;
  taskId: number;
  projectId: number;
  projectName: string;
  taskTitle: string;
  taskStatus: string;
  activityType: string;
  label: string;
  status: string;
  message: string;
  occurredAt: string;
};

export type SandboxSettingsCheck = {
  code: string;
  label: string;
  status: "PASS" | "WARN" | "BLOCKED" | string;
  message: string;
};

export type SandboxSettings = {
  ready: boolean;
  dockerImage: string | null;
  dockerImageConfigured: boolean;
  timeoutSeconds: number;
  workspaceRoot: string;
  workspaceRootExists: boolean;
  workspaceRootWritable: boolean;
  mavenCachePath: string;
  mavenCacheExists: boolean;
  mavenCacheWritable: boolean;
  dockerCheckEnabled: boolean;
  dockerAvailable: boolean;
  dockerVersion: string | null;
  missingRequirements: string[];
  checks: SandboxSettingsCheck[];
};

export type CloneProjectResponse = {
  projectId: number;
  status: string;
  localPath: string;
  branch: string;
  commitSha: string;
  fileCount: number;
  javaFileCount: number;
  message: string;
};

export type ProjectIndexResponse = {
  projectId: number;
  snapshotId: number;
  fileCount: number;
  javaFileCount: number;
  symbolCount: number;
  chunkCount: number;
  indexedAt: string;
  message: string;
};

export async function getHealth(): Promise<Response> {
  return fetch("/actuator/health");
}

export async function login(email: string, password: string): Promise<AuthResponse> {
  return postJson<AuthResponse, { email: string; password: string }>("/auth/login", { email, password });
}

export async function register(email: string, password: string, displayName: string): Promise<AuthResponse> {
  return postJson<AuthResponse, { email: string; password: string; displayName: string }>("/auth/register", {
    email,
    password,
    displayName
  });
}

export function listProjects(token: string, filters: ProjectFilters = {}): Promise<Project[]> {
  const query = new URLSearchParams();
  if (filters.status && filters.status !== "ALL") {
    query.set("status", filters.status);
  }
  if (filters.query?.trim()) {
    query.set("query", filters.query.trim());
  }
  const suffix = query.size > 0 ? `?${query}` : "";
  return getJson<Project[]>(`/projects${suffix}`, token);
}

export function createProject(
  token: string,
  body: { repoUrl: string; defaultBranch: string; accessToken?: string }
): Promise<Project> {
  return postJson<Project, typeof body>("/projects", body, token);
}

export function cloneProject(token: string, projectId: number): Promise<CloneProjectResponse> {
  return postJson<CloneProjectResponse, Record<string, never>>(`/projects/${projectId}/clone`, {}, token);
}

export function indexProject(token: string, projectId: number): Promise<ProjectIndexResponse> {
  return postJson<ProjectIndexResponse, Record<string, never>>(`/projects/${projectId}/index`, {}, token);
}

export function listProjectFiles(token: string, projectId: number, maxDepth = 10): Promise<ProjectFile[]> {
  const query = new URLSearchParams({ maxDepth: String(maxDepth) });
  return getJson<ProjectFile[]>(`/projects/${projectId}/files?${query}`, token);
}

export function listProjectSymbols(token: string, projectId: number, type?: string): Promise<ProjectSymbol[]> {
  const query = new URLSearchParams();
  if (type) {
    query.set("type", type);
  }
  const suffix = query.size > 0 ? `?${query}` : "";
  return getJson<ProjectSymbol[]>(`/projects/${projectId}/symbols${suffix}`, token);
}

export function listControllerApis(
  token: string,
  projectId: number,
  filters: ControllerApiFilters = {}
): Promise<ControllerApiListResponse> {
  const query = new URLSearchParams();
  if (filters.riskLevel && filters.riskLevel !== "ALL") {
    query.set("riskLevel", filters.riskLevel);
  }
  if (filters.riskCode && filters.riskCode !== "ALL") {
    query.set("riskCode", filters.riskCode);
  }
  const suffix = query.size > 0 ? `?${query}` : "";
  return getJson<ControllerApiListResponse>(`/projects/${projectId}/controller-apis${suffix}`, token);
}

export function getControllerApiDocs(
  token: string,
  projectId: number,
  filters: ControllerApiFilters = {},
  limit = 12
): Promise<ControllerApiDocsResponse> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (filters.riskLevel && filters.riskLevel !== "ALL") {
    query.set("riskLevel", filters.riskLevel);
  }
  if (filters.riskCode && filters.riskCode !== "ALL") {
    query.set("riskCode", filters.riskCode);
  }
  return getJson<ControllerApiDocsResponse>(`/projects/${projectId}/controller-apis/docs?${query}`, token);
}

export function listControllerApiDocsSnapshots(
  token: string,
  projectId: number,
  limit = 5
): Promise<ControllerApiDocsSnapshotSummary[]> {
  const query = new URLSearchParams({ limit: String(limit) });
  return getJson<ControllerApiDocsSnapshotSummary[]>(
    `/projects/${projectId}/controller-apis/docs/snapshots?${query}`,
    token
  );
}

export function createControllerApiDocsSnapshot(
  token: string,
  projectId: number,
  filters: ControllerApiFilters = {},
  limit = 12
): Promise<ControllerApiDocsSnapshot> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (filters.riskLevel && filters.riskLevel !== "ALL") {
    query.set("riskLevel", filters.riskLevel);
  }
  if (filters.riskCode && filters.riskCode !== "ALL") {
    query.set("riskCode", filters.riskCode);
  }
  return postJson<ControllerApiDocsSnapshot, Record<string, never>>(
    `/projects/${projectId}/controller-apis/docs/snapshots?${query}`,
    {},
    token
  );
}

export function getControllerApiDocsSnapshot(
  token: string,
  projectId: number,
  snapshotId: number
): Promise<ControllerApiDocsSnapshot> {
  return getJson<ControllerApiDocsSnapshot>(
    `/projects/${projectId}/controller-apis/docs/snapshots/${snapshotId}`,
    token
  );
}

export function deleteControllerApiDocsSnapshot(
  token: string,
  projectId: number,
  snapshotId: number
): Promise<void> {
  return deleteJson(`/projects/${projectId}/controller-apis/docs/snapshots/${snapshotId}`, token);
}

export function clearControllerApiDocsSnapshots(
  token: string,
  projectId: number
): Promise<ControllerApiDocsSnapshotClearResponse> {
  return deleteJson<ControllerApiDocsSnapshotClearResponse>(
    `/projects/${projectId}/controller-apis/docs/snapshots`,
    token
  );
}

export function searchProjectCode(
  token: string,
  projectId: number,
  query: string,
  limit = 8
): Promise<CodeSearchResponse> {
  const params = new URLSearchParams({ query, limit: String(limit) });
  return getJson<CodeSearchResponse>(`/projects/${projectId}/search?${params}`, token);
}

export function listTasks(token: string, filters: TaskFilters = {}): Promise<AgentTask[]> {
  const query = new URLSearchParams();
  if (filters.projectId !== undefined && filters.projectId !== "") {
    query.set("projectId", String(filters.projectId));
  }
  if (filters.status && filters.status !== "ALL") {
    query.set("status", filters.status);
  }
  if (filters.taskType && filters.taskType !== "ALL") {
    query.set("taskType", filters.taskType);
  }
  if (filters.query?.trim()) {
    query.set("query", filters.query.trim());
  }
  const suffix = query.size > 0 ? `?${query}` : "";
  return getJson<AgentTask[]>(`/agent/tasks${suffix}`, token);
}

export function getTask(token: string, taskId: number): Promise<AgentTask> {
  return getJson<AgentTask>(`/agent/tasks/${taskId}`, token);
}

export function createTask(
  token: string,
  body: { projectId: number; taskType: string; title: string; description: string }
): Promise<AgentTask> {
  return postJson<AgentTask, typeof body>("/agent/tasks", body, token);
}

export function runTask(token: string, taskId: number): Promise<AgentRun> {
  return postJson<AgentRun, Record<string, never>>(`/agent/tasks/${taskId}/run`, {}, token);
}

export function cancelTask(token: string, taskId: number): Promise<AgentTask> {
  return postJson<AgentTask, Record<string, never>>(`/agent/tasks/${taskId}/cancel`, {}, token);
}

export function listSteps(token: string, taskId: number): Promise<AgentStep[]> {
  return getJson<AgentStep[]>(`/agent/tasks/${taskId}/steps`, token);
}

export function getAgentRunReport(token: string, taskId: number): Promise<AgentRunReport> {
  return getJson<AgentRunReport>(`/agent/tasks/${taskId}/run-report`, token);
}

export function listAgentRunReportSnapshots(
  token: string,
  taskId: number,
  limit = 5
): Promise<AgentRunReportSnapshotSummary[]> {
  return getJson<AgentRunReportSnapshotSummary[]>(
    `/agent/tasks/${taskId}/run-report/snapshots?limit=${encodeURIComponent(String(limit))}`,
    token
  );
}

export function createAgentRunReportSnapshot(token: string, taskId: number): Promise<AgentRunReportSnapshot> {
  return postJson<AgentRunReportSnapshot, Record<string, never>>(
    `/agent/tasks/${taskId}/run-report/snapshots`,
    {},
    token
  );
}

export function getAgentRunReportSnapshot(
  token: string,
  taskId: number,
  snapshotId: number
): Promise<AgentRunReportSnapshot> {
  return getJson<AgentRunReportSnapshot>(
    `/agent/tasks/${taskId}/run-report/snapshots/${snapshotId}`,
    token
  );
}

export async function streamTaskEvents(
  token: string,
  taskId: number,
  onEvent: (event: AgentTaskEvent) => void,
  signal: AbortSignal
): Promise<void> {
  const response = await fetch(`${API_BASE}/agent/tasks/${taskId}/stream`, {
    headers: {
      Accept: "text/event-stream",
      Authorization: `Bearer ${token}`
    },
    signal
  });
  if (!response.ok) {
    throw new ApiError("Task stream request failed", response.status, null, null);
  }
  if (!response.body) {
    throw new ApiError("Task stream is not readable", response.status, null, null);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const nextBuffer = dispatchSseBuffer(buffer, onEvent);
      buffer = nextBuffer;
    }
    buffer += decoder.decode();
    dispatchSseBuffer(buffer, onEvent, true);
  } finally {
    reader.releaseLock();
  }
}

export function getLatestPatch(token: string, taskId: number): Promise<PatchRecord> {
  return getJson<PatchRecord>(`/tasks/${taskId}/patches/latest`, token);
}

export function regeneratePatch(token: string, taskId: number): Promise<PatchRecord> {
  return postJson<PatchRecord, Record<string, never>>(`/tasks/${taskId}/patches/regenerate`, {}, token);
}

export function approvePatch(token: string, taskId: number, patchId: number, comment: string): Promise<ApprovalRecord> {
  return postJson<ApprovalRecord, { patchId: number; comment: string }>(
    `/tasks/${taskId}/approval/approve`,
    { patchId, comment },
    token
  );
}

export function rejectPatch(token: string, taskId: number, patchId: number, comment: string): Promise<ApprovalRecord> {
  return postJson<ApprovalRecord, { patchId: number; comment: string }>(
    `/tasks/${taskId}/approval/reject`,
    { patchId, comment },
    token
  );
}

export function listApprovals(token: string, taskId: number): Promise<ApprovalRecord[]> {
  return getJson<ApprovalRecord[]>(`/tasks/${taskId}/approval`, token);
}

export function listTestRuns(token: string, runId: number): Promise<TestRun[]> {
  return getJson<TestRun[]>(`/agent/runs/${runId}/test-runs`, token);
}

export function listToolCalls(token: string, runId: number): Promise<ToolCallLog[]> {
  return getJson<ToolCallLog[]>(`/agent/runs/${runId}/tool-calls`, token);
}

export function listModelCalls(token: string, runId: number): Promise<ModelCallLog[]> {
  return getJson<ModelCallLog[]>(`/agent/runs/${runId}/model-calls`, token);
}

export function getLatestPullRequest(token: string, taskId: number): Promise<PullRequestRecord> {
  return getJson<PullRequestRecord>(`/tasks/${taskId}/pull-request`, token);
}

export function getPullRequestPreflight(token: string, taskId: number): Promise<PullRequestPreflight> {
  return getJson<PullRequestPreflight>(`/tasks/${taskId}/pull-request/preflight`, token);
}

export function preparePullRequest(token: string, taskId: number): Promise<PullRequestRecord> {
  return postJson<PullRequestRecord, Record<string, never>>(`/tasks/${taskId}/pull-request`, {}, token);
}

export function getCoderSettings(token: string): Promise<CoderSettings> {
  return getJson<CoderSettings>("/settings/coder", token);
}

export function getGitHubSettings(token: string): Promise<GitHubSettings> {
  return getJson<GitHubSettings>("/settings/github", token);
}

export function getSandboxSettings(token: string): Promise<SandboxSettings> {
  return getJson<SandboxSettings>("/settings/sandbox", token);
}

export function getDashboardSummary(token: string): Promise<DashboardSummary> {
  return getJson<DashboardSummary>("/dashboard/summary", token);
}

export function getDashboardRunMetrics(token: string, days = 7): Promise<DashboardRunMetrics> {
  const query = new URLSearchParams({ days: String(days) });
  return getJson<DashboardRunMetrics>(`/dashboard/run-metrics?${query.toString()}`, token);
}

export function getDashboardActivity(token: string, limit = 10): Promise<DashboardActivityItem[]> {
  const query = new URLSearchParams({ limit: String(limit) });
  return getJson<DashboardActivityItem[]>(`/dashboard/activity?${query.toString()}`, token);
}

export async function getJson<TResponse>(path: string, token?: string): Promise<TResponse> {
  return request<TResponse>(path, { method: "GET" }, token);
}

export async function postJson<TResponse, TBody>(
  path: string,
  body: TBody,
  token?: string
): Promise<TResponse> {
  return request<TResponse>(
    path,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    },
    token
  );
}

export async function deleteJson<TResponse = void>(path: string, token?: string): Promise<TResponse> {
  return request<TResponse>(path, { method: "DELETE" }, token);
}

async function request<TResponse>(path: string, init: RequestInit, token?: string): Promise<TResponse> {
  const headers = new Headers(init.headers);
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE}${path}`, { ...init, headers });
  const payload = (await response.json()) as ApiResponse<TResponse>;
  if (!response.ok || !payload.success) {
    throw new ApiError(
      payload.message ?? "Request failed",
      response.status,
      payload.code,
      payload.traceId
    );
  }
  return payload.data;
}

function dispatchSseBuffer(
  buffer: string,
  onEvent: (event: AgentTaskEvent) => void,
  flush = false
): string {
  const normalized = buffer.replace(/\r\n/g, "\n");
  const frames = normalized.split("\n\n");
  const completeFrameCount = flush ? frames.length : frames.length - 1;
  for (let index = 0; index < completeFrameCount; index += 1) {
    dispatchSseFrame(frames[index], onEvent);
  }
  return flush ? "" : frames[frames.length - 1];
}

function dispatchSseFrame(frame: string, onEvent: (event: AgentTaskEvent) => void) {
  const dataLines = frame
    .split("\n")
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice("data:".length).trimStart());
  if (dataLines.length === 0) {
    return;
  }
  onEvent(JSON.parse(dataLines.join("\n")) as AgentTaskEvent);
}
