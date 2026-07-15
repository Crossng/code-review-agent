package com.repopilot.indexer.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.repopilot.common.ApiException;
import com.repopilot.indexer.domain.CodeChunk;
import com.repopilot.indexer.domain.CodeChunkType;
import com.repopilot.indexer.domain.CodeFile;
import com.repopilot.indexer.domain.CodeFileLanguage;
import com.repopilot.indexer.domain.CodeSymbol;
import com.repopilot.indexer.domain.CodeSymbolType;
import com.repopilot.indexer.repository.CodeChunkRepository;
import com.repopilot.indexer.repository.CodeFileRepository;
import com.repopilot.indexer.repository.CodeSymbolRepository;
import com.repopilot.project.domain.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.domain.RepositorySnapshot;
import com.repopilot.repository.repository.RepositorySnapshotRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CodeIndexService {

    private static final int MAX_CHUNK_CHARS = 16000;

    private final ProjectRepository projectRepository;
    private final RepositorySnapshotRepository snapshotRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final CodeFileRepository codeFileRepository;
    private final CodeSymbolRepository codeSymbolRepository;
    private final ObjectMapper objectMapper;
    private final JavaParser javaParser;

    public CodeIndexService(
            ProjectRepository projectRepository,
            RepositorySnapshotRepository snapshotRepository,
            CodeChunkRepository codeChunkRepository,
            CodeFileRepository codeFileRepository,
            CodeSymbolRepository codeSymbolRepository,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.snapshotRepository = snapshotRepository;
        this.codeChunkRepository = codeChunkRepository;
        this.codeFileRepository = codeFileRepository;
        this.codeSymbolRepository = codeSymbolRepository;
        this.objectMapper = objectMapper;
        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(parserConfiguration);
    }

    @Transactional
    public IndexResult index(Project project) {
        Path root = projectPath(project);
        if (!Files.exists(root)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_FOUND", "Clone repository before indexing");
        }

        List<Path> files = walkRegularFiles(root);
        List<Path> javaFiles = files.stream().filter(path -> path.toString().endsWith(".java")).toList();
        RepositorySnapshot snapshot = snapshotRepository.save(new RepositorySnapshot(
                project,
                gitValue(root, "rev-parse", "--abbrev-ref", "HEAD").orElse(project.getDefaultBranch()),
                gitValue(root, "rev-parse", "HEAD").orElse("UNKNOWN"),
                files.size(),
                javaFiles.size()
        ));

        codeChunkRepository.deleteByProjectId(project.getId());
        codeSymbolRepository.deleteByProjectId(project.getId());
        codeFileRepository.deleteByProjectId(project.getId());

        int symbolCount = 0;
        for (Path file : files) {
            CodeFile codeFile = codeFileRepository.save(toCodeFile(project, snapshot, root, file));
            createFileChunk(project, codeFile, file);
            if (codeFile.getLanguage() == CodeFileLanguage.JAVA) {
                symbolCount += indexJavaFile(project, codeFile, file);
            }
        }

        project.markIndexed(Instant.now());
        projectRepository.save(project);
        long chunkCount = codeChunkRepository.countByProjectId(project.getId());

        return new IndexResult(snapshot.getId(), files.size(), javaFiles.size(), symbolCount, chunkCount);
    }

    @Transactional(readOnly = true)
    public List<CodeSymbol> listSymbols(Long projectId, Optional<CodeSymbolType> symbolType) {
        return symbolType
                .map(type -> codeSymbolRepository.findByProjectIdAndSymbolTypeOrderByQualifiedNameAsc(projectId, type))
                .orElseGet(() -> codeSymbolRepository.findByProjectIdOrderByQualifiedNameAsc(projectId));
    }

    private CodeFile toCodeFile(Project project, RepositorySnapshot snapshot, Path root, Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            return new CodeFile(
                    project,
                    snapshot,
                    root.relativize(file).toString(),
                    language(file),
                    sha256(content),
                    content.length
            );
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INDEX_FILE_READ_FAILED", exception.getMessage());
        }
    }

    private int indexJavaFile(Project project, CodeFile codeFile, Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            ParseResult<CompilationUnit> result = javaParser.parse(file);
            if (result.getResult().isEmpty()) {
                return 0;
            }
            CompilationUnit unit = result.getResult().get();
            String packageName = unit.getPackageDeclaration().map(declaration -> declaration.getName().asString()).orElse("");
            int count = 0;
            for (TypeDeclaration<?> type : unit.getTypes()) {
                count += indexType(project, codeFile, packageName, type, lines);
            }
            return count;
        } catch (IOException exception) {
            return 0;
        }
    }

    private int indexType(Project project, CodeFile codeFile, String packageName, TypeDeclaration<?> type, List<String> lines) {
        String typeName = type.getNameAsString();
        String qualifiedTypeName = packageName.isBlank() ? typeName : packageName + "." + typeName;
        CodeSymbolType symbolType = classifyType(type);
        int count = saveSymbol(project, codeFile, symbolType, typeName, qualifiedTypeName, annotations(type), type, lines);

        for (MethodDeclaration method : type.findAll(MethodDeclaration.class)) {
            count += saveSymbol(
                    project,
                    codeFile,
                    CodeSymbolType.METHOD,
                    method.getNameAsString(),
                    qualifiedTypeName + "#" + method.getNameAsString(),
                    annotations(method),
                    method,
                    lines
            );
        }
        for (FieldDeclaration field : type.findAll(FieldDeclaration.class)) {
            for (var variable : field.getVariables()) {
                count += saveSymbol(
                        project,
                        codeFile,
                        CodeSymbolType.FIELD,
                        variable.getNameAsString(),
                        qualifiedTypeName + "#" + variable.getNameAsString(),
                        annotations(field),
                        field,
                        lines
                );
            }
        }
        return count;
    }

    private int saveSymbol(
            Project project,
            CodeFile codeFile,
            CodeSymbolType symbolType,
            String name,
            String qualifiedName,
            List<String> annotations,
            Node node,
            List<String> lines
    ) {
        CodeSymbol saved = codeSymbolRepository.save(new CodeSymbol(
                project,
                codeFile,
                symbolType,
                name,
                qualifiedName,
                toJson(annotations),
                startLine(node),
                endLine(node)
        ));
        createSymbolChunk(project, codeFile, saved, symbolType, qualifiedName, node, lines);
        return 1;
    }

    private void createFileChunk(Project project, CodeFile codeFile, Path file) {
        readText(file).ifPresent(content -> codeChunkRepository.save(new CodeChunk(
                project,
                codeFile,
                null,
                CodeChunkType.FILE,
                truncate(content),
                "FILE " + codeFile.getPath(),
                1,
                lineCount(content)
        )));
    }

    private void createSymbolChunk(
            Project project,
            CodeFile codeFile,
            CodeSymbol symbol,
            CodeSymbolType symbolType,
            String qualifiedName,
            Node node,
            List<String> lines
    ) {
        CodeChunkType chunkType = chunkType(symbolType);
        if (chunkType == null) {
            return;
        }
        String content = rangeContent(lines, startLine(node), endLine(node));
        if (content.isBlank()) {
            content = node.toString();
        }
        codeChunkRepository.save(new CodeChunk(
                project,
                codeFile,
                symbol,
                chunkType,
                truncate(content),
                symbolType + " " + qualifiedName,
                startLine(node),
                endLine(node)
        ));
    }

    private CodeChunkType chunkType(CodeSymbolType symbolType) {
        return switch (symbolType) {
            case METHOD -> CodeChunkType.METHOD;
            case CLASS, INTERFACE, ENUM, CONTROLLER, SERVICE, MAPPER, ENTITY -> CodeChunkType.CLASS;
            case FIELD -> null;
        };
    }

    private CodeSymbolType classifyType(TypeDeclaration<?> type) {
        List<String> annotations = annotations(type);
        if (hasAnnotation(annotations, "RestController") || hasAnnotation(annotations, "Controller")) {
            return CodeSymbolType.CONTROLLER;
        }
        if (hasAnnotation(annotations, "Service")) {
            return CodeSymbolType.SERVICE;
        }
        if (hasAnnotation(annotations, "Mapper") || hasAnnotation(annotations, "Repository")) {
            return CodeSymbolType.MAPPER;
        }
        if (hasAnnotation(annotations, "Entity") || hasAnnotation(annotations, "Table")) {
            return CodeSymbolType.ENTITY;
        }
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.isInterface() ? CodeSymbolType.INTERFACE : CodeSymbolType.CLASS;
        }
        if (type instanceof EnumDeclaration) {
            return CodeSymbolType.ENUM;
        }
        return CodeSymbolType.CLASS;
    }

    private boolean hasAnnotation(List<String> annotations, String expected) {
        return annotations.stream().anyMatch(annotation -> annotation.equals(expected) || annotation.endsWith("." + expected));
    }

    private List<String> annotations(Node node) {
        return node.findAll(AnnotationExpr.class, child -> child.getParentNode().orElse(null) == node)
                .stream()
                .map(annotation -> annotation.getName().asString())
                .toList();
    }

    private String toJson(List<String> annotations) {
        try {
            return objectMapper.writeValueAsString(annotations);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private Integer startLine(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(null);
    }

    private Integer endLine(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(null);
    }

    private Optional<String> readText(Path file) {
        try {
            if (Files.size(file) > 500_000) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private int lineCount(String content) {
        return (int) content.lines().count();
    }

    private String rangeContent(List<String> lines, Integer startLine, Integer endLine) {
        if (startLine == null || endLine == null || lines.isEmpty()) {
            return "";
        }
        int from = Math.max(1, startLine);
        int to = Math.min(lines.size(), endLine);
        if (from > to) {
            return "";
        }
        return String.join("\n", lines.subList(from - 1, to));
    }

    private String truncate(String content) {
        if (content.length() <= MAX_CHUNK_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CHUNK_CHARS);
    }

    private CodeFileLanguage language(Path file) {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(".java")) {
            return CodeFileLanguage.JAVA;
        }
        if (fileName.endsWith(".xml")) {
            return CodeFileLanguage.XML;
        }
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            return CodeFileLanguage.YAML;
        }
        if (fileName.endsWith(".properties")) {
            return CodeFileLanguage.PROPERTIES;
        }
        return CodeFileLanguage.OTHER;
    }

    private List<Path> walkRegularFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("/.git/"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INDEX_FILE_SCAN_FAILED", exception.getMessage());
        }
    }

    private Optional<String> gitValue(Path root, String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Path projectPath(Project project) {
        if (project.getLocalPath() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path is not initialized");
        }
        return Path.of(project.getLocalPath()).toAbsolutePath().normalize();
    }

    public record IndexResult(Long snapshotId, int fileCount, int javaFileCount, int symbolCount, long chunkCount) {
    }
}
