import re
import time
from typing import Any, Optional

from app.clients.backend_api import BackendApiClient
from app.graph.runner import WorkerGraphNode, WorkerGraphRunner
from app.schemas import (
    AgentModelCallRecordRequest,
    AgentPatchRecordRequest,
    AgentRunStartRequest,
    AgentStepRecordRequest,
)

QUERY_TOKEN_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_]{2,}|[\u4e00-\u9fff]{2,}")
SEARCH_LIMIT_PER_QUERY = 4
MAX_RETRIEVED_RESULTS = 12
MAX_READ_FILES = 3
CONTENT_PREVIEW_CHARS = 1200
SEARCH_PREVIEW_CHARS = 500
PATCH_GENERATION_MODE = "WORKER_SAFE_PLANNING_DRAFT"
PATCH_GENERATION_PROVIDER = "AGENT_WORKER"
PATCH_GENERATION_MODEL = "worker-retrieval-plan-v1"


def run_initial_nodes_safely(run_id: int, request: AgentRunStartRequest) -> None:
    client = BackendApiClient()
    current_step = "load_task_context"

    def update_current_step(step_name: str) -> None:
        nonlocal current_step
        current_step = step_name

    try:
        build_initial_graph(run_id, request, client).run(on_node_start=update_current_step)
    except Exception as error:  # noqa: BLE001 - background task must not crash the worker process
        try:
            client.record_step(
                run_id,
                AgentStepRecordRequest(
                    step_name=current_step,
                    status="FAILED",
                    input={"runId": run_id, "source": "agent-worker"},
                    error_message=str(error)[:4000],
                ),
            )
        except Exception:
            return


def build_initial_graph(
    run_id: int,
    request: AgentRunStartRequest,
    client: BackendApiClient,
) -> WorkerGraphRunner:
    return WorkerGraphRunner(
        [
            WorkerGraphNode(
                "load_task_context",
                lambda state: {
                    "loaded_context": load_task_context(run_id, request, client),
                },
            ),
            WorkerGraphNode(
                "ensure_index",
                lambda state: {
                    "index_status": ensure_index(run_id, state["loaded_context"], request, client),
                },
            ),
            WorkerGraphNode(
                "plan_task",
                lambda state: {
                    "plan_output": plan_task(
                        run_id,
                        state["loaded_context"],
                        request,
                        client,
                    ),
                },
            ),
            WorkerGraphNode(
                "retrieve_context",
                lambda state: {
                    "retrieval_output": retrieve_context(
                        run_id,
                        state["loaded_context"],
                        state["plan_output"],
                        request,
                        client,
                    ),
                },
            ),
            WorkerGraphNode(
                "generate_patch",
                lambda state: {
                    "patch_output": generate_patch(
                        run_id,
                        state["loaded_context"],
                        state["index_status"],
                        state["plan_output"],
                        state["retrieval_output"],
                        request,
                        client,
                    ),
                },
            ),
        ]
    )


def load_task_context(
    run_id: int,
    request: AgentRunStartRequest,
    client: Optional[BackendApiClient] = None,
) -> dict[str, Any]:
    backend = client or BackendApiClient()
    context = backend.load_run_context(run_id)
    files = backend.list_project_files(run_id, max_depth=5)
    symbols = backend.list_symbols(run_id)
    output = {
        "runId": context.get("runId"),
        "taskId": context.get("taskId"),
        "projectId": context.get("projectId"),
        "repoFullName": context.get("repoFullName"),
        "defaultBranch": context.get("defaultBranch"),
        "localPath": context.get("localPath"),
        "taskType": context.get("taskType"),
        "title": context.get("title"),
        "description": context.get("description"),
        "fileCount": len(files),
        "sampleFiles": sample_files(files),
        "symbolCount": len(symbols),
        "sampleSymbols": sample_symbols(symbols),
    }
    backend.record_step(
        run_id,
        AgentStepRecordRequest(
            step_name="load_task_context",
            status="SUCCESS",
            input={
                "runId": run_id,
                "taskId": request.task_id,
                "projectId": request.project_id,
                "source": "agent-worker",
            },
            output=output,
        ),
    )
    return {
        "context": context,
        "files": files,
        "symbols": symbols,
        "loadOutput": output,
    }


