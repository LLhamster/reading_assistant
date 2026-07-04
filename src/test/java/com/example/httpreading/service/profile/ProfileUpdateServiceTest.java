package com.example.httpreading.service.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.example.httpreading.domain.profile.UserKnowledgeState;
import com.example.httpreading.domain.profile.UserStyleProfile;
import com.example.httpreading.dto.profile.ProfileDtos.KnowledgeStatePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ManualStyleProfileRequest;
import com.example.httpreading.dto.profile.ProfileDtos.NewEvidencePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdatePatch;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdateRequest;
import com.example.httpreading.dto.profile.ProfileDtos.ReadingProfilePatch;
import com.example.httpreading.dto.profile.ProfileDtos.StyleProfilePatch;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.ProfileUpdateLogRepository;
import com.example.httpreading.service.AgentMemoryService;
import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.profile.ProfilePatchExtractor.ExtractedProfilePatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

class ProfileUpdateServiceTest {
    private ProfileUserResolver userResolver;
    private AgentMemoryService agentMemoryService;
    private UserStyleProfileService styleProfileService;
    private ReadingUnderstandingProfileService readingProfileService;
    private UserKnowledgeStateService knowledgeStateService;
    private ProfileGrowthEvidenceService evidenceService;
    private ProfileVectorIndexService vectorIndexService;
    private ProfileUpdateLogRepository updateLogRepository;
    private BooksRepository booksRepository;
    private ModelClient modelClient;
    private ProfilePatchExtractor patchExtractor;
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
        knowledgeStateService = mock(UserKnowledgeStateService.class);
        evidenceService = mock(ProfileGrowthEvidenceService.class);
        vectorIndexService = mock(ProfileVectorIndexService.class);
        updateLogRepository = mock(ProfileUpdateLogRepository.class);
        booksRepository = mock(BooksRepository.class);
        modelClient = mock(ModelClient.class);
        patchExtractor = mock(ProfilePatchExtractor.class);
        BookCategoryService bookCategoryService = new BookCategoryService(booksRepository, modelClient);

