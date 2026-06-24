package com.example.httpreading.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ToolRoutingEvaluationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonlEvaluationLoader loader = new JsonlEvaluationLoader(objectMapper);

    @Test
    void datasetUsesFixedExpectedResultsWithoutAnswerRubrics() throws IOException {
        List<EvaluationCases.EvaluationExample> cases = loader.load(
            "evaluation/tool-routing.jsonl", EvaluationCases.EvaluationExample.class);
        assertEquals(50, cases.size());
        assertEquals(35, cases.stream().filter(c -> EvaluationCases.DEV.equals(c.split())).count());
        assertEquals(15, cases.stream().filter(c -> EvaluationCases.HOLDOUT.equals(c.split())).count());
        assertTrue(new EvaluationDatasetValidator(objectMapper).validate(cases).isEmpty());
        assertTrue(cases.stream().allMatch(c -> c.expectedResult() != null));
        cases.forEach(c -> assertNull(c.expectedBehavior(), c.id()));
    }

    @Test
    void routingIsScoredByDeterministicExactLabels() {
        EvaluationCases.ExpectedResult expected = new EvaluationCases.ExpectedResult(
            "BOUNDED_REACT", "self-local", List.of("context.get_recent_dialogue", "rag.search"));
        EvaluationMetrics.RoutingScore perfect = EvaluationMetrics.scoreRoute(expected,
            new EvaluationMetrics.RoutingPrediction("BOUNDED_REACT", "self-local",
                List.of("rag.search", "context.get_recent_dialogue")));
        assertTrue(perfect.exactMatch());
        assertEquals(1.0, perfect.overall());

        EvaluationMetrics.RoutingScore wrong = EvaluationMetrics.scoreRoute(expected,
            new EvaluationMetrics.RoutingPrediction("NO_TOOL", "", List.of()));
        assertFalse(wrong.exactMatch());
        assertEquals(0.0, wrong.overall());
    }
}