def ensure_index(
    run_id: int,
    loaded_context: dict[str, Any],
    request: AgentRunStartRequest,
    client: Optional[BackendApiClient] = None,
) -> dict[str, Any]:
    backend = client or BackendApiClient()
    context = loaded_context["context"]
    files = list(loaded_context.get("files") or [])
    symbols = list(loaded_context.get("symbols") or [])
    file_paths = [str(file.get("path") or "") for file in files]
    java_files = [path for path in file_paths if path.endswith(".java")]
    test_files = [path for path in file_paths if "/test/" in path or path.startswith("src/test/")]
    controller_symbols = [
        symbol for symbol in symbols if str(symbol.get("symbolType") or "").upper() == "CONTROLLER"
    ]
    service_symbols = [
        symbol for symbol in symbols if str(symbol.get("symbolType") or "").upper() == "SERVICE"
    ]
    index_ready = bool(files) and bool(java_files or symbols)
    missing_signals = []
    if not files:
        missing_signals.append("files")
    if not java_files and not symbols:
        missing_signals.append("javaFilesOrSymbols")

    output = {
        "summary": index_summary(context, index_ready, files, java_files, symbols),
        "indexReady": index_ready,
        "fileCount": len(files),
        "javaFileCount": len(java_files),
        "testFileCount": len(test_files),
        "symbolCount": len(symbols),
        "controllerCount": len(controller_symbols),
        "serviceCount": len(service_symbols),
        "missingSignals": missing_signals,
        "sampleJavaFiles": java_files[:8],
        "sampleControllers": sample_symbols(controller_symbols),
        "sampleServices": sample_symbols(service_symbols),
    }
    backend.record_step(
        run_id,
        AgentStepRecordRequest(
            step_name="ensure_index",
            status="SUCCESS",
            input={
                "runId": run_id,
                "taskId": request.task_id,
                "projectId": request.project_id,
                "source": "agent-worker",
            },
            output=output,
        ),
    )
    return output


def plan_task(
    run_id: int,
    loaded_context: dict[str, Any],
    request: AgentRunStartRequest,
    client: Optional[BackendApiClient] = None,
) -> dict[str, Any]:
    backend = client or BackendApiClient()
    context = loaded_context["context"]
    queries = candidate_queries(
        context.get("title"),
        context.get("description"),
        request.user_request,
    )
    search_summaries = []
    for query in queries[:3]:
        search_response = backend.search_code(run_id, query, limit=4)
        results = search_response.get("results", [])
        search_summaries.append(
            {
                "query": search_response.get("query", query),
                "resultCount": len(results),
                "topFiles": unique_values(result.get("filePath") for result in results)[:5],
                "chunkIds": [result.get("chunkId") for result in results[:5]],
            }
        )
    output = deterministic_plan(context, loaded_context, queries, search_summaries)
    backend.record_step(
        run_id,
        AgentStepRecordRequest(
            step_name="plan_task",
            status="SUCCESS",
            input={
                "runId": run_id,
                "title": context.get("title"),
                "taskType": context.get("taskType"),
                "source": "agent-worker",
            },
            output=output,
        ),
    )
    return output


def retrieve_context(
    run_id: int,
    loaded_context: dict[str, Any],
    plan_output: dict[str, Any],
    request: AgentRunStartRequest,
    client: Optional[BackendApiClient] = None,
) -> dict[str, Any]:
    backend = client or BackendApiClient()
    context = loaded_context["context"]
    queries = retrieval_queries(plan_output, context, request)
    search_runs = []
    all_results = []
    for query in queries:
        search_response = backend.search_code(run_id, query, limit=SEARCH_LIMIT_PER_QUERY)
        results = list(search_response.get("results") or [])
        all_results.extend(results)
        search_runs.append(
            {
                "query": search_response.get("query", query),
                "resultCount": len(results),
                "topFiles": unique_values(result.get("filePath") for result in results)[:5],
            }
        )

    results = summarize_search_results(dedupe_search_results(all_results)[:MAX_RETRIEVED_RESULTS])
    read_files = []
    for file_path in unique_values(result.get("filePath") for result in results)[:MAX_READ_FILES]:
        file_response = backend.read_project_file(run_id, file_path)
        read_files.append(summarize_file_content(file_response))

    output = {
        "summary": f"已检索 {len(queries)} 个 query，命中 {len(results)} 个去重代码片段，读取 {len(read_files)} 个关键文件预览。",
        "queries": queries,
        "resultCountByQuery": {search["query"]: search["resultCount"] for search in search_runs},
        "searchRuns": search_runs,
        "uniqueResultCount": len(results),
        "results": results,
        "readFiles": read_files,
    }
    backend.record_step(
        run_id,
        AgentStepRecordRequest(
            step_name="retrieve_context",
            status="SUCCESS",
            input={
                "runId": run_id,
                "queries": queries,
                "limitPerQuery": SEARCH_LIMIT_PER_QUERY,
                "source": "agent-worker",
            },
            output=output,
        ),
    )
    return output


