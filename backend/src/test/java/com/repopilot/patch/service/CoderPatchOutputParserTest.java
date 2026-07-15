package com.repopilot.patch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repopilot.common.ApiException;
import org.junit.jupiter.api.Test;

class CoderPatchOutputParserTest {

    private final CoderPatchOutputParser parser = new CoderPatchOutputParser();

    @Test
    void parseAcceptsRawUnifiedDiff() {
        CoderPatchOutputParser.ParsedCoderPatch patch = parser.parse("""
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                +hello
                """);

        assertThat(patch.diffContent())
                .startsWith("diff --git a/README.md b/README.md\n")
                .endsWith("\n");
    }

    @Test
    void parseAcceptsSingleDiffFenceAndRemovesMarkdown() {
        CoderPatchOutputParser.ParsedCoderPatch patch = parser.parse("""
                ```diff
                diff --git a/pom.xml b/pom.xml
                --- a/pom.xml
                +++ b/pom.xml
                @@ -1 +1,2 @@
                +dependency
                ```
                """);

        assertThat(patch.diffContent())
                .startsWith("diff --git a/pom.xml b/pom.xml\n")
                .doesNotContain("```");
    }

    @Test
    void parseRejectsProseWrappedDiffWithoutFence() {
        assertInvalid("""
                Here is the patch:
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                +hello
                """);
    }

    @Test
    void parseRejectsProseOutsideFence() {
        assertInvalid("""
                Here is the patch:
                ```diff
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                +hello
                ```
                """);
    }

    @Test
    void parseRejectsMultipleDiffBlocks() {
        assertInvalid("""
                ```diff
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                +a
                ```
                ```diff
                diff --git a/b.txt b/b.txt
                --- a/b.txt
                +++ b/b.txt
                @@ -1 +1 @@
                +b
                ```
                """);
    }

    @Test
    void parseRejectsMissingDiffHeader() {
        assertInvalid("""
                @@ -1 +1 @@
                +hello
                """);
    }

    private void assertInvalid(String rawOutput) {
        assertThatThrownBy(() -> parser.parse(rawOutput))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getCode())
                .isEqualTo("CODER_PATCH_OUTPUT_INVALID");
    }
}
