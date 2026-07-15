package com.repopilot.agent.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.dto.PatchChangedFileResponse;
import com.repopilot.sandbox.domain.TestRun;
import com.repopilot.sandbox.domain.TestRunStatus;
import org.springframework.stereotype.Service;

@Service
public class PatchRiskReviewService {

    public PatchRiskReview review(PatchRecord patch, TestRun testRun) {
        List<PatchChangedFileResponse> changedFiles = PatchChangedFileResponse.fromDiff(patch.getDiffContent());
        List<AddedLine> addedLines = addedLines(patch.getDiffContent());
        List<PatchRiskFinding> findings = new ArrayList<>();

        reviewControllerSecurity(addedLines, findings);
        reviewPaginationBounds(addedLines, findings);
        reviewSqlRisk(addedLines, findings);
        reviewTestCoverage(changedFiles, testRun, findings);

        String riskLevel = highestSeverity(findings);
        return new PatchRiskReview(
                riskLevel,
                summary(riskLevel, findings),
                findings.stream()
                        .sorted(Comparator.comparing((PatchRiskFinding finding) -> severityRank(finding.severity())).reversed())
                        .toList()
        );
    }

    private void reviewControllerSecurity(List<AddedLine> addedLines, List<PatchRiskFinding> findings) {
        List<AddedLine> newEndpoints = addedLines.stream()
                .filter(line -> line.filePath() != null && line.filePath().endsWith("Controller.java"))
                .filter(line -> line.content().contains("@GetMapping")
                        || line.content().contains("@PostMapping")
                        || line.content().contains("@PutMapping")
                        || line.content().contains("@DeleteMapping")
                        || line.content().contains("@PatchMapping")
                        || line.content().contains("@RequestMapping"))
                .toList();
        if (newEndpoints.isEmpty()) {
            return;
        }
        boolean hasSecurityAnnotation = addedLines.stream()
                .filter(line -> line.filePath() != null && line.filePath().endsWith("Controller.java"))
                .map(AddedLine::content)
                .anyMatch(content -> content.contains("@PreAuthorize")
                        || content.contains("@Secured")
                        || content.contains("@RolesAllowed")
                        || content.contains("@Authenticated")
                        || content.contains("@PermitAll"));
        if (!hasSecurityAnnotation) {
            findings.add(new PatchRiskFinding(
                    "MEDIUM",
                    "NEW_CONTROLLER_ENDPOINT_WITHOUT_AUTH",
                    "新增 Controller 接口没有显式鉴权注解。",
                    newEndpoints.get(0).filePath()
            ));
        }
    }

    private void reviewPaginationBounds(List<AddedLine> addedLines, List<PatchRiskFinding> findings) {
        boolean addsPageOrSizeParam = addedLines.stream()
                .map(AddedLine::content)
                .anyMatch(content -> content.contains("@RequestParam")
                        && (content.contains("page") || content.contains("size") || content.contains("limit")));
        if (!addsPageOrSizeParam) {
            return;
        }
        boolean annotationBounds = addedLines.stream()
                .map(AddedLine::content)
                .anyMatch(content -> content.contains("@Min") || content.contains("@Max") || content.contains("@Positive"));
        boolean serviceBounds = addedLines.stream()
                .map(AddedLine::content)
                .anyMatch(content -> content.contains("Math.max(page")
                        || content.contains("Math.max(size")
                        || content.contains("Math.min(Math.max(size"));
        if (annotationBounds || serviceBounds) {
            findings.add(new PatchRiskFinding(
                    "LOW",
                    "PAGINATION_BOUNDS_NORMALIZED",
                    "分页输入在使用前已经做了边界限制或归一化。",
                    firstControllerPath(addedLines)
            ));
            return;
        }
        findings.add(new PatchRiskFinding(
                "MEDIUM",
                "PAGINATION_PARAMETER_WITHOUT_BOUNDS",
                "新增分页查询参数，但没有看到明确的上下界约束。",
                firstControllerPath(addedLines)
        ));
    }

