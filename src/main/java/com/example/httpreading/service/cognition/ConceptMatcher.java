package com.example.httpreading.service.cognition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.httpreading.domain.cognition.ConceptAlias;
import com.example.httpreading.domain.cognition.ConceptMergeRelation;
import com.example.httpreading.domain.cognition.ConceptStatus;
import com.example.httpreading.domain.cognition.KnowledgeConcept;
import com.example.httpreading.dto.cognition.ConceptCandidateDto;
import com.example.httpreading.repository.cognition.ConceptAliasRepository;
import com.example.httpreading.repository.cognition.ConceptMergeRelationRepository;
import com.example.httpreading.repository.cognition.KnowledgeConceptRepository;
import org.springframework.stereotype.Service;

@Service
public class ConceptMatcher {
    private final KnowledgeConceptRepository conceptRepository;
    private final ConceptAliasRepository aliasRepository;
    private final ConceptMergeRelationRepository mergeRelationRepository;
    private final ConceptNormalizationService normalizationService;
    private final ConceptSimilarityRecallPort similarityRecallPort;

    public ConceptMatcher(KnowledgeConceptRepository conceptRepository,
                          ConceptAliasRepository aliasRepository,
                          ConceptMergeRelationRepository mergeRelationRepository,
                          ConceptNormalizationService normalizationService,
                          ConceptSimilarityRecallPort similarityRecallPort) {
        this.conceptRepository = conceptRepository;
        this.aliasRepository = aliasRepository;
        this.mergeRelationRepository = mergeRelationRepository;
        this.normalizationService = normalizationService;
        this.similarityRecallPort = similarityRecallPort;
    }

    public List<MatchedConcept> match(String candidateName, Long bookId) {
        String normalized = normalizationService.normalize(candidateName);
        Map<Long, MatchedConcept> matches = new LinkedHashMap<>();

        conceptRepository.findFirstByCanonicalNameIgnoreCase(candidateName.trim())
            .ifPresent(concept -> putBest(matches, resolveMerged(concept), 1.0d, "canonical exact match", false));

        aliasRepository.findFirstByAliasNameIgnoreCase(candidateName.trim())
            .flatMap(alias -> conceptRepository.findById(alias.getConceptId())
                .map(concept -> new AliasConcept(alias, concept)))
            .ifPresent(aliasConcept -> putBest(matches, resolveMerged(aliasConcept.concept()),
                0.92d, "alias exact match", true));

        for (KnowledgeConcept concept : conceptRepository.findByNormalizedName(normalized)) {
            putBest(matches, resolveMerged(concept), 0.88d, "normalized canonical match", false);
        }
        for (ConceptAlias alias : aliasRepository.findByNormalizedAliasName(normalized)) {
            conceptRepository.findById(alias.getConceptId())
                .map(this::resolveMerged)
                .ifPresent(concept -> putBest(matches, concept, 0.86d, "normalized alias match", true));
        }

        for (KnowledgeConcept concept : fuzzyByPrefixOrSuffix(normalized)) {
            double score = fuzzyScore(normalized, concept.getNormalizedName());
            if (score >= 0.35d) {
                putBest(matches, resolveMerged(concept), score, "fuzzy recall", false);
            }
        }
        for (ConceptCandidateDto recalled : similarityRecallPort.recall(candidateName, bookId, 5)) {
            if (recalled.conceptId() != null) {
                conceptRepository.findById(recalled.conceptId())
                    .map(this::resolveMerged)
                    .ifPresent(concept -> putBest(matches, concept, recalled.score(), "similarity recall", false));
            }
        }

        return matches.values().stream()
            .sorted(Comparator.comparingDouble(MatchedConcept::score).reversed())
            .limit(5)
            .toList();
    }

    public List<ConceptCandidateDto> toCandidateDtos(List<MatchedConcept> matches, String fallbackName) {
        List<ConceptCandidateDto> result = new ArrayList<>();
        for (MatchedConcept match : matches) {
            result.add(new ConceptCandidateDto(
                match.concept().getId(),
                match.concept().getCanonicalName(),
                clamp(match.score())));
        }
        if (result.isEmpty() && !normalizationService.isBlank(fallbackName)) {
            result.add(new ConceptCandidateDto(null, fallbackName.trim(), 0.40d));
        }
        return result.stream()
            .sorted(Comparator.comparingDouble(ConceptCandidateDto::score).reversed())
            .limit(5)
            .toList();
    }

    private List<KnowledgeConcept> fuzzyByPrefixOrSuffix(String normalized) {
        if (normalized.length() < 2) {
            return List.of();
        }
        String prefix = normalized.substring(0, Math.min(2, normalized.length()));
        String suffix = normalized.substring(Math.max(0, normalized.length() - 2));
        return conceptRepository.findTop10ByNormalizedNameStartingWithOrNormalizedNameEndingWith(prefix, suffix);
    }

    private double fuzzyScore(String candidate, String conceptName) {
        if (candidate == null || conceptName == null || candidate.isBlank() || conceptName.isBlank()) {
            return 0.0d;
        }
        if (candidate.equals(conceptName)) {
            return 0.88d;
        }
        int common = 0;
        for (int i = 0; i < candidate.length(); i++) {
            if (conceptName.indexOf(candidate.charAt(i)) >= 0) {
                common++;
            }
        }
        double overlap = (double) common / Math.max(candidate.length(), conceptName.length());
        return Math.min(0.70d, overlap);
    }

    private KnowledgeConcept resolveMerged(KnowledgeConcept concept) {
        if (concept == null || concept.getStatus() != ConceptStatus.MERGED) {
            return concept;
        }
        Long targetId = concept.getMergedToConceptId();
        if (targetId == null) {
            Optional<ConceptMergeRelation> relation =
                mergeRelationRepository.findFirstBySourceConceptIdOrderByCreatedAtDesc(concept.getId());
            targetId = relation.map(ConceptMergeRelation::getTargetConceptId).orElse(null);
        }
        if (targetId == null || targetId.equals(concept.getId())) {
            return concept;
        }
        return conceptRepository.findById(targetId).orElse(concept);
    }

    private void putBest(Map<Long, MatchedConcept> matches,
                         KnowledgeConcept concept,
                         double score,
                         String reason,
                         boolean aliasMatch) {
        if (concept == null || concept.getId() == null) {
            return;
        }
        MatchedConcept existing = matches.get(concept.getId());
        if (existing == null || existing.score() < score) {
            matches.put(concept.getId(), new MatchedConcept(concept, clamp(score), reason, aliasMatch));
        }
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private record AliasConcept(ConceptAlias alias, KnowledgeConcept concept) {
    }
}
