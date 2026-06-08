package com.example.httpreading.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class ReadingContextCompactionService {
    private static final int MAX_SNIPPETS = 3;
    private static final int MAX_SNIPPET_CHARS = 650;
    private static final int MAX_TOTAL_CHARS = 2200;

    public String compactChapter(String question,
                                 Long bookId,
                                 Integer chapterIndex,
                                 String chapterTitle,
                                 String chapterContent) {
        String content = normalize(chapterContent);
        if (content.isBlank()) {
            return "";
        }

        String title = chapterTitle == null || chapterTitle.isBlank()
            ? "第 " + chapterIndex + " 章"
            : chapterTitle.trim();
        List<String> snippets = isOverviewQuestion(question)
            ? overviewSnippets(content)
            : relevantSnippets(question, content);

        StringBuilder builder = new StringBuilder();
        builder.append("当前章节相关片段：\n")
            .append("来源：当前阅读页面，bookId=").append(bookId)
            .append("，chapterIndex=").append(chapterIndex).append("\n")
            .append("章节：").append(title).append("\n")
            .append("注意：以下不是完整章节，只是和问题相关的摘录。\n");

        int used = builder.length();
        for (int i = 0; i < snippets.size(); i++) {
            String snippet = truncate(snippets.get(i), MAX_SNIPPET_CHARS);
            String section = "\n【片段" + (i + 1) + "】\n" + snippet + "\n";
            if (used + section.length() > MAX_TOTAL_CHARS) {
                int remaining = MAX_TOTAL_CHARS - used - 20;
                if (remaining > 80) {
                    builder.append(section, 0, Math.min(section.length(), remaining))
                        .append("\n[章节摘录已达到长度上限]");
                }
                break;
            }
            builder.append(section);
            used += section.length();
        }
        return builder.toString().trim();
    }

    public String selectedExcerpt(Long bookId,
                                  Integer chapterIndex,
                                  String chapterTitle,
                                  String selectedText,
                                  String selectedContext) {
        String selection = truncate(normalize(selectedText), 500);
        String context = truncate(normalize(selectedContext), 1400);
        if (selection.isBlank() && context.isBlank()) {
            return "";
        }

        String title = chapterTitle == null || chapterTitle.isBlank()
            ? "第 " + chapterIndex + " 章"
            : chapterTitle.trim();

        StringBuilder builder = new StringBuilder();
        builder.append("当前章节划词上下文：\n")
            .append("来源：当前阅读页面，bookId=").append(bookId)
            .append("，chapterIndex=").append(chapterIndex).append("\n")
            .append("章节：").append(title).append("\n")
            .append("注意：以下内容来自用户在正文中的划词及其上下句，不是完整章节。\n");
        if (!selection.isBlank()) {
            builder.append("\n【用户划词】\n").append(selection).append("\n");
        }
        if (!context.isBlank()) {
            builder.append("\n【划词附近上下文】\n").append(context).append("\n");
        }
        return builder.toString().trim();
    }

    private List<String> relevantSnippets(String question, String content) {
        List<String> segments = splitSegments(content);
        if (segments.isEmpty()) {
            return List.of(truncate(content, MAX_SNIPPET_CHARS));
        }

        Set<String> terms = terms(question);
        List<ScoredSegment> scored = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            double score = relevance(terms, segment);
            scored.add(new ScoredSegment(i, score, segment));
        }

        List<String> selected = scored.stream()
            .sorted(Comparator.comparingDouble(ScoredSegment::score).reversed()
                .thenComparingInt(ScoredSegment::index))
            .filter(item -> item.score() > 0.0d)
            .limit(MAX_SNIPPETS)
            .sorted(Comparator.comparingInt(ScoredSegment::index))
            .map(ScoredSegment::segment)
            .toList();

        if (!selected.isEmpty()) {
            return selected;
        }
        return segments.stream().limit(Math.min(MAX_SNIPPETS, segments.size())).toList();
    }

    private List<String> overviewSnippets(String content) {
        List<String> segments = splitSegments(content);
        if (segments.isEmpty()) {
            return List.of(truncate(content, MAX_SNIPPET_CHARS));
        }

        List<String> selected = new ArrayList<>();
        selected.add(segments.get(0));
        segments.stream()
            .filter(this::looksLikeHeading)
            .filter(segment -> !selected.contains(segment))
            .limit(1)
            .forEach(selected::add);
        String last = segments.get(segments.size() - 1);
        if (!selected.contains(last)) {
            selected.add(last);
        }
        return selected.stream().limit(MAX_SNIPPETS).toList();
    }

    private List<String> splitSegments(String content) {
        List<String> result = new ArrayList<>();
        for (String paragraph : content.split("\\R{2,}")) {
            String normalized = normalize(paragraph);
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.length() <= MAX_SNIPPET_CHARS) {
                result.add(normalized);
                continue;
            }
            result.addAll(splitLongParagraph(normalized));
        }
        return result;
    }

    private List<String> splitLongParagraph(String paragraph) {
        List<String> result = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[。！？.!?])");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String normalized = normalize(sentence);
            if (normalized.isBlank()) {
                continue;
            }
            if (current.length() + normalized.length() > MAX_SNIPPET_CHARS && current.length() > 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
            if (normalized.length() > MAX_SNIPPET_CHARS) {
                result.add(truncate(normalized, MAX_SNIPPET_CHARS));
            } else {
                current.append(normalized);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private double relevance(Set<String> queryTerms, String content) {
        if (queryTerms.isEmpty() || content == null || content.isBlank()) {
            return 0.0d;
        }
        String normalized = content.toLowerCase();
        int hits = 0;
        for (String term : queryTerms) {
            if (normalized.contains(term)) {
                hits++;
            }
        }
        return (double) hits / queryTerms.size();
    }

    private Set<String> terms(String question) {
        Set<String> result = new HashSet<>();
        String normalized = question == null ? "" : question.toLowerCase();
        for (String token : normalized.split("[\\s,，。.!！?？;；:：()（）\\[\\]{}<>《》\"']+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        normalized.codePoints()
            .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint))
            .forEach(codePoint -> result.add(new String(Character.toChars(codePoint))));
        return result;
    }

    private boolean isOverviewQuestion(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        return normalized.contains("概括本章")
            || normalized.contains("总结本章")
            || normalized.contains("本章讲了什么")
            || normalized.contains("本章主要")
            || normalized.contains("总结一下")
            || normalized.contains("概述本章")
            || normalized.contains("summarize");
    }

    private boolean looksLikeHeading(String segment) {
        String trimmed = segment == null ? "" : segment.trim();
        return trimmed.length() <= 80
            && (trimmed.startsWith("#")
            || trimmed.matches(".*第[一二三四五六七八九十百0-9]+[章节节].*")
            || trimmed.endsWith("：")
            || trimmed.endsWith(":"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[ \\t]+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        String normalized = normalize(value);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\n[片段过长，后续内容已截断]";
    }

    private record ScoredSegment(int index, double score, String segment) {
    }
}
