package com.example.httpreading.service.profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.dto.profile.ProfileDtos.ProfileSearchResult;
import org.springframework.stereotype.Service;

@Service
public class UserProfileMcpService {
    private final UserStyleProfileService styleProfileService;
    private final ReadingUnderstandingProfileService readingProfileService;
    private final UserKnowledgeStateService knowledgeStateService;
    private final ProfileVectorIndexService vectorIndexService;
    private final ProfileMapper mapper;
    private final BookCategoryService bookCategoryService;

    public UserProfileMcpService(UserStyleProfileService styleProfileService,
                                 ReadingUnderstandingProfileService readingProfileService,
                                 UserKnowledgeStateService knowledgeStateService,
                                 ProfileVectorIndexService vectorIndexService,
                                 ProfileMapper mapper,
                                 BookCategoryService bookCategoryService) {
        this.styleProfileService = styleProfileService;
        this.readingProfileService = readingProfileService;
        this.knowledgeStateService = knowledgeStateService;
        this.vectorIndexService = vectorIndexService;
        this.mapper = mapper;
        this.bookCategoryService = bookCategoryService;
    }

    public List<Map<String, Object>> listCategories(String userId, boolean includeEmpty) {
        var style = mapper.toDto(styleProfileService.getOrCreate(userId));
        var readings = readingProfileService.listByUser(userId).stream().map(mapper::toDto).toList();
        var knowledgeStates = knowledgeStateService.listByUser(userId).stream().map(mapper::toDto).toList();
        Map<String, Object> styleCategory = new LinkedHashMap<>();
        styleCategory.put("categoryCode", "style");
        styleCategory.put("categoryName", "用户个人风格");
        styleCategory.put("description", "记录用户偏好的解释方式、回答深度、例子偏好和不喜欢的回答方式。");
        styleCategory.put("hasContent", style.summary() != null && !style.summary().isBlank());
        styleCategory.put("summary", style.summary());
        styleCategory.put("updatedAt", style.updatedAt());

        Map<String, Object> readingCategory = new LinkedHashMap<>();
        readingCategory.put("categoryCode", "reading_understanding");
        readingCategory.put("categoryName", "阅读理解画像");
        readingCategory.put("description", "记录用户对不同类别书籍的理解水平、薄弱点和类别级概览。");
        readingCategory.put("hasContent", !readings.isEmpty());
        readingCategory.put("availableBookCategories", readings.stream().map(item -> item.bookCategory()).toList());
        readingCategory.put("summary", readings.isEmpty() ? "" : "已有 "
            + String.join("、", readings.stream().map(item -> item.bookCategory()).toList()) + " 类书籍的阅读理解画像。");
        readingCategory.put("updatedAt", readings.isEmpty() ? null : readings.get(0).updatedAt());

        Map<String, Object> knowledgeCategory = new LinkedHashMap<>();
        knowledgeCategory.put("categoryCode", "knowledge_state");
        knowledgeCategory.put("categoryName", "知识点掌握状态");
        knowledgeCategory.put("description", "记录用户对具体概念、理论、人物、事件、方法和案例的掌握程度，用于推荐后续阅读和关联旧知识。");
        knowledgeCategory.put("hasContent", !knowledgeStates.isEmpty());
        knowledgeCategory.put("availableTopics", knowledgeStates.stream().map(item -> item.topic()).toList());
        knowledgeCategory.put("summary", knowledgeStates.isEmpty() ? "" : "已有 " + knowledgeStates.size() + " 个知识点掌握状态。");
        knowledgeCategory.put("updatedAt", knowledgeStates.isEmpty() ? null : knowledgeStates.get(0).updatedAt());

        return includeEmpty
            ? List.of(styleCategory, readingCategory, knowledgeCategory)
            : List.of(styleCategory, readingCategory, knowledgeCategory).stream()
                .filter(item -> Boolean.TRUE.equals(item.get("hasContent")))
                .toList();
    }

    public Map<String, Object> getCategoryDetail(String userId, String categoryCode, String bookCategory) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categoryCode", categoryCode);
        if ("style".equals(categoryCode)) {
            data.put("categoryName", "用户个人风格");
            data.put("profile", mapper.toDto(styleProfileService.getOrCreate(userId)));
            return data;
        }
        if ("reading_understanding".equals(categoryCode)) {
            data.put("categoryName", "阅读理解画像");
            if (bookCategory != null && !bookCategory.isBlank()) {
                String category = bookCategoryService.normalize(bookCategory);
                data.put("bookCategory", category);
                data.put("profile", mapper.toDto(readingProfileService.getOrCreate(userId, category)));
            } else {
                data.put("profiles", readingProfileService.listByUser(userId).stream().map(mapper::toDto).toList());
            }
            return data;
        }
        if ("knowledge_state".equals(categoryCode)) {
            data.put("categoryName", "知识点掌握状态");
            if (bookCategory != null && !bookCategory.isBlank()) {
                String category = bookCategoryService.normalize(bookCategory);
                data.put("bookCategory", category);
                data.put("knowledgeStates", knowledgeStateService.listByUserAndDomain(userId, category).stream()
                    .map(mapper::toDto)
                    .toList());
            } else {
                data.put("knowledgeStates", knowledgeStateService.listByUser(userId).stream().map(mapper::toDto).toList());
            }
            return data;
        }
        data.put("error", "unsupported categoryCode");
        return data;
    }

    public ProfileSearchResult searchRelevant(String userId,
                                              String query,
                                              String standaloneQuestion,
                                              int topK,
                                              double minScore,
                                              String categoryCode,
                                              String bookCategory) {
        String searchQuery = standaloneQuestion != null && !standaloneQuestion.isBlank() ? standaloneQuestion : query;
        return vectorIndexService.searchRelevant(
            userId,
            searchQuery,
            topK,
            minScore,
            categoryCode,
            bookCategory == null || bookCategory.isBlank() ? null : bookCategoryService.normalize(bookCategory));
    }
}
