package com.repopilot.agent.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.common.ApiException;
import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.repository.PatchRecordRepository;
import com.repopilot.sandbox.domain.TestRun;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PatchRepairService {

    private static final String MODE_REPAIR_MISSING_TEST_DEPENDENCY = "REPAIR_MISSING_TEST_DEPENDENCY";
    private static final String MODE_REPAIR_MISSING_JAVA_IMPORT = "REPAIR_MISSING_JAVA_IMPORT";
    private static final Pattern MISSING_SYMBOL = Pattern.compile("(?m)symbol:\\s+(?:class|variable)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern JAVA_SOURCE_PATH = Pattern.compile("([^\\s:\\]]*src/(?:main|test)/java/[^\\s:\\]]+\\.java)");
    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private static final Map<String, String> JAVA_IMPORTS = Map.ofEntries(
            Map.entry("ArrayList", "java.util.ArrayList"),
            Map.entry("BigDecimal", "java.math.BigDecimal"),
            Map.entry("HashMap", "java.util.HashMap"),
            Map.entry("HashSet", "java.util.HashSet"),
            Map.entry("Instant", "java.time.Instant"),
            Map.entry("List", "java.util.List"),
            Map.entry("LocalDate", "java.time.LocalDate"),
            Map.entry("LocalDateTime", "java.time.LocalDateTime"),
            Map.entry("Map", "java.util.Map"),
            Map.entry("Objects", "java.util.Objects"),
            Map.entry("Optional", "java.util.Optional"),
            Map.entry("Set", "java.util.Set"),
            Map.entry("Stream", "java.util.stream.Stream"),
            Map.entry("Collectors", "java.util.stream.Collectors")
    );

    private final PatchRecordRepository patchRecordRepository;

    public PatchRepairService(PatchRecordRepository patchRecordRepository) {
        this.patchRecordRepository = patchRecordRepository;
    }

    public PatchRecord repairMavenFailure(
            AgentTask task,
            AgentRun run,
            PatchRecord failedPatch,
            TestRun failedTestRun,
            int attempt
    ) {
        if (looksLikeMissingTestDependency(failedTestRun.getLogExcerpt())) {
            return repairMissingTestDependency(task, run, failedPatch, failedTestRun, attempt);
        }
        Optional<MissingImportRepair> missingImport = missingImportRepair(task, failedPatch, failedTestRun.getLogExcerpt());
        if (missingImport.isPresent()) {
            return repairMissingJavaImport(task, run, failedPatch, attempt, missingImport.get());
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "REPAIR_NOT_AVAILABLE",
                "RepairAgent 暂时没有适合这段 Maven 失败日志的确定性修复"
        );
    }

    public PatchRecord repairMissingTestDependency(
            AgentTask task,
            AgentRun run,
            PatchRecord failedPatch,
            TestRun failedTestRun,
            int attempt
    ) {
        if (!looksLikeMissingTestDependency(failedTestRun.getLogExcerpt())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REPAIR_NOT_AVAILABLE",
                    "RepairAgent 暂时没有适合这段 Maven 失败日志的确定性修复"
            );
        }
        if (failedPatch.getDiffContent().contains("spring-boot-starter-test")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REPAIR_NOT_AVAILABLE",
                    "补丁已经包含 spring-boot-starter-test"
            );
        }

        Path repositoryPath = repositoryPath(task);
        Path pomPath = repositoryPath.resolve("pom.xml");
        String currentPom = read(pomPath);
        String repairedPom = addTestDependency(currentPom);
        if (repairedPom.equals(currentPom)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REPAIR_NOT_AVAILABLE",
                    "RepairAgent 无法向 pom.xml 补充 spring-boot-starter-test"
            );
        }

        String repairedDiff = replaceFileDiff("pom.xml", currentPom, repairedPom) + failedPatch.getDiffContent();
        return patchRecordRepository.save(new PatchRecord(
                task,
                run,
                failedPatch.getBaseBranch(),
                failedPatch.getTargetBranch(),
                repairedDiff,
                "修复尝试 " + attempt + "：补充缺失的 Spring Boot test 依赖后重新运行沙箱测试。",
                MODE_REPAIR_MISSING_TEST_DEPENDENCY
        ));
    }

    private PatchRecord repairMissingJavaImport(
            AgentTask task,
            AgentRun run,
            PatchRecord failedPatch,
            int attempt,
            MissingImportRepair repair
    ) {
        String originalContent = read(repair.path());
        String patchedContent = applyFilePatch(originalContent, failedPatch.getDiffContent(), repair.filePath());
        String repairedContent = addImport(patchedContent, repair.importName());
        if (repairedContent.equals(patchedContent)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REPAIR_NOT_AVAILABLE",
                    "补丁已经包含 " + repair.importName() + " import"
            );
        }

        String repairedDiff = replaceFileDiff(repair.filePath(), originalContent, repairedContent)
                + removeFileDiff(failedPatch.getDiffContent(), repair.filePath());
        return patchRecordRepository.save(new PatchRecord(
                task,
                run,
                failedPatch.getBaseBranch(),
                failedPatch.getTargetBranch(),
                repairedDiff,
                "修复尝试 " + attempt + "：为 " + repair.filePath() + " 补充 " + repair.importName() + " import 后重新运行沙箱测试。",
                MODE_REPAIR_MISSING_JAVA_IMPORT
        ));
    }

    private boolean looksLikeMissingTestDependency(String logExcerpt) {
        if (logExcerpt == null || logExcerpt.isBlank()) {
            return false;
        }
        String normalized = logExcerpt.toLowerCase();
        return normalized.contains("package org.junit.jupiter.api does not exist")
                || normalized.contains("package org.assertj.core.api does not exist")
                || normalized.contains("cannot find symbol")
                && (normalized.contains("org.junit.jupiter.api") || normalized.contains("assertthat"));
    }

    private Path repositoryPath(AgentTask task) {
        String localPath = task.getProject().getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path is not initialized");
        }
        Path repositoryPath = Path.of(localPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(repositoryPath)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path does not exist");
        }
        return repositoryPath;
    }

    private Optional<MissingImportRepair> missingImportRepair(AgentTask task, PatchRecord failedPatch, String logExcerpt) {
        if (logExcerpt == null || !logExcerpt.toLowerCase().contains("cannot find symbol")) {
            return Optional.empty();
        }
        Matcher symbolMatcher = MISSING_SYMBOL.matcher(logExcerpt);
        while (symbolMatcher.find()) {
            String importName = JAVA_IMPORTS.get(symbolMatcher.group(1));
            if (importName == null) {
                continue;
            }
            Optional<String> filePath = javaSourcePath(task, logExcerpt);
            if (filePath.isEmpty() || !failedPatch.getDiffContent().contains("diff --git a/" + filePath.get() + " b/" + filePath.get())) {
                return Optional.empty();
            }
            Path path = repositoryPath(task).resolve(filePath.get()).normalize();
            if (!path.startsWith(repositoryPath(task)) || !Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(new MissingImportRepair(filePath.get(), path, importName));
        }
        return Optional.empty();
    }

    private Optional<String> javaSourcePath(AgentTask task, String logExcerpt) {
        Matcher matcher = JAVA_SOURCE_PATH.matcher(logExcerpt);
        while (matcher.find()) {
            String path = normalizeJavaSourcePath(matcher.group(1));
            if (!path.isBlank()) {
                return Optional.of(path);
            }
        }
        Matcher fileNameMatcher = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*\\.java)").matcher(logExcerpt);
        if (fileNameMatcher.find()) {
            return findUniqueJavaFile(repositoryPath(task), fileNameMatcher.group(1));
        }
        return Optional.empty();
    }

    private Optional<String> findUniqueJavaFile(Path repositoryPath, String fileName) {
        try (var stream = Files.walk(repositoryPath.resolve("src"))) {
            List<String> matches = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .map(repositoryPath::relativize)
                    .map(path -> path.toString().replace("\\", "/"))
                    .toList();
            return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String normalizeJavaSourcePath(String rawPath) {
        String normalized = rawPath.replace("\\", "/");
        int mainIndex = normalized.indexOf("src/main/java/");
        if (mainIndex >= 0) {
            return normalized.substring(mainIndex);
        }
        int testIndex = normalized.indexOf("src/test/java/");
        if (testIndex >= 0) {
            return normalized.substring(testIndex);
        }
        return normalized.replaceFirst("^/+", "");
    }

    private String read(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new ApiException(HttpStatus.CONFLICT, "REPAIR_FILE_NOT_FOUND", "修复所需文件不存在：" + path.getFileName());
            }
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPAIR_FILE_READ_FAILED", exception.getMessage());
        }
    }

    private String addTestDependency(String content) {
        if (content.contains("spring-boot-starter-test")) {
            return content;
        }
        String dataJpaDependency = ""
                + "        <dependency>\n"
                + "            <groupId>org.springframework.boot</groupId>\n"
                + "            <artifactId>spring-boot-starter-data-jpa</artifactId>\n"
                + "        </dependency>\n";
        String testDependency = ""
                + "        <dependency>\n"
                + "            <groupId>org.springframework.boot</groupId>\n"
                + "            <artifactId>spring-boot-starter-test</artifactId>\n"
                + "            <scope>test</scope>\n"
                + "        </dependency>\n";
        int index = content.indexOf(dataJpaDependency);
        if (index >= 0) {
            return content.substring(0, index)
                    + dataJpaDependency
                    + testDependency
                    + content.substring(index + dataJpaDependency.length());
        }
        int dependenciesEnd = content.indexOf("    </dependencies>");
        if (dependenciesEnd < 0) {
            return content;
        }
        return content.substring(0, dependenciesEnd) + testDependency + content.substring(dependenciesEnd);
    }

    private String addImport(String content, String importName) {
        String importLine = "import " + importName + ";";
        if (content.contains(importLine)) {
            return content;
        }
        List<String> sourceLines = lines(content);
        if (sourceLines.isEmpty()) {
            return content;
        }
        int lastJavaImportIndex = -1;
        int lastImportIndex = -1;
        int firstImportIndex = -1;
        for (int index = 0; index < sourceLines.size(); index++) {
            String line = sourceLines.get(index);
            if (line.startsWith("import ")) {
                if (firstImportIndex < 0) {
                    firstImportIndex = index;
                }
                lastImportIndex = index;
                if (line.startsWith("import java.")) {
                    lastJavaImportIndex = index;
                }
            }
        }
        List<String> repairedLines = new ArrayList<>(sourceLines);
        if (importName.startsWith("java.") && lastJavaImportIndex >= 0) {
            repairedLines.add(lastJavaImportIndex + 1, importLine);
            return String.join("\n", repairedLines) + "\n";
        }
        if (importName.startsWith("java.") && firstImportIndex >= 0) {
            repairedLines.add(firstImportIndex, importLine);
            return String.join("\n", repairedLines) + "\n";
        }
        if (lastImportIndex >= 0) {
            int importInsertIndex = lastImportIndex + 1;
            repairedLines.add(importInsertIndex, importLine);
            return String.join("\n", repairedLines) + "\n";
        }
        if (repairedLines.get(0).startsWith("package ")) {
            repairedLines.add(1, "");
            repairedLines.add(2, importLine);
            return String.join("\n", repairedLines) + "\n";
        }
        repairedLines.add(0, importLine);
        return String.join("\n", repairedLines) + "\n";
    }

    private String applyFilePatch(String originalContent, String diffContent, String filePath) {
        List<String> fileDiff = fileDiff(diffContent, filePath)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "REPAIR_NOT_AVAILABLE", "失败补丁没有包含 " + filePath));
        List<String> originalLines = lines(originalContent);
        List<String> patchedLines = new ArrayList<>();
        int originalIndex = 0;
        int index = 0;
        while (index < fileDiff.size()) {
            String line = fileDiff.get(index);
            if (!line.startsWith("@@ ")) {
                index++;
                continue;
            }
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (!matcher.matches()) {
                throw new ApiException(HttpStatus.CONFLICT, "REPAIR_NOT_AVAILABLE", "RepairAgent 无法解析失败补丁 hunk");
            }
            int oldStart = Integer.parseInt(matcher.group(1));
            int targetOriginalIndex = oldStart == 0 ? 0 : oldStart - 1;
            while (originalIndex < targetOriginalIndex && originalIndex < originalLines.size()) {
                patchedLines.add(originalLines.get(originalIndex++));
            }
            index++;
            while (index < fileDiff.size() && !fileDiff.get(index).startsWith("@@ ")) {
                String hunkLine = fileDiff.get(index);
                if (hunkLine.startsWith("+") && !hunkLine.startsWith("+++")) {
                    patchedLines.add(hunkLine.substring(1));
                } else if (hunkLine.startsWith("-") && !hunkLine.startsWith("---")) {
                    originalIndex++;
                } else if (hunkLine.startsWith(" ")) {
                    patchedLines.add(hunkLine.substring(1));
                    originalIndex++;
                } else if (hunkLine.equals("\\ No newline at end of file")) {
                    // Git metadata line; no content to apply.
                }
                index++;
            }
        }
        while (originalIndex < originalLines.size()) {
            patchedLines.add(originalLines.get(originalIndex++));
        }
        return String.join("\n", patchedLines) + "\n";
    }

    private Optional<List<String>> fileDiff(String diffContent, String filePath) {
        List<String> blocks = splitDiffBlocks(diffContent);
        for (String block : blocks) {
            if (block.startsWith("diff --git a/" + filePath + " b/" + filePath + "\n")) {
                return Optional.of(lines(block));
            }
        }
        return Optional.empty();
    }

    private String removeFileDiff(String diffContent, String filePath) {
        StringBuilder builder = new StringBuilder();
        for (String block : splitDiffBlocks(diffContent)) {
            if (!block.startsWith("diff --git a/" + filePath + " b/" + filePath + "\n")) {
                builder.append(block);
            }
        }
        return builder.toString();
    }

    private List<String> splitDiffBlocks(String diffContent) {
        String normalized = diffContent.replace("\r\n", "\n");
        List<String> blocks = new ArrayList<>();
        int start = normalized.indexOf("diff --git ");
        while (start >= 0) {
            int next = normalized.indexOf("\ndiff --git ", start + 1);
            if (next < 0) {
                blocks.add(ensureTrailingNewline(normalized.substring(start)));
                break;
            }
            blocks.add(ensureTrailingNewline(normalized.substring(start, next + 1)));
            start = next + 1;
        }
        return blocks;
    }

    private static String replaceFileDiff(String filePath, String oldContent, String newContent) {
        List<String> oldLines = lines(oldContent);
        List<String> newLines = lines(newContent);
        StringBuilder builder = new StringBuilder();
        builder.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
        builder.append("index 1111111..2222222 100644\n");
        builder.append("--- a/").append(filePath).append("\n");
        builder.append("+++ b/").append(filePath).append("\n");
        builder.append("@@ -1,").append(oldLines.size()).append(" +1,").append(newLines.size()).append(" @@\n");
        for (String line : oldLines) {
            builder.append("-").append(line).append("\n");
        }
        for (String line : newLines) {
            builder.append("+").append(line).append("\n");
        }
        return builder.toString();
    }

    private static String ensureTrailingNewline(String content) {
        return content.endsWith("\n") ? content : content + "\n";
    }

    private static List<String> lines(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
    }

    private record MissingImportRepair(String filePath, Path path, String importName) {
    }
}
