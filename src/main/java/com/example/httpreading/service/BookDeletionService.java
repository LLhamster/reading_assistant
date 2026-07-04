package com.example.httpreading.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.httpreading.api.ErrorCode;
import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import com.example.httpreading.domain.profile.UserKnowledgeState;
import com.example.httpreading.domain.user.Reading;
import com.example.httpreading.domain.user.ReadingAnnotation;
import com.example.httpreading.memory.storage.QdrantStore;
import com.example.httpreading.mq.BookIndexProducer;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.BookshelfRepository;
import com.example.httpreading.repository.ChaptersRepository;
import com.example.httpreading.repository.ChunkRepository;
import com.example.httpreading.repository.ProfileGrowthEvidenceRepository;
import com.example.httpreading.repository.ProfileVectorIndexMappingRepository;
import com.example.httpreading.repository.ReadingAnnotationRepository;
import com.example.httpreading.repository.ReadingRepository;
import com.example.httpreading.repository.UserKnowledgeStateRepository;
import com.example.httpreading.service.profile.ProfileVectorIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class BookDeletionService {
    private static final Logger log = LoggerFactory.getLogger(BookDeletionService.class);

    private final BooksRepository booksRepository;
    private final ChaptersRepository chaptersRepository;
    private final BookshelfRepository bookshelfRepository;
    private final ReadingRepository readingRepository;
    private final ReadingAnnotationRepository annotationRepository;
    private final ProfileGrowthEvidenceRepository evidenceRepository;
    private final UserKnowledgeStateRepository knowledgeStateRepository;
    private final ProfileVectorIndexMappingRepository vectorMappingRepository;
    private final ProfileVectorIndexService profileVectorIndexService;
    private final ChunkRepository chunkRepository;
    private final QdrantStore qdrantStore;
    private final RedisTemplate<String, Object> redisTemplate;
    private final DocumentStorageService storageService;
    private final BookIndexProducer bookIndexProducer;
    private final ObjectMapper objectMapper;

    public BookDeletionService(BooksRepository booksRepository,
                               ChaptersRepository chaptersRepository,
                               BookshelfRepository bookshelfRepository,
                               ReadingRepository readingRepository,
                               ReadingAnnotationRepository annotationRepository,
                               ProfileGrowthEvidenceRepository evidenceRepository,
                               UserKnowledgeStateRepository knowledgeStateRepository,
                               ProfileVectorIndexMappingRepository vectorMappingRepository,
                               ProfileVectorIndexService profileVectorIndexService,
                               ChunkRepository chunkRepository,
                               QdrantStore qdrantStore,
                               RedisTemplate<String, Object> redisTemplate,
                               DocumentStorageService storageService,
                               BookIndexProducer bookIndexProducer,
                               ObjectMapper objectMapper) {
        this.booksRepository = booksRepository;
        this.chaptersRepository = chaptersRepository;
        this.bookshelfRepository = bookshelfRepository;
        this.readingRepository = readingRepository;
        this.annotationRepository = annotationRepository;
        this.evidenceRepository = evidenceRepository;
        this.knowledgeStateRepository = knowledgeStateRepository;
        this.vectorMappingRepository = vectorMappingRepository;
        this.profileVectorIndexService = profileVectorIndexService;
        this.chunkRepository = chunkRepository;
        this.qdrantStore = qdrantStore;
        this.redisTemplate = redisTemplate;
        this.storageService = storageService;
        this.bookIndexProducer = bookIndexProducer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void deleteBook(Long bookId) {
        booksRepository.findById(bookId)
            .orElseThrow(() -> ErrorCode.BOOK_NOT_FOUND.toException("书籍不存在: " + bookId));

        List<Reading> progressRecords = readingRepository.findByBookId(bookId);
        List<ReadingAnnotation> annotations = annotationRepository.findByBookId(bookId);
        List<ProfileGrowthEvidence> evidences =
            new java.util.ArrayList<>(evidenceRepository.findByRelatedBookId(bookId));
        for (ReadingAnnotation annotation : annotations) {
            evidenceRepository.findByRelatedAnnotationId(annotation.getId())
                .filter(evidence -> evidences.stream().noneMatch(item -> item.getId().equals(evidence.getId())))
                .ifPresent(evidences::add);
        }
        Set<Long> evidenceIds = new HashSet<>();
        for (ProfileGrowthEvidence evidence : evidences) {
            evidenceIds.add(evidence.getId());
            vectorMappingRepository.deleteBySourceTableAndSourceId(
                "profile_growth_evidence", evidence.getId());
        }

        List<UserKnowledgeState> knowledgeStates = knowledgeStateRepository.findByRelatedBookId(bookId);
        for (UserKnowledgeState state : knowledgeStates) {
            state.setRelatedBookId(null);
            state.setRelatedBookTitle(null);
            state.setRelatedChapterIndex(null);
            state.setSourceEvidenceIds(removeEvidenceIds(state.getSourceEvidenceIds(), evidenceIds));
        }
        knowledgeStateRepository.saveAll(knowledgeStates);

        evidenceRepository.deleteAll(evidences);
        annotationRepository.deleteByBookId(bookId);
        readingRepository.deleteByBookId(bookId);
        bookshelfRepository.deleteByBookId(bookId);
        chaptersRepository.deleteByBookId(bookId);
        booksRepository.deleteById(bookId);
        bookIndexProducer.sendDeleteMessage(bookId);

        Runnable externalCleanup = () -> cleanupExternalData(
            bookId, progressRecords, evidences, knowledgeStates);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    externalCleanup.run();
                }
            });
        } else {
            externalCleanup.run();
        }
    }

    private void cleanupExternalData(Long bookId,
                                     List<Reading> progressRecords,
                                     List<ProfileGrowthEvidence> evidences,
                                     List<UserKnowledgeState> knowledgeStates) {
        safeCleanup(bookId, "画像证据向量", () -> {
            if (!evidences.isEmpty()) {
                qdrantStore.deleteVectors(evidences.stream()
                    .map(item -> "profile_evidence:" + item.getId()).toList());
            }
        });
        safeCleanup(bookId, "书籍关联记忆向量",
            () -> qdrantStore.deleteVectorsByFilter(Map.of("bookId", bookId)));
        safeCleanup(bookId, "知识状态向量", () ->
            knowledgeStates.forEach(profileVectorIndexService::upsertKnowledgeStateVector));
        safeCleanup(bookId, "RAG chunks", () -> chunkRepository.deleteByBookId(bookId));
        safeCleanup(bookId, "阅读进度缓存", () -> progressRecords.forEach(progress -> {
            redisTemplate.delete("progress:userId:" + progress.getUserId() + ":bookId:" + bookId);
            redisTemplate.delete("lock:progress:userId:" + progress.getUserId() + ":bookId:" + bookId);
        }));
        safeCleanup(bookId, "书籍文件", () -> storageService.deleteBookFiles(bookId));
    }

    private String removeEvidenceIds(String json, Set<Long> deletedIds) {
        if (json == null || json.isBlank() || deletedIds.isEmpty()) {
            return json;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return json;
            }
            ArrayNode kept = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                Long id = item.canConvertToLong() ? item.longValue() : null;
                if (id == null || !deletedIds.contains(id)) {
                    kept.add(item);
                }
            }
            return objectMapper.writeValueAsString(kept);
        } catch (Exception e) {
            log.warn("清理知识状态证据引用失败，保留原值: {}", e.getMessage());
            return json;
        }
    }

    private void safeCleanup(Long bookId, String target, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("书籍外部数据清理失败 - bookId:{}, target:{}, reason:{}",
                bookId, target, e.getMessage(), e);
        }
    }
}
