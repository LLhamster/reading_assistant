package com.example.httpreading.context.builder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ContextBuilderTest {
    @Test
    void toolResultsKeepPriorityWhenReadingContextWouldExhaustBudget() {
        ContextBuilder builder = new ContextBuilder();
        ContextPacket memory = new ContextPacket("历史失败记忆".repeat(2500), Map.of("type", "memory"));
        ContextPacket chapter = new ContextPacket("很长的章节正文".repeat(2500), Map.of("type", "current_chapter"));
        ContextPacket toolResult = new ContextPacket(
            "来源：github/list_commits\n提交一：初始化项目\n提交二：修复登录问题",
            Map.of("type", "tool_result"));

        String context = builder.build(
            "github 中 httpread 提交了多少次",
            "回答用户问题",
            List.of(memory, chapter, toolResult));

        assertTrue(context.contains("github/list_commits"));
        assertTrue(context.contains("提交一：初始化项目"));
        assertFalse(context.contains("历史失败记忆"));
    }
}
