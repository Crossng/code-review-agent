import { FormEvent, useEffect, useId, useMemo, useState } from "react";

import {
  AgentStep,
  AgentTask,
  AgentRunReport,
  AgentRunReportSnapshot,
  AgentRunReportSnapshotSummary,
  ApiError,
  ApprovalRecord,
  CodeSearchResponse,
  CoderSettings,
  ControllerApi,
  ControllerApiDocsResponse,
  ControllerApiDocsSnapshot,
  ControllerApiDocsSnapshotClearResponse,
  ControllerApiDocsSnapshotSummary,
  ControllerApiRiskSummary,
  DashboardActivityItem,
  DashboardRunMetrics,
  DashboardSummary,
  GitHubSettings,
  ModelCallLog,
  PatchChangedFile,
  PatchRecord,
  Project,
  ProjectFilters,
  ProjectFile,
  ProjectSymbol,
  PullRequestPreflight,
  PullRequestRecord,
  SandboxSettings,
  TaskFilters,
  TestRun,
  ToolCallLog,
  approvePatch,
  cancelTask,
  clearControllerApiDocsSnapshots,
  cloneProject,
  createAgentRunReportSnapshot,
  createControllerApiDocsSnapshot,
  createProject,
  createTask,
  deleteControllerApiDocsSnapshot,
  getLatestPatch,
  getLatestPullRequest,
  getAgentRunReport,
  getAgentRunReportSnapshot,
  getCoderSettings,
  getControllerApiDocs,
  getControllerApiDocsSnapshot,
  getDashboardActivity,
  getDashboardRunMetrics,
  getDashboardSummary,
  getGitHubSettings,
  getPullRequestPreflight,
  getSandboxSettings,
  getTask,
  indexProject,
  listApprovals,
  listAgentRunReportSnapshots,
  listControllerApis,
  listControllerApiDocsSnapshots,
  listModelCalls,
  listProjectFiles,
  listProjects,
  listProjectSymbols,
  listSteps,
  listTasks,
  listTestRuns,
  listToolCalls,
  login,
  preparePullRequest,
  regeneratePatch,
  register,
  rejectPatch,
  runTask,
  searchProjectCode,
  streamTaskEvents
} from "./api/client";

type TaskDetails = {
  task: AgentTask | null;
  steps: AgentStep[];
  runReport: AgentRunReport | null;
  runReportSnapshots: AgentRunReportSnapshotSummary[];
  patch: PatchRecord | null;
  approvals: ApprovalRecord[];
  testRuns: TestRun[];
  modelCalls: ModelCallLog[];
  toolCalls: ToolCallLog[];
  pullRequest: PullRequestRecord | null;
  pullRequestPreflight: PullRequestPreflight | null;
};

type PatchRiskFinding = {
  severity: string;
  code: string;
  message: string;
  filePath: string | null;
};

type PatchRiskReview = {
  riskLevel: string;
  summary: string;
  findings: PatchRiskFinding[];
};

type JsonObject = Record<string, unknown>;

type AgentEvidenceItem = {
  key: string;
  label: string;
  stepName: string;
  status: string;
  finishedAt: string | null;
  summary: string;
  meta: string[];
  highlights: string[];
};

type TaskStreamState = "idle" | "connecting" | "live" | "ended" | "fallback";

const emptyDetails: TaskDetails = {
  task: null,
  steps: [],
  runReport: null,
  runReportSnapshots: [],
  patch: null,
  approvals: [],
  testRuns: [],
  modelCalls: [],
  toolCalls: [],
  pullRequest: null,
  pullRequestPreflight: null
};

type ProjectInsight = {
  files: ProjectFile[];
  symbols: ProjectSymbol[];
  controllerApis: ControllerApi[];
  controllerApiFilteredCount: number;
  controllerApiRiskSummary: ControllerApiRiskSummary;
  controllerApiRiskCodes: string[];
  controllerApiDocSnapshots: ControllerApiDocsSnapshotSummary[];
  search: CodeSearchResponse | null;
};

const emptyProjectInsight: ProjectInsight = {
  files: [],
  symbols: [],
  controllerApis: [],
  controllerApiFilteredCount: 0,
  controllerApiRiskSummary: { total: 0, byLevel: {} },
  controllerApiRiskCodes: [],
  controllerApiDocSnapshots: [],
  search: null
};

const terminalStatuses = new Set(["DONE", "FAILED_TEST", "FAILED_PR_CREATION", "CANCELLED"]);
const regeneratableStatuses = new Set(["WAITING_HUMAN_APPROVAL", "FAILED_TEST", "FAILED_PATCH_GENERATION", "CANCELLED"]);
const runningStatuses = new Set([
  "REPO_INDEXING",
  "PLANNING",
  "RETRIEVING_CONTEXT",
  "GENERATING_PATCH",
  "APPLYING_PATCH_IN_SANDBOX",
  "RUNNING_TESTS",
  "REPAIRING",
  "REVIEWING_PATCH"
]);
const defaultTaskFilters: TaskFilters = {
  projectId: "",
  status: "ALL",
  taskType: "ALL",
  query: ""
};
const defaultProjectFilters: ProjectFilters = {
  status: "ALL",
  query: ""
};
const defaultRunMetricsDays = 7;
const runMetricsDaysOptions = [7, 14, 30];
const defaultActivityLimit = 10;
const activityLimitOptions = [10, 25, 50];
const projectStatusOptions = ["CREATED", "CLONING", "READY", "FAILED"];
const projectStatusSet = new Set(["ALL", ...projectStatusOptions]);
const taskStatusOptions = [
  "CREATED",
  "REPO_INDEXING",
  "PLANNING",
  "RETRIEVING_CONTEXT",
  "GENERATING_PATCH",
  "APPLYING_PATCH_IN_SANDBOX",
  "RUNNING_TESTS",
  "REPAIRING",
  "REVIEWING_PATCH",
  "WAITING_HUMAN_APPROVAL",
  "CREATING_PULL_REQUEST",
  "DONE",
  "FAILED_REPO_CLONE",
  "FAILED_INDEXING",
  "FAILED_CONTEXT_RETRIEVAL",
  "FAILED_PATCH_GENERATION",
  "FAILED_TEST",
  "FAILED_PR_CREATION",
  "CANCELLED"
];
const taskStatusSet = new Set(["ALL", ...taskStatusOptions]);
const taskTypeOptions = ["FEATURE", "BUGFIX", "REVIEW", "DOC"];
const taskTypeSet = new Set(["ALL", ...taskTypeOptions]);
const projectFilterStatusParam = "projectStatus";
const projectFilterQueryParam = "projectQuery";
const projectSelectionParam = "projectId";
const taskFilterProjectParam = "taskProjectId";
const taskFilterStatusParam = "taskStatus";
const taskFilterTypeParam = "taskType";
const taskFilterQueryParam = "taskQuery";
const taskSelectionParam = "taskId";
const runMetricsDaysParam = "runMetricsDays";
const activityLimitParam = "activityLimit";
const controllerRiskFilterLevelParam = "controllerRiskLevel";
const controllerRiskFilterCodeParam = "controllerRiskCode";
const controllerApiAnchorPrefix = "controller-api-";
const controllerRiskLevels = ["ALL", "HIGH", "MEDIUM", "LOW", "NONE"];
const controllerRiskLevelSet = new Set(controllerRiskLevels);

type ControllerApiRiskFilters = {
  riskLevel: string;
  riskCode: string;
};

function positiveIntegerParam(params: URLSearchParams, name: string): number | "" {
  const requestedValue = params.get(name);
  if (requestedValue === null || requestedValue.trim() === "") {
    return "";
  }
  const value = Number(requestedValue);
  return Number.isInteger(value) && value > 0 ? value : "";
}

function supportedRunMetricsDays(value: string | number | null | undefined) {
  const days = Number(value);
  return Number.isInteger(days) && runMetricsDaysOptions.includes(days)
    ? days
    : defaultRunMetricsDays;
}

function supportedActivityLimit(value: string | number | null | undefined) {
  const limit = Number(value);
  return Number.isInteger(limit) && activityLimitOptions.includes(limit)
    ? limit
    : defaultActivityLimit;
}

function initialProjectFilters(): ProjectFilters {
  if (typeof window === "undefined") {
    return defaultProjectFilters;
  }
  const params = new URLSearchParams(window.location.search);
  const requestedStatus = params.get(projectFilterStatusParam) ?? "ALL";
  const requestedQuery = params.get(projectFilterQueryParam)?.trim() ?? "";
  return {
    status: projectStatusSet.has(requestedStatus) ? requestedStatus : "ALL",
    query: requestedQuery
  };
}

function initialTaskFilters(): TaskFilters {
  if (typeof window === "undefined") {
    return defaultTaskFilters;
  }
  const params = new URLSearchParams(window.location.search);
  const requestedStatus = params.get(taskFilterStatusParam) ?? "ALL";
  const requestedType = params.get(taskFilterTypeParam) ?? "ALL";
  const requestedQuery = params.get(taskFilterQueryParam)?.trim() ?? "";
  return {
    projectId: positiveIntegerParam(params, taskFilterProjectParam),
    status: taskStatusSet.has(requestedStatus) ? requestedStatus : "ALL",
    taskType: taskTypeSet.has(requestedType) ? requestedType : "ALL",
    query: requestedQuery
  };
}

function initialRunMetricsDays() {
  if (typeof window === "undefined") {
    return defaultRunMetricsDays;
  }
  return supportedRunMetricsDays(new URLSearchParams(window.location.search).get(runMetricsDaysParam));
}

function initialActivityLimit() {
  if (typeof window === "undefined") {
    return defaultActivityLimit;
  }
  return supportedActivityLimit(new URLSearchParams(window.location.search).get(activityLimitParam));
}

function initialSelectedProjectId(): number | "" {
  if (typeof window === "undefined") {
    return "";
  }
  const requestedProjectId = window.location.search
    ? new URLSearchParams(window.location.search).get(projectSelectionParam)
    : null;
  if (requestedProjectId === null || requestedProjectId.trim() === "") {
    return "";
  }
  const projectId = Number(requestedProjectId);
  return Number.isInteger(projectId) && projectId > 0 ? projectId : "";
}

function initialSelectedTaskId(): number | null {
  if (typeof window === "undefined") {
    return null;
  }
  const taskId = positiveIntegerParam(new URLSearchParams(window.location.search), taskSelectionParam);
  return taskId === "" ? null : taskId;
}

function initialControllerApiRiskFilters(): ControllerApiRiskFilters {
  if (typeof window === "undefined") {
    return { riskLevel: "ALL", riskCode: "ALL" };
  }
  const params = new URLSearchParams(window.location.search);
  const requestedRiskLevel = params.get(controllerRiskFilterLevelParam) ?? "ALL";
  const requestedRiskCode = params.get(controllerRiskFilterCodeParam)?.trim() || "ALL";
  return {
    riskLevel: controllerRiskLevelSet.has(requestedRiskLevel) ? requestedRiskLevel : "ALL",
    riskCode: requestedRiskCode
  };
}

function controllerApiAnchor(api: ControllerApi) {
  return `${controllerApiAnchorPrefix}${slugify([
    api.httpMethod,
    api.path,
    api.qualifiedControllerName,
    api.methodName
  ].join(" "))}`;
}

function slugify(value: string) {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "") || "route";
}

function controllerApiRiskFilterUrl(riskLevel: string, riskCode: string, anchorId?: string) {
  if (typeof window === "undefined") {
    return null;
  }
  const url = new URL(window.location.href);
  if (riskLevel === "ALL") {
    url.searchParams.delete(controllerRiskFilterLevelParam);
  } else {
    url.searchParams.set(controllerRiskFilterLevelParam, riskLevel);
  }
  if (riskCode === "ALL") {
    url.searchParams.delete(controllerRiskFilterCodeParam);
  } else {
    url.searchParams.set(controllerRiskFilterCodeParam, riskCode);
  }
  if (anchorId !== undefined) {
    url.hash = anchorId;
  }
  return url;
}

function projectViewUrl(filters: ProjectFilters, selectedProjectId: number | "") {
  if (typeof window === "undefined") {
    return null;
  }
  const url = new URL(window.location.href);
  const status = filters.status ?? "ALL";
  const query = filters.query?.trim() ?? "";
  if (status === "ALL") {
    url.searchParams.delete(projectFilterStatusParam);
  } else {
    url.searchParams.set(projectFilterStatusParam, status);
  }
  if (query === "") {
    url.searchParams.delete(projectFilterQueryParam);
  } else {
    url.searchParams.set(projectFilterQueryParam, query);
  }
  if (selectedProjectId === "") {
    url.searchParams.delete(projectSelectionParam);
  } else {
    url.searchParams.set(projectSelectionParam, String(selectedProjectId));
  }
  return url;
}

function taskViewUrl(filters: TaskFilters, selectedTaskId: number | null) {
  if (typeof window === "undefined") {
    return null;
  }
  const url = new URL(window.location.href);
  const projectId = filters.projectId ?? "";
  const status = filters.status ?? "ALL";
  const taskType = filters.taskType ?? "ALL";
  const query = filters.query?.trim() ?? "";
  if (projectId === "") {
    url.searchParams.delete(taskFilterProjectParam);
  } else {
    url.searchParams.set(taskFilterProjectParam, String(projectId));
  }
  if (status === "ALL") {
    url.searchParams.delete(taskFilterStatusParam);
  } else {
    url.searchParams.set(taskFilterStatusParam, status);
  }
  if (taskType === "ALL") {
    url.searchParams.delete(taskFilterTypeParam);
  } else {
    url.searchParams.set(taskFilterTypeParam, taskType);
  }
  if (query === "") {
    url.searchParams.delete(taskFilterQueryParam);
  } else {
    url.searchParams.set(taskFilterQueryParam, query);
  }
  if (selectedTaskId === null) {
    url.searchParams.delete(taskSelectionParam);
  } else {
    url.searchParams.set(taskSelectionParam, String(selectedTaskId));
  }
  return url;
}

function runMetricsViewUrl(days: number) {
  if (typeof window === "undefined") {
    return null;
  }
  const url = new URL(window.location.href);
  const supportedDays = supportedRunMetricsDays(days);
  if (supportedDays === defaultRunMetricsDays) {
    url.searchParams.delete(runMetricsDaysParam);
  } else {
    url.searchParams.set(runMetricsDaysParam, String(supportedDays));
  }
  return url;
}

function activityViewUrl(limit: number) {
  if (typeof window === "undefined") {
    return null;
  }
  const url = new URL(window.location.href);
  const supportedLimit = supportedActivityLimit(limit);
  if (supportedLimit === defaultActivityLimit) {
    url.searchParams.delete(activityLimitParam);
  } else {
    url.searchParams.set(activityLimitParam, String(supportedLimit));
  }
  return url;
}

function dashboardOverviewUrl(days: number, limit: number) {
  if (typeof window === "undefined") {
    return null;
  }
  const url = new URL(window.location.href);
  const supportedDays = supportedRunMetricsDays(days);
  const supportedLimit = supportedActivityLimit(limit);
  if (supportedDays === defaultRunMetricsDays) {
    url.searchParams.delete(runMetricsDaysParam);
  } else {
    url.searchParams.set(runMetricsDaysParam, String(supportedDays));
  }
  if (supportedLimit === defaultActivityLimit) {
    url.searchParams.delete(activityLimitParam);
  } else {
    url.searchParams.set(activityLimitParam, String(supportedLimit));
  }
  url.hash = "overview";
  return url;
}

function replaceBrowserUrl(url: URL | null) {
  if (url === null) {
    return;
  }
  const nextPath = `${url.pathname}${url.search}${url.hash}`;
  const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  if (nextPath !== currentPath) {
    window.history.replaceState(null, "", nextPath);
  }
}

