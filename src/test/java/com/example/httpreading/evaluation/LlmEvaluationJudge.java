package com.example.httpreading.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.ModelClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class LlmEvaluationJudge implements EvaluationJudge {
    private static final int MAX_PARSE_ATTEMPTS = 2;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    LlmEvaluationJudge(ModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public EvaluationMetrics.JudgeScore judge(EvaluationCases.EvaluationExample example,
                                              EvaluationMetrics.AnswerPrediction prediction,
                                              EvaluationMetrics.RuleScore rules,
                                              Mode mode) {
        List<EvaluationMetrics.JudgeScore> passes = new ArrayList<>();
        for (int pass = 0; pass < mode.passes(); pass++) {
            EvaluationMetrics.JudgeScore score = onePass(example, prediction, rules, pass);
            if (!score.scored()) return score;
            passes.add(score);
        }
        return median(example.expectedBehavior(), passes);
    }

    private EvaluationMetrics.JudgeScore onePass(EvaluationCases.EvaluationExample example,
                                                  EvaluationMetrics.AnswerPrediction prediction,
                                                  EvaluationMetrics.RuleScore rules,
                                                  int pass) {
        String lastError = "";
        for (int attempt = 1; attempt <= MAX_PARSE_ATTEMPTS; attempt++) {
            try {
                JsonNode root = parseObject(modelClient.chat(prompt(example, prediction, rules, pass)));
                Map<String, EvaluationCases.ScoringCriterion> expected = new LinkedHashMap<>();
                example.expectedBehavior().scoringCriteria().forEach(c -> expected.put(c.id(), c));
                List<EvaluationMetrics.CriterionScore> scores = new ArrayList<>();
                root.path("criterion_scores").forEach(node -> {
                    String id = node.path("id").asText();
                    EvaluationCases.ScoringCriterion criterion = expected.get(id);
                    if (criterion == null) throw new IllegalArgumentException("unknown criterion: " + id);
                    double value = node.path("score").asDouble(-1);
                    if (value < 0 || value > criterion.score()) throw new IllegalArgumentException("score out of range: " + id);
                    scores.add(new EvaluationMetrics.CriterionScore(id, value, criterion.score(), node.path("reason").asText()));
                });
                if (scores.size() != expected.size()
                    || scores.stream().map(EvaluationMetrics.CriterionScore::id).distinct().count() != expected.size()) {
                    throw new IllegalArgumentException("judge must score every criterion exactly once");
                }
                List<EvaluationMetrics.RequiredItemCheck> required = requiredChecks(root, example.expectedBehavior().mustInclude());
                List<EvaluationMetrics.ForbiddenItemCheck> forbidden = forbiddenChecks(root, example.expectedBehavior().mustNotInclude());
                List<EvaluationMetrics.StyleConstraintCheck> style = styleChecks(root, example.expectedBehavior().styleConstraints());
                List<String> violations = new ArrayList<>();
                root.path("policy_violations").forEach(node -> violations.add(node.asText()));
                return new EvaluationMetrics.JudgeScore(scores, violations, required, forbidden, style,
                    root.path("feedback").asText(), true, root.path("force_zero").asBoolean(false));
            } catch (ModelClientException exception) {
                return EvaluationMetrics.JudgeScore.unscored("judge model failed: " + exception.getMessage());
            } catch (Exception exception) {
                lastError = exception.getMessage();
            }
        }
        return EvaluationMetrics.JudgeScore.unscored("judge parse failed: " + lastError);
    }

    private String prompt(EvaluationCases.EvaluationExample example,
                          EvaluationMetrics.AnswerPrediction prediction,
                          EvaluationMetrics.RuleScore rules,
                          int pass) throws Exception {
        ObjectNode input = objectMapper.createObjectNode();
        input.set("task_input", objectMapper.valueToTree(example.taskInput()));
        input.set("expected_behavior", objectMapper.valueToTree(example.expectedBehavior()));
        input.put("agent_output", prediction.answer());
        input.set("execution_trace", objectMapper.valueToTree(prediction.trace()));
        input.set("deterministic_checks", objectMapper.valueToTree(rules));
        input.put("criterion_order_variant", pass);
        return """
            你是阅读 Agent 最终回答的严格评估器。不要寻找固定标准答案，也不要要求逐字匹配。
            expected_behavior.scoring_criteria 定义了本题需要回答的不同方面。逐项判断 Agent 是否完成该方面：
            完全满足给满分，部分满足可以给 0 到满分之间的小数，未满足给 0 分。
            同时检查 must_include、must_not_include、style_constraints 和 evidence_policy：
            - 每个 must_include 必须检查一次；缺少普通项只扣分，不直接判 0。
            - 每个 must_not_include 必须检查一次；severity 只能是 low、medium、high。
              low 表示模板化或轻微表达问题；medium 表示明显未满足题型要求；
              high 表示编造材料外事实、把假设说成真实、证据不足却硬下结论等严重证据边界问题。
            - 每个 style_constraints 必须检查一次。
            - 若回答违反 evidence_policy，将问题写入 policy_violations。
            - 若答案完全没有回答本轮问题，或核心结论完全来自材料外编造，可设置 force_zero=true。
            每个 criterion 必须且只能评分一次。只返回 JSON。
            注意：下面 JSON 中的“must_include 原文”等只是格式占位说明，绝不能原样输出；
            如果 expected_behavior.must_include / must_not_include / style_constraints 是空数组，
            对应 required_item_checks / forbidden_item_checks / style_constraint_checks 必须输出空数组 []。
            {"criterion_scores":[{"id":"criterion_id","score":0.0,"reason":"评分理由"}],
             "required_item_checks":[{"item":"must_include 原文","matched":true,"reason":"理由"}],
             "forbidden_item_checks":[{"item":"must_not_include 原文","hit":false,"severity":"low|medium|high","reason":"理由"}],
             "style_constraint_checks":[{"item":"style_constraints 原文","matched":true,"reason":"理由"}],
             "policy_violations":[],"force_zero":false,"feedback":"最主要的缺失或改进建议"}
            输入：
            """ + objectMapper.writeValueAsString(input);
    }

    private List<EvaluationMetrics.RequiredItemCheck> requiredChecks(JsonNode root, List<String> expected) {
        if (expected.isEmpty()) return List.of();
        Map<String, EvaluationMetrics.RequiredItemCheck> checks = new LinkedHashMap<>();
        root.path("required_item_checks").forEach(node -> {
            String item = node.path("item").asText();
            if (!expected.contains(item)) throw new IllegalArgumentException("unknown must_include item: " + item);
            checks.put(item, new EvaluationMetrics.RequiredItemCheck(item, node.path("matched").asBoolean(false),
                node.path("reason").asText()));
        });
        ensureAllItems(expected, checks.keySet().stream().toList(), "must_include");
        return expected.stream().map(checks::get).toList();
    }

    private List<EvaluationMetrics.ForbiddenItemCheck> forbiddenChecks(JsonNode root, List<String> expected) {
        if (expected.isEmpty()) return List.of();
        Map<String, EvaluationMetrics.ForbiddenItemCheck> checks = new LinkedHashMap<>();
        root.path("forbidden_item_checks").forEach(node -> {
            String item = node.path("item").asText();
            if (!expected.contains(item)) throw new IllegalArgumentException("unknown must_not_include item: " + item);
            checks.put(item, new EvaluationMetrics.ForbiddenItemCheck(item, node.path("hit").asBoolean(false),
                node.path("severity").asText("medium"), node.path("reason").asText()));
        });
        ensureAllItems(expected, checks.keySet().stream().toList(), "must_not_include");
        return expected.stream().map(checks::get).toList();
    }

    private List<EvaluationMetrics.StyleConstraintCheck> styleChecks(JsonNode root, List<String> expected) {
        if (expected.isEmpty()) return List.of();
        Map<String, EvaluationMetrics.StyleConstraintCheck> checks = new LinkedHashMap<>();
        root.path("style_constraint_checks").forEach(node -> {
            String item = node.path("item").asText();
            if (!expected.contains(item)) throw new IllegalArgumentException("unknown style_constraints item: " + item);
            checks.put(item, new EvaluationMetrics.StyleConstraintCheck(item, node.path("matched").asBoolean(false),
                node.path("reason").asText()));
        });
        ensureAllItems(expected, checks.keySet().stream().toList(), "style_constraints");
        return expected.stream().map(checks::get).toList();
    }

    private void ensureAllItems(List<String> expected, List<String> actual, String field) {
        if (actual.size() != expected.size() || !new java.util.HashSet<>(actual).equals(new java.util.HashSet<>(expected))) {
            throw new IllegalArgumentException("judge must check every " + field + " item exactly once");
        }
    }

    private JsonNode parseObject(String raw) throws Exception {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("judge did not return JSON");
        return objectMapper.readTree(value.substring(start, end + 1));
    }

    private EvaluationMetrics.JudgeScore median(EvaluationCases.ExpectedBehavior behavior,
                                                 List<EvaluationMetrics.JudgeScore> passes) {
        List<EvaluationMetrics.CriterionScore> criteria = new ArrayList<>();
        for (EvaluationCases.ScoringCriterion expected : behavior.scoringCriteria()) {
            List<EvaluationMetrics.CriterionScore> matching = passes.stream()
                .flatMap(pass -> pass.criterionScores().stream()).filter(score -> expected.id().equals(score.id())).toList();
            double score = medianValue(matching.stream().map(EvaluationMetrics.CriterionScore::score).toList());
            String reasons = matching.stream().map(EvaluationMetrics.CriterionScore::reason).filter(v -> !v.isBlank())
                .distinct().reduce((a, b) -> a + " | " + b).orElse("");
            criteria.add(new EvaluationMetrics.CriterionScore(expected.id(), score, expected.score(), reasons));
        }
        return new EvaluationMetrics.JudgeScore(criteria,
            passes.stream().flatMap(pass -> pass.policyViolations().stream()).distinct().toList(),
            medianRequired(behavior.mustInclude(), passes),
            medianForbidden(behavior.mustNotInclude(), passes),
            medianStyle(behavior.styleConstraints(), passes),
            passes.stream().map(EvaluationMetrics.JudgeScore::feedback).filter(v -> !v.isBlank())
                .distinct().reduce((a, b) -> a + " | " + b).orElse(""), true,
            passes.stream().filter(EvaluationMetrics.JudgeScore::forceZero).count() > passes.size() / 2);
    }

    private double medianValue(List<Double> values) {
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(sorted.size() / 2);
    }

    private List<EvaluationMetrics.RequiredItemCheck> medianRequired(List<String> expected,
                                                                      List<EvaluationMetrics.JudgeScore> passes) {
        return expected.stream().map(item -> {
            List<EvaluationMetrics.RequiredItemCheck> matching = passes.stream()
                .flatMap(pass -> pass.requiredItemChecks().stream()).filter(check -> item.equals(check.item())).toList();
            boolean matched = matching.stream().filter(EvaluationMetrics.RequiredItemCheck::matched).count() > passes.size() / 2;
            return new EvaluationMetrics.RequiredItemCheck(item, matched, reasons(matching.stream()
                .map(EvaluationMetrics.RequiredItemCheck::reason).toList()));
        }).toList();
    }

    private List<EvaluationMetrics.ForbiddenItemCheck> medianForbidden(List<String> expected,
                                                                        List<EvaluationMetrics.JudgeScore> passes) {
        return expected.stream().map(item -> {
            List<EvaluationMetrics.ForbiddenItemCheck> matching = passes.stream()
                .flatMap(pass -> pass.forbiddenItemChecks().stream()).filter(check -> item.equals(check.item())).toList();
            boolean hit = matching.stream().filter(EvaluationMetrics.ForbiddenItemCheck::hit).count() > passes.size() / 2;
            String severity = matching.stream().map(EvaluationMetrics.ForbiddenItemCheck::severity)
                .max(Comparator.comparingInt(this::severityRank)).orElse("medium");
            return new EvaluationMetrics.ForbiddenItemCheck(item, hit, severity, reasons(matching.stream()
                .map(EvaluationMetrics.ForbiddenItemCheck::reason).toList()));
        }).toList();
    }

    private List<EvaluationMetrics.StyleConstraintCheck> medianStyle(List<String> expected,
                                                                      List<EvaluationMetrics.JudgeScore> passes) {
        return expected.stream().map(item -> {
            List<EvaluationMetrics.StyleConstraintCheck> matching = passes.stream()
                .flatMap(pass -> pass.styleConstraintChecks().stream()).filter(check -> item.equals(check.item())).toList();
            boolean matched = matching.stream().filter(EvaluationMetrics.StyleConstraintCheck::matched).count() > passes.size() / 2;
            return new EvaluationMetrics.StyleConstraintCheck(item, matched, reasons(matching.stream()
                .map(EvaluationMetrics.StyleConstraintCheck::reason).toList()));
        }).toList();
    }

    private String reasons(List<String> values) {
        return values.stream().filter(value -> !value.isBlank()).distinct().reduce((a, b) -> a + " | " + b).orElse("");
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }
}
