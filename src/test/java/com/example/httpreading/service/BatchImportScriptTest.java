package com.example.httpreading.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchImportScriptTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesFilenameMetadataAndSkipsUnchangedFileOnSecondRun() throws Exception {
        Path books = Files.createDirectories(tempDir.resolve("books"));
        Files.writeString(
            books.resolve("乡土中国__费孝通__完结.epub"),
            "epub-content",
            StandardCharsets.UTF_8);

        Path fakeBin = Files.createDirectories(tempDir.resolve("bin"));
        Path callLog = tempDir.resolve("curl-calls.log");
        Path fakeCurl = fakeBin.resolve("curl");
        Files.writeString(fakeCurl, """
            #!/usr/bin/env bash
            printf '%s\n' "$*" >> "$CALL_LOG"
            if [[ "$*" == *"/api/admin/books/import-index/refresh"* ]]; then
              printf '%s\n' '{"hashes":{},"indexedCount":0,"missingSourceCount":0,"duplicateContentCount":0,"missingSourceBookIds":[]}'
            else
              printf '%s\n' '{"id":101,"title":"乡土中国","author":"费孝通","status":"完结","parseStatus":"SUCCESS","importDisposition":"IMPORTED"}'
            fi
            """, StandardCharsets.UTF_8);
        fakeCurl.toFile().setExecutable(true);

        ProcessResult first = runScript(books, fakeBin, callLog);
        ProcessResult second = runScript(books, fakeBin, callLog);

        assertThat(first.exitCode()).isZero();
        assertThat(first.output())
            .contains("title: 乡土中国")
            .contains("author: 费孝通")
            .contains("status: 完结")
            .contains("imported bookId: 101");
        assertThat(second.exitCode()).isZero();
        assertThat(second.output()).contains("UNCHANGED: 乡土中国__费孝通__完结.epub (bookId=101)");
        assertThat(Files.readString(books.resolve(".http-reading-import-state.tsv")))
            .contains("乡土中国__费孝通__完结.epub")
            .contains("\t101");

        long uploadCalls = Files.readAllLines(callLog).stream()
            .filter(line -> line.contains("/api/admin/books/import"))
            .filter(line -> !line.contains("/api/admin/books/import-index/refresh"))
            .count();
        assertThat(uploadCalls).isEqualTo(1);
    }

    private ProcessResult runScript(Path books, Path fakeBin, Path callLog) throws Exception {
        Path script = Path.of("scripts/import-books-batch.sh").toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder(
            "bash",
            script.toString(),
            "--dir",
            books.toString(),
            "--filename-metadata");
        builder.redirectErrorStream(true);
        builder.environment().put("CALL_LOG", callLog.toString());
        builder.environment().put(
            "PATH",
            fakeBin + System.getProperty("path.separator") + System.getenv("PATH"));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
