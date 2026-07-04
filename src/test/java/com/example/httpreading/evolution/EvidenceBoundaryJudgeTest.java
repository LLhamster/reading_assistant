package com.example.httpreading.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
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
    void catchesUnsupportedFactsAndFalseSourceAttributionInHistoricalNarrative() {
        EvidenceBoundaryJudge judge = judge("""
            {
              "claims":[
                {"claim":"农会组织农民集体开渠引水","classification":"UNSUPPORTED_FACTUAL_CLAIM","reason":"历史摘要没有该事件"},
                {"claim":"他家隐瞒了八亩水田","classification":"UNSUPPORTED_FACTUAL_CLAIM","reason":"证据没有田亩数字"},
                {"claim":"他揣了两块银圆","classification":"UNSUPPORTED_FACTUAL_CLAIM","reason":"证据没有银圆细节"},
                {"claim":"农会会长当众要求先开群众会","classification":"UNSUPPORTED_FACTUAL_CLAIM","reason":"证据没有人物和处理过程"},
                {"claim":"关键来源：《湖南农民运动考察报告》中的记载",
                 "classification":"FALSE_SOURCE_ATTRIBUTION","reason":"当前没有书籍原文证据"}
              ],
              "possible_scenario_present":true,
              "scenario_label_position":"MISSING",
              "violations":[]
            }
            """, """
            {"reviews":[
              {"index":0,"relation":"UNSUPPORTED_EXTERNAL_FACT","reason":"新增历史事件"},
              {"index":1,"relation":"UNSUPPORTED_EXTERNAL_FACT","reason":"新增田亩数字"},
              {"index":2,"relation":"UNSUPPORTED_EXTERNAL_FACT","reason":"新增银圆细节"},
              {"index":3,"relation":"UNSUPPORTED_EXTERNAL_FACT","reason":"新增人物和过程"}
            ]}
            """);
        EvolutionEvalCase evalCase = minedStoryCase();
        assertEquals(EvidenceUseMode.SOURCE_GROUNDED_NARRATIVE,
            evalCase.expectedBehavior().evidencePolicy().evidenceUseMode());
        String answer = """
            有个中等地主在春耕前遇到农会开渠引水。区里干部发现他隐瞒了八亩水田。
            后来他揣了两块银圆去申请入会，农会会长要求先开群众会。
            关键来源：《湖南农民运动考察报告》中的记载。
            """;

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(evalCase, answer);

        assertTrue(result.evaluated());
        assertFalse(result.safe());
        assertTrue(result.violations().stream().anyMatch(value -> value.contains("八亩水田")));
        assertTrue(result.issues().stream().allMatch(issue -> issue.examples().size() <= 2));
        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.type() == EvidenceIssueType.FALSE_SOURCE_ATTRIBUTION));
        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.type() == EvidenceIssueType.UNLABELED_UNGROUNDED_SECTION));
    }

    @Test
    void acceptsOneUpfrontPossibilityLabelForAnEntireHistoricalScene() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"有一个地主陈满叔在春耕时遇到农会组织灌水",
                         "classification":"LABELED_POSSIBLE_SCENARIO",
                         "reason":"场景开始前已经说明这是当时可能出现的情况"}],
             "possible_scenario_present":true,
             "scenario_label_position":"BEFORE_OR_AT_FIRST","violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "在当时可能会有这种情况，有一个地主陈满叔在春耕时遇到农会组织灌水，"
                + "随后与农户发生争执，最后才理解组织方式。");

        assertTrue(result.safe());
    }

    @Test
    void acceptsPossibilityLabelInSameParagraphAsFirstHistoricalClaim() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"以下为假设性示例。有一个地主在春耕时遇到农会组织灌水",
                         "classification":"LABELED_POSSIBLE_SCENARIO",
                         "reason":"同段开头已经标明是假设性示例"}],
             "possible_scenario_present":true,
             "scenario_label_position":"BEFORE_OR_AT_FIRST","violations":[]}
            """);

        assertTrue(judge.review(
            minedStoryCase(),
            "以下为假设性示例。有一个地主在春耕时遇到农会组织灌水，随后改变了态度。"
        ).safe());
    }

    @Test
    void acceptsHistoricalDisclosureAddedAtTheEnd() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"有一个地主在春耕时遇到农会组织灌水",
                         "classification":"LABELED_POSSIBLE_SCENARIO",
                         "reason":"直到结尾才声明是假设"}],
             "possible_scenario_present":true,
             "scenario_label_position":"AFTER","violations":[]}
            """);

        assertTrue(judge.review(
            minedStoryCase(),
            "有一个地主在春耕时遇到农会组织灌水，随后改变了态度。以上是假设。"
        ).safe());
    }

    @Test
    void acceptsSemanticallyEquivalentUnderstandingSupplementDisclosure() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"刘德才有八十亩田并雇了两个长工",
                         "classification":"UNSUPPORTED_FACTUAL_CLAIM",
                         "reason":"资料没有人物、田亩和长工数字"}],
             "possible_scenario_present":true,
             "scenario_label_position":"AFTER","violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "刘德才有八十亩田并雇了两个长工。"
                + "以上故事中的人物、场景和对话细节为基于历史背景的帮助理解补充。");

        assertTrue(result.safe());
    }

    @Test
    void acceptsNovelDisclosureWhenSemanticJudgeRecognizesIt() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"刘德才有八十亩田",
                         "classification":"DISCLOSED_UNGROUNDED_CONTENT",
                         "reason":"回答说明叙事是为说明观点而组织的内容"}],
             "possible_scenario_present":true,
             "scenario_label_position":"AFTER","violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "刘德才有八十亩田。本文叙事只是为说明观点而组织，并不承担史实声明。");

        assertTrue(result.safe());
    }

    @Test
    void doesNotLetAnUnlabeledHistoricalSceneBypassAsPedagogical() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"有一个地主在春耕时遇到农会组织灌水",
                         "classification":"PEDAGOGICAL_ILLUSTRATION",
                         "reason":"模型把历史语境中的补写误分为普通举例"}],
             "possible_scenario_present":false,
             "scenario_label_position":"MISSING","violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "有一个地主在春耕时遇到农会组织灌水，随后改变了态度。");

        assertFalse(result.safe());
        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.type() == EvidenceIssueType.UNLABELED_UNGROUNDED_SECTION));
    }

    @Test
    void allowsConcretePedagogicalExampleWithoutTruthLabel() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"一家便利店投入三万元增加夜间库存",
                         "classification":"PEDAGOGICAL_ILLUSTRATION",
                         "reason":"用生活场景解释机会成本，不承担真实事件声明"}],
             "possible_scenario_present":false,
             "scenario_label_position":"NOT_APPLICABLE","violations":[]}
            """);
        EvolutionEvalCase evalCase = commonExampleCase();
        assertEquals(EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            evalCase.expectedBehavior().evidencePolicy().evidenceUseMode());

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            evalCase,
            "一家便利店投入三万元增加夜间库存，就放弃了把同一笔钱用于早餐设备的收益。"
                + "这个取舍说明资源用于一个选项时，会失去另一个选项的机会。");

        assertTrue(result.safe());
    }

    @Test
    void strictSourceRejectsConstructedContentEvenWhenItIsLabeled() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"某店投入三万元",
                         "classification":"DISCLOSED_UNGROUNDED_CONTENT",
                         "reason":"回答已声明内容由助手自主构造"}],
             "possible_scenario_present":true,
             "scenario_label_position":"BEFORE_OR_AT_FIRST","violations":[]}
            """);
        EvolutionEvalCase strictCase = withMode(commonExampleCase(), EvidenceUseMode.STRICT_SOURCE);

        EvidenceBoundaryJudge.EvidenceReview result =
            judge.review(strictCase,
                "以下是助手自主回答内容，没有依据任何资料。某店投入三万元。");

        assertFalse(result.safe());
        assertTrue(result.issues().stream().anyMatch(value ->
            value.type() == EvidenceIssueType.STRICT_SOURCE_UNSUPPORTED_CONTENT));
    }

    @Test
    void strictSourceAllowsEvidenceGapAndConflictGuidance() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[
              {"claim":"第二季度销量1680册，高于第一季度1200册",
               "classification":"SUPPORTED","reason":"表格直接支持"},
              {"claim":"现有证据没有说明销量上升的原因",
               "classification":"EPISTEMIC_BOUNDARY_STATEMENT","reason":"说明证据缺口"},
              {"claim":"需要价格、营销或渠道资料才能进一步分析原因",
               "classification":"EPISTEMIC_BOUNDARY_STATEMENT","reason":"列出所需证据类型"},
              {"claim":"memory 与当前页冲突时，本题采用当前页的直接文本",
               "classification":"EPISTEMIC_BOUNDARY_STATEMENT","reason":"说明冲突处理边界"}
             ],
             "possible_scenario_present":false,
             "scenario_label_position":"NOT_APPLICABLE","violations":[]}
            """);
        EvolutionEvalCase strictCase =
            withMode(commonExampleCase(), EvidenceUseMode.STRICT_SOURCE);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            strictCase,
            "第二季度销量1680册，高于第一季度1200册，但证据没有说明原因。"
                + "需要价格、营销或渠道资料才能分析。若 memory 与当前页冲突，本题采用当前页。");

        assertTrue(result.safe());
    }

    @Test
    void entailmentReviewCanRecoverEpistemicBoundaryStatement() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[
              {"claim":"需要作者简历或教育经历资料才能确认毕业学校",
               "classification":"UNSUPPORTED_FACTUAL_CLAIM",
               "reason":"证据没有作者简历"}
             ],
             "possible_scenario_present":false,
             "scenario_label_position":"NOT_APPLICABLE","violations":[]}
            """, """
            {"reviews":[
              {"index":0,"relation":"EPISTEMIC_BOUNDARY_STATEMENT",
               "reason":"只说明确认答案所需的证据类型，没有声称作者毕业于任何学校"}
            ]}
            """);
        EvolutionEvalCase strictCase =
            withMode(commonExampleCase(), EvidenceUseMode.STRICT_SOURCE);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            strictCase, "当前资料无法确认；需要作者简历或教育经历资料才能回答。");

        assertTrue(result.safe());
        assertEquals("EPISTEMIC_BOUNDARY_STATEMENT",
            result.claims().get(0).classification());
    }

    @Test
    void acceptsExplicitUpfrontAssistantGeneratedWithoutEvidenceDisclosure() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[
              {"claim":"地主陈满叔有八十亩田","classification":"UNSUPPORTED_FACTUAL_CLAIM",
               "reason":"资料没有人物和田亩数字"},
              {"claim":"他后来提着礼物申请入会","classification":"UNGROUNDED_DETAIL",
               "reason":"资料没有具体行为"}
             ],
             "possible_scenario_present":true,
             "scenario_label_position":"BEFORE_OR_AT_FIRST",
             "violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "以下是助手自主回答内容，没依据任何内容，仅用于帮助理解。"
                + "地主陈满叔有八十亩田，他后来提着礼物申请入会。");

        assertTrue(result.safe());
    }

    @Test
    void semanticJudgeRecognizesUpfrontPossibilityDisclosure() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"他给影子起名并研究影子的速度",
                         "classification":"UNSUPPORTED_FACTUAL_CLAIM",
                         "reason":"证据没有这个细节"}],
             "possible_scenario_present":true,
             "scenario_label_position":"BEFORE_OR_AT_FIRST",
             "violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "可以想象这样一个过程：他给影子起名并研究影子的速度，随后才逐渐认识真实事物。");

        assertTrue(result.safe());
    }

    @Test
    void disclosureCannotHideFalseSourceAttribution() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"原文记载地主陈满叔有八十亩田",
                         "classification":"FALSE_SOURCE_ATTRIBUTION",
                         "reason":"免责声明不能把补写内容变成原文事实"}],
             "possible_scenario_present":true,
             "scenario_label_position":"BEFORE_OR_AT_FIRST","violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "以下是助手自主回答内容，没有依据任何资料。"
                + "原文记载地主陈满叔有八十亩田。");

        assertFalse(result.safe());
        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.type() == EvidenceIssueType.FALSE_SOURCE_ATTRIBUTION));
    }

    @Test
    void unverifiedMemoryMentionOfBookIsNotFalseSourceAttribution() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[
              {"claim":"关键来源：当前未提供原文；历史记忆摘要提到《湖南农民运动考察报告》相关内容，无法核验",
               "classification":"SUPPORTED",
               "reason":"明确限定为未核验的历史记忆摘要，没有把书名当作事实来源"},
              {"claim":"故事人物和情节由助手构造",
               "classification":"DISCLOSED_UNGROUNDED_CONTENT",
               "reason":"回答已明确声明"}
             ],
             "possible_scenario_present":true,
             "scenario_label_position":"BEFORE_OR_AT_FIRST","violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "以下为助手自主构造、没有资料依据，仅用于理解。"
                + "故事中的人物和情节均为构造。"
                + "关键来源：当前未提供原文；历史记忆摘要提到"
                + "《湖南农民运动考察报告》相关内容，无法核验。");

        assertTrue(result.safe());
        assertEquals("SUPPORTED", result.claims().get(0).classification());
    }

    @Test
    void successfulLlmDecisionIsNotOverriddenByRegex() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[
              {"claim":"关键来源：历史记忆摘要提到《湖南农民运动考察报告》相关内容，当前未提供原文，无法核验",
               "classification":"SUPPORTED",
               "reason":"只是说明未核验 memory 的内容"}
             ],
             "possible_scenario_present":false,
             "scenario_label_position":"NOT_APPLICABLE","violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "关键来源：历史记忆摘要提到《湖南农民运动考察报告》相关内容，"
                + "当前未提供原文，无法核验。");

        assertTrue(result.safe());
    }

    @Test
    void unverifiedMemoryNoteDoesNotExcuseDirectBookAttributionElsewhere() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[
              {"claim":"《湖南农民运动考察报告》中记载了这个历史动态",
               "classification":"FALSE_SOURCE_ATTRIBUTION",
               "reason":"当前没有书籍原文证据"},
              {"claim":"历史记忆摘要提到该报告，当前无法核验",
               "classification":"SUPPORTED",
               "reason":"只是描述 memory 的内容和未核验状态"}
             ],
             "possible_scenario_present":true,
             "scenario_label_position":"BEFORE_OR_AT_FIRST","violations":[]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "以下为助手自主构造、没有资料依据，仅用于理解。"
                + "这个故事反映了《湖南农民运动考察报告》中记载的历史动态。"
                + "历史记忆摘要提到该报告，但当前没有原文，无法核验。");

        assertFalse(result.safe());
        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.type() == EvidenceIssueType.FALSE_SOURCE_ATTRIBUTION));
    }

    @Test
    void filtersBlankAndSuccessViolationsAndAllowsSupportedClaims() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"顾客只有20元和十分钟","classification":"SUPPORTED","reason":"current_page 直接提供"}],
             "possible_scenario_present":false,
             "scenario_label_position":"NOT_APPLICABLE",
             "violations":["","   ","未发现违规：回答符合证据策略"]}
            """);

        EvidenceBoundaryJudge.EvidenceReview result =
            judge.review(commonExampleCase(), "顾客只有20元和十分钟。");

        assertTrue(result.safe());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void modelFailureMakesEvidenceReviewUnevaluated() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(
            anyString(), any(ModelClient.ChatOptions.class)))
            .thenThrow(new ModelClientException("timeout", 500, true));
        EvidenceBoundaryJudge judge =
            new EvidenceBoundaryJudge(modelClient, new ObjectMapper());

        EvidenceBoundaryJudge.EvidenceReview result =
            judge.review(commonExampleCase(), "回答");

        assertFalse(result.evaluated());
        assertTrue(result.error().contains("timeout"));
        assertTrue(result.error().contains("fallback was inconclusive"));
    }

    @Test
    void regexDetectsExplicitSourceViolationOnlyAfterLlmFailure() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(
            anyString(), any(ModelClient.ChatOptions.class)))
            .thenThrow(new ModelClientException("timeout", 500, true));
        EvidenceBoundaryJudge judge =
            new EvidenceBoundaryJudge(modelClient, new ObjectMapper());

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            minedStoryCase(),
            "《湖南农民运动考察报告》中记载了陈满叔有八十亩田。");

        assertTrue(result.evaluated());
        assertFalse(result.safe());
        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.type() == EvidenceIssueType.FALSE_SOURCE_ATTRIBUTION));
        assertTrue(result.error().contains("deterministic fallback"));
    }

    @Test
    void semanticEntailmentReviewAcceptsEquivalentTheoreticalWording() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"损失区的心理曲线斜率比收益区更陡",
                         "classification":"UNSUPPORTED_FACTUAL_CLAIM",
                         "reason":"证据没有使用斜率一词"}],
             "possible_scenario_present":false,
             "scenario_label_position":"NOT_APPLICABLE","violations":[]}
            """, """
            {"reviews":[{"index":0,"relation":"ENTAILED",
                         "reason":"心理反应更强与曲线斜率更陡是语义等价表达"}]}
            """);

        EvolutionEvalCase evalCase = new EvalCaseGenerator()
            .generate(List.of(), "u1", 1L, 1, 30).stream()
            .filter(value -> value.request().getQuestion().contains("损失感受"))
            .findFirst().orElseThrow();
        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            evalCase, "损失区的心理曲线斜率比收益区更陡。");

        assertTrue(result.safe());
        assertEquals("SUPPORTED_PARAPHRASE",
            result.claims().get(0).classification());
    }

    @Test
    void semanticEntailmentReviewKeepsUnsupportedExternalFactUnsafe() {
        EvidenceBoundaryJudge judge = judge("""
            {"claims":[{"claim":"某研究发现损失影响是收益的2.25倍",
                         "classification":"UNSUPPORTED_FACTUAL_CLAIM",
                         "reason":"证据没有研究和比例"}],
             "possible_scenario_present":false,
             "scenario_label_position":"NOT_APPLICABLE","violations":[]}
            """, """
            {"reviews":[{"index":0,"relation":"UNSUPPORTED_EXTERNAL_FACT",
                         "reason":"新增具体研究和统计比例"}]}
            """);
        EvolutionEvalCase evalCase = new EvalCaseGenerator()
            .generate(List.of(), "u1", 1L, 1, 30).stream()
            .filter(value -> value.request().getQuestion().contains("损失感受"))
            .findFirst().orElseThrow();

        EvidenceBoundaryJudge.EvidenceReview result = judge.review(
            evalCase, "某研究发现损失影响是收益的2.25倍。");

        assertFalse(result.safe());
        assertTrue(result.issues().stream().anyMatch(issue ->
            issue.type() == EvidenceIssueType.UNSUPPORTED_FACTUAL_CLAIM));
    }

    @Test
    void entailmentModelFailureMakesEvidenceReviewUnevaluated() {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(
            anyString(), any(ModelClient.ChatOptions.class)))
            .thenReturn("""
                {"claims":[{"claim":"某研究发现影响是2.25倍",
                             "classification":"UNSUPPORTED_FACTUAL_CLAIM",
                             "reason":"证据没有研究比例"}],
                 "possible_scenario_present":false,
                 "scenario_label_position":"NOT_APPLICABLE","violations":[]}
                """)
            .thenThrow(new ModelClientException("entailment timeout", 500, true));
        EvidenceBoundaryJudge judge =
            new EvidenceBoundaryJudge(modelClient, new ObjectMapper());

        EvidenceBoundaryJudge.EvidenceReview result =
            judge.review(commonExampleCase(), "某研究发现影响是2.25倍。");

        assertFalse(result.evaluated());
        assertTrue(result.error().contains("entailment timeout"));
    }

    private EvidenceBoundaryJudge judge(String output, String... followingOutputs) {
        ModelClient modelClient = mock(ModelClient.class);
        when(modelClient.chat(
            anyString(), any(ModelClient.ChatOptions.class)))
            .thenReturn(output, followingOutputs);
        return new EvidenceBoundaryJudge(modelClient, new ObjectMapper());
    }

    private EvolutionEvalCase commonExampleCase() {
        return new EvalCaseGenerator()
            .generate(List.of(), "u1", 1L, 1, 10).stream()
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

    private EvolutionEvalCase withMode(EvolutionEvalCase source, EvidenceUseMode mode) {
        EvolutionEvalCase.ExpectedBehavior old = source.expectedBehavior();
        EvolutionEvalCase.ExpectedBehavior behavior = new EvolutionEvalCase.ExpectedBehavior(
            old.scoringCriteria(), old.maxScore(),
            new EvolutionEvalCase.EvidencePolicy(true, true, mode), old.maxChars());
        return new EvolutionEvalCase(
            source.id(), source.signalId(), source.request(), source.expectedFailureType(),
            source.anchorTerms(), source.minimumAnswerChars(), source.previousAnswer(),
            source.dialogue(), source.collectedEvidence(), source.mcpResults(),
            source.finalAnswerInput(), behavior, source.boundarySpec(),
            source.difficulty(), source.category());
    }
}
