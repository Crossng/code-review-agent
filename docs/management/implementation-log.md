# Implementation Log

## 2026-07-08

Initial system skeleton created from the development baseline.

### Added

- Root project config: `.gitignore`, `.env.example`, `docker-compose.yml`.
- Docker services: PostgreSQL with pgvector and Redis.
- Spring Boot backend module.
- Flyway `V1__init.sql` migration for users, projects, agent tasks, runs and steps.
- JWT auth API:
  - `POST /api/auth/register`
  - `POST /api/auth/login`
  - `GET /api/auth/me`
- Project API:
  - `POST /api/projects`
  - `GET /api/projects`
  - `GET /api/projects/{id}`
  - `POST /api/projects/{id}/index`
  - `GET /api/projects/{id}/files`
- Agent task API:
  - `POST /api/agent/tasks`
  - `GET /api/agent/tasks`
  - `GET /api/agent/tasks/{id}`
  - `POST /api/agent/tasks/{id}/run`
  - `POST /api/agent/tasks/{id}/cancel`
  - `GET /api/agent/tasks/{id}/steps`
- FastAPI Agent Worker contract with `/health` and `/runs/{run_id}/start`.
- Vite React frontend console shell.

### Verified

- Docker Compose config is valid.
- PostgreSQL/pgvector and Redis containers start and become healthy.
- Backend Maven tests pass.
- Backend starts, connects to PostgreSQL, and applies Flyway migration.
- Register/login/project/task/run/steps API flow works against the live backend.
- Agent step JSONB persistence works after Hibernate JSON mapping fix.
- Frontend production build passes.
- Agent Worker Python files parse under the system Python.

### Next

- Implement repository clone service and workspace lifecycle.
- Add JavaParser-based AST scanning and `code_file`/`code_symbol` tables.
- Add code chunking and pgvector embeddings.
- Connect Spring Boot backend to Agent Worker run start API.
- Replace placeholder Agent run steps with real LangGraph node execution.

## 2026-07-08, Slice 2

Repository workspace management and JavaParser indexing were added.

### Added

- `POST /api/projects/{id}/clone` for synchronous MVP repository clone.
- `repository_snapshot` table and entity.
- `code_file` table and entity.
- `code_symbol` table and entity.
- JavaParser dependency and AST indexer service.
- Project indexing now records repository snapshot, code files and Java symbols.
- `GET /api/projects/{id}/symbols` with optional `type` filter.
- Symbol extraction for:
  - class/interface/enum
  - Spring `Controller` / `RestController`
  - `Service`
  - `Mapper` / `Repository`
  - JPA `Entity`
  - methods
  - fields

### Verified

- Backend compiles and tests pass with Maven.
- Flyway V2 migration applies to PostgreSQL.
- Created a project pointing at `examples/demo-spring-repo`.
- Cloned the repository into `workspace/repos/3/source`.
- Indexed the cloned repository:
  - `fileCount`: 6
  - `javaFileCount`: 5
  - `symbolCount`: 18
- Confirmed symbol APIs return:
  - `CONTROLLER`: `com.example.demo.user.UserController`
  - `SERVICE`: `com.example.demo.user.UserService`
  - `MAPPER`: `com.example.demo.user.UserMapper`
  - `ENTITY`: `com.example.demo.user.UserEntity`

### Next

- Add code chunking and `code_chunk` persistence.
- Add first keyword search endpoint over indexed files and symbols.
- Connect RetrieverAgent placeholder to the indexed symbol/search APIs.

## 2026-07-09, Slice 3

Code chunk persistence and first keyword search API were added.

### Added

- `code_chunk` table and entity.
- File-level chunks for indexed source/config files.
- Symbol-level chunks for Java types and methods.
- `GET /api/projects/{id}/search?query={keyword}&limit={n}`.
- Search results include file path, chunk type, symbol metadata, line range, summary and preview.

### Verified

- Apply Flyway V3 migration.
- Re-index the demo Spring repository.
- Confirm index response includes `chunkCount`.
- Search for `UserController`, `findById` and `UserService` through the API.

Observed result:

- `projectId`: 4
- `fileCount`: 6
- `javaFileCount`: 5
- `symbolCount`: 18
- `chunkCount`: 20
- `UserController` search returns FILE, CLASS and METHOD chunks.
- `findById` search returns the `UserMapper#findById` method chunk.
- `UserService` search returns the `UserService` class chunk.

### Next

- Add RetrieverAgent integration so `POST /api/agent/tasks/{id}/run` can call the search API.
- Add patch record table/entity and a safe diff generation placeholder.
- Add a tool-call log for search and repository tools.

## 2026-07-09, Slice 4

Agent task runs now retrieve real repository context from indexed chunks.

### Added

- `AgentTaskService.run` now creates structured JSON step payloads.
- `plan_task` now produces a basic implementation plan and search query candidates.
- `retrieve_context` calls `CodeSearchService` with task-derived query candidates.
- Retrieval results are deduplicated by chunk id and written into `agent_step.output_json`.
- `generate_patch` is now the explicit next pending step, matching the CoderAgent roadmap.

### Verified

- Re-index the demo Spring repository.
- Create an Agent task: “Add User pagination API”.
- Run the task.
- Confirm `retrieve_context` step contains real chunk search results for User-related code.

Observed result:

- `projectId`: 5
- `taskId`: 2
- `runId`: 3
- Indexed repository produced `fileCount=6`, `javaFileCount=5`, `symbolCount=18`, `chunkCount=20`.
- Agent steps:
  - `load_task_context: SUCCESS`
  - `plan_task: SUCCESS`
  - `retrieve_context: SUCCESS`
  - `generate_patch: PENDING`
- Retrieval query candidates included `User`, `pagination`, `paginated`, `query`.
- Retrieval returned 8 unique chunks, including `UserController` file/class/method chunks and `UserEntity` context.

### Next

- Add `patch_record` persistence.
- Add a deterministic safe diff placeholder before introducing an LLM CoderAgent.
- Add sandbox patch application and Maven test skeleton.

## 2026-07-09, Slice 5

Patch persistence and a deterministic safe diff placeholder were added.

### Added

- `patch_record` table and entity.
- Patch status enum: `GENERATED`, `APPLIED`, `APPROVED`, `REJECTED`.
- `PatchGenerationService` creates a safe planning patch as a valid unified diff.
- `GET /api/tasks/{taskId}/patches` lists task patches.
- `GET /api/tasks/{taskId}/patches/latest` returns the newest patch.
- Patch query APIs validate task ownership.
- `AgentTaskService.run` now:
  - retrieves context,
  - generates a safe planning diff,
  - persists `patch_record`,
  - marks `generate_patch` as `SUCCESS`,
  - transitions task to `WAITING_HUMAN_APPROVAL`,
  - creates a pending `waiting_human_approval` step.

### Verified

- Apply Flyway V4 migration.
- Run an Agent task against the demo Spring repository.
- Confirm the latest patch is persisted with status `GENERATED`.
- Confirm the diff is valid unified diff text for `.repopilot/task-{id}-plan.md`.

Observed result:

- `projectId`: 6
- `taskId`: 3
- `runId`: 4
- Task status: `WAITING_HUMAN_APPROVAL`
- Agent steps:
  - `load_task_context: SUCCESS`
  - `plan_task: SUCCESS`
  - `retrieve_context: SUCCESS`
  - `generate_patch: SUCCESS`
  - `waiting_human_approval: PENDING`
- Latest patch:
  - `id`: 1
  - `status`: `GENERATED`
  - `baseBranch`: `main`
  - `targetBranch`: `repopilot/task-3`
  - diff creates `.repopilot/task-3-plan.md`

### Next

- Add approval record persistence and approve/reject endpoints.
- Add sandbox patch application for generated patches.
- Add Maven test run skeleton after patch application.

## 2026-07-09, Slice 6

Human approval persistence and approve/reject APIs were added.

### Added

- `approval_record` table and entity.
- Approval actions: `APPROVE`, `REJECT`.
- `POST /api/tasks/{taskId}/approval/approve`.
- `POST /api/tasks/{taskId}/approval/reject`.
- `GET /api/tasks/{taskId}/approval`.
- Approve flow:
  - validates task ownership,
  - validates patch belongs to task,
  - requires patch status `GENERATED`,
  - changes patch to `APPROVED`,
  - changes task to `CREATING_PULL_REQUEST`,
  - writes `approval_record`.
- Reject flow:
  - changes patch to `REJECTED`,
  - changes task to `CANCELLED`,
  - writes `approval_record`.

### Verified

- Apply Flyway V5 migration.
- Generate a patch through an Agent task run.
- Approve the patch and confirm patch/task/approval states.
- Generate a second patch and reject it to verify the rejection path.

Observed result:

- Approve path:
  - `projectId`: 7
  - `taskId`: 4
  - `patchId`: 2
  - approval action: `APPROVE`
  - patch status: `APPROVED`
  - task status: `CREATING_PULL_REQUEST`
  - approval record count: 1
- Reject path:
  - `projectId`: 8
  - `taskId`: 5
  - `patchId`: 3
  - approval action: `REJECT`
  - patch status: `REJECTED`
  - task status: `CANCELLED`

### Next

- Add PR preparation records or GitHub PR creation placeholder.
- Add sandbox patch application for approved patches.
- Add Maven test run persistence.

## 2026-07-09, Slice 7

Pull Request preparation records were added after human approval.

### Added

- `pull_request_record` table and entity.
- PR provider/status enums:
  - `GITHUB`
  - `DRAFT_READY`, `OPEN`, `FAILED`
- `POST /api/tasks/{taskId}/pull-request`.
- `GET /api/tasks/{taskId}/pull-request`.
- PR preparation flow:
  - validates task ownership,
  - requires task status `CREATING_PULL_REQUEST`,
  - uses the latest patch for the task,
  - requires patch status `APPROVED`,
  - creates an idempotent `DRAFT_READY` record,
  - keeps `url` and `prNumber` empty until real GitHub API creation is enabled.

### Verified

- Apply Flyway V6 migration.
- Generate a patch through an Agent task run.
- Approve the patch.
- Prepare a PR record.
- Query the latest PR record.
- Repeat PR preparation and confirm it returns the existing record without duplication.

Observed result:

- `projectId`: 9
- `taskId`: 6
- `runId`: 7
- `patchId`: 4
- approval action: `APPROVE`
- patch status: `APPROVED`
- task status: `CREATING_PULL_REQUEST`
- PR record:
  - `id`: 1
  - `provider`: `GITHUB`
  - `status`: `DRAFT_READY`
  - `url`: `null`
  - `prNumber`: `null`
  - linked patch: `4`
- Idempotency check:
  - second `POST /api/tasks/6/pull-request` returned `id`: 1
  - database count for `(agent_task_id=6, patch_id=4)`: 1

### Next

- Add sandbox patch application and Maven test run persistence.
- Add real GitHub branch, commit, and PR creation once repository credentials are configured.

## 2026-07-09, Slice 8

Docker sandbox patch application and Maven test run persistence were added.

### Added

- `test_run` table and entity.
- `TestRunStatus`: `PASSED`, `FAILED`.
- `SandboxTestService`:
  - prepares `workspace/runs/{runId}/source`,
  - writes `workspace/runs/{runId}/patch.diff`,
  - applies the diff with Docker + `git apply ../patch.diff`,
  - runs Docker + `mvn -q test`,
  - stores exit code, duration, log excerpt, and status in `test_run`.
- `GET /api/agent/runs/{runId}/test-runs`.
- Agent run workflow now includes:
  - `apply_patch`,
  - `run_tests`,
  - `review_patch`,
  - then `waiting_human_approval`.
- Patch status now changes from `GENERATED` to `APPLIED` after sandbox patch application.
- Approval now requires task status `WAITING_HUMAN_APPROVAL` and patch status `GENERATED` or `APPLIED`.
- Sandbox Maven dependency cache now mounts the workspace Maven repository at `/root/.m2/repository`.

### Found And Fixed

- First Docker Maven verification failed because the container tried to download dependencies from Maven Central and the remote TLS handshake was terminated.
  - Fix: reuse the workspace `.m2` local repository as the sandbox Maven cache.
- Approval incorrectly allowed a patch to be approved even when the task had failed tests.
  - Fix: approve/reject now require `WAITING_HUMAN_APPROVAL`.

### Verified

- Pull `maven:3.9-eclipse-temurin-17`.
- Confirm the sandbox image contains `git` and Maven.
- Apply Flyway V7 migration.
- Generate a patch through an Agent task run.
- Apply the patch in a Docker sandbox.
- Run `mvn -q test` in the Docker sandbox.
- Query `test_run`.
- Approve only after the task reaches `WAITING_HUMAN_APPROVAL`.
- Confirm a non-waiting task is blocked from approval/rejection.
- Confirm PR preparation still works after the new sandbox-tested approval flow.

Observed result:

- Happy path:
  - `projectId`: 11
  - `taskId`: 8
  - `runId`: 9
  - `patchId`: 6
  - task status before approval: `WAITING_HUMAN_APPROVAL`
  - patch status before approval: `APPLIED`
  - steps:
    - `load_task_context: SUCCESS`
    - `plan_task: SUCCESS`
    - `retrieve_context: SUCCESS`
    - `generate_patch: SUCCESS`
    - `apply_patch: SUCCESS`
    - `run_tests: SUCCESS`
    - `review_patch: SUCCESS`
    - `waiting_human_approval: PENDING`
  - `testRunId`: 2
  - test status: `PASSED`
  - exit code: 0
  - duration: 1665 ms
  - approval action: `APPROVE`
  - patch status after approval: `APPROVED`
  - task status after approval: `CREATING_PULL_REQUEST`
  - PR preparation record:
    - `id`: 2
    - `provider`: `GITHUB`
    - `status`: `DRAFT_READY`
    - linked patch: `6`
- Negative check:
  - `POST /api/tasks/7/approval/reject`
  - HTTP status: 409
  - error code: `AGENT_INVALID_STATUS`

### Next

- Add real Git branch/commit preparation for approved patches.
- Add GitHub credential configuration and real Pull Request creation.
- Add RepairAgent loop for `FAILED_TEST` cases.

## 2026-07-09, Slice 9

Approved patches can now be materialized into a local Git branch and commit before GitHub PR creation.

### Added

- Flyway V8 migration for PR Git materialization fields:
  - `base_branch`
  - `target_branch`
  - `commit_sha`
  - `commit_message`
- `PullRequestGitService`:
  - validates the app-managed repository path,
  - requires a clean local worktree,
  - checks out the patch base branch,
  - creates the patch target branch,
  - applies the approved diff,
  - commits the change with RepoPilot author metadata,
  - returns the local commit SHA.
- PR preparation now requires:
  - task status `CREATING_PULL_REQUEST`,
  - latest patch status `APPROVED`,
  - latest `test_run` for the patch status `PASSED`.
- `PullRequestRecordResponse` now includes local Git metadata.
- Existing idempotency remains: repeated PR preparation returns the existing record.

### Verified

- Apply Flyway V8 migration.
- Generate a patch through an Agent task run.
- Pass Docker sandbox Maven test.
- Approve the patch.
- Prepare a PR record.
- Confirm local Git branch and commit were created.
- Confirm repeated PR preparation returns the same record.
- Confirm a failed-test patch is blocked from PR preparation.

Observed result:

- `projectId`: 12
- `taskId`: 9
- `runId`: 10
- `patchId`: 7
- `testRunId`: 3
- test status: `PASSED`
- test exit code: 0
- patch status after approval: `APPROVED`
- PR record:
  - `id`: 3
  - `provider`: `GITHUB`
  - `status`: `DRAFT_READY`
  - `baseBranch`: `main`
  - `targetBranch`: `repopilot/task-9`
  - `commitSha`: `7ab2bd40a730a83eac9e52fd43470240ec8f192a`
  - `url`: `null`
  - `prNumber`: `null`
- Local Git check:
  - current branch: `repopilot/task-9`
  - `HEAD`: `7ab2bd40a730a83eac9e52fd43470240ec8f192a`
  - worktree status: clean
  - `.repopilot/task-9-plan.md` exists
  - commit message includes `Tests: mvn test passed`
- Idempotency check:
  - second `POST /api/tasks/9/pull-request` returned `id`: 3
  - commit SHA unchanged
- Negative check:
  - `POST /api/tasks/7/pull-request`
  - HTTP status: 409
  - error code: `PATCH_TEST_NOT_PASSED`

### Next

- Add GitHub credential configuration.
- Push local target branch to origin.
- Call GitHub API to create the real Pull Request and update `pull_request_record` with URL and PR number.

## 2026-07-14, Slice 10

GitHub publishing support was added behind explicit configuration.

### Added

- GitHub configuration:
  - `REPOPILOT_GITHUB_ENABLED`
  - `REPOPILOT_GITHUB_TOKEN` or `GITHUB_TOKEN`
  - `REPOPILOT_GITHUB_API_BASE_URL`
- Flyway V9 migration for GitHub publication metadata:
  - `remote_pushed_at`
  - `opened_at`
  - `error_message`
- `GitHubPullRequestService`:
  - detects `github.com` repositories,
  - requires a configured token when GitHub publishing is enabled,
  - pushes the prepared target branch with a Git auth header,
  - calls the GitHub Pull Request API,
  - returns PR number and URL.
- PR preparation now:
  - keeps local-only records as `DRAFT_READY` when GitHub publishing is disabled,
  - updates records to `OPEN` and task status to `DONE` after GitHub PR creation,
  - marks records as `FAILED` and task status as `FAILED_PR_CREATION` when configured publishing fails,
  - allows retry from `FAILED_PR_CREATION`.
- Added explicit guard for historical records missing local branch/commit metadata.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- Docker Desktop was initially unavailable, then restarted.
- Apply Flyway V9 migration at runtime.
- Local-only PR preparation regression with GitHub publishing disabled:
  - `projectId`: 13
  - `taskId`: 10
  - `runId`: 11
  - `testRunId`: 4
  - test status: `PASSED`
  - PR record: `id` 4
  - status: `DRAFT_READY`
  - `url`, `prNumber`, `remotePushedAt`, `openedAt`, `errorMessage`: `null`
  - local branch: `repopilot/task-10`
  - local commit: `569d3e8d067342c5431436a9ca56e037ae141db0`
- GitHub publishing enabled without token:
  - `projectId`: 15
  - `taskId`: 12
  - `testRunId`: 6
  - test status: `PASSED`
  - `POST /api/tasks/12/pull-request` returns HTTP 409
  - error code: `GITHUB_TOKEN_NOT_CONFIGURED`
  - PR record persisted as `FAILED`
  - task status: `FAILED_PR_CREATION`
  - local branch: `repopilot/task-12`
  - local commit: `eba0c6c7367a214ebc76b90e42fa92bca002dc85`

### Found And Fixed

- Publishing failures were initially rolled back by the transactional boundary, so the `FAILED` PR record was not persisted.
  - Fix: `PullRequestService.prepare` now uses `noRollbackFor = ApiException.class`, preserving failed PR metadata while still returning the correct HTTP error.

### Next

- With a real GitHub test repository and token, verify push + GitHub PR creation and update `pull_request_record.url`/`pr_number`.
- Add an automated integration test for GitHub publish failure persistence.

## 2026-07-14, Slice 11

The frontend dashboard was upgraded from a static scaffold to an operational RepoPilot control console.

### Added

- Frontend API client coverage for:
  - auth login/register,
  - project create/clone/index/list,
  - task create/list/detail/run,
  - agent steps,
  - latest patch,
  - test runs,
  - approvals,
  - PR prepare/query.
- Token persistence via `localStorage`.
- Project intake panel.
- Agent task creation panel.
- Task list and selected task detail panel.
- Agent step timeline.
- Patch diff viewer.
- Sandbox test result/log panel.
- Approval and rejection actions.
- Pull request result panel, including:
  - local target branch,
  - commit SHA,
  - PR number/link when opened,
  - publish failure message.
- Loading, empty, and error states for the main workflow.

### Verified

- `npm run build` passes.
- Vite dev server smoke check:
  - `npm run dev -- --port 5173`,
  - `curl -I http://127.0.0.1:5173/` returned HTTP 200.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- Docker Compose services remain healthy:
  - Postgres healthy,
  - Redis healthy.

### Next

- Add route-level navigation once the console needs deep links.
- Add browser-level smoke tests against a running backend.
- Add GitHub publish success verification with a real token and test repository.

## 2026-07-14, Slice 12

Automated tests were added for the Pull Request preparation and GitHub publishing control flow.

### Added

- `PullRequestServiceTest`.
- Service-level tests for:
  - local-only PR preparation when GitHub publishing is disabled,
  - GitHub publishing failure persistence,
  - GitHub publishing success status transitions,
  - `@Transactional(noRollbackFor = ApiException.class)` on `prepare`.

### Covered Behavior

- Local-only mode:
  - creates a `DRAFT_READY` PR record,
  - stores base branch, target branch, and commit SHA,
  - does not call GitHub publishing,
  - keeps task status `CREATING_PULL_REQUEST`.
- GitHub failure mode:
  - propagates the `ApiException`,
  - marks the PR record as `FAILED`,
  - stores the failure message,
  - marks the task as `FAILED_PR_CREATION`,
  - saves both task and PR record.
- GitHub success mode:
  - marks PR record as `OPEN`,
  - stores PR number and URL,
  - marks the task as `DONE`.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- Docker Compose services remain healthy:
  - Postgres healthy,
  - Redis healthy.

### Next

- Add browser-level smoke tests against a running backend.
- Add integration tests around the HTTP API layer for approval and PR preparation.

## 2026-07-14, Slice 13

The frontend console now exposes repository understanding data from the indexing layer.

### Added

- Frontend API client types and methods for:
  - project file listing,
  - indexed Java symbol listing,
  - code chunk search.
- Project insight panel in the control console.
- Project selector for inspecting repository structure independently of the selected task.
- File path list with directory/file labels and sizes.
- Symbol summary chips and top indexed symbol list.
- Code search form and indexed chunk result previews.
- Empty states for projects that have not been cloned or indexed yet.

### Verified

- `npm run build` passes.
- Vite dev server smoke check:
  - `npm run dev -- --port 5173`,
  - `curl -I http://127.0.0.1:5173/` returned HTTP 200.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- Docker Compose services remain healthy:
  - Postgres healthy,
  - Redis healthy.

### Next

- Add browser-level smoke tests against a running backend and seeded demo project.
- Add HTTP API integration tests for project files, symbols, and code search.

## 2026-07-14, Slice 14

HTTP integration coverage was added for the project repository understanding APIs.

### Added

- `ProjectControllerIntegrationTest`.
- Test profile configuration for Spring Boot HTTP integration tests.
- End-to-end controller coverage for:
  - user registration and JWT authentication,
  - project creation from the local demo repository,
  - project clone,
  - JavaParser indexing,
  - `GET /api/projects/{id}/files`,
  - `GET /api/projects/{id}/symbols?type=SERVICE`,
  - `GET /api/projects/{id}/search`.
- Test data isolation with unique emails and a dedicated test workspace.

### Fixed

- Frontend project file loading now requests `maxDepth=10`, so nested Java package files such as `src/main/java/com/example/demo/user/UserService.java` appear in the repository insight panel.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- Docker Compose services remain healthy:
  - Postgres healthy,
  - Redis healthy.

### Next

- Add browser-level smoke tests against a running backend and seeded demo project.
- Add HTTP integration tests for approval and PR preparation endpoints.

## 2026-07-14, Slice 15

A browser-level smoke path was added for the operational console.

### Added

- `scripts/browser-smoke.sh`.
- `scripts/browser-smoke.mjs`.
- Frontend `smoke:browser` npm script.
- Playwright development dependency for the frontend.
- Browser smoke coverage for:
  - opening the React console,
  - registering a smoke user,
  - creating a local demo project,
  - cloning the demo repository,
  - indexing Java/Spring symbols,
  - refreshing the repository insight panel,
  - searching indexed code for `UserService`.
- Smoke artifacts:
  - success screenshot at `output/playwright/repopilot-browser-smoke.png`,
  - backend/frontend logs under `target/browser-smoke/logs`.
- Smoke data cleanup for generated users and projects.
- Smoke workspace cleanup for cloned demo repositories.

### Fixed

- Text inputs now receive unique IDs, preventing duplicate `Email`/`Password` label associations across auth forms.
- Project file listing filters `.git` paths out of the repository insight tree.
- `ProjectControllerIntegrationTest` now asserts that `.git` paths are not returned by `GET /api/projects/{id}/files`.

### Verified

- `./scripts/browser-smoke.sh` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- Docker Compose services remain healthy:
  - Postgres healthy,
  - Redis healthy.
- Smoke cleanup leaves `0` `browser-smoke-%` users in Postgres.
- Smoke cleanup leaves only the empty `target/browser-smoke/workspace` root.

### Next

- Add HTTP integration tests for approval and PR preparation endpoints.
- Add tool-call audit persistence to satisfy AC-011.

## 2026-07-14, Slice 16

Tool-call audit persistence was implemented for Agent runs.

### Added

- Flyway migration `V10__tool_call_log.sql`.
- `tool_call_log` JPA domain, repository, service, query service, DTO, and controller.
- `GET /api/agent/runs/{runId}/tool-calls`.
- Tool call recording in the Agent run workflow for:
  - `load_task_context`,
  - `plan_task`,
  - `search_code`,
  - `generate_patch`,
  - `prepare_sandbox`,
  - `apply_patch`,
  - `run_maven_test`,
  - `review_patch`.
- Sensitive-field redaction for token, secret, password, and authorization keys.
- Large JSON summary truncation for audit payloads.
- Frontend API client support for tool call logs.
- Frontend `Tool call audit` panel in task details.
- HTTP integration test for:
  - tool-call listing,
  - run ownership authorization,
  - success and failure records,
  - sensitive input redaction.

### Fixed

- Project file listing now filters only the internal `.git` directory instead of every path beginning with `.git`.
- Tool-call failure recording handles exceptions with null messages.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes.
- Docker Compose services remain healthy:
  - Postgres healthy,
  - Redis healthy.
- Flyway schema history shows version `10 - tool call log` applied successfully.
- Smoke cleanup leaves `0` `browser-smoke-%` users in Postgres.

### Next

- Add HTTP integration tests for approval and PR preparation endpoints.
- Add model-call audit persistence for `/api/agent/runs/{runId}/model-calls`.

## 2026-07-14, Slice 17

Approval and pull-request preparation HTTP gates were covered with integration tests.

### Added

- `PullRequestApprovalIntegrationTest`.
- HTTP integration coverage for:
  - `POST /api/tasks/{taskId}/pull-request` before approval returning `PATCH_NOT_APPROVED`,
  - `POST /api/tasks/{taskId}/approval/approve`,
  - `GET /api/tasks/{taskId}/approval`,
  - `POST /api/tasks/{taskId}/pull-request` after approval and passing sandbox test,
  - `GET /api/tasks/{taskId}/pull-request`,
  - `PATCH_TEST_NOT_PASSED` when the latest sandbox test failed.
- Test fixture that creates a real local git repository, applies the PR patch through the production PR git service, and verifies the prepared branch commit contains the expected file change.
- Test cleanup for generated users, projects, local repositories, and PR workspaces.

### Fixed

- Calling PR preparation while a task is still `WAITING_HUMAN_APPROVAL` now returns `PATCH_NOT_APPROVED`, matching the MVP acceptance contract for the unapproved gate.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes.
- Docker Compose services remain healthy:
  - Postgres healthy,
  - Redis healthy.
- Smoke/test cleanup leaves:
  - `0` `browser-smoke-%` users,
  - `0` `approval-pr-%` users,
  - no `approval-pr-*` test repository directories,
  - no lingering 8080/5173 listeners.

### Next

- Add model-call audit persistence for `/api/agent/runs/{runId}/model-calls`.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 18

Model-call audit persistence was implemented for Agent runs.

### Added

- Flyway migration `V11__model_call_log.sql`.
- `model_call_log` JPA domain, repository, service, query service, DTO, and controller.
- `GET /api/agent/runs/{runId}/model-calls`.
- Model-call recording in the Agent run workflow for:
  - `plan_task`,
  - `generate_patch`,
  - `review_patch`.
- Local placeholder model metadata:
  - provider: `LOCAL_PLACEHOLDER`,
  - model: `deterministic-mvp`.
