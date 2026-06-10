package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ModelClient;
import org.junit.jupiter.api.Test;

class FinalAnswerServiceTest {

    @Test
    void promptRequiresPlainNaturalReadingAnswerStyle() {
        FinalAnswerService service = new FinalAnswerService(mock(ModelClient.class));

        String prompt = service.buildPrompt(request(), plan(), evidence());

        assertTrue(prompt.contains("不要写成教科书式条目堆砌"));
        assertTrue(prompt.contains("只回答这个新增点"));
        assertTrue(prompt.contains("不要把上一轮答案换个说法再讲一遍"));
        assertTrue(prompt.contains("最近对话只用于理解追问指向"));
        assertTrue(prompt.contains("重点解释“当时的人为什么看不清”"));
        assertTrue(prompt.contains("不要重新回答“为什么他们重要”"));
        assertTrue(prompt.contains("不要反复使用“首先、其次、综上所述”这套论文腔"));
        assertTrue(prompt.contains("控制在 4-6 段"));
        assertTrue(prompt.contains("先翻译成普通人的话"));
        assertTrue(prompt.contains("简单说"));
        assertTrue(prompt.contains("不要固定标题式结构"));
        assertTrue(prompt.contains("每个原因后面都要解释“为什么”"));
        assertTrue(prompt.contains("不要在最终回答中完整展示 working memory"));
        assertTrue(prompt.contains("记忆最多概括为“最近对话摘要”或“相关记忆”"));
        assertTrue(prompt.contains("requiresConcreteExample=true"));
        assertTrue(prompt.contains("requiresStorytelling=true"));
        assertTrue(prompt.contains("answerMode=CONTEXT_ANCHORED_MODEL_KNOWLEDGE"));
        assertTrue(prompt.contains("本系统不是纯 RAG 摘录器"));
        assertTrue(prompt.contains("最终回答应以用户问题为主"));
        assertTrue(prompt.contains("collectedEvidence 默认不是唯一依据"));
        assertTrue(prompt.contains("允许根据公共知识回答"));
        assertTrue(prompt.contains("当前资料没有直接解释，下面是基于一般知识的辅助理解"));
        assertTrue(prompt.contains("为帮助理解补充的常识解释"));
        assertTrue(prompt.contains("问题要求具体出处、具体人物、具体事件、具体时间地点"));
        assertTrue(prompt.contains("不要低价值复述资料关键词"));
        assertTrue(prompt.contains("更具体的焦点术语"));
        assertTrue(prompt.contains("不能因为关键词重叠就回答成母概念的一般例子"));
        assertTrue(prompt.contains("没有实际执行 GitHub、网页或外部实时搜索"));
        assertTrue(prompt.contains("不能说“根据本次搜索结果”"));
        assertTrue(prompt.contains("当前未收集到足够证据；以上为一般知识辅助理解"));
    }

    @Test
    void promptAllowsModelKnowledgeForConceptExplanationWithEmptyEvidence() {
        FinalAnswerService service = new FinalAnswerService(mock(ModelClient.class));

        String prompt = service.buildPrompt(request(), conceptExplanationPlan(), emptyEvidence());

        assertTrue(prompt.contains("answerMode=CONTEXT_ANCHORED_MODEL_KNOWLEDGE"));
        assertTrue(prompt.contains("即使 collectedEvidence 为空，也允许根据公共知识回答"));
        assertTrue(prompt.contains("当前资料没有直接解释，下面是基于一般知识的辅助理解"));
        assertTrue(prompt.contains("这不是原文直接证据"));
        assertTrue(prompt.contains("当前未收集到足够证据；以上为一般知识辅助理解"));
    }

    @Test
    void textOnlyPromptStillRequiresEvidenceOnlyAnswerWhenEvidenceIsEmpty() {
        FinalAnswerService service = new FinalAnswerService(mock(ModelClient.class));

        String prompt = service.buildPrompt(request(), textOnlyPlan(), emptyEvidence());

        assertTrue(prompt.contains("answerMode=TEXT_ONLY"));
        assertTrue(prompt.contains("只能基于 collectedEvidence 回答"));
        assertTrue(prompt.contains("answerMode=TEXT_ONLY：只说明当前资料没有直接解释"));
        assertTrue(prompt.contains("不要补充资料外内容"));
    }

