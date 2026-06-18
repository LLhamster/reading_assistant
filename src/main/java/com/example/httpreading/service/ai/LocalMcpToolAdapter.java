package com.example.httpreading.service.ai;

import java.util.Map;

import com.example.httpreading.mcp.ExternalMcpCallResult;
import com.example.httpreading.mcp.ReadingMcpToolService;
import org.springframework.stereotype.Service;

@Service
public class LocalMcpToolAdapter {
    private final ReadingMcpToolService readingMcpToolService;

    public LocalMcpToolAdapter(ReadingMcpToolService readingMcpToolService) {
        this.readingMcpToolService = readingMcpToolService;
    }

    public ExternalMcpCallResult call(String logicalToolName, Map<String, Object> arguments) {
        String toolName = logicalToolName == null ? "" : logicalToolName.trim();
        Map<String, Object> safeArgs = arguments == null ? Map.of() : arguments;
        try {
            String content = switch (toolName) {
                case "rag.search", "rag_retrieve" -> readingMcpToolService.ragRetrieve(safeArgs);
                case "rag.answer", "rag_answer" -> readingMcpToolService.ragAnswer(safeArgs);
                case "memory.search", "memory_search" -> readingMcpToolService.memorySearch(safeArgs);
                case "memory.remember_turn", "memory_remember_turn" -> readingMcpToolService.memoryRememberTurn(safeArgs);
                case "profile.list_categories", "profile_list_categories" ->
                    readingMcpToolService.profileListCategories(safeArgs);
                case "profile.get_category_detail", "profile_get_category_detail" ->
                    readingMcpToolService.profileGetCategoryDetail(safeArgs);
                case "profile.search_relevant", "profile_search_relevant" ->
                    readingMcpToolService.profileSearchRelevant(safeArgs);
                case "context.build", "context_build" -> readingMcpToolService.contextBuild(safeArgs);
                case "context.get_recent_dialogue", "context_get_recent_dialogue" ->
                    readingMcpToolService.contextGetRecentDialogue(safeArgs);
                case "context.get_current_page", "context_get_current_page" ->
                    readingMcpToolService.contextGetCurrentPage(safeArgs);
                default -> null;
            };
            if (content == null) {
                return ExternalMcpCallResult.failure("local", toolName, "未知本地 MCP 工具");
            }
            return content.contains("\"ok\":false")
                ? ExternalMcpCallResult.failure("local", toolName, content)
                : ExternalMcpCallResult.success("local", toolName, content);
        } catch (Exception exception) {
            return ExternalMcpCallResult.failure("local", toolName, exception.getMessage());
        }
    }
}
