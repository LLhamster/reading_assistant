package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.mcp.ExternalMcpCallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EvidenceAggregatorTest {
    private final EvidenceAggregator aggregator = new EvidenceAggregator(new ObjectMapper());

    @Test
    void normalizesAndSortsEvidenceByPriority() {
        ToolExecutionResult execution = ToolExecutionResult.completed(List.of(
            ExternalMcpCallResult.success("local", "memory.search",
                "{\"ok\":true,\"data\":[{\"id\":\"m1\",\"content\":\"working memory\",\"memoryType\":\"working\",\"importance\":0.9}]}"),
            ExternalMcpCallResult.success("local", "rag.search",
                "{\"ok\":true,\"data\":[{\"id\":\"c1\",\"chapterIndex\":1,\"sourceRef\":\"当前章\",\"content\":\"rag current\"},{\"id\":\"c2\",\"chapterIndex\":2,\"sourceRef\":\"其他章\",\"content\":\"rag other\"}]}"),
            ExternalMcpCallResult.success("local", "context.get_current_page",
                "{\"ok\":true,\"data\":{\"chapterTitle\":\"章节\",\"content\":\"page evidence\"}}"),
            ExternalMcpCallResult.success("github", "search_code", "external evidence")),
            List.of("PLAN_MODE MULTI_TOOL"));

        CollectedEvidence evidence = aggregator.aggregate(request(), plan(), execution);

        assertEquals("current_page", evidence.items().get(0).type());
        assertEquals("rag_current_chapter", evidence.items().get(1).type());
        assertEquals("rag_other_chapter", evidence.items().get(2).type());
        assertTrue(evidence.sources().contains("当前阅读页面划词：章节"));
        assertTrue(evidence.memoryRefs().contains("[working] working memory"));
        assertTrue(evidence.externalMcpRefs().contains("OK github/search_code"));
    }

    @Test
    void filtersEmptyErrorAndDuplicateEvidence() {
        ToolExecutionResult execution = ToolExecutionResult.completed(List.of(
            ExternalMcpCallResult.failure("local", "rag.search", "timeout"),
            ExternalMcpCallResult.success("local", "rag.search",
                "{\"ok\":true,\"data\":[{\"id\":\"c1\",\"chapterIndex\":1,\"sourceRef\":\"A\",\"content\":\"same\"},{\"id\":\"c2\",\"chapterIndex\":1,\"sourceRef\":\"B\",\"content\":\"same\"}]}")),
            List.of());

        CollectedEvidence evidence = aggregator.aggregate(request(), plan(), execution);

        assertEquals(1, evidence.items().size());
        assertFalse(evidence.externalMcpRefs().isEmpty());
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(1L);
        request.setChapterIndex(1);
        request.setQuestion("q");
        return request;
    }

    private ChatPlan plan() {
        return new ChatPlan("q", "q", "test", PlannerTaskType.READING_QA, true,
            ToolExecutionMode.MULTI_TOOL, List.of(), List.of(), "goal", 5, "stop", "guidance");
    }
}
