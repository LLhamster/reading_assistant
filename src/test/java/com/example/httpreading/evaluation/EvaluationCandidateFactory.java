package com.example.httpreading.evaluation;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

final class EvaluationCandidateFactory {
    interface TextGenerator {
        String generate(String prompt);
    }

    private static final Pattern SECRET = Pattern.compile(
        "(?i)(sk-[a-z0-9_-]{8,}|bearer\\s+[^\\s,]+|api[_-]?key\\s*[:=]\\s*[^\\s,]+)");
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private final ObjectMapper mapper;

    EvaluationCandidateFactory(ObjectMapper objectMapper) {
        mapper = objectMapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    List<EvaluationCases.EvaluationExample> synthetic(String suite, String objectSpec, int count,
                                                       TextGenerator generator) {
        List<EvaluationCases.EvaluationExample> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candidates.add(forceCandidate(parse(generator.generate(syntheticPrompt(suite, objectSpec, i))),
                "synthetic", Map.of("generator_index", i, "reviewed", false)));
        }
        return List.copyOf(candidates);
    }

    List<EvaluationCases.EvaluationExample> sessionDb(Path exportedJsonl, TextGenerator generator) throws IOException {
        List<EvaluationCases.EvaluationExample> candidates = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(exportedJsonl, StandardCharsets.UTF_8)) {
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String sanitized = sanitize(line);
                JsonNode session = mapper.readTree(sanitized);
                String hash = Integer.toHexString(sanitized.hashCode());
                EvaluationCases.EvaluationExample generated = parse(generator.generate(sessionPrompt(session)));
                candidates.add(forceCandidate(generated, "sessiondb",
                    Map.of("session_hash", hash, "source_index", index++, "reviewed", false)));
            }
        }
        return List.copyOf(candidates);
    }

    Path writeCandidates(List<EvaluationCases.EvaluationExample> candidates, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        String jsonl = candidates.stream().map(this::json).collect(java.util.stream.Collectors.joining("\n")) + "\n";
        Files.writeString(output, jsonl, StandardCharsets.UTF_8);
        return output;
    }

    String sanitize(String value) {
        return apply(value, text -> PHONE.matcher(EMAIL.matcher(SECRET.matcher(text)
            .replaceAll("[REDACTED_SECRET]")).replaceAll("[REDACTED_EMAIL]")).replaceAll("[REDACTED_PHONE]"));
    }

    private String apply(String value, UnaryOperator<String> redactor) {
        return value == null ? "" : redactor.apply(value);
    }

    private EvaluationCases.EvaluationExample forceCandidate(EvaluationCases.EvaluationExample candidate,
                                                               String source,
                                                               Map<String, Object> provenance) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(provenance);
        merged.put("generator", "synthetic-final-answer-boundary-v1");
        merged.put("reviewed", false);
        return new EvaluationCases.EvaluationExample(candidate.id(), candidate.suite(), candidate.taskInput(),
            candidate.expectedResult(), candidate.expectedBehavior(), candidate.difficulty(), candidate.category(), source,
            EvaluationCases.DEV, Map.copyOf(merged));
    }

    private EvaluationCases.EvaluationExample parse(String raw) {
        try {
            String value = raw == null ? "" : raw.trim();
            int start = value.indexOf('{');
            int end = value.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("generator did not return a JSON object");
            }
            return mapper.readValue(value.substring(start, end + 1), EvaluationCases.EvaluationExample.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid generated evaluation example", exception);
        }
    }

    private String syntheticPrompt(String suite, String objectSpec, int index) {
        return """
            根据下面的阅读 Agent 能力说明生成 1 条评测候选。只输出统一 EvaluationExample JSON。
            TOOL_ROUTING 使用固定 expected_result；MULTI_TURN_QA 使用 task_input + expected_behavior。
            多轮问答的 task_input 必须包含预先汇总的 collected_evidence 和 mcp_results；
            expected_behavior 必须按本题实际要求生成独立 scoring_criteria，每项单独计分；
            若 suite=MULTI_TURN_QA，expected_behavior 还必须包含 must_include、must_not_include、
            style_constraints、answer_shape、failure_mode、max_chars；没有约束时用空数组或空字符串。
            只有问题确实要求原因、对比、过程或例子时才添加对应评分项，不能提供参考答案。
            可使用的新增类别包括 INSUFFICIENT_EVIDENCE、REAL_CASE_REQUEST、COMPLETE_STORY_REQUEST、
            STYLE_CONTROL、SOURCE_AWARE_ANSWER、RAG_EMPTY_RESULT、RAG_IRRELEVANT_RESULT、
            CONFLICTING_EVIDENCE、TOOL_FAILURE_FALLBACK、MEMORY_OR_PROFILE_FOLLOW_UP。
            provenance 必须包含 "generator":"synthetic-final-answer-boundary-v1","reviewed":false。
            source 必须是 synthetic，split 必须是 dev。
            suite=%s，候选序号=%d。
            能力说明：%s
            """.formatted(suite, index, objectSpec);
    }

    private String sessionPrompt(JsonNode session) {
        return """
            从下面已经脱敏的真实阅读会话中提取 1 条有代表性的评测候选。只输出统一 EvaluationExample JSON。
            忽略无效闲聊，不恢复任何被脱敏的信息。source=sessiondb，split=dev。
            expected_behavior 应生成逐项计分的 scoring_criteria，不要生成参考答案；
            expected_behavior 还必须包含 must_include、must_not_include、style_constraints、
            answer_shape、failure_mode、max_chars；没有约束时用空数组或空字符串。
            评分项应来自该会话的真实问题，不能机械要求每题都解释原因或举例。
            provenance.reviewed=false。
            会话：%s
            """.formatted(session.toString());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