function syncProjectViewToUrl(filters: ProjectFilters, selectedProjectId: number | "") {
  replaceBrowserUrl(projectViewUrl(filters, selectedProjectId));
}

function syncTaskViewToUrl(filters: TaskFilters, selectedTaskId: number | null) {
  replaceBrowserUrl(taskViewUrl(filters, selectedTaskId));
}

function syncRunMetricsDaysToUrl(days: number) {
  replaceBrowserUrl(runMetricsViewUrl(days));
}

function syncActivityLimitToUrl(limit: number) {
  replaceBrowserUrl(activityViewUrl(limit));
}

function syncControllerApiRiskFiltersToUrl(riskLevel: string, riskCode: string) {
  replaceBrowserUrl(controllerApiRiskFilterUrl(riskLevel, riskCode));
}

async function copyUrlToClipboard(
  url: URL | null,
  successMessage: string,
  setStatus: (value: string) => void
) {
  if (url === null) {
    setStatus("Copy unavailable");
    return;
  }
  await copyTextToClipboard(url.toString(), successMessage, setStatus);
}

async function copyTextToClipboard(
  text: string,
  successMessage: string,
  setStatus: (value: string) => void
) {
  if (navigator.clipboard === undefined) {
    setStatus("无法复制");
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    setStatus(successMessage);
  } catch {
    setStatus("复制失败");
  }
}

function safeFileSegment(value: string) {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "") || "controller-api-docs";
}

function controllerApiDocsFilename(docs: { repoFullName: string; generatedAt: string }) {
  const timestamp = docs.generatedAt
    .replace(/[:.]/g, "-")
    .replace(/z$/i, "Z");
  return `${safeFileSegment(docs.repoFullName)}-controller-api-docs-${timestamp}.md`;
}

function agentRunReportFilename(report: AgentRunReport) {
  const timestamp = report.generatedAt
    .replace(/[:.]/g, "-")
    .replace(/z$/i, "Z");
  return `${safeFileSegment(report.projectName)}-task-${report.taskId}-run-${report.runId}-agent-run-report-${timestamp}.md`;
}

function agentRunReportSnapshotFilename(snapshot: AgentRunReportSnapshot) {
  const timestamp = snapshot.reportGeneratedAt
    .replace(/[:.]/g, "-")
    .replace(/z$/i, "Z");
  const runSegment = snapshot.runId === null ? "run-unknown" : `run-${snapshot.runId}`;
  return `${safeFileSegment(snapshot.projectName)}-task-${snapshot.taskId}-${runSegment}-agent-run-report-snapshot-${timestamp}.md`;
}

function controllerApiFilterLabel(filters: { riskLevel: string | null; riskCode: string | null }) {
  return `风险 ${filters.riskLevel ?? "ALL"} / ${filters.riskCode ?? "ALL"}`;
}

