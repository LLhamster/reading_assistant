package com.example.httpreading.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.service.ModelClient;
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
                List<String> violations = new ArrayList<>();
                root.path("policy_violations").forEach(node -> violations.add(node.asText()));
                return new EvaluationMetrics.JudgeScore(scores, violations, root.path("feedback").asText(), true);
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
            同时检查 evidence_policy；若回答违反证据使用范围，将问题写入 policy_violations。
            每个 criterion 必须且只能评分一次。只返回 JSON：
            {"criterion_scores":[{"id":"criterion_id","score":0.0,"reason":"评分理由"}],
             "policy_violations":[],"feedback":"最主要的缺失或改进建议"}
            输入：
            """ + objectMapper.writeValueAsString(input);
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
            passes.stream().map(EvaluationMetrics.JudgeScore::feedback).filter(v -> !v.isBlank())
                .distinct().reduce((a, b) -> a + " | " + b).orElse(""), true);
    }

    private double medianValue(List<Double> values) {
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(sorted.size() / 2);
    }
}
