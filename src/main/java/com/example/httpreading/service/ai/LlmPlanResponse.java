package com.example.httpreading.service.ai;

import java.util.List;

public record LlmPlanResponse(String taskType,
                              String taskTypeReason,
                              String subIntent,
                              String standaloneQuestion,
                              Boolean dependsOnContext,
                              String executionMode,
                              List<String> allowedTools,
                              List<LlmToolStep> toolPlan,
                              String taskGoal,
                              Integer maxSteps,
                              String stopCondition,
                              String answerGuidance,
                              String answerMode,
                              String evidenceStrictness,
                              LlmAnswerRequirement answerRequirement,
                              String planningReason) {
    public LlmPlanResponse {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        toolPlan = toolPlan == null ? List.of() : List.copyOf(toolPlan);
    }
}
