package com.example.httpreading.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.httpreading.api.ErrorCode;

@Service
public class DocumentStorageService {

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
}
