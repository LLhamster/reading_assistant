package com.example.httpreading.service.cognition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.httpreading.dto.cognition.ConceptResolutionRequest;
import org.springframework.stereotype.Service;

@Service
public class ConceptCandidateExtractor {
    private static final Pattern QUOTED = Pattern.compile("[“\"'《]([^”\"'》]{2,40})[”\"'》]");
    private static final Pattern CHINESE_TERM = Pattern.compile("[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9]{1,24}");
    private static final List<String> GENERIC_QUESTIONS = List.of(
        "这里是什么意思", "这是什么意思", "是什么意思", "什么意思", "这里怎么理解", "怎么理解", "解释一下");
    private static final List<String> QUESTION_SUFFIXES = List.of(
        "是什么意思", "是什么", "什么意思", "怎么理解", "如何理解", "为什么", "吗", "呢");

    private final ConceptNormalizationService normalizationService;

    public ConceptCandidateExtractor(ConceptNormalizationService normalizationService) {
        this.normalizationService = normalizationService;
    }

    public List<ExtractedConceptCandidate> extract(ConceptResolutionRequest request) {
        Map<String, ExtractedConceptCandidate> candidates = new LinkedHashMap<>();
        addCandidate(candidates, request.selectedText(), "selectedText", 1.0d);

        if (!isGenericQuestionWithoutContext(request)) {
            addQuestionCandidates(candidates, request.question());
        }
        addCandidate(candidates, request.selectedContext(), "selectedContext", 0.65d);
        addCandidate(candidates, request.chapterTitle(), "chapterTitle", 0.45d);
        addCandidate(candidates, request.chapterContent(), "chapterContent", 0.30d);
        addCandidate(candidates, request.recentDialogue(), "recentDialogue", 0.25d);
        return new ArrayList<>(candidates.values());
    }

    private boolean isGenericQuestionWithoutContext(ConceptResolutionRequest request) {
        String question = normalizationService.normalize(request.question());
        boolean generic = GENERIC_QUESTIONS.stream()
            .map(normalizationService::normalize)
            .anyMatch(value -> value.equals(question));
        return generic
            && normalizationService.isBlank(request.selectedText())
            && normalizationService.isBlank(request.selectedContext())
            && normalizationService.isBlank(request.chapterTitle())
            && normalizationService.isBlank(request.chapterContent())
            && normalizationService.isBlank(request.recentDialogue());
    }

    private void addQuestionCandidates(Map<String, ExtractedConceptCandidate> candidates, String question) {
        if (normalizationService.isBlank(question)) {
            return;
        }
        Matcher quoted = QUOTED.matcher(question);
        while (quoted.find()) {
            addCandidate(candidates, quoted.group(1), "question", 0.90d);
        }
        String stripped = question.trim();
        for (String suffix : QUESTION_SUFFIXES) {
            stripped = stripped.replace(suffix, " ");
        }
        stripped = stripped.replace("这个", " ").replace("这里", " ").replace("请", " ").replace("帮我", " ");
        Matcher matcher = CHINESE_TERM.matcher(stripped);
        while (matcher.find()) {
            addCandidate(candidates, matcher.group(), "question", 0.80d);
        }
    }

    private void addCandidate(Map<String, ExtractedConceptCandidate> candidates,
                              String raw,
                              String source,
                              double priority) {
        String name = cleanCandidate(raw);
        String key = normalizationService.normalize(name);
        if (key.length() < 2 || key.length() > 40) {
            return;
        }
        ExtractedConceptCandidate existing = candidates.get(key);
        if (existing == null || existing.priority() < priority) {
            candidates.put(key, new ExtractedConceptCandidate(name, source, priority));
        }
    }

    private String cleanCandidate(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 40) {
            Matcher quoted = QUOTED.matcher(trimmed);
            if (quoted.find()) {
                return quoted.group(1).trim();
            }
            Matcher matcher = CHINESE_TERM.matcher(trimmed);
            if (matcher.find()) {
                return matcher.group().trim();
            }
        }
        for (String suffix : QUESTION_SUFFIXES) {
            if (trimmed.endsWith(suffix) && trimmed.length() > suffix.length()) {
                trimmed = trimmed.substring(0, trimmed.length() - suffix.length());
            }
        }
        return trimmed;
    }
}
