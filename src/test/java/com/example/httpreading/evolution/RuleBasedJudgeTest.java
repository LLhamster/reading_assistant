package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RuleBasedJudgeTest {
    private static final String SAFE_EVIDENCE = """
        {"claims":[{"claim":"回答中的核心说明","classification":"SUPPORTED","reason":"由测试证据支持"}],
         "hypothetical_content_present":false,
         "hypothetical_label_position":"NOT_APPLICABLE","violations":[]}
        """;

    @Test
    void failsWhenLlmCriterionScoreIsBelowThreshold() {
        RuleBasedJudge judge = judge("""
            {"criterion_scores":[
              {"id":"answer_current_question","score":1.0,"reason":"回答了问题"},
              {"id":"provide_requested_example","score":0.0,"reason":"没有具体例子"}],
             "force_zero":false,"feedback":"缺少具体例子"}
            """, SAFE_EVIDENCE);

        EvolutionCaseResult result = judge.judge(
            evalCase(FailureType.MISSING_EXAMPLE),
            run("这句话是在说明两种力量之间存在差异。"));

        assertFalse(result.passed());
        assertTrue(result.failureTypes().contains(FailureType.MISSING_EXAMPLE));
    }

    @Test
    void passesFullySatisfiedCriteria() {
        RuleBasedJudge judge = judge("""
            {"criterion_scores":[
              {"id":"answer_current_question","score":1.0,"reason":"直接回答"},
              {"id":"provide_requested_example","score":1.0,"reason":"例子具体且有对应关系"}],
             "force_zero":false,"feedback":""}
            """, """
            {"claims":[{"claim":"一家小店收入100元","classification":"LABELED_HYPOTHETICAL","reason":"前置标注假设"}],
             "hypothetical_content_present":true,
             "hypothetical_label_position":"BEFORE_OR_AT_FIRST","violations":[]}
            """);

        EvolutionCaseResult result = judge.judge(
            evalCase(FailureType.MISSING_EXAMPLE),
            run("例如，假设一家小店收入100元，其中60元补充原料，这说明两个量不能混为一谈。"));

        assertTrue(result.passed());
    }

    @Test
    void evidenceViolationIsHardFailureAndJudgeFailureIsUnscored() {
        RuleBasedJudge evidenceJudge = judge("""
            {"criterion_scores":[
              {"id":"answer_current_question","score":1.0,"reason":"形式上回答"},
              {"id":"complete_story","score":1.0,"reason":"故事完整"}],
             "force_zero":false,"feedback":""}
            """, """
            {"claims":[{"claim":"某村真实发生了一个完整故事","classification":"UNSUPPORTED_CONCRETE","reason":"证据未提供该事件"}],
             "hypothetical_content_present":false,
             "hypothetical_label_position":"MISSING","violations":["没有书籍证据却把虚构故事表述为真实事件"]}
            """);
        EvolutionCaseResult evidenceResult = evidenceJudge.judge(
            evalCase(FailureType.MISSING_STORY_DETAIL), run("某村真实发生了一个完整故事。"));
        assertTrue(evidenceResult.hardFailure());
        assertTrue(evidenceResult.failureTypes().contains(FailureType.EVIDENCE_BOUNDARY));

        RuleBasedJudge brokenJudge = judge("not-json", SAFE_EVIDENCE);
        EvolutionCaseResult unscored = brokenJudge.judge(
            evalCase(FailureType.NOT_DIRECT), run("回答正文"));
        assertTrue(unscored.hardFailure());
        assertTrue(unscored.failureTypes().contains(FailureType.EVALUATION_ERROR));
    }

    private RuleBasedJudge judge(String scoringOutput, String evidenceOutput) {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn(scoringOutput, evidenceOutput);
        ObjectMapper objectMapper = new ObjectMapper();
        return new RuleBasedJudge(
            modelClient, objectMapper, new EvidenceBoundaryJudge(modelClient, objectMapper));
    }

    private EvolutionEvalCase evalCase(FailureType type) {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(1L);
        request.setChapterIndex(1);
        request.setQuestion("本轮问题");
        String secondId = switch (type) {
            case MISSING_EXAMPLE -> "provide_requested_example";
            case MISSING_STORY_DETAIL -> "complete_story";
            default -> "direct_answer";
        };
        EvolutionEvalCase.ExpectedBehavior behavior = new EvolutionEvalCase.ExpectedBehavior(
            List.of(
                new EvolutionEvalCase.ScoringCriterion("answer_current_question", "回答本轮问题", 1.0),
                new EvolutionEvalCase.ScoringCriterion(secondId, "满足专项要求", 1.0)),
            2.0,
            new EvolutionEvalCase.EvidencePolicy(true, true, true, true),
            500);
        return new EvolutionEvalCase(
            "c1", "s1", request, type, List.of(), 0, "",
            List.of(), List.of(), List.of(), null, behavior, "MEDIUM", "TEST");
    }

    private InProcessAgentEvaluator.AgentRun run(String answer) {
        return new InProcessAgentEvaluator.AgentRun("c1", answer, "completed", null, 1, "");
    }
}
