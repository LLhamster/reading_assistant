package com.example.httpreading.evaluation;

import java.util.ArrayList;
import java.util.List;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.AiChatResponse;
import com.example.httpreading.service.AiChatService;
import com.example.httpreading.service.ai.ChatPlan;
import com.example.httpreading.service.ai.PlannerService;

final class EvaluationComponentAdapters {
    private EvaluationComponentAdapters() {
    }

    static EvaluationReplayRunner.AgentAdapter planner(PlannerService plannerService) {
        return example -> {
            long start = System.nanoTime();
            ChatPlan plan = plannerService.plan(request(example));
            long latency = (System.nanoTime() - start) / 1_000_000;
            String server = plan.allowedTools().stream().filter(tool -> tool.startsWith("mcp.server:"))
                .map(tool -> tool.substring("mcp.server:".length())).findFirst().orElse("");
            List<String> tools = plan.toolPlan().stream().map(step -> step.toolName()).distinct().toList();
            EvaluationMetrics.RoutingPrediction route = new EvaluationMetrics.RoutingPrediction(
                plan.executionMode().name(), server, tools);
            EvaluationMetrics.ExecutionTrace trace = new EvaluationMetrics.ExecutionTrace(
                tools, List.of(), tools.size(), latency, 1, 0, 0);
            return new EvaluationReplayRunner.AgentResult(route, "", trace);
        };
    }

    static EvaluationReplayRunner.AgentAdapter endToEnd(AiChatService chatService) {
        return example -> {
            long start = System.nanoTime();
            AiChatResponse response = chatService.chat(request(example));
            long latency = (System.nanoTime() - start) / 1_000_000;
            List<String> refs = response.getExternalMcpPlanRefs() == null ? List.of() : response.getExternalMcpPlanRefs();
            List<String> tools = knownTools(refs);
            String mode = tokenAfter(refs, "PLAN_MODE ");
            String server = tokenAfter(refs, "MCP_ROUTE ");
            List<String> evidenceIds = response.getSources() == null ? List.of() : response.getSources();
            EvaluationMetrics.ExecutionTrace trace = new EvaluationMetrics.ExecutionTrace(
                tools, evidenceIds, tools.size(), latency, 0, 0, response.getAnswer() == null ? 0 : response.getAnswer().length());
            return new EvaluationReplayRunner.AgentResult(
                new EvaluationMetrics.RoutingPrediction(mode, server, tools), response.getAnswer(), trace);
        };
    }

    static AiChatRequest request(EvaluationCases.EvaluationExample example) {
        AiChatRequest request = new AiChatRequest();
        EvaluationCases.TaskInput input = example.taskInput();
        EvaluationCases.RoutingContext context = input.context();
        request.setQuestion(input.question());
        request.setBookId(context == null || context.bookId() == null ? 1L : context.bookId());
        request.setChapterIndex(context == null || context.chapterIndex() == null ? 1 : context.chapterIndex());
        if (context != null) {
            request.setUserId(context.userId());
            request.setSessionId(context.sessionId());
            request.setChapterTitle(context.chapterTitle());
            request.setSelectedText(context.selectedText());
            request.setSelectedContext(context.selectedContext());
            request.setEnableMemory(context.memoryEnabled());
            request.setEnableRag(context.ragEnabled());
        }
        if (input.readingContext() != null && request.getChapterTitle() == null) {
            request.setChapterTitle(input.readingContext().chapter());
        }
        return request;
    }

    private static List<String> knownTools(List<String> refs) {
        List<String> tools = new ArrayList<>();
        List<String> names = List.of("context.get_recent_dialogue", "context.get_current_page", "memory.search",
            "profile.list_categories", "profile.get_category_detail", "profile.search_relevant", "rag.search");
        for (String ref : refs) {
            for (String name : names) {
                if (ref != null && ref.contains(name) && !tools.contains(name)) {
                    tools.add(name);
                }
            }
        }
        return List.copyOf(tools);
    }

    private static String tokenAfter(List<String> refs, String prefix) {
        return refs.stream().filter(ref -> ref != null && ref.startsWith(prefix))
            .map(ref -> ref.substring(prefix.length()).split("[: ]", 2)[0].trim()).findFirst().orElse("");
    }
}