- Sensitive-field redaction for token, secret, password, authorization, and API key style fields.
- Large JSON summary truncation for prompt/response payloads.
- Estimated token accounting for local placeholder model calls.
- Frontend API client support for model call logs.
- Frontend `Model call audit` panel in task details.
- HTTP integration test for:
  - model-call listing,
  - run ownership authorization,
  - success and failure records,
  - sensitive prompt redaction,
  - token accounting.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes.
- Docker Compose services remain healthy:
  - Postgres healthy,
  - Redis healthy.
- Flyway schema history shows version `11 - model call log` applied successfully.
- Smoke/test cleanup leaves:
  - `0` `browser-smoke-%` users,
  - `0` `model-call-%` users,
  - no lingering 8080/5173 listeners.

### Next

- Verify GitHub publish success with a real configured test repository and token.
- Add Controller API extraction for Spring endpoints.

## 2026-07-14, Slice 19

Spring Controller API extraction was added to project insight.

### Added

- `GET /api/projects/{id}/controller-apis`.
- JavaParser-based Spring Controller route extraction for:
  - `@RestController` and `@Controller`,
  - class-level `@RequestMapping`,
  - method-level `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, and `@RequestMapping(method = ...)`,
  - request body type, response type, security annotations, and source line ranges.
- Frontend API client type and loader for Controller API records.
- Project insight panel section showing route method, path, controller method, request/response types, security annotations, and source location.
- HTTP integration coverage for extracted demo Spring routes.
- API, frontend, and acceptance documentation updates.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes.

### Next

- Add deeper Controller API extraction for query/path parameter metadata and composed/custom security annotations.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 20

Controller API extraction now includes parameter metadata.

### Added

- `ProjectControllerApiParameterResponse`.
- `parameters` array in `GET /api/projects/{id}/controller-apis` responses.
- JavaParser extraction for:
  - `@PathVariable` as `PATH`,
  - `@RequestParam` as `QUERY`,
  - `@RequestBody` as `BODY`,
  - `@RequestHeader` as `HEADER`,
  - parameter type,
  - required flag,
  - default value.
- Spring-style handling where `defaultValue` makes query/header parameters optional when `required` is not explicit.
- Frontend Controller API parameter chips in the project insight panel.
- HTTP integration test fixture that injects a temporary Controller into the cloned test workspace and verifies path, query, header, body, default value, required flag, and class/method security annotations.
- API, frontend, and acceptance documentation updates for parameter metadata.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.

### Next

- Add Controller to Service call-chain extraction.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 21

Controller API extraction now includes first-hop Service calls.

### Added

- `ProjectControllerServiceCallResponse`.
- `serviceCalls` array in `GET /api/projects/{id}/controller-apis` responses.
- JavaParser extraction that:
  - identifies Controller fields whose type name ends with `Service`,
  - detects scoped method calls such as `userService.listUsers()` and `this.userService.getUser()`,
  - records receiver name, service type, method name, and source line.
- Frontend Controller API service-call chips in the project insight panel.
- HTTP integration assertions for demo `UserController -> UserService` calls.
- API, frontend, and acceptance documentation updates for service-call metadata.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.

### Next

- Extend call-chain extraction from Service to Mapper/Repository.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 22

Controller API call-chain extraction now reaches Mapper/Repository calls.

### Added

- `ProjectControllerDownstreamCallResponse`.
- `downstreamCalls` nested under each `serviceCalls` item in `GET /api/projects/{id}/controller-apis`.
- JavaParser type indexing for scanned Java files so call-chain resolution can reuse parsed ASTs.
- Service-method resolution by simple service type and method name.
- Downstream extraction that:
  - identifies Service fields whose type name ends with `Mapper` or `Repository`,
  - detects scoped calls such as `userMapper.findAll()` and `userRepository.findById()`,
  - records receiver name, component type, method name, and service-method source line.
- Frontend rendering for nested Mapper/Repository calls inside service-call chips.
- HTTP integration assertions for demo `UserController -> UserService -> UserMapper` calls.
- API, frontend, and acceptance documentation updates for downstream call metadata.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.

### Next

- Add risk hints for unauthenticated or weakly-guarded Controller APIs.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 23

Controller API insight now includes basic risk hints.

### Added

- `ProjectControllerRiskHintResponse`.
- `riskHints` array in `GET /api/projects/{id}/controller-apis` responses.
- Risk hint rules for:
  - missing recognized method/class security annotations,
  - unguarded mutating endpoints,
  - mutating endpoints without request body parameters,
  - optional request body parameters,
  - unclassified method parameters.
- Frontend risk hint chips with severity-specific styling in the project insight panel.
- HTTP integration assertions for:
  - secured endpoints having no security risk hint,
  - optional request body hints,
  - unguarded read endpoint hints.
- API, frontend, and acceptance documentation updates for risk hints.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.

### Next

- Add richer validation-risk hints for request bodies and query parameters.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 24

Controller API risk hints now include validation and query-bound signals.

### Added

- `BODY_WITHOUT_VALIDATION` risk hints for request bodies missing `@Valid` or `@Validated`.
- `BODY_TYPE_WITHOUT_CONSTRAINTS` risk hints for scanned request body DTOs without recognized validation constraint annotations.
- `QUERY_PARAMETER_WITHOUT_BOUNDS` risk hints for numeric pagination/limit query parameters without bound annotations.
- Nested type indexing so request body DTO inspection can cover inner classes in scanned Java files.
- HTTP integration assertions for:
  - secured endpoints with unbounded query parameters,
  - optional request bodies missing validation annotations,
  - request body DTOs without constraint annotations.
- API, frontend, and acceptance documentation updates for validation and query-bound risk hints.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.

### Next

- Add DTO field-level risk details and risk scoring for Controller API hints.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 25

Controller API insight now includes aggregate risk scoring.

### Added

- `riskScore` and `riskLevel` fields in `GET /api/projects/{id}/controller-apis` responses.
- Deterministic Controller API risk scoring:
  - `HIGH` hint = 70 points,
  - `MEDIUM` hint = 35 points,
  - `LOW` hint = 10 points,
  - score capped at 100.
- Risk level mapping for `NONE`, `LOW`, `MEDIUM`, and `HIGH`.
- Frontend Controller API ordering by highest risk score first.
- Frontend risk score badge next to each Controller API method.
- HTTP integration assertions for low, medium, and high aggregate risk cases.
- API, MCP tool, frontend, and acceptance documentation updates for risk scoring.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes.

### Next

- Add DTO field-level risk details to explain exactly which request fields lack constraints.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 26

Controller API validation risk hints now include field-level details.

### Added

- `details` array on `ProjectControllerRiskHintResponse`.
- Field-level details for `BODY_TYPE_WITHOUT_CONSTRAINTS` so fully unconstrained request DTOs identify exact fields.
- New `BODY_FIELDS_WITHOUT_CONSTRAINTS` hint for request DTOs that have some validation constraints but still leave specific fields unconstrained.
- Direct-field DTO validation inspection so nested types do not pollute the owning DTO's field checks.
- Frontend rendering for field-level risk details inside Controller API risk chips.
- HTTP integration assertions for:
  - fully unconstrained DTO field details,
  - partially constrained DTO field details,
  - empty details on hints that do not have field-level targets.
- API, MCP tool, frontend, and acceptance documentation updates for field-level risk details.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes.

### Next

- Add query-parameter details for exact missing lower/upper bounds.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 27

Controller API query-bound risk hints now identify the missing bound direction.

### Added

- Query parameter `details` for `QUERY_PARAMETER_WITHOUT_BOUNDS`.
- Lower-bound detection for `@Min`, `@Positive`, `@PositiveOrZero`, `@DecimalMin`, and `@Range`.
- Upper-bound detection for `@Max`, `@DecimalMax`, and `@Range`.
- Risk messages that distinguish:
  - missing lower and upper bounds,
  - missing only a lower bound,
  - missing only an upper bound.
- HTTP integration assertions for:
  - unbounded numeric pagination parameters,
  - partially bounded numeric pagination parameters.
- API, MCP tool, frontend, and acceptance documentation updates for field/parameter-level risk details.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes.

### Next

- Add richer Controller API filtering in the frontend by risk level or risk code.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 28

Controller API insight can now be filtered by risk level and risk code.

### Added

- Frontend risk-level filter for Controller API routes.
- Frontend risk-code filter derived from the returned `riskHints`.
- Filter-aware route counts, showing `x of y routes` when filters are active.
- Empty state for filters that match no Controller APIs.
- Browser smoke coverage that selects `MEDIUM` and `NO_SECURITY_ANNOTATION` filters on the demo repository.
- Frontend and acceptance documentation updates for Controller API risk filtering.

### Verified

- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with risk-level and risk-code filter interaction.

### Next

- Add saved/demo-friendly risk filter defaults or route anchors for sharing a focused Controller API risk view.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 29

Controller API risk filters are now shareable through URL query parameters.

### Added

- URL-backed Controller API risk filter state:
  - `controllerRiskLevel`,
  - `controllerRiskCode`.
- Initial filter restoration from URL query parameters.
- `history.replaceState` synchronization when risk filters change, preserving existing path and hash.
- Support for selected risk-code values that are not present in the current result set, so shared URLs still show the requested filter.
- Browser smoke coverage that:
  - selects risk-level and risk-code filters,
  - verifies URL query parameters,
  - reloads the page,
  - verifies filters are restored from the URL.
- Frontend and acceptance documentation updates for shareable Controller API risk filters.

### Verified

- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with URL query persistence and reload restoration checks.

### Next

- Add direct route anchors or copy-link affordance for a focused Controller API risk view.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 30

Controller API focused risk views can now be copied as shareable links.

### Added

- `Copy risk view link` action beside the Controller API risk filters.
- Clipboard-backed copy flow using the same URL construction as filter synchronization.
- Inline copy status feedback for copied, unavailable, and failed clipboard states.
- Browser smoke clipboard coverage that:
  - grants clipboard permissions,
  - copies the filtered Controller API risk view,
  - verifies the copied URL contains `controllerRiskLevel` and `controllerRiskCode`.
- Frontend and acceptance documentation updates for copying focused Controller API risk view links.

### Verified

- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with clipboard copy verification for the focused risk view link.

### Next

- Add direct per-route anchors so a copied risk view can focus a specific Controller API row.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 31

Controller API rows now have direct shareable anchors.

### Added

- Stable `controller-api-*` anchor IDs for rendered Controller API rows.
- `Copy route link` action on each Controller API row.
- Route-link copies that include the current risk filter query parameters and the target Controller API hash.
- Hash-target row highlighting and scroll restoration for asynchronously rendered Controller API rows.
- Browser smoke coverage that:
  - copies a focused route link,
  - verifies the copied URL includes risk filter query parameters,
  - verifies the copied URL includes a `#controller-api-*` hash.
- Frontend and acceptance documentation updates for route-level Controller API links.

### Verified

- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with route-link hash and risk-filter query verification.

### Next

- Add lightweight risk summary counters for HIGH/MEDIUM/LOW/NONE Controller API routes.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 32

Controller API insight now shows risk summary counters.

### Added

- HIGH/MEDIUM/LOW/NONE risk-level counters above the Controller API list.
- Risk summary buttons that set the matching risk-level filter.
- Active-state styling for the selected risk summary level.
- Browser smoke coverage that clicks the MEDIUM summary counter and verifies the risk-level filter changes.
- Frontend and acceptance documentation updates for Controller API risk summary counters.

### Verified

- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with risk summary counter click coverage.

### Next

- Add backend/API-level risk summary fields if the frontend summary needs to support server-side pagination.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 33

Controller API risk summary counters are now part of the backend API contract.

### Added

- `GET /api/projects/{id}/controller-apis` now returns `items` and `riskSummary`.
- `riskSummary.total` and `riskSummary.byLevel` are computed from the full Controller API result set.
- Frontend Controller API counters now read API-provided `riskSummary` values, with local item-count fallback.
- Integration test coverage for HIGH/MEDIUM/LOW/NONE summary counts.
- API, frontend, and acceptance documentation updates for the new response contract.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with API-provided risk summary counters.

### Next

- Add backend query parameters for server-side Controller API filtering by risk level and risk code.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 34

Controller API risk filtering now runs through backend query parameters.

### Added

- `riskLevel` and `riskCode` query parameters for `GET /api/projects/{id}/controller-apis`.
- Backend filtering of response `items` while keeping `riskSummary` computed from the full Controller API result set.
- Invalid risk-level validation with `CONTROLLER_API_INVALID_RISK_LEVEL`.
- Frontend risk-filter state lifted to the page level so filter changes refetch Controller APIs from the backend.
- Browser smoke coverage that waits for filtered `/controller-apis?riskLevel=...&riskCode=...` responses.
- API, frontend, and acceptance documentation updates for server-side Controller API filtering.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with server-side Controller API filter request checks.

### Next

- Add response-level metadata for active Controller API filters if future pagination needs explicit server echo fields.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 35

Controller API responses now echo the active server-side filters.

### Added

- `filters` metadata on `GET /api/projects/{id}/controller-apis` responses.
- Server-normalized `filters.riskLevel` and trimmed `filters.riskCode` values.
- Null filter values when no Controller API risk filter is active.
- Integration test coverage for default null filters and filtered response echo fields.
- Frontend API type coverage for the new `filters` response field.
- API and acceptance documentation updates for active filter metadata.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with Controller API filter metadata in the response contract.

### Next

- Add server-provided risk-code option metadata so the UI can list all available risk codes even when `items` is filtered.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 36

Controller API responses now include full risk-code option metadata.

### Added

- `riskCodes` metadata on `GET /api/projects/{id}/controller-apis` responses.
- Sorted, de-duplicated risk-code extraction from the full Controller API result set.
- Integration test coverage proving `riskCodes` remains full even when response `items` is filtered.
- Frontend project insight state now stores API-provided `riskCodes`.
- Risk-code filter dropdown now uses server-provided `riskCodes`, with current-item fallback.
- API, frontend, and acceptance documentation updates for full risk-code options.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with server-provided risk-code options.

### Next

- Add response metadata for filtered item count if pagination or server-side page sizes are introduced.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 37

Controller API responses now include filtered item count metadata.

### Added

- `filteredCount` on `GET /api/projects/{id}/controller-apis` responses.
- Backend filtered-count calculation from the server-side filtered result set.
- Integration test coverage for default and filtered `filteredCount` values.
- Frontend project insight state now stores API-provided `filteredCount`.
- Controller API route count display now uses `filteredCount` instead of deriving from rendered items.
- API, frontend, and acceptance documentation updates for filtered count metadata.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with API-provided `filteredCount` route counts.

### Next

- Add explicit pagination metadata if Controller API result paging is introduced.
- Verify GitHub publish success with a real configured test repository and token.

## 2026-07-14, Slice 38

Browser smoke now covers the local Agent-to-PR demo loop.

### Added

- Browser smoke coverage for creating the default Agent task from the UI.
- End-to-end UI assertions for:
  - Agent run completion at `WAITING_HUMAN_APPROVAL`,
  - step timeline entries for planning, retrieval, patch generation, tests, review, and approval wait,
  - model-call and tool-call audit panels,
  - generated patch diff summary,
  - Maven sandbox test result,
  - human approval,
  - local `DRAFT_READY` PR preparation with target branch, commit state, and PR body.
- Smoke cleanup now removes task, run, patch, test, approval, PR, tool-call, and model-call records for the smoke user.
- Smoke workspace cleanup now removes the full dedicated browser-smoke workspace.
- Scripts and acceptance documentation now describe the default local `DRAFT_READY` PR flow and the separate GitHub publish requirement.

### Verified

- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes through local PR preparation.

### Next

- Verify GitHub push + PR creation with a real configured test repository and token.

## 2026-07-14, Slice 39

The default demo Agent task now generates a real Spring pagination patch.

### Added

- `PatchGenerationService.generatePatch(...)` with a deterministic Spring demo patch path and safe planning fallback.
- Real default demo diff generation for `Add User pagination API`, including:
  - `GET /api/users/page` in `UserController`,
  - bounded page/size normalization in `UserService`,
  - in-memory page slicing in `UserMapper`,
  - `spring-boot-starter-test` in the demo `pom.xml`,
  - `UserServiceTest` coverage for slice, bounds, and empty-page behavior.
- Agent model-call and step inputs now record `SPRING_DEMO_PATCH_WITH_SAFE_FALLBACK`.
- Browser smoke now asserts the real `Adds GET /api/users/page...` patch summary.
- Unit coverage for real demo patch generation and fallback planning diff generation.
- API, acceptance, MVP, and script documentation updates for the real demo diff.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchGenerationServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes with the real pagination patch through sandbox test, approval, and local PR preparation.

### Next

- Replace the deterministic demo patch path with a general CoderAgent implementation for arbitrary Java/Spring changes.
- Verify GitHub push + PR creation with a real configured test repository and token.

## 2026-07-14, Slice 42

Patch APIs now expose file-level diff summaries.

### Added

- `PatchChangedFileResponse` for parsing unified diff text into changed-file entries.
- `PatchRecordResponse.changedFiles`, including:
  - current path,
  - old path,
  - change type: `ADDED`, `MODIFIED`, `DELETED`, or `RENAMED`,
  - added line count,
  - deleted line count.
- Unit coverage for modified, added, and deleted file parsing.
- Browser smoke API assertion that the regenerated demo patch exposes changed files for:
  - `pom.xml`,
  - `UserController.java`,
  - `UserService.java`,
  - `UserMapper.java`,
  - `UserServiceTest.java`.
- Browser smoke verifies `UserServiceTest.java` is reported as an added file with added lines.
- API, acceptance, and script documentation updates for file-level diff summaries.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchChangedFileResponseTest,PatchGenerationServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes with regenerated patch `changedFiles` verified through the API.

### Next

- Replace the deterministic demo patch path with a general CoderAgent implementation for arbitrary Java/Spring changes.
- Verify GitHub push + PR creation with a real configured test repository and token.

## 2026-07-14, Slice 43

The frontend patch panel now renders file-level diff summaries.

### Added

- Frontend `PatchChangedFile` type and `PatchRecord.changedFiles` typing.
- `ChangedFilesSummary` in the Patch panel before the raw unified diff.
- Compact changed-file rows showing:
  - change type,
  - file path,
  - added/deleted line counts.
- Responsive CSS for changed-file rows.
- Browser smoke UI assertions that the regenerated patch panel shows:
  - `Changed files`,
  - `UserController.java`,
  - `UserServiceTest.java`,
  - `ADDED`.

### Verified

- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes with file-level diff summary visible in the Patch panel.

### Next

- Replace the deterministic demo patch path with a general CoderAgent implementation for arbitrary Java/Spring changes.
- Verify GitHub push + PR creation with a real configured test repository and token.

## 2026-07-14, Slice 40

Patch regeneration is now available from API and UI.

### Added

- `POST /api/tasks/{taskId}/patches/regenerate`.
- Legacy-compatible `POST /api/tasks/{taskId}/patch/regenerate`.
- `AgentTaskService.regeneratePatch(...)` for creating a new Agent run and patch from:
  - `WAITING_HUMAN_APPROVAL`,
  - `FAILED_TEST`,
  - `FAILED_PATCH_GENERATION`,
  - `CANCELLED`.
- Successful Agent runs now mark `agent_run.status=SUCCESS` and set `finished_at` once the task reaches `WAITING_HUMAN_APPROVAL`.
- Frontend API client support for patch regeneration.
- Approval panel `Regenerate` button for eligible task states.
- Browser smoke assertion that the `Regenerate` button is enabled at the human approval gate.
- Unit coverage for regeneration success and invalid-status rejection.
- API, user-story, acceptance, and script documentation updates.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskServiceRegenerationTest,PatchGenerationServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes with the regeneration control visible before approval.

### Next

- Replace the deterministic demo patch path with a general CoderAgent implementation for arbitrary Java/Spring changes.
- Verify GitHub push + PR creation with a real configured test repository and token.

## 2026-07-14, Slice 41

Browser smoke now exercises the Regenerate loop.

### Added

- Browser smoke now records the first Agent run id, patch id, and sandbox test run id after the initial run.
- Browser smoke clicks `Regenerate` before approval.
- Browser smoke verifies that the regenerated task has a new run id, patch id, and test run id.
- Browser smoke then continues through approval and local `DRAFT_READY` PR preparation with the regenerated patch.
- Script and acceptance documentation now describe Regenerate version verification instead of only checking button availability.

### Verified

- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with Regenerate clicked and new Run/Patch/Test run ids verified.

### Next

- Replace the deterministic demo patch path with a general CoderAgent implementation for arbitrary Java/Spring changes.
- Verify GitHub push + PR creation with a real configured test repository and token.

## 2026-07-14, Slice 44

Rule-based patch risk review is now visible in the Agent loop.

### Added

- `PatchRiskReviewService` as a rule-based ReviewAgent placeholder for generated diffs and sandbox test results.
- `review_patch` now records structured output with `riskLevel`, `summary`, and `findings`.
- Review rules for:
  - newly added Controller endpoints without explicit auth annotations,
  - pagination parameters with or without bounds normalization,
  - SQL access that requires review,
  - test coverage present or missing.
- Frontend `Automated review` summary in the Patch panel.
- Browser smoke assertions for:
  - `NEW_CONTROLLER_ENDPOINT_WITHOUT_AUTH`,
  - `PAGINATION_BOUNDS_NORMALIZED`,
  - `TEST_COVERAGE_PRESENT`.
- Product, MVP, workflow, acceptance, and script documentation updates for automated patch risk review.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchRiskReviewServiceTest,AgentTaskServiceRegenerationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes with automated review visible in the Patch panel.

### Next

- Replace the deterministic demo patch path with a general CoderAgent implementation for arbitrary Java/Spring changes.
- Expand ReviewAgent with project context and LLM review once model wiring is ready.
- Verify GitHub push + PR creation with a real configured test repository and token.

## 2026-07-14, Slice 45

CoderAgent now has a second real Spring demo patch path for User id validation.

### Added

- `PatchGenerationService` now recognizes User id validation bugfix requests and generates a real diff that:
  - adds a positive id guard in `UserService#getUser`,
  - adds `spring-boot-starter-test` when needed,
  - creates or updates `UserServiceTest` with null and non-positive id coverage.
- Existing `UserServiceTest.java` files can be updated instead of forcing a planning-patch fallback.
- `generate_patch` audit metadata now uses `SPRING_DEMO_CODER_WITH_SAFE_FALLBACK`.
- Browser smoke now creates a second task, `Fix User id validation bug`, and verifies:
  - real guard patch summary,
  - `User id must be positive`,
  - `getUserRejectsNonPositiveId`,
  - `changedFiles`,
  - automated review,
  - Maven sandbox test pass.
- `PullRequestGitService` now checks the project workspace back out to the base branch after preparing a local PR branch, so later tasks are not accidentally generated from the previous task branch.
- Product, MVP, Agent workflow, acceptance, and script docs now describe the two supported Spring demo Coder paths.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchGenerationServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchGenerationServiceTest,PullRequestApprovalIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes with both the pagination PR loop and the User id validation patch loop.
- Docker Compose shows PostgreSQL and Redis healthy; smoke cleanup leaves `0` temporary smoke/project users and no `8080`/`5173` listeners.

### Next

- Continue replacing deterministic demo paths with a general CoderAgent for arbitrary Java/Spring changes.
- Add RepairAgent behavior for failed sandbox tests.
- Verify GitHub push + PR creation with a real configured test repository and token.

## 2026-07-14, Slice 46

RepairAgent is now connected to the Agent execution loop for a deterministic Maven failure case.

### Added

- `PatchRepairService` for repairing Maven test failures caused by missing test dependencies.
- Deterministic repair for logs such as:
  - `package org.junit.jupiter.api does not exist`,
  - `package org.assertj.core.api does not exist`.
- Repair output creates a new `PatchRecord` that prepends a `pom.xml` diff adding `spring-boot-starter-test` to the failed patch.
- `AgentTaskService` now:
  - runs patch validation through a reusable apply/test helper,
  - enters `REPAIRING` after a failed sandbox test,
  - records `repair_patch` as a model-call-like step,
  - retries sandbox prepare/apply/`mvn -q test` with the repaired patch,
  - caps RepairAgent attempts at 2 before `FAILED_TEST`.
- Unit coverage for:
  - `PatchRepairService` repairing missing test dependency failures,
  - unrecognized failure logs rejecting deterministic repair,
  - the Agent loop repairing one failed test run and reaching `WAITING_HUMAN_APPROVAL`.
- PRD, MVP scope, Agent workflow, acceptance, and sandbox docs now describe the current RepairAgent scope.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchRepairServiceTest,AgentTaskServiceRegenerationTest,PatchGenerationServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes after the Agent loop refactor.

### Next

- Expand RepairAgent beyond dependency repair into assertion failures and compile errors with file-local fixes.
- Continue replacing deterministic Coder paths with a general Java/Spring CoderAgent.
- Add live task event streaming once the synchronous run loop is split into background execution.

## 2026-07-14, Slice 47

Task event streaming now has a backend endpoint and smoke coverage.

### Added

- `notification` backend module implementation:
  - `AgentEventResponse`,
  - `TaskStreamService`,
  - `TaskStreamController`.
- `GET /api/agent/tasks/{id}/stream` returns `text/event-stream`.
- Current stream behavior is a finite snapshot that emits:
  - `TASK_SNAPSHOT`,
  - `STEP_SNAPSHOT`,
  - `STREAM_COMPLETE`.
- Owner authorization for task streams; non-owner requests are forbidden.
- Browser smoke now fetches the task SSE stream with the JWT bearer token and verifies task, step, and completion events.
- API, PRD, backend module, acceptance, and script docs now describe the snapshot SSE stream and the later path to continuous async event streaming.

### Fixed

- Avoided `SseEmitter` async dispatch for the current finite snapshot stream because it caused Spring Security async re-dispatch issues in the smoke run. The endpoint now returns a synchronous SSE-formatted response, which is sufficient until the Agent run loop is moved off the HTTP request thread.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=TaskStreamControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes with task SSE snapshot verification.

### Next

- Move Agent execution to a background executor or worker so `run` can return immediately.
- Upgrade the task stream from finite snapshot SSE to continuous live events.
- Add frontend subscription/polling once the run loop is asynchronous.

## 2026-07-14, Slice 48

Agent task runs now execute in the background.

### Added

- `AgentExecutionConfig` with a dedicated `agentTaskExecutor` for long-running Agent workflows.
- `AgentTaskService.run(...)` now:
  - creates a new `agent_run`,
  - sets the task to `GENERATING_PATCH`,
  - returns the RUNNING run immediately,
  - submits the existing Agent workflow after transaction commit.
- Background run execution now uses a new transaction and records unexpected failures back to the run and task.
- `regeneratePatch(...)` remains synchronous so the approval-side patch regeneration flow continues to return the new patch directly.
- Frontend task detail loading now has a quiet polling path.
- The selected task auto-refreshes while it is in running statuses so the UI can follow async progress through `WAITING_HUMAN_APPROVAL`.
- API, backend module, and Agent workflow docs now describe background run execution and current snapshot SSE behavior.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskServiceRegenerationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes with async run polling reaching approval.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Upgrade the task stream from finite snapshot SSE to continuous live events from the background run.
- Add cancellation awareness inside the background execution loop.
- Continue replacing deterministic Coder paths with a general Java/Spring CoderAgent.

## 2026-07-14, Slice 49

Task event streaming now follows background Agent runs live.

### Added

- `TaskStreamService` now manages in-memory SSE subscribers per task.
- `GET /api/agent/tasks/{id}/stream` now returns an `SseEmitter` stream:
  - completed or paused tasks still receive `TASK_SNAPSHOT`, `STEP_SNAPSHOT`, and `STREAM_COMPLETE`,
  - running tasks receive the initial snapshot and stay connected,
  - background execution publishes `TASK_UPDATED` and `STEP_RECORDED`,
  - approval, failure, cancellation, or completion publishes `STREAM_COMPLETE` and closes the stream.
- `AgentTaskService` now publishes task updates, step records, and terminal stream events from the existing run workflow.
- Spring Security now permits async dispatcher re-entry for SSE completion.
- Stream integration coverage now verifies both owner-only snapshot access and live step/completion delivery.
- PRD, API, backend module, Agent workflow, and acceptance docs now describe live task event streaming.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=TaskStreamControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskServiceRegenerationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes after switching task stream to `SseEmitter`.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add cancellation awareness inside the background execution loop.
- Continue replacing deterministic Coder paths with a general Java/Spring CoderAgent.
- Consider wiring the frontend to consume SSE directly once bearer-token stream handling is settled.

