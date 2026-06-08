package com.example.httpreading.service;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import com.example.httpreading.api.ErrorCode;

@Service
public class DocumentParseService {

    private static final Pattern CHAPTER_TITLE = Pattern.compile(
        "^\\s*(第[0-9零一二三四五六七八九十百千万]+[章节回篇卷][^\\n]{0,80}|Chapter\\s+[0-9IVXLCDM]+[^\\n]{0,80}|[0-9]{1,3}[.、]\\s*\\S.{0,80})\\s*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTLINE_ARTICLE_TITLE = Pattern.compile(
        "^\\s*([0-9]{1,3}[、.．]\\s*.+|第[0-9零一二三四五六七八九十百千万]+[章篇回节].+|Chapter\\s+[0-9IVXLCDM]+.+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLUME_TITLE = Pattern.compile(
        "^\\s*(第\\s*([0-9零一二三四五六七八九十百千万]+)\\s*卷|卷\\s*([0-9零一二三四五六七八九十百千万]+))\\s*$");
    private static final Pattern TITLE_TAG = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern H1_TAG = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EPUB_ROOTFILE = Pattern.compile("<rootfile\\b[^>]*full-path\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPUB_ITEM = Pattern.compile("<item\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EPUB_ITEMREF = Pattern.compile("<itemref\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern XML_ATTR = Pattern.compile("([\\w:.-]+)\\s*=\\s*[\"']([^\"']*)[\"']");

    public ParsedBook parse(Path sourcePath, String fallbackTitle, String fallbackAuthor) {
        String filename = sourcePath.getFileName().toString();
        String extension = extension(filename);
        List<ParsedChapter> chapters = switch (extension) {
            case "txt", "md" -> splitText(readUtf8(sourcePath));
            case "html", "htm" -> splitText(stripHtml(readUtf8(sourcePath)));
            case "epub" -> parseEpub(sourcePath);
            case "pdf" -> parsePdf(sourcePath);
            default -> throw ErrorCode.BAD_REQUEST.toException("暂不支持的书籍格式: " + extension);
        };
        chapters = normalizeChapters(chapters);
        if (chapters.isEmpty()) {
            throw ErrorCode.BAD_REQUEST.toException("未能从文档中抽取到可阅读文本，扫描型 PDF 需要先 OCR");
        }
        String title = isBlank(fallbackTitle) ? filenameWithoutExtension(filename) : fallbackTitle.trim();
        String author = isBlank(fallbackAuthor) ? "未知作者" : fallbackAuthor.trim();
        return new ParsedBook(title, author, chapters);
    }

    private List<ParsedChapter> parseEpub(Path sourcePath) {
        List<ParsedChapter> chapters = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(sourcePath.toFile(), StandardCharsets.UTF_8)) {
            List<ZipEntry> entries = epubReadingOrderEntries(zipFile);

            for (ZipEntry entry : entries) {
                String html = readZipText(zipFile, entry);
                String text = stripHtml(html).trim();
                if (text.isBlank()) {
                    continue;
                }
                String title = firstTagText(html, H1_TAG);
                if (isBlank(title)) {
                    title = firstTagText(html, TITLE_TAG);
                }
                if (isBlank(title)) {
                    title = Path.of(entry.getName()).getFileName().toString();
                }
                chapters.add(new ParsedChapter(title.trim(), text));
            }
        } catch (IOException e) {
            throw ErrorCode.BAD_REQUEST.toException("EPUB 解析失败: " + e.getMessage());
        }
        if (chapters.size() == 1) {
            return splitText(chapters.get(0).content());
        }
        return chapters;
    }

    private List<ZipEntry> epubReadingOrderEntries(ZipFile zipFile) throws IOException {
        List<ZipEntry> spineEntries = epubSpineEntries(zipFile);
        if (!spineEntries.isEmpty()) {
            return spineEntries;
        }
        return zipFile.stream()
            .filter(entry -> !entry.isDirectory())
            .filter(this::isEpubHtmlEntry)
            .map(ZipEntry.class::cast)
            .sorted(Comparator.comparing(ZipEntry::getName))
            .toList();
    }

    private List<ZipEntry> epubSpineEntries(ZipFile zipFile) throws IOException {
        ZipEntry containerEntry = zipFile.getEntry("META-INF/container.xml");
        if (containerEntry == null) {
            return List.of();
        }
        String containerXml = readZipText(zipFile, containerEntry);
        Matcher rootfileMatcher = EPUB_ROOTFILE.matcher(containerXml);
        if (!rootfileMatcher.find()) {
            return List.of();
        }

        String opfPath = normalizeZipPath(rootfileMatcher.group(1));
        ZipEntry opfEntry = zipFile.getEntry(opfPath);
        if (opfEntry == null) {
            return List.of();
        }
        String opfXml = readZipText(zipFile, opfEntry);
        String opfBasePath = parentZipPath(opfPath);
        Map<String, String> manifest = epubManifest(opfXml, opfBasePath);
        List<String> spineIds = epubSpineIds(opfXml);

        List<ZipEntry> entries = new ArrayList<>();
        for (String idref : spineIds) {
            String href = manifest.get(idref);
            if (href == null) {
                continue;
            }
            ZipEntry entry = zipFile.getEntry(href);
            if (entry != null && isEpubHtmlEntry(entry)) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private Map<String, String> epubManifest(String opfXml, String opfBasePath) {
        Map<String, String> manifest = new LinkedHashMap<>();
        Matcher matcher = EPUB_ITEM.matcher(opfXml == null ? "" : opfXml);
        while (matcher.find()) {
            Map<String, String> attrs = xmlAttrs(matcher.group(1));
            String id = attrs.get("id");
            String href = attrs.get("href");
            if (isBlank(id) || isBlank(href)) {
                continue;
            }
            String mediaType = attrs.getOrDefault("media-type", "");
            String lowerHref = href.toLowerCase(Locale.ROOT);
            if (mediaType.contains("xhtml") || lowerHref.endsWith(".xhtml")
                || lowerHref.endsWith(".html") || lowerHref.endsWith(".htm")) {
                manifest.put(id, resolveZipPath(opfBasePath, href));
            }
        }
        return manifest;
    }

    private List<String> epubSpineIds(String opfXml) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = EPUB_ITEMREF.matcher(opfXml == null ? "" : opfXml);
        while (matcher.find()) {
            Map<String, String> attrs = xmlAttrs(matcher.group(1));
            String idref = attrs.get("idref");
            if (!isBlank(idref)) {
                ids.add(idref);
            }
        }
        return ids;
    }

    private Map<String, String> xmlAttrs(String rawAttrs) {
        Map<String, String> attrs = new HashMap<>();
        Matcher matcher = XML_ATTR.matcher(rawAttrs == null ? "" : rawAttrs);
        while (matcher.find()) {
            attrs.put(matcher.group(1), matcher.group(2));
        }
        return attrs;
    }

    private boolean isEpubHtmlEntry(ZipEntry entry) {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm");
    }

    private String readZipText(ZipFile zipFile, ZipEntry entry) throws IOException {
        return new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
    }

    private String parentZipPath(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    private String resolveZipPath(String basePath, String href) {
        String decodedHref = URLDecoder.decode(href, StandardCharsets.UTF_8);
        return normalizeZipPath((basePath == null ? "" : basePath) + decodedHref);
    }

    private String normalizeZipPath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isBlank() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    private List<ParsedChapter> parsePdf(Path sourcePath) {
        try (PDDocument document = Loader.loadPDF(sourcePath.toFile())) {
            List<PdfOutlineEntry> outlineEntries = pdfOutlineEntries(document);
            if (!outlineEntries.isEmpty()) {
                return chaptersFromOutline(document, outlineEntries);
            }

            String text = extractPdfText(document, 1, document.getNumberOfPages());
            if (text == null || text.trim().length() < 20) {
                throw ErrorCode.BAD_REQUEST.toException("PDF 未抽取到足够文本，可能是扫描件，需要 OCR");
            }
            return splitText(text);
        } catch (IOException e) {
            throw ErrorCode.BAD_REQUEST.toException("PDF 解析失败: " + e.getMessage());
        }
    }

    private List<PdfOutlineEntry> pdfOutlineEntries(PDDocument document) throws IOException {
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null || !outline.hasChildren()) {
            return List.of();
        }

        List<PdfOutlineEntry> entries = new ArrayList<>();
        collectOutlineEntries(document, outline.children(), null, entries);
        return entries.stream()
            .filter(entry -> entry.pageIndex() >= 0)
            .filter(entry -> isArticleOutlineTitle(entry.title()))
            .sorted(Comparator.comparingInt(PdfOutlineEntry::pageIndex))
            .toList();
    }

    private void collectOutlineEntries(PDDocument document,
                                       Iterable<PDOutlineItem> items,
                                       VolumeRef currentVolume,
                                       List<PdfOutlineEntry> entries) throws IOException {
        for (PDOutlineItem item : items) {
            int pageIndex = document.getPages().indexOf(item.findDestinationPage(document));
            String title = cleanOutlineTitle(item.getTitle());
            VolumeRef nextVolume = currentVolume;
            if (isVolumeTitle(title)) {
                nextVolume = new VolumeRef(parseVolumeIndex(title), title);
            } else if (!title.isBlank() && isArticleOutlineTitle(title)) {
                entries.add(new PdfOutlineEntry(
                    title,
                    pageIndex,
                    nextVolume == null ? null : nextVolume.volumeIndex(),
                    nextVolume == null ? null : nextVolume.volumeTitle()));
            }
            if (item.hasChildren()) {
                collectOutlineEntries(document, item.children(), nextVolume, entries);
            }
        }
    }

    private List<ParsedChapter> chaptersFromOutline(PDDocument document, List<PdfOutlineEntry> entries) throws IOException {
        List<ParsedChapter> chapters = new ArrayList<>();
        int totalPages = document.getNumberOfPages();
        for (int i = 0; i < entries.size(); i++) {
            PdfOutlineEntry entry = entries.get(i);
            int startPage = entry.pageIndex() + 1;
            int endPage = i + 1 < entries.size()
                ? Math.max(startPage, entries.get(i + 1).pageIndex())
                : totalPages;
            String text = extractPdfText(document, startPage, endPage);
            text = trimBeforeTitle(text, entry.title());
            text = normalizeText(text);
            if (!text.isBlank()) {
                chapters.add(new ParsedChapter(entry.volumeIndex(), entry.volumeTitle(), entry.title(), text));
            }
        }
        return chapters;
    }

    private String extractPdfText(PDDocument document, int startPage, int endPage) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(Math.max(1, startPage));
        stripper.setEndPage(Math.max(startPage, endPage));
        stripper.setLineSeparator("\n");
        return stripper.getText(document);
    }

    private List<ParsedChapter> splitText(String rawText) {
        String text = normalizeText(rawText);
        if (text.isBlank()) {
            return List.of();
        }

        List<ParsedChapter> chapters = new ArrayList<>();
        String currentTitle = null;
        StringBuilder currentContent = new StringBuilder();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (isChapterTitle(trimmed)) {
                if (currentTitle != null || !currentContent.toString().isBlank()) {
                    addChapter(chapters, currentTitle, currentContent.toString());
                }
                currentTitle = trimmed;
                currentContent.setLength(0);
            } else {
                currentContent.append(line).append('\n');
            }
        }
        addChapter(chapters, currentTitle, currentContent.toString());
        return chapters;
    }

