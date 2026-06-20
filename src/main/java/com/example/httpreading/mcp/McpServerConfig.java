package com.example.httpreading.mcp;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfig {

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(McpJsonMapper mcpJsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(mcpJsonMapper)
            .mcpEndpoint("/mcp")
            .build();
    }

    @Bean
    public ServletRegistrationBean<?> mcpServlet(HttpServletStreamableServerTransportProvider transportProvider) {
        ServletRegistrationBean<?> registration = new ServletRegistrationBean<>(transportProvider, "/mcp");
        registration.setName("mcpServlet");
        registration.setLoadOnStartup(1);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider transportProvider,
                                       ReadingMcpToolService tools) {
        return McpServer.sync(transportProvider)
            .serverInfo("http-reading-mcp", "0.0.1-SNAPSHOT")
            .capabilities(ServerCapabilities.builder().tools(true).build())
            .toolCall(tool("memory_search",
                    "Search user working, episodic, and semantic memories.",
                    properties(
                        property("userId", "string", "User id. Defaults to default_user."),
                        property("sessionId", "string", "Session id. Defaults to default_session."),
                        property("query", "string", "Memory search query."),
                        property("limit", "integer", "Maximum result count. Defaults to 5.")),
                    List.of("query")),
                (exchange, request) -> result(tools.memorySearch(request.arguments())))
            .toolCall(tool("memory_remember_turn",
                    "Store a reading conversation turn into memory.",
                    properties(
                        property("userId", "string", "User id. Defaults to default_user."),
                        property("sessionId", "string", "Session id. Defaults to default_session."),
                        property("bookId", "integer", "Book id."),
                        property("chapterIndex", "integer", "Chapter index."),
                        property("question", "string", "User question."),
                        property("answer", "string", "Assistant answer.")),
                    List.of("question", "answer")),
                (exchange, request) -> result(tools.memoryRememberTurn(request.arguments())))
            .toolCall(tool("profile_list_categories",
                    "List user profile categories and summaries, including style, reading understanding, and knowledge state categories.",
                    properties(
                        property("userId", "string", "User id."),
                        property("includeEmpty", "boolean", "Whether to include empty profile categories. Defaults to false.")),
                    List.of("userId")),
                (exchange, request) -> result(tools.profileListCategories(request.arguments())))
            .toolCall(tool("profile_get_category_detail",
                    "Get detailed user profile content for style guidance, reading understanding, or knowledge state. Use it when a specific profile category or book category is needed.",
                    properties(
                        property("userId", "string", "User id."),
                        property("categoryCode", "string", "Profile category code, such as style, reading_understanding, or knowledge_state."),
                        property("bookCategory", "string", "Optional fixed book category filter, such as 社会学、技术、历史、文学、哲学、心理学、英语、职业成长、经济学、其他.")),
                    List.of("userId", "categoryCode")),
                (exchange, request) -> result(tools.profileGetCategoryDetail(request.arguments())))
            .toolCall(tool("profile_search_relevant",
                    "Search semantically relevant user profile snippets by standalone question. Covers user style, reading understanding state, and knowledge mastery state; useful for personalized explanation, next-reading recommendation, and connecting the question to the user's previous knowledge.",
                    properties(
                        property("userId", "string", "User id."),
                        property("query", "string", "Original user query. Use standaloneQuestion when available."),
                        property("standaloneQuestion", "string", "Standalone rewritten question for profile retrieval; required for short follow-ups like 这个呢 or 继续."),
                        property("topK", "integer", "Maximum profile hit count. Defaults to 5."),
                        property("minScore", "number", "Minimum semantic score. Defaults to 0.72."),
                        property("categoryCode", "string", "Optional category filter, such as style, reading_understanding, or knowledge_state."),
                        property("bookCategory", "string", "Optional fixed book category filter.")),
                    List.of("userId")),
                (exchange, request) -> result(tools.profileSearchRelevant(request.arguments())))
            .toolCall(tool("rag_retrieve",
                    "Retrieve relevant book chunks from the local reading RAG index.",
                    properties(
                        property("bookId", "integer", "Optional book id filter."),
                        property("chapterIndex", "integer", "Optional chapter index filter."),
                        property("query", "string", "Retrieval query."),
                        property("topK", "integer", "Maximum chunk count. Defaults to 3.")),
                    List.of("query")),
                (exchange, request) -> result(tools.ragRetrieve(request.arguments())))
            .toolCall(tool("rag_answer",
                    "Answer a reading question using local RAG evidence.",
                    properties(
                        property("bookId", "integer", "Optional book id filter."),
                        property("chapterIndex", "integer", "Optional chapter index filter."),
                        property("question", "string", "Question to answer."),
                        property("topK", "integer", "Maximum chunk count. Defaults to 3.")),
                    List.of("question")),
                (exchange, request) -> result(tools.ragAnswer(request.arguments())))
            .toolCall(tool("context_build",
                    "Build a structured GSSC context from session history and supplied packets.",
                    properties(
                        property("userId", "string", "User id. Defaults to default_user."),
                        property("sessionId", "string", "Session id. Defaults to default_session."),
                        property("question", "string", "Current user question."),
                        property("systemInstructions", "string", "Optional system instructions."),
                        property("packets", "array", "Optional context packets.")),
                    List.of("question")),
                (exchange, request) -> result(tools.contextBuild(request.arguments())))
            .toolCall(tool("context_get_recent_dialogue",
                    "Return recent dialogue for a user/session from local context.",
                    properties(
                        property("userId", "string", "User id. Defaults to default_user."),
                        property("sessionId", "string", "Session id. Defaults to default_session."),
                        property("limit", "integer", "Maximum dialogue turns. Defaults to 5.")),
                    List.of()),
                (exchange, request) -> result(tools.contextGetRecentDialogue(request.arguments())))
            .toolCall(tool("context_get_current_page",
                    "Return selected text/current page context supplied by the reading client.",
                    properties(
                        property("bookId", "integer", "Book id."),
                        property("chapterIndex", "integer", "Chapter index."),
                        property("chapterTitle", "string", "Chapter title."),
                        property("selectedText", "string", "Selected text."),
                        property("selectedContext", "string", "Surrounding selected context.")),
                    List.of()),
                (exchange, request) -> result(tools.contextGetCurrentPage(request.arguments())))
            .build();
    }

    @SafeVarargs
    private static Map<String, Object> properties(Map.Entry<String, Object>... entries) {
        return Map.ofEntries(entries);
    }

    private static Map.Entry<String, Object> property(String name, String type, String description) {
        return Map.entry(name, Map.of("type", type, "description", description));
    }

    private static Tool tool(String name,
                             String description,
                             Map<String, Object> properties,
                             List<String> required) {
        return Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(new JsonSchema("object", properties, required, false, null, null))
            .build();
    }

    private static CallToolResult result(String json) {
        return CallToolResult.builder()
            .addContent(new McpSchema.TextContent(json))
            .isError(json != null && json.contains("\"ok\":false"))
            .build();
    }
}
