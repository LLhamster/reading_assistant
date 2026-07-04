package com.example.httpreading.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.httpreading.api.ErrorCode;

@Service
public class DocumentStorageService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");
    private final Path storageRoot;

    public DocumentStorageService(@Value("${http-reading.storage.root:${user.home}/.http-reading}") String storageRoot) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException e) {
            ErrorCode.INTERNAL_ERROR.throwException("文件存储目录初始化失败: " + this.storageRoot);
        }
    }

    public String saveSourceFile(Long bookId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            ErrorCode.BAD_REQUEST.throwException("上传文件不能为空");
        }
        String extension = extension(file.getOriginalFilename());
        String relativePath = "books/" + bookId + "/source/original" + extension;
        Path target = resolveRelativePath(relativePath);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            ErrorCode.INTERNAL_ERROR.throwException("原始书籍文件保存失败");
        }
        return relativePath;
    }

    public String stageReplacementSourceFile(Long bookId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ErrorCode.BAD_REQUEST.toException("替换文件不能为空");
        }
        String extension = extension(file.getOriginalFilename());
        String relativePath = "books/" + bookId + "/source/replacement-" + UUID.randomUUID() + extension;
        Path target = resolveRelativePath(relativePath);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return relativePath;
        } catch (IOException e) {
            throw ErrorCode.INTERNAL_ERROR.toException("替换书籍临时文件保存失败");
        }
    }

    public String commitReplacementSourceFile(Long bookId,
                                              String stagedRelativePath,
                                              String originalFilename) {
        Path sourceDirectory = resolveRelativePath("books/" + bookId + "/source");
        Path staged = resolveRelativePath(stagedRelativePath);
        if (!staged.startsWith(sourceDirectory)
            || !staged.getFileName().toString().startsWith("replacement-")) {
            throw ErrorCode.BAD_REQUEST.toException("非法替换书籍临时路径");
        }
        String targetRelativePath = "books/" + bookId + "/source/original" + extension(originalFilename);
        Path target = resolveRelativePath(targetRelativePath);
        try {
            Files.createDirectories(sourceDirectory);
            moveFileReplacing(staged, target);
            try (var sourceFiles = Files.list(sourceDirectory)) {
                for (Path path : sourceFiles.toList()) {
                    if (!path.equals(target) && path.getFileName().toString().startsWith("original.")) {
                        Files.deleteIfExists(path);
                    }
                }
            }
            return targetRelativePath;
        } catch (IOException e) {
            throw ErrorCode.INTERNAL_ERROR.toException("替换书籍原始文件提交失败");
        }
    }

    public void deleteStagedSourceFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path path = resolveRelativePath(relativePath);
        if (!path.getFileName().toString().startsWith("replacement-")) {
            throw ErrorCode.BAD_REQUEST.toException("非法替换书籍临时路径");
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw ErrorCode.INTERNAL_ERROR.toException("替换书籍临时文件清理失败");
        }
    }

    public String sha256(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ErrorCode.BAD_REQUEST.toException("上传文件不能为空");
        }
        try (InputStream input = file.getInputStream()) {
            return digestSha256(input);
        } catch (IOException e) {
            throw ErrorCode.BAD_REQUEST.toException("无法读取上传文件");
        }
    }

    public String sha256(String relativePath) {
        Path path = resolveRelativePath(relativePath);
        if (!Files.isRegularFile(path)) {
            throw ErrorCode.RESOURCE_NOT_FOUND.toException("原始书籍文件不存在: " + relativePath);
        }
        try (InputStream input = Files.newInputStream(path)) {
            return digestSha256(input);
        } catch (IOException e) {
            throw ErrorCode.INTERNAL_ERROR.toException("书籍文件哈希计算失败: " + relativePath);
        }
    }

    private String digestSha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    public String writeChapterText(Long bookId, Integer chapterIndex, String content) {
        String relativePath = "books/" + bookId + "/chapters/" + chapterIndex + ".txt";
        Path target = resolveRelativePath(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ErrorCode.INTERNAL_ERROR.throwException("章节文本文件保存失败: " + chapterIndex);
        }
        return relativePath;
    }

    public String writeChapterHtml(Long bookId, Integer chapterIndex, String contentHtml) {
        if (contentHtml == null || contentHtml.isBlank()) {
            return null;
        }
        String relativePath = "books/" + bookId + "/chapters/" + chapterIndex + ".html";
        Path target = resolveRelativePath(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, contentHtml, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            ErrorCode.INTERNAL_ERROR.throwException("章节 HTML 文件保存失败: " + chapterIndex);
        }
        return relativePath;
    }

    public void replaceBookAssets(Long bookId, Map<String, byte[]> assets) {
        Path bookRoot = resolveRelativePath("books/" + bookId);
        Path target = bookRoot.resolve("assets").normalize();
        Path staging = bookRoot.resolve("assets-staging-" + UUID.randomUUID()).normalize();
        Path backup = bookRoot.resolve("assets-backup-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(staging);
            for (Map.Entry<String, byte[]> asset : assets.entrySet()) {
                Path relative = safeAssetRelativePath(asset.getKey());
                Path output = staging.resolve(relative).normalize();
                if (!output.startsWith(staging)) {
                    throw new IOException("非法 EPUB 图片路径");
                }
                Files.createDirectories(output.getParent());
                Files.write(output, asset.getValue(), StandardOpenOption.CREATE_NEW);
            }
            Files.createDirectories(bookRoot);
            if (Files.exists(target)) {
                moveDirectory(target, backup);
            }
            try {
                moveDirectory(staging, target);
            } catch (IOException moveError) {
                if (Files.exists(backup) && !Files.exists(target)) {
                    moveDirectory(backup, target);
                }
                throw moveError;
            }
            deleteTree(backup);
        } catch (IOException e) {
            try {
                deleteTree(staging);
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
            ErrorCode.INTERNAL_ERROR.throwException("EPUB 图片资源保存失败");
        }
    }

    public String readText(String relativePath) {
        Path path = resolveRelativePath(relativePath);
        if (!Files.exists(path)) {
            ErrorCode.RESOURCE_NOT_FOUND.throwException("章节文本文件不存在: " + relativePath);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ErrorCode.INTERNAL_ERROR.throwException("章节文本文件读取失败: " + relativePath);
            return "";
        }
    }

    public StoredAsset readBookAsset(Long bookId, String assetPath) {
        Path relative = safeAssetRelativePath(assetPath);
        Path assetRoot = resolveRelativePath("books/" + bookId + "/assets");
        Path resolved = assetRoot.resolve(relative).normalize();
        if (!resolved.startsWith(assetRoot)) {
            ErrorCode.BAD_REQUEST.throwException("非法图片路径");
        }
        if (!Files.isRegularFile(resolved)) {
            ErrorCode.RESOURCE_NOT_FOUND.throwException("图片资源不存在");
        }
        return new StoredAsset(resolved, imageMediaType(resolved.getFileName().toString()));
    }

    public void deleteBookFiles(Long bookId) {
        if (bookId == null || bookId <= 0) {
            throw ErrorCode.BAD_REQUEST.toException("书籍 ID 不合法");
        }
        Path bookRoot = resolveRelativePath("books/" + bookId);
        try {
            deleteTree(bookRoot);
        } catch (IOException e) {
            throw ErrorCode.INTERNAL_ERROR.toException("书籍文件目录删除失败: " + bookId);
        }
    }

    private Path safeAssetRelativePath(String assetPath) {
        if (assetPath == null || assetPath.isBlank()) {
            throw ErrorCode.BAD_REQUEST.toException("图片路径不能为空");
        }
        String normalizedSeparators = assetPath.replace('\\', '/');
        Path relative;
        try {
            relative = Path.of(normalizedSeparators).normalize();
        } catch (RuntimeException e) {
            throw ErrorCode.BAD_REQUEST.toException("非法图片路径");
        }
        if (relative.isAbsolute() || relative.startsWith("..") || !hasImageExtension(relative.toString())) {
            throw ErrorCode.BAD_REQUEST.toException("非法图片路径");
        }
        return relative;
    }

    private boolean hasImageExtension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String imageMediaType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        throw ErrorCode.BAD_REQUEST.toException("不支持的图片格式");
    }

    private void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void moveFileReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path resolveRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            ErrorCode.BAD_REQUEST.throwException("文件路径不能为空");
        }
        Path input = Path.of(relativePath);
        if (input.isAbsolute()) {
            ErrorCode.BAD_REQUEST.throwException("文件路径必须是相对路径");
        }
        Path resolved = storageRoot.resolve(input).normalize();
        if (!resolved.startsWith(storageRoot)) {
            ErrorCode.BAD_REQUEST.throwException("非法文件路径: " + relativePath);
        }
        return resolved;
    }

    public String extension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String cleanName = Path.of(filename).getFileName().toString();
        int dot = cleanName.lastIndexOf('.');
        if (dot < 0 || dot == cleanName.length() - 1) {
            return "";
        }
        return cleanName.substring(dot).toLowerCase(Locale.ROOT);
    }

    public String format(String filename) {
        String extension = extension(filename);
        return extension.isBlank() ? "" : extension.substring(1);
    }

    public Path getStorageRoot() {
        return storageRoot;
    }

    public record StoredAsset(Path path, String mediaType) {
    }
}
