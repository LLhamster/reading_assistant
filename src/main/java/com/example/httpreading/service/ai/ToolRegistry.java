package com.example.httpreading.service.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class ToolRegistry {
    private final Map<String, AvailableTool> tools;

    public ToolRegistry() {
        Map<String, AvailableTool> registry = new LinkedHashMap<>();
        register(registry, new AvailableTool(
            "context.get_recent_dialogue",
            "获取当前用户当前会话最近几轮对话。",
            Map.of("userId", "string", "sessionId", "string", "limit", "number"),
            true,
            false,
            false,
            true));
        register(registry, new AvailableTool(
            "context.get_current_page",
            "获取当前页面、章节、划词和划词上下文。",
            Map.of(
                "bookId", "number",
                "chapterIndex", "number",
                "chapterTitle", "string",
                "selectedText", "string",
                "selectedContext", "string"),
            true,
            false,
            false,
            true));
        register(registry, new AvailableTool(
            "memory.search",
            "检索用户长期记忆，例如阅读偏好、历史问题、学习目标。",
            Map.of("userId", "string", "sessionId", "string", "query", "string", "limit", "number"),
            true,
            false,
            false,
            true));
        register(registry, new AvailableTool(
            "profile.list_categories",
            "查询当前用户已有画像类别和摘要，包括用户风格、阅读理解状态、知识点掌握状态，只读。",
            Map.of("userId", "string", "includeEmpty", "boolean"),
            true,
            false,
            false,
            true));
        register(registry, new AvailableTool(
            "profile.get_category_detail",
            "查询用户个人风格、阅读理解画像或知识点掌握状态详情，只读。",
            Map.of("userId", "string", "categoryCode", "string", "bookCategory", "string"),
            true,
            false,
            false,
            true));
        register(registry, new AvailableTool(
            "profile.search_relevant",
            "按独立问题检索相关用户画像片段，覆盖用户风格、阅读理解状态、知识点掌握状态，用于个性化解释、推荐下一步阅读、关联用户以前学过的知识，只读。",
            Map.of("userId", "string", "query", "string", "standaloneQuestion", "string", "topK", "number",
                "minScore", "number", "categoryCode", "string", "bookCategory", "string"),
            true,
            false,
            false,
            true));
        register(registry, new AvailableTool(
            "rag.search",
            "检索书籍内容证据。",
            Map.of("bookId", "number", "chapterIndex", "number", "query", "string", "topK", "number"),
            true,
            false,
            false,
            true));
        register(registry, new AvailableTool(
            "note.search",
            "检索用户笔记。当前未启用。",
            Map.of("userId", "string", "bookId", "number", "query", "string", "limit", "number"),
            true,
            false,
            false,
            false));
        register(registry, new AvailableTool(
            "reading_progress.query",
            "查询用户阅读进度。当前未启用。",
            Map.of("userId", "string", "bookId", "number"),
            true,
            false,
            false,
            false));
        register(registry, new AvailableTool(
            "learning_plan.save",
            "保存阅读计划或学习计划。当前未启用，写操作必须确认。",
            Map.of("userId", "string", "bookId", "number", "planContent", "string"),
            false,
            true,
            true,
            false));
        tools = Collections.unmodifiableMap(new LinkedHashMap<>(registry));
    }

    public List<AvailableTool> enabledTools() {
        return tools.values().stream()
            .filter(AvailableTool::enabled)
            .toList();
    }

    public Optional<AvailableTool> enabledTool(String name) {
        AvailableTool tool = tools.get(name);
        return tool != null && tool.enabled() ? Optional.of(tool) : Optional.empty();
    }

    public Optional<AvailableTool> tool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    private void register(Map<String, AvailableTool> registry, AvailableTool tool) {
        registry.put(tool.name(), tool);
    }
}
