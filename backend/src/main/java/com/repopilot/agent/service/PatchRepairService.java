package com.repopilot.agent.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    private final PatchRecordRepository patchRecordRepository;

    public PatchRepairService(PatchRecordRepository patchRecordRepository) {
        this.patchRecordRepository = patchRecordRepository;
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
                    "RepairAgent has no deterministic repair for this Maven failure"
            );
        }
        if (failedPatch.getDiffContent().contains("spring-boot-starter-test")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REPAIR_NOT_AVAILABLE",
                    "Patch already contains spring-boot-starter-test"
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
                    "RepairAgent could not add spring-boot-starter-test to pom.xml"
            );
        }

        String repairedDiff = replaceFileDiff("pom.xml", currentPom, repairedPom) + failedPatch.getDiffContent();
        return patchRecordRepository.save(new PatchRecord(
                task,
                run,
                failedPatch.getBaseBranch(),
                failedPatch.getTargetBranch(),
                repairedDiff,
                "Repair attempt " + attempt + ": adds missing Spring Boot test dependency before rerunning sandbox tests.",
                "REPAIR_MISSING_TEST_DEPENDENCY"
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

    private String read(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new ApiException(HttpStatus.CONFLICT, "REPAIR_POM_NOT_FOUND", "pom.xml not found");
            }
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPAIR_POM_READ_FAILED", exception.getMessage());
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
}
