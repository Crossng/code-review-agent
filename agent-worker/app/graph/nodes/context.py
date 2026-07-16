from typing import Any, Optional

from app.clients.backend_api import BackendApiClient
from app.schemas import AgentRunStartRequest, AgentStepRecordRequest


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
