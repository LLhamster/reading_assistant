package com.example.httpreading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.httpreading.api.BusinessException;
import com.example.httpreading.domain.entity.Books;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

class BookDeletionServiceTest {
    private BooksRepository booksRepository;
    private ChaptersRepository chaptersRepository;
    private BookshelfRepository bookshelfRepository;
    private ReadingRepository readingRepository;
    private ReadingAnnotationRepository annotationRepository;
    private ProfileGrowthEvidenceRepository evidenceRepository;
    private UserKnowledgeStateRepository knowledgeStateRepository;
    private ProfileVectorIndexMappingRepository vectorMappingRepository;
    private ProfileVectorIndexService profileVectorIndexService;
    private ChunkRepository chunkRepository;
    private QdrantStore qdrantStore;
    private RedisTemplate<String, Object> redisTemplate;
    private DocumentStorageService storageService;
    private BookIndexProducer bookIndexProducer;
    private BookDeletionService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        booksRepository = mock(BooksRepository.class);
        chaptersRepository = mock(ChaptersRepository.class);
        bookshelfRepository = mock(BookshelfRepository.class);
        readingRepository = mock(ReadingRepository.class);
        annotationRepository = mock(ReadingAnnotationRepository.class);
        evidenceRepository = mock(ProfileGrowthEvidenceRepository.class);
        knowledgeStateRepository = mock(UserKnowledgeStateRepository.class);
        vectorMappingRepository = mock(ProfileVectorIndexMappingRepository.class);
        profileVectorIndexService = mock(ProfileVectorIndexService.class);
        chunkRepository = mock(ChunkRepository.class);
        qdrantStore = mock(QdrantStore.class);
        redisTemplate = mock(RedisTemplate.class);
        storageService = mock(DocumentStorageService.class);
        bookIndexProducer = mock(BookIndexProducer.class);
        service = new BookDeletionService(
            booksRepository, chaptersRepository, bookshelfRepository, readingRepository,
            annotationRepository, evidenceRepository, knowledgeStateRepository,
            vectorMappingRepository, profileVectorIndexService, chunkRepository, qdrantStore,
            redisTemplate, storageService, bookIndexProducer, new ObjectMapper());
    }

    @Test
    void deletesBookDataAndCleansExternalStores() {
        Books book = new Books();
        book.setId(46L);
        Reading progress = new Reading();
        progress.setUserId(7L);
        progress.setBookId(46L);
        ReadingAnnotation annotation = new ReadingAnnotation();
        annotation.setId(8L);
        annotation.setBookId(46L);
        ProfileGrowthEvidence evidence = new ProfileGrowthEvidence();
        evidence.setId(12L);
        evidence.setRelatedBookId(46L);
        UserKnowledgeState knowledge = new UserKnowledgeState();
        knowledge.setId(15L);
        knowledge.setRelatedBookId(46L);
        knowledge.setRelatedBookTitle("乡土中国");
        knowledge.setRelatedChapterIndex(3);
        knowledge.setSourceEvidenceIds("[12,99]");

        when(booksRepository.findById(46L)).thenReturn(Optional.of(book));
        when(readingRepository.findByBookId(46L)).thenReturn(List.of(progress));
        when(annotationRepository.findByBookId(46L)).thenReturn(List.of(annotation));
        when(evidenceRepository.findByRelatedBookId(46L)).thenReturn(List.of(evidence));
        when(evidenceRepository.findByRelatedAnnotationId(8L)).thenReturn(Optional.of(evidence));
        when(knowledgeStateRepository.findByRelatedBookId(46L)).thenReturn(List.of(knowledge));

        service.deleteBook(46L);

        verify(evidenceRepository).deleteAll(List.of(evidence));
        verify(annotationRepository).deleteByBookId(46L);
        verify(readingRepository).deleteByBookId(46L);
        verify(bookshelfRepository).deleteByBookId(46L);
        verify(chaptersRepository).deleteByBookId(46L);
        verify(booksRepository).deleteById(46L);
        assertThat(knowledge.getRelatedBookId()).isNull();
        assertThat(knowledge.getRelatedBookTitle()).isNull();
        assertThat(knowledge.getRelatedChapterIndex()).isNull();
        assertThat(knowledge.getSourceEvidenceIds()).isEqualTo("[99]");
        verify(vectorMappingRepository)
            .deleteBySourceTableAndSourceId("profile_growth_evidence", 12L);

        verify(qdrantStore).deleteVectors(List.of("profile_evidence:12"));
        verify(qdrantStore).deleteVectorsByFilter(Map.of("bookId", 46L));
        verify(profileVectorIndexService).upsertKnowledgeStateVector(knowledge);
        verify(chunkRepository).deleteByBookId(46L);
        verify(redisTemplate).delete("progress:userId:7:bookId:46");
        verify(redisTemplate).delete("lock:progress:userId:7:bookId:46");
        verify(storageService).deleteBookFiles(46L);
        verify(bookIndexProducer).sendDeleteMessage(46L);
    }

    @Test
    void missingBookIsRejectedWithoutCleanup() {
        when(booksRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteBook(404L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("书籍不存在");

        verify(booksRepository, never()).deleteById(any());
        verify(storageService, never()).deleteBookFiles(any());
    }
}
