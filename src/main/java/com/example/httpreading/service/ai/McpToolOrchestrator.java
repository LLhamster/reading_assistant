package com.example.httpreading.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.ExternalMcpCall;
import com.example.httpreading.mcp.ExternalMcpAgentResult;
import com.example.httpreading.mcp.ExternalMcpAgentService;
import com.example.httpreading.mcp.ExternalMcpCallResult;
import com.example.httpreading.mcp.ExternalMcpClientService;
import org.springframework.stereotype.Service;

@Service
public class McpToolOrchestrator {
    private final LocalMcpToolAdapter localMcpToolAdapter;
    private final ExternalMcpClientService externalMcpClientService;
    private final ExternalMcpAgentService externalMcpAgentService;

    public McpToolOrchestrator(LocalMcpToolAdapter localMcpToolAdapter,
                               ExternalMcpClientService externalMcpClientService,
                               ExternalMcpAgentService externalMcpAgentService) {
        this.localMcpToolAdapter = localMcpToolAdapter;
        this.externalMcpClientService = externalMcpClientService;
        this.externalMcpAgentService = externalMcpAgentService;
    }

    public ToolExecutionResult execute(AiChatRequest request, ChatPlan plan) {
        if (plan == null || plan.executionMode() == ToolExecutionMode.NO_TOOL) {
            return ToolExecutionResult.completed(List.of(), planRefs(plan, "NO_TOOL"));
        }
        if (plan.executionMode() == ToolExecutionMode.BOUNDED_REACT) {
            if (!request.isExternalMcpEnabled()) {
                return ToolExecutionResult.completed(List.of(), planRefs(plan, "BOUNDED_REACT_SKIPPED external MCP disabled"));
            }
            ExternalMcpAgentResult agentResult = externalMcpAgentService.execute(request, planningContext(plan));
            return fromAgentResult(agentResult, plan);
        }

        List<ExternalMcpCallResult> results = new ArrayList<>();
        List<String> refs = planRefs(plan, plan.executionMode().name());
        int maxSteps = Math.min(plan.maxSteps(), plan.toolPlan().size());
        for (int i = 0; i < maxSteps; i++) {
            ToolStep step = plan.toolPlan().get(i);
            if (!plan.allowedTools().isEmpty() && !plan.allowedTools().contains(step.toolName())) {
                results.add(ExternalMcpCallResult.failure("orchestrator", step.toolName(),
                    "工具不在 allowedTools 中"));
                refs.add("PLAN_STEP " + (i + 1) + " BLOCKED " + step.toolName());
                continue;
            }
            ExternalMcpCallResult result = executeStep(request, step);
            results.add(result);
            refs.add("PLAN_STEP " + (i + 1) + " " + (result.isOk() ? "OK " : "FAIL ") + step.toolName());
        }
        if (plan.toolPlan().size() > maxSteps) {
            refs.add("PLAN_LIMIT_REACHED maxSteps=" + plan.maxSteps());
        }
        return ToolExecutionResult.completed(results, refs);
    }

    public ToolExecutionResult resume(AiChatRequest request, ChatPlan plan) {
        ExternalMcpAgentResult agentResult = externalMcpAgentService.resume(request);
        return fromAgentResult(agentResult, plan);
    }

    private ExternalMcpCallResult executeStep(AiChatRequest request, ToolStep step) {
        if (step.toolName().startsWith("external.")) {
            ExternalMcpCall call = toExternalCall(step);
            return externalMcpClientService.callTool(call);
        }
        return localMcpToolAdapter.call(step.toolName(), step.arguments());
    }

    private ExternalMcpCall toExternalCall(ToolStep step) {
        String[] parts = step.toolName().split("\\.", 3);
        ExternalMcpCall call = new ExternalMcpCall();
        call.setServerName(parts.length >= 2 ? parts[1] : "");
        call.setToolName(parts.length >= 3 ? parts[2] : "");
        call.setArguments(step.arguments());
        return call;
    }

    private ToolExecutionResult fromAgentResult(ExternalMcpAgentResult agentResult, ChatPlan plan) {
        List<String> refs = new ArrayList<>(planRefs(plan, "BOUNDED_REACT"));
        refs.addAll(agentResult.getRefs());
        return new ToolExecutionResult(
            agentResult.getResults(),
            refs,
            agentResult.getGuidance(),
            agentResult.getStatus(),
            agentResult.getInteraction());
    }

    private String planningContext(ChatPlan plan) {
        return """
            originalQuestion: %s
            standaloneQuestion: %s
            taskGoal: %s
            allowedTools: %s
            maxSteps: %d
            stopCondition: %s
            planningReason: %s
            """.formatted(
            plan.originalQuestion(),
            plan.standaloneQuestion(),
            plan.taskGoal(),
            plan.allowedTools(),
            plan.maxSteps(),
            plan.stopCondition(),
            plan.planningReason());
    }

    private List<String> planRefs(ChatPlan plan, String status) {
        if (plan == null) {
            return List.of();
        }
        return new ArrayList<>(List.of(
            "PLAN_MODE " + plan.executionMode(),
            "PLAN_TASK " + plan.taskType(),
            "PLAN_STATUS " + status,
            "PLAN_REASON " + concise(plan.planningReason())));
    }

    private String concise(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }
}
