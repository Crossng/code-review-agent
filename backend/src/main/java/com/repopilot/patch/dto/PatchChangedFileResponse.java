package com.repopilot.patch.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PatchChangedFileResponse(
        String path,
        String oldPath,
        String changeType,
        int addedLines,
        int deletedLines
) {

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");

    public static List<PatchChangedFileResponse> fromDiff(String diffContent) {
        if (diffContent == null || diffContent.isBlank()) {
            return List.of();
        }
        List<PatchChangedFileResponse> files = new ArrayList<>();
        ChangedFileBuilder current = null;
        for (String line : diffContent.replace("\r\n", "\n").split("\n")) {
            Matcher matcher = DIFF_HEADER.matcher(line);
            if (matcher.matches()) {
                if (current != null) {
                    files.add(current.build());
                }
                current = new ChangedFileBuilder(matcher.group(1), matcher.group(2));
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("rename from ")) {
                current.oldPath = line.substring("rename from ".length());
                current.changeType = "RENAMED";
                continue;
            }
            if (line.startsWith("rename to ")) {
                current.path = line.substring("rename to ".length());
                current.changeType = "RENAMED";
                continue;
            }
            if (line.startsWith("--- ")) {
                current.oldPath = parseDiffPath(line.substring(4), current.oldPath);
                continue;
            }
            if (line.startsWith("+++ ")) {
                current.path = parseDiffPath(line.substring(4), current.path);
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                current.addedLines += 1;
                continue;
            }
            if (line.startsWith("-") && !line.startsWith("---")) {
                current.deletedLines += 1;
            }
        }
        if (current != null) {
            files.add(current.build());
        }
        return files;
    }

    private static String parseDiffPath(String rawPath, String fallback) {
        if ("/dev/null".equals(rawPath)) {
            return null;
        }
        if (rawPath.startsWith("a/") || rawPath.startsWith("b/")) {
            return rawPath.substring(2);
        }
        return rawPath.isBlank() ? fallback : rawPath;
    }

    private static final class ChangedFileBuilder {
        private String path;
        private String oldPath;
        private String changeType = "MODIFIED";
        private int addedLines;
        private int deletedLines;

        private ChangedFileBuilder(String oldPath, String path) {
            this.oldPath = oldPath;
            this.path = path;
        }

        private PatchChangedFileResponse build() {
            String resolvedChangeType = changeType;
            if ("MODIFIED".equals(resolvedChangeType)) {
                if (oldPath == null) {
                    resolvedChangeType = "ADDED";
                } else if (path == null) {
                    resolvedChangeType = "DELETED";
                }
            }
            String resolvedPath = path == null ? oldPath : path;
            return new PatchChangedFileResponse(resolvedPath, oldPath, resolvedChangeType, addedLines, deletedLines);
        }
    }
}
