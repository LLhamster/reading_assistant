package com.example.httpreading.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.evolution.PromptOverride;
import com.example.httpreading.mcp.ExternalMcpClientService;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlannerService {
    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final PlannerPromptBuilder promptBuilder;
    private final PlanValidator planValidator;
    private final ExternalMcpClientService externalMcpClientService;

    public PlannerService(ModelClient modelClient,
                          ObjectMapper objectMapper,
                          PlannerPromptBuilder promptBuilder,
                          PlanValidator planValidator,
                          ExternalMcpClientService externalMcpClientService) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.planValidator = planValidator;
        this.externalMcpClientService = externalMcpClientService;
    }

    public ChatPlan plan(AiChatRequest request) {
        return plan(request, PromptOverride.none());
    }

    public ChatPlan plan(AiChatRequest request, PromptOverride promptOverride) {
        AiChatRequest safeRequest = request == null ? new AiChatRequest() : request;
        String question = normalize(safeRequest.getQuestion());
        try {
            PromptOverride override = promptOverride == null ? PromptOverride.none() : promptOverride;
            String prompt = promptBuilder.build(safeRequest, override.plannerPatch());
            logPrompt("PLANNER", prompt);
            String raw = modelClient.chat(prompt);
            LlmPlanResponse response = parse(raw);
            return planValidator.validateAndConvert(response, question);
        } catch (Exception ex) {
            if (PlannerIntentClassifier.requiresUnavailableExternalTool(question)) {
                if (safeRequest.isExternalMcpEnabled() && hasMatchingExternalMcpServer(question)) {
                    log.warn("LLM Planner 失败，问题需要外部 MCP，回退到 MCP server router。reason={}", ex.getMessage());
                    return externalMcpRouterFallbackPlan(question);
                }
                log.warn("LLM Planner 失败，问题需要外部工具但当前不可用，使用 unsupportedExternalToolPlan。reason={}", ex.getMessage());
                return unsupportedExternalToolPlan(question);
            }
            log.warn("LLM Planner 失败，使用 fallbackReadingPlan。reason={}", ex.getMessage());
            return fallbackReadingPlan(safeRequest, question);
        }
    }

    private LlmPlanResponse parse(String raw) throws JsonProcessingException {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isBlank()) {
            throw new JsonProcessingException("empty planner response") {
            };
        }
        if (normalized.startsWith("```")) {
            throw new JsonProcessingException("planner response must be plain JSON") {
            };
        }
        return objectMapper.readValue(normalized, LlmPlanResponse.class);
    }

    private ChatPlan fallbackReadingPlan(AiChatRequest request, String question) {
        List<ToolStep> steps = new ArrayList<>();
        steps.add(new ToolStep("context.get_recent_dialogue", Map.of(
            "userId", request.resolvedUserId(),
            "sessionId", request.resolvedSessionId(),
            "limit", 5), "fallback：补充最近对话"));
        if (hasCurrentPage(request)) {
            steps.add(new ToolStep("context.get_current_page", Map.of(
                "bookId", request.getBookId(),
                "chapterIndex", request.getChapterIndex(),
                "chapterTitle", nullToEmpty(request.getChapterTitle()),
                "selectedText", nullToEmpty(request.getSelectedText()),
                "selectedContext", nullToEmpty(request.getSelectedContext())), "fallback：补充当前页面/划词上下文"));
        }
        if (request.isMemoryEnabled()) {
            steps.add(new ToolStep("memory.search", Map.of(
                "userId", request.resolvedUserId(),
                "sessionId", request.resolvedSessionId(),
                "query", question,
                "limit", 5), "fallback：检索相关记忆"));
        }
        if (request.isRagEnabled()) {
            steps.add(new ToolStep("rag.search", Map.of(
                "bookId", request.getBookId(),
                "chapterIndex", request.getChapterIndex(),
                "query", question,
                "topK", request.resolvedTopK()), "fallback：检索书籍证据"));
        }

        ToolExecutionMode mode = steps.isEmpty()
            ? ToolExecutionMode.NO_TOOL
            : steps.size() == 1 ? ToolExecutionMode.SINGLE_TOOL : ToolExecutionMode.MULTI_TOOL;
        return new ChatPlan(
            question,
            question,
            "LLM Planner 调用、解析或校验失败，使用安全阅读兜底计划。",
            PlannerTaskType.READING_QA,
            SubIntent.NONE,
            AnswerRequirement.normal(),
            AnswerMode.TEXT_ONLY,
            EvidenceStrictness.STRICT,
            true,
            mode,
            steps.stream().map(ToolStep::toolName).distinct().toList(),
            steps,
            "回答当前阅读问题",
            steps.size(),
            "fallback 工具计划执行完成",
            "优先使用当前页面、最近对话和 RAG 证据；如果证据不足，明确说明，不要编造；不要用“简单来说”作为固定开头。");
    }

    private ChatPlan unsupportedExternalToolPlan(String question) {
        return new ChatPlan(
            question,
            question,
            "用户请求 GitHub、网页、外部搜索或实时查询，但当前没有匹配的外部 MCP server，不能用 RAG 或记忆冒充外部搜索。",
            PlannerTaskType.GENERAL_QA,
            SubIntent.NONE,
            AnswerRequirement.normal(),
            AnswerMode.EXTERNAL_SEARCH_REQUIRED,
            EvidenceStrictness.STRICT,
            false,
            ToolExecutionMode.NO_TOOL,
            List.of(),
            List.of(),
            "说明当前无法执行外部搜索",
            0,
            "无可用外部工具，停止工具调用",
            "必须明确说明当前没有可用的 GitHub/网页/外部搜索 MCP 工具，因此没有实际执行实时搜索；不要声称已经搜索 GitHub、网页或最新结果。如果提到历史记忆，只能说是历史记录，不代表当前实时结果。");
    }

    private ChatPlan externalMcpRouterFallbackPlan(String question) {
        return new ChatPlan(
            question,
            question,
            "LLM Planner 输出无效，但用户请求外部 MCP 能力且存在可路由 server，回退到 MCP server router 再进入 ReAct。",
            PlannerTaskType.TOOL_ACTION,
            SubIntent.NONE,
            AnswerRequirement.normal(),
            AnswerMode.EXTERNAL_SEARCH_REQUIRED,
            EvidenceStrictness.STRICT,
            true,
            ToolExecutionMode.BOUNDED_REACT,
            List.of(),
            List.of(),
            "通过外部 MCP server router 选择合适 server 并获取证据",
            5,
            "ReAct agent 获得足够外部证据或需要用户确认时停止",
            "严格依据外部 MCP 工具结果回答；不要把记忆或 RAG 说成 GitHub/外部搜索结果。");
    }

    private void logPrompt(String stage, String prompt) {
        log.info("""
            ===== AI_MODEL_PROMPT_BEGIN stage={} chars={} =====
            {}
            ===== AI_MODEL_PROMPT_END stage={} =====
            """, stage, prompt == null ? 0 : prompt.length(), prompt, stage);
    }

    private boolean hasCurrentPage(AiChatRequest request) {
        return request != null && ((request.getSelectedText() != null && !request.getSelectedText().isBlank())
            || (request.getSelectedContext() != null && !request.getSelectedContext().isBlank()));
    }

    private boolean hasMatchingExternalMcpServer(String question) {
        String text = normalize(question).toLowerCase(java.util.Locale.ROOT);
        return externalMcpClientService.routableServers().stream()
            .map(server -> String.valueOf(server.get("name")).trim())
            .filter(name -> !name.isBlank())
            .filter(name -> !"self-local".equals(name))
            .anyMatch(name -> matchesExternalServer(text, name));
    }

    private boolean matchesExternalServer(String text, String serverName) {
        if (requiresGithub(text)) {
            return "github".equalsIgnoreCase(serverName);
        }
        if (requiresWebSearch(text)) {
            return "web-search".equalsIgnoreCase(serverName);
        }
        return true;
    }

    private boolean requiresGithub(String text) {
        return text.contains("github")
            || text.contains("git hub")
            || text.contains("仓库")
            || text.contains("repo")
            || text.contains("repository")
            || text.contains("commit")
            || text.contains("readme");
    }

    private boolean requiresWebSearch(String text) {
        return text.contains("网页")
            || text.contains("联网")
            || text.contains("网上")
            || text.contains("上网")
            || text.contains("新闻")
            || text.contains("最新")
            || text.contains("实时")
            || text.contains("外部搜索")
            || text.contains("搜索一下")
            || text.contains("搜一下")
            || text.contains("事实核验")
            || text.contains("核验")
            || text.contains("查证")
            || text.contains("外部资料")
            || text.contains("网上资料");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