function downloadTextFile(filename: string, text: string, type = "text/plain;charset=utf-8") {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function streamLabel(task: AgentTask | null, state: TaskStreamState) {
  if (task === null || !runningStatuses.has(task.status)) {
    return null;
  }
  if (state === "live") {
    return "实时流";
  }
  if (state === "connecting") {
    return "正在连接实时流";
  }
  if (state === "fallback") {
    return "轮询兜底";
  }
  return null;
}

export function App() {
  const [token, setToken] = useState(() => localStorage.getItem("repopilot.token") ?? "");
  const [email, setEmail] = useState("dev@example.com");
  const [password, setPassword] = useState("password123");
  const [displayName, setDisplayName] = useState("RepoPilot Developer");
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectRows, setProjectRows] = useState<Project[]>([]);
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [dashboardSummary, setDashboardSummary] = useState<DashboardSummary | null>(null);
  const [dashboardRunMetrics, setDashboardRunMetrics] = useState<DashboardRunMetrics | null>(null);
  const [dashboardActivity, setDashboardActivity] = useState<DashboardActivityItem[] | null>(null);
  const [runMetricsDays, setRunMetricsDays] = useState(initialRunMetricsDays);
  const [activityLimit, setActivityLimit] = useState(initialActivityLimit);
  const [coderSettings, setCoderSettings] = useState<CoderSettings | null>(null);
  const [githubSettings, setGitHubSettings] = useState<GitHubSettings | null>(null);
  const [sandboxSettings, setSandboxSettings] = useState<SandboxSettings | null>(null);
  const [selectedProjectId, setSelectedProjectId] = useState<number | "">(initialSelectedProjectId);
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(initialSelectedTaskId);
  const [details, setDetails] = useState<TaskDetails>(emptyDetails);
  const [projectInsight, setProjectInsight] = useState<ProjectInsight>(emptyProjectInsight);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [repoUrl, setRepoUrl] = useState("file:///Users/crossng/Desktop/ai-agent/examples/demo-spring-repo");
  const [defaultBranch, setDefaultBranch] = useState("main");
  const [taskProjectId, setTaskProjectId] = useState<number | "">("");
  const [taskTitle, setTaskTitle] = useState("Add User pagination API");
  const [taskDescription, setTaskDescription] = useState("Add a paginated query API for the User module and preserve existing style.");
  const [projectFilters, setProjectFilters] = useState<ProjectFilters>(initialProjectFilters);
  const [taskFilters, setTaskFilters] = useState<TaskFilters>(initialTaskFilters);
  const [codeSearchQuery, setCodeSearchQuery] = useState("UserService");
  const [controllerApiRiskFilters, setControllerApiRiskFilters] = useState<ControllerApiRiskFilters>(initialControllerApiRiskFilters);
  const [approvalComment, setApprovalComment] = useState("沙箱验证已通过。");
  const [rejectComment, setRejectComment] = useState("");
  const [copyOverviewLinkStatus, setCopyOverviewLinkStatus] = useState<string | null>(null);
  const [copyProjectLinkStatus, setCopyProjectLinkStatus] = useState<string | null>(null);
  const [copyTaskLinkStatus, setCopyTaskLinkStatus] = useState<string | null>(null);
  const [runReportActionStatus, setRunReportActionStatus] = useState<string | null>(null);
  const [taskStreamState, setTaskStreamState] = useState<TaskStreamState>("idle");

  const selectedTask = useMemo(
    () => details.task ?? tasks.find((task) => task.id === selectedTaskId) ?? null,
    [details.task, selectedTaskId, tasks]
  );
  const selectedProject = useMemo(
    () => projects.find((project) => project.id === selectedTask?.projectId) ?? null,
    [projects, selectedTask?.projectId]
  );
  const selectedInsightProject = useMemo(
    () => projects.find((project) => project.id === selectedProjectId) ?? null,
    [projects, selectedProjectId]
  );
  const latestTestRun = details.testRuns[0] ?? null;
  const patchRiskReview = useMemo(() => reviewReportFromSteps(details.steps), [details.steps]);
  const canRun = selectedTask !== null && ["CREATED", "FAILED_TEST"].includes(selectedTask.status);
  const canCancel = selectedTask !== null && runningStatuses.has(selectedTask.status);
  const canApprove = selectedTask?.status === "WAITING_HUMAN_APPROVAL" && details.patch !== null;
  const canRegeneratePatch = selectedTask !== null && regeneratableStatuses.has(selectedTask.status);
  const canPreparePr = details.pullRequestPreflight?.canPrepare ?? false;
  const isRetryingPullRequest = selectedTask?.status === "FAILED_PR_CREATION" || details.pullRequest?.status === "FAILED";
  const preparePrActionLabel = isRetryingPullRequest ? "重试发布 PR" : "准备 PR";
  const preparePrBusyLabel = isRetryingPullRequest ? "正在重试发布 PR" : "正在准备 PR";
  const shouldPollSelectedTask = selectedTask !== null && runningStatuses.has(selectedTask.status);
  const taskStreamLabel = streamLabel(selectedTask, taskStreamState);

  useEffect(() => {
    if (!token) {
      setProjects([]);
      setProjectRows([]);
      setTasks([]);
      setDashboardSummary(null);
      setDashboardRunMetrics(null);
      setDashboardActivity(null);
      setCoderSettings(null);
      setGitHubSettings(null);
      setSandboxSettings(null);
      setDetails(emptyDetails);
      setSelectedProjectId("");
      setProjectInsight(emptyProjectInsight);
      return;
    }
    void loadWorkspace(token);
  }, [token]);

  useEffect(() => {
    syncControllerApiRiskFiltersToUrl(controllerApiRiskFilters.riskLevel, controllerApiRiskFilters.riskCode);
  }, [controllerApiRiskFilters.riskLevel, controllerApiRiskFilters.riskCode]);

  useEffect(() => {
    syncRunMetricsDaysToUrl(runMetricsDays);
  }, [runMetricsDays]);

  useEffect(() => {
    syncActivityLimitToUrl(activityLimit);
  }, [activityLimit]);

  useEffect(() => {
    setCopyOverviewLinkStatus(null);
  }, [runMetricsDays, activityLimit]);

  useEffect(() => {
    syncProjectViewToUrl(projectFilters, selectedProjectId);
  }, [projectFilters.status, projectFilters.query, selectedProjectId]);

  useEffect(() => {
    setCopyProjectLinkStatus(null);
  }, [projectFilters.status, projectFilters.query, selectedProjectId]);

  useEffect(() => {
    syncTaskViewToUrl(taskFilters, selectedTaskId);
  }, [taskFilters.projectId, taskFilters.status, taskFilters.taskType, taskFilters.query, selectedTaskId]);

  useEffect(() => {
    setCopyTaskLinkStatus(null);
  }, [taskFilters.projectId, taskFilters.status, taskFilters.taskType, taskFilters.query, selectedTaskId]);

  useEffect(() => {
    if (!token || selectedProjectId === "") {
      setProjectInsight(emptyProjectInsight);
      return;
    }
    setProjectInsight(emptyProjectInsight);
    void loadProjectInsight(token, Number(selectedProjectId), controllerApiRiskFilters);
  }, [token, selectedProjectId, controllerApiRiskFilters.riskLevel, controllerApiRiskFilters.riskCode]);

  useEffect(() => {
    if (!token || selectedTaskId === null) {
      setDetails(emptyDetails);
      return;
    }
    void loadTaskDetails(token, selectedTaskId);
  }, [token, selectedTaskId]);

  useEffect(() => {
    if (!token || selectedTaskId === null || !shouldPollSelectedTask) {
      return;
    }
    let cancelled = false;
    const poll = async () => {
      try {
        const [nextDetails, nextTasks, [nextDashboardSummary, nextDashboardRunMetrics, nextDashboardActivity]] = await Promise.all([
          fetchTaskDetails(token, selectedTaskId),
          listTasks(token, taskFilters),
          fetchDashboard(token, runMetricsDays, activityLimit)
        ]);
        if (cancelled) {
          return;
        }
        setDetails(nextDetails);
        setTasks(nextTasks);
        setDashboardSummary(nextDashboardSummary);
        setDashboardRunMetrics(nextDashboardRunMetrics);
        setDashboardActivity(nextDashboardActivity);
      } catch (error) {
        if (!cancelled) {
          setMessage(formatError(error));
        }
      }
    };
    void poll();
    const intervalId = window.setInterval(() => void poll(), 1500);
    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
    };
  }, [token, selectedTaskId, shouldPollSelectedTask, taskFilters, runMetricsDays, activityLimit]);

  useEffect(() => {
    if (!token || selectedTaskId === null || !shouldPollSelectedTask) {
      setTaskStreamState("idle");
      return;
    }
    const controller = new AbortController();
    let cancelled = false;
    let refreshTimer: number | null = null;

    const refreshFromStream = async () => {
      try {
        const [nextDetails, nextTasks, [nextDashboardSummary, nextDashboardRunMetrics, nextDashboardActivity]] = await Promise.all([
          fetchTaskDetails(token, selectedTaskId),
          listTasks(token, taskFilters),
          fetchDashboard(token, runMetricsDays, activityLimit)
        ]);
        if (cancelled) {
          return;
        }
        setDetails(nextDetails);
        setTasks(nextTasks);
        setDashboardSummary(nextDashboardSummary);
        setDashboardRunMetrics(nextDashboardRunMetrics);
        setDashboardActivity(nextDashboardActivity);
      } catch (error) {
        if (!cancelled && !isAbortError(error)) {
          setMessage(formatError(error));
        }
      }
    };

    const scheduleRefresh = () => {
      if (refreshTimer !== null) {
        return;
      }
      refreshTimer = window.setTimeout(() => {
        refreshTimer = null;
        void refreshFromStream();
      }, 80);
    };

    setTaskStreamState("connecting");
    void streamTaskEvents(
      token,
      selectedTaskId,
      (event) => {
        if (cancelled) {
          return;
        }
        setTaskStreamState(event.eventType === "STREAM_COMPLETE" ? "ended" : "live");
        scheduleRefresh();
      },
      controller.signal
    ).catch((error) => {
      if (cancelled || isAbortError(error)) {
        return;
      }
      setTaskStreamState("fallback");
      setMessage(`任务实时流已切换为轮询兜底：${formatError(error)}`);
    });

    return () => {
      cancelled = true;
      controller.abort();
      if (refreshTimer !== null) {
        window.clearTimeout(refreshTimer);
      }
    };
  }, [token, selectedTaskId, shouldPollSelectedTask, taskFilters, runMetricsDays, activityLimit]);

  async function withBusy(label: string, action: () => Promise<void>) {
    setBusy(label);
    setMessage(null);
    try {
      await action();
    } catch (error) {
      setMessage(formatError(error));
    } finally {
      setBusy(null);
    }
  }

  async function fetchDashboard(
    authToken: string,
    days = runMetricsDays,
    limit = activityLimit
  ): Promise<[DashboardSummary, DashboardRunMetrics, DashboardActivityItem[]]> {
    return Promise.all([
      getDashboardSummary(authToken),
      getDashboardRunMetrics(authToken, days),
      getDashboardActivity(authToken, limit)
    ]);
  }

  async function handleRunMetricsDaysChange(days: number) {
    const supportedDays = supportedRunMetricsDays(days);
    setRunMetricsDays(supportedDays);
    await withBusy("正在加载运行指标", async () => {
      const nextRunMetrics = await getDashboardRunMetrics(token, supportedDays);
      setDashboardRunMetrics(nextRunMetrics);
    });
  }

  async function handleActivityLimitChange(limit: number) {
    const supportedLimit = supportedActivityLimit(limit);
    setActivityLimit(supportedLimit);
    await withBusy("正在加载活动流", async () => {
      const nextActivity = await getDashboardActivity(token, supportedLimit);
      setDashboardActivity(nextActivity);
    });
  }

  async function copyOverviewLink() {
    await copyUrlToClipboard(
      dashboardOverviewUrl(runMetricsDays, activityLimit),
      "概览链接已复制",
      setCopyOverviewLinkStatus
    );
  }

  function selectVisibleProject(nextProjectRows: Project[]) {
    setSelectedProjectId((current) => {
      if (nextProjectRows.length === 0) {
        return "";
      }
      if (current !== "" && nextProjectRows.some((project) => project.id === current)) {
        return current;
      }
      return nextProjectRows[0].id;
    });
  }

  async function loadWorkspace(authToken = token) {
    await withBusy("Refreshing workspace", async () => {
      const [
        nextProjects,
        nextProjectRows,
        nextTasks,
        [nextDashboardSummary, nextDashboardRunMetrics, nextDashboardActivity],
        nextCoderSettings,
        nextGitHubSettings,
        nextSandboxSettings
      ] = await Promise.all([
        listProjects(authToken),
        listProjects(authToken, projectFilters),
        listTasks(authToken, taskFilters),
        fetchDashboard(authToken),
        getCoderSettings(authToken),
        getGitHubSettings(authToken),
        getSandboxSettings(authToken)
      ]);
      setProjects(nextProjects);
      setProjectRows(nextProjectRows);
      setTasks(nextTasks);
      setDashboardSummary(nextDashboardSummary);
      setDashboardRunMetrics(nextDashboardRunMetrics);
      setDashboardActivity(nextDashboardActivity);
      setCoderSettings(nextCoderSettings);
      setGitHubSettings(nextGitHubSettings);
      setSandboxSettings(nextSandboxSettings);
      if (nextProjects.length > 0 && taskProjectId === "") {
        setTaskProjectId(nextProjects[0].id);
      }
      selectVisibleProject(nextProjectRows);
      if (selectedTaskId === null && nextTasks.length > 0) {
        setSelectedTaskId(nextTasks[0].id);
      }
    });
  }

  async function loadProjectInsight(authToken: string, projectId: number, riskFilters = controllerApiRiskFilters) {
    await withBusy("正在加载项目洞察", async () => {
      const [files, symbols, controllerApiList, controllerApiDocSnapshots] = await Promise.all([
        listProjectFiles(authToken, projectId, 10),
        listProjectSymbols(authToken, projectId),
        listControllerApis(authToken, projectId, riskFilters),
        listControllerApiDocsSnapshots(authToken, projectId, 5)
      ]);
      setProjectInsight((current) => ({
        ...current,
        files,
        symbols,
        controllerApis: controllerApiList.items,
        controllerApiFilteredCount: controllerApiList.filteredCount,
        controllerApiRiskSummary: controllerApiList.riskSummary,
        controllerApiRiskCodes: controllerApiList.riskCodes,
        controllerApiDocSnapshots
      }));
    });
  }

  async function fetchTaskDetails(authToken: string, taskId: number): Promise<TaskDetails> {
    const task = await getTask(authToken, taskId);
    const [steps, runReport, runReportSnapshots, patch, approvals, testRuns, modelCalls, toolCalls, pullRequest, pullRequestPreflight] = await Promise.all([
      listSteps(authToken, taskId),
      task.currentRunId === null ? Promise.resolve(null) : optional(() => getAgentRunReport(authToken, taskId)),
      listAgentRunReportSnapshots(authToken, taskId, 5),
      optional(() => getLatestPatch(authToken, taskId)),
      listApprovals(authToken, taskId),
      task.currentRunId === null ? Promise.resolve([]) : listTestRuns(authToken, task.currentRunId),
      task.currentRunId === null ? Promise.resolve([]) : listModelCalls(authToken, task.currentRunId),
      task.currentRunId === null ? Promise.resolve([]) : listToolCalls(authToken, task.currentRunId),
      optional(() => getLatestPullRequest(authToken, taskId)),
      getPullRequestPreflight(authToken, taskId)
    ]);
    return { task, steps, runReport, runReportSnapshots, patch, approvals, testRuns, modelCalls, toolCalls, pullRequest, pullRequestPreflight };
  }

  async function loadTaskDetails(authToken: string, taskId: number, showBusy = true) {
    const action = async () => {
      const [nextDetails, nextTasks, [nextDashboardSummary, nextDashboardRunMetrics, nextDashboardActivity]] = await Promise.all([
        fetchTaskDetails(authToken, taskId),
        listTasks(authToken, taskFilters),
        fetchDashboard(authToken)
      ]);
      setDetails(nextDetails);
      setTasks(nextTasks);
      setDashboardSummary(nextDashboardSummary);
      setDashboardRunMetrics(nextDashboardRunMetrics);
      setDashboardActivity(nextDashboardActivity);
    };
    if (showBusy) {
      await withBusy("正在加载任务详情", action);
      return;
    }
    try {
      await action();
    } catch (error) {
      setMessage(formatError(error));
    }
  }

  async function handleAuth(event: FormEvent<HTMLFormElement>, mode: "login" | "register") {
    event.preventDefault();
    await withBusy(mode === "login" ? "正在登录" : "正在创建账号", async () => {
      const auth = mode === "login"
        ? await login(email, password)
        : await register(email, password, displayName);
      localStorage.setItem("repopilot.token", auth.token);
      setToken(auth.token);
      setMessage(`已登录：${auth.user.email}`);
    });
  }

  async function handleCreateProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await withBusy("正在创建项目", async () => {
      const project = await createProject(token, { repoUrl, defaultBranch });
      const [
        nextProjects,
        nextProjectRows,
        [nextDashboardSummary, nextDashboardRunMetrics, nextDashboardActivity]
      ] = await Promise.all([
        listProjects(token),
        listProjects(token, projectFilters),
        fetchDashboard(token)
      ]);
      setProjects(nextProjects);
      setProjectRows(nextProjectRows);
      setTaskProjectId(project.id);
      if (nextProjectRows.some((row) => row.id === project.id)) {
        setSelectedProjectId(project.id);
      } else {
        selectVisibleProject(nextProjectRows);
      }
      setDashboardSummary(nextDashboardSummary);
      setDashboardRunMetrics(nextDashboardRunMetrics);
      setDashboardActivity(nextDashboardActivity);
      setMessage(`项目 #${project.id} 已创建。`);
    });
  }

  async function refreshProjectList(filters = projectFilters) {
    await withBusy("正在筛选项目", async () => {
      const [
        nextProjects,
        nextProjectRows,
        [nextDashboardSummary, nextDashboardRunMetrics, nextDashboardActivity]
      ] = await Promise.all([
        listProjects(token),
        listProjects(token, filters),
        fetchDashboard(token)
      ]);
      setProjects(nextProjects);
      setProjectRows(nextProjectRows);
      setDashboardSummary(nextDashboardSummary);
      setDashboardRunMetrics(nextDashboardRunMetrics);
      setDashboardActivity(nextDashboardActivity);
      selectVisibleProject(nextProjectRows);
    });
  }

  async function handleProjectFiltersSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await refreshProjectList(projectFilters);
  }

  async function resetProjectFilters() {
    const clearedFilters = { ...defaultProjectFilters };
    setProjectFilters(clearedFilters);
    await refreshProjectList(clearedFilters);
  }

  async function copyProjectViewLink() {
    await copyUrlToClipboard(projectViewUrl(projectFilters, selectedProjectId), "项目链接已复制", setCopyProjectLinkStatus);
  }

  async function handleCodeSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const query = codeSearchQuery.trim();
    if (selectedProjectId === "") {
      setMessage("请先选择项目再搜索代码。");
      return;
    }
    if (!query) {
      setMessage("请输入代码搜索关键词。");
      return;
    }
    await withBusy("正在搜索代码", async () => {
      const search = await searchProjectCode(token, Number(selectedProjectId), query, 8);
      setProjectInsight((current) => ({ ...current, search }));
    });
  }

  async function handleCreateTask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (taskProjectId === "") {
      setMessage("请先选择项目再创建任务。");
      return;
    }
    await withBusy("正在创建任务", async () => {
      const task = await createTask(token, {
        projectId: Number(taskProjectId),
        taskType: "FEATURE",
        title: taskTitle,
        description: taskDescription
      });
      const [nextTasks, [nextDashboardSummary, nextDashboardRunMetrics, nextDashboardActivity]] = await Promise.all([
        listTasks(token, taskFilters),
        fetchDashboard(token)
      ]);
      setTasks(nextTasks);
      setSelectedTaskId(task.id);
      setDashboardSummary(nextDashboardSummary);
      setDashboardRunMetrics(nextDashboardRunMetrics);
      setDashboardActivity(nextDashboardActivity);
      setMessage(`任务 #${task.id} 已创建。`);
    });
  }

  async function refreshTaskList(filters = taskFilters) {
    await withBusy("正在筛选任务", async () => {
      const [nextTasks, [nextDashboardSummary, nextDashboardRunMetrics, nextDashboardActivity]] = await Promise.all([
        listTasks(token, filters),
        fetchDashboard(token)
      ]);
      setTasks(nextTasks);
      setDashboardSummary(nextDashboardSummary);
      setDashboardRunMetrics(nextDashboardRunMetrics);
      setDashboardActivity(nextDashboardActivity);
      if (selectedTaskId === null && nextTasks.length > 0) {
        setSelectedTaskId(nextTasks[0].id);
      }
    });
  }

  async function handleTaskFiltersSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await refreshTaskList(taskFilters);
  }

  async function resetTaskFilters() {
    const clearedFilters = { ...defaultTaskFilters };
    setTaskFilters(clearedFilters);
    await refreshTaskList(clearedFilters);
  }

  async function copyTaskViewLink() {
    await copyUrlToClipboard(taskViewUrl(taskFilters, selectedTaskId), "任务链接已复制", setCopyTaskLinkStatus);
  }

  async function copyRunReport() {
    if (details.runReport === null) {
      setRunReportActionStatus("运行报告还未生成");
      return;
    }
    await copyTextToClipboard(details.runReport.markdown, "已复制当前运行报告", setRunReportActionStatus);
  }

  function downloadRunReport() {
    if (details.runReport === null) {
      setRunReportActionStatus("运行报告还未生成");
      return;
    }
    downloadTextFile(agentRunReportFilename(details.runReport), details.runReport.markdown, "text/markdown;charset=utf-8");
    setRunReportActionStatus("已下载当前运行报告");
  }

  async function saveRunReportSnapshot() {
    if (selectedTaskId === null || details.runReport === null) {
      setRunReportActionStatus("运行报告还未生成");
      return;
    }
    try {
      const snapshot = await createAgentRunReportSnapshot(token, selectedTaskId);
      setDetails((current) => ({
        ...current,
        runReportSnapshots: [
          snapshot,
          ...current.runReportSnapshots.filter((item) => item.id !== snapshot.id)
        ].slice(0, 5)
      }));
      setRunReportActionStatus(`已保存运行报告快照 #${snapshot.id}`);
    } catch (error) {
      setRunReportActionStatus(formatError(error));
    }
  }

  async function copyRunReportSnapshot(snapshotId: number) {
    if (selectedTaskId === null) {
      setRunReportActionStatus("请先选择任务");
      return;
    }
    try {
      const snapshot = await getAgentRunReportSnapshot(token, selectedTaskId, snapshotId);
      await copyTextToClipboard(snapshot.markdown, `已复制快照 #${snapshot.id}`, setRunReportActionStatus);
    } catch (error) {
      setRunReportActionStatus(formatError(error));
    }
  }

  async function downloadRunReportSnapshot(snapshotId: number) {
    if (selectedTaskId === null) {
      setRunReportActionStatus("请先选择任务");
      return;
    }
    try {
      const snapshot = await getAgentRunReportSnapshot(token, selectedTaskId, snapshotId);
      downloadTextFile(agentRunReportSnapshotFilename(snapshot), snapshot.markdown, "text/markdown;charset=utf-8");
      setRunReportActionStatus(`已下载快照 #${snapshot.id}`);
    } catch (error) {
      setRunReportActionStatus(formatError(error));
    }
  }

  async function refreshSelectedTask(showBusy = true) {
    if (selectedTaskId !== null) {
      await loadTaskDetails(token, selectedTaskId, showBusy);
      return;
    }
    const [nextTasks, [nextDashboardSummary, nextDashboardRunMetrics, nextDashboardActivity]] = await Promise.all([
      listTasks(token, taskFilters),
      fetchDashboard(token)
    ]);
    setTasks(nextTasks);
    setDashboardSummary(nextDashboardSummary);
    setDashboardRunMetrics(nextDashboardRunMetrics);
    setDashboardActivity(nextDashboardActivity);
  }

  function signOut() {
    localStorage.removeItem("repopilot.token");
    setToken("");
    setSelectedTaskId(null);
    setMessage("已退出登录。");
  }

  return (
    <main className="shell">
      <aside className="rail" aria-label="RepoPilot navigation">
        <div className="mark">RP</div>
        <nav>
          <a className="navItem active" href="#tasks">任务</a>
          <a className="navItem" href="#overview">概览</a>
          <a className="navItem" href="#projects">项目</a>
          <a className="navItem" href="#settings">配置</a>
          <a className="navItem" href="#patch">补丁</a>
          <a className="navItem" href="#pr">PR</a>
        </nav>
      </aside>

      <section className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">RepoPilot 控制台</p>
            <h1>接入仓库、运行 Agent、审查补丁、测试通过后再准备 PR。</h1>
          </div>
          <div className="topActions">
            <button className="ghostButton" type="button" onClick={() => void loadWorkspace()} disabled={!token || busy !== null}>
              刷新
            </button>
            {token ? <button className="ghostButton" type="button" onClick={signOut}>退出登录</button> : null}
          </div>
        </header>

        <StatusStrip
          selectedTask={selectedTask}
          patch={details.patch}
          testRun={latestTestRun}
          pullRequest={details.pullRequest}
        />

        {token ? (
          <DashboardSummaryPanel
            busy={busy !== null}
            copyLinkStatus={copyOverviewLinkStatus}
            onCopyLink={() => void copyOverviewLink()}
            summary={dashboardSummary}
          />
        ) : null}
        {token ? (
          <DashboardRunMetricsPanel
            busy={busy !== null}
            days={runMetricsDays}
            metrics={dashboardRunMetrics}
            onDaysChange={(days) => void handleRunMetricsDaysChange(days)}
          />
        ) : null}
        {token ? (
          <DashboardActivityPanel
            activity={dashboardActivity}
            busy={busy !== null}
            limit={activityLimit}
            onLimitChange={(limit) => void handleActivityLimitChange(limit)}
          />
        ) : null}

        {token ? (
          <section className="settingsGrid" id="settings">
            <CoderSettingsPanel settings={coderSettings} />
            <GitHubSettingsPanel settings={githubSettings} />
            <SandboxSettingsPanel settings={sandboxSettings} />
          </section>
        ) : null}

        {message ? <div className="notice">{message}</div> : null}
        {busy ? <div className="notice mutedNotice">{busy}...</div> : null}

        {!token ? (
          <section className="authGrid">
            <form className="panel" onSubmit={(event) => void handleAuth(event, "login")}>
                <div className="panelHeader">
                  <div>
                    <p className="eyebrow">身份认证</p>
                    <h2>登录</h2>
                  </div>
                </div>
              <TextField label="邮箱" value={email} onChange={setEmail} />
              <TextField label="密码" value={password} onChange={setPassword} type="password" />
              <button className="primaryButton fullButton" type="submit" disabled={busy !== null}>登录</button>
            </form>
            <form className="panel" onSubmit={(event) => void handleAuth(event, "register")}>
              <div className="panelHeader">
                <div>
                  <p className="eyebrow">首次使用</p>
                  <h2>创建本地账号</h2>
                </div>
              </div>
              <TextField label="显示名称" value={displayName} onChange={setDisplayName} />
              <TextField label="邮箱" value={email} onChange={setEmail} />
              <TextField label="密码" value={password} onChange={setPassword} type="password" />
              <button className="primaryButton fullButton" type="submit" disabled={busy !== null}>注册</button>
            </form>
          </section>
        ) : (
          <>
            <section className="mainGrid" id="projects">
              <form className="panel" onSubmit={(event) => void handleCreateProject(event)}>
                <div className="panelHeader">
                  <div>
                    <p className="eyebrow">仓库接入</p>
                    <h2>添加项目</h2>
                  </div>
                </div>
                <TextField label="仓库地址" value={repoUrl} onChange={setRepoUrl} />
                <TextField label="默认分支" value={defaultBranch} onChange={setDefaultBranch} />
                <button className="primaryButton fullButton" type="submit" disabled={busy !== null}>创建项目</button>
              </form>

              <form className="panel" onSubmit={(event) => void handleCreateTask(event)}>
                <div className="panelHeader">
                  <div>
                    <p className="eyebrow">Agent 任务</p>
                    <h2>创建任务</h2>
                  </div>
                  <span className="pill">FEATURE</span>
                </div>
                <label className="fieldLabel" htmlFor="projectSelect">项目</label>
                <select
                  id="projectSelect"
                  value={taskProjectId}
                  onChange={(event) => setTaskProjectId(event.target.value === "" ? "" : Number(event.target.value))}
                >
                  <option value="">选择项目</option>
                  {projects.map((project) => (
                    <option value={project.id} key={project.id}>
                      #{project.id} {project.repoFullName}
                    </option>
                  ))}
                </select>
                <TextField label="标题" value={taskTitle} onChange={setTaskTitle} />
                <label className="fieldLabel" htmlFor="taskDescription">任务描述</label>
                <textarea
                  id="taskDescription"
                  value={taskDescription}
                  onChange={(event) => setTaskDescription(event.target.value)}
                />
                <button className="primaryButton fullButton" type="submit" disabled={busy !== null}>创建任务</button>
              </form>
            </section>

            <section className="panel">
              <div className="panelHeader">
                <div>
                  <p className="eyebrow">项目</p>
                  <h2>仓库工作区</h2>
                </div>
              </div>
              <form className="projectFilterForm" aria-label="项目筛选" onSubmit={(event) => void handleProjectFiltersSubmit(event)}>
                <label className="fieldLabel" htmlFor="projectFilterStatus">状态</label>
                <select
                  id="projectFilterStatus"
                  aria-label="项目状态筛选"
                  value={projectFilters.status ?? "ALL"}
                  onChange={(event) => setProjectFilters((current) => ({ ...current, status: event.target.value }))}
                >
                  <option value="ALL">全部状态</option>
                  {projectStatusOptions.map((status) => (
                    <option value={status} key={status}>{status}</option>
                  ))}
                </select>
                <TextField
                  label="搜索项目"
                  value={projectFilters.query ?? ""}
                  onChange={(query) => setProjectFilters((current) => ({ ...current, query }))}
                />
                <div className="buttonRow">
                  <button className="ghostButton" type="submit" disabled={busy !== null}>应用筛选</button>
                  <button className="ghostButton" type="button" onClick={() => void resetProjectFilters()} disabled={busy !== null}>重置</button>
                  <button className="ghostButton copyProjectLinkButton" type="button" onClick={() => void copyProjectViewLink()}>
                    复制项目视图链接
                  </button>
                  <span className="copyProjectLinkStatus" aria-live="polite">
                    {copyProjectLinkStatus ?? ""}
                  </span>
                </div>
                <span className="filterSummary">显示 {projectRows.length} 个项目</span>
              </form>
              <div className="dataTable">
                {projectRows.length === 0 ? (
                  <EmptyText text={projects.length === 0 ? "还没有项目。" : "没有项目匹配当前筛选。"} />
                ) : projectRows.map((project) => (
                  <div className="dataRow projectRow" key={project.id}>
                    <span>#{project.id}</span>
                    <strong>{project.repoFullName}</strong>
                    <Badge value={project.status} />
                    <span>{project.lastIndexedAt ? formatDate(project.lastIndexedAt) : "未索引"}</span>
                    <div className="rowActions">
                      <button className="ghostButton" type="button" onClick={() => void withBusy("正在克隆项目", async () => {
                        await cloneProject(token, project.id);
                        await loadWorkspace(token);
                      })}>克隆</button>
                      <button className="ghostButton" type="button" onClick={() => void withBusy("正在索引项目", async () => {
                        await indexProject(token, project.id);
                        await loadWorkspace(token);
                      })}>索引</button>
                    </div>
                  </div>
                ))}
              </div>
            </section>

            <ProjectInsightPanel
              projects={projectRows}
              selectedProject={selectedInsightProject}
              selectedProjectId={selectedProjectId}
              insight={projectInsight}
              codeSearchQuery={codeSearchQuery}
              riskLevelFilter={controllerApiRiskFilters.riskLevel}
              riskCodeFilter={controllerApiRiskFilters.riskCode}
              busy={busy !== null}
              onSelectProject={setSelectedProjectId}
              setCodeSearchQuery={setCodeSearchQuery}
              setRiskLevelFilter={(riskLevel) => setControllerApiRiskFilters((current) => ({ ...current, riskLevel }))}
              setRiskCodeFilter={(riskCode) => setControllerApiRiskFilters((current) => ({ ...current, riskCode }))}
              onLoadApiDocs={async (limit) => {
                if (selectedProjectId === "") {
                  throw new Error("请先选择项目再加载接口文档。");
                }
                return getControllerApiDocs(token, Number(selectedProjectId), controllerApiRiskFilters, limit);
              }}
              onSaveApiDocsSnapshot={async (limit) => {
                if (selectedProjectId === "") {
                  throw new Error("请先选择项目再保存接口文档快照。");
                }
                const snapshot = await createControllerApiDocsSnapshot(
                  token,
                  Number(selectedProjectId),
                  controllerApiRiskFilters,
                  limit
                );
                setProjectInsight((current) => ({
                  ...current,
                  controllerApiDocSnapshots: [
                    snapshot,
                    ...current.controllerApiDocSnapshots.filter((item) => item.id !== snapshot.id)
                  ].slice(0, 5)
                }));
                return snapshot;
              }}
              onLoadApiDocsSnapshot={async (snapshotId) => {
                if (selectedProjectId === "") {
                  throw new Error("请先选择项目再加载接口文档快照。");
                }
                return getControllerApiDocsSnapshot(token, Number(selectedProjectId), snapshotId);
              }}
              onDeleteApiDocsSnapshot={async (snapshotId) => {
                if (selectedProjectId === "") {
                  throw new Error("请先选择项目再删除接口文档快照。");
                }
                await deleteControllerApiDocsSnapshot(token, Number(selectedProjectId), snapshotId);
                setProjectInsight((current) => ({
                  ...current,
                  controllerApiDocSnapshots: current.controllerApiDocSnapshots.filter((item) => item.id !== snapshotId)
                }));
              }}
              onClearApiDocsSnapshots={async () => {
                if (selectedProjectId === "") {
                  throw new Error("请先选择项目再清空接口文档快照。");
                }
                const result = await clearControllerApiDocsSnapshots(token, Number(selectedProjectId));
                setProjectInsight((current) => ({
                  ...current,
                  controllerApiDocSnapshots: []
                }));
                return result;
              }}
              onRefresh={() => {
                if (selectedProjectId !== "") {
                  void loadProjectInsight(token, Number(selectedProjectId), controllerApiRiskFilters);
                }
              }}
              onSearch={(event) => void handleCodeSearch(event)}
            />

            <section className="taskLayout" id="tasks">
              <aside className="panel taskListPanel">
                <div className="panelHeader">
                  <div>
                    <p className="eyebrow">任务</p>
                    <h2>Agent 运行</h2>
                  </div>
                </div>
                <form className="taskFilterForm" aria-label="任务筛选" onSubmit={(event) => void handleTaskFiltersSubmit(event)}>
                  <label className="fieldLabel" htmlFor="taskFilterProject">项目</label>
                  <select
                    id="taskFilterProject"
                    aria-label="任务项目筛选"
                    value={taskFilters.projectId ?? ""}
                    onChange={(event) => setTaskFilters((current) => ({
                      ...current,
                      projectId: event.target.value === "" ? "" : Number(event.target.value)
                    }))}
                  >
                    <option value="">全部项目</option>
                    {projects.map((project) => (
                      <option value={project.id} key={project.id}>
                        #{project.id} {project.repoFullName}
                      </option>
                    ))}
                  </select>
                  <label className="fieldLabel" htmlFor="taskFilterStatus">状态</label>
                  <select
                    id="taskFilterStatus"
                    aria-label="任务状态筛选"
                    value={taskFilters.status ?? "ALL"}
                    onChange={(event) => setTaskFilters((current) => ({ ...current, status: event.target.value }))}
                  >
                    <option value="ALL">全部状态</option>
                    {taskStatusOptions.map((status) => (
                      <option value={status} key={status}>{status}</option>
                    ))}
                  </select>
                  <label className="fieldLabel" htmlFor="taskFilterType">类型</label>
                  <select
                    id="taskFilterType"
                    aria-label="任务类型筛选"
                    value={taskFilters.taskType ?? "ALL"}
                    onChange={(event) => setTaskFilters((current) => ({ ...current, taskType: event.target.value }))}
                  >
                    <option value="ALL">全部类型</option>
                    {taskTypeOptions.map((taskType) => (
                      <option value={taskType} key={taskType}>{taskType}</option>
                    ))}
                  </select>
                  <TextField
                    label="搜索任务"
                    value={taskFilters.query ?? ""}
                    onChange={(query) => setTaskFilters((current) => ({ ...current, query }))}
                  />
                  <div className="buttonRow">
                    <button className="ghostButton" type="submit" disabled={busy !== null}>应用筛选</button>
                    <button className="ghostButton" type="button" onClick={() => void resetTaskFilters()} disabled={busy !== null}>重置</button>
                    <button className="ghostButton copyTaskLinkButton" type="button" onClick={() => void copyTaskViewLink()}>
                      复制任务视图链接
                    </button>
                    <span className="copyTaskLinkStatus" aria-live="polite">
                      {copyTaskLinkStatus ?? ""}
                    </span>
                  </div>
                  <span className="filterSummary">显示 {tasks.length} 个任务</span>
                </form>
                <div className="taskList">
                  {tasks.length === 0 ? <EmptyText text="还没有任务。" /> : tasks.map((task) => (
                    <button
                      className={task.id === selectedTaskId ? "taskListItem active" : "taskListItem"}
                      type="button"
                      onClick={() => setSelectedTaskId(task.id)}
                      key={task.id}
                    >
                      <span>#{task.id}</span>
                      <strong>{task.title}</strong>
                      <Badge value={task.status} />
                    </button>
                  ))}
                </div>
              </aside>

              <section className="detailStack">
                <TaskSummary
                  task={selectedTask}
                  project={selectedProject}
                  canRun={canRun}
                  canCancel={canCancel}
                  canPreparePr={canPreparePr}
                  preparePrActionLabel={preparePrActionLabel}
                  streamLabel={taskStreamLabel}
                  busy={busy !== null}
                  onRun={() => void withBusy("正在启动任务", async () => {
                    if (!selectedTask) return;
                    await runTask(token, selectedTask.id);
                    await refreshSelectedTask(false);
                  })}
                  onCancel={() => void withBusy("正在取消任务", async () => {
                    if (!selectedTask) return;
                    await cancelTask(token, selectedTask.id);
                    await refreshSelectedTask(false);
                  })}
                  onPreparePr={() => void withBusy(preparePrBusyLabel, async () => {
                    if (!selectedTask) return;
                    await preparePullRequest(token, selectedTask.id);
                    await refreshSelectedTask();
                  })}
                />

                <StepTimeline steps={details.steps} task={selectedTask} />
                <AgentEvidencePanel
                  steps={details.steps}
                  runReport={details.runReport}
                  runReportSnapshots={details.runReportSnapshots}
                  actionStatus={runReportActionStatus}
                  busy={busy !== null}
                  onCopyRunReport={() => void copyRunReport()}
                  onDownloadRunReport={downloadRunReport}
                  onSaveRunReportSnapshot={() => void saveRunReportSnapshot()}
                  onCopyRunReportSnapshot={(snapshotId) => void copyRunReportSnapshot(snapshotId)}
                  onDownloadRunReportSnapshot={(snapshotId) => void downloadRunReportSnapshot(snapshotId)}
                />
                <ModelCallPanel modelCalls={details.modelCalls} runId={selectedTask?.currentRunId ?? null} />
                <ToolCallPanel toolCalls={details.toolCalls} runId={selectedTask?.currentRunId ?? null} />

                <section className="dualGrid">
                  <PatchPanel patch={details.patch} review={patchRiskReview} />
                  <TestPanel testRun={latestTestRun} />
                </section>

                <section className="dualGrid">
                  <ApprovalPanel
                    canApprove={canApprove}
                    patch={details.patch}
                    approvals={details.approvals}
                    approvalComment={approvalComment}
                    rejectComment={rejectComment}
                    setApprovalComment={setApprovalComment}
                    setRejectComment={setRejectComment}
                    busy={busy !== null}
                    canRegenerate={canRegeneratePatch}
                    onApprove={() => void withBusy("正在审批补丁", async () => {
                      if (!selectedTask || !details.patch) return;
                      await approvePatch(token, selectedTask.id, details.patch.id, approvalComment);
                      await refreshSelectedTask();
                    })}
                    onReject={() => void withBusy("正在拒绝补丁", async () => {
                      if (!selectedTask || !details.patch) return;
                      await rejectPatch(token, selectedTask.id, details.patch.id, rejectComment);
                      await refreshSelectedTask();
                    })}
                    onRegenerate={() => void withBusy("正在重新生成补丁", async () => {
                      if (!selectedTask) return;
                      await regeneratePatch(token, selectedTask.id);
                      await refreshSelectedTask();
                    })}
                  />
                  <PullRequestPanel pullRequest={details.pullRequest} preflight={details.pullRequestPreflight} />
                </section>
              </section>
            </section>
          </>
        )}
      </section>
    </main>
  );
}