    private List<ParsedChapter> normalizeChapters(List<ParsedChapter> chapters) {
        List<ParsedChapter> normalized = new ArrayList<>();
        for (ParsedChapter chapter : chapters) {
            String content = normalizeText(chapter.content());
            if (content.isBlank()) {
                continue;
            }
            String title = isBlank(chapter.title()) ? "全文" : chapter.title().trim();
            normalized.add(new ParsedChapter(chapter.volumeIndex(), chapter.volumeTitle(), title, content));
        }
        if (normalized.size() == 1 && "全文".equals(normalized.get(0).title())) {
            return normalized;
        }
        for (int i = 0; i < normalized.size(); i++) {
            ParsedChapter chapter = normalized.get(i);
            if ("全文".equals(chapter.title())) {
                normalized.set(i, new ParsedChapter(chapter.volumeIndex(), chapter.volumeTitle(), "第" + (i + 1) + "章", chapter.content()));
            }
        }
        return normalized;
    }

    private void addChapter(List<ParsedChapter> chapters, String title, String content) {
        String normalizedContent = normalizeText(content);
        if (normalizedContent.isBlank()) {
            return;
        }
        chapters.add(new ParsedChapter(isBlank(title) ? "全文" : title, normalizedContent));
    }

    private boolean isChapterTitle(String line) {
        if (line == null || line.length() > 90 || line.length() < 2) {
            return false;
        }
        return CHAPTER_TITLE.matcher(line).matches();
    }

