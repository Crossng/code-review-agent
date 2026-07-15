package com.repopilot.agent.service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.repopilot.patch.domain.PatchRecord;
import org.springframework.stereotype.Service;

@Service
public class PatchDiffSafetyService {

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern OLD_PATH = Pattern.compile("^--- (?:a/(.+)|/dev/null)$");
    private static final Pattern NEW_PATH = Pattern.compile("^\\+\\+\\+ (?:b/(.+)|/dev/null)$");
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*");

    public PatchDiffSafetyReport review(PatchRecord patch) {
        Set<String> changedPaths = new LinkedHashSet<>();
        List<PatchDiffSafetyFinding> findings = new ArrayList<>();
        boolean sawDiffHeader = false;
        String[] lines = patch.getDiffContent().replace("\r\n", "\n").split("\n");
        for (String line : lines) {
            if (line.equals("GIT binary patch") || line.startsWith("Binary files ")) {
                findings.add(finding("BINARY_PATCH_UNSUPPORTED", "Binary patches are not allowed in the MVP sandbox flow.", null));
                continue;
            }
            Matcher diffHeader = DIFF_HEADER.matcher(line);
            if (diffHeader.matches()) {
                sawDiffHeader = true;
                collectPath(diffHeader.group(1), changedPaths, findings, "diff header old path");
                collectPath(diffHeader.group(2), changedPaths, findings, "diff header new path");
                continue;
            }
            Matcher oldPath = OLD_PATH.matcher(line);
            if (oldPath.matches()) {
                collectPath(oldPath.group(1), changedPaths, findings, "old file path");
                continue;
            }
            Matcher newPath = NEW_PATH.matcher(line);
            if (newPath.matches()) {
                collectPath(newPath.group(1), changedPaths, findings, "new file path");
            }
        }
        if (!sawDiffHeader) {
            findings.add(finding("DIFF_HEADER_MISSING", "Patch does not contain a unified diff file header.", null));
        }
        return new PatchDiffSafetyReport(findings.isEmpty(), List.copyOf(changedPaths), List.copyOf(findings));
    }

    private void collectPath(
            String rawPath,
            Set<String> changedPaths,
            List<PatchDiffSafetyFinding> findings,
            String source
    ) {
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        String path = rawPath.trim();
        if (path.contains("\\")) {
            findings.add(finding("UNSAFE_PATCH_PATH", "Patch " + source + " uses backslash separators.", path));
            return;
        }
        if (path.startsWith("/") || path.startsWith("~") || WINDOWS_DRIVE_PATH.matcher(path).matches()) {
            findings.add(finding("UNSAFE_PATCH_PATH", "Patch " + source + " must be project-relative.", path));
            return;
        }
        Path normalized;
        try {
            normalized = Path.of(path).normalize();
        } catch (InvalidPathException exception) {
            findings.add(finding("UNSAFE_PATCH_PATH", "Patch " + source + " is not a valid path.", path));
            return;
        }
        String normalizedPath = normalized.toString().replace('\\', '/');
        if (normalizedPath.isBlank() || normalizedPath.equals(".") || normalizedPath.startsWith("../") || normalizedPath.equals("..")) {
            findings.add(finding("UNSAFE_PATCH_PATH", "Patch " + source + " escapes the project workspace.", path));
            return;
        }
        if (hasReservedSegment(normalizedPath)) {
            findings.add(finding("RESERVED_PATCH_PATH", "Patch " + source + " touches a reserved repository or secret path.", path));
            return;
        }
        changedPaths.add(normalizedPath);
    }

    private boolean hasReservedSegment(String normalizedPath) {
        for (String segment : normalizedPath.split("/")) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (lower.equals(".git")
                    || lower.equals(".ssh")
                    || lower.equals(".aws")
                    || lower.equals(".gnupg")
                    || lower.equals(".env")
                    || lower.equals("id_rsa")
                    || lower.equals("id_dsa")
                    || lower.equals("id_ecdsa")
                    || lower.equals("id_ed25519")) {
                return true;
            }
        }
        return false;
    }

    private PatchDiffSafetyFinding finding(String code, String message, String path) {
        return new PatchDiffSafetyFinding(code, message, path);
    }

    public record PatchDiffSafetyReport(
            boolean safe,
            List<String> changedPaths,
            List<PatchDiffSafetyFinding> findings
    ) {
    }

    public record PatchDiffSafetyFinding(String code, String message, String path) {
    }
}