def generate_patch(
    run_id: int,
    loaded_context: dict[str, Any],
    index_status: dict[str, Any],
    plan_output: dict[str, Any],
    retrieval_output: dict[str, Any],
    request: AgentRunStartRequest,
    client: Optional[BackendApiClient] = None,
) -> dict[str, Any]:
    backend = client or BackendApiClient()
    context = loaded_context["context"]
    started_at = time.monotonic()
    draft = deterministic_patch_draft(run_id, context, index_status, plan_output, retrieval_output, request)
    duration_ms = elapsed_ms(started_at)
    backend.record_model_call(
        run_id,
        AgentModelCallRecordRequest(
            step_name="generate_patch",
            model_provider=PATCH_GENERATION_PROVIDER,
            model_name=PATCH_GENERATION_MODEL,
            status="SUCCESS",
            prompt={
                "runId": run_id,
                "taskId": request.task_id,
                "title": context.get("title"),
                "planStepCount": len(plan_output.get("steps", [])),
                "retrievalResultCount": retrieval_output.get("uniqueResultCount", 0),
                "source": "agent-worker",
            },
            response={
                "summary": draft["summary"],
                "path": draft["path"],
                "generationMode": PATCH_GENERATION_MODE,
                "diffLineCount": len(str(draft["diffContent"]).splitlines()),
            },
            duration_ms=duration_ms,
        ),
    )
    patch_response = backend.record_patch(
        run_id,
        AgentPatchRecordRequest(
            base_branch=context.get("defaultBranch") or request.base_branch,
            target_branch=f"repopilot/task-{request.task_id}",
            diff_content=draft["diffContent"],
            summary=draft["summary"],
            generation_mode=PATCH_GENERATION_MODE,
            generation_provider=PATCH_GENERATION_PROVIDER,
            generation_model=PATCH_GENERATION_MODEL,
        ),
    )
    output = {
        "summary": draft["summary"],
        "patchId": patch_response.get("data", {}).get("id"),
        "patchStatus": patch_response.get("data", {}).get("status"),
        "baseBranch": patch_response.get("data", {}).get("baseBranch"),
        "targetBranch": patch_response.get("data", {}).get("targetBranch"),
        "generationMode": PATCH_GENERATION_MODE,
        "generationProvider": PATCH_GENERATION_PROVIDER,
        "generationModel": PATCH_GENERATION_MODEL,
        "diffPath": draft["path"],
        "diffLineCount": len(str(draft["diffContent"]).splitlines()),
        "changedFiles": patch_response.get("data", {}).get("changedFiles", []),
        "evidence": draft["evidence"],
    }
    backend.record_step(
        run_id,
        AgentStepRecordRequest(
            step_name="generate_patch",
            status="SUCCESS",
            input={
                "runId": run_id,
                "taskId": request.task_id,
                "generationMode": PATCH_GENERATION_MODE,
                "source": "agent-worker",
            },
            output=output,
        ),
    )
    patch_id = output.get("patchId")
    if patch_id is not None:
        try:
            safety_response = backend.validate_patch_safety(run_id, int(patch_id))
            safety_data = safety_response.get("data")
            output["safety"] = safety_data
            if isinstance(safety_data, dict) and safety_data.get("safe") is True:
                sandbox_response = backend.run_patch_sandbox_tests(run_id, int(patch_id))
                output["sandbox"] = sandbox_response.get("data")
        except Exception as error:  # noqa: BLE001 - post-patch gates are reported without duplicating generate_patch failure
            output["postPatchGateError"] = str(error)[:1000]
    return output


def index_summary(
    context: dict[str, Any],
    index_ready: bool,
    files: list[dict[str, Any]],
    java_files: list[str],
    symbols: list[dict[str, Any]],
) -> str:
    repo = context.get("repoFullName") or f"project#{context.get('projectId')}"
    if index_ready:
        return (
            f"已确认 {repo} 的代码索引可用："
            f"{len(files)} 个文件条目、{len(java_files)} 个 Java 文件、"
            f"{len(symbols)} 个 Java 符号。"
        )
    return (
        f"{repo} 的索引信号不足："
        f"{len(files)} 个文件条目、{len(java_files)} 个 Java 文件、"
        f"{len(symbols)} 个 Java 符号。"
    )