    private String readUtf8(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ErrorCode.BAD_REQUEST.toException("文档读取失败: " + e.getMessage());
        }
    }

    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</p\\s*>", "\n")
            .replaceAll("(?i)</h[1-6]\\s*>", "\n")
            .replaceAll("(?s)<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
    }

    private String firstTagText(String html, Pattern pattern) {
        Matcher matcher = pattern.matcher(html == null ? "" : html);
        if (!matcher.find()) {
            return "";
        }
        return stripHtml(matcher.group(1)).trim();
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replaceAll("[ \\t\\x0B\\f]+", " ");
        normalized = mergeHardLineBreaks(normalized);
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private String mergeHardLineBreaks(String text) {
        StringBuilder result = new StringBuilder();
        String[] blocks = text.split("\\n\\s*\\n");
        for (String block : blocks) {
            String merged = mergeParagraphBlock(block);
            if (!merged.isBlank()) {
                if (!result.isEmpty()) {
                    result.append("\n\n");
                }
                result.append(merged);
            }
        }
        return result.toString();
    }

    private String mergeParagraphBlock(String block) {
        StringBuilder merged = new StringBuilder();
        for (String line : block.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (merged.isEmpty()) {
                merged.append(trimmed);
                continue;
            }
            if (shouldKeepLineBreak(merged.toString(), trimmed)) {
                merged.append('\n').append(trimmed);
            } else if (shouldJoinWithoutSpace(merged.toString(), trimmed)) {
                merged.append(trimmed);
            } else {
                merged.append(' ').append(trimmed);
            }
        }
        return merged.toString();
    }

    private boolean shouldKeepLineBreak(String current, String next) {
        String lastLine = current.substring(current.lastIndexOf('\n') + 1).trim();
        if (isChapterTitle(next) || isArticleOutlineTitle(next)) {
            return true;
        }
        if (lastLine.endsWith("。") || lastLine.endsWith("！") || lastLine.endsWith("？")
            || lastLine.endsWith("；") || lastLine.endsWith(":") || lastLine.endsWith("：")) {
            return true;
        }
        return next.matches("^([（(]?[一二三四五六七八九十0-9]+[）).、].*|[-*•].*)$");
    }

    private boolean shouldJoinWithoutSpace(String current, String next) {
        char last = lastNonWhitespaceChar(current);
        char first = firstNonWhitespaceChar(next);
        if (last == 0 || first == 0) {
            return false;
        }
        return isCjk(last) || isCjk(first) || "，。、；：！？）】》」』".indexOf(first) >= 0;
    }

    private char lastNonWhitespaceChar(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) {
                return ch;
            }
        }
        return 0;
    }

    private char firstNonWhitespaceChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) {
                return ch;
            }
        }
        return 0;
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private boolean isArticleOutlineTitle(String title) {
        if (title == null) {
            return false;
        }
        String cleanTitle = cleanOutlineTitle(title);
        if (cleanTitle.length() < 3) {
            return false;
        }
        return OUTLINE_ARTICLE_TITLE.matcher(cleanTitle).find();
    }

    private boolean isVolumeTitle(String title) {
        if (title == null) {
            return false;
        }
        return VOLUME_TITLE.matcher(cleanOutlineTitle(title)).matches();
    }

    private Integer parseVolumeIndex(String title) {
        Matcher matcher = VOLUME_TITLE.matcher(cleanOutlineTitle(title));
        if (!matcher.matches()) {
            return null;
        }
        String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        return parseChineseOrArabicNumber(value);
    }

    private Integer parseChineseOrArabicNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleanValue = value.trim();
        if (cleanValue.matches("\\d+")) {
            return Integer.parseInt(cleanValue);
        }
        int result = 0;
        int section = 0;
        for (int i = 0; i < cleanValue.length(); i++) {
            char ch = cleanValue.charAt(i);
            int digit = switch (ch) {
                case '零' -> 0;
                case '一' -> 1;
                case '二', '两' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> -1;
            };
            if (digit >= 0) {
                section = digit;
                continue;
            }
            if (ch == '十') {
                result += section == 0 ? 10 : section * 10;
                section = 0;
            } else if (ch == '百') {
                result += section == 0 ? 100 : section * 100;
                section = 0;
            } else if (ch == '千') {
                result += section == 0 ? 1000 : section * 1000;
                section = 0;
            }
        }
        return result + section == 0 ? null : result + section;
    }

    private String cleanOutlineTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.trim()
            .replaceFirst("^\\s*([0-9]{1,3})[.．]\\s*", "$1、")
            .replaceAll("\\s+", " ");
    }

    private String trimBeforeTitle(String text, String title) {
        String normalized = text == null ? "" : text;
        String cleanTitle = cleanOutlineTitle(title);
        if (cleanTitle.isBlank()) {
            return normalized;
        }
        int index = normalized.indexOf(cleanTitle);
        if (index < 0 && cleanTitle.contains("、")) {
            String alternate = cleanTitle.replaceFirst("^([0-9]{1,3})、", "$1.");
            index = normalized.indexOf(alternate);
        }
        if (index <= 0) {
            return normalized;
        }
        return normalized.substring(index);
    }

    private String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String filenameWithoutExtension(String filename) {
        String clean = filename == null || filename.isBlank() ? "未命名书籍" : filename;
        int dot = clean.lastIndexOf('.');
        return dot > 0 ? clean.substring(0, dot) : clean;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ParsedBook(String title, String author, List<ParsedChapter> chapters) {
    }

    public record ParsedChapter(Integer volumeIndex, String volumeTitle, String title, String content) {
        public ParsedChapter(String title, String content) {
            this(null, null, title, content);
        }
    }

    private record PdfOutlineEntry(String title, int pageIndex, Integer volumeIndex, String volumeTitle) {
    }

    private record VolumeRef(Integer volumeIndex, String volumeTitle) {
    }
}
