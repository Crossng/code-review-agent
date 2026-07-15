package com.repopilot.patch.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.repopilot.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CoderPatchOutputParser {

    private static final Pattern FENCED_DIFF = Pattern.compile("(?s)```(?:diff|patch)\\s*\\n(.*?)\\n```");

    public ParsedCoderPatch parse(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw invalid("Coder output is empty");
        }
        String normalized = rawOutput.replace("\r\n", "\n").trim();
        String diff = extractDiff(normalized);
        if (!diff.startsWith("diff --git ")) {
            throw invalid("Coder output must start with a unified diff header");
        }
        if (diff.contains("```")) {
            throw invalid("Coder diff must not contain Markdown fences");
        }
        return new ParsedCoderPatch(ensureTrailingNewline(diff));
    }

    private String extractDiff(String output) {
        Matcher matcher = FENCED_DIFF.matcher(output);
        String extracted = null;
        int matchCount = 0;
        while (matcher.find()) {
            matchCount++;
            extracted = matcher.group(1).trim();
        }
        if (matchCount > 1) {
            throw invalid("Coder output must contain at most one diff code block");
        }
        if (matchCount == 1) {
            String withoutBlock = matcher.replaceAll("").trim();
            if (!withoutBlock.isBlank()) {
                throw invalid("Coder output must not include prose outside the diff block");
            }
            return extracted;
        }
        if (!output.startsWith("diff --git ")) {
            throw invalid("Coder output must be a raw diff or one diff code block");
        }
        return output;
    }

    private String ensureTrailingNewline(String diff) {
        return diff.endsWith("\n") ? diff : diff + "\n";
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CODER_PATCH_OUTPUT_INVALID", message);
    }

    public record ParsedCoderPatch(String diffContent) {
    }
}