    private void reviewSqlRisk(List<AddedLine> addedLines, List<PatchRiskFinding> findings) {
        addedLines.stream()
                .filter(line -> {
                    String normalized = line.content().toLowerCase(Locale.ROOT);
                    return normalized.contains("createquery(")
                            || normalized.contains("createnativequery(")
                            || normalized.contains("@query(")
                            || normalized.contains("statement ")
                            || normalized.contains("preparestatement(");
                })
                .findFirst()
                .ifPresent(line -> findings.add(new PatchRiskFinding(
                        "HIGH",
                        "SQL_ACCESS_REQUIRES_REVIEW",
                        "补丁新增了直接查询或 statement 构造，需要复核参数绑定和注入风险。",
                        line.filePath()
                )));
    }

    private void reviewTestCoverage(
            List<PatchChangedFileResponse> changedFiles,
            TestRun testRun,
            List<PatchRiskFinding> findings
    ) {
        boolean hasProductionChange = changedFiles.stream()
                .anyMatch(file -> file.path() != null
                        && file.path().startsWith("src/main/")
                        && file.addedLines() + file.deletedLines() > 0);
        boolean hasTestChange = changedFiles.stream()
                .anyMatch(file -> file.path() != null
                        && file.path().startsWith("src/test/")
                        && file.addedLines() > 0);
        if (hasTestChange && testRun.getStatus() == TestRunStatus.PASSED) {
            findings.add(new PatchRiskFinding(
                    "INFO",
                    "TEST_COVERAGE_PRESENT",
                    "补丁包含测试变更，且沙箱测试已通过。",
                    changedFiles.stream()
                            .map(PatchChangedFileResponse::path)
                            .filter(path -> path != null && path.startsWith("src/test/"))
                            .findFirst()
                            .orElse(null)
            ));
            return;
        }
        if (hasProductionChange) {
            findings.add(new PatchRiskFinding(
                    "MEDIUM",
                    "TEST_COVERAGE_MISSING",
                    "补丁修改了生产代码，但没有新增或修改测试。",
                    null
            ));
        }
    }

    private List<AddedLine> addedLines(String diffContent) {
        List<AddedLine> lines = new ArrayList<>();
        String currentFile = null;
        for (String line : diffContent.replace("\r\n", "\n").split("\n")) {
            if (line.startsWith("+++ b/")) {
                currentFile = line.substring("+++ b/".length());
                continue;
            }
            if (line.startsWith("+++ /dev/null")) {
                currentFile = null;
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                lines.add(new AddedLine(currentFile, line.substring(1)));
            }
        }
        return lines;
    }

    private String firstControllerPath(List<AddedLine> addedLines) {
        return addedLines.stream()
                .map(AddedLine::filePath)
                .filter(path -> path != null && path.endsWith("Controller.java"))
                .findFirst()
                .orElse(null);
    }

    private String highestSeverity(List<PatchRiskFinding> findings) {
        return findings.stream()
                .map(PatchRiskFinding::severity)
                .max(Comparator.comparing(this::severityRank))
                .orElse("NONE");
    }

    private String summary(String riskLevel, List<PatchRiskFinding> findings) {
        if (findings.isEmpty()) {
            return "没有自动审查发现。";
        }
        long actionableFindings = findings.stream()
                .filter(finding -> !"INFO".equals(finding.severity()))
                .count();
        if (actionableFindings == 0) {
            return "自动审查只发现信息性提示。";
        }
        return "自动审查发现 " + actionableFindings + " 个需要处理的风险点，最高风险等级为 " + riskLevel + "。";
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private record AddedLine(String filePath, String content) {
    }

    public record PatchRiskReview(
            String riskLevel,
            String summary,
            List<PatchRiskFinding> findings
    ) {
    }

    public record PatchRiskFinding(
            String severity,
            String code,
            String message,
            String filePath
    ) {
    }
}
