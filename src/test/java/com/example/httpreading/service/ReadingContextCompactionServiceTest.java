package com.example.httpreading.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReadingContextCompactionServiceTest {
    private final ReadingContextCompactionService service = new ReadingContextCompactionService();

    @Test
    void selectsRelevantSnippetsFromLongChapter() {
        String content = """
            第一段只介绍背景，没有直接回答。

            第二段包含关键概念：向量检索用于根据问题找到相关片段，这是回答需要的证据。

            第三段继续讲无关内容。
            """.repeat(40);

        String result = service.compactChapter("向量检索是什么", 1L, 2, "检索章", content);

        assertTrue(result.contains("当前章节相关片段"));
        assertTrue(result.contains("向量检索"));
        assertTrue(result.contains("不是完整章节"));
        assertTrue(result.length() <= 2300);
    }

    @Test
    void returnsFallbackWhenNoTermsMatch() {
        String result = service.compactChapter(
            "完全不匹配的问题",
            1L,
            2,
            "章节",
            "这是第一段兜底内容。\n\n这是第二段内容。");

        assertTrue(result.contains("这是第一段兜底内容"));
        assertFalse(result.isBlank());
    }

    @Test
    void overviewQuestionUsesBeginningAndEnding() {
        String result = service.compactChapter(
            "请概括本章",
            1L,
            2,
            "章节",
            """
                开头：本章提出主题。

                中间：大量细节。

                结尾：本章总结结论。
                """);

        assertTrue(result.contains("开头：本章提出主题"));
        assertTrue(result.contains("结尾：本章总结结论"));
    }

    @Test
    void selectedExcerptUsesUserSelectionAndNearbyContext() {
        String result = service.selectedExcerpt(
            1L,
            2,
            "划词章",
            "用户划中的句子",
            "上一句。用户划中的句子。下一句。");

        assertTrue(result.contains("当前章节划词上下文"));
        assertTrue(result.contains("用户划词"));
        assertTrue(result.contains("用户划中的句子"));
        assertTrue(result.contains("上一句"));
        assertTrue(result.contains("下一句"));
    }
}