        when(userResolver.resolve(any(), any())).thenReturn("u1");
        when(agentMemoryService.recentImportantEpisodic(anyString(), anyInt(), anyDouble()))
            .thenReturn(memories());
        when(styleProfileService.findByUserId("u1")).thenReturn(Optional.of(styleProfile()));
        when(styleProfileService.getOrCreate("u1")).thenReturn(styleProfile());
        when(styleProfileService.updateStyleProfile(anyString(), any())).thenReturn(styleProfile());
        when(styleProfileService.replaceStyleProfile(anyString(), any())).thenReturn(styleProfile());
        when(readingProfileService.listByUser("u1")).thenReturn(List.of());
        when(readingProfileService.updateReadingProfile(anyString(), anyString(), any(), any()))
            .thenReturn(readingProfile());
        when(knowledgeStateService.listByUser("u1")).thenReturn(List.of());
        when(knowledgeStateService.isAllowedKnowledgeType(anyString())).thenAnswer(invocation ->
            List.of("concept", "person", "event", "theory", "method", "case", "other").contains(invocation.getArgument(0)));
        when(knowledgeStateService.isAllowedLevel(anyString())).thenAnswer(invocation ->
            List.of("unknown", "exposed", "learning", "basic_understood", "well_understood").contains(invocation.getArgument(0)));
        when(knowledgeStateService.updateKnowledgeState(anyString(), any(), any()))
            .thenReturn(knowledgeState());
        when(vectorIndexService.upsertStyleStateVector(any())).thenReturn(true);
        when(vectorIndexService.upsertReadingStateVector(any())).thenReturn(true);
        when(vectorIndexService.upsertKnowledgeStateVector(any())).thenReturn(true);
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
            knowledgeStateService,
            evidenceService,
            vectorIndexService,
            updateLogRepository,
            bookCategoryService,
            patchExtractor,
            profileJson,
            profileMapper);
    }

    @Test
    void evidenceDoesNotUseRequestBookAsDefaultSource() {
        when(patchExtractor.extract(any(), any(), any(), any(), anyString(), anyString()))
            .thenReturn(extracted(styleEvidenceWithoutSource()));

        var response = service.updateProfileManually(request());

        assertEquals("success", response.status());
        ArgumentCaptor<ProfileGrowthEvidence> evidence = ArgumentCaptor.forClass(ProfileGrowthEvidence.class);
        verify(evidenceService).saveEvidence(evidence.capture());
        assertEquals(null, evidence.getValue().getRelatedBookId());
        assertEquals(null, evidence.getValue().getRelatedBookTitle());
        assertEquals(null, evidence.getValue().getRelatedChapterIndex());
    }

    @Test
    void evidenceUsesOnlyExplicitPatchSource() {
        when(patchExtractor.extract(any(), any(), any(), any(), anyString(), anyString()))
            .thenReturn(extracted(readingEvidenceWithSource()));

        var response = service.updateProfileManually(request());

        assertEquals("success", response.status());
        ArgumentCaptor<ProfileGrowthEvidence> evidence = ArgumentCaptor.forClass(ProfileGrowthEvidence.class);
        verify(evidenceService).saveEvidence(evidence.capture());
        assertEquals(99L, evidence.getValue().getRelatedBookId());
        assertEquals("明确来源书", evidence.getValue().getRelatedBookTitle());
        assertEquals(3, evidence.getValue().getRelatedChapterIndex());
    }

    @Test
    void knowledgePatchDoesNotUseRequestBookAsDefaultSource() {
        when(patchExtractor.extract(any(), any(), any(), any(), anyString(), anyString()))
            .thenReturn(extracted(knowledgePatchWithoutSource()));

        var response = service.updateProfileManually(request());

        assertEquals("success", response.status());
        ArgumentCaptor<KnowledgeStatePatch> patch = ArgumentCaptor.forClass(KnowledgeStatePatch.class);
        verify(knowledgeStateService).updateKnowledgeState(anyString(), patch.capture(), any());
        assertEquals(null, patch.getValue().relatedBookId());
        assertEquals(null, patch.getValue().relatedBookTitle());
        assertEquals(null, patch.getValue().relatedChapterIndex());
        verify(vectorIndexService).upsertKnowledgeStateVector(any());
    }

    @Test
    void extractorParseFailedDoesNotWrite() {
        when(patchExtractor.extract(any(), any(), any(), any(), anyString(), anyString())).thenReturn(null);

        var response = service.updateProfileManually(request());

        assertEquals("profile_patch_parse_failed", response.status());
        verify(evidenceService, never()).saveEvidence(any());
        verify(updateLogRepository, never()).save(any());
    }

    @Test
    void readingNoteAllowsProfileUpdateWhenConversationMemoryIsEmpty() {
        when(agentMemoryService.recentImportantEpisodic(anyString(), anyInt(), anyDouble()))
            .thenReturn(List.of());
        ProfileGrowthEvidence note = new ProfileGrowthEvidence();
        note.setId(31L);
        note.setUserId("u1");
        note.setEvidenceType("reading_note");
        note.setContent("用户笔记：差序格局像以自己为中心扩散的水波纹。");
        note.setImportance(0.8d);
        note.setUpdatedAt(LocalDateTime.now());
        when(evidenceService.recentReadingNotes("u1", 30)).thenReturn(List.of(note));
        when(patchExtractor.extract(any(), any(), any(), any(), anyString(), anyString()))
            .thenReturn(extracted(styleEvidenceWithoutSource()));

        var response = service.updateProfileManually(request());

        assertEquals("success", response.status());
        assertEquals(1, response.usedMemoryCount());
        verify(patchExtractor).extract(any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void manualStyleUpdateWritesStyleAndSyncsVector() {
        var response = service.updateStyleManually(styleRequest());

        assertEquals("success", response.status());
        assertEquals("u1", response.userId());
        ArgumentCaptor<StyleProfilePatch> patch = ArgumentCaptor.forClass(StyleProfilePatch.class);
        verify(styleProfileService).replaceStyleProfile(anyString(), patch.capture());
        assertEquals("直接讲故事", patch.getValue().explanationStyle());
        assertEquals("详细但不啰嗦", patch.getValue().preferredDepth());
        assertEquals(true, patch.getValue().prefersExamples());
        assertEquals(false, patch.getValue().prefersStepByStep());
        assertEquals(List.of("教科书式", "模板化"), patch.getValue().avoidance());
        verify(vectorIndexService).upsertStyleStateVector(any());
        verify(evidenceService, never()).saveEvidence(any());
    }

    @Test
    void manualStyleUpdateUsesGuestSessionResolver() {
        service.updateStyleManually(new ManualStyleProfileRequest(
            null, "session-1", "", "", false, false, false, List.of(), ""));

        verify(userResolver).resolve(null, "session-1");
    }

    @Test
    void manualStyleVectorFailureReturnsWarningWithoutRollback() {
        when(vectorIndexService.upsertStyleStateVector(any())).thenReturn(false);

        var response = service.updateStyleManually(styleRequest());

        assertEquals("success_with_warning", response.status());
        assertEquals(List.of("sync_failed:style"), response.warnings());
        verify(styleProfileService).replaceStyleProfile(anyString(), any());
    }

    private ProfileUpdateRequest request() {
        return new ProfileUpdateRequest("u1", "s1", 44L, 1, "社会学", "请完整举例");
    }

    private ManualStyleProfileRequest styleRequest() {
        return new ManualStyleProfileRequest(
            "u1",
            "s1",
            "直接讲故事",
            "详细但不啰嗦",
            true,
            true,
            false,
            List.of("教科书式", "模板化"),
            "用户喜欢完整案例和背景解释。");
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

    private UserKnowledgeState knowledgeState() {
        UserKnowledgeState state = new UserKnowledgeState();
        state.setId(3L);
        state.setUserId("u1");
        state.setDomain("社会学");
        state.setTopic("差序格局");
        state.setKnowledgeType("concept");
        state.setLevel("learning");
        state.setConfidence(0.6d);
        state.setUpdatedAt(LocalDateTime.now());
        return state;
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

    private ExtractedProfilePatch extracted(ProfileUpdatePatch patch) {
        return new ExtractedProfilePatch(patch, "{}");
    }

    private ProfileUpdatePatch styleEvidenceWithoutSource() {
        return new ProfileUpdatePatch(
            null,
            List.of(),
            List.of(),
            List.of(new NewEvidencePatch("style", "explanation_preference", null,
                "用户要求完整案例。", 0.8, null, null, null)),
            "更新风格画像");
    }

    private ProfileUpdatePatch readingEvidenceWithSource() {
        return new ProfileUpdatePatch(
            null,
            List.of(new ReadingProfilePatch("社会学", "learning", "case_mapping",
                List.of(), List.of("概念边界不稳定"), List.of(), List.of(), List.of(),
                "类别级概览", 0.1)),
            List.of(),
            List.of(new NewEvidencePatch("reading_understanding", "concept_confusion", "社会学",
                "用户对概念边界仍不稳定。", 0.8, 99L, "明确来源书", 3)),
            "更新阅读理解画像");
    }

    private ProfileUpdatePatch knowledgePatchWithoutSource() {
        return new ProfileUpdatePatch(
            null,
            List.of(),
            List.of(new KnowledgeStatePatch("社会学", "差序格局", "concept", "learning",
                0.1, "", "用户仍需要通过具体案例理解差序格局的边界。",
                "用户正在理解差序格局。", null, null, null)),
            List.of(),
            "更新知识点状态");
    }
}