def deterministic_patch_draft(
    run_id: int,
    context: dict[str, Any],
    index_status: dict[str, Any],
    plan_output: dict[str, Any],
    retrieval_output: dict[str, Any],
    request: AgentRunStartRequest,
) -> dict[str, Any]:
    task_id = context.get("taskId") or request.task_id
    path = f".repopilot/task-{task_id}-worker-plan.md"
    lines = worker_patch_plan_lines(run_id, context, index_status, plan_output, retrieval_output, request)
    diff = new_file_diff(path, lines)
    return {
        "path": path,
        "summary": f"Worker 安全规划草稿：为任务 #{task_id} 生成基于检索证据的实施计划 diff。",
        "diffContent": diff,
        "evidence": {
            "planStepCount": len(plan_output.get("steps", [])),
            "retrievalQueryCount": len(retrieval_output.get("queries", [])),
            "retrievalResultCount": retrieval_output.get("uniqueResultCount", 0),
            "readFileCount": len(retrieval_output.get("readFiles", [])),
            "indexReady": index_status.get("indexReady", False),
        },
    }


def worker_patch_plan_lines(
    run_id: int,
    context: dict[str, Any],
    index_status: dict[str, Any],
    plan_output: dict[str, Any],
    retrieval_output: dict[str, Any],
    request: AgentRunStartRequest,
) -> list[str]:
    repo = context.get("repoFullName") or f"project#{context.get('projectId')}"
    lines = [
        "# RepoPilot Worker 补丁草稿",
        "",
        f"- Run ID: {run_id}",
        f"- Task ID: {context.get('taskId') or request.task_id}",
        f"- Project ID: {context.get('projectId') or request.project_id}",
        f"- 仓库: {repo}",
        f"- 任务标题: {context.get('title') or request.user_request}",
        f"- 生成模式: {PATCH_GENERATION_MODE}",
        f"- 生成来源: {PATCH_GENERATION_PROVIDER} / {PATCH_GENERATION_MODEL}",
        "",
        "## 索引状态",
        "",
        f"- indexReady: {index_status.get('indexReady', False)}",
        f"- Java 文件数: {index_status.get('javaFileCount', 0)}",
        f"- Java 符号数: {index_status.get('symbolCount', 0)}",
        f"- Controller 数: {index_status.get('controllerCount', 0)}",
        f"- Service 数: {index_status.get('serviceCount', 0)}",
        "",
        "## 实施计划",
        "",
    ]
    for step in plan_output.get("steps", []):
        expected_files = ", ".join(str(path) for path in step.get("expectedFiles", []) if path)
        suffix = f" 预期文件: {expected_files}" if expected_files else ""
        lines.append(f"{step.get('order', '-')}. {step.get('title', '未命名步骤')} - {step.get('reason', '')}{suffix}")
    lines.extend(["", "## 检索证据", ""])
    for result in retrieval_output.get("results", [])[:8]:
        location = result.get("filePath") or "unknown"
        start_line = result.get("startLine")
        end_line = result.get("endLine")
        line_suffix = f":{start_line}-{end_line}" if start_line and end_line else ""
        symbol = result.get("symbolName") or result.get("qualifiedName") or result.get("chunkType") or "code"
        lines.append(f"- `{location}{line_suffix}`: {symbol}。{text_preview(result.get('summary'), 120)}")
    if not retrieval_output.get("results"):
        lines.append("- 当前检索没有返回代码片段，后续生成真实业务 diff 前需要补充上下文。")
    lines.extend(["", "## 已读取关键文件", ""])
    for file in retrieval_output.get("readFiles", []):
        lines.append(f"- `{file.get('path')}`，大小 {file.get('size', 0)} 字节。")
    if not retrieval_output.get("readFiles"):
        lines.append("- 暂无关键文件预览。")
    lines.extend(
        [
            "",
            "## 后续验证门槛",
            "",
            "1. 真实业务 diff 必须先通过 `validate_patch_safety`。",
            "2. 补丁必须在 Docker 沙箱中执行 `mvn -q test`。",
            "3. 风险审查通过后才能进入人工审批暂停点。",
            "4. 人工审批通过后才允许创建远端 Pull Request。",
            "",
        ]
    )
    return lines


def new_file_diff(path: str, lines: list[str]) -> str:
    diff_lines = [
        f"diff --git a/{path} b/{path}",
        "new file mode 100644",
        "index 0000000..1111111",
        "--- /dev/null",
        f"+++ b/{path}",
        f"@@ -0,0 +1,{len(lines)} @@",
    ]
    diff_lines.extend(f"+{line}" for line in lines)
    return "\n".join(diff_lines) + "\n"


def elapsed_ms(started_at: float) -> int:
    return max(0, int((time.monotonic() - started_at) * 1000))


def candidate_queries(*values: Optional[str]) -> list[str]:
    queries: list[str] = []
    for value in values:
        add_query(queries, value)
        if value:
            for match in QUERY_TOKEN_PATTERN.findall(value):
                if len(match) >= 4 or "user" in match.lower():
                    add_query(queries, match)
    return queries[:8]


