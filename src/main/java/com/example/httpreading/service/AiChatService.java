package com.example.httpreading.service;

import java.util.Map;

import com.example.httpreading.context.manager.ContextManager;
import com.example.httpreading.context.model.Context;
import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.AiChatResponse;
import com.example.httpreading.mcp.ExternalMcpAgentService;
import com.example.httpreading.service.RagService.RagAnswer;
import com.example.httpreading.service.ai.ChatPlan;
import com.example.httpreading.service.ai.CollectedEvidence;
import com.example.httpreading.service.ai.EvidenceAggregator;
import com.example.httpreading.service.ai.FinalAnswerService;
import com.example.httpreading.service.ai.McpToolOrchestrator;
import com.example.httpreading.service.ai.MemoryWriter;
import com.example.httpreading.service.ai.PlannerService;
import com.example.httpreading.service.ai.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiChatService {
    private static final Logger logger = LoggerFactory.getLogger(AiChatService.class);

    private final RagService ragService;
    private final ContextManager contextManager;
    private final ExternalMcpAgentService externalMcpAgentService;
    private final PlannerService plannerService;
    private final McpToolOrchestrator mcpToolOrchestrator;
    private final EvidenceAggregator evidenceAggregator;
    private final FinalAnswerService finalAnswerService;
    private final MemoryWriter memoryWriter;

    public AiChatService(RagService ragService,
                         ContextManager contextManager,
                         ExternalMcpAgentService externalMcpAgentService,
                         PlannerService plannerService,
                         McpToolOrchestrator mcpToolOrchestrator,
                         EvidenceAggregator evidenceAggregator,
                         FinalAnswerService finalAnswerService,
                         MemoryWriter memoryWriter) {
        this.ragService = ragService;
        this.contextManager = contextManager;
        this.externalMcpAgentService = externalMcpAgentService;
        this.plannerService = plannerService;
        this.mcpToolOrchestrator = mcpToolOrchestrator;
        this.evidenceAggregator = evidenceAggregator;
        this.finalAnswerService = finalAnswerService;
        this.memoryWriter = memoryWriter;
    }

    public AiChatResponse chat(AiChatRequest request) {
        String userId = request.resolvedUserId();
        String sessionId = request.resolvedSessionId();
        Context context = contextManager.getOrCreateContext(userId, sessionId);
        Integer contextId = context.getContextId();

        contextManager.putVariable(contextId, "bookId", request.getBookId(), "request", 0.9d);
        contextManager.putVariable(contextId, "chapterIndex", request.getChapterIndex(), "request", 0.8d);
        contextManager.putVariable(contextId, "lastQuestion", request.getQuestion(), "user", 1.0d);

        ChatPlan plan = plannerService.plan(request);
        ToolExecutionResult toolExecution = request.hasConfirmationId()
            ? mcpToolOrchestrator.resume(request, plan)
            : executeFreshPlan(request, plan);

        if (toolExecution.needsConfirmation()) {
            contextManager.appendSnapshot(contextId, userId, "user", request.getQuestion(), requestMetadata(request));
            contextManager.appendSnapshot(contextId, userId, "assistant", toolExecution.guidance(), Map.of(
                "type", "mcp_confirmation"));
            AiChatResponse response = new AiChatResponse(toolExecution.guidance(), java.util.List.of());
            response.setStatus("needs_confirmation");
            response.setInteraction(toolExecution.interaction());
            response.setContextId(contextId);
            response.setSessionId(sessionId);
            response.setMemoryRefs(java.util.List.of());
            response.setExternalMcpRefs(toolExecution.results().stream()
                .map(com.example.httpreading.mcp.ExternalMcpCallResult::ref)
                .toList());
            response.setExternalMcpPlanRefs(toolExecution.planRefs());
            return response;
        }

        CollectedEvidence evidence = evidenceAggregator.aggregate(request, plan, toolExecution);
        String answer = finalAnswerService.answer(request, plan, evidence);
        logger.info("AI Plan-and-Execute answer generated. planMode={}, evidenceCount={}",
            plan.executionMode(), evidence.items().size());

        contextManager.appendSnapshot(contextId, userId, "user", request.getQuestion(), requestMetadata(request));
        contextManager.appendSnapshot(contextId, userId, "assistant", answer, Map.of(
            "sourceCount", evidence.sources().size(),
            "memoryCount", evidence.memoryRefs().size(),
            "planMode", plan.executionMode().name()));
        memoryWriter.write(request, plan, evidence, answer);

        AiChatResponse response = new AiChatResponse(answer, evidence.sources());
        response.setContextId(contextId);
        response.setSessionId(sessionId);
        response.setMemoryRefs(evidence.memoryRefs());
        response.setExternalMcpRefs(evidence.externalMcpRefs());
        response.setExternalMcpPlanRefs(evidence.externalMcpPlanRefs());
        response.setStatus("completed");
        return response;
    }

    public RagAnswer getAnswer(Long bookId, Integer chapterIndex, String question) {
        return ragService.answer(bookId, chapterIndex, question, 3, null);
    }

    private ToolExecutionResult executeFreshPlan(AiChatRequest request, ChatPlan plan) {
        externalMcpAgentService.cancelPending(request);
        return mcpToolOrchestrator.execute(request, plan);
    }

    private Map<String, Object> requestMetadata(AiChatRequest request) {
        return Map.of(
            "bookId", request.getBookId(),
            "chapterIndex", request.getChapterIndex());
    }
}