## 2026-07-14, Slice 50

Cancellation now stops background Agent execution at safe checkpoints.

### Added

- `AgentRun.markCancelled(...)` and persisted `AgentRunStatus.CANCELLED` usage for user-initiated task cancellation.
- `AgentTaskRepository.findStatusById(...)` as a scalar latest-status query so the background transaction can detect a separately committed cancel request.
- Cancellation checks in the background Agent workflow before and after major stages:
  - context loading,
  - planning,
  - retrieval,
  - patch generation,
  - sandbox apply,
  - Maven tests,
  - repair,
  - review,
  - final approval transition.
- `POST /api/agent/tasks/{id}/cancel` now immediately marks both task and current RUNNING run as `CANCELLED`, publishes stream updates, and closes the task stream.
- Frontend task detail now shows a `Cancel` action while the selected task is running.
- API, backend module, Agent workflow, MVP scope, and acceptance docs now describe cancellation behavior.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskServiceRegenerationTest test` passes with cancellation unit coverage.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` passes.
- `node --check scripts/browser-smoke.mjs` passes.
- `./scripts/browser-smoke.sh` passes after adding frontend Cancel.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Continue replacing deterministic Coder paths with a general Java/Spring CoderAgent.

## 2026-07-14, Slice 51

Project-level write-task concurrency is now guarded before workspace-mutating operations.

### Added

- `ProjectRepository.findByIdForUpdate(...)` uses a pessimistic project row lock as the write-slot boundary.
- `ProjectWriteGuardService` blocks a task when another task in the same project is already in an active write status:
  - indexing,
  - planning,
  - retrieving context,
  - generating patch,
  - applying patch in sandbox,
  - running tests,
  - repairing,
  - reviewing patch,
  - creating pull request.
- Guard checks now run before:
  - `POST /api/agent/tasks/{id}/run`,
  - `POST /api/tasks/{id}/patches/regenerate`,
  - approval that moves a task into PR creation,
  - PR preparation.
- Conflicting operations return `409 PROJECT_WRITE_TASK_RUNNING`.
- Guard state revalidation prevents stale same-task concurrent operations after the project lock is acquired.
- Local-only `DRAFT_READY` PR preparation now marks the task `DONE`, releasing the project write slot after branch and commit materialization.
- Unit coverage verifies the guard allows an empty write slot, rejects occupied project write slots, rejects stale task statuses, and keeps completed PR preparation idempotent.
- API, backend module, Agent workflow, PRD, MVP scope, and acceptance docs now describe the concurrency contract.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectWriteGuardServiceTest,AgentTaskServiceRegenerationTest,PullRequestServiceTest,PullRequestApprovalIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` and `node --check scripts/browser-smoke.mjs` pass.
- `./scripts/browser-smoke.sh` passes with local `DRAFT_READY` PR preparation releasing the write slot before the second demo task starts.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Continue replacing deterministic Coder paths with a general Java/Spring CoderAgent.

## 2026-07-14, Slice 52

The frontend now consumes live task SSE events during Agent runs.

### Added

- `streamTaskEvents(...)` in the frontend API client opens `/api/agent/tasks/{id}/stream` with the existing bearer token and parses `text/event-stream` frames from a `ReadableStream`.
- The task detail page subscribes while the selected task is running, displays `Connecting stream` / `Live stream` / fallback state, and refreshes task details from SSE events.
- The existing polling loop remains as a fallback if the stream disconnects or cannot be read.
- Browser smoke now asserts that the UI exposes the live stream state after starting a task.
- API, Agent workflow, frontend page, PRD, and acceptance docs now describe frontend SSE consumption.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with the visible stream state assertion during task execution.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Continue replacing deterministic Coder paths with a general Java/Spring CoderAgent.

## 2026-07-14, Slice 53

CoderAgent patch generation now uses an explicit recipe catalog and persists generation mode metadata.

### Added

- `patch_record.generation_mode` via Flyway V12.
- `PatchRecord.generationMode` and `PatchRecordResponse.generationMode`.
- `PatchGenerationService` now evaluates a Spring Coder recipe catalog instead of direct sequential demo conditionals:
  - `SPRING_USER_PAGINATION_RECIPE`,
  - `SPRING_USER_ID_VALIDATION_RECIPE`,
  - `SAFE_PLANNING_FALLBACK`.
- RepairAgent patches now use `REPAIR_MISSING_TEST_DEPENDENCY`.
- Agent model-call output now includes the selected patch `generationMode`.
- Frontend Patch panel shows the generation mode, and browser smoke asserts the pagination recipe mode is visible.
- API, database, Agent workflow, frontend, PRD, MVP, and acceptance docs now describe generation mode metadata.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchGenerationServiceTest,PatchRepairServiceTest,AgentTaskServiceRegenerationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes, including Flyway V12 migration.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` and `node --check scripts/browser-smoke.mjs` pass.
- `./scripts/browser-smoke.sh` passes with `SPRING_USER_PAGINATION_RECIPE` visible in the patch panel.
- Flyway schema history reports `12 | patch generation mode | success`.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add additional Spring recipe coverage before replacing the recipe catalog with LLM-backed arbitrary Java/Spring patching.

## 2026-07-14, Slice 54

CoderAgent now has a third Spring recipe for User count APIs.

### Added

- `SPRING_USER_COUNT_RECIPE` in the Spring Coder recipe catalog.
- Count recipe generation for:
  - `GET /api/users/count` in `UserController`,
  - `UserService.countUsers()`,
  - `UserMapper.countAll()`,
  - `UserServiceTest.countUsersReturnsTotalNumberOfUsers()`,
  - `spring-boot-starter-test` when missing.
- Unit coverage for generating the count recipe from the baseline demo repository and appending count coverage to an existing `UserServiceTest`.
- Browser smoke now creates a third task, `Add User count API`, and verifies the count recipe mode, changed files, generated test, and sandbox test success.
- PRD, MVP scope, Agent workflow, database, acceptance, and demo steps now describe the third Spring Coder recipe.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchGenerationServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchGenerationServiceTest,AgentTaskServiceRegenerationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` and `node --check scripts/browser-smoke.mjs` pass.
- `./scripts/browser-smoke.sh` passes with the third User count recipe task.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Continue broadening Spring recipe coverage before LLM-backed arbitrary Java/Spring patching.

## 2026-07-15, Slice 55

CoderAgent now has a fourth Spring recipe for User create APIs.

### Added

- `SPRING_USER_CREATE_RECIPE` in the Spring Coder recipe catalog.
- Create recipe generation for:
  - `POST /api/users` in `UserController`,
  - `CreateUserRequest` DTO,
  - `UserService.createUser(CreateUserRequest request)`,
  - `UserMapper.save(String name)`,
  - `UserServiceTest.createUserReturnsCreatedUser()`,
  - `UserServiceTest.createUserRejectsBlankName()`,
  - `spring-boot-starter-test` when missing.
- Unit coverage for generating the create recipe from the baseline demo repository and appending create coverage to an existing `UserServiceTest`.
- Browser smoke now creates a fourth task, `Add User create API`, and verifies the create recipe mode, DTO file, changed files, generated tests, and sandbox test success.
- PRD, MVP scope, Agent workflow, database, acceptance, and demo steps now describe the fourth Spring Coder recipe.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchGenerationServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` and `node --check scripts/browser-smoke.mjs` pass.
- `./scripts/browser-smoke.sh` passes with the fourth User create recipe task.
- Flyway schema history reports `12 | patch generation mode | success`.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Continue broadening Spring recipe coverage before LLM-backed arbitrary Java/Spring patching.

## 2026-07-15, Slice 56

Safe fallback patch generation is now retrieval-grounded instead of generic.

### Added

- `generate_patch` model-call prompts now include bounded retrieved Coder context:
  - chunk id,
  - file path,
  - chunk type,
  - symbol type,
  - qualified name,
  - line range,
  - summary,
  - preview.
- `SAFE_PLANNING_FALLBACK` patches now create a structured `.repopilot/task-*-plan.md` Coder plan with:
  - generation mode and retrieval count,
  - a candidate file table,
  - suggested edit sequence,
  - guardrails for the next Coder pass,
  - required sandbox/review validation.
- Unit coverage for retrieval-grounded fallback plan content and `generate_patch` model-call prompt context.
- PRD, Agent workflow, and acceptance documentation now describe the retrieval-grounded safe Coder plan fallback.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchGenerationServiceTest,AgentTaskServiceRegenerationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` and `node --check scripts/browser-smoke.mjs` pass.
- `./scripts/browser-smoke.sh` passes with the expanded `generate_patch` model prompt shape.
- Flyway schema history reports `12 | patch generation mode | success`.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Replace the retrieval-grounded fallback plan with an LLM-backed Java/Spring CoderAgent that emits bounded unified diffs from the same prompt shape.

## 2026-07-15, Slice 57

Patch application now has a safety preflight before Docker sandbox execution.

### Added

- `PatchDiffSafetyService` for unified diff safety validation before `git apply`.
- The preflight rejects:
  - path traversal,
  - absolute paths,
  - Windows drive and backslash paths,
  - reserved repository/secret paths such as `.git`, `.ssh`, `.env`, and private-key filenames,
  - binary patches,
  - missing `diff --git` headers.
- Agent execution now records a `validate_patch_safety` tool call and `AgentStep` before `prepare_sandbox`.
- Unsafe patches fail as `FAILED_PATCH_GENERATION` before Docker workspace preparation.
- Unit coverage for safe paths, unsafe paths, binary patches, missing diff headers, and Agent flow stopping before sandbox preparation.
- Browser smoke now verifies the `validate_patch_safety` step is visible in the task detail flow.
- PRD, Agent workflow, sandbox, and acceptance documentation now describe the patch safety preflight.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchDiffSafetyServiceTest,AgentTaskServiceRegenerationTest,PatchGenerationServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` and `node --check scripts/browser-smoke.mjs` pass.
- `./scripts/browser-smoke.sh` passes with `validate_patch_safety` visible in the task detail flow.
- Flyway schema history reports `12 | patch generation mode | success`.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Build the bounded LLM-backed Java/Spring CoderAgent on top of the retrieval prompt shape and patch safety preflight.

## 2026-07-15, Slice 58

LLM Coder draft output now has a strict unified-diff parsing contract.

### Added

- `CoderPatchOutputParser` for parsing model raw responses into unified diff text.
- The parser accepts:
  - raw unified diff starting with `diff --git`,
  - one fenced `diff`/`patch` code block.
- The parser rejects:
  - empty output,
  - prose-wrapped output,
  - prose outside a code block,
  - multiple diff blocks,
  - missing `diff --git` header,
  - Markdown fences inside parsed diff content.
- `PatchGenerationService.generatePatchFromCoderOutput(...)` now persists parsed model output as `generationMode=LLM_CODER_DRAFT`.
- Unit coverage for parser contract and `LLM_CODER_DRAFT` patch persistence.
- PRD, Agent workflow, database, and acceptance docs now describe the LLM Coder draft contract.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=CoderPatchOutputParserTest,PatchGenerationServiceTest,PatchDiffSafetyServiceTest,AgentTaskServiceRegenerationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` and `node --check scripts/browser-smoke.mjs` pass.
- `./scripts/browser-smoke.sh` passes with the parser bean wired into the backend application context.
- Flyway schema history reports `12 | patch generation mode | success`.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Wire a configurable model client to produce raw Coder responses, then route them through `CoderPatchOutputParser`, `validate_patch_safety`, sandbox tests, and review.

## 2026-07-15, Slice 59

CoderAgent now has a configurable model-client slot after Spring recipes and before safe fallback.

### Added

- `CoderModelClient` interface with request context for:
  - task metadata,
  - project metadata,
  - default branch,
  - bounded retrieved Coder context.
- `ConfiguredCoderModelClient` with default `disabled` mode.
- `fixture` mode via:
  - `repopilot.coder.mode=fixture`,
  - `repopilot.coder.fixture-response`.
- `PatchGenerationService.generatePatch(...)` now evaluates generation in this order:
  - Spring recipe catalog,
  - configured Coder model client,
  - `SAFE_PLANNING_FALLBACK`.
- Coder model raw responses still route through `CoderPatchOutputParser` and persist as `LLM_CODER_DRAFT`.
- Unit coverage for disabled mode, fixture mode, unsupported modes, and model-client generation before safe fallback.
- Application config, PRD, MVP scope, and Agent workflow docs now describe the configurable Coder model slot.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ConfiguredCoderModelClientTest,CoderPatchOutputParserTest,PatchGenerationServiceTest,AgentTaskServiceRegenerationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `bash -n scripts/browser-smoke.sh` and `node --check scripts/browser-smoke.mjs` pass.
- `./scripts/browser-smoke.sh` passes with the default disabled Coder model mode preserving existing demo recipe behavior.
- Flyway schema history reports `12 | patch generation mode | success`.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Replace fixture mode with a real provider implementation, keeping the same parser, patch safety, sandbox, and review gates.

## 2026-07-15, Slice 60

CoderAgent now has a real OpenAI-compatible HTTP provider path.

### Added

- `openai` / `openai-compatible` modes for `ConfiguredCoderModelClient`.
- Chat Completions-compatible HTTP call to `${repopilot.coder.api-base-url}/chat/completions`.
- Coder model configuration for:
  - API base URL,
  - API key,
  - model,
  - timeout,
  - max completion tokens,
  - instruction role,
  - optional OpenAI organization and project headers.
- Strict diff-only Coder prompt that includes task metadata and bounded retrieved context.
- Response extraction from `choices[0].message.content`, still routed through `CoderPatchOutputParser` before patch persistence.
- Unit coverage with a local fake HTTP server for request body, Authorization header, model response parsing, missing key/model errors, and upstream HTTP failures.
- `.env.example`, Agent workflow, PRD, and acceptance docs now describe the OpenAI-compatible Coder provider mode.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ConfiguredCoderModelClientTest,PatchGenerationServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with the default disabled Coder model mode preserving the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add an end-to-end fixture-mode AgentTask test that proves model output flows through parser, patch safety, sandbox tests, review, and approval using the production Agent state machine.

## 2026-07-15, Slice 61

Fixture-mode Coder output is now verified through the production Agent state machine and real sandbox.

### Added

- `AgentTaskServiceFixtureCoderIntegrationTest`.
- Test coverage that wires together:
  - real `AgentTaskService`,
  - real `PatchGenerationService`,
  - real `ConfiguredCoderModelClient` in `fixture` mode,
  - real `CoderPatchOutputParser`,
  - real `PatchDiffSafetyService`,
  - real `SandboxTestService`,
  - real `PatchRiskReviewService`.
- The fixture model emits raw unified diff for an unknown non-recipe task, producing `generationMode=LLM_CODER_DRAFT`.
- The generated patch is applied in the Docker Maven sandbox and verified through `mvn -q test`.
- Assertions cover the production steps:
  - `generate_patch`,
  - `validate_patch_safety`,
  - `apply_patch`,
  - `run_tests`,
  - `review_patch`,
  - `waiting_human_approval`.
- The sandboxed workspace is inspected to confirm the fixture-generated `README.md` exists after patch application.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskServiceFixtureCoderIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskServiceFixtureCoderIntegrationTest,AgentTaskServiceRegenerationTest,ConfiguredCoderModelClientTest,CoderPatchOutputParserTest,PatchGenerationServiceTest,PatchDiffSafetyServiceTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup still leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a user-facing settings/API surface for Coder model mode and provider configuration visibility, while keeping secrets environment-driven.

## 2026-07-15, Slice 62

Coder model configuration is now visible through a sanitized settings API and console panel.

### Added

- `GET /api/settings/coder`.
- `settings` backend module:
  - `CoderSettingsController`,
  - `CoderSettingsService`,
  - `CoderSettingsResponse`.
- Sanitized Coder runtime configuration response for:
  - mode,
  - provider,
  - enabled/ready state,
  - model,
  - API base URL,
  - API key configured flag,
  - fixture configured flag,
  - timeout,
  - max completion tokens,
  - instruction role,
  - organization/project configured flags,
  - missing requirements,
  - supported modes.
- Integration test coverage that:
  - requires authentication,
  - verifies OpenAI-compatible settings are returned,
  - confirms API key, organization and project secrets are not exposed.
- Frontend API client type and loader for `CoderSettings`.
- `CoderSettingsPanel` in the console, loaded in parallel with projects and tasks.
- Browser smoke assertion that the default disabled Coder mode renders as ready and explains recipe/safe fallback behavior.
- API, frontend, Agent workflow, PRD, and acceptance documentation updates for Coder settings visibility.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=CoderSettingsControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=CoderSettingsControllerIntegrationTest,ConfiguredCoderModelClientTest,AgentTaskServiceFixtureCoderIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with the default Coder settings panel visible before the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a GitHub publishing settings visibility panel next to Coder settings, keeping `GITHUB_TOKEN` secret while showing whether remote PR creation is enabled.

## 2026-07-15, Slice 63

GitHub PR publishing configuration is now visible through a sanitized settings API and console panel.

### Added

- `GET /api/settings/github`.
- `settings` backend additions:
  - `GitHubSettingsController`,
  - `GitHubSettingsService`,
  - `GitHubSettingsResponse`.
- Sanitized GitHub publishing configuration response for:
  - provider,
  - enabled/ready state,
  - publish mode (`LOCAL_DRAFT_ONLY` or `REMOTE_GITHUB_PR`),
  - API base URL,
  - token configured flag,
  - remote publishing flag,
  - local draft mode flag,
  - missing requirements.
- Integration test coverage that:
  - requires authentication,
  - verifies remote GitHub settings are returned when enabled,
  - confirms the GitHub token is not exposed.
- Frontend API client type and loader for `GitHubSettings`.
- `GitHubSettingsPanel` next to `CoderSettingsPanel`, loaded in parallel with projects, tasks, and Coder settings.
- Browser smoke assertions that the default GitHub publishing mode renders as `LOCAL_DRAFT_ONLY`, ready, and secret-safe.
- `.env.example` now documents optional GitHub publishing toggles.
- API, frontend, backend module, sandbox/GitHub, PRD, and acceptance documentation updates for GitHub publishing settings visibility.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=GitHubSettingsControllerIntegrationTest,CoderSettingsControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with both Coder and GitHub settings panels visible before the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add PR publishing preflight/error visibility so the console clearly explains repository eligibility, local draft readiness, and remote GitHub token or API failures before and after PR preparation.

## 2026-07-15, Slice 64

Pull request publishing now has task-level preflight visibility before and after PR preparation.

### Added

- `GET /api/tasks/{taskId}/pull-request/preflight`.
- PR preflight DTOs:
  - `PullRequestPreflightResponse`,
  - `PullRequestPreflightCheckResponse`.
- Preflight checks for:
  - task readiness,
  - latest patch approval,
  - latest sandbox test result,
  - local draft branch/commit readiness,
  - remote GitHub publishing mode,
  - GitHub repository eligibility,
  - GitHub token configured state.
- `GitHubPullRequestService` read-only helpers for remote publishing enabled state, repository eligibility, and token configured state.
- `PullRequestService.preflight` returns `PASS`/`PENDING`/`BLOCKED`/`WARN` checks, `canPrepare`, publish mode, local/remote readiness, latest patch/test/PR statuses, and blockers without creating a branch, commit, or remote PR.
- Pull request controller route for preflight.
- Unit coverage for:
  - ready local draft mode when remote publishing is disabled,
  - remote GitHub publishing blocked when the GitHub token is missing.
- HTTP integration coverage for:
  - preflight blocked before approval,
  - preflight ready after approval and passing sandbox test,
  - preflight reflecting an existing `DRAFT_READY` record after PR preparation.
- Frontend API type and loader for `PullRequestPreflight`.
- PR panel `PullRequestPreflightSummary`, loaded with task details and used to enable the Prepare PR button.
- Browser smoke assertions for blocked, ready, and already-prepared preflight states.
- API, frontend, PRD, acceptance, and sandbox/GitHub docs updated for PR preflight visibility.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PullRequestServiceTest,PullRequestApprovalIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with PR preflight blocker/ready/already-prepared states visible in the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a lightweight runtime/system readiness panel for Docker sandbox and dependency cache settings so the console can explain test execution prerequisites before long Agent runs.

## 2026-07-15, Slice 65

Docker sandbox runtime readiness is now visible through a sanitized settings API and console panel.

### Added

- `GET /api/settings/sandbox`.
- `settings` backend additions:
  - `SandboxSettingsController`,
  - `SandboxSettingsService`,
  - `SandboxSettingsResponse`,
  - `SandboxSettingsCheckResponse`.
- Runtime readiness checks for:
  - Docker daemon availability,
  - sandbox Docker image configuration,
  - workspace root path availability/writability,
  - Maven cache path availability/writability,
  - sandbox timeout configuration.
- Optional `repopilot.sandbox.docker-check-enabled` / `REPOPILOT_SANDBOX_DOCKER_CHECK_ENABLED` toggle for disabling daemon probing in tests or constrained environments.
- `.env.example` now documents sandbox image, timeout, Maven cache, and Docker check configuration.
- Integration test coverage that:
  - requires authentication,
  - verifies sandbox runtime settings and checks,
  - avoids depending on the host Docker daemon by disabling daemon probing for the test.
- Frontend API client type and loader for `SandboxSettings`.
- `SandboxSettingsPanel` in the console Settings area, loaded in parallel with projects, tasks, Coder settings, and GitHub settings.
- Browser smoke assertions that the default Docker sandbox runtime renders as ready, shows the Maven image, and displays Maven cache/workspace readiness.
- API, frontend, backend module, PRD, acceptance, and sandbox/GitHub docs updated for sandbox runtime visibility.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=SandboxSettingsControllerIntegrationTest,CoderSettingsControllerIntegrationTest,GitHubSettingsControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with Coder, GitHub, and Sandbox settings panels visible before the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add lightweight task/run metrics and dashboard counters so the console can summarize throughput, failures, and waiting-for-approval work without opening each task.

## 2026-07-15, Slice 66

Lightweight workspace dashboard metrics are now visible through a user-scoped summary API and console panel.

### Added

- `GET /api/dashboard/summary`.
- `dashboard` backend module:
  - `DashboardController`,
  - `DashboardSummaryService`,
  - `DashboardSummaryResponse`.
- User-scoped summary counters for:
  - total, ready and failed projects,
  - total, created, running, waiting approval, done, failed and cancelled tasks,
  - total, draft, open and failed pull request records.
- Repository count helpers for projects, Agent tasks and PR records.
- Integration test coverage that:
  - requires authentication,
  - verifies summary counts only include the current user's workspace,
  - covers project, task and PR status aggregation.
- Frontend API client type and loader for `DashboardSummary`.
- `DashboardSummaryPanel` in the console overview area, loaded in parallel with projects, tasks and settings panels.
- Browser smoke assertions for:
  - empty workspace overview after registration,
  - project ready count after indexing,
  - waiting approval count after an Agent task reaches the approval pause point.
- API, frontend, backend module, PRD and acceptance documentation updates for dashboard summary visibility.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=DashboardControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with workspace overview counters included in the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add historical run duration and throughput trends so the dashboard can distinguish current state from recent Agent performance over time.

## 2026-07-15, Slice 67

Agent run performance metrics are now visible through the dashboard API and console.

### Added

- `GET /api/dashboard/run-metrics`.
- Dashboard DTOs:
  - `DashboardRunMetricsResponse`,
  - `DashboardRunTrendPointResponse`.
- User-scoped Agent run metrics for a 1-30 day window, defaulting to 7 days:
  - total runs,
  - success, failed, cancelled and running counts,
  - completed run count,
  - average duration in seconds,
  - success rate percentage,
  - UTC daily trend points.
- `AgentRunRepository.findDashboardRuns` for owner-scoped run retrieval through `agent_task.user`.
- Integration test coverage that:
  - requires authentication,
  - excludes other users' runs,
  - excludes runs outside the requested window,
  - verifies average duration, success rate and daily trend aggregation.
- Frontend API client type and loader for `DashboardRunMetrics`.
- `DashboardRunMetricsPanel` in the console overview area, loaded in parallel with workspace summary, projects, tasks and settings.
- Browser smoke assertions that:
  - an empty workspace starts with `Runs=0` and `Success rate=0%`,
  - the first successful Agent run updates the panel to `Runs=1` and `Success rate=100%`.
- API, frontend, backend module, PRD and acceptance documentation updates for Agent run performance visibility.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=DashboardControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with Agent run performance counters included in the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a persisted task activity/event timeline so users can inspect recent state changes across projects without opening each task.

## 2026-07-15, Slice 68

Recent cross-project task activity is now visible through the dashboard API and console.

### Added

- `GET /api/dashboard/activity`.
- `DashboardActivityItemResponse`.
- Owner-scoped dashboard activity retrieval from persisted `agent_step` records through `agent_run -> agent_task -> project`.
- Activity response fields for:
  - step id,
  - run id,
  - task id and title,
  - project id and name,
  - current task status,
  - activity type,
  - step label,
  - step status,
  - summary message,
  - occurred timestamp.
- `AgentStepRepository.findDashboardActivity` with fetch joins and limit support.
- `AgentStep.getAgentRun()` for controlled dashboard mapping.
- Integration test coverage that:
  - requires authentication,
  - excludes other users' step activity,
  - sorts by latest step activity,
  - verifies `limit` handling,
  - confirms the response omits step input/output JSON payloads.
- Frontend API client type and loader for `DashboardActivityItem`.
- `DashboardActivityPanel` in the console overview area, loaded in parallel with workspace summary, run metrics, projects, tasks and settings.
- Browser smoke assertions that:
  - an empty workspace shows no recent task activity,
  - the first successful Agent run shows `waiting_human_approval` in recent activity.
- API, frontend, backend module, PRD and acceptance documentation updates for cross-project activity visibility.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=DashboardControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with Recent task activity included in the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a focused task filter/search surface so larger workspaces can narrow projects and Agent tasks by status, text and ownership context before opening task details.

## 2026-07-15, Slice 69

Agent task filtering and search are now available in the task API and console.

### Added

- `GET /api/agent/tasks` query parameters:
  - `projectId`,
  - `status`,
  - `taskType`,
  - `query`.
- Owner-scoped backend filtering for project, status, type and case-insensitive title/description search.
- `AgentTaskRepository.search` with optional filters and deterministic created-at descending order.
- `AgentTaskService.search`, including blank-query normalization and PostgreSQL-safe query pattern handling.
- `AgentTaskControllerIntegrationTest` coverage that:
  - confirms the default list remains current-user scoped,
  - filters by project,
  - filters by status,
  - filters by task type,
  - searches title/description case-insensitively,
  - combines project, status, type and query filters.
- Frontend API `TaskFilters` type and filtered `listTasks` client.
- Console `TaskFilterForm` in the Agent runs panel:
  - project filter,
  - status filter,
  - task type filter,
  - title/description search,
  - Apply filters,
  - Reset.
- Existing task refresh, polling and SSE-triggered refresh paths now reuse the current task filters.
- Browser smoke assertions for:
  - searching the first pagination task,
  - filtering the task list by `WAITING_HUMAN_APPROVAL`,
  - resetting filters before continuing the existing demo flow.
- API, frontend, PRD and acceptance documentation updates for task filtering/search.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with task search/status filtering included in the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add project list filtering/search so larger workspaces can narrow repository rows by status, repo name and indexing state before selecting a project.

## 2026-07-15, Slice 70

Project list filtering and search are now available in the project API and console.

### Added

- `GET /api/projects` query parameters:
  - `status`,
  - `query`.
- Owner-scoped backend filtering by project status and case-insensitive repository name/URL search.
- `ProjectRepository.search` with optional filters and deterministic created-at descending order.
- `ProjectService.search`, including blank-query normalization and PostgreSQL-safe query pattern handling.
- `ProjectControllerIntegrationTest` coverage that:
  - confirms the default list remains current-user scoped,
  - filters by status,
  - searches `repoFullName` case-insensitively,
  - searches `repoUrl` case-insensitively,
  - combines status and query filters.
- Frontend API `ProjectFilters` type and filtered `listProjects` client.
- Console `ProjectFilterForm` in the Repository workspaces panel:
  - status filter,
  - repository name/URL search,
  - Apply filters,
  - Reset,
  - filtered project count.
- Separate full-project and filtered-project frontend state so project list filtering does not remove task creation, task filtering or task detail project context.
- Browser smoke assertions for:
  - searching the demo repository row,
  - filtering project rows by `READY`,
  - resetting project filters before indexing and continuing the existing demo flow.
- API, frontend, PRD and acceptance documentation updates for project filtering/search.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with project search/status filtering included before the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add URL-backed project selection or project detail deep links so a filtered repository view can be shared as easily as Controller API risk views.

## 2026-07-15, Slice 71

Project filtering and Repository insight selection are now recoverable through the console URL.

### Added

- URL query parameters for the project view:
  - `projectStatus` for the project list status filter,
  - `projectQuery` for the repository name/URL search text,
  - `projectId` for the currently selected Repository insight project.
- Initial React state restoration from the URL before the workspace data load runs.
- URL synchronization when project filters or the selected insight project change, preserving existing Controller API risk filter query parameters and route hashes.
- Filter-aware reload behavior that calls `GET /api/projects?status=...&query=...` after a shared/reloaded URL is opened.
- Browser smoke assertions that:
  - confirm project filter parameters and `projectId` are written into the URL,
  - reload the page and wait for the filtered project API request,
  - verify the project filter form and Repository insight selector restore from the URL,
  - reset project filters and confirm `projectStatus`/`projectQuery` are removed before continuing the demo.
- PRD, frontend and acceptance documentation updates for recoverable project views.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with project URL restoration included before indexing and the existing four-task demo flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add task-list URL persistence or saved task filters so multi-task workspaces can share a focused task queue view.

## 2026-07-15, Slice 72

Task filtering and selected task details are now recoverable through the console URL.

### Added

- URL query parameters for the task view:
  - `taskProjectId` for the task list project filter,
  - `taskStatus` for the task status filter,
  - `taskType` for the task type filter,
  - `taskQuery` for title/description search,
  - `taskId` for the currently selected task detail.
- Initial React state restoration from the URL before workspace and task detail loads run.
- URL synchronization when task filters or the selected task change, preserving project view, Controller API risk filter query parameters and route hashes.
- Filter-aware reload behavior that calls `GET /api/agent/tasks?...` after a shared/reloaded URL is opened.
- Browser smoke assertions that:
  - confirm task search writes `taskQuery` and `taskId`,
  - reload the page and verify task search and task details restore,
  - confirm status filtering writes `taskStatus` together with the active task search and task id,
  - reload again and verify status/search filters and task details restore,
  - reset task filters and confirm task filter parameters are removed while `taskId` remains.
- PRD, frontend and acceptance documentation updates for recoverable task views.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with task URL restoration included during the first task search/status filter flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a small copy-link affordance for the current task queue/detail view so users do not need to manually copy the address bar.

## 2026-07-15, Slice 73

The task queue/detail view can now be copied as a shareable URL from the task filter panel.

### Added

- `Copy task view link` action inside the task filter form.
- Clipboard copy of the current task view URL, including active task filters and `taskId`.
- Accessible copy status feedback through an `aria-live` message.
- Shared clipboard helper reused by Controller API risk links and task view links.
- Browser smoke coverage that:
  - filters the first task by search and status,
  - reloads to prove task view restoration,
  - copies the task view link,
  - verifies clipboard URL contains `taskStatus`, `taskQuery` and `taskId`.
- PRD, frontend and acceptance documentation updates for task view link sharing.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with task view link copying included in the first task filter flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a matching copy-link affordance for project repository views or expose a small share menu that groups project, task and Controller API links.

## 2026-07-15, Slice 74

The project repository view can now be copied as a shareable URL from the project filter panel.

### Added

- `Copy project view link` action inside the project filter form.
- Clipboard copy of the current project view URL, including active project filters and `projectId`.
- Accessible copy status feedback through an `aria-live` message.
- Shared styling with task and Controller API copy-link actions.
- Browser smoke coverage that:
  - filters the demo repository by search and `READY` status,
  - reloads to prove project view restoration,
  - copies the project view link,
  - verifies clipboard URL contains `projectStatus`, `projectQuery` and `projectId`.
- PRD, frontend and acceptance documentation updates for project view link sharing.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with project view link copying included in the project filter flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Consider grouping project, task and Controller API share actions into a small share menu once the console has more panels.

## 2026-07-15, Slice 75

The Agent run performance panel now supports selectable metric windows and restores that dashboard state from the URL.

### Added

- `DashboardRunMetricsPanel` window selector for 7, 14 and 30 day Agent run metrics.
- `runMetricsDays` URL synchronization and initial state restoration, so reloading `?runMetricsDays=14` keeps the panel on the 14 day window.
- Frontend dashboard refresh paths that keep using the selected run metrics window during manual refresh, task polling and SSE-triggered updates.
- Safer run metrics API client query construction with `URLSearchParams`.
- Browser smoke coverage that:
  - confirms the default 7 day run metrics window,
  - switches to 14 days,
  - verifies `GET /api/dashboard/run-metrics?days=14`,
  - reloads the console and verifies the 14 day selector/window restore.
- PRD, frontend and acceptance documentation updates for selectable Agent run metrics windows.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with run metrics window switching and URL restoration included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a similar lightweight window/limit selector for Recent task activity so operators can inspect more than the default activity slice without leaving the console.

## 2026-07-15, Slice 76

The Recent task activity panel now supports selectable activity limits and restores that dashboard state from the URL.

### Added

- `DashboardActivityPanel` limit selector for the latest 10, 25 and 50 Agent step activity events.
- `activityLimit` URL synchronization and initial state restoration, so reloading `?activityLimit=25` keeps the panel on the 25 event window.
- Frontend dashboard refresh paths that keep using both the selected run metrics window and activity limit during manual refresh, task polling and SSE-triggered updates.
- Safer activity API client query construction with `URLSearchParams`.
- Browser smoke coverage that:
  - confirms the default 10 event activity limit,
  - switches to 25 events,
  - verifies `GET /api/dashboard/activity?limit=25`,
  - reloads the console and verifies the 25 event selector/window restore.
- PRD, frontend and acceptance documentation updates for selectable activity limits.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with activity limit switching and URL restoration included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a compact dashboard share action that copies the current overview state, including run metrics and activity windows, once more dashboard controls are present.

## 2026-07-15, Slice 77

The workspace overview can now be copied as a shareable dashboard URL with the current overview controls preserved.

### Added

- `Copy overview link` action in `DashboardSummaryPanel`.
- Clipboard copy of the current overview URL, including selected `runMetricsDays`, selected `activityLimit` and the `#overview` anchor.
- Accessible copy status feedback through an `aria-live` message.
- Shared copy-link styling with project, task and Controller API link actions.
- Browser smoke coverage that:
  - switches Agent run metrics to 14 days,
  - switches Recent task activity to 25 events,
  - copies the overview link,
  - verifies clipboard URL contains `runMetricsDays=14`, `activityLimit=25` and `#overview`.
