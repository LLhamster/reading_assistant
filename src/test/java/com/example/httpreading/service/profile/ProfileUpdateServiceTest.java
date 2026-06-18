package com.example.httpreading.service.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import com.example.httpreading.domain.profile.ReadingUnderstandingProfile;
import com.example.httpreading.domain.profile.UserStyleProfile;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdateRequest;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.ProfileUpdateLogRepository;
import com.example.httpreading.service.AgentMemoryService;
import com.example.httpreading.service.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class ProfileUpdateServiceTest {
    private ProfileUserResolver userResolver;
    private AgentMemoryService agentMemoryService;
    private UserStyleProfileService styleProfileService;
    private ReadingUnderstandingProfileService readingProfileService;
    private ProfileGrowthEvidenceService evidenceService;
    private ProfileVectorIndexService vectorIndexService;
    private ProfileUpdateLogRepository updateLogRepository;
    private BooksRepository booksRepository;
    private ModelClient modelClient;
    private ProfileUpdateService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ProfileJson profileJson = new ProfileJson(objectMapper);
        ProfileMapper profileMapper = new ProfileMapper(profileJson);

        userResolver = mock(ProfileUserResolver.class);
        agentMemoryService = mock(AgentMemoryService.class);
        styleProfileService = mock(UserStyleProfileService.class);
        readingProfileService = mock(ReadingUnderstandingProfileService.class);
        evidenceService = mock(ProfileGrowthEvidenceService.class);
        vectorIndexService = mock(ProfileVectorIndexService.class);
        updateLogRepository = mock(ProfileUpdateLogRepository.class);
        booksRepository = mock(BooksRepository.class);
        modelClient = mock(ModelClient.class);
        BookCategoryService bookCategoryService = new BookCategoryService(booksRepository, modelClient);

        when(userResolver.resolve(any(), any())).thenReturn("u1");
        when(agentMemoryService.recentImportantEpisodic(anyString(), anyInt(), anyDouble()))
            .thenReturn(memories());
        when(styleProfileService.findByUserId("u1")).thenReturn(Optional.of(styleProfile()));
        when(styleProfileService.getOrCreate("u1")).thenReturn(styleProfile());
        when(styleProfileService.updateStyleProfile(anyString(), any())).thenReturn(styleProfile());
        when(readingProfileService.listByUser("u1")).thenReturn(List.of());
        when(readingProfileService.updateReadingProfile(anyString(), anyString(), any(), any()))
            .thenReturn(readingProfile());
        when(vectorIndexService.upsertStyleStateVector(any())).thenReturn(true);
        when(vectorIndexService.upsertReadingStateVector(any())).thenReturn(true);
        when(vectorIndexService.upsertEvidenceVector(any())).thenReturn(true);
        when(evidenceService.saveEvidence(any())).thenAnswer((Answer<ProfileGrowthEvidence>) invocation -> {
            ProfileGrowthEvidence evidence = invocation.getArgument(0);
            evidence.setId(10L);
            return evidence;
        });

        service = new ProfileUpdateService(
            userResolver,
            agentMemoryService,
            styleProfileService,
            readingProfileService,
            evidenceService,
            vectorIndexService,
            updateLogRepository,
            bookCategoryService,
            booksRepository,
            modelClient,
            objectMapper,
            profileJson,
            profileMapper);
    }

    @Test
    void stringEvidenceArrayFailsThenRetryCanSucceed() {
        when(modelClient.chat(anyString())).thenReturn(invalidStringEvidence(), validPatch());

        var response = service.updateProfileManually(request());

        assertEquals("success", response.status());
        verify(modelClient, org.mockito.Mockito.times(2)).chat(anyString());
        verify(evidenceService).saveEvidence(any());
    }

    @Test
    void jsonPatchReadingPatchIsRejected() {
        when(modelClient.chat(anyString())).thenReturn(jsonPatchStyle(), jsonPatchStyle());

        var response = service.updateProfileManually(request());

        assertEquals("profile_patch_parse_failed", response.status());
        verify(evidenceService, never()).saveEvidence(any());
        verify(styleProfileService, never()).updateStyleProfile(anyString(), any());
    }

    @Test
    void markdownCodeFenceJsonCanBeExtracted() {
        when(modelClient.chat(anyString())).thenReturn("```json\n" + validPatch() + "\n```");

        var response = service.updateProfileManually(request());

        assertEquals("success", response.status());
        verify(modelClient).chat(anyString());
    }

    @Test
    void retryLegalJsonUpdatesProfile() {
        when(modelClient.chat(anyString())).thenReturn("{not json", validPatch());

        var response = service.updateProfileManually(request());

        assertEquals("success", response.status());
        verify(modelClient, org.mockito.Mockito.times(2)).chat(anyString());
        verify(readingProfileService).updateReadingProfile(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void retryStillIllegalReturnsParseFailedWithoutWriting() {
        when(modelClient.chat(anyString())).thenReturn("{not json", invalidStringEvidence());

        var response = service.updateProfileManually(request());

        assertEquals("profile_patch_parse_failed", response.status());
        verify(evidenceService, never()).saveEvidence(any());
        verify(updateLogRepository, never()).save(any());
    }

    private ProfileUpdateRequest request() {
        return new ProfileUpdateRequest("u1", "s1", 44L, 1, "社会学", "请完整举例");
    }

    private UserStyleProfile styleProfile() {
        UserStyleProfile profile = new UserStyleProfile();
        profile.setId(1L);
        profile.setUserId("u1");
        profile.setAvoidance("[]");
        profile.setConfidence(0.5d);
        profile.setUpdatedAt(LocalDateTime.now());
        return profile;
    }

    private ReadingUnderstandingProfile readingProfile() {
        ReadingUnderstandingProfile profile = new ReadingUnderstandingProfile();
        profile.setId(2L);
        profile.setUserId("u1");
        profile.setBookCategory("社会学");
        profile.setConfidence(0.6d);
        profile.setEvidenceCount(1);
        profile.setUpdatedAt(LocalDateTime.now());
        return profile;
    }

    private List<com.example.httpreading.memory.model.MemoryItem> memories() {
        return java.util.stream.IntStream.range(0, 6)
            .mapToObj(i -> new com.example.httpreading.memory.model.MemoryItem(
                "m" + i,
                "用户要求完整案例和背景解释 " + i,
                "episodic",
                "u1",
                LocalDateTime.now().minusMinutes(i),
                0.8f,
                java.util.Map.of()))
            .toList();
    }

    private String validPatch() {
        return """
            {
              "stylePatch": null,
              "readingPatches": [
                {
                  "bookCategory": "社会学",
                  "understandingLevel": "learning",
                  "learningStage": "case_mapping",
                  "strengths": ["能识别回答是否空泛"],
                  "weaknesses": ["抽象概念需要具体案例支撑"],
                  "preferredExplanation": ["直接进入故事"],
                  "backgroundNeeds": ["历史背景"],
                  "typicalQuestions": ["能否举一个实际例子"],
                  "summary": "用户阅读社会学类内容时需要完整案例和背景解释。",
                  "confidenceDelta": 0.1
                }
              ],
              "newEvidence": [
                {
                  "evidenceDomain": "reading_understanding",
                  "evidenceType": "case_need",
                  "bookCategory": "社会学",
                  "content": "用户阅读社会学类内容时需要完整案例和背景解释。",
                  "importance": 0.82,
                  "relatedBookId": 44,
                  "relatedBookTitle": "测试书",
                  "relatedChapterIndex": 1
                }
              ],
              "summary": "更新社会学阅读理解画像"
            }
            """;
    }

    private String invalidStringEvidence() {
        return """
            {
              "stylePatch": null,
              "readingPatches": [],
              "newEvidence": ["用户喜欢完整案例"],
              "summary": "bad"
            }
            """;
    }

    private String jsonPatchStyle() {
        return """
            {
              "stylePatch": null,
              "readingPatches": [
                {
                  "op": "replace",
                  "path": "/readingProgress",
                  "value": "80%",
                  "bookId": 44,
                  "chapterIndex": 1,
                  "question": "q",
                  "answer": "a"
                }
              ],
              "newEvidence": [],
              "summary": "bad"
            }
            """;
    }
}
