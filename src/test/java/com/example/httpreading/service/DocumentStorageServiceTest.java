package com.example.httpreading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.example.httpreading.api.BusinessException;

class DocumentStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void replacesAndReadsOnlyAssetsInsideTheBookDirectory() throws Exception {
        DocumentStorageService service = new DocumentStorageService(tempDir.toString());
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };

        service.replaceBookAssets(1L, Map.of("OPS/images/test.png", png));
        DocumentStorageService.StoredAsset stored = service.readBookAsset(1L, "OPS/images/test.png");

        assertThat(stored.mediaType()).isEqualTo("image/png");
        assertThat(Files.readAllBytes(stored.path())).isEqualTo(png);
        assertThatThrownBy(() -> service.readBookAsset(2L, "OPS/images/test.png"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.readBookAsset(1L, "../source/original.epub"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.readBookAsset(1L, "OPS/chapter.xhtml"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void replacingAssetsRemovesStaleFiles() {
        DocumentStorageService service = new DocumentStorageService(tempDir.toString());

        service.replaceBookAssets(1L, Map.of("old.png", new byte[] {1}));
        service.replaceBookAssets(1L, Map.of("new.jpg", new byte[] {2}));

        assertThatThrownBy(() -> service.readBookAsset(1L, "old.png"))
            .isInstanceOf(BusinessException.class);
        assertThat(service.readBookAsset(1L, "new.jpg").mediaType()).isEqualTo("image/jpeg");
    }

    @Test
    void calculatesTheSameSha256ForUploadAndStoredSource() throws Exception {
        DocumentStorageService service = new DocumentStorageService(tempDir.toString());
        byte[] content = "same book content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile upload = new MockMultipartFile(
            "file", "book.epub", "application/epub+zip", content);
        Path stored = tempDir.resolve("books/1/source/original.epub");
        Files.createDirectories(stored.getParent());
        Files.write(stored, content);

        assertThat(service.sha256(upload))
            .isEqualTo(service.sha256("books/1/source/original.epub"))
            .hasSize(64);
    }

    @Test
    void stagesAndCommitsReplacementSourceAfterValidation() throws Exception {
        DocumentStorageService service = new DocumentStorageService(tempDir.toString());
        Path oldSource = tempDir.resolve("books/1/source/original.epub");
        Files.createDirectories(oldSource.getParent());
        Files.writeString(oldSource, "old");
        MockMultipartFile replacement = new MockMultipartFile(
            "file", "replacement.pdf", "application/pdf", "new".getBytes());

        String staged = service.stageReplacementSourceFile(1L, replacement);
        assertThat(Files.readString(service.resolveRelativePath(staged))).isEqualTo("new");

        String committed = service.commitReplacementSourceFile(1L, staged, replacement.getOriginalFilename());

        assertThat(committed).isEqualTo("books/1/source/original.pdf");
        assertThat(Files.readString(service.resolveRelativePath(committed))).isEqualTo("new");
        assertThat(Files.exists(oldSource)).isFalse();
    }

    @Test
    void deletesOnlyTheRequestedBookDirectory() throws Exception {
        DocumentStorageService service = new DocumentStorageService(tempDir.toString());
        Path firstBook = tempDir.resolve("books/1/source/original.epub");
        Path secondBook = tempDir.resolve("books/2/source/original.epub");
        Files.createDirectories(firstBook.getParent());
        Files.createDirectories(secondBook.getParent());
        Files.writeString(firstBook, "first");
        Files.writeString(secondBook, "second");

        service.deleteBookFiles(1L);

        assertThat(Files.exists(tempDir.resolve("books/1"))).isFalse();
        assertThat(Files.readString(secondBook)).isEqualTo("second");
    }
}
