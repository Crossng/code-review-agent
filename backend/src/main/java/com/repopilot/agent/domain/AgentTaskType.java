package com.repopilot.agent.domain;

public enum AgentTaskType {
    FEATURE(
            "功能开发",
            "梳理需求边界与现有调用链",
            "确认新增能力涉及的 Controller、Service、Mapper、Entity 和测试边界。",
            "实现最小功能闭环",
            "让接口、业务逻辑、持久层和测试按现有工程风格一起演进。",
            "覆盖正向、边界和失败场景，并在 Docker 沙箱运行 mvn -q test。"
    ),
    BUGFIX(
            "缺陷修复",
            "复现问题并定位根因",
            "优先检索失败入口、异常路径、相关实现和既有测试，避免只修表面症状。",
            "修复根因并补回归测试",
            "保持改动最小，同时用失败场景对应的测试锁定行为。",
            "先验证原失败场景，再覆盖相邻边界，并在 Docker 沙箱运行 mvn -q test。"
    ),
    REVIEW(
            "代码审查",
            "审查风险路径与测试缺口",
            "围绕鉴权、参数校验、SQL、兼容性和测试证据定位可复核风险。",
            "形成审查结论或最小修复补丁",
            "有明确证据时只修改必要位置，否则保留可审计的审查计划。",
            "逐项核对风险证据；若生成补丁，仍执行安全预检和 Docker Maven 测试。"
    ),
    DOC(
            "文档维护",
            "核对实现与文档事实",
            "沿接口、配置、命令和源码定位权威事实，避免文档与实现漂移。",
            "更新最小必要文档",
            "只修改与当前实现直接相关的说明、示例、链接和操作步骤。",
            "检查链接、命令和示例；仍在 Docker 沙箱运行 mvn -q test 防止夹带回归。"
    );

    private final String displayName;
    private final String analysisTitle;
    private final String analysisReason;
    private final String changeTitle;
    private final String changeReason;
    private final String validationStrategy;

    AgentTaskType(
            String displayName,
            String analysisTitle,
            String analysisReason,
            String changeTitle,
            String changeReason,
            String validationStrategy
    ) {
        this.displayName = displayName;
        this.analysisTitle = analysisTitle;
        this.analysisReason = analysisReason;
        this.changeTitle = changeTitle;
        this.changeReason = changeReason;
        this.validationStrategy = validationStrategy;
    }

    public String displayName() {
        return displayName;
    }

    public String analysisTitle() {
        return analysisTitle;
    }

    public String analysisReason() {
        return analysisReason;
    }

    public String changeTitle() {
        return changeTitle;
    }

    public String changeReason() {
        return changeReason;
    }

    public String validationStrategy() {
        return validationStrategy;
    }
}
