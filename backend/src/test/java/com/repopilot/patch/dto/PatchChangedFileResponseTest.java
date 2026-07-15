package com.repopilot.patch.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PatchChangedFileResponseTest {

    @Test
    void fromDiffSummarizesModifiedAddedAndDeletedFiles() {
        String diff = """
                diff --git a/src/UserService.java b/src/UserService.java
                index 1111111..2222222 100644
                --- a/src/UserService.java
                +++ b/src/UserService.java
                @@ -1,3 +1,4 @@
                 class UserService {
                -  void list() {}
                +  void listPage() {}
                +  void count() {}
                 }
                diff --git a/src/UserServiceTest.java b/src/UserServiceTest.java
                new file mode 100644
                index 0000000..3333333
                --- /dev/null
                +++ b/src/UserServiceTest.java
                @@ -0,0 +1,2 @@
                +class UserServiceTest {
                +}
                diff --git a/src/OldUser.java b/src/OldUser.java
                deleted file mode 100644
                index 4444444..0000000
                --- a/src/OldUser.java
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -class OldUser {
                -}
                """;

        List<PatchChangedFileResponse> changedFiles = PatchChangedFileResponse.fromDiff(diff);

        assertThat(changedFiles).hasSize(3);
        assertThat(changedFiles.get(0))
                .extracting(
                        PatchChangedFileResponse::path,
                        PatchChangedFileResponse::oldPath,
                        PatchChangedFileResponse::changeType,
                        PatchChangedFileResponse::addedLines,
                        PatchChangedFileResponse::deletedLines
                )
                .containsExactly("src/UserService.java", "src/UserService.java", "MODIFIED", 2, 1);
        assertThat(changedFiles.get(1))
                .extracting(
                        PatchChangedFileResponse::path,
                        PatchChangedFileResponse::oldPath,
                        PatchChangedFileResponse::changeType,
                        PatchChangedFileResponse::addedLines,
                        PatchChangedFileResponse::deletedLines
                )
                .containsExactly("src/UserServiceTest.java", null, "ADDED", 2, 0);
        assertThat(changedFiles.get(2))
                .extracting(
                        PatchChangedFileResponse::path,
                        PatchChangedFileResponse::oldPath,
                        PatchChangedFileResponse::changeType,
                        PatchChangedFileResponse::addedLines,
                        PatchChangedFileResponse::deletedLines
                )
                .containsExactly("src/OldUser.java", "src/OldUser.java", "DELETED", 0, 2);
    }
}
