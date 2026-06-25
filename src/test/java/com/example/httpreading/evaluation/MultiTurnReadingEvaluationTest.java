package com.example.httpreading.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

class MultiTurnReadingEvaluationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonlEvaluationLoader loader = new JsonlEvaluationLoader(objectMapper);

    @Test
    void datasetContainsPreAggregatedEvidenceMcpResultsAndCaseSpecificRubrics() throws IOException {
        List<EvaluationCases.EvaluationExample> cases = loader.load(
            "evaluation/multi-turn-reading-qa.jsonl", EvaluationCases.EvaluationExample.class);
        assertEquals(50, cases.size());
        assertEquals(35, cases.stream().filter(c -> EvaluationCases.DEV.equals(c.split())).count());
        assertEquals(15, cases.stream().filter(c -> EvaluationCases.HOLDOUT.equals(c.split())).count());
        assertTrue(new EvaluationDatasetValidator(objectMapper).validate(cases).isEmpty());
        assertEquals(50, cases.stream().map(c -> c.expectedBehavior().scoringCriteria().stream()
            .map(EvaluationCases.ScoringCriterion::description).toList()).distinct().count());
        assertTrue(cases.stream().map(c -> c.expectedBehavior().scoringCriteria().size()).distinct().count() > 1,
            "criteria count should vary with the question");
        for (EvaluationCases.EvaluationExample evaluationCase : cases) {
            assertNull(evaluationCase.expectedResult(), evaluationCase.id());
            assertTrue(evaluationCase.taskInput().dialogue().size() >= 2, evaluationCase.id());
            assertTrue(evaluationCase.taskInput().collectedEvidence().size() >= 3, evaluationCase.id());
            assertEquals(List.of("context.get_recent_dialogue", "context.get_current_page", "rag.search"),
                evaluationCase.taskInput().mcpResults().stream().map(EvaluationCases.McpResult::tool).toList(),
                evaluationCase.id());
            assertEquals(evaluationCase.expectedBehavior().maxScore(),
                evaluationCase.expectedBehavior().scoringCriteria().stream()
                    .mapToDouble(EvaluationCases.ScoringCriterion::score).sum(), 0.0001);
        }
    }

    @Test
    void criteriaScoresAreAdditiveAndPolicyViolationsAreHardFailures() {
        EvaluationMetrics.RuleScore rules = new EvaluationMetrics.RuleScore(true, 0.1);
        EvaluationMetrics.JudgeScore judge = new EvaluationMetrics.JudgeScore(
            List.of(new EvaluationMetrics.CriterionScore("answer", 1, 1, "done")), List.of(), "ok", true);
        assertEquals(0.9, EvaluationMetrics.combineAnswer(rules, judge).overall(), 0.0001);

        EvaluationMetrics.JudgeScore violation = new EvaluationMetrics.JudgeScore(
            judge.criterionScores(), List.of("把假设性例子说成原文事实"), "policy failure", true);
        assertTrue(EvaluationMetrics.combineAnswer(new EvaluationMetrics.RuleScore(true, 0), violation).overall() <= 0.49);
    }

    @Test
    void missingCriterionLosesOnlyThatCriterionPoints() {
        EvaluationMetrics.JudgeScore judge = new EvaluationMetrics.JudgeScore(List.of(
            new EvaluationMetrics.CriterionScore("identify", 1, 1, "done"),
            new EvaluationMetrics.CriterionScore("explain", 1, 1, "done"),
            new EvaluationMetrics.CriterionScore("example", 0, 1, "missing")), List.of(), "缺少例子", true);
        assertEquals(2.0 / 3.0,
            EvaluationMetrics.combineAnswer(new EvaluationMetrics.RuleScore(true, 0), judge).overall(), 0.0001);
    }

    @Test
    void oldBehaviorJsonDefaultsNewFields() throws Exception {
        ObjectMapper snake = objectMapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        EvaluationCases.ExpectedBehavior behavior = snake.readValue("""
            {"scoring_criteria":[{"id":"answer","description":"回答问题","score":1}],
             "max_score":1,"evidence_policy":{"use_provided_evidence":true},"max_chars":100}
            """, EvaluationCases.ExpectedBehavior.class);
        assertTrue(behavior.mustInclude().isEmpty());
        assertTrue(behavior.mustNotInclude().isEmpty());
        assertTrue(behavior.styleConstraints().isEmpty());
        assertEquals("", behavior.answerShape());
        assertEquals("", behavior.failureMode());
    }

    @Test
    void validatorEnforcesNewFinalAnswerCategories() {
        EvaluationDatasetValidator validator = new EvaluationDatasetValidator(objectMapper);
        assertTrue(validator.validate(List.of(answerCase("real", "REAL_CASE_REQUEST",
            List.of("人物"), List.of(), List.of()))).stream()
            .anyMatch(value -> value.contains("REAL_CASE_REQUEST")));
        assertTrue(validator.validate(List.of(answerCase("style", "STYLE_CONTROL",
            List.of(), List.of(), List.of()))).stream()
            .anyMatch(value -> value.contains("STYLE_CONTROL")));
        assertTrue(validator.validate(List.of(answerCase("insufficient", "INSUFFICIENT_EVIDENCE",
            List.of("说明证据不足"), List.of(), List.of()))).stream()
            .anyMatch(value -> value.contains("INSUFFICIENT_EVIDENCE")));
    }

    @Test
    void requiredForbiddenStyleAndLengthAffectAnswerScore() {
        EvaluationCases.ExpectedBehavior behavior = behavior(
            List.of("人物", "具体时间"), List.of("编造材料外事实"), List.of("不要模板化"), 5);
        EvaluationMetrics.JudgeScore judge = new EvaluationMetrics.JudgeScore(
            List.of(new EvaluationMetrics.CriterionScore("answer", 1, 1, "done")), List.of(),
            List.of(new EvaluationMetrics.RequiredItemCheck("人物", true, "有人物"),
                new EvaluationMetrics.RequiredItemCheck("具体时间", false, "缺时间")),
            List.of(new EvaluationMetrics.ForbiddenItemCheck("编造材料外事实", false, "high", "")),
            List.of(new EvaluationMetrics.StyleConstraintCheck("不要模板化", false, "模板化")),
            "missing fields", true, false);
        EvaluationMetrics.AnswerScore score = EvaluationMetrics.combineAnswer(behavior,
            EvaluationMetrics.scoreAnswerRules(behavior, new EvaluationMetrics.AnswerPrediction(
                "这是一段超过十个字的回答。", EvaluationMetrics.ExecutionTrace.empty())), judge);
        assertEquals(List.of("具体时间"), judge.missingRequiredItems());
        assertEquals(List.of("不要模板化"), judge.styleViolations());
        assertEquals(0.5, score.requiredItemRecall(), 0.0001);
        assertEquals(1.0 - 0.15 - 0.10 - 0.10, score.overall(), 0.0001);
    }

    @Test
    void highForbiddenHitCapsScoreAndAppearsInReportCase() throws Exception {
        EvaluationCases.ExpectedBehavior behavior = behavior(List.of(), List.of("编造材料外事实"), List.of(), 200);
        EvaluationCases.EvaluationExample example = answerCase("bad", "EVIDENCE_GROUNDING", behavior);
        EvaluationReplayRunner runner = new EvaluationReplayRunner(objectMapper);
        EvaluationReport report = runner.run(List.of(example), EvaluationCases.MULTI_TURN_QA, EvaluationCases.DEV,
            "FINAL_ANSWER_COMPONENT_REPLAY", "fixture", EvaluationJudge.Mode.FAST,
            ignored -> new EvaluationReplayRunner.AgentResult(
                new EvaluationMetrics.RoutingPrediction("", "", List.of()), "编造材料外事实",
                EvaluationMetrics.ExecutionTrace.empty()),
            (evaluationCase, prediction, rules, mode) -> new EvaluationMetrics.JudgeScore(
                List.of(new EvaluationMetrics.CriterionScore("answer", 1, 1, "done")), List.of(),
                List.of(),
                List.of(new EvaluationMetrics.ForbiddenItemCheck("编造材料外事实", true, "high", "命中严重禁止项")),
                List.of(), "严重违规", true, false));

        EvaluationReport.CaseResult result = report.cases().get(0);
        assertTrue(result.score() <= 0.49);
        assertEquals(List.of("编造材料外事实"), result.forbiddenItemsHit());
        assertEquals("evidence_only", result.answerShape());
        assertEquals("hallucination", result.failureMode());
    }

    private EvaluationCases.EvaluationExample answerCase(String id, String category,
                                                         List<String> mustInclude,
                                                         List<String> mustNotInclude,
                                                         List<String> styleConstraints) {
        return answerCase(id, category, behavior(mustInclude, mustNotInclude, styleConstraints, 200));
    }

    private EvaluationCases.EvaluationExample answerCase(String id, String category,
                                                         EvaluationCases.ExpectedBehavior behavior) {
        EvaluationCases.TaskInput input = new EvaluationCases.TaskInput("问题", null,
            new EvaluationCases.BookContext("书", "章"),
            List.of(new EvaluationCases.DialogueTurn("user", "上一问"),
                new EvaluationCases.DialogueTurn("assistant", "上一答")),
            List.of(new EvaluationCases.CollectedEvidence("e1", "rag", "证据", "证据内容", Map.of())),
            List.of(new EvaluationCases.McpResult("rag.search", true, Map.of())));
        return new EvaluationCases.EvaluationExample(id, EvaluationCases.MULTI_TURN_QA, input, null, behavior,
            "MEDIUM", category, "golden", EvaluationCases.DEV, Map.of("reviewed", true));
    }

    private EvaluationCases.ExpectedBehavior behavior(List<String> mustInclude,
                                                      List<String> mustNotInclude,
                                                      List<String> styleConstraints,
                                                      int maxChars) {
        return new EvaluationCases.ExpectedBehavior(
            List.of(new EvaluationCases.ScoringCriterion("answer", "回答本轮问题", 1)), 1,
            new EvaluationCases.EvidencePolicy(true, true, false, false),
            mustInclude, mustNotInclude, styleConstraints, "evidence_only", "hallucination", maxChars);
    }
}
