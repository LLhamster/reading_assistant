package com.example.httpreading.evolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.ModelClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/**
 * Hybrid FinalAnswer evaluator: deterministic output/length checks plus an LLM
 * judge that awards points for each positive scoring criterion.
 */
@Service
public class RuleBasedJudge {
    public static final double DEFAULT_PASS_SCORE = 0.75;
    private static final int MAX_PARSE_ATTEMPTS = 2;

    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final EvidenceBoundaryJudge evidenceBoundaryJudge;

    public RuleBasedJudge(ModelClient modelClient,
                          ObjectMapper objectMapper,
                          EvidenceBoundaryJudge evidenceBoundaryJudge) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.evidenceBoundaryJudge = evidenceBoundaryJudge;
    }

    public EvolutionCaseResult judge(EvolutionEvalCase evalCase,
                                     InProcessAgentEvaluator.AgentRun run) {
        String answer = run.answer() == null ? "" : run.answer().trim();
        boolean outputNonBlank = !answer.isBlank() && "completed".equals(run.status());
        double lengthPenalty = lengthPenalty(answer.length(), evalCase.expectedBehavior().maxChars());
        if (!outputNonBlank) {
            return result(evalCase, run, 0.0, false, true,
                List.of(FailureType.EMPTY_OR_MODEL_ERROR),
                List.of(run.error().isBlank() ? "回答为空或模型执行未完成" : run.error()));
        }

        JudgeScore judged = judgeWithModel(evalCase, run, outputNonBlank, lengthPenalty);
        if (!judged.scored()) {
            return result(evalCase, run, 0.0, false, true,
                List.of(FailureType.EVALUATION_ERROR),
                List.of("评测器错误：" + judged.feedback()));
        }
        EvidenceBoundaryJudge.EvidenceReview evidenceReview =
            evidenceBoundaryJudge.review(evalCase, answer);
        if (!evidenceReview.evaluated()) {
            return result(evalCase, run, 0.0, false, true,
                List.of(FailureType.EVALUATION_ERROR),
                List.of("评测器错误：" + evidenceReview.error()));
        }

        double score = judged.normalizedScore(evalCase.expectedBehavior().maxScore()) - lengthPenalty;
        score = Math.max(0.0, Math.min(1.0, score));
        if (judged.forceZero()) score = 0.0;
        if (!evidenceReview.violations().isEmpty()) {
            score = Math.min(0.49, score);
        }
        boolean hardFailure = judged.forceZero() || !evidenceReview.violations().isEmpty();
        boolean passed = score >= DEFAULT_PASS_SCORE && !hardFailure;

        List<FailureType> failures = new ArrayList<>();
        if (!passed) failures.add(evalCase.expectedFailureType());
        if (!evidenceReview.violations().isEmpty()) failures.add(FailureType.EVIDENCE_BOUNDARY);

        List<String> reasons = new ArrayList<>();
        if (!judged.feedback().isBlank()) reasons.add("内容评分：" + judged.feedback());
        judged.criterionScores().stream()
            .filter(value -> value.score() < value.maxScore())
            .forEach(value -> reasons.add(
                "内容评分项 " + value.id() + "（" + format(value.score()) + "/"
                    + format(value.maxScore()) + "）：" + value.reason()));
        evidenceReview.violations().forEach(value -> reasons.add("证据边界：" + value));

        return result(evalCase, run, score, passed, hardFailure,
            failures.stream().distinct().toList(), reasons,
            evidenceReview.issues(), evidenceReview.claims());
    }

    private JudgeScore judgeWithModel(EvolutionEvalCase evalCase,
                                      InProcessAgentEvaluator.AgentRun run,
                                      boolean outputNonBlank,
                                      double lengthPenalty) {
        String lastError = "";
        for (int attempt = 1; attempt <= MAX_PARSE_ATTEMPTS; attempt++) {
            try {
                JsonNode root = parseObject(modelClient.chat(prompt(
                    evalCase, run, outputNonBlank, lengthPenalty)));
                return parseScore(evalCase.expectedBehavior(), root);
            } catch (ModelClientException exception) {
                return JudgeScore.unscored("judge model failed: " + exception.getMessage());
            } catch (Exception exception) {
                lastError = exception.getMessage();
            }
        }
        return JudgeScore.unscored("judge parse failed: " + lastError);
    }

    private String prompt(EvolutionEvalCase evalCase,
                          InProcessAgentEvaluator.AgentRun run,
                          boolean outputNonBlank,
                          double lengthPenalty) throws Exception {
        ObjectNode taskInput = objectMapper.createObjectNode();
        taskInput.put("question", evalCase.request().getQuestion());
        taskInput.set("dialogue", objectMapper.valueToTree(evalCase.dialogue()));
        taskInput.set("collected_evidence", objectMapper.valueToTree(evalCase.collectedEvidence()));
        taskInput.set("mcp_results", objectMapper.valueToTree(evalCase.mcpResults()));
        ObjectNode input = objectMapper.createObjectNode();
        input.set("task_input", taskInput);
        input.set("scoring_criteria",
            objectMapper.valueToTree(evalCase.expectedBehavior().scoringCriteria()));
        input.put("max_score", evalCase.expectedBehavior().maxScore());
        input.put("agent_output", run.answer());
        input.set("execution_trace", objectMapper.valueToTree(Map.of(
            "status", run.status(), "latencyMs", run.latencyMs())));
        input.set("deterministic_checks", objectMapper.valueToTree(Map.of(
            "outputNonBlank", outputNonBlank, "lengthPenalty", lengthPenalty)));
        return """
            你是 FinalAnswer 的严格评估器。不要寻找固定标准答案，也不要要求逐字匹配。
            expected_behavior.scoringCriteria 定义了理想回答应具备的不同信息或行为。
            每项完全满足给满分，部分满足给 0 到满分之间的小数，未满足给 0 分。
            每个评分项必须且只能评分一次；评分是正向累加，不要额外创造扣分字段。

            这里只评价回答是否完成 scoringCriteria，不评价证据真实性；证据由另一个独立 Judge 检查。
            完全没有回答本轮问题时，force_zero=true。

            只返回 JSON：
            {"criterion_scores":[{"id":"criterion_id","score":0.0,"reason":"具体评分理由"}],
             "force_zero":false,"feedback":"最主要的内容缺失或改进建议"}

            输入：
            """ + objectMapper.writeValueAsString(input);
    }

    private JudgeScore parseScore(EvolutionEvalCase.ExpectedBehavior expected, JsonNode root) {
        Map<String, EvolutionEvalCase.ScoringCriterion> criteria = new LinkedHashMap<>();
        expected.scoringCriteria().forEach(value -> criteria.put(value.id(), value));
        double configuredMax = expected.scoringCriteria().stream()
            .mapToDouble(EvolutionEvalCase.ScoringCriterion::score)
            .sum();
        if (Math.abs(configuredMax - expected.maxScore()) > 0.000001) {
            throw new IllegalArgumentException("scoring criteria total must equal maxScore");
        }

        List<CriterionScore> criterionScores = new ArrayList<>();
        root.path("criterion_scores").forEach(node -> {
            String id = node.path("id").asText();
            EvolutionEvalCase.ScoringCriterion criterion = criteria.get(id);
            if (criterion == null) throw new IllegalArgumentException("unknown criterion: " + id);
            double score = node.path("score").asDouble(-1);
            if (score < 0 || score > criterion.score()) {
                throw new IllegalArgumentException("score out of range: " + id);
            }
            criterionScores.add(new CriterionScore(
                id, score, criterion.score(), node.path("reason").asText()));
        });
        if (criterionScores.size() != criteria.size()
            || criterionScores.stream().map(CriterionScore::id).distinct().count() != criteria.size()) {
            throw new IllegalArgumentException("judge must score every criterion exactly once");
        }

        return new JudgeScore(
            criterionScores, root.path("feedback").asText(),
            true, root.path("force_zero").asBoolean(false));
    }

    private double lengthPenalty(int actualChars, int maxChars) {
        if (maxChars <= 0 || actualChars <= maxChars) return 0.0;
        return actualChars <= Math.ceil(maxChars * 1.5) ? 0.05 : 0.10;
    }

    private EvolutionCaseResult result(EvolutionEvalCase evalCase,
                                       InProcessAgentEvaluator.AgentRun run,
                                       double score,
                                       boolean passed,
                                       boolean hardFailure,
                                       List<FailureType> failures,
                                       List<String> reasons) {
        return result(evalCase, run, score, passed, hardFailure, failures, reasons,
            List.of(), List.of());
    }

    private EvolutionCaseResult result(EvolutionEvalCase evalCase,
                                       InProcessAgentEvaluator.AgentRun run,
                                       double score,
                                       boolean passed,
                                       boolean hardFailure,
                                       List<FailureType> failures,
                                       List<String> reasons,
                                       List<EvidenceIssue> evidenceIssues,
                                       List<EvidenceBoundaryJudge.ClaimReview> evidenceClaims) {
        return new EvolutionCaseResult(
            evalCase.id(), run.answer(), run.status(), run.plan(), score,
            passed, hardFailure, failures, reasons, evidenceIssues, evidenceClaims,
            run.latencyMs());
    }

    private JsonNode parseObject(String raw) throws Exception {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("judge did not return JSON");
        return objectMapper.readTree(value.substring(start, end + 1));
    }

    private String format(double value) {
        return "%.2f".formatted(value);
    }

    private record CriterionScore(String id, double score, double maxScore, String reason) {
    }

    private record JudgeScore(List<CriterionScore> criterionScores,
                              String feedback,
                              boolean scored,
                              boolean forceZero) {
        static JudgeScore unscored(String feedback) {
            return new JudgeScore(List.of(), feedback, false, false);
        }

        double normalizedScore(double maxScore) {
            return maxScore <= 0
                ? 0.0
                : criterionScores.stream().mapToDouble(CriterionScore::score).sum() / maxScore;
        }
    }
}
