package com.example.httpreading.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
