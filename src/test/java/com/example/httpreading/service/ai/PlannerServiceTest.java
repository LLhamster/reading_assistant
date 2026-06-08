package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.httpreading.dto.AiChatRequest;
import org.junit.jupiter.api.Test;

class PlannerServiceTest {
    private final PlannerService plannerService = new PlannerService();

    @Test
    void readingQuestionUsesDeterministicMultiTool() {
        AiChatRequest request = request("解释一下这一段");
        request.setSelectedText("这一段");

        ChatPlan plan = plannerService.plan(request);

        assertEquals(ToolExecutionMode.MULTI_TOOL, plan.executionMode());
        assertTrue(plan.toolPlan().stream().anyMatch(step -> "rag.search".equals(step.toolName())));
        assertTrue(plan.toolPlan().stream().anyMatch(step -> "context.get_current_page".equals(step.toolName())));
    }

    @Test
    void smallTalkUsesNoTool() {
        ChatPlan plan = plannerService.plan(request("你好"));

        assertEquals(ToolExecutionMode.NO_TOOL, plan.executionMode());
        assertTrue(plan.toolPlan().isEmpty());
    }

    @Test
    void githubAnalysisUsesBoundedReact() {
        ChatPlan plan = plannerService.plan(request("帮我分析 GitHub 仓库的 README"));

        assertEquals(ToolExecutionMode.BOUNDED_REACT, plan.executionMode());
        assertTrue(plan.allowedTools().stream().allMatch(tool -> tool.startsWith("github.")));
    }

    @Test
    void ragMemoryContextDoNotDefaultToReact() {
        ChatPlan plan = plannerService.plan(request("根据当前章节和我的记忆回答"));

        assertTrue(plan.executionMode() == ToolExecutionMode.SINGLE_TOOL
            || plan.executionMode() == ToolExecutionMode.MULTI_TOOL);
    }

    private AiChatRequest request(String question) {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(1L);
        request.setChapterIndex(1);
        request.setQuestion(question);
        request.setEnableMemory(true);
        request.setEnableRag(true);
        return request;
    }
}
