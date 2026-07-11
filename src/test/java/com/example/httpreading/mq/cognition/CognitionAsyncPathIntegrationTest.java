package com.example.httpreading.mq.cognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.httpreading.domain.cognition.ConceptAlias;
import com.example.httpreading.domain.cognition.ConceptCandidateStatus;
import com.example.httpreading.domain.cognition.ConceptResolutionDecision;
import com.example.httpreading.domain.cognition.ConceptStatus;
import com.example.httpreading.domain.cognition.ConfidenceLevel;
import com.example.httpreading.domain.cognition.KnowledgeConcept;
import com.example.httpreading.repository.cognition.ConceptAliasRepository;
import com.example.httpreading.repository.cognition.ConceptCandidateRecordRepository;
import com.example.httpreading.repository.cognition.ConceptMergeRelationRepository;
import com.example.httpreading.repository.cognition.ConceptResolutionRecordRepository;
import com.example.httpreading.repository.cognition.ConceptSourceRepository;
import com.example.httpreading.repository.cognition.KnowledgeConceptRepository;
import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.cognition.ConceptCandidateExtractor;
import com.example.httpreading.service.cognition.ConceptConfidenceScorer;
import com.example.httpreading.service.cognition.ConceptMatcher;
import com.example.httpreading.service.cognition.ConceptModelAnalyzer;
import com.example.httpreading.service.cognition.ConceptNormalizationService;
import com.example.httpreading.service.cognition.ConceptResolverService;
import com.example.httpreading.service.cognition.NoopConceptSimilarityRecallPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;NON_KEYWORDS=USER"
})
@Import({
    CognitionAsyncPathIntegrationTest.JsonTestConfig.class,
    ConceptResolverConsumer.class,
    ConceptResolverService.class,
    ConceptCandidateExtractor.class,
    ConceptMatcher.class,
    ConceptModelAnalyzer.class,
    ConceptConfidenceScorer.class,
    ConceptNormalizationService.class,
    NoopConceptSimilarityRecallPort.class
})
class CognitionAsyncPathIntegrationTest {
    private static final String REALISTIC_CHAPTER_CONTENT = """
        本节讨论一个人在群体力量变化时如何调整自己的公开态度。作者并不是在罗列普通情绪，
        而是在比较三种相近但并不完全相同的现象：机会主义、政治投机和暂时观望。机会主义更强调
        按眼前利害改变立场；政治投机更强调把公共行动当作谋利工具；暂时观望则可能只是信息不足。
        因此，同一句话附近经常同时出现这些词，读者需要根据划词和上下文判断作者真正指向的概念。
        """;

    @Autowired
    private ConceptResolverConsumer consumer;

    @Autowired
    private KnowledgeConceptRepository conceptRepository;

    @Autowired
    private ConceptAliasRepository aliasRepository;

    @Autowired
    private ConceptSourceRepository sourceRepository;

    @Autowired
    private ConceptResolutionRecordRepository resolutionRepository;

    @Autowired
    private ConceptCandidateRecordRepository candidateRecordRepository;

    @Autowired
    private ConceptMergeRelationRepository mergeRelationRepository;

    @Autowired
    private ConceptNormalizationService normalizationService;

    @MockBean
    private ModelClient modelClient;

    @TestConfiguration
    static class JsonTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @BeforeEach
    void setUp() {
        candidateRecordRepository.deleteAll();
        resolutionRepository.deleteAll();
        sourceRepository.deleteAll();
        aliasRepository.deleteAll();
        mergeRelationRepository.deleteAll();
        conceptRepository.deleteAll();
        when(modelClient.chat(anyString())).thenAnswer(invocation -> modelJson(invocation.getArgument(0)));
    }

    @Test
    void selectedTextExactMatchFlowsThroughConsumerAndLinksExistingConcept() {
        KnowledgeConcept concept = confirmedConcept("机会主义");

        consumer.handleLearningEvent(realisticEvent(
            "evt-async-selected",
            "机会主义",
            "为什么作者在这里把这种态度说得这么负面？",
            """
                有些人原先公开反对农会，看到农会势力扩大以后，又急忙表示支持。作者把这种随风向改变立场、
                只计算个人安全和利益的态度称为机会主义，并把它和真正理解农民运动的人区别开来。
                """,
            "群众运动中的立场变化",
            """
                [user] 上一段说的不是单纯害怕吗？
                [assistant] 资料里更强调立场随力量变化而改变。
                [user] 那这里的机会主义是不是一种政治态度？
                [assistant] 是，它和政治投机相近，但更强调按眼前利害调整立场。
                """));

        var resolution = resolutionRepository.findByEventId("evt-async-selected").orElseThrow();
        assertEquals(ConceptResolutionDecision.LINK_EXISTING, resolution.getDecision());
        assertEquals(ConfidenceLevel.HIGH, resolution.getConfidenceLevel());
        assertEquals(concept.getId(), resolution.getMatchedConceptId());
        assertEquals("机会主义", resolution.getPrimaryConceptName());
        assertTrue(sourceRepository.findByConceptIdOrderByCreatedAtDesc(concept.getId()).stream()
            .anyMatch(source -> "evt-async-selected".equals(source.getSourceRef())));
        assertTrue(candidateRecordRepository.findByEventIdOrderByCreatedAtDesc("evt-async-selected").isEmpty());
    }