def deterministic_plan(
    context: dict[str, Any],
    loaded_context: dict[str, Any],
    queries: list[str],
    search_summaries: list[dict[str, Any]],
) -> dict[str, Any]:
    repo = context.get("repoFullName") or f"project#{context.get('projectId')}"
    sample_file_paths = [file["path"] for file in loaded_context.get("loadOutput", {}).get("sampleFiles", [])]
    controller_files = [path for path in sample_file_paths if "Controller" in path][:3]
    expected_files = controller_files or sample_file_paths[:3]
    return {
        "summary": f"Worker 已为 {repo} 准备实现计划：{context.get('title')}",
        "steps": [
            {
                "order": 1,
                "title": "确认任务与项目上下文",
                "reason": "确保后续修改绑定到当前 run 对应的 task/project。",
                "expectedFiles": [],
            },
            {
                "order": 2,
                "title": "定位相关 Controller/Service/Mapper 代码",
                "reason": "先沿现有 Spring 分层风格寻找最小修改点。",
                "expectedFiles": expected_files,
            },
            {
                "order": 3,
                "title": "生成最小 unified diff",
                "reason": "优先保持接口、业务逻辑和测试一起演进。",
                "expectedFiles": expected_files,
            },
            {
                "order": 4,
                "title": "执行补丁安全预检",
                "reason": "拒绝越权路径、二进制补丁和保留目录修改。",
                "expectedFiles": [],
            },
            {
                "order": 5,
                "title": "在 Docker 沙箱运行 Maven 测试",
                "reason": "只有测试通过后才进入人工审批暂停点。",
                "expectedFiles": [],
            },
        ],
        "searchQueries": queries,
        "searchResults": search_summaries,
        "testStrategy": "生成补丁后先执行 diff 安全预检，再在 Docker 沙箱运行 mvn -q test。",
    }


def sample_files(files: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "path": file.get("path"),
            "type": file.get("type"),
            "size": file.get("size"),
        }
        for file in files[:12]
    ]


def sample_symbols(symbols: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "filePath": symbol.get("filePath"),
            "symbolType": symbol.get("symbolType"),
            "name": symbol.get("name"),
            "qualifiedName": symbol.get("qualifiedName"),
            "startLine": symbol.get("startLine"),
            "endLine": symbol.get("endLine"),
        }
        for symbol in symbols[:12]
    ]


def retrieval_queries(
    plan_output: dict[str, Any],
    context: dict[str, Any],
    request: AgentRunStartRequest,
) -> list[str]:
    queries: list[str] = []
    for query in plan_output.get("searchQueries", []):
        if query is not None:
            add_query(queries, str(query))
    if not queries:
        queries = candidate_queries(context.get("title"), context.get("description"), request.user_request)
    return queries[:5]


def dedupe_search_results(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    deduped = []
    seen = set()
    for result in results:
        key = result_identity(result)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(result)
    return deduped


def result_identity(result: dict[str, Any]) -> tuple[Any, ...]:
    chunk_id = result.get("chunkId")
    if chunk_id is not None:
        return ("chunk", chunk_id)
    return (
        "location",
        result.get("filePath"),
        result.get("startLine"),
        result.get("endLine"),
        result.get("symbolName"),
    )


def summarize_search_results(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "chunkId": result.get("chunkId"),
            "filePath": result.get("filePath"),
            "chunkType": result.get("chunkType"),
            "symbolType": result.get("symbolType"),
            "symbolName": result.get("symbolName"),
            "qualifiedName": result.get("qualifiedName"),
            "startLine": result.get("startLine"),
            "endLine": result.get("endLine"),
            "summary": result.get("summary"),
            "preview": text_preview(result.get("preview"), SEARCH_PREVIEW_CHARS),
        }
        for result in results
    ]


def summarize_file_content(file_response: dict[str, Any]) -> dict[str, Any]:
    content = file_response.get("content")
    return {
        "path": file_response.get("path"),
        "size": file_response.get("size"),
        "contentPreview": text_preview(content, CONTENT_PREVIEW_CHARS),
    }


def text_preview(value: Any, limit: int) -> str:
    if value is None:
        return ""
    text = str(value).replace("\r\n", "\n")
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "\n..."


def add_query(queries: list[str], value: Optional[str]) -> None:
    if value:
        normalized = value.strip()
        if normalized and normalized not in queries:
            queries.append(normalized)


def unique_values(values: Any) -> list[Any]:
    unique = []
    for value in values:
        if value is not None and value not in unique:
            unique.append(value)
    return unique
