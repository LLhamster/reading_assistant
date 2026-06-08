package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
        assertTrue(prompt.contains("开头要自然、直接回答问题"));
        assertTrue(prompt.contains("先翻译成普通人的话"));
        assertTrue(prompt.contains("简单说"));
        assertTrue(prompt.contains("不要固定标题式结构"));
        assertTrue(prompt.contains("每个原因后面都必须解释“为什么”"));
        assertTrue(prompt.contains("不要把这些结构标签展示给用户"));
        assertTrue(prompt.contains("不要在最终回答中完整展示 working memory"));
        assertTrue(prompt.contains("最多写“最近对话摘要”或“相关记忆”"));
        assertTrue(prompt.contains("不要脱离当前章节"));
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
            true,
            ToolExecutionMode.MULTI_TOOL,
            List.of(),
            List.of(),
            "回答阅读问题",
            3,
            "完成",
            "依据当前章节回答");
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
}
