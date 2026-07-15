package com.repopilot.project.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.repopilot.common.ApiException;
import com.repopilot.indexer.domain.CodeSymbol;
import com.repopilot.indexer.domain.CodeSymbolType;
import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.indexer.service.CodeIndexService;
import com.repopilot.indexer.service.CodeSearchService;
import com.repopilot.indexer.service.SpringControllerApiService;
import com.repopilot.project.domain.Project;
import com.repopilot.project.domain.ProjectControllerApiDocSnapshot;
import com.repopilot.project.domain.ProjectStatus;
import com.repopilot.project.dto.CreateProjectRequest;
import com.repopilot.project.dto.ProjectControllerApiDocsResponse;
import com.repopilot.project.dto.ProjectControllerApiDocsSnapshotClearResponse;
import com.repopilot.project.dto.ProjectControllerApiDocsSnapshotResponse;
import com.repopilot.project.dto.ProjectControllerApiDocsSnapshotSummaryResponse;
import com.repopilot.project.dto.ProjectControllerApiFiltersResponse;
import com.repopilot.project.dto.ProjectControllerApiListResponse;
import com.repopilot.project.dto.ProjectControllerApiParameterResponse;
import com.repopilot.project.dto.ProjectControllerApiResponse;
import com.repopilot.project.dto.ProjectControllerApiRiskSummaryResponse;
import com.repopilot.project.dto.ProjectControllerDownstreamCallResponse;
import com.repopilot.project.dto.ProjectFileResponse;
import com.repopilot.project.dto.ProjectIndexResponse;
import com.repopilot.project.dto.ProjectControllerRiskHintResponse;
import com.repopilot.project.dto.ProjectControllerServiceCallResponse;
import com.repopilot.project.repository.ProjectControllerApiDocSnapshotRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.dto.CloneProjectResponse;
import com.repopilot.repository.service.GitRepositoryService;
import com.repopilot.user.domain.User;
import com.repopilot.user.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private static final List<String> CONTROLLER_API_RISK_LEVELS = List.of("HIGH", "MEDIUM", "LOW", "NONE");
    private static final int MAX_CONTROLLER_API_DOC_LIMIT = 50;
    private static final int MAX_CONTROLLER_API_DOC_SNAPSHOT_LIMIT = 20;

    private final ProjectRepository projectRepository;
    private final ProjectControllerApiDocSnapshotRepository controllerApiDocSnapshotRepository;
    private final UserRepository userRepository;
    private final GitRepositoryService gitRepositoryService;
    private final CodeIndexService codeIndexService;
    private final CodeSearchService codeSearchService;
    private final SpringControllerApiService springControllerApiService;
    private final Path workspaceRoot;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectControllerApiDocSnapshotRepository controllerApiDocSnapshotRepository,
            UserRepository userRepository,
            GitRepositoryService gitRepositoryService,
            CodeIndexService codeIndexService,
            CodeSearchService codeSearchService,
            SpringControllerApiService springControllerApiService,
            @Value("${repopilot.workspace-root}") String workspaceRoot
    ) {
        this.projectRepository = projectRepository;
        this.controllerApiDocSnapshotRepository = controllerApiDocSnapshotRepository;
        this.userRepository = userRepository;
        this.gitRepositoryService = gitRepositoryService;
        this.codeIndexService = codeIndexService;
        this.codeSearchService = codeSearchService;
        this.springControllerApiService = springControllerApiService;
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public Project create(CreateProjectRequest request, Long ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        String defaultBranch = request.defaultBranch() == null || request.defaultBranch().isBlank()
                ? "main"
                : request.defaultBranch();
        Project project = new Project(owner, request.repoUrl(), repoFullName(request.repoUrl()), defaultBranch);
        Project saved = projectRepository.save(project);
        saved.setLocalPath(workspaceRoot.resolve("repos").resolve(String.valueOf(saved.getId())).resolve("source").toString());
        return projectRepository.save(saved);
    }

    @Transactional(readOnly = true)
    public List<Project> list(Long ownerId) {
        return projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public List<Project> search(Long ownerId, ProjectStatus status, String query) {
        String normalizedQuery = query == null ? null : query.trim().toLowerCase(Locale.ROOT);
        boolean queryPresent = normalizedQuery != null && !normalizedQuery.isBlank();
        String queryPattern = queryPresent ? "%" + normalizedQuery + "%" : "";
        return projectRepository.search(ownerId, status, queryPresent, queryPattern);
    }

    @Transactional(readOnly = true)
    public Project get(Long projectId, Long ownerId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
        if (!project.getOwner().getId().equals(ownerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_FORBIDDEN", "Project does not belong to current user");
        }
        return project;
    }

    @Transactional(readOnly = true)
    public List<ProjectFileResponse> listFiles(Long projectId, Long ownerId, int maxDepth) {
        Project project = get(projectId, ownerId);
        Path root = projectPath(project);
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root, Math.max(1, maxDepth))) {
            return stream
                    .filter(path -> !path.equals(root))
                    .filter(path -> !isGitInternalPath(root, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> toFileResponse(root, path))
                    .toList();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PROJECT_FILE_SCAN_FAILED", exception.getMessage());
        }
    }

    public CloneProjectResponse cloneRepository(Long projectId, Long ownerId) {
        Project project = get(projectId, ownerId);
        return gitRepositoryService.cloneProject(project);
    }

    @Transactional
    public ProjectIndexResponse index(Long projectId, Long ownerId) {
        Project project = get(projectId, ownerId);
        CodeIndexService.IndexResult result = codeIndexService.index(project);
        Instant indexedAt = Instant.now();
        return new ProjectIndexResponse(
                project.getId(),
                result.snapshotId(),
                result.fileCount(),
                result.javaFileCount(),
                result.symbolCount(),
                result.chunkCount(),
                indexedAt,
                "Repository indexed with JavaParser AST symbol extraction"
        );
    }

    @Transactional(readOnly = true)
    public List<CodeSymbol> listSymbols(Long projectId, Long ownerId, String type) {
        get(projectId, ownerId);
        Optional<CodeSymbolType> symbolType = Optional.empty();
        if (type != null && !type.isBlank()) {
            try {
                symbolType = Optional.of(CodeSymbolType.valueOf(type.toUpperCase()));
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INDEX_INVALID_SYMBOL_TYPE", "Unknown symbol type: " + type);
            }
        }
        return codeIndexService.listSymbols(projectId, symbolType);
    }

    @Transactional(readOnly = true)
    public CodeSearchResponse searchCode(Long projectId, Long ownerId, String query, int limit) {
        get(projectId, ownerId);
        return codeSearchService.search(projectId, query, limit);
    }

    @Transactional(readOnly = true)
    public ProjectControllerApiListResponse listControllerApis(Long projectId, Long ownerId, String riskLevel, String riskCode) {
        Project project = get(projectId, ownerId);
        ControllerApiQuery query = controllerApiQuery(project, riskLevel, riskCode);
        return new ProjectControllerApiListResponse(
                query.filteredItems(),
                query.filteredItems().size(),
                controllerApiRiskSummary(query.items()),
                controllerApiRiskCodes(query.items()),
                query.filters()
        );
    }

    @Transactional(readOnly = true)
    public ProjectControllerApiDocsResponse controllerApiDocs(
            Long projectId,
            Long ownerId,
            String riskLevel,
            String riskCode,
            int limit
    ) {
        Project project = get(projectId, ownerId);
        return controllerApiDocs(project, riskLevel, riskCode, limit);
    }

    @Transactional
    public ProjectControllerApiDocsSnapshotResponse createControllerApiDocSnapshot(
            Long projectId,
            Long ownerId,
            String riskLevel,
            String riskCode,
            int limit
    ) {
        Project project = get(projectId, ownerId);
        ProjectControllerApiDocsResponse docs = controllerApiDocs(project, riskLevel, riskCode, limit);
        ProjectControllerApiDocSnapshot snapshot = controllerApiDocSnapshotRepository.save(
                new ProjectControllerApiDocSnapshot(project, project.getOwner(), docs)
        );
        return ProjectControllerApiDocsSnapshotResponse.from(snapshot);
    }

    @Transactional(readOnly = true)
    public List<ProjectControllerApiDocsSnapshotSummaryResponse> listControllerApiDocSnapshots(
            Long projectId,
            Long ownerId,
            int limit
    ) {
        Project project = get(projectId, ownerId);
        int boundedLimit = Math.max(1, Math.min(MAX_CONTROLLER_API_DOC_SNAPSHOT_LIMIT, limit));
        return controllerApiDocSnapshotRepository
                .findByProjectIdOrderByGeneratedAtDesc(project.getId(), PageRequest.of(0, boundedLimit))
                .stream()
                .map(ProjectControllerApiDocsSnapshotSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectControllerApiDocsSnapshotResponse getControllerApiDocSnapshot(
            Long projectId,
            Long ownerId,
            Long snapshotId
    ) {
        Project project = get(projectId, ownerId);
        ProjectControllerApiDocSnapshot snapshot = controllerApiDocSnapshotRepository
                .findByIdAndProjectId(snapshotId, project.getId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CONTROLLER_API_DOC_SNAPSHOT_NOT_FOUND",
                        "Controller API docs snapshot not found"
                ));
        return ProjectControllerApiDocsSnapshotResponse.from(snapshot);
    }

    @Transactional
    public ProjectControllerApiDocsSnapshotClearResponse clearControllerApiDocSnapshots(Long projectId, Long ownerId) {
        Project project = get(projectId, ownerId);
        int deletedCount = controllerApiDocSnapshotRepository.deleteByProjectId(project.getId());
        return new ProjectControllerApiDocsSnapshotClearResponse(deletedCount);
    }

    @Transactional
    public void deleteControllerApiDocSnapshot(Long projectId, Long ownerId, Long snapshotId) {
        Project project = get(projectId, ownerId);
        ProjectControllerApiDocSnapshot snapshot = controllerApiDocSnapshotRepository
                .findByIdAndProjectId(snapshotId, project.getId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "CONTROLLER_API_DOC_SNAPSHOT_NOT_FOUND",
                        "Controller API docs snapshot not found"
                ));
        controllerApiDocSnapshotRepository.delete(snapshot);
    }

    private ProjectControllerApiDocsResponse controllerApiDocs(Project project, String riskLevel, String riskCode, int limit) {
        ControllerApiQuery query = controllerApiQuery(project, riskLevel, riskCode);
        int boundedLimit = Math.max(1, Math.min(MAX_CONTROLLER_API_DOC_LIMIT, limit));
        List<ProjectControllerApiResponse> documentItems = query.filteredItems().stream()
                .sorted(Comparator
                        .comparing(ProjectControllerApiResponse::riskScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ProjectControllerApiResponse::path)
                        .thenComparing(ProjectControllerApiResponse::httpMethod)
                        .thenComparing(ProjectControllerApiResponse::qualifiedControllerName)
                        .thenComparing(ProjectControllerApiResponse::methodName))
                .limit(boundedLimit)
                .toList();
        return new ProjectControllerApiDocsResponse(
                project.getId(),
                project.getRepoFullName(),
                Instant.now(),
                documentItems.size(),
                query.filteredItems().size(),
                query.filters(),
                controllerApiMarkdown(project, documentItems, query.filters(), query.filteredItems().size())
        );
    }

    private ControllerApiQuery controllerApiQuery(Project project, String riskLevel, String riskCode) {
        List<ProjectControllerApiResponse> items = springControllerApiService.listControllerApis(project);
        Optional<String> normalizedRiskLevel = normalizeControllerApiRiskLevel(riskLevel);
        Optional<String> normalizedRiskCode = normalizeControllerApiRiskCode(riskCode);
        List<ProjectControllerApiResponse> filteredItems = items.stream()
                .filter(item -> normalizedRiskLevel.map(level -> level.equals(item.riskLevel())).orElse(true))
                .filter(item -> normalizedRiskCode
                        .map(code -> item.riskHints().stream().anyMatch(hint -> code.equals(hint.code())))
                        .orElse(true))
                .toList();
        return new ControllerApiQuery(
                items,
                filteredItems,
                new ProjectControllerApiFiltersResponse(
                        normalizedRiskLevel.orElse(null),
                        normalizedRiskCode.orElse(null)
                )
        );
    }

    private String controllerApiMarkdown(
            Project project,
            List<ProjectControllerApiResponse> items,
            ProjectControllerApiFiltersResponse filters,
            long filteredCount
    ) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Controller API docs: ").append(project.getRepoFullName()).append("\n\n");
        markdown.append("Generated from RepoPilot project #").append(project.getId()).append(".\n");
        markdown.append("Current filters: Risk level: ")
                .append(filters.riskLevel() == null ? "ALL" : filters.riskLevel())
                .append(", Risk code: ")
                .append(filters.riskCode() == null ? "ALL" : filters.riskCode())
                .append(".\n");
        markdown.append("Routes included: ").append(items.size()).append(" of ").append(filteredCount).append(".\n\n");
        if (items.isEmpty()) {
            markdown.append("No Controller APIs match the current view.");
            return markdown.toString();
        }
        for (ProjectControllerApiResponse item : items) {
            appendControllerApiMarkdown(markdown, item);
            markdown.append("\n\n");
        }
        return markdown.toString().stripTrailing();
    }

    private void appendControllerApiMarkdown(StringBuilder markdown, ProjectControllerApiResponse item) {
        markdown.append("## ").append(item.httpMethod()).append(" ").append(item.path()).append("\n");
        markdown.append("- Controller: ").append(inlineCode(item.controllerName() + "." + item.methodName())).append("\n");
        markdown.append("- Source: ").append(inlineCode(item.filePath() + ":" + (item.startLine() == null ? "?" : item.startLine()))).append("\n");
        markdown.append("- Request body: ").append(inlineCode(item.requestType())).append("\n");
        markdown.append("- Response: ").append(inlineCode(item.responseType())).append("\n");
        markdown.append("- Security: ");
        if (item.securityAnnotations().isEmpty()) {
            markdown.append("none\n");
        } else {
            markdown.append(String.join(", ", item.securityAnnotations().stream().map(ProjectService::inlineCode).toList())).append("\n");
        }
        markdown.append("- Risk: ").append(inlineCode(item.riskLevel())).append(" score ").append(item.riskScore()).append("\n\n");
        markdown.append("### Parameters\n");
        markdown.append(markdownBulletList(
                item.parameters().stream().map(ProjectService::parameterMarkdown).toList(),
                "No parameters"
        )).append("\n\n");
        markdown.append("### Calls\n");
        markdown.append(markdownBulletList(
                item.serviceCalls().stream().map(ProjectService::serviceCallMarkdown).toList(),
                "No service or repository calls detected"
        )).append("\n\n");
        markdown.append("### Risk hints\n");
        markdown.append(markdownBulletList(
                item.riskHints().stream().map(ProjectService::riskHintMarkdown).toList(),
                "No risk hints"
        ));
    }

    private static String parameterMarkdown(ProjectControllerApiParameterResponse parameter) {
        return inlineCode(parameter.source()) + " "
                + inlineCode(parameter.name()) + ": "
                + inlineCode(parameter.type()) + " ("
                + parameterHint(parameter.required(), parameter.defaultValue()) + ")";
    }

    private static String serviceCallMarkdown(ProjectControllerServiceCallResponse call) {
        String line = call.line() == null ? "" : ":" + call.line();
        String downstream = call.downstreamCalls().isEmpty()
                ? ""
                : "; downstream " + String.join(", ", call.downstreamCalls().stream()
                .map(ProjectService::downstreamCallMarkdown)
                .toList());
        return inlineCode(call.serviceType() + "." + call.methodName())
                + " via " + inlineCode(call.receiverName()) + line + downstream;
    }

    private static String downstreamCallMarkdown(ProjectControllerDownstreamCallResponse call) {
        String line = call.line() == null ? "" : ":" + call.line();
        return call.componentType() + "." + call.methodName() + " via " + call.receiverName() + line;
    }

    private static String riskHintMarkdown(ProjectControllerRiskHintResponse hint) {
        String details = hint.details().isEmpty() ? "" : " Details: " + String.join("; ", hint.details());
        return inlineCode(hint.severity()) + " " + inlineCode(hint.code()) + ": " + hint.message() + details;
    }

    private static String markdownBulletList(List<String> values, String emptyText) {
        if (values.isEmpty()) {
            return "- " + emptyText;
        }
        return values.stream()
                .map(value -> "- " + value)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- " + emptyText);
    }

    private static String parameterHint(boolean required, String defaultValue) {
        if (defaultValue != null) {
            return "default " + defaultValue;
        }
        return required ? "required" : "optional";
    }

    private static String inlineCode(String value) {
        String text = value == null || value.isBlank() ? "none" : value;
        return "`" + text.replace("`", "\\`") + "`";
    }

    private ProjectControllerApiRiskSummaryResponse controllerApiRiskSummary(List<ProjectControllerApiResponse> items) {
        Map<String, Long> byLevel = new LinkedHashMap<>();
        CONTROLLER_API_RISK_LEVELS.forEach(level -> byLevel.put(level, 0L));
        for (ProjectControllerApiResponse item : items) {
            byLevel.merge(item.riskLevel(), 1L, Long::sum);
        }
        return new ProjectControllerApiRiskSummaryResponse(items.size(), byLevel);
    }

    private List<String> controllerApiRiskCodes(List<ProjectControllerApiResponse> items) {
        return items.stream()
                .flatMap(item -> item.riskHints().stream())
                .map(hint -> hint.code())
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private Optional<String> normalizeControllerApiRiskLevel(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank() || "ALL".equalsIgnoreCase(riskLevel)) {
            return Optional.empty();
        }
        String normalized = riskLevel.trim().toUpperCase(Locale.ROOT);
        if (!CONTROLLER_API_RISK_LEVELS.contains(normalized)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CONTROLLER_API_INVALID_RISK_LEVEL",
                    "Unknown Controller API risk level: " + riskLevel
            );
        }
        return Optional.of(normalized);
    }

    private Optional<String> normalizeControllerApiRiskCode(String riskCode) {
        if (riskCode == null || riskCode.isBlank() || "ALL".equalsIgnoreCase(riskCode)) {
            return Optional.empty();
        }
        return Optional.of(riskCode.trim());
    }

    private ProjectFileResponse toFileResponse(Path root, Path path) {
        String type = Files.isDirectory(path) ? "DIRECTORY" : "FILE";
        long size = 0L;
        try {
            if (Files.isRegularFile(path)) {
                size = Files.size(path);
            }
        } catch (IOException ignored) {
            size = 0L;
        }
        return new ProjectFileResponse(root.relativize(path).toString(), type, size);
    }

    private boolean isGitInternalPath(Path root, Path path) {
        Path relative = root.relativize(path);
        return relative.getNameCount() > 0 && ".git".equals(relative.getName(0).toString());
    }

    private Path projectPath(Project project) {
        if (project.getLocalPath() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path is not initialized");
        }
        return Path.of(project.getLocalPath()).toAbsolutePath().normalize();
    }

    private String repoFullName(String repoUrl) {
        if (repoUrl.startsWith("git@github.com:")) {
            return trimGitSuffix(repoUrl.substring("git@github.com:".length()));
        }
        try {
            URI uri = URI.create(repoUrl);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Missing repository path");
            }
            return trimGitSuffix(path.replaceFirst("^/", ""));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_INVALID_REPO_URL", "Invalid GitHub repository URL");
        }
    }

    private String trimGitSuffix(String value) {
        String trimmed = value.endsWith(".git") ? value.substring(0, value.length() - 4) : value;
        if (!trimmed.contains("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_INVALID_REPO_URL", "Repository URL must include owner and name");
        }
        return trimmed;
    }

    private record ControllerApiQuery(
            List<ProjectControllerApiResponse> items,
            List<ProjectControllerApiResponse> filteredItems,
            ProjectControllerApiFiltersResponse filters
    ) {
    }
}
