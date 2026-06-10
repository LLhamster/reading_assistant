package com.example.httpreading.service.ai;

final class PlannerIntentClassifier {
    private PlannerIntentClassifier() {
    }

    static boolean requiresUnavailableExternalTool(String question) {
        String text = normalize(question);
        return containsAny(text,
            "github", "git hub", "仓库", "repo", "repository", "搜索项目", "搜索代码", "代码搜索",
            "网页搜索", "外部搜索", "联网搜索", "实时搜索", "网上搜", "上网搜", "查一下最新", "最新情况",
            "当前最新", "官网", "网址", "网页", "commit", "readme");
    }

    static boolean requiresRealtimeExternalFact(String question) {
        String text = normalize(question);
        return containsAny(text, "最新", "实时", "当前", "现在", "今天", "官网", "github", "网页", "联网", "搜索");
    }

    static boolean readingRelated(String originalQuestion, String standaloneQuestion, PlannerTaskType taskType) {
        if (taskType == PlannerTaskType.READING_QA || taskType == PlannerTaskType.NOTE_QA
            || taskType == PlannerTaskType.READING_PLAN) {
            return true;
        }
        String text = normalize(originalQuestion + " " + standaloneQuestion);
        return containsAny(text,
            "书", "章节", "这一章", "当前章", "原文", "作者", "文中", "书里", "这本书", "阅读",
            "划词", "这句话", "这一段", "上文", "下文", "这里", "这个概念", "当前页面");
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }
}
