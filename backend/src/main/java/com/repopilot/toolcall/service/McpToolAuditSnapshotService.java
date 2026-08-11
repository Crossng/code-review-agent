package com.repopilot.toolcall.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.repopilot.settings.dto.McpToolSettingsCheckResponse;
import com.repopilot.settings.dto.McpToolSettingsResponse;
import com.repopilot.settings.dto.McpToolSummaryResponse;
import com.repopilot.settings.service.McpToolSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class McpToolAuditSnapshotService {

    private static final Duration SUCCESS_CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration FAILURE_CACHE_TTL = Duration.ofSeconds(5);
    private static final Map<String, String> TOOL_ALIASES = Map.of(
            "read_project_file", "read_file",
            "list_symbols", "get_class_structure"
    );

    private final McpToolSettingsService mcpToolSettingsService;
    private final boolean enabled;
    private volatile CachedSettings cachedSettings;

    public McpToolAuditSnapshotService(
            McpToolSettingsService mcpToolSettingsService,
            @Value("${repopilot.mcp-tool-server.audit-snapshot-enabled:true}") boolean enabled
    ) {
        this.mcpToolSettingsService = mcpToolSettingsService;
        this.enabled = enabled;
    }

    public Object snapshot(String toolName) {
        if (!enabled || toolName == null || toolName.isBlank()) {
            return null;
        }
        Instant capturedAt = Instant.now();
        McpToolSettingsResponse settings;
        try {
            settings = currentSettings(capturedAt);
        } catch (RuntimeException exception) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("provider", "REPOPILOT_MCP_TOOL_SERVER");
            snapshot.put("catalogReady", false);
            snapshot.put("catalogAvailable", false);
            snapshot.put("toolName", toolName);
            snapshot.put("normalizedToolName", normalizeToolName(toolName));
            snapshot.put("toolFound", false);
            snapshot.put("capturedAt", capturedAt);
            snapshot.put("reason", "MCP 工具契约快照生成失败：" + exception.getMessage());
            return snapshot;
        }
        String normalizedToolName = normalizeToolName(toolName);
        McpToolSummaryResponse tool = findTool(settings.tools(), normalizedToolName);
        boolean catalogAvailable = hasPassingCheck(settings.checks(), "MCP_CATALOG");

        Map<String, Object> snapshot = baseSnapshot(settings, toolName, normalizedToolName, capturedAt, catalogAvailable);
        snapshot.put("toolFound", tool != null);
        if (tool == null) {
            snapshot.put("reason", catalogAvailable
                    ? "MCP 工具目录中未找到该工具。"
                    : firstBlockedReason(settings.checks(), "MCP 工具目录暂不可用。"));
            return snapshot;
        }

        snapshot.put("title", tool.title());
        snapshot.put("category", tool.category());
        snapshot.put("accessMode", tool.accessMode());
        snapshot.put("mvp", tool.mvp());
        snapshot.put("auditRequired", tool.auditRequired());
        snapshot.put("approvalRequired", tool.approvalRequired());
        snapshot.put("backendBridge", tool.backendBridge());
        snapshot.put("arguments", tool.arguments());
        snapshot.put("safetyRules", tool.safetyRules());
        return snapshot;
    }

    private McpToolSettingsResponse currentSettings(Instant now) {
        CachedSettings current = cachedSettings;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.settings();
        }
        synchronized (this) {
            current = cachedSettings;
            if (current != null && now.isBefore(current.expiresAt())) {
                return current.settings();
            }
            McpToolSettingsResponse settings = mcpToolSettingsService.current();
            Duration ttl = settings.ready() ? SUCCESS_CACHE_TTL : FAILURE_CACHE_TTL;
            cachedSettings = new CachedSettings(settings, now.plus(ttl));
            return settings;
        }
    }

    private Map<String, Object> baseSnapshot(
            McpToolSettingsResponse settings,
            String toolName,
            String normalizedToolName,
            Instant capturedAt,
            boolean catalogAvailable
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("provider", settings.provider());
        snapshot.put("baseUrl", settings.baseUrl());
        snapshot.put("catalogReady", settings.ready());
        snapshot.put("catalogAvailable", catalogAvailable);
        snapshot.put("healthAvailable", settings.healthAvailable());
        snapshot.put("serviceName", settings.serviceName());
        snapshot.put("protocolVersion", settings.protocolVersion());
        snapshot.put("catalogToolCount", settings.toolCount());
        snapshot.put("toolName", toolName);
        snapshot.put("normalizedToolName", normalizedToolName);
        snapshot.put("capturedAt", capturedAt);
        return snapshot;
    }

    private McpToolSummaryResponse findTool(List<McpToolSummaryResponse> tools, String normalizedToolName) {
        return tools.stream()
                .filter(tool -> normalizedToolName.equals(normalizeToolName(tool.name())))
                .findFirst()
                .orElse(null);
    }

    private boolean hasPassingCheck(List<McpToolSettingsCheckResponse> checks, String code) {
        return checks.stream()
                .anyMatch(check -> code.equals(check.code()) && "PASS".equals(check.status()));
    }

    private String firstBlockedReason(List<McpToolSettingsCheckResponse> checks, String fallback) {
        return checks.stream()
                .filter(check -> "BLOCKED".equals(check.status()))
                .findFirst()
                .map(McpToolSettingsCheckResponse::message)
                .orElse(fallback);
    }

    private String normalizeToolName(String toolName) {
        String normalized = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        return TOOL_ALIASES.getOrDefault(normalized, normalized);
    }

    private record CachedSettings(McpToolSettingsResponse settings, Instant expiresAt) {
    }
}
