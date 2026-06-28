package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.ModelClientException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EvidenceBoundaryJudgeTest {
    @Test
    void catchesUnsupportedDetailsFromRealCandidateRegressionSample() {
        EvidenceBoundaryJudge judge = judge("""
            {
              "claims":[
                {"claim":"农会组织农民集体开渠引水","classification":"UNSUPPORTED_CONCRETE","reason":"历史摘要没有该事件"},
                {"claim":"他家隐瞒了八亩水田","classification":"UNSUPPORTED_CONCRETE","reason":"证据没有田亩数字"},
                {"claim":"他揣了两块银圆","classification":"UNSUPPORTED_CONCRETE","reason":"证据没有银圆细节"},
                {"claim":"农会会长当众要求先开群众会","classification":"UNSUPPORTED_CONCRETE","reason":"证据没有人物和处理过程"}
              ],
              "hypothetical_content_present":true,
              "hypothetical_label_position":"MISSING",
              "violations":[]
            }
            """);
        EvolutionEvalCase evalCase = minedStoryCase();
        String answer = """
            有个中等地主在春耕前遇到农会开渠引水。区里干部发现他隐瞒了八亩水田。
            后来他揣了两块银圆去申请入会，农会会长要求先开群众会。
            关键来源：《湖南农民运动考察报告》中的记载。
            """;

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(evalCase, answer);

        assertTrue(result.evaluated());
        assertFalse(result.safe());
        assertTrue(result.violations().stream().anyMatch(value -> value.contains("八亩水田")));
        assertTrue(result.violations().stream().anyMatch(value -> value.contains("两块银圆")));
        assertTrue(result.violations().stream().anyMatch(value -> value.contains("农会会长")));
        assertTrue(result.violations().stream().anyMatch(value -> value.contains("没有 current_page/RAG")));
        assertTrue(result.violations().stream().anyMatch(value -> value.contains("首次出现前")));
    }

    @Test
    void requiresHypotheticalLabelBeforeFirstInventedDetail() {
        EvolutionEvalCase evalCase = commonExampleCase();
        EvidenceBoundaryJudge afterJudge = judge("""
            {"claims":[{"claim":"王明选择了三明治","classification":"LABELED_HYPOTHETICAL","reason":"结尾才声明是假设"}],
             "hypothetical_content_present":true,
             "hypothetical_label_position":"AFTER","violations":[]}
            """);
        EvidenceBoundaryJudge beforeJudge = judge("""
            {"claims":[{"claim":"王明选择了三明治","classification":"LABELED_HYPOTHETICAL","reason":"故事开始前已声明是假设"}],
             "hypothetical_content_present":true,
             "hypothetical_label_position":"BEFORE_OR_AT_FIRST","violations":[]}
            """);

        assertFalse(afterJudge.review(evalCase, "王明选择了三明治。以上是假设。").safe());
        assertTrue(beforeJudge.review(evalCase, "假设王明选择了三明治。").safe());
    }

    @Test
    void filtersBlankViolationsAndAllowsSupportedClaims() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"顾客只有20元和十分钟","classification":"SUPPORTED","reason":"current_page 直接提供"}],
             "hypothetical_content_present":false,
             "hypothetical_label_position":"NOT_APPLICABLE","violations":["","   "]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result =
            judge.review(commonExampleCase(), "顾客只有20元和十分钟。");

        assertTrue(result.safe());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void modelFailureMakesEvidenceReviewUnevaluated() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString()))
            .thenThrow(new ModelClientException("timeout", 500, true));
        EvidenceBoundaryJudge judge =
            new EvidenceBoundaryJudge(modelClient, new ObjectMapper());

        EvidenceBoundaryJudge.EvidenceReview result =
            judge.review(commonExampleCase(), "回答");

        assertFalse(result.evaluated());
        assertTrue(result.error().contains("timeout"));
    }

    private EvidenceBoundaryJudge judge(String output) {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(anyString())).thenReturn(output);
        return new EvidenceBoundaryJudge(modelClient, new ObjectMapper());
    }

    private EvolutionEvalCase commonExampleCase() {
        return new EvalCaseGenerator()
            .generate(List.of(), "u1", 1L, 1, 4).stream()
            .filter(value -> value.expectedFailureType() == FailureType.MISSING_EXAMPLE)
            .findFirst()
            .orElseThrow();
    }

    private EvolutionEvalCase minedStoryCase() {
        MisunderstandingSignal signal = new MisunderstandingSignal(
            "s1", "m1",
            "问题：有没有真实故事？\n结论：中小地主、富农和中农最初反对农会，后来想加入但不被接受。",
            FailureType.MISSING_STORY_DETAIL, 0.9, 44L, 2, Map.of());
        return new EvalCaseGenerator()
            .generate(List.of(signal), "11", 44L, 2, 1).get(0);
    }
}
