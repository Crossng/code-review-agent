import re
from typing import Any, Optional

from app.clients.backend_api import BackendApiClient
from app.schemas import AgentRunStartRequest, AgentStepRecordRequest

QUERY_TOKEN_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_]{2,}|[\u4e00-\u9fff]{2,}")


def run_initial_nodes_safely(run_id: int, request: AgentRunStartRequest) -> None:
    client = BackendApiClient()
    current_step = "load_task_context"
    try:
        context = load_task_context(run_id, request, client)
        current_step = "plan_task"
        plan_task(run_id, context, request, client)
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