- PRD, frontend and acceptance documentation updates for overview link sharing.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with overview link copying included in the Dashboard setup flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Group project, task, overview and Controller API copy actions into a small share pattern once the console has a common toolbar area.

## 2026-07-15, Slice 78

Controller API insight can now be copied as Markdown API documentation from the current filtered view.

### Added

- `Copy API docs` action in the Controller API toolbar.
- Markdown generation from the currently visible Controller API routes, including:
  - project name and active risk filters,
  - HTTP method/path and Controller method,
  - request body, response type and parameters,
  - first-hop Service calls and downstream Mapper/Repository calls,
  - risk score, risk level, risk hints and source location.
- Shared text clipboard helper reused by URL-copy actions and API documentation copy.
- Accessible copy status feedback through an `aria-live` message.
- Browser smoke coverage that:
  - filters Controller APIs to `MEDIUM` + `NO_SECURITY_ANNOTATION`,
  - copies the current API docs,
  - verifies clipboard Markdown contains the demo project title, active filters, `GET /api/users` and the risk code.
- PRD, frontend and acceptance documentation updates for Controller API Markdown documentation.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with Controller API Markdown copy included in the Controller API flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add a backend/export endpoint for generated API documentation if the product needs downloadable files or server-side audit history for docs generation.

## 2026-07-15, Slice 79

Controller API documentation generation is now available through a backend endpoint and the frontend copy action uses that server-generated Markdown.

### Added

- `GET /api/projects/{id}/controller-apis/docs`.
- Owner-scoped Controller API Markdown generation in `ProjectService`, reusing the same `riskLevel` and `riskCode` filtering contract as `GET /api/projects/{id}/controller-apis`.
- Docs response metadata:
  - `projectId`,
  - `repoFullName`,
  - `generatedAt`,
  - `routeCount`,
  - `filteredCount`,
  - normalized filter echo,
  - `markdown`.
- Bounded `limit` support for generated docs, capped at 50 routes and defaulted from the controller to 12.
- Markdown output containing project name, active filters, method/path, Controller method, source location, request/response types, parameters, Service/downstream calls and risk hints.
- Frontend API client support for `getControllerApiDocs`.
- `Copy API docs` now calls the backend docs endpoint, then writes the returned Markdown to the clipboard.
- Browser smoke coverage that waits for `/controller-apis/docs?riskLevel=MEDIUM&riskCode=NO_SECURITY_ANNOTATION&limit=2` before validating clipboard Markdown.
- Backend integration test coverage for the docs endpoint and documentation updates for the backend docs contract.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with server-generated Controller API Markdown copy included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Add downloadable `.md` or persisted documentation export history if users need to keep API docs snapshots across indexing runs.

## 2026-07-15, Slice 80

Controller API Markdown docs can now be downloaded as `.md` files from the Controller API toolbar.

### Added

- `Download API docs` action beside `Copy API docs`.
- Reuse of `GET /api/projects/{id}/controller-apis/docs` for server-generated Markdown downloads.
- Safe Markdown filename generation that includes `controller-api-docs` and uses the `.md` suffix.
- Browser-side Blob download from the returned Markdown payload.
- Separate accessible download status feedback through an `aria-live` message.
- Browser smoke coverage that:
  - waits for the filtered docs endpoint during download,
  - captures the Playwright download event,
  - verifies the suggested filename,
  - reads the downloaded file and checks the Markdown content.
- PRD, frontend and acceptance documentation updates for downloadable Controller API docs.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with API docs download included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users and no `8080`/`5173` listeners.

### Next

- Persist generated API docs snapshots if users need audit/history across indexing runs.

## 2026-07-15, Slice 81

Controller API Markdown docs can now be saved as project-scoped audit snapshots.

### Added

- `controller_api_doc_snapshot` Flyway migration with project/user ownership, filter metadata, route counts, Markdown payload and cleanup-friendly cascade behavior.
- JPA domain and repository support for Controller API docs snapshots.
- Project API endpoints for:
  - saving a generated docs snapshot,
  - listing recent snapshot summaries,
  - loading a single snapshot with Markdown.
- Owner-scoped backend integration coverage for snapshot creation, summary listing and snapshot detail retrieval.
- Frontend API client support for docs snapshot create/list/detail.
- `Save API docs snapshot` action in the Controller API toolbar.
- Recent API docs snapshot list in Repository insight, showing snapshot ID, generated time, route count and filters.
- Browser smoke coverage that saves the filtered docs snapshot and verifies the saved status plus snapshot history row.
- PRD, API, database, frontend and acceptance documentation updates for API docs snapshot history.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with Controller API docs snapshot saving included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and no `8080`/`5173` listeners.

### Next

- Add a saved snapshot download/copy action if users need to reuse historical docs without regenerating them from the current repository state.

## 2026-07-15, Slice 82

Saved Controller API docs snapshots can now be copied or downloaded without regenerating docs from the current repository state.

### Added

- Frontend loading of a single saved docs snapshot through `GET /api/projects/{id}/controller-apis/docs/snapshots/{snapshotId}`.
- `Copy snapshot` action for recent API docs snapshots.
- `Download snapshot` action for recent API docs snapshots, reusing the safe `controller-api-docs` Markdown filename convention.
- Accessible snapshot action status in the recent snapshots header.
- Responsive snapshot action layout for narrow screens.
- Browser smoke coverage that:
  - saves a filtered Controller API docs snapshot,
  - copies the saved snapshot Markdown and validates clipboard content,
  - downloads the saved snapshot Markdown and validates filename plus file content.
- PRD, frontend and acceptance documentation updates for historical snapshot reuse.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with saved snapshot copy/download included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and no `8080`/`5173` listeners.

### Next

- Add retention controls or snapshot deletion if API docs history starts accumulating during longer demo sessions.

## 2026-07-15, Slice 83

Saved Controller API docs snapshots can now be deleted from the project insight history.

### Added

- `DELETE /api/projects/{id}/controller-apis/docs/snapshots/{snapshotId}`.
- Owner-scoped backend deletion that first verifies the project belongs to the current user, then deletes only snapshots under that project.
- Backend integration coverage that:
  - deletes a saved Controller API docs snapshot,
  - verifies the recent snapshot list is empty,
  - verifies the deleted snapshot returns `CONTROLLER_API_DOC_SNAPSHOT_NOT_FOUND`.
- Frontend API client support for snapshot deletion.
- `Delete snapshot` action in the recent API docs snapshot row.
- Immediate UI removal of deleted snapshots with accessible status feedback.
- Browser smoke coverage that saves, copies, downloads and then deletes a Controller API docs snapshot, verifying the empty history state.
- API, PRD, frontend and acceptance documentation updates for snapshot deletion.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with saved snapshot deletion included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and no `8080`/`5173` listeners.

### Next

- Add a compact snapshot retention summary or bulk cleanup action once more history-producing features are present.

## 2026-07-15, Slice 84

Project API docs snapshot history can now be cleared in bulk from Repository insight.

### Added

- `DELETE /api/projects/{id}/controller-apis/docs/snapshots`.
- Owner-scoped backend bulk cleanup that verifies project ownership before deleting project snapshots.
- Clear response with `deletedCount`.
- Backend integration coverage that creates two snapshots, clears them, verifies `deletedCount=2` and confirms the recent snapshot list is empty.
- Frontend API client support for clearing all Controller API docs snapshots for the selected project.
- `Clear snapshots` action in the API doc snapshots header.
- Immediate UI clearing of the snapshot list with accessible deleted-count feedback.
- Browser smoke coverage that:
  - saves two filtered API docs snapshots,
  - clears them through the header action,
  - verifies `deletedCount=2`,
  - verifies the empty snapshot history state.
- API, PRD, frontend and acceptance documentation updates for bulk snapshot cleanup.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ProjectControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with bulk snapshot cleanup included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and no `8080`/`5173` listeners.

### Next

- Shift back toward core Agent workflow improvements, such as richer planner/retriever evidence display or persisted run artifacts beyond API docs.

## 2026-07-15, Slice 85

Task detail now surfaces structured Agent evidence from persisted step JSON.

### Added

- `AgentEvidencePanel` in the task detail flow, placed after the step timeline and before raw model/tool audits.
- Conservative frontend parsing of current run step JSON for:
  - planner summary, plan steps and search queries,
  - retrieved code chunk count, query hit counts and top file/symbol/line evidence,
  - generated patch id, status, branch pair, summary and generation mode,
  - patch safety validation result,
  - sandbox test command, status, duration, exit code and log excerpt,
  - automated patch review risk summary and findings,
  - human approval checkpoint and associated patch.
- Compact evidence styling with scan-friendly metadata chips and highlights.
- Browser smoke coverage that verifies `Agent evidence`, `Planner task plan`, `Retrieved code context`, `Generated patch artifact`, `Sandbox test result`, `Automated patch review` and `Human approval checkpoint` appear in the real task detail flow.
- PRD, Agent workflow, frontend page specification and acceptance checklist updates for the new evidence panel.

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with Agent evidence assertions included.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and no `8080`/`5173` listeners.

### Next

- Persist a dedicated run artifact summary endpoint if users need a shareable, API-level Agent run report beyond the UI-only evidence view.

## 2026-07-15, Slice 86

Agent run evidence can now be exported as an owner-scoped Markdown run report.

### Added

- `GET /api/agent/tasks/{id}/run-report`.
- `AgentRunReportResponse` and `AgentRunReportSectionResponse` DTOs.
- Backend report generation from the current run's persisted `agent_step` JSON, covering:
  - planner,
  - retrieval,
  - generated patch,
  - patch safety,
  - sandbox tests,
  - automated review,
  - human approval checkpoint.
- Markdown report output headed by `# RepoPilot Agent Run Report`.
- Owner-scoped integration coverage that:
  - creates a current run with persisted evidence steps,
  - verifies structured sections,
  - verifies Markdown content,
  - confirms non-owner access returns `AGENT_TASK_FORBIDDEN`.
- Frontend API client support for `AgentRunReport`.
- Task detail loading of the current run report alongside steps, patch, tests and audits.
- `Copy run report` and `Download run report` actions in `AgentEvidencePanel`.
- Browser smoke coverage that copies the run report to the clipboard, downloads the `.md` file, checks the filename, and verifies expected Markdown sections.
- API, PRD, Agent workflow, frontend page specification and acceptance checklist updates.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with run report copy/download included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and no `8080`/`5173` listeners.

### Next

- Add a saved Agent run report snapshot if users need immutable run reports after later reruns replace the current run.

## 2026-07-16, Slice 87 - 运行报告留档版

Agent 当前运行报告现在可以保存为任务内不可变历史快照，方便重新运行或 Regenerate 后继续复用旧报告。

### Added

- `agent_run_report_snapshot` Flyway migration with task/project/user ownership, run metadata, section count, Markdown payload and cleanup-friendly cascade behavior.
- JPA domain, repository and DTO support for saved Agent run report snapshots.
- Agent task API endpoints for:
  - saving the current run report as a snapshot,
  - listing recent run report snapshot summaries,
  - loading a single saved snapshot with Markdown.
- Owner-scoped backend integration coverage for snapshot creation, summary listing, detail retrieval and non-owner isolation.
- Frontend API client support for run report snapshot create/list/detail.
- `保存报告快照` action in the Agent evidence panel.
- Recent `运行报告快照` list in task detail, with Chinese copy/download actions for historical Markdown reports.
- Browser smoke coverage that saves a run report snapshot, copies the saved Markdown, downloads it as `.md`, and verifies filename plus report content.
- PRD, API, database, Agent workflow, frontend page specification and acceptance checklist updates for run report snapshot history.

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with run report snapshot save/copy/download included.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots.

### Next

- Continue turning task detail and dashboard copy into natural Chinese so the product feels like a local Chinese engineering tool instead of a translated demo.
- Add retention or cleanup controls for Agent run report snapshots once longer demo sessions start accumulating history.

## 2026-07-16, Slice 88 - 任务详情中文化版

任务详情核心链路已从英文演示文案转为更自然的中文产品文案，保留后端枚举、step name、recipe id 和 Markdown 报告标题作为工程排查锚点。

### Added

- 中文化控制台主导航和顶栏行动语。
- 中文化任务详情状态卡、任务摘要、运行/取消/准备 PR 操作和忙碌状态提示。
- 中文化 Agent 步骤时间线、Agent 执行证据面板、运行报告快照区的上下文文案。
- 中文化模型调用审计、工具调用审计、补丁详情、文件级 diff 摘要、沙箱测试运行、人工审批和 PR 前置检查面板。
- 默认审批备注改为中文：`沙箱验证已通过。`
- Browser smoke 脚本同步改用中文按钮、标题、meta label 和 aria label 断言，继续保留 Markdown 报告英文章节断言。
- 前端页面说明补充任务详情中文产品文案边界。

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with Chinese task-detail labels and actions.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots.

### Next

- Continue Chinese productization for Dashboard, project insight, Controller API documentation snapshots and settings panels.
- Consider localizing backend PR preflight/check messages while keeping machine-readable check codes unchanged.

## 2026-07-16, Slice 89 - 工作台配置中文化版

工作台概览、Agent 运行表现、最近任务活动和三块配置面板已切换为更自然的中文产品文案，保留 provider、mode、readiness badge 等工程状态原文作为排查锚点。

### Added

- 中文化工作台概览指标卡、加载态、取消/失败摘要和概览链接复制反馈。
- 中文化 Agent 运行表现标题、指标卡、窗口选择、每日趋势空状态和成功次数说明。
- 中文化最近任务活动标题、数量选择、空状态和活动行任务状态说明。
- 中文化 Coder 配置面板的标题、配置标签、状态说明、缺失配置和密钥安全提示。
- 中文化 GitHub 发布配置面板的标题、发布模式、Token 状态、远程 PR 状态和本地草稿说明。
- 中文化 Sandbox 运行时面板的标题、镜像/Docker/缓存/检查标签、沙箱就绪检查和路径说明。
- Browser smoke 脚本同步改用中文 Dashboard、配置面板、下拉 aria-label 和概览复制状态断言。
- 前端页面说明补充 Dashboard 与配置面板中文产品文案边界。

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `./scripts/browser-smoke.sh` passes with Chinese Dashboard and settings labels.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots.

### Next

- Continue Chinese productization for project list, task list, project insight, Controller API documentation snapshots and remaining filter/action labels.
- Localize backend PR preflight/check messages while keeping machine-readable check codes unchanged.

## 2026-07-16, Slice 90 - 项目任务工作区中文化版

项目接入、项目筛选、任务创建/筛选、仓库洞察和 Controller API 文档快照已切换为中文产品文案，保留仓库名、Java 符号、HTTP 方法、风险码、后端状态枚举和后端生成 Markdown 的工程原文。

### Added

- 中文化登录和注册表单标签、按钮和登录反馈。
- 中文化项目接入表单、项目筛选表单、项目行操作、项目数量摘要、空状态和项目视图链接复制反馈。
- 中文化任务创建表单、任务筛选表单、任务数量摘要、空状态和任务视图链接复制反馈。
- 中文化仓库洞察面板的项目选择、刷新操作、文件树、符号摘要、代码搜索和空状态。
- 中文化 Controller API 风险概览、风险筛选、风险视图/路由链接复制、接口文档复制/下载/保存和接口文档快照复制/下载/删除/清空反馈。
- Browser smoke 脚本同步改用中文表单标签、按钮、aria-label、空状态和复制/下载/快照状态断言；后端生成的 Controller API Markdown 内容继续按英文工程文本断言。
- 前端页面说明补充项目任务工作区中文产品文案边界。

### Verified

- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `./scripts/browser-smoke.sh` passes with Chinese project/task workspace labels and Controller API snapshot actions.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots.

### Next

- Localize remaining shared labels and backend-facing messages where they are product copy rather than machine-readable status.
- Continue deeper Agent capability work: real model-backed coding, retry/repair loops and PR publishing polish.

## 2026-07-16, Slice 91 - Agent 报告中文化版

Agent 运行报告、补丁摘要、自动审查摘要和实时流状态已切换为中文产品文案，继续保留 step name、状态枚举、recipe id、命令、路径和代码符号等工程锚点。

### Added

- 中文化 `GET /api/agent/tasks/{id}/run-report` 的 section 标题、摘要、事实、重点和 Markdown 元信息。
- 中文化运行报告快照保存的 Markdown 内容，历史快照读取时保留保存当时的中文报告。
- 中文化 Planner 输出的步骤标题、原因、summary 和测试策略，写入 `agent_step.output_json`。
- 中文化内置 Spring recipe 的 patch summary、LLM Coder 草稿 summary、安全规划回退 summary 和 `.repopilot/task-*-plan.md` 内容。
- 中文化自动补丁审查的 summary 和 finding message，保留 `riskLevel` 与 finding code。
- 中文化 Agent 运行失败/取消/进入人工审批的后端摘要，以及任务实时流前端角标和 SSE task/complete message。
- 为 SSE 响应补充 UTF-8 `Content-Type`，避免中文事件消息在测试或客户端中被错误解码。
- 更新后端集成/服务测试、浏览器 smoke、API 文档、前端页面规格和验收清单，断言中文报告与中文补丁摘要。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskControllerIntegrationTest,AgentTaskServiceRegenerationTest,TaskStreamControllerIntegrationTest,PatchGenerationServiceTest,AgentTaskServiceFixtureCoderIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `./scripts/browser-smoke.sh` passes with Chinese Agent run report Markdown, Chinese patch summaries, Chinese review summaries and Chinese stream labels.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots.

### Next

- 继续中文化 PR preflight blocker/ready message、PR body 和审批错误提示，保留 check code 与 PR 状态枚举。
- 继续推进真实模型编码、失败修复循环和远端 GitHub PR 发布体验。

## 2026-07-16, Slice 92 - PR 发布链路中文化版

PR 发布前置检查、审批错误提示、本地 PR 草稿正文和 commit message 已切换为中文产品文案，继续保留 check code、状态枚举、分支名、commit sha、GitHub 和 PR 等工程锚点。

### Added

- 中文化 `PullRequestService.preflight` 的任务状态、补丁审批、沙箱测试、本地草稿和远端 GitHub label/message/blockers。
- 中文化准备 PR 前的补丁审批、沙箱测试和任务状态错误提示。
- 中文化审批服务的补丁归属、用户归属、任务状态和补丁状态错误提示。
- 中文化 GitHub 发布失败、token 缺失、本地 PR 元数据缺失等 PR 发布错误提示。
- 中文化本地 PR 记录标题、PR body 和 Git commit message 说明。
- 前端 PR 面板改用“拉取请求”和“打开 PR”，Browser smoke 同步断言中文 blocker、ready 提示、PR body 和已准备状态。
- API、沙箱/GitHub、MCP 工具和验收文档同步 PR 链路中文示例。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PullRequestServiceTest,PullRequestApprovalIntegrationTest,DashboardControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `./scripts/browser-smoke.sh` passes with Chinese PR blocker, ready message, PR body and already-prepared state visible in the real task detail flow.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots.

### Next

- 继续推进真实模型编码、失败修复循环和远端 GitHub PR 发布体验。
- 在启用 GitHub token 的环境中补充远端 PR 创建与失败重试演示。

## 2026-07-16, Slice 93 - 修复循环增强版

RepairAgent 从单一的缺测试依赖修复扩展为 Maven 失败日志分流器：继续支持补 `spring-boot-starter-test`，并新增常见 Java 标准库缺 import 导致 `cannot find symbol` 编译失败时的确定性补 import 修复。

### Added