function StatusStrip({
  selectedTask,
  patch,
  testRun,
  pullRequest
}: {
  selectedTask: AgentTask | null;
  patch: PatchRecord | null;
  testRun: TestRun | null;
  pullRequest: PullRequestRecord | null;
}) {
  const cards = [
    { label: "任务", value: selectedTask?.status ?? "未选择", tone: toneFor(selectedTask?.status) },
    { label: "补丁", value: patch?.status ?? "未生成", tone: toneFor(patch?.status) },
    { label: "测试", value: testRun?.status ?? "未运行", tone: toneFor(testRun?.status) },
    { label: "PR", value: pullRequest?.status ?? "未准备", tone: toneFor(pullRequest?.status) }
  ];

  return (
    <section className="statusGrid" aria-label="系统状态">
      {cards.map((card) => (
        <article className="statusCard" data-tone={card.tone} key={card.label}>
          <span>{card.label}</span>
          <strong>{card.value}</strong>
        </article>
      ))}
    </section>
  );
}

function DashboardSummaryPanel({
  summary,
  busy,
  copyLinkStatus,
  onCopyLink
}: {
  summary: DashboardSummary | null;
  busy: boolean;
  copyLinkStatus: string | null;
  onCopyLink: () => void;
}) {
  const cards = summary === null ? [
    { label: "项目", value: "加载中", tone: "neutral" },
    { label: "运行中", value: "加载中", tone: "neutral" },
    { label: "待审批", value: "加载中", tone: "neutral" },
    { label: "失败", value: "加载中", tone: "neutral" }
  ] : [
    { label: "项目", value: `${summary.readyProjects}/${summary.totalProjects} 就绪`, tone: summary.failedProjects > 0 ? "warn" : "good" },
    { label: "任务", value: `${summary.totalTasks} 个任务`, tone: "neutral" },
    { label: "运行中", value: String(summary.runningTasks), tone: summary.runningTasks > 0 ? "warn" : "neutral" },
    { label: "待审批", value: String(summary.waitingApprovalTasks), tone: summary.waitingApprovalTasks > 0 ? "warn" : "neutral" },
    { label: "失败", value: String(summary.failedTasks), tone: summary.failedTasks > 0 ? "bad" : "good" },
    { label: "完成", value: String(summary.doneTasks), tone: "good" },
    { label: "PR 草稿", value: String(summary.draftPullRequests), tone: summary.draftPullRequests > 0 ? "warn" : "neutral" },
    { label: "已打开 PR", value: String(summary.openPullRequests), tone: summary.openPullRequests > 0 ? "good" : "neutral" }
  ];

  return (
    <section className="dashboardSummaryPanel" id="overview" aria-label="工作台概览">
      <div className="sectionHeader">
        <div>
          <h3>工作台概览</h3>
          {summary === null ? <span>正在加载概览</span> : <span>{summary.cancelledTasks} 个已取消，{summary.failedPullRequests} 个 PR 失败</span>}
        </div>
        <div className="buttonRow overviewShareActions">
          <button className="ghostButton copyOverviewLinkButton" type="button" onClick={onCopyLink} disabled={busy}>
            复制概览链接
          </button>
          <span className="copyOverviewLinkStatus" aria-live="polite">
            {copyLinkStatus ?? ""}
          </span>
        </div>
      </div>
      <div className="dashboardSummaryGrid">
        {cards.map((card) => (
          <article className="statusCard compactStatusCard" data-tone={card.tone} key={card.label}>
            <span>{card.label}</span>
            <strong>{card.value}</strong>
          </article>
        ))}
      </div>
    </section>
  );
}

