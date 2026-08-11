package com.repopilot.toolserver.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class McpToolRegistryServiceTest {

    private final McpToolRegistryService service = new McpToolRegistryService();

    @Test
    void listToolsIncludesMvpRepositoryAndPullRequestTools() {
        assertThat(service.listTools())
                .extracting(McpToolDefinition::name)
                .contains(
                        "list_project_files",
                        "read_file",
                        "search_code",
                        "validate_unified_diff",
                        "run_maven_test",
                        "create_pull_request"
                );
    }

    @Test
    void readFileValidationNormalizesSafeRelativePath() {
        ToolValidationResponse response = service.validate("read_file", Map.of(
                "runId", 7,
                "path", "src\\main\\java\\com\\example\\UserController.java"
        ));

        assertThat(response.valid()).isTrue();
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.normalizedArguments())
                .containsEntry("path", "src/main/java/com/example/UserController.java");
    }

    @Test
    void readFileValidationRejectsUnsafePath() {
        ToolValidationResponse response = service.validate("read_file", Map.of(
                "runId", 7,
                "path", "../secret.txt"
        ));

        assertThat(response.valid()).isFalse();
        assertThat(response.status()).isEqualTo("BLOCKED");
        assertThat(response.messages()).anySatisfy(message ->
                assertThat(message).contains("路径参数越界")
        );
    }

    @Test
    void readFileValidationRejectsParentSegmentInsideRelativePath() {
        ToolValidationResponse response = service.validate("read_file", Map.of(
                "runId", 7,
                "path", "src/main/../secret.txt"
        ));

        assertThat(response.valid()).isFalse();
        assertThat(response.status()).isEqualTo("BLOCKED");
        assertThat(response.messages()).anySatisfy(message ->
                assertThat(message).contains("路径参数越界")
        );
    }

    @Test
    void readFileValidationRejectsWindowsDrivePath() {
        ToolValidationResponse response = service.validate("read_file", Map.of(
                "runId", 7,
                "path", "C:\\Users\\crossng\\secret.txt"
        ));

        assertThat(response.valid()).isFalse();
        assertThat(response.status()).isEqualTo("BLOCKED");
        assertThat(response.messages()).anySatisfy(message ->
                assertThat(message).contains("路径参数越界")
        );
    }

    @Test
    void validationRejectsUnknownArgumentsAndWrongTypes() {
        ToolValidationResponse response = service.validate("search_code", Map.of(
                "runId", "7",
                "query", "User Controller",
                "limit", 1.5,
                "extra", "should not be accepted"
        ));

        assertThat(response.valid()).isFalse();
        assertThat(response.status()).isEqualTo("BLOCKED");
        assertThat(response.messages())
                .contains(
                        "参数 runId 必须是 number。",
                        "未知参数：extra",
                        "limit 必须是 1 到 20 之间的整数。"
                );
    }

    @Test
    void listProjectFilesValidationRejectsOutOfRangeMaxDepth() {
        ToolValidationResponse response = service.validate("list_project_files", Map.of(
                "runId", 7,
                "root", "src/main/java",
                "maxDepth", 11
        ));

        assertThat(response.valid()).isFalse();
        assertThat(response.status()).isEqualTo("BLOCKED");
        assertThat(response.messages()).contains("maxDepth 必须是 1 到 10 之间的整数。");
    }

    @Test
    void writeToolRequiresHumanApprovalFlag() {
        ToolValidationResponse response = service.validate("create_pull_request", Map.of(
                "taskId", 19,
                "title", "RepoPilot：新增接口",
                "body", "由 RepoPilot 准备。"
        ));

        assertThat(response.valid()).isFalse();
        assertThat(response.status()).isEqualTo("NEEDS_HUMAN_APPROVAL");
        assertThat(response.approvalRequired()).isTrue();
    }
}