- `PatchRepairService.repairMavenFailure` 作为统一修复入口，根据 Maven 日志自动选择可用修复策略。
- 保留 `REPAIR_MISSING_TEST_DEPENDENCY` 修复路径，并将修复 summary 中文化。
- 新增 `REPAIR_MISSING_JAVA_IMPORT` 修复路径：从 Maven 编译日志识别缺失符号、定位 `src/main/java` 或 `src/test/java` 文件、映射常见 Java 标准库 import，并生成合并后的第二版 patch。
- `AgentTaskService` 的修复循环改用通用 `repairMavenFailure`，后续可继续挂接更多 RepairAgent 策略。
- 单元测试覆盖缺测试依赖修复、缺 `java.util.Objects` import 修复，以及 Agent 运行修复入口调用。
- PRD、MVP scope、Agent workflow、database、sandbox/GitHub 和验收文档同步 RepairAgent 新能力边界。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchRepairServiceTest,AgentTaskServiceRegenerationTest test` passes, including `git apply --check` for the repaired Java import patch.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots.

### Next

- 继续推进真实模型编码和远端 GitHub PR 发布演示。
- 继续扩展 RepairAgent 到断言失败、编译错误和可由检索上下文定位的文件级修复。

## 2026-07-16, Slice 94 - 远端 PR 发布验证版

远端 GitHub PR 发布路径补上本地可重复集成验证：不用真实 GitHub token，也能证明 RepoPilot 会真实推送 target branch、调用 GitHub PR API，并在失败后复用已有记录重试到 `OPEN`。

### Added

- `GitHubPullRequestServiceTest` 创建临时 bare Git origin 和本地工作区，真实执行 `git push origin {targetBranch}`。
- 本地 HTTP server 模拟 GitHub `/repos/example/demo/pulls` API，断言 Authorization header、title、head、base 和 body。
- 断言发布成功后返回 PR number/url，并写入 `remotePushedAt`。
- `PullRequestServiceTest` 覆盖已有 `FAILED` PR 记录从 `FAILED_PR_CREATION` 重试并更新为 `OPEN`。
- API、沙箱/GitHub 和验收文档补充远端发布本地验证与失败重试行为。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=GitHubPullRequestServiceTest,PullRequestServiceTest,PullRequestApprovalIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots.

### Next

- 继续推进真实模型编码和真实 GitHub 仓库 token 环境下的发布演示。
- 继续补强远端 PR 发布失败时的前端错误解释和一键重试体验。

## 2026-07-16, Slice 95 - PR 失败重试体验版

远端 PR 发布失败时，控制台不再只展示原始错误字符串，而是给出中文失败类型、原因、下一步和原始错误，并把任务详情操作从“准备 PR”切换为“重试发布 PR”。

### Added

- 任务详情按钮根据 `FAILED_PR_CREATION` 或 `pullRequest.status=FAILED` 切换为“重试发布 PR”，忙碌提示同步切换为“正在重试发布 PR”。
- `PullRequestPanel` 新增 PR 发布失败说明块，分类解释 GitHub token 缺失、target branch push 失败和 GitHub PR API 创建失败。
- 失败说明保留原始错误消息，便于工程排障和面试演示。
- 前端页面规格和验收清单同步 PR 失败解释与重试入口要求。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots. Port `8080` is free after smoke; the existing frontend dev server remains on `5173`.

### Next

- 继续推进真实模型编码和真实 GitHub token 环境下的远端发布演示。

## 2026-07-16, Slice 96 - Coder 来源审计版

补丁生成结果现在会保留具体来源和模型名，真实 Coder 模型、fixture Coder、本地 recipe 和安全规划回退在 Patch API、Agent step output、运行报告、模型调用审计和前端补丁面板里都能区分。

### Added

- `patch_record` 新增 `generation_provider` 和 `generation_model`，记录补丁由 recipe catalog、fixture、OpenAI-compatible Coder 或安全规划回退生成。
- `PatchGenerationService` 在 recipe、LLM Coder draft 和 fallback 三条路径写入 provider/model；fixture 模式写入 `LOCAL_FIXTURE / fixture-coder`，OpenAI-compatible 模式会写入供应商返回的模型名。
- `ModelCallLogService` 支持成功后动态确定 provider/model，`generate_patch` 的 model call audit 不再只能显示默认占位来源。
- Agent `generate_patch` step output、运行报告 patch section、Patch API DTO 和前端补丁面板展示 generation mode、provider 和 model。
- 后端单元/集成测试覆盖 patch 来源字段和 fixture Coder 的 model call audit 来源。
- 数据库、API、Agent workflow、前端页面规格和验收清单同步 Coder 来源审计字段。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PatchGenerationServiceTest,AgentTaskServiceFixtureCoderIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes, including Flyway V15 validation and migration.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots. Port `8080` is free after smoke; the existing frontend dev server remains on `5173`.

### Next

- 继续推进真实 OpenAI-compatible Coder token 环境下的端到端演示和真实 GitHub PR 发布演示。

## 2026-07-16, Slice 97 - OpenAI兼容Coder端到端验证版

`openai-compatible` Coder 不再只停留在 HTTP client 单元验证；现在已有 Agent 生产状态机级测试证明它会真实请求 Chat Completions 兼容接口，并把返回的 raw diff 接入 parser、安全预检、Docker 沙箱测试、ReviewAgent 和人工审批暂停点。

### Added

- `AgentTaskServiceFixtureCoderIntegrationTest` 新增本地 `/v1/chat/completions` HTTP stub，用真实 `ConfiguredCoderModelClient(openai-compatible)` 驱动 Agent run。
- 测试断言 Authorization header、模型名、diff-only prompt、任务标题和检索上下文文件/符号进入模型请求体。
- 测试断言 HTTP stub 返回的 raw unified diff 持久化为 `LLM_CODER_DRAFT`，并在 patch、step output 和 model call audit 中展示 `OPENAI_COMPATIBLE / gpt-repopilot-test`。
- 测试断言 OpenAI-compatible Coder patch 继续通过 diff parser、安全预检、Docker 沙箱 `mvn test`、自动审查和 `waiting_human_approval` 暂停点。
- Agent workflow 和验收清单同步 OpenAI-compatible Coder 端到端验证边界。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskServiceFixtureCoderIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots. Port `8080` is free after smoke; the existing frontend dev server remains on `5173`.

### Next

- 在真实 token 环境中补充一次真实模型端到端演示，并继续推进真实 GitHub PR 发布演示。

## 2026-07-16, Slice 100 - 真实Coder演示脚本版

真实模型演示从“能检查环境”推进到“有 token 时可跑 API 级端到端链路”：新增脚本会用真实 OpenAI-compatible Coder 生成一个最小 diff，并验证它继续通过统一 parser、安全预检、Docker 沙箱测试、ReviewAgent 和人工审批暂停点。

### Added

- 新增 `./scripts/real-coder-demo.sh`，负责启动 PostgreSQL/Redis、按需启动真实 Coder 后端、运行 API 演示并清理临时业务数据。
- 新增 `./scripts/real-coder-demo.mjs`，通过后端 API 注册临时用户、创建本地 demo 项目、clone/index、创建不会命中本地 recipe 的小任务并启动 Agent run。
- 演示任务要求真实模型只新增 `.repopilot/real-coder-demo-note.md`，降低真实模型输出对 Java 代码稳定性的影响，同时仍验证完整 patch/test/review 链路。
- 脚本验证 `generationMode=LLM_CODER_DRAFT`、`generationProvider=OPENAI_COMPATIBLE`、成功的 `generate_patch` model call、`validate_patch_safety`、沙箱 `mvn -q test`、`review_patch` 和 `WAITING_HUMAN_APPROVAL`。
- 脚本将脱敏运行证据写入 `output/real-coder-demo/last-run.json`，不输出模型 key、GitHub token 或 Authorization header。
- 脚本 README、Agent workflow 和验收清单同步真实 Coder API 演示入口。

### Verified

- `bash -n scripts/real-coder-demo.sh scripts/real-token-demo-check.sh scripts/browser-smoke.sh` passes.
- `node --check scripts/real-coder-demo.mjs` and `node --check scripts/browser-smoke.mjs` pass.
- `env -u REPOPILOT_CODER_API_KEY -u OPENAI_API_KEY REPOPILOT_CODER_MODE=disabled REPOPILOT_CODER_MODEL= ./scripts/real-coder-demo.sh` exits 2 when no backend is running and explains the missing real Coder env vars without printing secrets.
- `git diff --check` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` real Coder demo users, `0` Controller API doc snapshots and `0` Agent run report snapshots. Port `8080` is free after smoke; the existing frontend dev server remains on `5173`.

说明：当前环境没有真实模型 token，因此这里不声称已经跑通过真实 `OPENAI_COMPATIBLE` 模型成功路径。配置 `REPOPILOT_CODER_MODE`、模型 key 和模型名后即可运行该脚本留存真实演示证据。

### Next

- 在真实 token 环境中执行 `./scripts/real-coder-demo.sh` 并留存一次真实模型演示证据。
- 继续推进真实 GitHub token 环境下的远端 PR 发布演示。

## 2026-07-16, Slice 101 - 真实GitHubPR演示脚本版

远端 PR 演示从本地 stub 验证推进到可在真实 GitHub token 环境中执行：新增脚本会创建临时任务，生成稳定的 User count API patch，通过审批后真实 push target branch 并创建 GitHub PR。

### Added

- 新增 `./scripts/real-github-pr-demo.sh`，要求显式设置 `REPOPILOT_REAL_GITHUB_PR_CONFIRM=create-pr`、`REPOPILOT_REAL_GITHUB_PR_REPO_URL`、`REPOPILOT_GITHUB_ENABLED=true` 和 GitHub token，避免误创建真实 PR。
- 新增 `./scripts/real-github-pr-demo.mjs`，通过 API 创建临时用户、指定 GitHub 项目、clone/index、创建 User count API 任务、运行 Agent、审批 patch、检查 PR preflight 并调用 PR 发布接口。
- 脚本验证 `SPRING_USER_COUNT_RECIPE`、`LOCAL_RECIPE_CATALOG`、沙箱测试通过、远端 PR preflight ready、PR record `OPEN`、PR number/url、target branch、commit sha、`remotePushedAt` 和 `openedAt`。
- 脚本将脱敏运行证据写入 `output/real-github-pr-demo/last-run.json`，不输出 GitHub token、模型 key 或 Authorization header。
- `.env.example`、脚本 README、沙箱/GitHub 集成设计和验收清单同步真实 GitHub PR 演示入口。

### Verified

- `bash -n scripts/real-github-pr-demo.sh scripts/real-coder-demo.sh scripts/real-token-demo-check.sh scripts/browser-smoke.sh` passes.
- `node --check scripts/real-github-pr-demo.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/browser-smoke.mjs` pass.
- `./scripts/real-github-pr-demo.sh` exits 2 without confirmation/repo/token and explains missing `REPOPILOT_REAL_GITHUB_PR_CONFIRM`, `REPOPILOT_REAL_GITHUB_PR_REPO_URL`, `REPOPILOT_GITHUB_ENABLED` and token, without touching GitHub.
- `git diff --check` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` real Coder demo users, `0` real GitHub PR demo users, `0` Controller API doc snapshots and `0` Agent run report snapshots. Port `8080` is free after smoke; the existing frontend dev server remains on `5173`.

说明：当前环境没有真实 GitHub token 和可丢弃远端 demo 仓库，因此这里不声称已经真实创建远端 PR。配置确认开关、GitHub token 和 demo 仓库 URL 后即可运行该脚本留存真实 PR 证据。

### Next

- 在真实 GitHub token 和可丢弃 demo 仓库环境中执行 `./scripts/real-github-pr-demo.sh`，打开真实 PR 链接演示标题、描述和修改文件。

## 2026-07-20, Slice 136 - Worker业务重试结构化Smoke版

Worker 业务闭环 smoke 现在会在真实 Spring Boot 后端 + 真实 FastAPI Worker + 本地 OpenAI-compatible Coder stub 链路里注入一次 Coder 模型 429，验证 Worker 可恢复重试、后端结构化 `retryAudit`、Java 业务 diff、Docker 沙箱、人工审批和本地 PR 草稿仍能连成一条链。

### Changed

- `scripts/agent-worker-business-smoke.mjs` 的模型 stub 默认先返回一次 HTTP 429，再返回 `GET /api/users/summary` raw unified diff；可通过 `REPOPILOT_WORKER_BUSINESS_SMOKE_INJECT_CODER_RETRY=false` 关闭注入。
- Worker smoke 环境显式设置 `REPOPILOT_WORKER_RETRY_MAX_ATTEMPTS=2` 和 `REPOPILOT_WORKER_RETRY_BACKOFF_SECONDS=0`，让临时模型失败快速恢复且可重复。
- Business smoke 从标准 `/api/agent/runs/{runId}/model-calls` 响应断言 `retryAudit.attemptCount=1`、`recovered=true`、`firstFailureType=WorkerModelError` 和首次失败摘要包含 `HTTP 429`。
- 证据文件新增 `retryAudit`、模型请求次数、是否注入重试和模型 stub 响应状态序列。
- Agent Worker README、Agent workflow 和验收清单同步业务闭环 smoke 的 Coder 429 恢复和结构化审计验证。

### Verified

- `node --check scripts/agent-worker-business-smoke.mjs` passes.
- `bash -n scripts/agent-worker-business-smoke.sh` passes.
- `./scripts/agent-worker-business-smoke.sh` passes for `agent-worker-business-smoke-1784516177-43283@example.test`, task `#3651`, patch `#1515` and draft PR branch `repopilot/task-3651`.
- Business smoke evidence `output/agent-worker-business-smoke/last-run.json` shows model statuses `[429, 200]`, `retryAudit.attemptCount=1`, `recovered=true`, `firstFailureType=WorkerModelError`, patch `LLM_CODER_DRAFT / OPENAI_COMPATIBLE / gpt-worker-business-smoke`, sandbox test `PASSED` and task status `DONE`.
- Smoke cleanup leaves `0` `agent-worker-business-smoke-%` users and `0` `Worker Coder 业务演示%` tasks.
- `PYTHONPATH=. python3 -m unittest discover -s tests` passes in `agent-worker`.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes in `backend`.
- `npm run build` passes in `frontend`.
- `git diff --check` passes.

### Next

- 继续扩展更多 Worker Coder 真实业务 diff 场景，并保留 parser、安全预检、Docker 沙箱、风险审查和人工审批门。
- 在真实 GitHub token 和可丢弃 demo 仓库环境中执行真实远端 PR 发布演示，沉淀中文操作者 runbook。

## 2026-07-20, Slice 135 - Worker重试审计结构化版

模型/工具审计查询 API 现在直接返回结构化 retry 摘要，前端优先使用 `retryAudit` 字段展示重试恢复诊断，旧 JSON 字符串解析仅作为兼容兜底。

### Changed

- 新增 `RetryAuditSummaryResponse`，从 `retryAttempts` / `retryAttemptCount` 中提取 `attemptCount`、`recovered`、`firstFailureType` 和 `firstFailureMessage`。
- `ToolCallLogResponse` 和 `ModelCallLogResponse` 新增 `retryAudit` 字段；没有 retry 证据时返回 `null`。
- 前端 `ToolCallPanel` / `ModelCallPanel` 优先读取 `call.retryAudit`，继续兼容旧 `outputJson` / `responseJson` 中的 retry 字段。
- 工具/模型审计 controller 集成测试覆盖结构化 retry 字段、owner scope 和敏感字段脱敏。
- API 设计、前端页面规格和验收清单同步结构化 retry 审计响应。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=ToolCallLogControllerIntegrationTest,ModelCallLogControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes in `backend`.
- `npm run build` passes in `frontend`.
- `node --check ../scripts/browser-smoke.mjs && bash -n ../scripts/browser-smoke.sh` passes in `frontend`.
- `./scripts/browser-smoke.sh` passes for `browser-smoke-1784515687-26391@example.test`; screenshot: `output/playwright/repopilot-browser-smoke.png`.

### Next

- 继续扩展更多 Worker Coder 真实业务 diff 场景，并保留安全预检、沙箱测试、风险审查和人工审批后置门。
- 继续把真实 GitHub PR 发布演示整理成更可复用的中文演示脚本和文档。

## 2026-07-20, Slice 134 - Worker重试审计可见版

Worker retry 诊断从运行报告继续下沉到前端模型/工具审计列表：任务详情的工具调用审计和模型调用审计会直接读取 `retryAttemptCount` / `retryAttempts`，在列表头和单条记录中标记 `已重试恢复`，同时展示失败尝试次数和首次失败摘要。

### Changed

- 前端新增 retry audit 摘要解析，从 tool call `outputJson` 和 model call `responseJson` 中提取 `retryAttemptCount`、`retryAttempts[0].message` 或 `errorType`。
- 工具调用审计和模型调用审计面板新增 `重试恢复 N 条` 汇总 pill、单条 `已重试恢复` badge 和中文恢复摘要。
- Browser smoke 会在当前临时任务 run 中注入一条只读工具 retry audit 和一条模型 retry audit，刷新任务详情后验证 `Worker 重试恢复证据`、工具审计与模型审计都显示恢复标记。
- 前端页面规格和验收清单同步审计列表的 retry 可见性。

### Verified

- `npm run build` passes in `frontend`.
- `node --check scripts/browser-smoke.mjs && bash -n scripts/browser-smoke.sh` passes.
- `./scripts/browser-smoke.sh` passes for `browser-smoke-1784515044-9415@example.test`; screenshot: `output/playwright/repopilot-browser-smoke.png`.

### Next

- 继续把 Worker retry 诊断接入模型/工具审计查询 API 的结构化字段，减少前端从 JSON 字符串解析诊断信息。
- 继续扩展更多 Worker Coder 真实业务 diff 场景，并保留安全预检、沙箱测试、风险审查和人工审批后置门。

## 2026-07-17, Slice 133 - Worker重试报告可见版

Worker retry 从 smoke 证据推进到正式诊断视图：可恢复模型/只读工具失败现在会进入 model/tool audit，后端运行报告汇总为 `Worker 重试恢复证据`，前端任务详情优先渲染后端 run report sections，因此 retry 诊断能直接出现在 Agent 执行证据、Markdown 报告和报告快照中。

### Changed

- `call_with_retry(...)` 增加 `on_retry` 钩子，Worker 模型客户端和只读工具客户端会记录失败尝试的 attempt、error type、message 和 retryable。
- Planner/Coder model call response 新增 `retryAttempts` / `retryAttemptCount`；只读工具 output 同步新增 `retryAttempts` / `retryAttemptCount`。
- 后端 `run-report` 扫描当前 run 的 `model_call_log.response_json` 与 `tool_call_log.output_json`，在存在 retry audit 时生成中文 `Worker 重试恢复证据` section。
- 前端 `AgentEvidencePanel` 有 run report 时直接展示后端 report sections，让 retry section 自动进入任务详情证据链。
- Planner/Coder model smoke 增加 retry audit 字段断言，文档与验收清单同步。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest agent-worker/tests/test_backend_api_client.py agent-worker/tests/test_worker_model_client.py agent-worker/tests/test_planning_model_node.py agent-worker/tests/test_patch_model_node.py` passes.
- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests && python3 -m compileall -q agent-worker/app agent-worker/tests` passes with 25 tests.
- `bash -n scripts/agent-worker-planner-smoke.sh scripts/agent-worker-coder-model-smoke.sh scripts/agent-worker-coder-smoke.sh scripts/agent-worker-smoke.sh scripts/agent-worker-callback-smoke.sh scripts/agent-worker-tool-smoke.sh scripts/agent-worker-node-smoke.sh scripts/agent-worker-business-smoke.sh scripts/real-token-demo-check.sh scripts/browser-smoke.sh && node --check scripts/browser-smoke.mjs scripts/real-coder-demo.mjs scripts/real-github-pr-demo.mjs` passes.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentTaskControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes in `frontend`.
- Worker smoke suite passes: planner model retry audit, coder model retry audit, coder patch node, worker contract, callback client, tool client, node flow and business smoke.
- `./scripts/agent-worker-business-smoke.sh` passes with task `#3526`, patch `#1459` and draft PR branch `repopilot/task-3526`.
- `./scripts/browser-smoke.sh` passes for `browser-smoke-1784232769-10177@example.test`; screenshot: `output/playwright/repopilot-browser-smoke.png`.

### Next

- 继续把 Worker retry 诊断接入更细的前端模型/工具审计摘要，例如在审计列表里直接标记“已重试恢复”。
- 继续扩展更多 Worker Coder 真实业务 diff 场景，并保留安全预检、沙箱测试、风险审查和人工审批后置门。

## 2026-07-17, Slice 132 - Worker重试Smoke证据版

Worker retry 从“单元测试证明分类正确”推进到“真实 FastAPI Worker smoke 证明临时故障可恢复”：Planner smoke 会让后端 `/context` 首次返回 `503`、Planner 模型首次返回 `429`，随后确认 Worker 重试后完成 plan/retrieve/patch/后置门；Coder model smoke 会让 Coder 模型首次返回 `429`，随后确认恢复后的 raw diff 仍生成 `LLM_CODER_DRAFT` 并通过 safety、sandbox、review 和 approval-ready。

### Changed

- `agent-worker-planner-smoke.sh` 注入一次后端只读工具 `503` 和一次 Planner 模型 `429`，并把 `retryEvidence.transientFailures`、`contextGetCount=2`、`plannerRequestCount=2` 写入 smoke 证据。
- `agent-worker-coder-model-smoke.sh` 注入一次 Coder 模型 `429`，并把 `retryEvidence.coderRequestCount=2` 写入 smoke 证据。
- Worker README、Agent workflow、scripts README 和验收清单同步端到端 retry smoke 行为。

### Verified

