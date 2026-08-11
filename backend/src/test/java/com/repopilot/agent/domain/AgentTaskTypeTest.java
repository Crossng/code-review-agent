package com.repopilot.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentTaskTypeTest {

    @Test
    void everyTaskTypeProvidesChinesePlanningSemantics() {
        assertThat(AgentTaskType.values())
                .extracting(AgentTaskType::displayName)
                .containsExactly("功能开发", "缺陷修复", "代码审查", "文档维护");

        assertThat(AgentTaskType.values()).allSatisfy(taskType -> {
            assertThat(taskType.analysisTitle()).isNotBlank();
            assertThat(taskType.analysisReason()).isNotBlank();
            assertThat(taskType.changeTitle()).isNotBlank();
            assertThat(taskType.changeReason()).isNotBlank();
            assertThat(taskType.validationStrategy()).isNotBlank();
        });
    }
}
