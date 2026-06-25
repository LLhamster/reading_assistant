package com.example.httpreading.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.httpreading.service.ModelClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationFrameworkTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void syntheticAndSessionCandidatesAreForcedIntoDev(@TempDir Path temp) throws Exception {
        EvaluationCandidateFactory factory = new EvaluationCandidateFactory(objectMapper);
        String generated = generatedCandidateJson("generated-1", "holdout");
        List<EvaluationCases.EvaluationExample> synthetic = factory.synthetic(
            EvaluationCases.TOOL_ROUTING, "tools", 1, ignored -> generated);
        assertEquals("synthetic", synthetic.get(0).source());
        assertEquals(EvaluationCases.DEV, synthetic.get(0).split());

        Path sessions = temp.resolve("sessions.jsonl");
        Files.writeString(sessions, "{\"user\":\"person@example.com\",\"message\":\"token sk-secret1234567890\"}\n");
        List<EvaluationCases.EvaluationExample> session = factory.sessionDb(sessions, prompt -> {
            assertTrue(prompt.contains("[REDACTED_EMAIL]"));
            assertTrue(prompt.contains("[REDACTED_SECRET]"));
            return generatedCandidateJson("session-1", "holdout");
        });
        assertEquals("sessiondb", session.get(0).source());
        assertEquals(EvaluationCases.DEV, session.get(0).split());
    }

    @Test
    void validatorRejectsUnreviewedSourceInHoldoutAndSecrets() {
        EvaluationCases.EvaluationExample example = example("x", "synthetic", "holdout", "sk-secret1234567890");
        List<String> failures = new EvaluationDatasetValidator(objectMapper).validate(List.of(example));
        assertTrue(failures.stream().anyMatch(value -> value.contains("must stay in dev")));
        assertTrue(failures.stream().anyMatch(value -> value.contains("token detected")));
    }

    @Test
    void replayRunnerWritesReportsAndComparisonRequiresSameDataset(@TempDir Path temp) throws Exception {
        List<EvaluationCases.EvaluationExample> examples = List.of(example("a", "golden", "dev", "hello"));
        EvaluationReplayRunner runner = new EvaluationReplayRunner(objectMapper);
        EvaluationReplayRunner.AgentAdapter adapter = evaluationCase -> new EvaluationReplayRunner.AgentResult(
            new EvaluationMetrics.RoutingPrediction("NO_TOOL", "", List.of()), "",
            EvaluationMetrics.ExecutionTrace.empty());
        EvaluationJudge judge = (evaluationCase, prediction, rules, mode) ->
            EvaluationMetrics.JudgeScore.unscored("not used for routing");
        EvaluationReport baseline = runner.run(examples, EvaluationCases.TOOL_ROUTING, EvaluationCases.DEV,
            "COMPONENT", "fixture", EvaluationJudge.Mode.FAST, adapter, judge);
        Path output = new EvaluationReportWriter(objectMapper).write(baseline, temp);
        assertTrue(Files.exists(output.resolve("report.json")));
        assertTrue(Files.exists(output.resolve("report.md")));

        EvaluationReport candidate = copyScore(baseline, 0.9);
        EvaluationReportComparator.Comparison comparison = new EvaluationReportComparator().compare(baseline, candidate);
        assertEquals(candidate.score() - baseline.score(), comparison.improvement(), 0.0001);

        EvaluationReport different = new EvaluationReport(candidate.runId(), candidate.suite(), candidate.target(),
            candidate.split(), candidate.evaluationMode(), candidate.model(), "different", candidate.numDev(),
            candidate.numHoldout(), candidate.evaluated(), candidate.passed(), candidate.unscored(), candidate.score(),
            candidate.modeAccuracy(), candidate.toolPrecision(), candidate.toolRecall(), candidate.toolF1(),
            candidate.exactMatch(), candidate.evidenceRecall(), candidate.modelCalls(), candidate.inputChars(),
            candidate.outputChars(), candidate.latencyMs(), candidate.estimatedCost(), candidate.cases(),
            candidate.categoryScores());
        assertThrows(IllegalArgumentException.class, () -> new EvaluationReportComparator().compare(baseline, different));
    }

    @Test
    void replayRunnerCanSelectAllSplitsForExplicitFullRun() {
        List<EvaluationCases.EvaluationExample> examples = List.of(
            example("dev-case", "golden", EvaluationCases.DEV, "hello"),
            example("holdout-case", "golden", EvaluationCases.HOLDOUT, "hello"));
        EvaluationReplayRunner runner = new EvaluationReplayRunner(objectMapper);
        EvaluationReplayRunner.AgentAdapter adapter = evaluationCase -> new EvaluationReplayRunner.AgentResult(
            new EvaluationMetrics.RoutingPrediction("NO_TOOL", "", List.of()), "",
            EvaluationMetrics.ExecutionTrace.empty());
        EvaluationJudge judge = (evaluationCase, prediction, rules, mode) ->
            EvaluationMetrics.JudgeScore.unscored("not used for routing");

        EvaluationReport report = runner.run(examples, EvaluationCases.TOOL_ROUTING, EvaluationCases.ALL,
            "COMPONENT", "fixture", EvaluationJudge.Mode.FAST, adapter, judge);

        assertEquals(2, report.evaluated());
        assertEquals(1, report.numDev());
        assertEquals(1, report.numHoldout());
    }

    @Test
    void fastAndStrictJudgeUseOneAndThreePasses() {
        ModelClient fastModel = mock(ModelClient.class);
        when(fastModel.chat(anyString())).thenReturn(judgeJson(0.8));
        LlmEvaluationJudge fast = new LlmEvaluationJudge(fastModel, objectMapper);
        judge(fast, EvaluationJudge.Mode.FAST);
        verify(fastModel, times(1)).chat(anyString());

        ModelClient strictModel = mock(ModelClient.class);
        when(strictModel.chat(anyString())).thenReturn(judgeJson(0.7), judgeJson(0.9), judgeJson(0.8));
        LlmEvaluationJudge strict = new LlmEvaluationJudge(strictModel, objectMapper);
        EvaluationMetrics.JudgeScore score = judge(strict, EvaluationJudge.Mode.STRICT);
        verify(strictModel, times(3)).chat(anyString());
        assertEquals(0.8, score.criterionScores().get(0).score(), 0.0001);
    }

    private EvaluationCases.EvaluationExample example(String id, String source, String split, String question) {
        EvaluationCases.TaskInput input = new EvaluationCases.TaskInput(
            question, null, null, List.of(), List.of(), List.of());
        EvaluationCases.ExpectedResult result = new EvaluationCases.ExpectedResult("NO_TOOL", "", List.of());
        return new EvaluationCases.EvaluationExample(id, EvaluationCases.TOOL_ROUTING, input, result, null,
            "EASY", "SMALL_TALK", source, split, Map.of("reviewed", true));
    }

    private String generatedCandidateJson(String id, String split) {
        return """
            {"id":"%s","suite":"TOOL_ROUTING","task_input":{"question":"你好"},
             "expected_result":{"planner_mode":"NO_TOOL","planner_server":"","local_tools":[]},
             "difficulty":"EASY","category":"SMALL_TALK","source":"synthetic","split":"%s",
             "provenance":{}}
            """.formatted(id, split);
    }

    private EvaluationReport copyScore(EvaluationReport report, double score) {
        return new EvaluationReport("candidate", report.suite(), report.target(), report.split(), report.evaluationMode(),
            report.model(), report.datasetFingerprint(), report.numDev(), report.numHoldout(), report.evaluated(),
            report.passed(), report.unscored(), score, report.modeAccuracy(), report.toolPrecision(), report.toolRecall(),
            report.toolF1(), report.exactMatch(), report.evidenceRecall(), report.modelCalls(), report.inputChars(),
            report.outputChars(), report.latencyMs(), report.estimatedCost(), report.cases(), report.categoryScores());
    }

    private EvaluationMetrics.JudgeScore judge(LlmEvaluationJudge judge, EvaluationJudge.Mode mode) {
        EvaluationCases.TaskInput input = new EvaluationCases.TaskInput("question", null, null, List.of(),
            List.of(new EvaluationCases.CollectedEvidence("e1", "rag", "evidence", "content", Map.of())),
            List.of(new EvaluationCases.McpResult("rag.search", true, Map.of())));
        EvaluationCases.ExpectedBehavior behavior = new EvaluationCases.ExpectedBehavior(
            List.of(new EvaluationCases.ScoringCriterion("answer", "Answer only from evidence.", 1)), 1,
            new EvaluationCases.EvidencePolicy(true, false, false, false), 100);
        EvaluationCases.EvaluationExample example = new EvaluationCases.EvaluationExample(
            "judge", EvaluationCases.MULTI_TURN_QA, input, null, behavior,
            "EASY", "JUDGE", "golden", "dev", Map.of());
        EvaluationMetrics.AnswerPrediction prediction = new EvaluationMetrics.AnswerPrediction(
            "answer", EvaluationMetrics.ExecutionTrace.empty());
        EvaluationMetrics.RuleScore rules = new EvaluationMetrics.RuleScore(true, 0);
        return judge.judge(example, prediction, rules, mode);
    }

    private String judgeJson(double score) {
        return "{\"criterion_scores\":[{\"id\":\"answer\",\"score\":" + score
            + ",\"reason\":\"ok\"}],\"policy_violations\":[],\"feedback\":\"ok\"}";
    }
}