    @Test
    void questionAliasFlowsThroughConsumerAndResolvesCanonicalConcept() {
        KnowledgeConcept concept = confirmedConcept("机会主义");
        ConceptAlias alias = new ConceptAlias();
        alias.setConceptId(concept.getId());
        alias.setAliasName("见风使舵");
        alias.setNormalizedAliasName(normalizationService.normalize("见风使舵"));
        alias.setSource("TEST");
        alias.setConfidence(0.95d);
        aliasRepository.save(alias);

        consumer.handleLearningEvent(realisticEvent(
            "evt-async-alias",
            null,
            "见风使舵是什么意思？",
            """
                这段不是在说普通的犹豫。上下文里有人先反对，后来又突然请求加入，像是看哪边势力强就倒向哪边。
                用户用“见风使舵”来概括这种行为，和概念库中的机会主义高度接近。
                """,
            "态度转向与利益计算",
            """
                [user] 他是不是在批评一种投机心理？
                [assistant] 是，重点是看形势变化后迅速改变态度。
                [user] 那这能不能叫见风使舵？
                [assistant] 可以，它可以作为机会主义的通俗说法。
                """));

        var resolution = resolutionRepository.findByEventId("evt-async-alias").orElseThrow();
        assertEquals(ConceptResolutionDecision.LINK_EXISTING, resolution.getDecision());
        assertEquals(concept.getId(), resolution.getMatchedConceptId());
        assertEquals("机会主义", resolution.getPrimaryConceptName());
        assertTrue(resolution.getReason().contains("alias"));
    }

    @Test
    void newHighConfidenceConceptFlowsThroughConsumerAndCreatesCandidateConcept() {
        consumer.handleLearningEvent(realisticEvent(
            "evt-async-new-candidate",
            "规训权力",
            "这里不是在说普通管理吧，为什么说它会进入人的日常行为？",
            """
                作者不是只说命令和惩罚，而是说学校、工厂、家庭里的细小规则会长期塑造人的身体习惯和自我评价。
                “规训权力”在这里指一种不一定总是外显为暴力、却能持续安排行动方式的权力形式。
                """,
            "权力与规训",
            """
                [user] 前面说权力不只是法律和命令，我没太懂。
                [assistant] 它也可能通过日常制度塑造人的行为。
                [user] 所以规训权力不是一次性的强迫？
                [assistant] 对，规训权力更像持续的训练、分类和评价。
                """));

        var resolution = resolutionRepository.findByEventId("evt-async-new-candidate").orElseThrow();
        assertEquals(ConceptResolutionDecision.CREATE_CANDIDATE, resolution.getDecision());
        assertEquals(ConfidenceLevel.HIGH, resolution.getConfidenceLevel());
        assertNotNull(resolution.getMatchedConceptId());

        KnowledgeConcept concept = conceptRepository.findById(resolution.getMatchedConceptId()).orElseThrow();
        assertEquals("规训权力", concept.getCanonicalName());
        assertEquals(ConceptStatus.CANDIDATE, concept.getStatus());

        var records = candidateRecordRepository.findByEventIdOrderByCreatedAtDesc("evt-async-new-candidate");
        assertEquals(1, records.size());
        assertEquals(ConceptCandidateStatus.PENDING, records.get(0).getStatus());
    }

    @Test
    void genericQuestionWithoutContextFlowsThroughConsumerAndOnlyStoresNoConceptResolution() {
        consumer.handleLearningEvent(new LearningEvent(
            "evt-async-no-context",
            "test-user",
            1L,
            1,
            "test-session",
            "这里是什么意思",
            null,
            null,
            null,
            null,
            null));

        var resolution = resolutionRepository.findByEventId("evt-async-no-context").orElseThrow();
        assertEquals(ConceptResolutionDecision.NO_CONCEPT, resolution.getDecision());
        assertEquals(0.0d, resolution.getConfidence());
        assertTrue(conceptRepository.findAll().isEmpty());
        assertTrue(candidateRecordRepository.findByEventIdOrderByCreatedAtDesc("evt-async-no-context").isEmpty());
        verify(modelClient, never()).chat(anyString());
    }

    private KnowledgeConcept confirmedConcept(String name) {
        KnowledgeConcept concept = new KnowledgeConcept();
        concept.setCanonicalName(name);
        concept.setNormalizedName(normalizationService.normalize(name));
        concept.setBookId(1L);
        concept.setFirstChapterIndex(1);
        concept.setStatus(ConceptStatus.CONFIRMED);
        return conceptRepository.save(concept);
    }

    private LearningEvent realisticEvent(String eventId,
                                         String selectedText,
                                         String question,
                                         String selectedContext,
                                         String chapterTitle,
                                         String recentDialogue) {
        return new LearningEvent(
            eventId,
            "test-user",
            1L,
            1,
            "test-session",
            question,
            selectedText,
            selectedContext,
            chapterTitle,
            REALISTIC_CHAPTER_CONTENT,
            recentDialogue);
    }

    private String modelJson(String prompt) {
        String concept = "机会主义";
        if (prompt.contains("规训权力")) {
            concept = "规训权力";
        } else if (prompt.contains("见风使舵")) {
            concept = "见风使舵";
        }
        List<String> evidence = prompt.contains("规训权力")
            ? List.of("划词包含规训权力", "附近上下文讨论规训权力")
            : List.of("当前输入包含候选概念", "上下文支持候选概念");
        return """
            {
              "concept": "%s",
              "confidence": 0.96,
              "reason": "测试模型认为候选概念明确",
              "contextSupport": {
                "supported": true,
                "score": 0.92,
                "evidence": %s
              }
            }
            """.formatted(concept, toJsonArray(evidence));
    }

    private String toJsonArray(List<String> values) {
        return "[" + values.stream()
            .map(value -> "\"" + value + "\"")
            .reduce((left, right) -> left + "," + right)
            .orElse("") + "]";
    }
}
