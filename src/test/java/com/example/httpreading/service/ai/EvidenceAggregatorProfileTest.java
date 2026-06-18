package com.example.httpreading.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.mcp.ExternalMcpCallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EvidenceAggregatorProfileTest {
    private final EvidenceAggregator aggregator = new EvidenceAggregator(new ObjectMapper());

    @Test
    void matchedFalseProfileSearchDoesNotBecomeEvidence() {
        ToolExecutionResult execution = new ToolExecutionResult(
            List.of(ExternalMcpCallResult.success("local", "profile.search_relevant",
                "{\"ok\":true,\"data\":{\"matched\":false,\"items\":[],\"message\":\"no\"}}")),
            List.<String>of(),
            null,
            null,
            null);

        CollectedEvidence evidence = aggregator.aggregate(request(), null, execution);

        assertTrue(evidence.items().isEmpty());
        assertTrue(evidence.sources().isEmpty());
    }

    @Test
    void profileSearchEvidenceIsStyleGuidanceNotSource() {
        ToolExecutionResult execution = new ToolExecutionResult(
            List.of(ExternalMcpCallResult.success("local", "profile.search_relevant",
                """
                    {"ok":true,"data":{"matched":true,"items":[{"sourceType":"user_style_profile","sourceId":1,"categoryCode":"style","score":0.9,"summary":"用户偏好通俗例子。","detail":{"usage":"style_guidance"}}]}}
                    """)),
            List.<String>of(),
            null,
            null,
            null);

        CollectedEvidence evidence = aggregator.aggregate(request(), null, execution);

        assertEquals(1, evidence.items().size());
        assertEquals("profile_search_result", evidence.items().get(0).type());
        assertEquals("style_guidance", evidence.items().get(0).metadata().get("usage"));
        assertTrue(evidence.sources().isEmpty());
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setBookId(1L);
        request.setChapterIndex(1);
        request.setQuestion("按我能理解的方式讲");
        return request;
    }
}
