package com.example.httpreading.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.dto.ExternalMcpCall;
import org.springframework.stereotype.Service;

@Service
public class PlannerService {
    private static final int DEFAULT_MAX_STEPS = 5;

    public ChatPlan plan(AiChatRequest request) {
        String question = request == null ? "" : normalize(request.getQuestion());
        String lower = question.toLowerCase(Locale.ROOT);
        if (hasExplicitExternalMcpCalls(request)) {
            return explicitToolPlan(request, question);
        }
        if (isSmallTalk(lower)) {
            return new ChatPlan(
                question,
                question,
                "问题是闲聊或简单问候，不需要检索工具。",
                PlannerTaskType.SMALL_TALK,
                false,
                ToolExecutionMode.NO_TOOL,
                List.of(),
                List.of(),
                "直接回答用户。",
                1,
                "已生成回答",
                "可以自然回答；如信息不足，说明需要更多上下文。");
        }
        if (isBoundedReactCandidate(lower)) {
            return new ChatPlan(
                question,
                standalone(question),
                "问题涉及 GitHub、代码仓库或文件结构探索，路径可能需要根据工具结果调整。",
                taskType(lower),
                true,
                ToolExecutionMode.BOUNDED_REACT,
                List.of("github.search_code", "github.search_repositories", "github.get_file_contents",
                    "github.list_branches", "github.list_commits", "github.get_commit"),
                List.of(),
                question,
                DEFAULT_MAX_STEPS,
                "取得回答所需的仓库/代码证据，或遇到歧义请求确认",
                "外部 MCP 结果是 GitHub/代码分析任务的主要证据；不要脱离证据猜测。");
        }

        List<ToolStep> steps = new ArrayList<>();
        steps.add(new ToolStep("context.get_recent_dialogue", Map.of(
            "userId", request.resolvedUserId(),
            "sessionId", request.resolvedSessionId(),
            "limit", 5), "补充最近对话"));
        if (hasCurrentPage(request)) {
            steps.add(new ToolStep("context.get_current_page", Map.of(
                "bookId", request.getBookId(),
                "chapterIndex", request.getChapterIndex(),
                "chapterTitle", nullToEmpty(request.getChapterTitle()),
                "selectedText", nullToEmpty(request.getSelectedText()),
                "selectedContext", nullToEmpty(request.getSelectedContext())), "补充当前页面/划词上下文"));
        }
        if (request.isMemoryEnabled()) {
            steps.add(new ToolStep("memory.search", Map.of(
                "userId", request.resolvedUserId(),
                "sessionId", request.resolvedSessionId(),
                "query", question,
                "limit", 5), "检索相关记忆"));
        }
        if (request.isRagEnabled()) {
            steps.add(new ToolStep("rag.search", Map.of(
                "bookId", request.getBookId(),
                "chapterIndex", request.getChapterIndex(),
                "query", question,
                "topK", request.resolvedTopK()), "检索书籍证据"));
        }

        ToolExecutionMode mode = steps.isEmpty() ? ToolExecutionMode.NO_TOOL
            : steps.size() == 1 ? ToolExecutionMode.SINGLE_TOOL : ToolExecutionMode.MULTI_TOOL;
        return new ChatPlan(
            question,
            standalone(question),
            "阅读问答默认按固定顺序补充上下文、记忆和 RAG 证据。",
            PlannerTaskType.READING_QA,
            true,
            mode,
            List.of("context.get_recent_dialogue", "context.get_current_page", "memory.search", "rag.search"),
            steps,
            "回答当前阅读问题",
            Math.max(1, steps.size()),
            "固定工具计划执行完成",
            "优先使用当前页面/划词和 RAG 证据；证据不足时直接说明。");
    }

    private ChatPlan explicitToolPlan(AiChatRequest request, String question) {
        List<ToolStep> steps = request.getExternalMcpCalls().stream()
            .map(call -> new ToolStep(
                externalToolName(call),
                call.getArguments(),
                "执行前端显式 MCP 调用"))
            .toList();
        return new ChatPlan(
            question,
            standalone(question),
            "请求中包含 explicit externalMcpCalls，按兼容逻辑执行。",
            PlannerTaskType.EXPLICIT_TOOL_CALL,
            true,
            steps.size() == 1 ? ToolExecutionMode.SINGLE_TOOL : ToolExecutionMode.MULTI_TOOL,
            steps.stream().map(ToolStep::toolName).distinct().toList(),
            steps,
            question,
            steps.size(),
            "显式工具调用执行完成",
            "显式 MCP 工具结果是用户请求的直接证据。");
    }

    private boolean hasExplicitExternalMcpCalls(AiChatRequest request) {
        return request != null && request.getExternalMcpCalls() != null && !request.getExternalMcpCalls().isEmpty();
    }

    private boolean isSmallTalk(String lower) {
        String normalized = lower.replaceAll("\\s+", "");
        return List.of("你好", "您好", "hi", "hello", "嗨", "谢谢", "thanks").contains(normalized);
    }

    private boolean isBoundedReactCandidate(String lower) {
        return lower.contains("github")
            || lower.contains("仓库")
            || lower.contains("repo")
            || lower.contains("repository")
            || lower.contains("代码")
            || lower.contains("commit")
            || lower.contains("readme")
            || lower.contains("文件结构")
            || lower.contains("目录结构");
    }

    private PlannerTaskType taskType(String lower) {
        if (lower.contains("文件结构") || lower.contains("目录结构")) {
            return PlannerTaskType.FILE_STRUCTURE_EXPLORATION;
        }
        if (lower.contains("代码") || lower.contains("commit") || lower.contains("readme")) {
            return PlannerTaskType.CODE_REPOSITORY_ANALYSIS;
        }
        return PlannerTaskType.GITHUB_ANALYSIS;
    }

    private String externalToolName(ExternalMcpCall call) {
        if (call == null) {
            return "external.unknown";
        }
        return "external." + normalize(call.getServerName()) + "." + normalize(call.getToolName());
    }

    private boolean hasCurrentPage(AiChatRequest request) {
        return request != null && ((request.getSelectedText() != null && !request.getSelectedText().isBlank())
            || (request.getSelectedContext() != null && !request.getSelectedContext().isBlank()));
    }

    private String standalone(String question) {
        return question == null ? "" : question.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
