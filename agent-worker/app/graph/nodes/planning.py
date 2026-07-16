import json
import re
from typing import Any, Optional

from app.clients.backend_api import BackendApiClient
from app.clients.model_client import WorkerModelClient
from app.graph.nodes.common import add_query, text_preview, unique_values
from app.schemas import AgentModelCallRecordRequest, AgentRunStartRequest, AgentStepRecordRequest

QUERY_TOKEN_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_]{2,}|[\u4e00-\u9fff]{2,}")
SEARCH_LIMIT_PER_QUERY = 4
MAX_RETRIEVED_RESULTS = 12
MAX_READ_FILES = 3
CONTENT_PREVIEW_CHARS = 1200
SEARCH_PREVIEW_CHARS = 500
MODEL_PLAN_PREVIEW_CHARS = 2000
MAX_MODEL_PLAN_STEPS = 5
MAX_MODEL_SEARCH_QUERIES = 5


def plan_task(
    run_id: int,
    loaded_context: dict[str, Any],
    request: AgentRunStartRequest,
    client: Optional[BackendApiClient] = None,
    model_client: Optional[WorkerModelClient] = None,
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
    model_prompt = build_plan_model_prompt(context, loaded_context, request, queries, search_summaries, output)
    model_result = (model_client or WorkerModelClient()).generate_text("plan_task", model_prompt)
    if model_result is not None:
        model_plan = parse_model_plan(model_result.text)
        output["modelPlanText"] = text_preview(model_plan["summary"], MODEL_PLAN_PREVIEW_CHARS)
        output["modelProvider"] = model_result.provider
        output["modelName"] = model_result.model
        output["modelPlan"] = model_plan
        backend.record_model_call(
            run_id,
            AgentModelCallRecordRequest(
                step_name="plan_task",
                model_provider=model_result.provider,
                model_name=model_result.model,
                status="SUCCESS",
                prompt=model_result.prompt,
                response=model_result.response,
                prompt_tokens=model_result.prompt_tokens,
                completion_tokens=model_result.completion_tokens,
                total_tokens=model_result.total_tokens,
                duration_ms=model_result.duration_ms,
            ),
        )
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


def build_plan_model_prompt(
    context: dict[str, Any],
    loaded_context: dict[str, Any],
    request: AgentRunStartRequest,
    queries: list[str],
    search_summaries: list[dict[str, Any]],
    deterministic_output: dict[str, Any],
) -> dict[str, Any]:
    index_source = loaded_context.get("indexStatus") or loaded_context.get("loadOutput", {})
    return {
        "role": "PlannerAgent",
        "language": "zh-CN",
        "instruction": "基于任务、项目索引线索和确定性计划，输出结构化中文工程实施建议。",
        "task": {
            "taskId": context.get("taskId"),
            "projectId": context.get("projectId"),
            "taskType": context.get("taskType"),
            "title": context.get("title"),
            "description": context.get("description"),
            "userRequest": request.user_request,
            "repoFullName": context.get("repoFullName"),
        },
        "index": {
            "fileCount": index_source.get("fileCount"),
            "symbolCount": index_source.get("symbolCount"),
            "sampleFiles": loaded_context.get("loadOutput", {}).get("sampleFiles", [])[:10],
        },
        "searchQueries": queries,
        "searchResults": search_summaries,
        "deterministicPlan": {
            "summary": deterministic_output.get("summary"),
            "steps": deterministic_output.get("steps"),
            "testStrategy": deterministic_output.get("testStrategy"),
        },
        "outputContract": {
            "format": "json_object",
            "schema": {
                "summary": "中文计划摘要",
                "steps": [{"order": 1, "title": "步骤标题", "reason": "工程理由", "expectedFiles": []}],
                "searchQueries": ["用于仓库检索的短 query"],
                "risks": ["风险或注意事项"],
                "testStrategy": "验证策略",
            },
            "requirements": ["使用中文", "不要输出代码块或 diff", "不要输出密钥或多个备选方案"],
        },
    }


def parse_model_plan(text: str) -> dict[str, Any]:
    parsed = parse_json_object(text)
    if parsed is None:
        return {
            "summary": text_preview(text, MODEL_PLAN_PREVIEW_CHARS),
            "steps": [],
            "searchQueries": [],
            "risks": [],
            "testStrategy": None,
            "format": "plain_text",
        }

    summary = first_text(parsed.get("summary"), parsed.get("title"), parsed.get("planSummary"))
    if summary is None:
        summary = text_preview(text, MODEL_PLAN_PREVIEW_CHARS)
    return {
        "summary": text_preview(summary, MODEL_PLAN_PREVIEW_CHARS),
        "steps": normalize_model_steps(parsed.get("steps")),
        "searchQueries": normalize_string_list(parsed.get("searchQueries"), MAX_MODEL_SEARCH_QUERIES),
        "risks": normalize_string_list(parsed.get("risks"), 5),
        "testStrategy": first_text(parsed.get("testStrategy"), parsed.get("validation")),
        "format": "json_object",
    }


def parse_json_object(text: str) -> Optional[dict[str, Any]]:
    stripped = (text or "").strip()
    if stripped.startswith("```"):
        lines = stripped.splitlines()
        if len(lines) >= 3 and lines[0].startswith("```") and lines[-1].strip() == "```":
            stripped = "\n".join(lines[1:-1]).strip()
    try:
        parsed = json.loads(stripped)
    except json.JSONDecodeError:
        return None
    if not isinstance(parsed, dict):
        return None
    return parsed


def normalize_model_steps(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    steps = []
    for index, item in enumerate(value[:MAX_MODEL_PLAN_STEPS], start=1):
        if isinstance(item, dict):
            title = first_text(item.get("title"), item.get("name"), item.get("action"))
            if not title:
                continue
            steps.append(
                {
                    "order": safe_int(item.get("order"), index),
                    "title": title,
                    "reason": first_text(item.get("reason"), item.get("why")) or "",
                    "expectedFiles": normalize_string_list(item.get("expectedFiles"), 5),
                }
            )
        elif isinstance(item, str) and item.strip():
            steps.append({"order": index, "title": item.strip(), "reason": "", "expectedFiles": []})
    return steps


def normalize_string_list(value: Any, limit: int) -> list[str]:
    if not isinstance(value, list):
        return []
    result = []
    for item in value:
        if len(result) >= limit:
            break
        if item is None:
            continue
        text = str(item).strip()
        if text and text not in result:
            result.append(text_preview(text, 200))
    return result


def first_text(*values: Any) -> Optional[str]:
    for value in values:
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def safe_int(value: Any, default: int) -> int:
    if isinstance(value, int):
        return value
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


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


def retrieval_queries(
    plan_output: dict[str, Any],
    context: dict[str, Any],
    request: AgentRunStartRequest,
) -> list[str]:
    deterministic_queries: list[str] = []
    for query in plan_output.get("searchQueries", []):
        if query is not None:
            add_query(deterministic_queries, str(query))
    model_queries: list[str] = []
    model_plan = plan_output.get("modelPlan")
    if isinstance(model_plan, dict):
        for query in model_plan.get("searchQueries", []):
            if query is not None:
                add_query(model_queries, str(query))
    queries: list[str] = []
    for query in deterministic_queries[:3]:
        add_query(queries, query)
    for query in model_queries[:2]:
        add_query(queries, query)
    for query in deterministic_queries[3:]:
        add_query(queries, query)
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