- `./scripts/agent-worker-planner-smoke.sh` passes and records backend context GET retry plus Planner model retry evidence.
- `./scripts/agent-worker-coder-model-smoke.sh` passes and records Coder model retry evidence.
- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests && python3 -m compileall -q agent-worker/app agent-worker/tests` passes with 25 tests.
- `bash -n scripts/*.sh && node --check scripts/*.mjs` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes in `frontend`.
- Worker smoke suite passes: coder model, planner, coder patch node, worker contract, callback client, tool client, node flow and business smoke.
- `./scripts/agent-worker-business-smoke.sh` passes with task `#3460`, patch `#1433` and draft PR branch `repopilot/task-3460`.
- `./scripts/browser-smoke.sh` passes for `browser-smoke-1784231306-68389@example.test`; screenshot: `output/playwright/repopilot-browser-smoke.png`.

### Next

- 继续把 Worker retry 证据暴露到运行报告或前端诊断视图，帮助定位临时模型/工具故障。
- 继续扩展更多 Worker Coder 真实业务 diff 场景，并保留安全预检、沙箱测试、风险审查和人工审批后置门。

## 2026-07-17, Slice 131 - Worker可恢复重试语义版

Worker primary 从“失败能收口”推进到“临时故障可恢复、确定性失败不乱重试”：Python Worker 增加统一 retry 工具和配置，只对 OpenAI-compatible Planner/Coder 模型调用、后端只读工具 GET 请求做有限重试；写 step/patch/status/approval 等 callback 不做透明重试，避免网络抖动后重复落库或把审批状态推进两次。

### Changed

- 新增 `app.retry.RetryPolicy` / `call_with_retry(...)`，统一用 `retryable` 异常属性区分可恢复错误。
- `WorkerModelClient` 增加 `WorkerModelError` 分类：HTTP `408/425/429/5xx`、网络错误和超时会按 `REPOPILOT_WORKER_RETRY_MAX_ATTEMPTS` / `REPOPILOT_WORKER_RETRY_BACKOFF_SECONDS` 重试；HTTP `400` 等请求错误、无效 JSON、非对象响应和输出契约错误不重试。
- `BackendApiClient` 只对 `load_run_context`、`list_project_files`、`search_code`、`read_project_file`、`list_symbols` 等只读工具 GET 请求启用重试；`record_step`、`record_model_call`、`record_patch`、`update_status` 等写型 callback 不透明重试。
- `.env.example`、Worker README、Agent workflow 和验收清单同步 Worker retry 配置、可重试 HTTP 分类和不可重试安全/测试失败边界。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest agent-worker/tests/test_backend_api_client.py agent-worker/tests/test_worker_model_client.py agent-worker/tests/test_initial_graph_builder.py agent-worker/tests/test_patch_model_node.py` passes with 19 tests.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests && python3 -m compileall -q agent-worker/app agent-worker/tests` passes with 25 tests.
- `bash -n scripts/*.sh && node --check scripts/*.mjs` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes in `frontend`.
- Worker smoke suite passes: coder model, planner, coder patch node, worker contract, callback client, tool client, node flow and business smoke.
- `./scripts/agent-worker-business-smoke.sh` passes with task `#3401`, patch `#1407` and draft PR branch `repopilot/task-3401`.
- `./scripts/browser-smoke.sh` passes for `browser-smoke-1784230650-48973@example.test`; screenshot: `output/playwright/repopilot-browser-smoke.png`.
- `agent-worker/tests/test_worker_model_client.py` verifies 429 retries into success and 400 is not retried.
- `agent-worker/tests/test_backend_api_client.py` verifies read-only backend GET retries after 503, while step callback POST does not retry.

### Next

- 继续把 Worker retry 证据接入端到端 smoke stub，让 Planner/Coder smoke 能覆盖一次真实 429/503 后恢复。
- 继续扩展更多 Worker Coder 真实业务 diff 场景，并保留安全预检、沙箱测试、风险审查和人工审批后置门。

## 2026-07-17, Slice 130 - Worker失败取消保护版

Worker primary 从“成功路径可接管”推进到“失败和取消不会悬挂或反向推进”：Worker 图节点异常、diff 安全门失败、沙箱应用失败、Maven 测试失败或 review gate 异常时，会显式通过后端 `/status` 把 run/task 标为失败并关闭 stream；后端对已终止 run/task 加上 late callback 状态门，防止用户取消后 Worker 继续写 patch、跑后置门或把任务推进到人工审批。

### Changed

- `run_initial_nodes_safely(...)` 在后台节点异常时先回写当前 step `FAILED`，再调用 `BackendApiClient.update_status(...)` 写入 `FAILED_PATCH_GENERATION / FAILED`、`complete_stream=true` 和中文 stream message。
- `generate_patch(...)` 在 post-patch gates 中检查真实后端响应：diff safety 不通过、sandbox apply 未成功、Maven 测试未通过或 review 未成功时，会按失败类型回写 `FAILED_PATCH_GENERATION` 或 `FAILED_TEST`，避免 Worker primary run 长时间停在 `RUNNING`。
- `AgentWorkerCallbackService` 对已终止 run/task 增加保护：`patches`、`safety`、`sandbox-tests`、`review`、`approval-ready` 和非幂等 `/status` 请求在 run/task 已完成、失败或取消后返回 `409 AGENT_WORKER_RUN_TERMINATED`。
- Worker README、Agent workflow、backend modules 和验收清单同步 Worker primary 失败收口、取消保护和 late callback 拒绝语义。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest agent-worker/tests/test_initial_graph_builder.py agent-worker/tests/test_patch_model_node.py` passes with 7 tests.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerCallbackControllerIntegrationTest test` passes.
- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests && python3 -m compileall -q agent-worker/app agent-worker/tests` passes with 21 tests.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes in `frontend`.
- `bash -n scripts/*.sh && node --check scripts/*.mjs` passes.
- Worker smoke suite passes: coder model, planner, coder patch node, worker contract, callback client, tool client, node flow and business smoke.
- `./scripts/agent-worker-business-smoke.sh` passes with task `#3342`, patch `#1381` and draft PR branch `repopilot/task-3342`.
- `./scripts/browser-smoke.sh` passes for `browser-smoke-1784229739-22098@example.test`; screenshot: `output/playwright/repopilot-browser-smoke.png`.
- `AgentWorkerCallbackControllerIntegrationTest` verifies cancelled runs reject late Worker status success overrides and patch writes with `AGENT_WORKER_RUN_TERMINATED`.
- `agent-worker/tests/test_initial_graph_builder.py` verifies node exceptions call `/status` with `FAILED_PATCH_GENERATION / FAILED` and `complete_stream=true`.
- `agent-worker/tests/test_patch_model_node.py` verifies safety gate failure does not continue to sandbox/approval and marks the run failed.

### Next

- 继续完善 Worker primary 的可恢复重试语义，区分可重试模型/工具故障和不可重试安全/测试失败。
- 扩展更多 Worker Coder 真实业务 diff 场景，继续保持 parser、安全预检、沙箱测试、风险审查和人工审批后置门不变。
- 后续在 token-backed demo 仓库中验证 Worker-approved patch 到真实远端 GitHub PR 发布。

## 2026-07-17, Slice 129 - Worker主路径接管Smoke版

后端 Worker 启动桥从“记录启动证据后继续 Spring Boot 本地 executor”推进到“callback token 齐备时由 Worker 接管主执行路径”：启用 `REPOPILOT_AGENT_WORKER_ENABLED=true` 且配置 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 后，后端成功调用 Worker `/runs/{runId}/start` 会写入 `agent_worker_start.output.execution_mode=WORKER_PRIMARY`，随后停止本地 patch 生成链路，等待 Worker callback 回写 step、patch、安全预检、沙箱测试、风险审查和人工审批暂停点。Worker 启动失败或 callback token 缺失时仍保留 Spring Boot 本地 executor 兜底。

### Changed

- `AgentWorkerGateway` 新增 `isPrimaryExecutionReady()`，`AgentWorkerClient` 以 `enabled + callbackToken` 判断 Worker 是否具备主执行接管条件。
- `AgentTaskService.executeRun(...)` 在 Worker 启动成功且 primary ready 时直接返回，不再继续本地 `load_task_context`、`plan_task`、`generate_patch`、sandbox 和 review 链路，避免同一 run 出现 Worker 与本地 executor 双重产物。
- `agent_worker_start` step output 新增 `execution_mode=WORKER_PRIMARY|SHADOW_FALLBACK` 和中文 `handoff` 描述，运行报告据此展示 Worker 主路径、影子启动或失败兜底。
- `AgentWorkerClient` 对 Worker start 请求强制使用 HTTP/1.1，避免 Java HttpClient 对本地 Uvicorn 发起 h2c upgrade 导致真实 Worker 返回 422；非 2xx 错误会带上脱敏后的响应片段，便于后续排查。
- `./scripts/agent-worker-business-smoke.mjs` 改为通过标准后端 `/api/agent/tasks/{taskId}/run` 启动任务，验证 `WORKER_PRIMARY`，并断言当前任务只产生 1 个 Worker Coder patch，不再通过 SQL 创建 Worker-only run。
- Agent Worker README、脚本 README、Agent workflow、backend modules、API design 和验收清单同步 Worker primary / shadow fallback / failure fallback 的当前语义。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerClientTest,AgentTaskServiceRegenerationTest test` passes.
- `bash -n scripts/agent-worker-business-smoke.sh` passes.
- `node --check scripts/agent-worker-business-smoke.mjs` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes with 19 tests.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `./scripts/agent-worker-business-smoke.sh` passes through the standard backend run API with `agent_worker_start.execution_mode=WORKER_PRIMARY`.
- Business smoke generated exactly one Worker patch `#1276` as `LLM_CODER_DRAFT / OPENAI_COMPATIBLE / gpt-worker-business-smoke`.
- Business smoke verified the Worker Java diff for `GET /api/users/summary` passed safety, Docker Maven test, review and approval-ready gates.
- Business smoke approved the Worker patch through the standard user JWT approval API and prepared local PR draft `repopilot/task-3105` with commit `d372f319593f63d14cb455fcb54b5af1e9460a48`.
- Business smoke wrote evidence to `output/agent-worker-business-smoke/last-run.json`; latest evidence shows `runReportSectionCount=8`, `modelCallCount=2`, `toolCallCount=15` and sandbox test `PASSED`.
- `./scripts/agent-worker-coder-model-smoke.sh` passes with `OPENAI_COMPATIBLE / gpt-worker-coder-smoke` and `LLM_CODER_DRAFT`.
- `./scripts/agent-worker-planner-smoke.sh` passes with `OPENAI_COMPATIBLE / gpt-worker-planner-smoke`, 2 model calls and 5 steps.
- `./scripts/agent-worker-coder-smoke.sh` passes with `WORKER_CODER_FIXTURE / worker-fixture-coder-v1 / LLM_CODER_DRAFT`.
- `./scripts/agent-worker-smoke.sh`、`./scripts/agent-worker-callback-smoke.sh`、`./scripts/agent-worker-tool-smoke.sh` 和 `./scripts/agent-worker-node-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes and writes the latest screenshot to `output/playwright/repopilot-browser-smoke.png`.
- Cleanup left no `agent-worker-business-smoke-%` users or `Worker Coder 业务演示%` tasks.

### Next

- 扩展更多 Worker Coder 真实业务 diff 场景，继续保持 parser、安全预检、沙箱测试、风险审查和人工审批后置门不变。
- 继续完善 Worker primary 下的取消、重试和失败恢复语义。
- 后续在 token-backed demo 仓库中验证 Worker-approved patch 到真实远端 GitHub PR 发布。

## 2026-07-17, Slice 128 - Worker业务闭环Smoke版

Worker Coder 从“本地后端 stub 可验证”推进到“真实 Spring Boot 后端 + 真实 demo 仓库可验证”：新增业务闭环 smoke，脚本会启动真实后端、真实 FastAPI Worker 和本地 OpenAI-compatible Coder stub，创建本地 demo Spring 项目和 Worker-only run，让 Worker 生成 `GET /api/users/summary` Java 业务 diff，通过安全预检、Docker 沙箱测试、风险审查和人工审批暂停点，再用标准用户 JWT 审批 Worker patch 并准备本地 `DRAFT_READY` PR 草稿。

### Added

- 新增 `./scripts/agent-worker-business-smoke.sh` 和 `./scripts/agent-worker-business-smoke.mjs`。
- 脚本启动 PostgreSQL/Redis、真实 Spring Boot 后端、真实 FastAPI Worker 和本地 `/v1/chat/completions` Coder 模型 stub。
- 脚本创建临时用户、本地 `examples/demo-spring-repo` 项目，执行 clone/index，再通过数据库测试夹具创建 Worker-only run，避免 Spring Boot 本地 executor 兜底链路混入证据。
- Worker Coder stub 生成真实 Java diff，修改 `UserController.java` 和 `UserService.java`，新增 `GET /api/users/summary` 汇总接口。
- 脚本验证 `LLM_CODER_DRAFT`、`OPENAI_COMPATIBLE`、`gpt-worker-business-smoke`、token usage、模型 key 不落审计、context/files/search/read_file 工具审计、diff 安全预检、Docker 沙箱 `mvn -q test`、风险审查和 `waiting_human_approval`。
- 脚本使用标准用户 JWT 审批 Worker patch，随后调用 PR preflight 和 `/api/tasks/{taskId}/pull-request`，验证本地 target branch、commit 和 `DRAFT_READY` PR 草稿。
- 脚本证据写入 `output/agent-worker-business-smoke/last-run.json`，并清理临时用户、项目、任务、run、patch、test、approval、PR 数据和临时 workspace。
- Agent Worker README、脚本 README、Agent workflow 和验收清单新增 Worker 业务闭环 smoke 说明，AC-012 全链路演示清单同步纳入该脚本。

### Verified

- `bash -n scripts/agent-worker-business-smoke.sh` passes.
- `node --check scripts/agent-worker-business-smoke.mjs` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes with 19 tests.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `./scripts/agent-worker-business-smoke.sh` passes with real backend, real FastAPI Worker and local Coder model stub.
- Business smoke generated Worker patch `#1274` as `LLM_CODER_DRAFT / OPENAI_COMPATIBLE / gpt-worker-business-smoke`.
- Business smoke verified the Worker Java diff for `GET /api/users/summary` passed safety, Docker Maven test, review and approval-ready gates.
- Business smoke approved the Worker patch through the standard user JWT approval API and prepared local PR draft `repopilot/task-3103` with commit `34a7c6f6ac3147177d78a4bb83de8bb717a11833`.
- Business smoke wrote evidence to `output/agent-worker-business-smoke/last-run.json` and cleanup left no `agent-worker-business-smoke-%` users or `Worker Coder 业务演示%` tasks.
- `./scripts/agent-worker-coder-model-smoke.sh` passes with `OPENAI_COMPATIBLE / gpt-worker-coder-smoke` and `LLM_CODER_DRAFT`.
- `./scripts/agent-worker-planner-smoke.sh` passes with `OPENAI_COMPATIBLE / gpt-worker-planner-smoke`, 2 model calls and 5 steps.
- `./scripts/agent-worker-coder-smoke.sh` passes with `WORKER_CODER_FIXTURE / worker-fixture-coder-v1 / LLM_CODER_DRAFT`.
- `./scripts/agent-worker-smoke.sh`、`./scripts/agent-worker-callback-smoke.sh`、`./scripts/agent-worker-tool-smoke.sh` 和 `./scripts/agent-worker-node-smoke.sh` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes and writes the latest screenshot to `output/playwright/repopilot-browser-smoke.png`.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%`, `real-github-pr-demo-%` and `agent-worker-business-smoke-%` users, `0` Worker business smoke tasks, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 把 Worker-only 业务闭环继续推进到后端 Worker 启动桥的主执行模式，减少 Spring Boot executor 兜底重复产物。
- 扩展更多 Worker Coder 真实业务 diff 场景，继续保持 parser、安全预检、沙箱测试、风险审查和人工审批后置门不变。
- 后续在 token-backed demo 仓库中验证 Worker-approved patch 到真实远端 GitHub PR 发布。

## 2026-07-17, Slice 127 - Worker模型演示检查版

真实 token 演示检查从只覆盖后端 Coder 和 GitHub PR，扩展到 Worker Planner 与 Worker Coder 的可选模型入口。脚本现在能清楚地区分默认关闭、本地 fixture、OpenAI-compatible 真实配置和本地 stub smoke 链路：默认关闭只作为可选增强提示；显式切到 fixture 或 openai-compatible 后，如果缺少 fixture/model/key，strict 模式会失败。

### Added

- `./scripts/real-token-demo-check.sh` 新增 Worker Planner/Coder readiness 检查区块。
- 新增 `worker_model_status(...)` 和 fixture/openai-compatible 模式判断，统一输出 mode、API base URL、max tokens、model/key/fixture 状态。
- Worker Planner 使用 `REPOPILOT_WORKER_MODEL_*` 配置，Worker Coder 使用 `REPOPILOT_WORKER_CODER_MODEL_*` 配置，两者都可回退 `OPENAI_API_KEY`。
- 默认 `disabled` 只提示可选增强，并指向本地 `agent-worker-planner-smoke.sh` 与 `agent-worker-coder-model-smoke.sh` stub 验证链路。
- strict 模式保留后端真实 Coder、Docker 和远端 GitHub PR 的硬性检查，同时对显式开启但配置不完整的 Worker 模型入口返回非 0。
- 脚本 README、Agent workflow、Sandbox/GitHub 集成设计和验收清单同步 Worker Planner/Coder readiness 语义，并保持密钥/token 只展示“是否配置”，不打印原文。

### Verified

- `bash -n scripts/real-token-demo-check.sh` passes.
- `./scripts/real-token-demo-check.sh` passes in default mode with Worker Planner/Coder default `disabled` reported as optional WARN, not failure.
- `env -u REPOPILOT_CODER_API_KEY -u OPENAI_API_KEY -u REPOPILOT_GITHUB_TOKEN -u GITHUB_TOKEN REPOPILOT_CODER_MODE=disabled REPOPILOT_GITHUB_ENABLED=false ./scripts/real-token-demo-check.sh --strict` exits 1 and reports only backend real Coder and remote GitHub PR hard misses while Worker disabled remains optional.
- `REPOPILOT_CODER_MODE=openai-compatible REPOPILOT_CODER_API_KEY=dummy REPOPILOT_CODER_MODEL=gpt-demo REPOPILOT_GITHUB_ENABLED=true REPOPILOT_GITHUB_TOKEN=dummy REPOPILOT_WORKER_MODEL_MODE=openai-compatible REPOPILOT_WORKER_MODEL_API_KEY=dummy REPOPILOT_WORKER_MODEL_NAME=gpt-worker-planner-demo REPOPILOT_WORKER_CODER_MODEL_MODE=openai-compatible REPOPILOT_WORKER_CODER_MODEL_API_KEY=dummy REPOPILOT_WORKER_CODER_MODEL_NAME=gpt-worker-coder-demo ./scripts/real-token-demo-check.sh --strict` passes with Worker Planner/Coder model/key ready.
- `env -u REPOPILOT_WORKER_CODER_MODEL_API_KEY -u OPENAI_API_KEY REPOPILOT_CODER_MODE=openai-compatible REPOPILOT_CODER_API_KEY=dummy REPOPILOT_CODER_MODEL=gpt-demo REPOPILOT_GITHUB_ENABLED=true REPOPILOT_GITHUB_TOKEN=dummy REPOPILOT_WORKER_CODER_MODEL_MODE=openai-compatible REPOPILOT_WORKER_CODER_MODEL_NAME= ./scripts/real-token-demo-check.sh --strict` exits 1 and reports Worker Coder model/key misses.
- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes with 19 tests.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-coder-model-smoke.sh` passes with `OPENAI_COMPATIBLE / gpt-worker-coder-smoke` and `LLM_CODER_DRAFT`.
- `./scripts/agent-worker-planner-smoke.sh` passes with `OPENAI_COMPATIBLE / gpt-worker-planner-smoke`, 2 model calls and 5 steps.
- `./scripts/agent-worker-coder-smoke.sh` passes with `WORKER_CODER_FIXTURE / worker-fixture-coder-v1 / LLM_CODER_DRAFT`.
- `./scripts/agent-worker-smoke.sh` passes and reports `Graph engine: SEQUENTIAL_FALLBACK` in the current local Python environment.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes, keeping the default `WORKER_SAFE_PLANNING_DRAFT` path intact.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes and writes the latest screenshot to `output/playwright/repopilot-browser-smoke.png`.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 在真实 token 环境中运行 Worker Planner/Worker Coder 的真实 OpenAI-compatible 演示，留存模型 provider、model、token usage 和后置安全门证据。
- 继续推进 Worker Coder 真实业务 diff 场景，同时保持 parser、安全预检、沙箱测试、风险审查和人工审批后置门不变。
- 后续将 Worker 产物继续接入真实远端 GitHub PR 演示。

## 2026-07-17, Slice 126 - WorkerCoder节点模型Smoke版

Python Agent Worker 的 Coder 模型入口从“节点函数 fixture 可验证”推进到“真实 FastAPI Worker 节点链可验证”：新增 `agent-worker-coder-model-smoke.sh`，在本地后端 stub 和本地 OpenAI-compatible Chat Completions stub 下启动真实 Worker，配置 `REPOPILOT_WORKER_CODER_MODEL_MODE=openai-compatible`，验证 `/runs/{runId}/start` 后台执行到 `generate_patch` 时会真实请求 Coder 模型 stub，把 assistant raw unified diff 解析为 `LLM_CODER_DRAFT`，记录 `generate_patch / OPENAI_COMPATIBLE` model call audit，并继续执行 patch 回写、安全预检、沙箱测试、风险审查和人工审批暂停回调。

### Added

- 新增 `./scripts/agent-worker-coder-model-smoke.sh`。
- 脚本启动：
  - 本地后端 HTTP stub。
  - 本地 `/v1/chat/completions` 兼容 Coder 模型 stub。
  - 真实 FastAPI Agent Worker。
- 脚本配置 Worker Coder 模型环境变量：
  - `REPOPILOT_WORKER_CODER_MODEL_MODE=openai-compatible`
  - `REPOPILOT_WORKER_CODER_MODEL_API_BASE_URL`
  - `REPOPILOT_WORKER_CODER_MODEL_API_KEY`
  - `REPOPILOT_WORKER_CODER_MODEL_NAME`
  - `REPOPILOT_WORKER_CODER_MODEL_MAX_COMPLETION_TOKENS`
  - `REPOPILOT_WORKER_CODER_MODEL_ORGANIZATION`
  - `REPOPILOT_WORKER_CODER_MODEL_PROJECT`
- 脚本断言 Coder stub 收到 Authorization header、模型名、organization/project header、diff-only Coder prompt、计划/检索上下文和 `max_completion_tokens`。
- 脚本断言 `generate_patch` model call audit 写入 `OPENAI_COMPATIBLE` provider、模型名和 prompt/completion/total token usage。
- 脚本断言 Coder API key 只进入 Authorization header，不出现在 prompt/response 审计 payload。
- 脚本断言模型 raw diff 持久化为 `generationMode=LLM_CODER_DRAFT`、`generationProvider=OPENAI_COMPATIBLE`、`generationModel=gpt-worker-coder-smoke`，且 diff 不包含 Markdown fence。
- 脚本断言模型 patch 仍继续调用 safety、sandbox、review 和 approval-ready 后置门。
- 脚本证据写入 `output/agent-worker-coder-model-smoke/last-run.json`。
- Agent Worker README、脚本 README、Agent workflow 和验收清单新增 AC-011h27，并把 Coder 模型节点 smoke 纳入全链路演示脚本清单。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes with 19 tests.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-coder-model-smoke.sh` passes with `OPENAI_COMPATIBLE / gpt-worker-coder-smoke` and `LLM_CODER_DRAFT`.
- `./scripts/agent-worker-smoke.sh` passes and reports `Graph engine: SEQUENTIAL_FALLBACK` in the current local Python environment.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes, keeping the default `WORKER_SAFE_PLANNING_DRAFT` path intact.
- `./scripts/agent-worker-planner-smoke.sh` passes with `Planner model: OPENAI_COMPATIBLE / gpt-worker-planner-smoke`, 2 model calls and 5 steps.
- `./scripts/agent-worker-coder-smoke.sh` passes with `WORKER_CODER_FIXTURE / worker-fixture-coder-v1 / LLM_CODER_DRAFT`.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes and writes the latest screenshot to `output/playwright/repopilot-browser-smoke.png`.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 为真实 token 环境补 Worker Planner/Worker Coder readiness check，区分本地 stub、配置缺项和真实模型可调用状态。
- 继续推进 Worker Coder 真实业务 diff 场景，同时保持 parser、安全预检、沙箱测试、风险审查和人工审批后置门不变。
- 后续将 Worker 产物继续接入真实远端 GitHub PR 演示。

## 2026-07-17, Slice 125 - WorkerCoder模型补丁解析版

Python Agent Worker 的 `generate_patch` 从“只能生成安全规划草稿”推进到“可选 Worker Coder 模型 raw diff”：默认仍保持 `WORKER_SAFE_PLANNING_DRAFT`，但配置 Worker Coder 后，模型输出会先经过 Worker 侧 diff parser，只有合法 unified diff 才会持久化为 `LLM_CODER_DRAFT`。无论是确定性草稿还是模型 diff，后续都继续复用后端 safety、sandbox、review 和 approval-ready 后置门，不绕过人工审批。

### Added

- `agent-worker/app/config.py` 新增独立 `REPOPILOT_WORKER_CODER_MODEL_*` 配置，不复用 Planner 的 `REPOPILOT_WORKER_MODEL_*`。
- `WorkerCoderModelClient` 复用现有 fixture/OpenAI-compatible HTTP 管道，但使用 CoderAgent diff-only system prompt。
- `generate_patch` 默认仍走 `WORKER_SAFE_PLANNING_DRAFT`；Worker Coder 返回模型结果时，先解析 raw unified diff，再记录 `LLM_CODER_DRAFT` patch。
- 新增 Worker diff parser，接受 raw unified diff 或唯一一个无额外 prose 的 `diff`/`patch` 代码块，拒绝空输出、多 diff、Markdown fence 和解释性文本。
- `generate_patch` 的模型 patch 路径会记录真实 provider/model、token usage、解析后的 changed paths 和 diff 行数，再调用 `record_patch(...)`、`validate_patch_safety(...)`、`run_patch_sandbox_tests(...)`、`review_patch(...)` 和 `mark_patch_ready_for_approval(...)`。
- 新增 `agent-worker/tests/test_patch_model_node.py`，覆盖 parser、默认 fallback、fixture `LLM_CODER_DRAFT`、model call、patch 回写和后置门。
- `agent-worker/tests/test_worker_model_client.py` 增加 Worker Coder HTTP stub 测试，确认 CoderAgent prompt、`max_completion_tokens` 和密钥不落审计。
- 新增 `./scripts/agent-worker-coder-smoke.sh`，直接验证 Worker Coder fixture diff 到 `LLM_CODER_DRAFT` 和后置门的节点级契约。
- `.env.example`、Agent Worker README、脚本 README、Agent workflow 和验收清单同步 Worker Coder 模型补丁入口，新增 AC-011h26。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes with 19 tests.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-coder-smoke.sh` passes with `WORKER_CODER_FIXTURE / worker-fixture-coder-v1 / LLM_CODER_DRAFT`.
- `./scripts/agent-worker-node-smoke.sh` passes, keeping the default `WORKER_SAFE_PLANNING_DRAFT` path intact.
- `./scripts/agent-worker-planner-smoke.sh` passes with `Planner model: OPENAI_COMPATIBLE / gpt-worker-planner-smoke`, 2 model calls and 5 steps.
- `./scripts/agent-worker-smoke.sh` passes and reports `Graph engine: SEQUENTIAL_FALLBACK` in the current local Python environment.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes and writes the latest screenshot to `output/playwright/repopilot-browser-smoke.png`.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 增加 Worker Coder OpenAI-compatible FastAPI Worker smoke，用本地 Chat Completions stub 覆盖真实 `/runs/{runId}/start` 下的模型 diff 链路。
- 为真实 token 环境补 Worker Planner/Worker Coder readiness check，区分本地 stub、配置缺项和真实模型可调用状态。
- 继续推进 Worker Coder 真实业务 diff 场景，同时保持 parser、安全预检、沙箱测试、风险审查和人工审批后置门不变。

## 2026-07-17, Slice 124 - Worker结构化Planner建议版

Python Agent Worker 的 Planner 模型入口从“可审计文本摘要”升级为“结构化 Planner 建议”：模型现在被要求返回 JSON object，Worker 会解析出 `modelPlan.summary`、`steps`、`searchQueries`、`risks` 和 `testStrategy`，同时保留确定性计划作为安全默认值。`retrieve_context` 会在不放弃确定性 query 的前提下，吸收最多两条模型建议 search query，让模型可以帮助检索测试文件、Mapper 或边界上下文，但不能绕过后续 patch 安全预检、沙箱测试、风险审查和人工审批。

### Added

- `planner_system_prompt(...)` 改为 JSON object 输出契约，要求字段为 `summary`、`steps`、`searchQueries`、`risks` 和 `testStrategy`。
- `plan_task` 新增 `parse_model_plan(...)`，支持解析标准 JSON object；如果模型返回纯文本或非 JSON，自动降级为 `format=plain_text` 的 `modelPlan`，并继续保留 `modelPlanText`。
- 模型步骤会被规整为最多 5 条，模型检索 query 会被规整为最多 5 条，并去重、截断、过滤空值。
- `retrieve_context` 的 query 融合策略调整为：先保留前 3 条确定性 query，再吸收最多 2 条模型 query，最后补剩余确定性 query，总数仍限制为 5。
- Planner smoke 的本地 OpenAI-compatible stub 改为返回结构化 JSON，并断言 `modelPlan.format=json_object`、`modelPlan.searchQueries` 和 `retrieve_context.queries`。
- `agent-worker/tests/test_planning_model_node.py` 增加结构化模型计划解析、token usage 审计和模型 query 融合检索测试。
- Agent Worker README、脚本 README、Agent workflow 和验收清单同步结构化 Planner 建议与 query 融合边界。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes with 14 tests.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/agent-worker-planner-smoke.sh` passes with `Planner model: OPENAI_COMPATIBLE / gpt-worker-planner-smoke`, 2 model calls and 5 steps.
- `./scripts/agent-worker-smoke.sh` passes and reports `Graph engine: SEQUENTIAL_FALLBACK` in the current local Python environment.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback, 1 sandbox callback, 1 review callback and 1 approval-ready callback.
- `./scripts/browser-smoke.sh` passes and writes the latest screenshot to `output/playwright/repopilot-browser-smoke.png`.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 为真实 token 环境补 Worker Planner readiness check，区分本地 stub、真实模型配置缺项和真实模型可调用状态。
- 继续把 `generate_patch` 的 Worker 草稿从确定性规划 diff 推进到模型生成 diff，并复用 parser、安全预检、沙箱测试、风险审查和人工审批后置门。

## 2026-07-17, Slice 123 - WorkerPlanner节点模型Smoke版

Python Agent Worker 的 Planner 模型入口从“client 单测可用”推进到“真实 FastAPI Worker 节点链可验证”：新增 `agent-worker-planner-smoke.sh`，在本地后端 stub 和本地 OpenAI-compatible Chat Completions stub 下启动真实 Worker，配置 `REPOPILOT_WORKER_MODEL_MODE=openai-compatible`，验证 `plan_task` 会真实请求模型 stub、写入模型计划摘要、记录 `plan_task / OPENAI_COMPATIBLE` model call audit，并继续执行 `retrieve_context`、`generate_patch`、安全预检、沙箱测试、风险审查和人工审批暂停回调。

### Added

- 新增 `./scripts/agent-worker-planner-smoke.sh`。
- 脚本启动：
  - 本地后端 HTTP stub。
  - 本地 `/v1/chat/completions` 兼容模型 stub。
  - 真实 FastAPI Agent Worker。
- 脚本配置 Worker Planner 模型环境变量：
  - `REPOPILOT_WORKER_MODEL_MODE=openai-compatible`
  - `REPOPILOT_WORKER_MODEL_API_BASE_URL`
  - `REPOPILOT_WORKER_MODEL_API_KEY`
  - `REPOPILOT_WORKER_MODEL_NAME`
  - `REPOPILOT_WORKER_MODEL_MAX_COMPLETION_TOKENS`
  - `REPOPILOT_WORKER_MODEL_ORGANIZATION`
  - `REPOPILOT_WORKER_MODEL_PROJECT`
- 脚本断言 Planner stub 收到 Authorization header、模型名、organization/project header、Planner prompt、任务/索引/检索/确定性计划上下文和 `max_completion_tokens`。
- 脚本断言 `plan_task` step output 包含 `modelPlanText`、`modelProvider=OPENAI_COMPATIBLE` 和模型名。
- 脚本断言本次 run 记录两条 model call audit：`plan_task / OPENAI_COMPATIBLE` 和 `generate_patch / AGENT_WORKER`。
- 脚本断言 `plan_task` model call audit 写入 prompt/completion/total token usage。
- 脚本断言 Planner API key 只进入 Authorization header，不出现在 prompt/response 审计 payload。
- 脚本证据写入 `output/agent-worker-planner-smoke/last-run.json`。
- Agent Worker README、脚本 README、Agent workflow 和验收清单新增 AC-011h25，并把全链路演示脚本清单纳入 Planner smoke。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-planner-smoke.sh` passes with `Planner model: OPENAI_COMPATIBLE / gpt-worker-planner-smoke`, 2 model calls and 5 steps.
- `./scripts/agent-worker-smoke.sh` passes and reports `Graph engine: SEQUENTIAL_FALLBACK` in the current local Python environment.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback, 1 sandbox callback, 1 review callback and 1 approval-ready callback; default `disabled` 模式没有额外 Planner model call。
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing console-to-approval-to-local-PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 将 Worker Planner 模型摘要从展示字段升级为结构化 planner 建议，继续保留确定性计划作为 fallback。
- 为真实 token 环境补一个 Worker Planner 演示检查项，区分“本地 stub ready”和“真实模型 ready”。
- 继续把 `generate_patch` 的 Worker 草稿从确定性规划 diff 推进到模型生成 diff，并复用 parser、安全预检、沙箱测试、风险审查和人工审批后置门。

## 2026-07-17, Slice 122 - Worker真实Planner客户端版

Python Agent Worker 的 Planner 模型入口从 fixture 留证推进到 OpenAI-compatible HTTP 契约：`WorkerModelClient` 现在可以调用 `/chat/completions` 生成中文计划摘要，并把 assistant content、模型名、provider 和 token usage 接入 `plan_task` model call audit。默认 `disabled` 模式保持不变，因此现有 Worker 节点链、补丁草稿、安全预检、沙箱测试、风险审查和人工审批暂停点不受影响。

### Added

- `agent-worker/app/config.py` 新增 `REPOPILOT_WORKER_MODEL_API_BASE_URL`、`REPOPILOT_WORKER_MODEL_API_KEY`、`REPOPILOT_WORKER_MODEL_TIMEOUT_SECONDS`、`REPOPILOT_WORKER_MODEL_MAX_COMPLETION_TOKENS`、`REPOPILOT_WORKER_MODEL_INSTRUCTION_ROLE`、`REPOPILOT_WORKER_MODEL_ORGANIZATION` 和 `REPOPILOT_WORKER_MODEL_PROJECT`。
- `.env.example` 新增 Worker Planner 模型配置占位，默认仍为 `REPOPILOT_WORKER_MODEL_MODE=disabled`。
- `WorkerModelClient` 新增 `openai` / `openai-compatible` 模式：
  - 调用 `${REPOPILOT_WORKER_MODEL_API_BASE_URL}/chat/completions`。
  - 发送 Planner system/developer prompt、任务/索引/检索/确定性计划上下文和 `max_completion_tokens`。
  - 支持 `OpenAI-Organization` 与 `OpenAI-Project` 可选 header。
  - 解析 string 或 content-part array 形式的 assistant content。
  - 解析 `usage.prompt_tokens`、`completion_tokens` 和 `total_tokens`。
  - API key 只进入 Authorization header，不写入 prompt/response 审计。
- `plan_task` 的 model call audit 现在会同步写入 prompt/completion/total token usage。
- `agent-worker/tests/test_worker_model_client.py` 新增本地 HTTP stub，覆盖 OpenAI-compatible 请求路径、Authorization header、模型名、Planner prompt、`max_completion_tokens`、usage 解析、缺 key、缺 model 和 HTTP 失败。
- Agent Worker README、Agent workflow 和验收清单 AC-011h24 同步 OpenAI-compatible Planner 契约。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-smoke.sh` passes and reports `Graph engine: SEQUENTIAL_FALLBACK` in the current local Python environment.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback, 1 sandbox callback, 1 review callback and 1 approval-ready callback; default `disabled` 模式没有额外 Planner model call。
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing console-to-approval-to-local-PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

说明：当前环境没有真实 Worker Planner 模型 token，因此本版只声明本地 OpenAI-compatible HTTP stub 契约验证通过，不声明已经调用真实模型成功。配置 `REPOPILOT_WORKER_MODEL_MODE=openai-compatible`、模型 key 和模型名后即可在 Worker `plan_task` 中留存真实 Planner model call 证据。

### Next

- 增加 Worker Planner 节点级 OpenAI-compatible stub smoke，让真实 FastAPI Worker 在本地 stub 下走一次 `plan_task` 模型摘要链路。
- 将模型计划摘要从纯展示字段逐步升级为结构化 planner 建议，但继续保留确定性计划作为安全 fallback。
- 继续把 `generate_patch` 的 Worker 草稿从确定性规划 diff 推进到模型生成 diff，并复用 parser、安全预检、沙箱测试、风险审查和人工审批后置门。

## 2026-07-17, Slice 121 - Worker模型计划客户端版

Python Agent Worker 从“确定性 plan_task”推进到“确定性计划 + 可选模型计划摘要”的过渡形态：默认 `disabled` 模式不调用模型，保持现有节点链和审批闭环不变；当配置 `REPOPILOT_WORKER_MODEL_MODE=fixture` 时，Worker 会把固定模型计划摘要写入 `plan_task` step output，并通过既有 `record_model_call(...)` 写入 `plan_task` model call audit。这样后续接入真实 Planner 模型时，prompt、response、provider、model 和耗时审计链路已经先站稳。

### Added

- `agent-worker/app/config.py` 新增 `REPOPILOT_WORKER_MODEL_MODE`、`REPOPILOT_WORKER_MODEL_PROVIDER`、`REPOPILOT_WORKER_MODEL_NAME` 和 `REPOPILOT_WORKER_MODEL_FIXTURE_RESPONSE` 配置。
- 新增 `agent-worker/app/clients/model_client.py`，提供 `WorkerModelClient`、`WorkerModelClientSettings` 和 `WorkerModelResult`。
- `WorkerModelClient` 支持：
  - `disabled`：默认不调用模型，返回 `None`。
  - `fixture`：使用固定响应生成可审计模型结果。
- `plan_task` 生成确定性计划后会构造中文 Planner prompt；模型返回时写入 `modelPlanText`、`modelProvider`、`modelName`，并记录 `plan_task` model call audit。
- 新增 `agent-worker/tests/test_worker_model_client.py`，覆盖 disabled、fixture 和缺少 fixture response 的配置错误。
- 新增 `agent-worker/tests/test_planning_model_node.py`，覆盖 `plan_task` 的 fixture 模型审计、prompt 结构、step output 和确定性计划保留。
- Agent Worker README、Agent workflow 和验收清单同步 Worker 模型计划客户端契约，新增 AC-011h24。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-smoke.sh` passes and reports `Graph engine: SEQUENTIAL_FALLBACK` in the current local Python environment.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback, 1 sandbox callback, 1 review callback and 1 approval-ready callback; default `disabled` 模式没有额外 plan model call。
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing console-to-approval-to-local-PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 把 `WorkerModelClient` 从 fixture 模式扩展到真实 OpenAI-compatible Planner provider。
- 为 Worker `plan_task` 增加真实模型 smoke/stub 测试，同时继续保持确定性计划作为安全 fallback。
- 逐步把 `generate_patch` 的 Worker 草稿从确定性规划 diff 推进到模型生成 diff，但继续复用 parser、安全预检、沙箱测试、风险审查和人工审批后置门。

## 2026-07-17, Slice 120 - Worker节点模块拆分版

Python Agent Worker 从“所有初始节点和 helper 都堆在 `initial_nodes.py`”推进到“图装配与节点实现分离”：`initial_nodes.py` 现在只负责后台安全入口和 LangGraph 节点链装配，具体节点实现按职责拆入 `app/graph/nodes/context.py`、`planning.py`、`patch.py` 和 `common.py`。这样后续把确定性 planning/patch 替换成模型驱动节点时，可以逐个文件替换，图装配和回写契约保持稳定。

### Added

- 新增 `app/graph/nodes/` 包。
- `context.py` 承载 `load_task_context`、`ensure_index`、索引摘要和样本文件/符号 helper。
- `planning.py` 承载确定性 `plan_task`、`retrieve_context`、query 生成、检索结果去重和文件预览摘要 helper。
- `patch.py` 承载确定性 `generate_patch`、`WORKER_SAFE_PLANNING_DRAFT` 草稿生成、model/patch 回写和 safety/sandbox/review/approval-ready 后置门。
- `common.py` 承载 `text_preview`、`add_query` 和 `unique_values` 等共享小工具。
- `initial_nodes.py` 缩减为 `run_initial_nodes_safely(...)` 与 `build_initial_graph(...)`，保留 `load_task_context -> ensure_index -> plan_task -> retrieve_context -> generate_patch` 顺序。
- `agent-worker/tests/test_initial_graph_builder.py` 固定初始节点顺序，防止后续拆节点时误改图拓扑。
- Agent Worker README、Agent workflow、backend modules、repository structure 和验收清单同步节点模块布局。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-smoke.sh` passes and reports `Graph engine: SEQUENTIAL_FALLBACK` in the current local Python environment.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback, 1 sandbox callback, 1 review callback and 1 approval-ready callback.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing console-to-approval-to-local-PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 在安装完整 `agent-worker` 依赖的运行环境中补充一次 `graph_engine=LANGGRAPH` 的真实依赖 smoke 证据。
- 开始把确定性 planning/patch 节点替换为可配置的模型驱动节点，同时继续复用现有审计和安全后置门。

## 2026-07-17, Slice 119 - Worker图执行器LangGraph适配版

Python Agent Worker 从“自研轻量顺序 runner”推进到“LangGraph 优先、顺序兜底”的图执行器：`WorkerGraphRunner` 在 `langgraph` 依赖可导入时会用 `StateGraph(AgentRunState)` 编译初始节点链，并通过 `compile().invoke(...)` 执行；本地开发环境未安装 `langgraph` 时只在 `ImportError` 下回退为同序顺序执行，并通过 `/health.graph_engine` 明确暴露当前引擎。

### Added

- `WorkerGraphRunner` 新增 LangGraph `StateGraph` 适配：按 `load_task_context -> ensure_index -> plan_task -> retrieve_context -> generate_patch` 顺序注册节点、设置 entry point、添加边和 `END`，再执行 compiled graph。
- 顺序执行器保留为本地兜底，且只在缺少 `langgraph` 依赖时启用；真实图编译/运行错误不会被吞掉。
- `GET /health` 响应新增 `graph_engine`，返回 `LANGGRAPH` 或 `SEQUENTIAL_FALLBACK`。
- `agent-worker/tests/test_worker_graph_runner.py` 新增 Python `unittest`，覆盖顺序兜底、fake LangGraph 编译/边关系/`AgentRunState` schema/`compile().invoke(...)` 调用。
- `./scripts/agent-worker-smoke.sh` 新增 `graph_engine` 契约断言，并把当前引擎写入 smoke 证据。
- `.gitignore` 新增 `*.egg-info/`，避免本地 editable install 痕迹进入版本管理。
- Agent Worker README、脚本 README、Agent workflow 和验收清单同步 LangGraph 优先执行器与健康检查可见性。

### Verified

- `PYTHONPATH=agent-worker python3 -m unittest discover -s agent-worker/tests` passes.
- `python3 -m compileall -q agent-worker/app agent-worker/tests` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-smoke.sh` passes and reports `Graph engine: SEQUENTIAL_FALLBACK` in the current local Python environment where `langgraph` is not installed.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback, 1 sandbox callback, 1 review callback and 1 approval-ready callback.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing console-to-approval-to-local-PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 在安装完整 `agent-worker` 依赖的运行环境中补充一次 `graph_engine=LANGGRAPH` 的真实依赖 smoke 证据。
- 将较大的 Worker 节点实现拆成独立 LangGraph node 文件，继续为真实模型节点和远端 PR 演示做铺垫。

## 2026-07-17, Slice 118 - Worker审批后PR衔接验证版

Python Agent Worker 的产物从“能进入人工审批暂停点”推进到“人工 approve 后可复用既有 PR 准备链路”：集成测试现在用 Worker 元数据构造已通过安全、沙箱测试和风险审查的 patch，先通过 internal `review` 与 `approval-ready` 进入 `WAITING_HUMAN_APPROVAL`，再用标准用户 JWT approve，并最终通过 `/pull-request` 准备本地 `DRAFT_READY` PR commit。

### Added

- `PullRequestApprovalIntegrationTest` 新增 `workerGeneratedPatchCanBeApprovedAndPreparedAsLocalPullRequest`，覆盖 Worker token 内部 review/approval-ready、用户 JWT approve 和 PR 准备接口。
- 测试断言 Worker patch 保留 `WORKER_SAFE_PLANNING_DRAFT`、`AGENT_WORKER` 和 `worker-retrieval-plan-v1` 生成元数据，进入审批点后仍保持 `APPLIED`，不会被 approval-ready 自动 approve。
- 审批前 PR preflight 断言 `canPrepare=false`，并返回“准备 PR 前需要先审批已测试通过的补丁。” blocker；审批后 preflight 断言 `canPrepare=true`、`latestPatchStatus=APPROVED` 和 `localDraftReady=true`。
- PR 准备断言返回 `DRAFT_READY`、任务进入 `DONE`，并验证生成 commit 中包含 Worker patch 对 README 的修改和 RepoPilot 中文提交信息。
- Agent Worker README、脚本 README、Agent workflow、backend modules、MCP/API 设计和验收清单同步新边界：Worker 只把 patch 送到人工审批暂停点，approve 后由既有后端 PR 链路准备本地草稿。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-smoke.sh` passes.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback, 1 sandbox callback, 1 review callback and 1 approval-ready callback.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=PullRequestApprovalIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing console-to-approval-to-local-PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 继续把轻量 Worker 图迁移为真实 LangGraph graph。
- 在真实 GitHub token 和可丢弃 demo 仓库环境中，让 Worker 产物走到远端分支 push 与 GitHub PR 创建演示。

## 2026-07-16, Slice 117 - Worker人工审批暂停衔接版

Python Agent Worker 从“风险审查 shadow 链路可记录”推进到“安全、测试、审查均通过后进入人工审批暂停点”：Worker 生成的 `WORKER_SAFE_PLANNING_DRAFT` patch 现在会在 review 成功后调用后端 `approval-ready` 内部门，由后端写入 `waiting_human_approval` step，把任务切到 `WAITING_HUMAN_APPROVAL`，并把 run 标为 `SUCCESS`。

### Added

- 后端新增 `POST /api/internal/agent-worker/runs/{runId}/patches/{patchId}/approval-ready`，继续使用 `X-RepoPilot-Worker-Token` 与 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 鉴权。
- `AgentWorkerCallbackService.markPatchReadyForApproval(...)` 校验 patch 属于当前 run，要求 patch 已经成功应用、最新 `test_run.status=PASSED`，且同一 patch 已有成功 `review_patch` step，避免 Worker 跳过测试或风险审查直接进入审批。
- 审批暂停桥写入 `waiting_human_approval` PENDING step，把 task 标为 `WAITING_HUMAN_APPROVAL`，把 run 标为 `SUCCESS`，并发布 `STREAM_COMPLETE`；该版本不会 approve patch，也不会创建 PR。
- Python `BackendApiClient` 新增 `mark_patch_ready_for_approval(...)`，`generate_patch` 在 review 通过后调用 approval-ready 接口。
- `./scripts/agent-worker-node-smoke.sh` 新增 approval-ready callback stub 与断言，验证 `/patches/{patchId}/approval-ready` 路径、callback token、空 JSON 请求体和 `Approval-ready: 1`。
- Agent Worker README、脚本 README、Agent workflow、backend modules、MCP/API 设计和验收清单同步当前能力边界：Worker shadow 链路可进入人工审批暂停点，PR 创建仍需要用户 approve。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-smoke.sh` passes.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback, 1 sandbox callback, 1 review callback and 1 approval-ready callback.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerCallbackControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 把 Worker 生成并进入人工审批的 patch 接入人工 approve 后的 PR 创建/发布链路，逐步缩小 Spring Boot fallback executor 与 Python Worker 主链路的差距。

## 2026-07-16, Slice 116 - Worker补丁风险审查衔接版

Python Agent Worker 从“沙箱测试通过后留下测试证据”推进到“测试通过后继续进入后端 ReviewAgent shadow 链路”：Worker 生成的 `WORKER_SAFE_PLANNING_DRAFT` patch 现在会在 safety 与 sandbox tests 均通过后调用后端风险审查接口，复用 `PatchRiskReviewService` 与 `ModelCallLogService` 记录规则化 review model audit 和 `review_patch` step。

### Added

- 后端新增 `POST /api/internal/agent-worker/runs/{runId}/patches/{patchId}/review`，继续使用 `X-RepoPilot-Worker-Token` 与 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 鉴权。
- `AgentWorkerCallbackService.reviewPatch(...)` 校验 patch 属于当前 run，并要求 patch 已经成功应用且最新 `test_run.status=PASSED`，避免 Worker 跳过沙箱测试直接审查。
- Worker review shadow 链路复用 `PatchRiskReviewService.review(...)`，通过 `ModelCallLogService.record(...)` 写入 `review_patch` model audit，并写入 `review_patch` step；该版本不直接切换 task/run 状态，也不自动进入人工审批。
- Python `BackendApiClient` 新增 `review_patch(...)`，`generate_patch` 在 sandbox tests 通过后调用 review 接口；后置门调用异常继续写入 `postPatchGateError`。
- `./scripts/agent-worker-node-smoke.sh` 新增 review callback stub 与断言，验证 `/patches/{patchId}/review` 路径、callback token、空 JSON 请求体和 `Reviews: 1`。
- Agent Worker README、脚本 README、Agent workflow、backend modules、MCP/API 设计和验收清单同步当前能力边界：风险审查已接入，人工审批仍是下一步。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-smoke.sh` passes.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback, 1 sandbox callback and 1 review callback.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerCallbackControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 把 Worker review 结果接入人工审批/status bridge，让 Worker shadow 链路在通过安全、测试和审查后能进入 `WAITING_HUMAN_APPROVAL`，并继续保留人工审批门。

## 2026-07-16, Slice 115 - Worker补丁沙箱测试衔接版

Python Agent Worker 从“补丁生成后能进入安全门”推进到“安全通过后能进入后端 sandbox apply/test shadow 链路”：Worker 生成的 `WORKER_SAFE_PLANNING_DRAFT` patch 现在会在 safety 通过后调用后端沙箱测试接口，复用 `SandboxTestService` 记录 `prepare_sandbox`、`apply_patch`、`run_maven_test` tool audit，并写入 `apply_patch`、`run_tests` step 与 `test_run`。

### Added

- 后端新增 `POST /api/internal/agent-worker/runs/{runId}/patches/{patchId}/sandbox-tests`，继续使用 `X-RepoPilot-Worker-Token` 与 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 鉴权。
- `AgentWorkerCallbackService.runPatchSandboxTests(...)` 校验 patch 属于当前 run，并要求同一 patch 已经有成功的 `validate_patch_safety` step，避免 Worker 绕过 diff 安全门。
- Worker sandbox shadow 链路复用 `SandboxTestService.prepareWorkspace(...)`、`applyPatch(...)` 和 `runMavenTest(...)`，成功 apply 后把 patch 标记为 `APPLIED`，并记录 `apply_patch` / `run_tests` step 和 `test_run`；该版本不直接切换 task/run 状态，也不自动进入 review/approval。
- Python `BackendApiClient` 新增 `run_patch_sandbox_tests(...)`，`generate_patch` 在 safety 通过后调用 sandbox-tests 接口；后置门调用异常会写入 `postPatchGateError`，避免重复把已成功的 `generate_patch` step 标失败。
- `./scripts/agent-worker-node-smoke.sh` 新增 sandbox callback stub 与断言，验证 `/patches/{patchId}/sandbox-tests` 路径、callback token、空 JSON 请求体和 `Sandbox runs: 1`。
- Agent Worker README、脚本 README、Agent workflow、backend modules、MCP/API 设计和验收清单同步当前能力边界：沙箱测试已接入，风险审查和人工审批仍是下一步。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-smoke.sh` passes.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback, 1 safety callback and 1 sandbox callback.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerCallbackControllerIntegrationTest test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 把 Worker sandbox 通过后的 patch 接到后端 ReviewAgent shadow 链路。
- 继续推进真实 LangGraph graph 替换轻量 `WorkerGraphRunner`。

## 2026-07-16, Slice 114 - Worker补丁安全预检衔接版

Python Agent Worker 从“能生成并回写 patch draft”推进到“生成后立即进入后端 diff 安全门”：Worker 仍不替代 Spring Boot 主执行链路，但它已经能把自身产出的 `WORKER_SAFE_PLANNING_DRAFT` patch 交给后端复用同一套 `PatchDiffSafetyService` 预检，并留下 `validate_patch_safety` step 证据。

### Added

- 后端新增 `POST /api/internal/agent-worker/runs/{runId}/patches/{patchId}/safety`，继续使用 `X-RepoPilot-Worker-Token` 与 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 鉴权。
- `AgentWorkerCallbackService.validatePatchSafety(...)` 校验 patch 属于当前 run，复用 `PatchDiffSafetyService` 生成安全报告，并记录 `validate_patch_safety` step；该 shadow Worker 链路不直接切换 task/run 状态。
- Python `BackendApiClient` 新增 `validate_patch_safety(...)`，`generate_patch` 在 patch draft 持久化和 SUCCESS step 回写后调用 safety 接口。
- `./scripts/agent-worker-node-smoke.sh` 新增 safety callback stub 与断言，验证 `/patches/{patchId}/safety` 路径、callback token、空 JSON 请求体和 `Safety checks: 1`。
- Agent Worker README、脚本 README、Agent workflow、backend modules、MCP/API 设计和验收清单同步当前能力边界：安全预检已接入，沙箱测试、风险审查和人工审批仍是下一步。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/*.sh` passes.
- `node --check scripts/*.mjs` passes.
- `./scripts/agent-worker-smoke.sh` passes.
- `./scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-tool-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes with five SUCCESS worker step callbacks, 13 tool calls, 1 model call, 1 patch callback and 1 safety callback.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerCallbackControllerIntegrationTest test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs.
- `git diff --check` passes.
- Strict token scan found no committed secret-looking values.

### Next

- 把 Worker 生成的安全 patch 继续接到后端 sandbox apply/test shadow 链路。
- 继续推进真实 LangGraph graph 替换轻量 `WorkerGraphRunner`。

## 2026-07-16, Slice 113 - Worker补丁草稿节点版

Python Agent Worker 从“能回写 patch draft”推进到“图里真的执行 `generate_patch` 节点”：轻量图执行器现在在 `retrieve_context` 后执行确定性补丁草稿生成。该节点消费 `loaded_context`、`index_status`、`plan_output` 和 `retrieval_output`，生成 `.repopilot/task-{taskId}-worker-plan.md` 的 unified diff，通过 `record_model_call(...)` 写入 `generate_patch` model call audit，通过 `record_patch(...)` 写入 `patch_record`，最后回写 `generate_patch` SUCCESS step。

### Added

- `agent-worker/app/graph/initial_nodes.py` 新增 `generate_patch(...)` 节点，生成 `WORKER_SAFE_PLANNING_DRAFT` / `AGENT_WORKER` / `worker-retrieval-plan-v1` 补丁草稿。
- 新增 deterministic draft helpers：基于索引状态、计划步骤、检索结果和已读取文件生成 `.repopilot/task-{taskId}-worker-plan.md` 内容，并包装成 new-file unified diff。
- Worker graph 顺序扩展为 `load_task_context -> ensure_index -> plan_task -> retrieve_context -> generate_patch`。
- `AgentRunState` 新增 `patch_output`。
- `./scripts/agent-worker-node-smoke.sh` 升级为初始、检索与补丁草稿节点 smoke，后端 stub 新增 `/model-calls` 与 `/patches`，验证五个 SUCCESS step、13 条 SUCCESS tool call audit、1 条 SUCCESS model call audit 和 1 个 `WORKER_SAFE_PLANNING_DRAFT` patch draft。
- Agent Worker README、脚本 README、Agent workflow、backend modules 和验收清单同步 Worker 补丁草稿节点契约。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-node-smoke.sh` passes.
- `./scripts/agent-worker-node-smoke.sh` passes and writes `output/agent-worker-node-smoke/last-run.json` with `load_task_context` / `ensure_index` / `plan_task` / `retrieve_context` / `generate_patch` SUCCESS steps, `generatePatch.patchStatus=GENERATED`, `diffPath=.repopilot/task-303-worker-plan.md`, 13 SUCCESS tool call audit records, 1 SUCCESS model call audit record and 1 patch callback.

### Next

- 把 Worker 生成的 `WORKER_SAFE_PLANNING_DRAFT` 接入后端 diff 安全预检、沙箱测试、风险审查和人工审批链路。
- 后续用真实 Coder model 节点替换当前确定性草稿生成，同时继续复用 `record_model_call(...)` 和 `record_patch(...)`。

## 2026-07-16, Slice 112 - Worker补丁回写契约版

Python Agent Worker 从“能回写 step/tool/model/status”推进到“能把生成的 patch draft 写入既有 patch_record”：后端新增受 Worker callback token 保护的 `/patches` 内部接口，Worker client 新增 `record_patch(...)`。这一步暂不切换主执行链路，Spring Boot executor 仍负责默认 patch 生成、沙箱测试、审查和审批；但后续把 `generate_patch` 节点迁入 Worker 时，已经有可验证的 patch 持久化契约。

### Added

- 后端新增 `AgentWorkerPatchRecordRequest`，支持 `diff_content`、`summary`、`generation_mode`、`generation_provider`、`generation_model`，以及可选 `base_branch` / `target_branch`。
- `AgentWorkerCallbackController` 新增 `POST /api/internal/agent-worker/runs/{runId}/patches`，继续使用 `X-RepoPilot-Worker-Token` 和 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 鉴权。
- `AgentWorkerCallbackService.recordPatch(...)` 按 `runId` 绑定现有 task/run，缺省分支时使用项目默认分支和 `repopilot/task-{taskId}`，保存 `PatchRecord` 并返回标准 `PatchRecordResponse`。
- Python Worker 新增 `AgentPatchRecordRequest` 和 `BackendApiClient.record_patch(...)`。
- `./scripts/agent-worker-callback-smoke.sh` 从四类回写升级为五类回写，验证 step/tool/model/patch/status 路径、header、JSON 和响应解析。
- Agent Worker README、脚本 README、API design、Agent workflow、backend modules、MCP tools 和验收清单同步 Worker patch draft 回写契约。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-callback-smoke.sh` passes.
- `./scripts/agent-worker-callback-smoke.sh` passes and writes `output/agent-worker-callback-smoke/last-run.json` with `Patch: WORKER_SAFE_PLANNING_DRAFT -> GENERATED`.
- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerCallbackControllerIntegrationTest test` passes.

### Next

- 在 Worker 图中新增确定性 `generate_patch` draft 节点，消费 `retrieval_output` 生成安全规划型 diff，并通过 `record_patch(...)` + `record_model_call(...)` 回写。
- 后续再把 Worker 生成的 patch 接入沙箱安全链路，逐步替换 Spring Boot fallback executor 的 `generate_patch` 阶段。

## 2026-07-16, Slice 111 - Worker图执行器骨架版

Python Agent Worker 从“顺序调用初始节点”推进到“有节点边界的轻量图执行骨架”：`run_initial_nodes_safely` 现在通过 `WorkerGraphRunner` 串联 `load_task_context -> ensure_index -> plan_task -> retrieve_context`，每个节点都通过共享 state 传递上下文和产物。这样后续迁移 `generate_patch`、模型调用和真实 LangGraph graph 时，节点契约已经先稳定下来。

### Added

- 新增 `agent-worker/app/graph/runner.py`，提供 `WorkerGraphNode` 和 `WorkerGraphRunner`，支持节点启动回调和 state 合并。
- `AgentRunState` 新增 `loaded_context`、`index_status`、`plan_output` 和 `retrieval_output`，对齐 Worker 节点间共享状态。
- 新增 `ensure_index` 节点，基于 `load_task_context` 已读取的文件树和符号样本生成索引就绪证据，回写 `indexReady`、`fileCount`、`javaFileCount`、`testFileCount`、`symbolCount`、`controllerCount`、`serviceCount`、样本文件和样本 Controller/Service。
- `./scripts/agent-worker-node-smoke.sh` 从三节点验收升级为四节点验收，验证 `ensure_index` SUCCESS、`indexReady=true`、Java 文件数和 Controller/Service 符号统计，同时继续确认每个 context/files/symbols/search/file GET 都有 SUCCESS tool call audit。
- Agent Worker README、脚本 README、Agent workflow、backend modules 和验收清单同步图执行器骨架与 `ensure_index` 契约。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-smoke.sh scripts/agent-worker-callback-smoke.sh scripts/agent-worker-tool-smoke.sh scripts/agent-worker-node-smoke.sh scripts/browser-smoke.sh scripts/real-token-demo-check.sh scripts/real-coder-demo.sh scripts/real-github-pr-demo.sh` passes.
- `node --check scripts/browser-smoke.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/real-github-pr-demo.mjs` pass.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json`.
- `./scripts/agent-worker-callback-smoke.sh` passes and writes `output/agent-worker-callback-smoke/last-run.json`.
- `./scripts/agent-worker-tool-smoke.sh` passes and writes `output/agent-worker-tool-smoke/last-run.json`.
- `./scripts/agent-worker-node-smoke.sh` passes and writes `output/agent-worker-node-smoke/last-run.json` with `load_task_context` / `ensure_index` / `plan_task` / `retrieve_context` SUCCESS steps, `ensureIndex.indexReady=true` and 13 SUCCESS tool call audit records.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%`, `real-github-pr-demo-%` and `worker-tool-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the existing frontend dev server remains on `5173`.
- `git diff --check` passes, and strict placeholder secret scan over docs/scripts/backend/agent-worker/frontend returns empty.

### Next

- 把 `generate_patch` 迁入 Python Worker，让它消费 `retrieval_output` 并通过 `record_model_call(...)` 写入模型调用审计。
- 用真实 LangGraph graph 替换当前轻量执行器，同时保留这一版已经验证的节点输入/输出契约。

## 2026-07-16, Slice 110 - Worker工具审计自动化版

Python Agent Worker 从“能写工具审计”推进到“真实工具读取会自动写审计”：`BackendApiClient` 的 run-scoped 仓库读取方法现在会在每次 GET 工具请求后尽力回写 `tool_call_log`。成功调用记录 bounded output summary，失败调用记录 FAILED 审计；审计回写失败不反向打断主工具读取，保证日志管道故障不会影响 Worker 节点主流程。

### Added

- `BackendApiClient.load_run_context(...)`、`list_project_files(...)`、`read_project_file(...)`、`search_code(...)` 和 `list_symbols(...)` 自动通过 `record_tool_call(...)` 写入工具调用审计。
- 工具审计输出做客户端侧摘要：context 只保留 run/task/project/repo/title，files/symbols 只保留数量和样本，search 只保留命中数、top files 和 chunk ids，read file 只保留 path/size/contentPreview。
- 工具读取失败时会尽力写入 `status=FAILED`、错误摘要和耗时；如果审计写入本身失败，则静默忽略并继续抛出原始工具错误。
- `./scripts/agent-worker-node-smoke.sh` 的后端 stub 新增 `/tool-calls` 响应，验证 `load_task_context`、`plan_task` 和 `retrieve_context` 执行期间每个 context/files/symbols/search/file GET 都有对应 SUCCESS tool call audit。
- Agent Worker README、脚本 README、Agent workflow、backend modules、MCP tools 和验收清单同步自动工具审计契约。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-smoke.sh scripts/agent-worker-callback-smoke.sh scripts/agent-worker-tool-smoke.sh scripts/agent-worker-node-smoke.sh scripts/browser-smoke.sh scripts/real-token-demo-check.sh scripts/real-coder-demo.sh scripts/real-github-pr-demo.sh` passes.
- `node --check scripts/browser-smoke.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/real-github-pr-demo.mjs` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json`.
- `./scripts/agent-worker-callback-smoke.sh` passes and writes `output/agent-worker-callback-smoke/last-run.json`.
- `./scripts/agent-worker-tool-smoke.sh` passes and writes `output/agent-worker-tool-smoke/last-run.json`.
- `./scripts/agent-worker-node-smoke.sh` passes and writes `output/agent-worker-node-smoke/last-run.json` with `load_task_context` / `plan_task` / `retrieve_context` SUCCESS steps and 13 SUCCESS tool call audit records.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%`, `real-github-pr-demo-%` and `worker-tool-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the existing frontend dev server remains on `5173`.
- `git diff --check` passes, and placeholder secret scan over docs/scripts/backend/agent-worker returns empty.

### Next

- 把当前确定性 runner 提升为真正的 LangGraph graph。
- 开始迁移 `generate_patch`，让 Python Worker 能消费 `retrieve_context` 证据生成补丁，并把未来模型调用自动挂到 `record_model_call(...)`。

## 2026-07-16, Slice 109 - Worker审计回写契约版

Python Agent Worker 的回写能力从 step/status 扩展到 tool/model call audit：后端新增内部审计回写接口，Worker client 现在可以把工具调用和模型调用写入既有审计表，继续走 `X-RepoPilot-Worker-Token` 鉴权、run 作用域绑定、JSON 截断和敏感字段脱敏。这样后续把真实工具读取、模型生成和补丁生成迁到 Worker 时，前端现有 Agent evidence、tool call audit 和 model call audit 面板仍能沿用同一份审计数据。

### Added

- 后端新增 `POST /api/internal/agent-worker/runs/{runId}/tool-calls`，接收 `tool_name`、`status`、`input`、`output`、`duration_ms`、`error_message` 和可选时间戳，写入 `tool_call_log`。
- 后端新增 `POST /api/internal/agent-worker/runs/{runId}/model-calls`，接收 `step_name`、`model_provider`、`model_name`、`status`、`prompt`、`response`、token 统计、`duration_ms`、`error_message` 和可选时间戳，写入 `model_call_log`。
- `ToolCallLogService` 和 `ModelCallLogService` 新增外部补记入口，复用本地 executor 的敏感字段脱敏和 JSON 截断逻辑。
- Python Worker 新增 `AgentToolCallRecordRequest`、`AgentModelCallRecordRequest`、`BackendApiClient.record_tool_call(...)` 和 `record_model_call(...)`。
- `./scripts/agent-worker-callback-smoke.sh` 升级为 step/tool/model/status 四类回写 smoke，并验证路径、token header、JSON 契约和响应解析。
- Agent Worker README、脚本 README、API design、Agent workflow、backend modules、MCP tools 和验收清单同步 Worker 审计回写契约。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-smoke.sh scripts/agent-worker-callback-smoke.sh scripts/agent-worker-tool-smoke.sh scripts/agent-worker-node-smoke.sh scripts/browser-smoke.sh scripts/real-token-demo-check.sh scripts/real-coder-demo.sh scripts/real-github-pr-demo.sh` passes.
- `node --check scripts/browser-smoke.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/real-github-pr-demo.mjs` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json`.
- `./scripts/agent-worker-callback-smoke.sh` passes and writes `output/agent-worker-callback-smoke/last-run.json` with step/tool/model/status callback evidence.
- `./scripts/agent-worker-tool-smoke.sh` passes and writes `output/agent-worker-tool-smoke/last-run.json`.
- `./scripts/agent-worker-node-smoke.sh` passes and writes `output/agent-worker-node-smoke/last-run.json`.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%`, `real-github-pr-demo-%` and `worker-tool-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the existing frontend dev server remains on `5173`.
- `git diff --check` passes, and placeholder secret scan over docs/scripts/backend/agent-worker returns empty.

### Next

- 把 Worker 当前工具读取自动挂到 `record_tool_call(...)`，让 `load_task_context`、`plan_task` 和 `retrieve_context` 的工具调用进入审计表。
- 把当前确定性 runner 提升为真正的 LangGraph graph。
- 开始迁移 `generate_patch`，让 Python Worker 能消费 `retrieve_context` 证据生成补丁并回写 model call audit。

## 2026-07-16, Slice 108 - Worker检索节点执行版

Python Agent Worker 从“能生成计划”推进到“能按计划收集代码证据”：配置 callback token 后，`POST /runs/{runId}/start` 现在会连续执行 `load_task_context`、`plan_task` 和 `retrieve_context`。检索节点复用计划里的 search queries，通过后端内部工具桥搜索代码、去重 chunk、读取关键文件预览，并把可审查的检索证据回写为 `retrieve_context` SUCCESS step。

### Added

- `agent-worker/app/graph/initial_nodes.py` 新增 `retrieve_context(...)`，复用 `plan_task.searchQueries` 调用 `BackendApiClient.search_code(...)`。
- 检索结果按 `chunkId` 优先去重，缺少 chunk id 时按文件路径、起止行和符号名去重，最多回写 12 个代码片段摘要。
- `retrieve_context` 会读取最多 3 个命中文件的内容预览，输出 `summary`、`queries`、`resultCountByQuery`、`searchRuns`、`uniqueResultCount`、`results` 和 `readFiles`。
- 后台安全 runner 从两步扩展为三步：`load_task_context -> plan_task -> retrieve_context`；任一步失败仍按当前 step 回写 FAILED 证据。
- `./scripts/agent-worker-node-smoke.sh` 升级为初始与检索节点 smoke，后端 stub 现在覆盖 search 结果去重和 `/project/file` 文件读取，并验证三个 SUCCESS step。
- Agent Worker README、脚本 README、Agent workflow、backend modules 和验收清单同步 Worker 检索节点执行契约。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-smoke.sh scripts/agent-worker-callback-smoke.sh scripts/agent-worker-tool-smoke.sh scripts/agent-worker-node-smoke.sh scripts/browser-smoke.sh scripts/real-token-demo-check.sh scripts/real-coder-demo.sh scripts/real-github-pr-demo.sh` passes.
- `node --check scripts/browser-smoke.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/real-github-pr-demo.mjs` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json`.
- `./scripts/agent-worker-callback-smoke.sh` passes and writes `output/agent-worker-callback-smoke/last-run.json`.
- `./scripts/agent-worker-tool-smoke.sh` passes and writes `output/agent-worker-tool-smoke/last-run.json`.
- `./scripts/agent-worker-node-smoke.sh` passes and writes `output/agent-worker-node-smoke/last-run.json` with real worker start, backend context/files/symbols/search/file requests and `load_task_context` / `plan_task` / `retrieve_context` SUCCESS step callbacks.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%`, `real-github-pr-demo-%` and `worker-tool-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the existing frontend dev server remains on `5173`.
- `git diff --check` passes, and placeholder secret scan over docs/scripts/backend/agent-worker returns empty.

### Next

- 把当前确定性 runner 提升为真正的 LangGraph graph。
- 继续补齐 Worker 侧 tool call 和 model call 审计回写。
- 开始迁移 `generate_patch`，让 Python Worker 能消费 `retrieve_context` 证据生成补丁。

## 2026-07-16, Slice 107 - Worker初始节点执行版

Python Agent Worker 从“能读工具”推进到“能执行最前面的确定性节点”：配置 callback token 后，`POST /runs/{runId}/start` 会在后台执行 `load_task_context` 和 `plan_task`，通过后端内部工具桥读取 context/files/symbols/search，并把两个 SUCCESS step 回写到后端。未配置 callback token 时，`/start` 仍只返回启动契约，方便本地契约 smoke 和桥接关闭场景保持安静。

### Added

- 新增 `agent-worker/app/graph/initial_nodes.py`，实现 `load_task_context`、确定性 `plan_task` 和后台安全执行入口。
- `load_task_context` 通过 `BackendApiClient.load_run_context(...)`、`list_project_files(...)` 和 `list_symbols(...)` 读取 run-scoped 上下文，回写包含任务、项目、文件样本和符号样本的 SUCCESS step。
- `plan_task` 从任务标题/描述生成 search queries，调用 `search_code(...)` 获取检索证据，回写包含 `summary`、`steps`、`searchQueries`、`searchResults` 和 `testStrategy` 的 SUCCESS step。
- FastAPI `/runs/{runId}/start` 在 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 存在时通过 `BackgroundTasks` 调度初始节点，响应契约仍保持 `accepted=true`、`status=QUEUED` 和 MVP graph node 清单。
- 基础 `agent-worker-smoke.sh` 自启临时 worker 时清空 callback token，避免开发机环境变量意外触发后台节点。
- 新增 `./scripts/agent-worker-node-smoke.sh`，启动本地后端 stub 和真实 FastAPI worker，验证 start 后拉取 context/files/symbols/search 并回写 `load_task_context`、`plan_task` 两个 SUCCESS step。
- Agent Worker README、脚本 README、Agent workflow、backend modules 和验收清单同步初始节点执行契约。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-smoke.sh scripts/agent-worker-callback-smoke.sh scripts/agent-worker-tool-smoke.sh scripts/agent-worker-node-smoke.sh scripts/browser-smoke.sh scripts/real-token-demo-check.sh scripts/real-coder-demo.sh scripts/real-github-pr-demo.sh` passes.
- `node --check scripts/browser-smoke.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/real-github-pr-demo.mjs` pass.
- `npm run build` passes.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json` with `status=UP`, `service=agent-worker`, `status=QUEUED` and 10 graph nodes.
- `./scripts/agent-worker-callback-smoke.sh` passes and writes `output/agent-worker-callback-smoke/last-run.json` with both step and status callback evidence.
- `./scripts/agent-worker-tool-smoke.sh` passes and writes `output/agent-worker-tool-smoke/last-run.json` with run-scoped tool client evidence.
- `./scripts/agent-worker-node-smoke.sh` passes and writes `output/agent-worker-node-smoke/last-run.json` with real worker start, backend context/files/symbols/search requests and `load_task_context` / `plan_task` SUCCESS step callbacks.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%`, `real-github-pr-demo-%` and `worker-tool-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the existing frontend dev server remains on `5173`.
- `git diff --check` passes, and placeholder secret scan over docs/scripts/backend/agent-worker returns empty.

### Next

- 把当前确定性初始节点 runner 提升为真正的 LangGraph graph。
- 实现 Worker 侧 `retrieve_context` 节点，复用 `search_code(...)` 和 `read_project_file(...)` 输出可审查检索证据。
- 继续补齐 Worker 侧 tool call 和 model call 审计回写。

## 2026-07-16, Slice 106 - Worker工具读取桥版

Python Agent Worker 从“能被启动、能回写证据”推进到“能主动读取 run 作用域内的任务和仓库上下文”。后端新增内部工具读取接口，全部通过 `runId` 反查 task/project，并继续使用 Worker callback token 鉴权；Worker 侧 `BackendApiClient` 现在可以加载任务上下文、列文件、读文件、检索代码和读取符号，为后续迁移 `load_task_context`、`plan_task` 等 LangGraph 节点打基础。

### Added

- 新增 `AgentWorkerTokenGuard`，把内部 Worker token 校验抽成共用组件，并保留常量时间比较；callback 和 tool 接口共用 `AGENT_WORKER_CALLBACK_DISABLED`、`AGENT_WORKER_CALLBACK_FORBIDDEN` 错误码。
- 新增 `GET /api/internal/agent-worker/runs/{runId}/context`，返回 run、task 和 project 上下文。
- 新增 `GET /api/internal/agent-worker/runs/{runId}/project/files`、`/project/file`、`/project/search` 和 `/project/symbols`，提供 run-scoped 文件树、文件内容、代码检索和符号读取能力。
- `read_file` 只允许项目工作区内相对路径，拒绝绝对路径、`..` 越权路径和 `.git` 内部路径，单次读取限制为 200 KB。
- Python Agent Worker `BackendApiClient` 新增 `load_run_context(...)`、`list_project_files(...)`、`read_project_file(...)`、`search_code(...)` 和 `list_symbols(...)`。
- 新增 `./scripts/agent-worker-tool-smoke.sh`，用本地 HTTP stub 验证 Python client 的路径、header、query 参数和响应解析，并写入 `output/agent-worker-tool-smoke/last-run.json`。
- Agent Worker README、脚本 README、API design、Agent workflow、backend modules、MCP 工具设计和验收清单同步内部工具读取桥契约。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerToolControllerIntegrationTest,AgentWorkerCallbackControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-smoke.sh scripts/agent-worker-callback-smoke.sh scripts/agent-worker-tool-smoke.sh scripts/browser-smoke.sh scripts/real-token-demo-check.sh scripts/real-coder-demo.sh scripts/real-github-pr-demo.sh` passes.
- `node --check scripts/browser-smoke.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/real-github-pr-demo.mjs` pass.
- `npm run build` passes.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json` with `status=UP`, `service=agent-worker`, `status=QUEUED` and 10 graph nodes.
- `./scripts/agent-worker-callback-smoke.sh` passes and writes `output/agent-worker-callback-smoke/last-run.json` with both step and status callback evidence.
- `./scripts/agent-worker-tool-smoke.sh` passes and writes `output/agent-worker-tool-smoke/last-run.json` with `/context`、`/project/files`、`/project/file`、`/project/search` 和 `/project/symbols` requests, token header present, query parameters verified and parsed context/files/search/symbols evidence.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%`, `real-github-pr-demo-%` and `worker-tool-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the existing frontend dev server remains on `5173`.
- `git diff --check` passes, and placeholder secret scan over docs/scripts/backend/agent-worker returns empty.

### Next

- 用新的工具读取桥实现 Worker 侧 `load_task_context` 节点，并把执行结果通过 `record_step(...)` 回写。
- 实现 Worker 侧确定性 `plan_task` 节点，生成结构化 plan 和 search queries。
- 继续补齐 tool call 和 model call 回写，为正式 MCP Server 拆出做准备。

## 2026-07-16, Slice 105 - Worker状态回写版

Python Agent Worker 的回写通道从 step 证据扩展到 task/run 状态：Worker 现在可以通过内部 callback 把任务推进到 `WAITING_HUMAN_APPROVAL`、把 run 标为 `SUCCESS`/`FAILED`/`CANCELLED`，并按需关闭 SSE 流。这样后续 LangGraph 节点真正迁到 Worker 后，后端仍是唯一可信状态源，前端也能继续通过现有 SSE 看到状态变化。

### Added

- 新增 `POST /api/internal/agent-worker/runs/{runId}/status` 内部状态回写接口，继续使用 `X-RepoPilot-Worker-Token` 和 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 鉴权，不需要用户 JWT。
- 新增 `AgentWorkerRunStatusUpdateRequest` 和 `AgentWorkerRunStatusUpdateResponse`，支持 `task_status`、`run_status`、`error_message`、`stream_message` 和 `complete_stream`。
- `AgentWorkerCallbackService` 支持回写 task 状态、run 状态、失败/取消错误信息，并发布 `TASK_UPDATED`；`complete_stream=true` 时发布 `STREAM_COMPLETE`。
- 空状态请求返回 `AGENT_WORKER_STATUS_EMPTY`，错误 token 仍返回 `AGENT_WORKER_CALLBACK_FORBIDDEN` 且不落库。
- Python Agent Worker 新增 `AgentStatusUpdateRequest` 和 `BackendApiClient.update_status(...)`，并复用统一 callback POST 逻辑。
- `./scripts/agent-worker-callback-smoke.sh` 扩展为同时验证 `/steps` 和 `/status`，证据文件记录 `stepRequest`、`statusRequest`、`stepResponse` 和 `statusResponse`。
- Agent Worker README、脚本 README、API design、Agent workflow、backend modules 和验收清单同步 step/status 回写契约。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerCallbackControllerIntegrationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-smoke.sh scripts/agent-worker-callback-smoke.sh scripts/browser-smoke.sh scripts/real-token-demo-check.sh scripts/real-coder-demo.sh scripts/real-github-pr-demo.sh` passes.
- `node --check scripts/browser-smoke.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/real-github-pr-demo.mjs` pass.
- `npm run build` passes.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json` with `status=UP`, `service=agent-worker`, `status=QUEUED` and 10 graph nodes.
- `./scripts/agent-worker-callback-smoke.sh` passes and writes `output/agent-worker-callback-smoke/last-run.json` with both `POST /api/internal/agent-worker/runs/404/steps` and `POST /api/internal/agent-worker/runs/404/status`, callback token header present, `worker_callback_smoke` SUCCESS and `WAITING_HUMAN_APPROVAL / SUCCESS`.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the existing frontend dev server remains on `5173`.
- `git diff --check` passes, and placeholder secret scan over docs/scripts/backend/agent-worker returns empty.

### Next

- 增加 Agent Worker 的 MCP repository tool client。
- 开始把 `load_task_context` 和 `plan_task` LangGraph 节点迁移到 Python Worker，并通过 step/status 回写驱动前端事件流。
- 继续补齐 tool call 和 model call 回写。

## 2026-07-16, Slice 104 - Worker步骤回写版

Python Agent Worker 不再只是被后端启动；现在也具备把 step 证据回写到 Spring Boot 后端的受控通道。后端提供内部 callback API，Worker 侧提供 `BackendApiClient.record_step(...)`，两端用专用 callback token 连接，为后续把真实 LangGraph 节点迁移到 Python Worker 打好事件和审计通路。

### Added

- 新增 `POST /api/internal/agent-worker/runs/{runId}/steps` 内部回写接口，使用 `X-RepoPilot-Worker-Token` 和 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 鉴权，不需要用户 JWT。
- 新增 `AgentWorkerCallbackService`、`AgentWorkerCallbackController` 和 `AgentWorkerStepRecordRequest`，将 Worker 回写的 `step_name`、`status`、`input`、`output` 和 `error_message` 保存为 `agent_step`。
- 回写成功后发布 `STEP_RECORDED`，复用现有任务 SSE 事件通道。
- `SecurityConfig` 放行 `/api/internal/agent-worker/**` 到 controller，由 callback token 自行校验；错误 token 返回 `AGENT_WORKER_CALLBACK_FORBIDDEN` 且不落库。
- Python Agent Worker 新增 `app.clients.backend_api.BackendApiClient` 和 `AgentStepRecordRequest` schema，支持用 `REPOPILOT_AGENT_WORKER_CALLBACK_TOKEN` 回写 step。
- 新增 `./scripts/agent-worker-callback-smoke.sh`，用本地 HTTP stub 验证 Python client 的路径、header、JSON 和响应解析，并写入 `output/agent-worker-callback-smoke/last-run.json`。
- Agent Worker README、脚本 README、API design、Agent workflow、backend modules 和验收清单同步 Worker step 回写契约。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerCallbackControllerIntegrationTest,AgentWorkerClientTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-smoke.sh scripts/agent-worker-callback-smoke.sh scripts/browser-smoke.sh scripts/real-token-demo-check.sh scripts/real-coder-demo.sh scripts/real-github-pr-demo.sh` passes.
- `node --check scripts/browser-smoke.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/real-github-pr-demo.mjs` pass.
- `npm run build` passes.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json` with `status=UP`, `service=agent-worker`, `status=QUEUED` and 10 graph nodes.
- `./scripts/agent-worker-callback-smoke.sh` passes and writes `output/agent-worker-callback-smoke/last-run.json` with `POST /api/internal/agent-worker/runs/404/steps`, callback token header present and `worker_callback_smoke` SUCCESS response.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the frontend dev server remains on `5173`.
- `git diff --check` passes.

### Next

- 增加 Agent Worker 到 Backend API 的 task/run 状态回写，并开始把 `plan_task` 或 `load_task_context` 节点迁移到 Python Worker。
- 继续推进 MCP Tool Server 的可运行契约和工具能力封装。

## 2026-07-16, Slice 103 - 后端Worker启动桥版

Spring Boot 后端从只知道本地 executor，推进到可选桥接 Python Agent Worker：启用配置后，每次 run 执行入口会把启动契约发送给 Worker，并把 Worker 接受状态和 graph node 清单写成可审计 step。默认仍关闭桥接，现有本地演示闭环保持不变。

### Added

- 新增 `com.repopilot.agent.worker` 包，包含 `AgentWorkerGateway`、`AgentWorkerClient`、`AgentWorkerProperties` 和 `AgentWorkerStartResult`。
- 新增 `repopilot.agent-worker.enabled/base-url/timeout-seconds` 配置，支持通过 `REPOPILOT_AGENT_WORKER_ENABLED`、`REPOPILOT_AGENT_WORKER_URL` 和 `REPOPILOT_AGENT_WORKER_TIMEOUT_SECONDS` 打开后端到 Worker 的启动桥。
- `AgentTaskService` 在执行入口按配置调用 Worker start API，成功写入 `agent_worker_start` SUCCESS step，失败写入 FAILED step 并继续 Spring Boot 本地 executor 兜底。
- Agent 运行报告新增 `Agent Worker 启动桥` 小节，用于展示 run id、accepted、Worker status 和 graph node 清单。
- 新增 `AgentWorkerClientTest` 覆盖 HTTP 请求路径、snake_case 请求体、响应解析、启用配置、非 2xx 错误转译和 Worker 响应契约不匹配。
- `AgentTaskServiceRegenerationTest` 增加启用 Worker 桥接后的 `agent_worker_start` step 记录验证。
- Agent Worker README、Agent workflow、backend modules 和验收清单同步后端 Worker 启动桥边界。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 -Dtest=AgentWorkerClientTest,AgentTaskServiceRegenerationTest test` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `python3 -m compileall -q agent-worker/app` passes.
- `bash -n scripts/agent-worker-smoke.sh scripts/browser-smoke.sh scripts/real-token-demo-check.sh scripts/real-coder-demo.sh scripts/real-github-pr-demo.sh` passes.
- `node --check scripts/browser-smoke.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/real-github-pr-demo.mjs` pass.
- `npm run build` passes.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json` with `status=UP`, `service=agent-worker`, `status=QUEUED` and 10 graph nodes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact while Worker bridge remains disabled by default.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the frontend dev server remains on `5173`.
- `git diff --check` passes.

### Next

- 增加 Agent Worker 到 Backend API 的 step 回写 client，让 Worker 能主动回写 step、tool call、model call 和 task 状态。
- 继续推进 MCP Tool Server 的可运行契约和工具能力封装。

## 2026-07-16, Slice 102 - AgentWorker契约验证版

Python Agent Worker 从静态占位推进到可启动、可验证的服务契约：现在可以独立验证 worker 健康检查和 run 启动契约，为后续把 Spring Boot 内部 executor 逐步迁移到 LangGraph worker 做准备。

### Added

- 新增 `./scripts/agent-worker-smoke.sh`，检查 FastAPI/uvicorn/pydantic 依赖，按需启动 `agent-worker`，验证 `/health` 和 `/runs/{runId}/start`。
- smoke 验证 `status=UP`、`service=agent-worker`、`accepted=true`、`status=QUEUED` 和 MVP graph node 清单。
- smoke 将运行证据写入 `output/agent-worker-smoke/last-run.json`。
- `agent-worker/README.md`、脚本 README、Agent workflow 和验收清单同步 Agent Worker 当前契约边界：当前主执行链路仍由 Spring Boot executor 承担，后续再迁移到 LangGraph worker。

### Verified

- `python3 -m compileall -q agent-worker/app` passes.
- `./scripts/agent-worker-smoke.sh` passes and writes `output/agent-worker-smoke/last-run.json` with `status=UP`, `service=agent-worker`, `status=QUEUED` and 10 MVP graph nodes.
- `bash -n scripts/agent-worker-smoke.sh scripts/real-github-pr-demo.sh scripts/real-coder-demo.sh scripts/real-token-demo-check.sh scripts/browser-smoke.sh` passes.
- `node --check scripts/real-github-pr-demo.mjs`, `node --check scripts/real-coder-demo.mjs` and `node --check scripts/browser-smoke.mjs` pass.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis are healthy.
- Smoke cleanup leaves `0` `browser-smoke-%`, `real-coder-demo-%` and `real-github-pr-demo-%` users, `0` Controller API doc snapshots and `0` Agent run report snapshots.
- Ports `8080` and `8090` are free after smoke runs; the frontend dev server remains on `5173`.
- `git diff --check` passes.

### Next

- 增加 Agent Worker 到 Backend API 的 step 回写 client。
- 继续推进 MCP Tool Server 的可运行契约和工具能力封装。

## 2026-07-16, Slice 99 - 真实演示检查脚本版

真实 token 演示前新增一个命令行体检入口，把本地闭环、真实 OpenAI-compatible Coder 和远端 GitHub PR 的前置项统一检查出来，避免正式演示时再临时翻环境变量。

### Added

- 新增 `./scripts/real-token-demo-check.sh`，默认只读检查项目文件、本机命令、Docker/Compose、沙箱镜像、Maven cache、后端/前端端口、真实 Coder 和远端 GitHub PR 配置。
- 支持 `--strict`，正式演示前要求 Docker、真实 Coder 和远端 GitHub PR 关键项到位，缺项返回非 0。
- 支持 `--start-deps`，可先执行 `docker compose up -d postgres redis` 再检查依赖状态。
- 脚本只展示 key/token 是否配置，不打印 GitHub token、模型 key 或 Authorization header。
- 脚本说明、Agent workflow、沙箱/GitHub 集成设计和验收清单同步真实演示检查入口。

### Verified

- `bash -n scripts/real-token-demo-check.sh` passes.
- `./scripts/real-token-demo-check.sh` passes in default mode, reports local recipe/fallback and local PR draft mode as warnings, and exits 0 without printing secrets.
- `REPOPILOT_CODER_MODE=openai-compatible REPOPILOT_CODER_API_KEY=... REPOPILOT_CODER_MODEL=gpt-repopilot-demo REPOPILOT_GITHUB_ENABLED=true REPOPILOT_GITHUB_TOKEN=... ./scripts/real-token-demo-check.sh --strict` passes and prints only key/token configured status.
- `env -u REPOPILOT_CODER_API_KEY -u OPENAI_API_KEY -u REPOPILOT_GITHUB_TOKEN -u GITHUB_TOKEN REPOPILOT_CODER_MODE=disabled REPOPILOT_GITHUB_ENABLED=false ./scripts/real-token-demo-check.sh --strict` exits 1 and reports missing real Coder and remote PR readiness.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `./scripts/browser-smoke.sh` passes with the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots. Port `8080` is free after smoke; the existing frontend dev server remains on `5173`.

### Next

- 在真实 token 环境中补充一次真实模型端到端演示，并继续推进真实 GitHub PR 发布演示。

## 2026-07-16, Slice 98 - 演示就绪面板版

控制台配置区新增“演示就绪”总览，把本地闭环、真实模型和远端 GitHub PR 三条演示路线放在同一个中文 checklist 中，帮助真实 token 演示前快速判断还差哪些环境变量。

### Added

- `DemoReadinessPanel` 基于现有 Coder、GitHub 和 Sandbox 脱敏配置派生状态，不新增密钥读取或后端请求。
- 默认本地环境展示“本地闭环演示：可演示”“真实模型演示：可选增强”“远端 GitHub PR：本地草稿”。
- 真实模型演示提示 `REPOPILOT_CODER_MODE=openai-compatible`、`REPOPILOT_CODER_API_KEY`/`OPENAI_API_KEY` 和 `REPOPILOT_CODER_MODEL`。
- 远端 GitHub PR 演示提示 `REPOPILOT_GITHUB_ENABLED=true` 和 `REPOPILOT_GITHUB_TOKEN`/`GITHUB_TOKEN`。
- Browser smoke 新增演示就绪总览断言，前端页面规格、验收清单和脚本说明同步。

### Verified

- `mvn -q -Dmaven.repo.local=../.m2 test` passes.
- `npm run build` passes.
- `node --check scripts/browser-smoke.mjs` and `bash -n scripts/browser-smoke.sh` pass.
- `git diff --check` passes.
- `./scripts/browser-smoke.sh` passes with 演示就绪总览、本地闭环/真实模型/远端 GitHub PR 状态 and the existing end-to-end project/task/Agent/approval/PR flow intact.
- Docker Compose PostgreSQL and Redis remain healthy; smoke cleanup leaves `0` temporary smoke users, `0` Controller API doc snapshots and `0` Agent run report snapshots. Port `8080` is free after smoke; the existing frontend dev server remains on `5173`.

### Next

- 在真实 token 环境中补充一次真实模型端到端演示，并继续推进真实 GitHub PR 发布演示。
