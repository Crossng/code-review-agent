import re
import time
from typing import Any, Optional

from app.clients.backend_api import BackendApiClient
from app.clients.model_client import WorkerCoderModelClient
from app.graph.nodes.common import text_preview
from app.schemas import (
    AgentModelCallRecordRequest,
    AgentPatchRecordRequest,
    AgentRunStartRequest,
    AgentStepRecordRequest,
)

PATCH_GENERATION_MODE = "WORKER_SAFE_PLANNING_DRAFT"
PATCH_GENERATION_PROVIDER = "AGENT_WORKER"
PATCH_GENERATION_MODEL = "worker-retrieval-plan-v1"
CODER_PATCH_GENERATION_MODE = "LLM_CODER_DRAFT"
FENCED_DIFF_PATTERN = re.compile(r"(?s)```(?:diff|patch)\s*\n(.*?)\n```")
DIFF_HEADER_PATTERN = re.compile(r"^diff --git a/(.*?) b/(.*?)$", re.MULTILINE)


def generate_patch(
    run_id: int,
    loaded_context: dict[str, Any],
    index_status: dict[str, Any],
    plan_output: dict[str, Any],
    retrieval_output: dict[str, Any],
    request: AgentRunStartRequest,
    client: Optional[BackendApiClient] = None,
    model_client: Optional[WorkerCoderModelClient] = None,
) -> dict[str, Any]:
    backend = client or BackendApiClient()
    context = loaded_context["context"]
    draft = generate_model_patch_draft(
        run_id,
        context,
        index_status,
        plan_output,
        retrieval_output,
        request,
        backend,
        model_client,
    )
    if draft is None:
        started_at = time.monotonic()
        draft = deterministic_patch_draft(run_id, context, index_status, plan_output, retrieval_output, request)
        duration_ms = elapsed_ms(started_at)
        backend.record_model_call(
            run_id,
            AgentModelCallRecordRequest(
                step_name="generate_patch",
                model_provider=draft["generationProvider"],
                model_name=draft["generationModel"],
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
                    "generationMode": draft["generationMode"],
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
            generation_mode=draft["generationMode"],
            generation_provider=draft["generationProvider"],
            generation_model=draft["generationModel"],
        ),
    )
    output = {
        "summary": draft["summary"],
        "patchId": patch_response.get("data", {}).get("id"),
        "patchStatus": patch_response.get("data", {}).get("status"),
        "baseBranch": patch_response.get("data", {}).get("baseBranch"),
        "targetBranch": patch_response.get("data", {}).get("targetBranch"),
        "generationMode": draft["generationMode"],
        "generationProvider": draft["generationProvider"],
        "generationModel": draft["generationModel"],
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
                "generationMode": draft["generationMode"],
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
                sandbox_data = sandbox_response.get("data")
                output["sandbox"] = sandbox_data
                if isinstance(sandbox_data, dict) and sandbox_data.get("testsPassed") is True:
                    review_response = backend.review_patch(run_id, int(patch_id))
                    review_data = review_response.get("data")
                    output["review"] = review_data
                    if isinstance(review_data, dict) and review_data.get("stepStatus") == "SUCCESS":
                        approval_response = backend.mark_patch_ready_for_approval(run_id, int(patch_id))
                        output["approval"] = approval_response.get("data")
        except Exception as error:  # noqa: BLE001 - post-patch gates are reported without duplicating generate_patch failure
            output["postPatchGateError"] = str(error)[:1000]
    return output


def generate_model_patch_draft(
    run_id: int,
    context: dict[str, Any],
    index_status: dict[str, Any],
    plan_output: dict[str, Any],
    retrieval_output: dict[str, Any],
    request: AgentRunStartRequest,
    backend: BackendApiClient,
    model_client: Optional[WorkerCoderModelClient],
) -> Optional[dict[str, Any]]:
    coder = model_client or WorkerCoderModelClient()
    prompt = build_coder_model_prompt(context, index_status, plan_output, retrieval_output, request)
    model_result = coder.generate_text("generate_patch", prompt)
    if model_result is None:
        return None
    parsed = parse_coder_patch_output(model_result.text)
    changed_paths = parsed["changedPaths"]
    task_id = context.get("taskId") or request.task_id
    draft = {
        "path": changed_paths[0] if changed_paths else None,
        "summary": f"Worker Coder 草稿：已解析任务 #{task_id} 的 unified diff。",
        "diffContent": parsed["diffContent"],
        "generationMode": CODER_PATCH_GENERATION_MODE,
        "generationProvider": model_result.provider,
        "generationModel": model_result.model,
        "evidence": {
            "planStepCount": len(plan_output.get("steps", [])),
            "retrievalQueryCount": len(retrieval_output.get("queries", [])),
            "retrievalResultCount": retrieval_output.get("uniqueResultCount", 0),
            "readFileCount": len(retrieval_output.get("readFiles", [])),
            "indexReady": index_status.get("indexReady", False),
            "modelOutputFormat": parsed["format"],
            "changedPaths": changed_paths,
        },
    }
    backend.record_model_call(
        run_id,
        AgentModelCallRecordRequest(
            step_name="generate_patch",
            model_provider=model_result.provider,
            model_name=model_result.model,
            status="SUCCESS",
            prompt=model_result.prompt,
            response={
                "modelResponse": model_result.response,
                "parsedDiff": {
                    "format": parsed["format"],
                    "changedPaths": changed_paths,
                    "diffLineCount": len(str(parsed["diffContent"]).splitlines()),
                },
            },
            prompt_tokens=model_result.prompt_tokens,
            completion_tokens=model_result.completion_tokens,
            total_tokens=model_result.total_tokens,
            duration_ms=model_result.duration_ms,
        ),
    )
    return draft


def build_coder_model_prompt(
    context: dict[str, Any],
    index_status: dict[str, Any],
    plan_output: dict[str, Any],
    retrieval_output: dict[str, Any],
    request: AgentRunStartRequest,
) -> dict[str, Any]:
    return {
        "role": "CoderAgent",
        "language": "zh-CN",
        "instruction": "基于计划和检索上下文生成一个最小、安全、可审查的 unified diff。",
        "task": {
            "taskId": context.get("taskId") or request.task_id,
            "projectId": context.get("projectId") or request.project_id,
            "taskType": context.get("taskType"),
            "title": context.get("title") or request.user_request,
            "description": context.get("description") or request.user_request,
            "repoFullName": context.get("repoFullName"),
            "baseBranch": context.get("defaultBranch") or request.base_branch,
        },
        "index": {
            "indexReady": index_status.get("indexReady", False),
            "javaFileCount": index_status.get("javaFileCount", 0),
            "symbolCount": index_status.get("symbolCount", 0),
            "controllerCount": index_status.get("controllerCount", 0),
            "serviceCount": index_status.get("serviceCount", 0),
        },
        "plan": {
            "summary": plan_output.get("summary"),
            "steps": plan_output.get("steps", [])[:8],
            "modelPlan": plan_output.get("modelPlan"),
            "testStrategy": plan_output.get("testStrategy"),
        },
        "retrievedContext": [
            {
                "chunkId": result.get("chunkId"),
                "filePath": result.get("filePath"),
                "chunkType": result.get("chunkType"),
                "symbolType": result.get("symbolType"),
                "qualifiedName": result.get("qualifiedName"),
                "symbolName": result.get("symbolName"),
                "startLine": result.get("startLine"),
                "endLine": result.get("endLine"),
                "summary": text_preview(result.get("summary"), 300),
                "preview": text_preview(result.get("preview"), 1200),
            }
            for result in retrieval_output.get("results", [])[:8]
        ],
        "readFiles": [
            {
                "path": file.get("path"),
                "size": file.get("size"),
                "contentPreview": text_preview(file.get("content"), 2000),
            }
            for file in retrieval_output.get("readFiles", [])[:4]
        ],
        "outputContract": {
            "format": "raw_unified_diff",
            "requirements": [
                "只输出一个 unified diff",
                "第一行必须以 diff --git 开始",
                "不要输出 Markdown fence、解释、总结或多个备选方案",
                "只能使用仓库相对路径",
                "不要修改 .git、密钥文件、绝对路径、父级目录或二进制文件",
                "上下文不足时，只能新增 .repopilot/ 下的中文规划文件",
            ],
        },
    }


def parse_coder_patch_output(raw_output: str) -> dict[str, Any]:
    if raw_output is None or not str(raw_output).strip():
        raise ValueError("Coder output is empty")
    normalized = str(raw_output).replace("\r\n", "\n").strip()
    matches = list(FENCED_DIFF_PATTERN.finditer(normalized))
    if len(matches) > 1:
        raise ValueError("Coder output must contain at most one diff code block")
    if len(matches) == 1:
        diff = matches[0].group(1).strip()
        without_block = FENCED_DIFF_PATTERN.sub("", normalized).strip()
        if without_block:
            raise ValueError("Coder output must not include prose outside the diff block")
        output_format = "fenced_diff"
    else:
        if not normalized.startswith("diff --git "):
            raise ValueError("Coder output must be a raw diff or one diff code block")
        diff = normalized
        output_format = "raw_diff"
    if not diff.startswith("diff --git "):
        raise ValueError("Coder output must start with a unified diff header")
    if "```" in diff:
        raise ValueError("Coder diff must not contain Markdown fences")
    diff_content = ensure_trailing_newline(diff)
    return {
        "diffContent": diff_content,
        "format": output_format,
        "changedPaths": changed_paths_from_diff(diff_content),
    }


def ensure_trailing_newline(value: str) -> str:
    return value if value.endswith("\n") else value + "\n"


def changed_paths_from_diff(diff_content: str) -> list[str]:
    paths: list[str] = []
    for match in DIFF_HEADER_PATTERN.finditer(diff_content):
        path = (match.group(2) or match.group(1) or "").strip()
        if path and path not in paths:
            paths.append(path)
    return paths


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
        "generationMode": PATCH_GENERATION_MODE,
        "generationProvider": PATCH_GENERATION_PROVIDER,
        "generationModel": PATCH_GENERATION_MODEL,
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
