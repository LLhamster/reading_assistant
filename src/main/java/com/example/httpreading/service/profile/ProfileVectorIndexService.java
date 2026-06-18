package com.example.httpreading.service.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import com.example.httpreading.domain.profile.ProfileVectorIndexMapping;
import com.example.httpreading.domain.profile.ReadingUnderstandingProfile;
import com.example.httpreading.domain.profile.UserStyleProfile;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileSearchResult;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileVectorHit;
import com.example.httpreading.memory.storage.QdrantStore;
import com.example.httpreading.repository.ProfileGrowthEvidenceRepository;
import com.example.httpreading.repository.ProfileVectorIndexMappingRepository;
import com.example.httpreading.repository.ReadingUnderstandingProfileRepository;
import com.example.httpreading.repository.UserStyleProfileRepository;
import com.example.httpreading.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProfileVectorIndexService {
    private static final Logger log = LoggerFactory.getLogger(ProfileVectorIndexService.class);

    private final QdrantStore qdrantStore;
    private final EmbeddingService embeddingService;
    private final ProfileVectorIndexMappingRepository mappingRepository;
    private final UserStyleProfileRepository styleRepository;
    private final ReadingUnderstandingProfileRepository readingRepository;
    private final ProfileGrowthEvidenceRepository evidenceRepository;
    private final ProfileMapper mapper;

    public ProfileVectorIndexService(QdrantStore qdrantStore,
                                     EmbeddingService embeddingService,
                                     ProfileVectorIndexMappingRepository mappingRepository,
                                     UserStyleProfileRepository styleRepository,
                                     ReadingUnderstandingProfileRepository readingRepository,
                                     ProfileGrowthEvidenceRepository evidenceRepository,
                                     ProfileMapper mapper) {
        this.qdrantStore = qdrantStore;
        this.embeddingService = embeddingService;
        this.mappingRepository = mappingRepository;
        this.styleRepository = styleRepository;
        this.readingRepository = readingRepository;
        this.evidenceRepository = evidenceRepository;
        this.mapper = mapper;
    }

    public boolean upsertStyleStateVector(UserStyleProfile profile) {
        String text = profile.getSummary();
        String vectorId = "profile_state:" + profile.getUserId() + ":style";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", profile.getUserId());
        metadata.put("sourceType", "user_style_profile");
        metadata.put("sourceId", profile.getId());
        metadata.put("categoryCode", "style");
        metadata.put("status", "active");
        metadata.put("summary", text == null ? "" : text);
        return upsert(profile.getUserId(), "user_style_profile", profile.getId(), vectorId, "style_state", text, metadata);
    }

    public boolean upsertReadingStateVector(ReadingUnderstandingProfile profile) {
        String text = profile.getSummary();
        String vectorId = "reading_state:" + profile.getUserId() + ":" + profile.getBookCategory();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", profile.getUserId());
        metadata.put("sourceType", "reading_understanding_profile");
        metadata.put("sourceId", profile.getId());
        metadata.put("categoryCode", "reading_understanding");
        metadata.put("bookCategory", profile.getBookCategory());
        metadata.put("status", "active");
        metadata.put("summary", text == null ? "" : text);
        return upsert(profile.getUserId(), "reading_understanding_profile", profile.getId(), vectorId, "reading_state", text, metadata);
    }

    public boolean upsertEvidenceVector(ProfileGrowthEvidence evidence) {
        String vectorId = "profile_evidence:" + evidence.getId();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", evidence.getUserId());
        metadata.put("sourceType", "profile_growth_evidence");
        metadata.put("sourceId", evidence.getId());
        metadata.put("categoryCode", evidence.getEvidenceDomain());
        metadata.put("bookCategory", evidence.getBookCategory());
        metadata.put("evidenceType", evidence.getEvidenceType());
        metadata.put("status", evidence.getStatus());
        metadata.put("summary", evidence.getContent());
        return upsert(evidence.getUserId(), "profile_growth_evidence", evidence.getId(), vectorId, "profile_evidence",
            evidence.getContent(), metadata);
    }

    public ProfileSearchResult searchRelevant(String userId,
                                              String query,
                                              int topK,
                                              double minScore,
                                              String categoryCode,
                                              String bookCategory) {
        if (query == null || query.isBlank() || isShortFollowUp(query)) {
            return new ProfileSearchResult(false, List.of(), "画像检索问题不足，未检索");
        }
        List<Float> floats = embeddingService.embed(query);
        if (floats == null || floats.isEmpty()) {
            return new ProfileSearchResult(false, List.of(), "画像向量检索不可用");
        }
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("userId", userId);
        where.put("status", "active");
        if (categoryCode != null && !categoryCode.isBlank()) {
            where.put("categoryCode", categoryCode);
        }
        if (bookCategory != null && !bookCategory.isBlank()) {
            where.put("bookCategory", bookCategory);
        }
        List<Map<String, Object>> hits = qdrantStore.searchSimilar(
            floats.stream().map(Float::doubleValue).toList(),
            topK <= 0 ? 5 : topK,
            minScore <= 0d ? 0.72d : minScore,
            where);
        List<ProfileVectorHit> items = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            double score = hit.get("score") instanceof Number number ? number.doubleValue() : 0d;
            if (score < (minScore <= 0d ? 0.72d : minScore)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = hit.get("metadata") instanceof Map<?, ?> raw
                ? new LinkedHashMap<>((Map<String, Object>) raw)
                : new LinkedHashMap<>();
            ProfileVectorHit vectorHit = toHit(metadata, score);
            if (vectorHit != null) {
                items.add(vectorHit);
            }
        }
        if (items.isEmpty()) {
            return new ProfileSearchResult(false, List.of(), "未找到与当前问题足够相关的用户画像");
        }
        return new ProfileSearchResult(true, items, "找到相关用户画像");
    }

    private boolean upsert(String userId,
                           String sourceTable,
                           Long sourceId,
                           String vectorId,
                           String vectorType,
                           String text,
                           Map<String, Object> metadata) {
        ProfileVectorIndexMapping mapping = mappingRepository.findBySourceTableAndSourceId(sourceTable, sourceId)
            .orElseGet(ProfileVectorIndexMapping::new);
        mapping.setUserId(userId);
        mapping.setSourceTable(sourceTable);
        mapping.setSourceId(sourceId);
        mapping.setVectorId(vectorId);
        mapping.setVectorType(vectorType);
        try {
            List<Float> vector = embeddingService.embed(text == null ? "" : text);
            boolean ok = vector != null && !vector.isEmpty() && qdrantStore.addVectors(
                List.of(vector.stream().map(Float::doubleValue).toList()),
                List.of(metadata),
                List.of(vectorId));
            mapping.setStatus(ok ? "active" : "sync_failed");
            mappingRepository.save(mapping);
            return ok;
        } catch (Exception exception) {
            log.warn("画像向量同步失败 sourceTable={}, sourceId={}, reason={}",
                sourceTable, sourceId, exception.getMessage());
            mapping.setStatus("sync_failed");
            mappingRepository.save(mapping);
            return false;
        }
    }

    private ProfileVectorHit toHit(Map<String, Object> metadata, double score) {
        String sourceType = stringValue(metadata.get("sourceType"));
        Long sourceId = longValue(metadata.get("sourceId"));
        if (sourceType.isBlank() || sourceId == null) {
            return null;
        }
        String summary = stringValue(metadata.get("summary"));
        Map<String, Object> detail = new LinkedHashMap<>();
        if ("user_style_profile".equals(sourceType)) {
            styleRepository.findById(sourceId).ifPresent(profile -> detail.put("profile", mapper.toDto(profile)));
        } else if ("reading_understanding_profile".equals(sourceType)) {
            readingRepository.findById(sourceId).ifPresent(profile -> detail.put("profile", mapper.toDto(profile)));
        } else if ("profile_growth_evidence".equals(sourceType)) {
            evidenceRepository.findById(sourceId).ifPresent(evidence -> {
                detail.put("evidenceType", evidence.getEvidenceType());
                detail.put("content", evidence.getContent());
            });
        }
        detail.put("usage", "style_guidance");
        return new ProfileVectorHit(
            sourceType,
            sourceId,
            stringValue(metadata.get("categoryCode")),
            stringValue(metadata.get("bookCategory")),
            stringValue(metadata.get("evidenceType")),
            score,
            summary,
            detail);
    }

    private boolean isShortFollowUp(String query) {
        String compact = query.replaceAll("\\s+", "");
        return compact.length() <= 4
            || List.of("这个呢", "继续", "再讲讲", "还有呢", "然后呢", "呢").contains(compact);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
