package com.example.httpreading.service.ai;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.AgentMemoryService;
import org.springframework.stereotype.Service;

@Service
public class MemoryWriter {
    private static final float EPISODIC_IMPORTANCE_THRESHOLD = 0.7f;

    private final AgentMemoryService agentMemoryService;

    public MemoryWriter(AgentMemoryService agentMemoryService) {
        this.agentMemoryService = agentMemoryService;
    }

    public void write(AiChatRequest request, ChatPlan plan, CollectedEvidence evidence, String answer) {
        if (request == null || !request.isMemoryEnabled() || answer == null || answer.isBlank()) {
            return;
        }
        int sourceCount = evidence == null ? 0 : evidence.sources().size();
        agentMemoryService.rememberWorkingTurn(
            request.resolvedUserId(),
            request.resolvedSessionId(),
            request.getBookId(),
            request.getChapterIndex(),
            plan == null ? request.getQuestion() : plan.standaloneQuestion(),
            answer,
            sourceCount);

        float importance = estimateImportance(plan, evidence, answer);
        if (importance >= EPISODIC_IMPORTANCE_THRESHOLD || hasReadingConclusionValue(plan, evidence, answer)) {
            agentMemoryService.rememberEpisodicTurn(
                request.resolvedUserId(),
                request.resolvedSessionId(),
                request.getBookId(),
                request.getChapterIndex(),
                plan == null ? request.getQuestion() : plan.standaloneQuestion(),
                answer,
                sourceCount,
                Math.max(importance, EPISODIC_IMPORTANCE_THRESHOLD));
        }
    }

    private float estimateImportance(ChatPlan plan, CollectedEvidence evidence, String answer) {
        float score = 0.45f;
        if (plan != null && plan.taskType() == PlannerTaskType.READING_QA) {
            score += 0.1f;
        }
        if (evidence != null && evidence.items().stream()
            .anyMatch(item -> item.type().startsWith("rag") || "current_page".equals(item.type()))) {
            score += 0.15f;
        }
        if (answer.contains("结论") || answer.contains("因此") || answer.contains("关键")) {
            score += 0.1f;
        }
        return Math.min(1.0f, score);
    }

    private boolean hasReadingConclusionValue(ChatPlan plan, CollectedEvidence evidence, String answer) {
        return plan != null
            && plan.taskType() == PlannerTaskType.READING_QA
            && evidence != null
            && !evidence.sources().isEmpty()
            && answer.length() >= 80;
    }
}