    @Test
    void doesNotRewriteRealCaseAnswerThatRefusesToInventWithoutEvidence() {
        ModelClient modelClient = mock(ModelClient.class);
        FinalAnswerService service = new FinalAnswerService(modelClient);
        String insufficient = "当前资料缺少有出处的具体案例证据，也没有提供具体人物、地点、日期或事件线索，所以我不能编造一个真实案例。需要更多资料或外部搜索后，才能把某个案例完整讲出来。";
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn(insufficient);

        String answer = service.answer(request(), realCaseNoEvidencePlan(), emptyEvidence());

        assertTrue(answer.contains("不能编造"));
        verify(modelClient, times(1)).chat(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rewritesWhenStorytellingAnswerIsTooConceptual() {
        ModelClient modelClient = mock(ModelClient.class);
        FinalAnswerService service = new FinalAnswerService(modelClient);
        ChatPlan plan = new ChatPlan(
            "针对乡镇发展，举一个实际的例子，将某一个企业的故事完整地说出来",
            "请结合当前阅读内容，选取一个具体乡镇企业案例，完整讲述该企业在改革开放以来的发展故事。",
            "故事案例",
            PlannerTaskType.READING_QA,
            SubIntent.STORYTELLING_CASE,
            AnswerRequirement.storytellingCase(),
            true,
            ToolExecutionMode.MULTI_TOOL,
            List.of(),
            List.of(),
            "讲完整案例",
            3,
            "完成",
            "必须讲故事");
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("简单说，乡镇企业发展体现了政府与市场关系。")
            .thenReturn("可以用万向集团的早期发展来说明。它最初是浙江萧山宁围公社下面的小型农机修配厂，后来在鲁冠球带领下扩大生产，并在改革开放和地方政府支持下逐步市场化。这个例子说明，乡镇企业是在地方政府推动和市场机会共同作用下成长起来的。");

        String answer = service.answer(request(), plan, evidence());

        assertTrue(answer.contains("万向集团"));
        verify(modelClient).chat(contains("不合格原因"));
    }

    @Test
    void rewritesWhenModelKnowledgeAllowedButAnswerRefusesForMissingRagCase() {
        ModelClient modelClient = mock(ModelClient.class);
        FinalAnswerService service = new FinalAnswerService(modelClient);
        ChatPlan plan = taxPlan();
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("当前资料只支持概念解释，暂时不能给出有出处的具体案例。")
            .thenReturn("这里原文主要是在说，农民负担不只是正式税收，还包括费、劳务和摊派。当前资料没有逐项列出税种，但为了帮助理解，可以举几个常见例子：农业税、特产税、屠宰税更接近正式税；教育附加费、水利建设费、乡统筹、村提留更像费用；修路修渠出工、临时集资则更接近劳务和摊派。这样看，原文重点不是某一个税种，而是这些负担叠在一起压缩了农民收入。");

        String answer = service.answer(request(), plan, evidence());

        assertTrue(answer.contains("农业税"));
        verify(modelClient).chat(contains("不能直接用“当前资料只支持概念解释”拒答"));
    }

    @Test
    void rewritesWhenTaxExampleAnswerOnlyRepeatsAbstractSourcePhrases() {
        ModelClient modelClient = mock(ModelClient.class);
        FinalAnswerService service = new FinalAnswerService(modelClient);
        ChatPlan plan = taxPlan();
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("农民需要承担税、费、劳务以及各种摊派，这些构成了农民的重要负担。")
            .thenReturn("这里原文讲的是农民负担会由很多名目叠加起来。举例说，正式税里可以想到农业税、特产税、屠宰税；费用里可能有教育附加费、水利建设费、乡统筹、村提留；劳务和摊派则可能是修路修渠出工、临时集资。严格说这些不全是税，但在农民那里都会变成必须交的钱或必须出的力，这正好回到原文说的“负担”。");

        String answer = service.answer(request(), plan, evidence());

        assertTrue(answer.contains("特产税"));
        verify(modelClient).chat(contains("足够具体的税、费、劳务或摊派项目"));
    }

    @Test
    void rewritesWhenFollowUpAnswerRepeatsRecentAssistantTaxList() {
        ModelClient modelClient = mock(ModelClient.class);
        FinalAnswerService service = new FinalAnswerService(modelClient);
        ChatPlan plan = taxPlan();
        String repeated = "具体来说，农村税费负担中，农民需要面对的税、费、劳务和摊派包括：农业税、特产税、屠宰税、教育附加费、水利建设费、乡统筹、村提留、义务工、修路修渠出工、临时集资和摊派。";
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(repeated)
            .thenReturn("前面已经把名目列出来了，这次可以换成一个具体场景看：一个农户到了年底，可能先交农业税或特产税，再被要求交教育附加费、水利建设费、乡统筹、村提留；如果村里修路修渠，还可能要出义务工，临时集资也会摊到户头上。严格说，这些不全是税，但在农民感受里，都是必须拿钱或出力的负担，这正好回到原文说的“税、费、劳务和摊派叠加”。");

        String answer = service.answer(request(), plan, taxEvidenceWithRecentAssistant(repeated));

        assertTrue(answer.contains("前面已经把名目列出来了"));
        verify(modelClient).chat(contains("高度重合"));
    }

    @Test
    void rewritesWhenCompoundTermAnswerFallsBackToBroaderTerm() {
        ModelClient modelClient = mock(ModelClient.class);
        FinalAnswerService service = new FinalAnswerService(modelClient);
        ChatPlan plan = compoundTermPlan();
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("暗税包括农业税、特产税、屠宰税等，这些都会增加农民负担。")
            .thenReturn("暗税和税不一样：税是明面上有名目的征收，暗税则是名义上不叫税、实际却像税一样让农民交钱、出力或少拿收入的负担。比如地方摊派、临时集资、修路修渠的义务工，或者低价统购带来的收入损失，都更接近“暗税”这个焦点术语，而不是普通税种清单。");

        String answer = service.answer(request(), plan, evidence());

        assertTrue(answer.contains("暗税和税不一样"));
        assertTrue(answer.contains("焦点术语"));
        verify(modelClient).chat(contains("用户问的是“暗税”这个焦点术语"));
    }

    @Test
    void rewritesWhenContrastiveWhyAnswerOnlyRepeatsConsequences() {
        ModelClient modelClient = mock(ModelClient.class);
        FinalAnswerService service = new FinalAnswerService(modelClient);
        ChatPlan plan = contrastiveWhyPlan();
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("分税制之所以要执行，主要是为了调整中央和地方的财政关系。分税制推行后，县乡财政开始出现困难，农民负担加重，地方可能增加征税和收费，基层财政压力更大。")
            .thenReturn("关键是，当时选择分税制是在做一个权衡：它确实会让一些县乡财政吃紧，这是代价；但中央更急着解决的是中央财政能力过弱、宏观调控乏力的问题。换句话说，执行分税制不是因为看不到县乡困难，而是当时把重建中央财政能力和统一财政秩序放在更优先的位置。");

        String answer = service.answer(request(), plan, evidence());

        assertTrue(answer.contains("权衡"));
        assertTrue(answer.contains("代价"));
        verify(modelClient).chat(contains("不合格原因：\n用户问的是为什么在明知有问题时仍然执行"));
    }

    @Test
    void rewritesWhenExternalSearchRequiredPretendsSearchWasExecuted() {
        ModelClient modelClient = mock(ModelClient.class);
        FinalAnswerService service = new FinalAnswerService(modelClient);
        when(modelClient.chat(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("根据本次 GitHub 搜索结果，找到了 httpreading 项目。")
            .thenReturn("当前没有可用的 GitHub 或外部实时搜索工具，所以我不能声称已经搜索到了 httpreading 项目。根据历史记忆/之前记录，只能把它当作可能相关的项目线索；这不代表当前 GitHub 实时结果。");

        String answer = service.answer(request(), externalSearchPlan(), memoryOnlyEvidence());

        assertTrue(answer.contains("当前没有可用的 GitHub"));
        assertTrue(answer.contains("历史记忆"));
        verify(modelClient).chat(contains("不合格原因：\n问题需要外部搜索，但没有 externalMcpRefs"));
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(1L);
        request.setChapterIndex(1);
        request.setQuestion("为什么农民是同盟军？");
        return request;
    }

    private ChatPlan plan() {
        return new ChatPlan(
            "为什么农民是同盟军？",
            "为什么农民是同盟军？",
            "阅读问答",
            PlannerTaskType.READING_QA,
            SubIntent.STORYTELLING_CASE,
            AnswerRequirement.storytellingCase(),
            AnswerMode.CONTEXT_ANCHORED_MODEL_KNOWLEDGE,
            EvidenceStrictness.LOOSE,
            true,
            ToolExecutionMode.MULTI_TOOL,
            List.of(),
            List.of(),
            "回答阅读问题",
            3,
            "完成",
            "依据当前章节回答");
    }

    private ChatPlan taxPlan() {
        return new ChatPlan(
            "举个例子有哪些税",
            "结合当前文本中农民负担问题，说明农村税费负担中常见的具体税种、费用、劳务和摊派例子。",
            "理解辅助型例子",
            PlannerTaskType.READING_QA,
            SubIntent.CONCRETE_EXAMPLE,
            AnswerRequirement.concreteExample(),
            AnswerMode.CONTEXT_ANCHORED_MODEL_KNOWLEDGE,
            EvidenceStrictness.MEDIUM,
            true,
            ToolExecutionMode.MULTI_TOOL,
            List.of(),
            List.of(),
            "补充具体税费例子",
            3,
            "完成",
            "允许基于当前文本补充常识例子");
    }

    private ChatPlan compoundTermPlan() {
        return new ChatPlan(
            "暗税有哪些",
            "结合当前阅读内容，先界定“暗税”和“税”的区别，再回答“暗税”的具体表现或例子；不要因为焦点术语包含“税”就直接列举“税”的一般例子。",
            "理解辅助型例子",
            PlannerTaskType.READING_QA,
            SubIntent.CONCRETE_EXAMPLE,
            AnswerRequirement.concreteExample(),
            AnswerMode.CONTEXT_ANCHORED_MODEL_KNOWLEDGE,
            EvidenceStrictness.MEDIUM,
            true,
            ToolExecutionMode.MULTI_TOOL,
            List.of(),
            List.of(),
            "补充暗税例子",
            3,
            "完成",
            "焦点术语不能退回母概念");
    }

    private ChatPlan contrastiveWhyPlan() {
        return new ChatPlan(
            "那么既然分税制使得县乡财政开始出现困难，那么为什么还要执行分税制",
            "请基于当前阅读内容，回答让步型追问：在承认“分税制使得县乡财政开始出现困难”这个问题存在的前提下，为什么当时仍然要“执行分税制”；重点解释它要解决的更大问题、当时的优先目标、权衡取舍和代价，不要只复述这个问题造成的后果。",
            "让步型为什么",
            PlannerTaskType.READING_QA,
            SubIntent.CONTRASTIVE_WHY,
            new AnswerRequirement(false, false, false, false, false, true,
                true, true, true, DetailLevel.MEDIUM),
            AnswerMode.CONTEXT_ANCHORED_MODEL_KNOWLEDGE,
            EvidenceStrictness.MEDIUM,
            true,
            ToolExecutionMode.MULTI_TOOL,
            List.of(),
            List.of(),
            "解释为什么仍然执行",
            3,
            "完成",
            "先承认代价，再解释优先目标和权衡取舍");
    }

    private ChatPlan externalSearchPlan() {
        return new ChatPlan(
            "使用github搜索httpreading的项目",
            "使用 GitHub 搜索 httpreading 的项目",
            "外部工具不可用",
            PlannerTaskType.GENERAL_QA,
            SubIntent.NONE,
            AnswerRequirement.normal(),
            AnswerMode.EXTERNAL_SEARCH_REQUIRED,
            EvidenceStrictness.STRICT,
            false,
            ToolExecutionMode.NO_TOOL,
            List.of(),
            List.of(),
            "说明无法执行外部搜索",
            0,
            "无工具调用",
            "必须说明当前没有可用 GitHub/外部搜索工具，不能声称已经实时搜索。");
    }

    private ChatPlan conceptExplanationPlan() {
        return new ChatPlan(
            "机会主义是什么意思？",
            "结合当前阅读内容，解释机会主义是什么意思。",
            "概念解释",
            PlannerTaskType.READING_QA,
            SubIntent.NONE,
            new AnswerRequirement(false, false, false, false, false, false,
                true, true, false, DetailLevel.MEDIUM),
            AnswerMode.CONTEXT_ANCHORED_MODEL_KNOWLEDGE,
            EvidenceStrictness.MEDIUM,
            true,
            ToolExecutionMode.NO_TOOL,
            List.of(),
            List.of(),
            "解释概念",
            0,
            "无工具",
            "资料不足时允许公共知识辅助解释，但必须区分原文证据和补充理解。");
    }

    private ChatPlan textOnlyPlan() {
        return new ChatPlan(
            "只根据原文回答机会主义是什么意思？",
            "只根据原文回答机会主义是什么意思。",
            "严格原文",
            PlannerTaskType.READING_QA,
            SubIntent.NONE,
            AnswerRequirement.normal(),
            AnswerMode.TEXT_ONLY,
            EvidenceStrictness.STRICT,
            true,
            ToolExecutionMode.NO_TOOL,
            List.of(),
            List.of(),
            "只根据原文回答",
            0,
            "无工具",
            "只能根据当前资料回答。");
    }

    private ChatPlan realCaseNoEvidencePlan() {
        return new ChatPlan(
            "给我讲一个真实历史案例",
            "请给出一个有出处的真实历史案例，并说明它如何对应当前阅读观点。",
            "真实案例",
            PlannerTaskType.READING_QA,
            SubIntent.HISTORICAL_CASE,
            new AnswerRequirement(true, true, false, false, true, false,
                false, false, false, DetailLevel.MEDIUM),
            AnswerMode.TEXT_ONLY,
            EvidenceStrictness.STRICT,
            true,
            ToolExecutionMode.NO_TOOL,
            List.of(),
            List.of(),
            "回答真实案例",
            0,
            "无工具",
            "没有证据时不要编造真实案例。");
    }

    private CollectedEvidence evidence() {
        return new CollectedEvidence(
            List.of(new EvidenceItem("e1", "rag_current_chapter", "当前章", "农民人数众多，受压迫深。", 20, 0.9d, java.util.Map.of())),
            List.of("当前章"),
            List.of(),
            List.of(),
            List.of(),
            "已收集证据：农民人数众多，受压迫深。");
    }

    private CollectedEvidence taxEvidenceWithRecentAssistant(String assistantAnswer) {
        EvidenceItem recentDialogue = new EvidenceItem(
            "context:recent_dialogue",
            "recent_dialogue",
            "最近对话",
            "用户问题：有哪些税？\n助手回答：" + assistantAnswer,
            40,
            0.75d,
            java.util.Map.of());
        EvidenceItem currentPage = new EvidenceItem(
            "context:current_page",
            "current_page",
            "当前页",
            "农民负担包括税、费、劳务以及各种摊派。",
            10,
            1.0d,
            java.util.Map.of());
        return new CollectedEvidence(
            List.of(currentPage, recentDialogue),
            List.of("当前页"),
            List.of(),
            List.of(),
            List.of(),
            "已收集证据：\n【证据1】当前页\n农民负担包括税、费、劳务以及各种摊派。\n\n【证据2】最近对话\n用户问题：有哪些税？\n助手回答：" + assistantAnswer);
    }

    private CollectedEvidence memoryOnlyEvidence() {
        return new CollectedEvidence(
            List.of(new EvidenceItem("memory:1", "working_memory", "相关记忆", "之前记录过 httpreading 项目。", 10, 0.7d, java.util.Map.of())),
            List.of(),
            List.of("[working] 之前记录过 httpreading 项目"),
            List.of(),
            List.of(),
            "已收集证据：\n【相关记忆】之前记录过 httpreading 项目。");
    }

    private CollectedEvidence emptyEvidence() {
        return new CollectedEvidence(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");
    }
}