function DashboardRunMetricsPanel({
  metrics,
  days,
  busy,
  onDaysChange
}: {
  metrics: DashboardRunMetrics | null;
  days: number;
  busy: boolean;
  onDaysChange: (days: number) => void;
}) {
  const cards = metrics === null ? [
    { label: "运行次数", value: "加载中", tone: "neutral" },
    { label: "成功率", value: "加载中", tone: "neutral" },
    { label: "平均耗时", value: "加载中", tone: "neutral" },
    { label: "当前运行", value: "加载中", tone: "neutral" }
  ] : [
    { label: "运行次数", value: String(metrics.totalRuns), tone: "neutral" },
    { label: "成功率", value: `${metrics.successRatePercent}%`, tone: metrics.failedRuns > 0 ? "warn" : "good" },
    { label: "平均耗时", value: formatDuration(metrics.averageDurationSeconds), tone: "neutral" },
    { label: "当前运行", value: String(metrics.runningRuns), tone: metrics.runningRuns > 0 ? "warn" : "neutral" }
  ];
  const maxRuns = metrics === null
    ? 1
    : Math.max(1, ...metrics.trend.map((point) => point.totalRuns));

  return (
    <section className="dashboardRunMetricsPanel panel" aria-label="Agent 运行表现">
      <div className="sectionHeader">
        <div>
          <h3>Agent 运行表现</h3>
          {metrics === null ? (
            <span>正在加载运行指标</span>
          ) : (
            <span>最近 {metrics.days} 天，{metrics.failedRuns} 次失败，{metrics.cancelledRuns} 次取消</span>
          )}
        </div>
        <label className="runMetricsWindowControl" htmlFor="runMetricsWindow">
          <span>窗口</span>
          <select
            id="runMetricsWindow"
            aria-label="运行指标窗口"
            value={days}
            onChange={(event) => onDaysChange(Number(event.target.value))}
            disabled={busy}
          >
            {runMetricsDaysOptions.map((option) => (
              <option value={option} key={option}>最近 {option} 天</option>
            ))}
          </select>
        </label>
      </div>
      <div className="dashboardSummaryGrid">
        {cards.map((card) => (
          <article className="statusCard compactStatusCard" data-tone={card.tone} key={card.label}>
            <span>{card.label}</span>
            <strong>{card.value}</strong>
          </article>
        ))}
      </div>
      {metrics === null ? (
        <EmptyText text="正在加载每日运行趋势。" />
      ) : (
        <div className="runTrendList" aria-label="每日运行趋势">
          {metrics.trend.map((point) => (
            <div className="runTrendRow" key={point.date}>
              <span>{point.date.slice(5)}</span>
              <div className="runTrendTrack">
                <div
                  className="runTrendBar"
                  style={{ width: point.totalRuns === 0 ? "0%" : `${Math.max(4, (point.totalRuns / maxRuns) * 100)}%` }}
                />
              </div>
              <strong>{point.totalRuns}</strong>
              <span>{point.successRuns} 次成功</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function DashboardActivityPanel({
  activity,
  limit,
  busy,
  onLimitChange
}: {
  activity: DashboardActivityItem[] | null;
  limit: number;
  busy: boolean;
  onLimitChange: (limit: number) => void;
}) {
  return (
    <section className="dashboardActivityPanel panel" aria-label="最近任务活动">
      <div className="sectionHeader">
        <div>
          <h3>最近任务活动</h3>
          <span>{activity === null ? "正在加载活动流" : `最近 ${limit} 条中的 ${activity.length} 条`}</span>
        </div>
        <label className="activityLimitControl" htmlFor="activityLimit">
          <span>数量</span>
          <select
            id="activityLimit"
            aria-label="活动数量"
            value={limit}
            onChange={(event) => onLimitChange(Number(event.target.value))}
            disabled={busy}
          >
            {activityLimitOptions.map((option) => (
              <option value={option} key={option}>最近 {option} 条</option>
            ))}
          </select>
        </label>
      </div>
      {activity === null ? (
        <EmptyText text="正在加载最近任务活动。" />
      ) : activity.length === 0 ? (
        <EmptyText text="还没有任务活动。" />
      ) : (
        <div className="activityList">
          {activity.map((item) => (
            <article className="activityItem" key={item.stepId}>
              <div>
                <div className="activityTitle">
                  <strong>{item.label}</strong>
                  <Badge value={item.status} />
                </div>
                <p>#{item.taskId} {item.taskTitle}</p>
                <span>{item.projectName} · 任务 {item.taskStatus}</span>
              </div>
              <time dateTime={item.occurredAt}>{formatDate(item.occurredAt)}</time>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function CoderSettingsPanel({ settings }: { settings: CoderSettings | null }) {
  const readinessLabel = settings === null
    ? "Loading"
    : settings.ready
      ? "READY"
      : "NEEDS CONFIG";
  return (
    <section className="panel coderSettingsPanel">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">Coder 配置</p>
          <h2>模型提供方状态</h2>
        </div>
        <div className="headerBadges">
          {settings ? <Badge value={settings.provider} /> : null}
          <Badge value={readinessLabel} />
        </div>
      </div>
      {settings === null ? (
        <EmptyText text="正在加载 Coder 模型配置。" />
      ) : (
        <>
          <div className="metaGrid coderSettingsGrid">
            <Meta label="模式" value={settings.mode} />
            <Meta label="模型" value={settings.model ?? "未配置"} />
            <Meta label="API Key" value={settings.apiKeyConfigured ? "已配置" : "未配置"} />
            <Meta label="端点" value={settings.apiBaseUrl} />
          </div>
          <div className="coderSettingsDetails">
            <span className="paramChip"><strong>状态</strong> {settings.enabled ? "外部/fixture 模型路径已启用" : "Recipe 与安全兜底模式"}</span>
            <span className="paramChip"><strong>角色</strong> {settings.instructionRole}</span>
            <span className="paramChip"><strong>超时</strong> {settings.timeoutSeconds}s</span>
            <span className="paramChip"><strong>Token 上限</strong> {settings.maxCompletionTokens}</span>
            <span className="paramChip"><strong>Fixture</strong> {settings.fixtureConfigured ? "已配置" : "空"}</span>
            <span className="paramChip"><strong>组织</strong> {settings.organizationConfigured ? "已配置" : "空"}</span>
            <span className="paramChip"><strong>项目</strong> {settings.projectConfigured ? "已配置" : "空"}</span>
          </div>
          {settings.missingRequirements.length > 0 ? (
            <div className="errorBox">
              缺少 Coder 配置：{settings.missingRequirements.join(", ")}
            </div>
          ) : null}
          <p className="description compactDescription">
            支持模式：{settings.supportedModes.join(", ")}。密钥由环境变量管理，此接口不会返回密钥。
          </p>
        </>
      )}
    </section>
  );
}

function GitHubSettingsPanel({ settings }: { settings: GitHubSettings | null }) {
  const readinessLabel = settings === null
    ? "Loading"
    : settings.ready
      ? "READY"
      : "NEEDS CONFIG";
  return (
    <section className="panel githubSettingsPanel">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">GitHub 发布</p>
          <h2>Pull Request 发布</h2>
        </div>
        <div className="headerBadges">
          {settings ? <Badge value={settings.provider} /> : null}
          <Badge value={readinessLabel} />
        </div>
      </div>
      {settings === null ? (
        <EmptyText text="正在加载 GitHub 发布配置。" />
      ) : (
        <>
          <div className="metaGrid githubSettingsGrid">
            <Meta label="发布模式" value={settings.publishMode} />
            <Meta label="Token" value={settings.tokenConfigured ? "已配置" : "未配置"} />
            <Meta label="远程 PR" value={settings.remotePublishingEnabled ? "已启用" : "已禁用"} />
            <Meta label="端点" value={settings.apiBaseUrl} />
          </div>
          <div className="settingsDetails">
            <span className="paramChip"><strong>状态</strong> {settings.localDraftMode ? "本地 PR 草稿准备" : "远程 GitHub PR 创建"}</span>
            <span className="paramChip"><strong>草稿</strong> {settings.localDraftMode ? "启用" : "兜底"}</span>
            <span className="paramChip"><strong>远端</strong> {settings.remotePublishingEnabled ? "推送并创建 PR" : "不发布"}</span>
          </div>
          {settings.missingRequirements.length > 0 ? (
            <div className="errorBox">
              缺少 GitHub 配置：{settings.missingRequirements.join(", ")}
            </div>
          ) : null}
          <p className="description compactDescription">
            GitHub token 由环境变量管理。远端发布关闭时，RepoPilot 仍会准备本地分支、提交和 DRAFT_READY PR 记录。
          </p>
        </>
      )}
    </section>
  );
}

function SandboxSettingsPanel({ settings }: { settings: SandboxSettings | null }) {
  const readinessLabel = settings === null
    ? "Loading"
    : settings.ready
      ? "READY"
      : "NEEDS CONFIG";
  return (
    <section className="panel sandboxSettingsPanel">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">沙箱运行时</p>
          <h2>Docker 沙箱</h2>
        </div>
        <div className="headerBadges">
          {settings ? <Badge value={settings.dockerAvailable ? "DOCKER" : "LOCAL"} /> : null}
          <Badge value={readinessLabel} />
        </div>
      </div>
      {settings === null ? (
        <EmptyText text="正在加载沙箱运行时配置。" />
      ) : (
        <>
          <div className="metaGrid sandboxSettingsGrid">
            <Meta label="镜像" value={settings.dockerImage ?? "未配置"} />
            <Meta label="Docker" value={settings.dockerAvailable ? `可用 ${settings.dockerVersion ?? ""}`.trim() : settings.dockerCheckEnabled ? "不可用" : "未检查"} />
            <Meta label="超时" value={`${settings.timeoutSeconds}s`} />
            <Meta label="Maven 缓存" value={settings.mavenCacheWritable ? "可写" : "受阻"} />
          </div>
          <div className="settingsDetails">
            <span className="paramChip"><strong>工作区</strong> {settings.workspaceRootWritable ? "可写" : "受阻"}</span>
            <span className="paramChip"><strong>缓存</strong> {settings.mavenCacheExists ? "已存在" : "会创建"}</span>
            <span className="paramChip"><strong>检查</strong> {settings.dockerCheckEnabled ? "Docker daemon" : "已关闭"}</span>
          </div>
          <div className="sectionHeader">
            <h3>沙箱就绪检查</h3>
            <span>{settings.checks.length} 项检查</span>
          </div>
          <div className="preflightCheckList" aria-label="沙箱就绪检查">
            {settings.checks.map((check) => (
              <div className="preflightCheckRow" data-status={check.status} key={check.code}>
                <Badge value={check.status} />
                <div>
                  <strong>{check.label}</strong>
                  <span>{check.message}</span>
                </div>
              </div>
            ))}
          </div>
          {settings.missingRequirements.length > 0 ? (
            <div className="errorBox">
              缺少沙箱要求：{settings.missingRequirements.join(", ")}
            </div>
          ) : null}
          <p className="description compactDescription">
            Maven 缓存路径：{settings.mavenCachePath}。工作区根目录：{settings.workspaceRoot}。
          </p>
        </>
      )}
    </section>
  );
}

function ProjectInsightPanel({
  projects,
  selectedProject,
  selectedProjectId,
  insight,
  codeSearchQuery,
  riskLevelFilter,
  riskCodeFilter,
  busy,
  onSelectProject,
  setCodeSearchQuery,
  setRiskLevelFilter,
  setRiskCodeFilter,
  onLoadApiDocs,
  onSaveApiDocsSnapshot,
  onLoadApiDocsSnapshot,
  onDeleteApiDocsSnapshot,
  onClearApiDocsSnapshots,
  onRefresh,
  onSearch
}: {
  projects: Project[];
  selectedProject: Project | null;
  selectedProjectId: number | "";
  insight: ProjectInsight;
  codeSearchQuery: string;
  riskLevelFilter: string;
  riskCodeFilter: string;
  busy: boolean;
  onSelectProject: (value: number | "") => void;
  setCodeSearchQuery: (value: string) => void;
  setRiskLevelFilter: (value: string) => void;
  setRiskCodeFilter: (value: string) => void;
  onLoadApiDocs: (limit: number) => Promise<ControllerApiDocsResponse>;
  onSaveApiDocsSnapshot: (limit: number) => Promise<ControllerApiDocsSnapshot>;
  onLoadApiDocsSnapshot: (snapshotId: number) => Promise<ControllerApiDocsSnapshot>;
  onDeleteApiDocsSnapshot: (snapshotId: number) => Promise<void>;
  onClearApiDocsSnapshots: () => Promise<ControllerApiDocsSnapshotClearResponse>;
  onRefresh: () => void;
  onSearch: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const [copyRiskLinkStatus, setCopyRiskLinkStatus] = useState<string | null>(null);
  const [copyApiDocsStatus, setCopyApiDocsStatus] = useState<string | null>(null);
  const [downloadApiDocsStatus, setDownloadApiDocsStatus] = useState<string | null>(null);
  const [saveApiDocsStatus, setSaveApiDocsStatus] = useState<string | null>(null);
  const [snapshotActionStatus, setSnapshotActionStatus] = useState<string | null>(null);
  const symbolCounts = insight.symbols.reduce<Record<string, number>>((counts, symbol) => {
    counts[symbol.symbolType] = (counts[symbol.symbolType] ?? 0) + 1;
    return counts;
  }, {});
  const topSymbols = insight.symbols.slice(0, 12);
  const visibleFiles = insight.files.slice(0, 18);
  const riskLevelCounts = controllerRiskLevels
    .filter((level) => level !== "ALL")
    .reduce<Record<string, number>>((counts, level) => {
      counts[level] = insight.controllerApiRiskSummary.byLevel[level]
        ?? insight.controllerApis.filter((api) => api.riskLevel === level).length;
      return counts;
    }, {});
  const fallbackRiskCodeOptions = Array.from(new Set(
    insight.controllerApis.flatMap((api) => api.riskHints.map((hint) => hint.code))
  )).sort();
  const riskCodeOptions = insight.controllerApiRiskCodes.length > 0
    ? insight.controllerApiRiskCodes
    : fallbackRiskCodeOptions;
  const selectedRiskCodeIsMissing = riskCodeFilter !== "ALL" && !riskCodeOptions.includes(riskCodeFilter);
  const filteredApis = insight.controllerApis
    .filter((api) => riskLevelFilter === "ALL" || api.riskLevel === riskLevelFilter)
    .filter((api) => riskCodeFilter === "ALL" || api.riskHints.some((hint) => hint.code === riskCodeFilter))
    .slice()
    .sort((left, right) =>
      right.riskScore - left.riskScore
      || left.path.localeCompare(right.path)
      || left.httpMethod.localeCompare(right.httpMethod)
      || left.methodName.localeCompare(right.methodName)
    );
  const riskFiltersActive = riskLevelFilter !== "ALL" || riskCodeFilter !== "ALL";
  const visibleApis = filteredApis.slice(0, 12);
  const visibleApiAnchors = visibleApis.map((api) => controllerApiAnchor(api)).join("|");

  useEffect(() => {
    setCopyRiskLinkStatus(null);
  }, [riskLevelFilter, riskCodeFilter]);

  useEffect(() => {
    setCopyApiDocsStatus(null);
    setDownloadApiDocsStatus(null);
    setSaveApiDocsStatus(null);
    setSnapshotActionStatus(null);
  }, [riskLevelFilter, riskCodeFilter, visibleApiAnchors, selectedProject?.id]);

  useEffect(() => {
    if (typeof window === "undefined" || !window.location.hash.startsWith(`#${controllerApiAnchorPrefix}`)) {
      return;
    }
    window.setTimeout(() => {
      document.getElementById(window.location.hash.slice(1))?.scrollIntoView({ block: "center" });
    }, 0);
  }, [visibleApiAnchors]);

  async function copyRiskViewLink() {
    await copyUrlToClipboard(controllerApiRiskFilterUrl(riskLevelFilter, riskCodeFilter), "链接已复制", setCopyRiskLinkStatus);
  }

  async function copyControllerApiLink(api: ControllerApi) {
    await copyUrlToClipboard(
      controllerApiRiskFilterUrl(riskLevelFilter, riskCodeFilter, controllerApiAnchor(api)),
      "路由链接已复制",
      setCopyRiskLinkStatus
    );
  }

  async function copyControllerApiDocs() {
    if (!selectedProject) {
      setCopyApiDocsStatus("无法复制");
      return;
    }
    try {
      const docs = await onLoadApiDocs(Math.max(1, visibleApis.length));
      await copyTextToClipboard(docs.markdown, "接口文档已复制", setCopyApiDocsStatus);
    } catch (error) {
      setCopyApiDocsStatus(formatError(error));
    }
  }

  async function downloadControllerApiDocs() {
    if (!selectedProject) {
      setDownloadApiDocsStatus("无法下载");
      return;
    }
    try {
      const docs = await onLoadApiDocs(Math.max(1, visibleApis.length));
      downloadTextFile(controllerApiDocsFilename(docs), docs.markdown, "text/markdown;charset=utf-8");
      setDownloadApiDocsStatus("接口文档已下载");
    } catch (error) {
      setDownloadApiDocsStatus(formatError(error));
    }
  }

  async function saveControllerApiDocsSnapshot() {
    if (!selectedProject) {
      setSaveApiDocsStatus("无法保存快照");
      return;
    }
    try {
      const snapshot = await onSaveApiDocsSnapshot(Math.max(1, visibleApis.length));
      setSaveApiDocsStatus(`接口文档快照 #${snapshot.id} 已保存`);
    } catch (error) {
      setSaveApiDocsStatus(formatError(error));
    }
  }

  async function copySavedApiDocsSnapshot(snapshotId: number) {
    try {
      const snapshot = await onLoadApiDocsSnapshot(snapshotId);
      await copyTextToClipboard(snapshot.markdown, `快照 #${snapshot.id} 已复制`, setSnapshotActionStatus);
    } catch (error) {
      setSnapshotActionStatus(formatError(error));
    }
  }

  async function downloadSavedApiDocsSnapshot(snapshotId: number) {
    try {
      const snapshot = await onLoadApiDocsSnapshot(snapshotId);
      downloadTextFile(controllerApiDocsFilename(snapshot), snapshot.markdown, "text/markdown;charset=utf-8");
      setSnapshotActionStatus(`快照 #${snapshot.id} 已下载`);
    } catch (error) {
      setSnapshotActionStatus(formatError(error));
    }
  }

  async function deleteSavedApiDocsSnapshot(snapshotId: number) {
    try {
      await onDeleteApiDocsSnapshot(snapshotId);
      setSnapshotActionStatus(`快照 #${snapshotId} 已删除`);
    } catch (error) {
      setSnapshotActionStatus(formatError(error));
    }
  }

  async function clearSavedApiDocsSnapshots() {
    if (insight.controllerApiDocSnapshots.length === 0) {
      setSnapshotActionStatus("没有可清空的快照");
      return;
    }
    try {
      const result = await onClearApiDocsSnapshots();
      setSnapshotActionStatus(`已清空 ${result.deletedCount} 份快照`);
    } catch (error) {
      setSnapshotActionStatus(formatError(error));
    }
  }

  return (
    <section className="panel projectInsightPanel" id="code">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">代码地图</p>
          <h2>仓库洞察</h2>
        </div>
        <div className="insightControls">
          <select
            aria-label="洞察项目"
            value={selectedProjectId}
            onChange={(event) => onSelectProject(event.target.value === "" ? "" : Number(event.target.value))}
          >
            <option value="">选择项目</option>
            {projects.map((project) => (
              <option value={project.id} key={project.id}>
                #{project.id} {project.repoFullName}
              </option>
            ))}
          </select>
          <button className="ghostButton" type="button" onClick={onRefresh} disabled={selectedProjectId === "" || busy}>
            刷新地图
          </button>
        </div>
      </div>

      {selectedProject ? (
        <>
          <div className="metaGrid compact">
            <Meta label="项目" value={`#${selectedProject.id} ${selectedProject.repoFullName}`} />
            <Meta label="状态" value={selectedProject.status} />
            <Meta label="索引时间" value={selectedProject.lastIndexedAt ? formatDate(selectedProject.lastIndexedAt) : "未索引"} />
          </div>

          <section className="insightGrid">
            <div className="insightColumn">
              <div className="sectionHeader">
                <h3>文件</h3>
                <span>{insight.files.length} 条路径</span>
              </div>
              <div className="compactList">
                {visibleFiles.length === 0 ? <EmptyText text="克隆项目后会加载文件路径。" /> : visibleFiles.map((file) => (
                  <div className="compactRow" key={file.path}>
                    <Badge value={file.type === "DIRECTORY" ? "DIR" : "FILE"} />
                    <strong>{file.path}</strong>
                    <span>{file.type === "DIRECTORY" ? "目录" : formatSize(file.size)}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="insightColumn">
              <div className="sectionHeader">
                <h3>符号</h3>
                <span>{insight.symbols.length} 个已索引</span>
              </div>
              <div className="chipRow">
                {Object.entries(symbolCounts).length === 0 ? <span className="mutedChip">暂无符号</span> : Object.entries(symbolCounts).map(([type, count]) => (
                  <span className="pill" key={type}>{type} {count}</span>
                ))}
              </div>
              <div className="compactList">
                {topSymbols.length === 0 ? <EmptyText text="索引项目后会提取 Java 符号。" /> : topSymbols.map((symbol) => (
                  <div className="symbolRow" key={symbol.id}>
                    <Badge value={symbol.symbolType} />
                    <strong>{symbol.qualifiedName || symbol.name}</strong>
                    <span>{symbol.filePath}:{symbol.startLine ?? "?"}</span>
                  </div>
                ))}
              </div>
            </div>
          </section>

          <section className="apiSection">
            <div className="sectionHeader">
              <h3>Controller 接口</h3>
              <span>
                {!riskFiltersActive
                  ? `${insight.controllerApiRiskSummary.total} 个接口`
                  : `${insight.controllerApiFilteredCount} / ${insight.controllerApiRiskSummary.total} 个接口`}
              </span>
            </div>
            <div className="apiRiskSummary" aria-label="Controller 接口风险概览">
              {controllerRiskLevels.filter((level) => level !== "ALL").map((level) => (
                <button
                  className="riskSummaryButton"
                  data-level={level}
                  data-active={riskLevelFilter === level}
                  type="button"
                  onClick={() => setRiskLevelFilter(level)}
                  key={level}
                >
                  <strong>{riskLevelCounts[level] ?? 0}</strong>
                  <span>{level}</span>
                </button>
              ))}
            </div>
            <div className="apiFilterBar">
              <label>
                <span>风险等级</span>
                <select
                  aria-label="风险等级"
                  value={riskLevelFilter}
                  onChange={(event) => setRiskLevelFilter(event.target.value)}
                >
                  <option value="ALL">全部等级</option>
                  {controllerRiskLevels.filter((level) => level !== "ALL").map((level) => (
                    <option value={level} key={level}>{level}</option>
                  ))}
                </select>
              </label>
              <label>
                <span>风险码</span>
                <select
                  aria-label="风险码"
                  value={riskCodeFilter}
                  onChange={(event) => setRiskCodeFilter(event.target.value)}
                >
                  <option value="ALL">全部风险码</option>
                  {selectedRiskCodeIsMissing ? <option value={riskCodeFilter}>{riskCodeFilter}</option> : null}
                  {riskCodeOptions.map((code) => <option value={code} key={code}>{code}</option>)}
                </select>
              </label>
              <button className="ghostButton copyRiskLinkButton" type="button" onClick={copyRiskViewLink}>
                复制风险视图链接
              </button>
              <span className="copyRiskLinkStatus" aria-live="polite">
                {copyRiskLinkStatus ?? ""}
              </span>
              <button
                className="ghostButton copyApiDocsButton"
                type="button"
                onClick={() => void copyControllerApiDocs()}
                disabled={visibleApis.length === 0}
              >
                复制接口文档
              </button>
              <span className="copyApiDocsStatus" aria-live="polite">
                {copyApiDocsStatus ?? ""}
              </span>
              <button
                className="ghostButton downloadApiDocsButton"
                type="button"
                onClick={() => void downloadControllerApiDocs()}
                disabled={visibleApis.length === 0}
              >
                下载接口文档
              </button>
              <span className="downloadApiDocsStatus" aria-live="polite">
                {downloadApiDocsStatus ?? ""}
              </span>
              <button
                className="ghostButton saveApiDocsSnapshotButton"
                type="button"
                onClick={() => void saveControllerApiDocsSnapshot()}
                disabled={visibleApis.length === 0}
              >
                保存接口文档快照
              </button>
              <span className="saveApiDocsStatus" aria-live="polite">
                {saveApiDocsStatus ?? ""}
              </span>
            </div>
            <div className="apiDocSnapshotList" aria-label="接口文档快照">
              <div className="sectionHeader apiDocSnapshotHeader">
                <h4>接口文档快照</h4>
                <div className="apiDocSnapshotHeaderActions">
                  <span aria-live="polite">{snapshotActionStatus ?? `${insight.controllerApiDocSnapshots.length} 份最近快照`}</span>
                  {insight.controllerApiDocSnapshots.length > 0 ? (
                    <button
                      className="ghostButton dangerButton"
                      type="button"
                      onClick={() => void clearSavedApiDocsSnapshots()}
                    >
                      清空快照
                    </button>
                  ) : null}
                </div>
              </div>
              {insight.controllerApiDocSnapshots.length === 0 ? (
                <EmptyText text="还没有保存接口文档快照。" />
              ) : (
                <div className="compactList">
                  {insight.controllerApiDocSnapshots.map((snapshot) => (
                    <div className="apiDocSnapshotRow" key={snapshot.id}>
                      <strong>快照 #{snapshot.id}</strong>
                      <span>{formatDate(snapshot.generatedAt)}</span>
                      <span>{snapshot.routeCount} / {snapshot.filteredCount} 个接口</span>
                      <span>{controllerApiFilterLabel(snapshot.filters)}</span>
                      <span className="apiDocSnapshotActions">
                        <button
                          className="ghostButton"
                          type="button"
                          onClick={() => void copySavedApiDocsSnapshot(snapshot.id)}
                        >
                          复制快照
                        </button>
                        <button
                          className="ghostButton"
                          type="button"
                          onClick={() => void downloadSavedApiDocsSnapshot(snapshot.id)}
                        >
                          下载快照
                        </button>
                        <button
                          className="ghostButton dangerButton"
                          type="button"
                          onClick={() => void deleteSavedApiDocsSnapshot(snapshot.id)}
                        >
                          删除快照
                        </button>
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="apiList">
              {visibleApis.length === 0 ? (
                <EmptyText text={insight.controllerApiRiskSummary.total === 0 ? "没有检测到 Spring Controller 接口。" : "没有接口匹配当前风险筛选。"} />
              ) : visibleApis.map((api) => (
                <article className="apiRow" id={controllerApiAnchor(api)} key={`${api.qualifiedControllerName}.${api.methodName}.${api.httpMethod}.${api.path}`}>
                  <div className="apiMethodStack">
                    <Badge value={api.httpMethod} />
                    <span className="riskScoreBadge" data-level={api.riskLevel}>
                      <strong>{api.riskScore}</strong>
                      <span>{api.riskLevel}</span>
                    </span>
                  </div>
                  <div className="apiRoute">
                    <strong>{api.path}</strong>
                    <span>{api.controllerName}.{api.methodName}</span>
                    <button className="ghostButton routeLinkButton" type="button" onClick={() => void copyControllerApiLink(api)}>
                      复制路由链接
                    </button>
                  </div>
                  <div className="apiMeta">
                    <span>响应：{api.responseType}</span>
                    <span>请求体：{api.requestType ?? "无"}</span>
                    <span>{api.filePath}:{api.startLine ?? "?"}</span>
                  </div>
                  {api.securityAnnotations.length > 0 ? (
                    <div className="apiSecurity">
                      {api.securityAnnotations.map((annotation) => <span className="pill" key={annotation}>{annotation}</span>)}
                    </div>
                  ) : null}
                  <div className="apiParams">
                    {api.parameters.length === 0 ? <span className="mutedChip">无参数</span> : api.parameters.map((parameter) => (
                      <span className="paramChip" key={`${parameter.source}.${parameter.name}.${parameter.type}`}>
                        <strong>{parameter.source}</strong> {parameter.name}: {parameter.type} {parameterHint(parameter)}
                      </span>
                    ))}
                  </div>
                  <div className="apiServiceCalls">
                    {api.serviceCalls.length === 0 ? <span className="mutedChip">无 Service 调用</span> : api.serviceCalls.map((call) => (
                      <span className="serviceCallChip" key={`${call.receiverName}.${call.serviceType}.${call.methodName}.${call.line ?? "?"}`}>
                        <strong>{call.serviceType}.{call.methodName}</strong> 通过 {call.receiverName}
                        {call.line === null ? "" : `:${call.line}`}
                        {call.downstreamCalls.map((downstream) => (
                          <em key={`${downstream.receiverName}.${downstream.componentType}.${downstream.methodName}.${downstream.line ?? "?"}`}>
                            -&gt; {downstream.componentType}.{downstream.methodName} 通过 {downstream.receiverName}
                            {downstream.line === null ? "" : `:${downstream.line}`}
                          </em>
                        ))}
                      </span>
                    ))}
                  </div>
                  <div className="apiRiskHints">
                    {api.riskHints.length === 0 ? <span className="mutedChip">无风险提示</span> : api.riskHints.map((hint) => (
                      <span className="riskHintChip" data-severity={hint.severity} key={`${hint.severity}.${hint.code}`}>
                        <strong>{hint.severity}</strong> {hint.code}: {hint.message}
                        {hint.details.length === 0 ? null : <em>细节：{hint.details.join(", ")}</em>}
                      </span>
                    ))}
                  </div>
                </article>
              ))}
            </div>
          </section>

          <form className="searchBar" onSubmit={onSearch}>
            <label className="fieldLabel" htmlFor="codeSearch">代码搜索</label>
            <div>
              <input
                id="codeSearch"
                value={codeSearchQuery}
                onChange={(event) => setCodeSearchQuery(event.target.value)}
                placeholder="搜索符号、摘要或代码片段"
              />
              <button className="primaryButton" type="submit" disabled={busy}>搜索</button>
            </div>
          </form>

          <div className="searchResults">
            {insight.search === null ? <EmptyText text="搜索后可查看已索引代码片段。" /> : insight.search.results.length === 0 ? (
              <EmptyText text={`没有已索引片段匹配 “${insight.search.query}”。`} />
            ) : insight.search.results.map((result) => (
              <article className="searchResult" key={result.chunkId}>
                <div className="sectionHeader">
                  <div>
                    <strong>{result.qualifiedName ?? result.symbolName ?? result.filePath}</strong>
                    <span>{result.filePath}:{result.startLine ?? "?"}-{result.endLine ?? "?"}</span>
                  </div>
                  <Badge value={result.symbolType ?? result.chunkType} />
                </div>
                {result.summary ? <p>{result.summary}</p> : null}
                <pre className="codePreview">{result.preview}</pre>
              </article>
            ))}
          </div>
        </>
      ) : <EmptyText text="创建或选择项目后可查看仓库结构。" />}
    </section>
  );
}

function TaskSummary({
  task,
  project,
  canRun,
  canCancel,
  canPreparePr,
  preparePrActionLabel,
  streamLabel,
  busy,
  onRun,
  onCancel,
  onPreparePr
}: {
  task: AgentTask | null;
  project: Project | null;
  canRun: boolean;
  canCancel: boolean;
  canPreparePr: boolean;
  preparePrActionLabel: string;
  streamLabel: string | null;
  busy: boolean;
  onRun: () => void;
  onCancel: () => void;
  onPreparePr: () => void;
}) {
  return (
    <section className="panel">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">任务详情</p>
          <h2>{task ? `#${task.id} ${task.title}` : "选择一个任务"}</h2>
        </div>
        <div className="headerBadges">
          {streamLabel === null ? null : <span className="pill">{streamLabel}</span>}
          {task ? <Badge value={task.status} /> : null}
        </div>
      </div>
      {task ? (
        <>
          <p className="description">{task.description}</p>
          <div className="metaGrid">
            <Meta label="项目" value={project ? `#${project.id} ${project.repoFullName}` : `#${task.projectId}`} />
            <Meta label="运行" value={task.currentRunId === null ? "未运行" : `#${task.currentRunId}`} />
            <Meta label="类型" value={task.taskType} />
            <Meta label="创建时间" value={formatDate(task.createdAt)} />
          </div>
          <div className="buttonRow">
            <button className="primaryButton" type="button" onClick={onRun} disabled={!canRun || busy}>运行任务</button>
            <button className="ghostButton dangerButton" type="button" onClick={onCancel} disabled={!canCancel || busy}>取消任务</button>
            <button className="primaryButton" type="button" onClick={onPreparePr} disabled={!canPreparePr || busy}>{preparePrActionLabel}</button>
          </div>
        </>
      ) : <EmptyText text="从列表里选择一个 Agent 任务。" />}
    </section>
  );
}

function StepTimeline({ steps, task }: { steps: AgentStep[]; task: AgentTask | null }) {
  return (
    <section className="panel">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">执行过程</p>
          <h2>Agent 步骤时间线</h2>
        </div>
        {task && runningStatuses.has(task.status) ? <span className="pill">运行中</span> : null}
      </div>
      {steps.length === 0 ? <EmptyText text="还没有记录执行步骤。" /> : (
        <ol className="timeline richTimeline">
          {steps.map((step) => (
            <li className={step.status === "SUCCESS" ? "done" : step.status === "FAILED" ? "failed" : ""} key={step.id}>
              <span />
              <div>
                <strong>{step.stepName}</strong>
                <small>{step.status} {step.finishedAt ? `- ${formatDate(step.finishedAt)}` : ""}</small>
                {step.errorMessage ? <em>{step.errorMessage}</em> : null}
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function AgentEvidencePanel({
  steps,
  runReport,
  runReportSnapshots,
  actionStatus,
  busy,
  onCopyRunReport,
  onDownloadRunReport,
  onSaveRunReportSnapshot,
  onCopyRunReportSnapshot,
  onDownloadRunReportSnapshot
}: {
  steps: AgentStep[];
  runReport: AgentRunReport | null;
  runReportSnapshots: AgentRunReportSnapshotSummary[];
  actionStatus: string | null;
  busy: boolean;
  onCopyRunReport: () => void;
  onDownloadRunReport: () => void;
  onSaveRunReportSnapshot: () => void;
  onCopyRunReportSnapshot: (snapshotId: number) => void;
  onDownloadRunReportSnapshot: (snapshotId: number) => void;
}) {
  const evidence = useMemo(() => agentEvidenceFromSteps(steps), [steps]);
  return (
    <section className="panel" id="agent-evidence">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">证据链</p>
          <h2>Agent 执行证据</h2>
        </div>
        <span className="pill">{evidence.length} 份证据</span>
      </div>
      <div className="buttonRow evidenceActions">
        <button className="ghostButton" type="button" onClick={onCopyRunReport} disabled={runReport === null || busy}>
          复制当前报告
        </button>
        <button className="ghostButton" type="button" onClick={onDownloadRunReport} disabled={runReport === null || busy}>
          下载当前报告
        </button>
        <button className="ghostButton" type="button" onClick={onSaveRunReportSnapshot} disabled={runReport === null || busy}>
          保存报告快照
        </button>
        <span className="runReportActionStatus" aria-live="polite">{actionStatus ?? ""}</span>
      </div>
      <div className="runReportSnapshotList" aria-label="运行报告快照">
        <div className="sectionHeader runReportSnapshotHeader">
          <h4>运行报告快照</h4>
          <span>{runReportSnapshots.length} 份最近快照</span>
        </div>
        {runReportSnapshots.length === 0 ? (
          <EmptyText text="还没有保存运行报告快照。" />
        ) : (
          <div className="runReportSnapshotRows">
            {runReportSnapshots.map((snapshot) => (
              <div className="runReportSnapshotRow" key={snapshot.id}>
                <div>
                  <strong>快照 #{snapshot.id}</strong>
                  <span>
                    Run {snapshot.runId === null ? "未知" : `#${snapshot.runId}`} · {snapshot.sectionCount} 个段落 · {formatDate(snapshot.reportGeneratedAt)}
                  </span>
                  <small>{snapshot.taskTitle}</small>
                </div>
                <span className="runReportSnapshotActions">
                  <button
                    className="ghostButton"
                    type="button"
                    onClick={() => onCopyRunReportSnapshot(snapshot.id)}
                    disabled={busy}
                  >
                    复制快照
                  </button>
                  <button
                    className="ghostButton"
                    type="button"
                    onClick={() => onDownloadRunReportSnapshot(snapshot.id)}
                    disabled={busy}
                  >
                    下载快照
                  </button>
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
      {evidence.length === 0 ? <EmptyText text="运行任务后会在这里沉淀计划、检索、补丁、测试和审查证据。" /> : (
        <div className="evidenceList">
          {evidence.map((item) => (
            <article className="evidenceItem" key={item.key}>
              <div className="sectionHeader">
                <div>
                  <strong>{item.label}</strong>
                  <span>
                    {item.stepName}
                    {item.finishedAt ? ` - ${formatDate(item.finishedAt)}` : ""}
                  </span>
                </div>
                <Badge value={item.status} />
              </div>
              <p>{item.summary}</p>
              {item.meta.length === 0 ? null : (
                <div className="evidenceMeta">
                  {item.meta.map((value, index) => <span key={`${value}-${index}`}>{value}</span>)}
                </div>
              )}
              {item.highlights.length === 0 ? null : (
                <ul className="evidenceHighlights">
                  {item.highlights.map((highlight, index) => <li key={`${highlight}-${index}`}>{highlight}</li>)}
                </ul>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function ToolCallPanel({ toolCalls, runId }: { toolCalls: ToolCallLog[]; runId: number | null }) {
  return (
    <section className="panel" id="tool-calls">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">审计</p>
          <h2>工具调用审计</h2>
        </div>
        {runId === null ? null : <span className="pill">Run #{runId}</span>}
      </div>
      {toolCalls.length === 0 ? <EmptyText text="还没有工具调用记录。" /> : (
        <div className="toolCallList">
          {toolCalls.map((call) => (
            <article className="toolCallItem" key={call.id}>
              <div className="sectionHeader">
                <div>
                  <strong>{call.toolName}</strong>
                  <span>{formatDate(call.finishedAt)} · {call.durationMs} ms</span>
                </div>
                <Badge value={call.status} />
              </div>
              {call.errorMessage ? <div className="errorBox">{call.errorMessage}</div> : null}
              <div className="toolJsonGrid">
                <div>
                  <span>输入</span>
                  <pre className="jsonBlock">{formatJson(call.inputJson)}</pre>
                </div>
                <div>
                  <span>输出</span>
                  <pre className="jsonBlock">{formatJson(call.outputJson)}</pre>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function ModelCallPanel({ modelCalls, runId }: { modelCalls: ModelCallLog[]; runId: number | null }) {
  return (
    <section className="panel" id="model-calls">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">审计</p>
          <h2>模型调用审计</h2>
        </div>
        {runId === null ? null : <span className="pill">Run #{runId}</span>}
      </div>
      {modelCalls.length === 0 ? <EmptyText text="还没有模型调用记录。" /> : (
        <div className="toolCallList">
          {modelCalls.map((call) => (
            <article className="toolCallItem" key={call.id}>
              <div className="sectionHeader">
                <div>
                  <strong>{call.stepName}</strong>
                  <span>
                    {call.modelProvider} / {call.modelName} · {call.totalTokens} tokens · {call.durationMs} ms
                  </span>
                </div>
                <Badge value={call.status} />
              </div>
              {call.errorMessage ? <div className="errorBox">{call.errorMessage}</div> : null}
              <div className="toolJsonGrid">
                <div>
                  <span>提示词</span>
                  <pre className="jsonBlock">{formatJson(call.promptJson)}</pre>
                </div>
                <div>
                  <span>响应</span>
                  <pre className="jsonBlock">{formatJson(call.responseJson)}</pre>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function PatchPanel({ patch, review }: { patch: PatchRecord | null; review: PatchRiskReview | null }) {
  return (
    <section className="panel" id="patch">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">补丁</p>
          <h2>统一 diff</h2>
        </div>
        {patch ? <Badge value={patch.status} /> : null}
      </div>
      {patch ? (
        <>
          <div className="metaGrid compact">
            <Meta label="基线分支" value={patch.baseBranch} />
            <Meta label="目标分支" value={patch.targetBranch} />
            <Meta label="补丁" value={`#${patch.id}`} />
            <Meta label="生成模式" value={patch.generationMode} />
            <Meta label="生成来源" value={patch.generationProvider} />
            {patch.generationModel ? <Meta label="模型" value={patch.generationModel} /> : null}
          </div>
          <p className="description">{patch.summary}</p>
          <PatchRiskReviewSummary review={review} />
          <ChangedFilesSummary changedFiles={patch.changedFiles} />
          <pre className="diffBlock">{patch.diffContent}</pre>
        </>
      ) : <EmptyText text="运行任务后会生成补丁。" />}
    </section>
  );
}

function PatchRiskReviewSummary({ review }: { review: PatchRiskReview | null }) {
  if (review === null) {
    return <EmptyText text="还没有自动审查报告。" />;
  }
  return (
    <div className="patchReviewSummary" aria-label="自动审查">
      <div className="sectionHeader">
        <div>
          <h3>自动审查</h3>
          <span>{review.summary}</span>
        </div>
        <Badge value={review.riskLevel} />
      </div>
      <div className="reviewFindingList">
        {review.findings.length === 0 ? <EmptyText text="没有审查发现。" /> : review.findings.map((finding) => (
          <div className="reviewFindingRow" data-severity={finding.severity} key={`${finding.code}-${finding.filePath ?? ""}`}>
            <Badge value={finding.severity} />
            <div>
              <strong>{finding.code}</strong>
              <span>{finding.message}</span>
              {finding.filePath ? <em>{finding.filePath}</em> : null}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function ChangedFilesSummary({ changedFiles }: { changedFiles: PatchChangedFile[] }) {
  if (changedFiles.length === 0) {
    return <EmptyText text="还没有文件级 diff 摘要。" />;
  }
  const totals = changedFiles.reduce(
    (summary, file) => ({
      addedLines: summary.addedLines + file.addedLines,
      deletedLines: summary.deletedLines + file.deletedLines
    }),
    { addedLines: 0, deletedLines: 0 }
  );
  return (
    <div className="changedFilesSummary" aria-label="变更文件">
      <div className="sectionHeader">
        <div>
          <h3>变更文件</h3>
          <span>{changedFiles.length} 个文件 · +{totals.addedLines} / -{totals.deletedLines}</span>
        </div>
      </div>
      <div className="changedFileList">
        {changedFiles.map((file) => (
          <div className="changedFileRow" key={`${file.oldPath ?? ""}->${file.path}`}>
            <Badge value={file.changeType} />
            <strong>{file.path}</strong>
            <span className="lineDelta">+{file.addedLines} / -{file.deletedLines}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function TestPanel({ testRun }: { testRun: TestRun | null }) {
  return (
    <section className="panel">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">沙箱</p>
          <h2>Maven 测试运行</h2>
        </div>
        {testRun ? <Badge value={testRun.status} /> : null}
      </div>
      {testRun ? (
        <>
          <div className="metaGrid compact">
            <Meta label="退出码" value={String(testRun.exitCode)} />
            <Meta label="耗时" value={`${testRun.durationMs} ms`} />
            <Meta label="测试运行" value={`#${testRun.id}`} />
          </div>
          <pre className="logBlock">{testRun.logExcerpt || "没有日志输出。"}</pre>
        </>
      ) : <EmptyText text="还没有沙箱测试运行。" />}
    </section>
  );
}

function ApprovalPanel({
  canApprove,
  canRegenerate,
  patch,
  approvals,
  approvalComment,
  rejectComment,
  setApprovalComment,
  setRejectComment,
  busy,
  onApprove,
  onReject,
  onRegenerate
}: {
  canApprove: boolean;
  canRegenerate: boolean;
  patch: PatchRecord | null;
  approvals: ApprovalRecord[];
  approvalComment: string;
  rejectComment: string;
  setApprovalComment: (value: string) => void;
  setRejectComment: (value: string) => void;
  busy: boolean;
  onApprove: () => void;
  onReject: () => void;
  onRegenerate: () => void;
}) {
  return (
    <section className="panel">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">人工审批</p>
          <h2>人工闸口</h2>
        </div>
      </div>
      <TextField label="审批备注" value={approvalComment} onChange={setApprovalComment} />
      <TextField label="拒绝原因" value={rejectComment} onChange={setRejectComment} />
      <div className="buttonRow">
        <button className="primaryButton" type="button" onClick={onApprove} disabled={!canApprove || patch === null || busy}>
          通过审批
        </button>
        <button className="ghostButton" type="button" onClick={onReject} disabled={!canApprove || !rejectComment.trim() || busy}>
          拒绝
        </button>
        <button className="ghostButton" type="button" onClick={onRegenerate} disabled={!canRegenerate || busy}>
          重新生成
        </button>
      </div>
      <div className="historyList">
        {approvals.length === 0 ? <EmptyText text="还没有审批记录。" /> : approvals.map((approval) => (
          <div className="historyItem" key={approval.id}>
            <strong>{approval.action}</strong>
            <span>{approval.comment || "没有备注"}</span>
            <small>{formatDate(approval.createdAt)}</small>
          </div>
        ))}
      </div>
    </section>
  );
}

type PullRequestFailureExplanation = {
  title: string;
  reason: string;
  nextStep: string;
  originalMessage: string;
};

function explainPullRequestFailure(
  pullRequest: PullRequestRecord,
  preflight: PullRequestPreflight | null
): PullRequestFailureExplanation | null {
  if (pullRequest.status !== "FAILED" && !pullRequest.errorMessage) {
    return null;
  }
  const originalMessage = pullRequest.errorMessage || "远端 PR 发布没有返回具体错误。";
  const normalized = originalMessage.toLowerCase();
  let title = "PR 发布失败";
  let reason = "RepoPilot 已保留本地分支和提交，远端发布还没有完成。";
  let nextStep = "处理错误原因后，可以在任务详情里点击“重试发布 PR”。";

  if (normalized.includes("token") || originalMessage.includes("尚未配置 token")) {
    title = "远端发布缺少 GitHub token";
    reason = "已启用远端 GitHub 发布，但后端运行环境还没有可用的 GitHub token。";
    nextStep = "配置 REPOPILOT_GITHUB_TOKEN 或 GITHUB_TOKEN 后，重启后端并点击“重试发布 PR”。";
  } else if (originalMessage.includes("GITHUB_BRANCH_PUSH_FAILED") || normalized.includes("push")) {
    title = "target branch 推送失败";
    reason = "RepoPilot 已准备本地分支和提交，但推送到 origin 时失败。";
    nextStep = "检查仓库 origin、分支权限和网络后，再点击“重试发布 PR”。";
  } else if (originalMessage.includes("GITHUB_PR_CREATE_FAILED") || originalMessage.includes("GitHub 返回 HTTP")) {
    title = "GitHub PR 创建失败";
    reason = "target branch 已进入远端发布流程，但 GitHub PR API 没有成功创建 PR。";
    nextStep = "检查 GitHub token 权限、API base URL、仓库权限和返回状态后，再点击“重试发布 PR”。";
  }

  if (preflight && !preflight.canPrepare && preflight.blockers.length > 0) {
    nextStep = "先处理发布前置检查阻塞项：" + preflight.blockers.join(" ");
  }

  return { title, reason, nextStep, originalMessage };
}

function PullRequestFailureNotice({ failure }: { failure: PullRequestFailureExplanation }) {
  return (
    <div className="prFailureBox" role="alert">
      <strong>{failure.title}</strong>
      <span>{failure.reason}</span>
      <span>{failure.nextStep}</span>
      <code>{failure.originalMessage}</code>
    </div>
  );
}

function PullRequestPanel({
  pullRequest,
  preflight
}: {
  pullRequest: PullRequestRecord | null;
  preflight: PullRequestPreflight | null;
}) {
  const failure = pullRequest ? explainPullRequestFailure(pullRequest, preflight) : null;
  return (
    <section className="panel" id="pr">
      <div className="panelHeader">
        <div>
          <p className="eyebrow">拉取请求</p>
          <h2>PR 准备结果</h2>
        </div>
        {pullRequest ? <Badge value={pullRequest.status} /> : null}
      </div>
      {preflight ? <PullRequestPreflightSummary preflight={preflight} /> : null}
      {pullRequest ? (
        <>
          <div className="metaGrid">
            <Meta label="分支" value={pullRequest.targetBranch ?? "未准备"} />
            <Meta label="提交" value={shortSha(pullRequest.commitSha)} />
            <Meta label="PR" value={pullRequest.prNumber === null ? "未打开" : `#${pullRequest.prNumber}`} />
            <Meta label="打开时间" value={pullRequest.openedAt ? formatDate(pullRequest.openedAt) : "未打开"} />
          </div>
          {pullRequest.url ? <a className="externalLink" href={pullRequest.url} target="_blank" rel="noreferrer">打开 PR</a> : null}
          {failure ? <PullRequestFailureNotice failure={failure} /> : null}
          <pre className="logBlock">{pullRequest.body || "还没有 PR 正文记录。"}</pre>
        </>
      ) : <EmptyText text="补丁测试通过并完成审批后，就可以准备 PR。" />}
    </section>
  );
}

function PullRequestPreflightSummary({ preflight }: { preflight: PullRequestPreflight }) {
  return (
    <section className="prPreflight" aria-label="PR 准备前置检查">
      <div className="sectionHeader">
        <h3>发布前置检查</h3>
        <Badge value={preflight.canPrepare ? "READY" : "BLOCKED"} />
      </div>
      <div className="metaGrid compact">
        <Meta label="发布模式" value={preflight.publishMode} />
        <Meta label="本地草稿" value={preflight.localDraftReady ? "已就绪" : "等待中"} />
        <Meta label="远端发布" value={preflight.remotePublishingWillRun ? "会发布" : "不发布"} />
      </div>
      <div className="preflightCheckList">
        {preflight.checks.map((check) => (
          <div className="preflightCheckRow" data-status={check.status} key={check.code}>
            <Badge value={check.status} />
            <div>
              <strong>{check.label}</strong>
              <span>{check.message}</span>
            </div>
          </div>
        ))}
      </div>
      {preflight.blockers.length > 0 ? (
        <div className="errorBox">
          PR 准备阻塞项：{preflight.blockers.join(" ")}
        </div>
      ) : null}
    </section>
  );
}

function TextField({
  label,
  value,
  onChange,
  type = "text"
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
}) {
  const reactId = useId();
  const id = `${label.toLowerCase().replace(/[^a-z0-9]+/g, "-")}-${reactId.replace(/:/g, "")}`;
  return (
    <>
      <label className="fieldLabel" htmlFor={id}>{label}</label>
      <input id={id} type={type} value={value} onChange={(event) => onChange(event.target.value)} />
    </>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div className="metaItem">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function Badge({ value }: { value: string }) {
  return <span className="badge" data-tone={toneFor(value)}>{value}</span>;
}

function EmptyText({ text }: { text: string }) {
  return <p className="emptyText">{text}</p>;
}

function agentEvidenceFromSteps(steps: AgentStep[]): AgentEvidenceItem[] {
  const items: AgentEvidenceItem[] = [];

  const planStep = latestStepByName(steps, "plan_task");
  if (planStep) {
    const output = parseJsonObject(planStep.outputJson);
    const planSteps = objectArrayField(output, "steps");
    const searchQueries = stringArrayField(output, "searchQueries");
    items.push({
      key: "plan",
      label: "任务规划",
      stepName: planStep.stepName,
      status: planStep.status,
      finishedAt: planStep.finishedAt,
      summary: stringField(output, "summary") ?? "Planner 已生成实现计划。",
      meta: searchQueries.length > 0 ? [`检索词：${summarizeList(searchQueries, 3)}`] : [],
      highlights: planSteps.slice(0, 5).map((step, index) => {
        const order = numberField(step, "order") ?? index + 1;
        const title = stringField(step, "title") ?? "计划步骤";
        const reason = stringField(step, "reason");
        return reason ? `${order}. ${title} - ${reason}` : `${order}. ${title}`;
      })
    });
  }

  const retrievalStep = latestStepByName(steps, "retrieve_context");
  if (retrievalStep) {
    const output = parseJsonObject(retrievalStep.outputJson);
    const results = objectArrayField(output, "results");
    const queries = stringArrayField(output, "queries");
    const counts = objectField(output, "resultCountByQuery");
    items.push({
      key: "retrieval",
      label: "检索到的代码上下文",
      stepName: retrievalStep.stepName,
      status: retrievalStep.status,
      finishedAt: retrievalStep.finishedAt,
      summary: `通过 ${queries.length} 个检索词命中 ${results.length} 个去重代码片段。`,
      meta: counts ? Object.entries(counts).slice(0, 4).map(([query, count]) => `${query}: ${String(count)}`) : [],
      highlights: results.slice(0, 5).map((result) => {
        const filePath = stringField(result, "filePath") ?? "未知文件";
        const qualifiedName = stringField(result, "qualifiedName");
        const summary = stringField(result, "summary") ?? stringField(result, "preview");
        const range = sourceRange(result);
        const subject = qualifiedName ? `${filePath}${range} (${qualifiedName})` : `${filePath}${range}`;
        return summary ? `${subject} - ${truncateText(summary, 140)}` : subject;
      })
    });
  }

  const patchStep = latestStepByName(steps, "generate_patch");
  if (patchStep) {
    const output = parseJsonObject(patchStep.outputJson);
    const patchId = numberField(output, "patchId");
    const mode = stringField(output, "generationMode");
    const provider = stringField(output, "generationProvider");
    const model = stringField(output, "generationModel");
    items.push({
      key: "patch",
      label: "生成的补丁产物",
      stepName: patchStep.stepName,
      status: patchStep.status,
      finishedAt: patchStep.finishedAt,
      summary: stringField(output, "summary") ?? "Coder 已生成补丁产物。",
      meta: compactStrings([
        patchId ? `补丁 #${patchId}` : null,
        stringField(output, "status"),
        mode,
        provider,
        model ? `模型：${model}` : null,
        branchPair(output)
      ]),
      highlights: compactStrings([
        mode ? `生成模式：${mode}` : null,
        provider ? `生成来源：${provider}` : null,
        model ? `模型：${model}` : null
      ])
    });
  }

  const safetyStep = latestStepByName(steps, "validate_patch_safety");
  if (safetyStep) {
    const output = parseJsonObject(safetyStep.outputJson);
    const safe = booleanField(output, "safe");
    items.push({
      key: "safety",
      label: "补丁安全门",
      stepName: safetyStep.stepName,
      status: safetyStep.status,
      finishedAt: safetyStep.finishedAt,
      summary: safe === null ? "补丁安全预检已完成。" : `补丁安全预检判定 diff 为${safe ? "安全" : "不安全"}。`,
      meta: compactStrings([safe === null ? null : `safe=${String(safe)}`]),
      highlights: stringArrayField(output, "reasons").slice(0, 4)
    });
  }

  const testStep = latestStepByName(steps, "run_tests");
  if (testStep) {
    const output = parseJsonObject(testStep.outputJson);
    items.push({
      key: "tests",
      label: "沙箱测试结果",
      stepName: testStep.stepName,
      status: testStep.status,
      finishedAt: testStep.finishedAt,
      summary: stringField(output, "logExcerpt") ?? "沙箱测试执行已完成。",
      meta: compactStrings([
        stringField(output, "command"),
        stringField(output, "status"),
        numberField(output, "durationMs") !== null ? `${numberField(output, "durationMs")} ms` : null,
        numberField(output, "exitCode") !== null ? `退出码 ${numberField(output, "exitCode")}` : null
      ]),
      highlights: []
    });
  }

  const reviewStep = latestStepByName(steps, "review_patch");
  if (reviewStep) {
    const output = parseJsonObject(reviewStep.outputJson);
    const findings = objectArrayField(output, "findings");
    items.push({
      key: "review",
      label: "自动补丁审查",
      stepName: reviewStep.stepName,
      status: reviewStep.status,
      finishedAt: reviewStep.finishedAt,
      summary: stringField(output, "summary") ?? "自动补丁审查已完成。",
      meta: compactStrings([stringField(output, "riskLevel")]),
      highlights: findings.length === 0
        ? ["没有自动审查发现。"]
        : findings.slice(0, 5).map((finding) => compactStrings([
          stringField(finding, "severity"),
          stringField(finding, "code"),
          stringField(finding, "message")
        ]).join(" - "))
    });
  }

  const approvalStep = latestStepByName(steps, "waiting_human_approval");
  if (approvalStep) {
    const input = parseJsonObject(approvalStep.inputJson);
    const patchId = numberField(input, "patchId");
    items.push({
      key: "approval",
      label: "人工审批检查点",
      stepName: approvalStep.stepName,
      status: approvalStep.status,
      finishedAt: approvalStep.finishedAt,
      summary: "本次运行已在创建 PR 前暂停，等待人工审查并审批补丁。",
      meta: compactStrings([patchId ? `补丁 #${patchId}` : null, stringField(input, "status")]),
      highlights: []
    });
  }

  return items;
}

function latestStepByName(steps: AgentStep[], stepName: string) {
  return [...steps].reverse().find((step) => step.stepName === stepName) ?? null;
}

function parseJsonObject(value: string | null): JsonObject {
  if (!value) {
    return {};
  }
  try {
    const parsed = JSON.parse(value);
    return isJsonObject(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function objectField(object: JsonObject, key: string): JsonObject | null {
  const value = object[key];
  return isJsonObject(value) ? value : null;
}

function objectArrayField(object: JsonObject, key: string): JsonObject[] {
  const value = object[key];
  return Array.isArray(value) ? value.filter(isJsonObject) : [];
}

function stringArrayField(object: JsonObject, key: string): string[] {
  const value = object[key];
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function stringField(object: JsonObject, key: string): string | null {
  const value = object[key];
  return typeof value === "string" && value.trim() ? value : null;
}

function numberField(object: JsonObject, key: string): number | null {
  const value = object[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function booleanField(object: JsonObject, key: string): boolean | null {
  const value = object[key];
  return typeof value === "boolean" ? value : null;
}

function compactStrings(values: Array<string | null | undefined>): string[] {
  return values.filter((value): value is string => typeof value === "string" && value.trim().length > 0);
}

function summarizeList(values: string[], maxItems: number) {
  const visible = values.slice(0, maxItems).join(", ");
  return values.length > maxItems ? `${visible} +${values.length - maxItems} more` : visible;
}

function truncateText(value: string, maxLength: number) {
  return value.length > maxLength ? `${value.slice(0, maxLength - 1)}...` : value;
}

function sourceRange(object: JsonObject) {
  const start = numberField(object, "startLine");
  const end = numberField(object, "endLine");
  if (start === null) {
    return "";
  }
  return end === null || end === start ? `:${start}` : `:${start}-${end}`;
}

function branchPair(object: JsonObject) {
  const base = stringField(object, "baseBranch");
  const target = stringField(object, "targetBranch");
  return base && target ? `${base} -> ${target}` : null;
}

async function optional<T>(loader: () => Promise<T>): Promise<T | null> {
  try {
    return await loader();
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}

function reviewReportFromSteps(steps: AgentStep[]): PatchRiskReview | null {
  const reviewStep = [...steps].reverse().find((step) => step.stepName === "review_patch" && step.outputJson);
  if (!reviewStep?.outputJson) {
    return null;
  }
  try {
    const parsed = JSON.parse(reviewStep.outputJson) as Partial<PatchRiskReview>;
    if (typeof parsed.riskLevel !== "string" || typeof parsed.summary !== "string" || !Array.isArray(parsed.findings)) {
      return null;
    }
    const findings = parsed.findings as unknown[];
    return {
      riskLevel: parsed.riskLevel,
      summary: parsed.summary,
      findings: findings.flatMap((finding) => {
        if (typeof finding !== "object" || finding === null) {
          return [];
        }
        const value = finding as Partial<PatchRiskFinding>;
        return [{
          severity: typeof value.severity === "string" ? value.severity : "INFO",
          code: typeof value.code === "string" ? value.code : "REVIEW_FINDING",
          message: typeof value.message === "string" ? value.message : "Review finding",
          filePath: typeof value.filePath === "string" ? value.filePath : null
        }];
      })
    };
  } catch {
    return null;
  }
}

function formatError(error: unknown): string {
  if (error instanceof ApiError) {
    return `${error.code ?? error.status}: ${error.message}`;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return "Unexpected error";
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === "AbortError";
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(value));
}

function formatDuration(seconds: number) {
  if (seconds <= 0) return "0s";
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.round((seconds % 3600) / 60);
  return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
}

function formatJson(value: string | null) {
  if (!value) return "null";
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

function formatSize(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function parameterHint(parameter: { required: boolean; defaultValue: string | null }) {
  if (parameter.defaultValue !== null) return `默认 ${parameter.defaultValue}`;
  return parameter.required ? "必填" : "可选";
}

function toneFor(value: string | undefined) {
  if (!value) return "neutral";
  if (["DONE", "SUCCESS", "PASSED", "APPROVED", "OPEN", "READY", "PASS"].includes(value)) return "good";
  if (["FAILED", "FAILED_TEST", "FAILED_PR_CREATION", "REJECTED", "CANCELLED", "BLOCKED"].includes(value)) return "bad";
  if (terminalStatuses.has(value)) return "neutral";
  return "warn";
}

function shortSha(value: string | null) {
  return value ? value.slice(0, 10) : "未提交";
}
