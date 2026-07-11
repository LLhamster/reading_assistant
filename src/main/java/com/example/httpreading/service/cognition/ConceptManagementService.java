package com.example.httpreading.service.cognition;

import java.util.List;

import com.example.httpreading.api.ErrorCode;
import com.example.httpreading.domain.cognition.ConceptAlias;
import com.example.httpreading.domain.cognition.ConceptCandidateRecord;
import com.example.httpreading.domain.cognition.ConceptCandidateStatus;
import com.example.httpreading.domain.cognition.ConceptMergeRelation;
import com.example.httpreading.domain.cognition.ConceptStatus;
import com.example.httpreading.domain.cognition.KnowledgeConcept;
import com.example.httpreading.dto.cognition.ConceptCandidateRecordDto;
import com.example.httpreading.dto.cognition.ConceptManagementResponse;
import com.example.httpreading.dto.cognition.ConfirmCandidateRequest;
import com.example.httpreading.repository.cognition.ConceptAliasRepository;
import com.example.httpreading.repository.cognition.ConceptCandidateRecordRepository;
import com.example.httpreading.repository.cognition.ConceptMergeRelationRepository;
import com.example.httpreading.repository.cognition.KnowledgeConceptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConceptManagementService {
    private final ConceptCandidateRecordRepository candidateRecordRepository;
    private final KnowledgeConceptRepository conceptRepository;
    private final ConceptAliasRepository aliasRepository;
    private final ConceptMergeRelationRepository mergeRelationRepository;
    private final ConceptNormalizationService normalizationService;

    public ConceptManagementService(ConceptCandidateRecordRepository candidateRecordRepository,
                                    KnowledgeConceptRepository conceptRepository,
                                    ConceptAliasRepository aliasRepository,
                                    ConceptMergeRelationRepository mergeRelationRepository,
                                    ConceptNormalizationService normalizationService) {
        this.candidateRecordRepository = candidateRecordRepository;
        this.conceptRepository = conceptRepository;
        this.aliasRepository = aliasRepository;
        this.mergeRelationRepository = mergeRelationRepository;
        this.normalizationService = normalizationService;
    }

    @Transactional(readOnly = true)
    public List<ConceptCandidateRecordDto> listCandidates(ConceptCandidateStatus status) {
        ConceptCandidateStatus resolvedStatus = status == null ? ConceptCandidateStatus.PENDING : status;
        return candidateRecordRepository.findByStatusOrderByCreatedAtDesc(resolvedStatus).stream()
            .map(this::toDto)
            .toList();
    }

    @Transactional
    public ConceptManagementResponse confirmCandidate(Long id, ConfirmCandidateRequest request) {
        ConceptCandidateRecord record = candidateRecordRepository.findById(id)
            .orElseThrow(() -> ErrorCode.RESOURCE_NOT_FOUND.toException("候选概念不存在: " + id));
        ConfirmCandidateRequest resolvedRequest = request == null
            ? new ConfirmCandidateRequest(null, null, null, null)
            : request;
        KnowledgeConcept concept = resolveConfirmedConcept(record, resolvedRequest);
        if (!normalizationService.isBlank(resolvedRequest.aliasName())) {
            addAliasIfMissing(concept.getId(), resolvedRequest.aliasName(), "MANUAL", 1.0d);
        } else if (!normalizationService.normalize(record.getCandidateName()).equals(concept.getNormalizedName())) {
            addAliasIfMissing(concept.getId(), record.getCandidateName(), "CANDIDATE_CONFIRM", record.getConfidence());
        }
        record.setMatchedConceptId(concept.getId());
        record.setStatus(ConceptCandidateStatus.ACCEPTED);
        candidateRecordRepository.save(record);
        return new ConceptManagementResponse("confirmed", concept.getId(), concept.getCanonicalName(),
            concept.getStatus(), record.getId(), record.getStatus());
    }

    @Transactional
    public ConceptManagementResponse mergeConcept(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
            throw ErrorCode.BAD_REQUEST.toException("sourceId 和 targetId 必须不同");
        }
        KnowledgeConcept source = conceptRepository.findById(sourceId)
            .orElseThrow(() -> ErrorCode.RESOURCE_NOT_FOUND.toException("源概念不存在: " + sourceId));
        KnowledgeConcept target = conceptRepository.findById(targetId)
            .orElseThrow(() -> ErrorCode.RESOURCE_NOT_FOUND.toException("目标概念不存在: " + targetId));
        source.setStatus(ConceptStatus.MERGED);
        source.setMergedToConceptId(target.getId());
        conceptRepository.save(source);
        ConceptMergeRelation relation = new ConceptMergeRelation();
        relation.setSourceConceptId(source.getId());
        relation.setTargetConceptId(target.getId());
        relation.setReason("manual merge");
        mergeRelationRepository.save(relation);
        return new ConceptManagementResponse("merged", target.getId(), target.getCanonicalName(),
            target.getStatus(), null, null);
    }

    private KnowledgeConcept resolveConfirmedConcept(ConceptCandidateRecord record, ConfirmCandidateRequest request) {
        if (request.targetConceptId() != null) {
            KnowledgeConcept concept = conceptRepository.findById(request.targetConceptId())
                .orElseThrow(() -> ErrorCode.RESOURCE_NOT_FOUND.toException("目标概念不存在: " + request.targetConceptId()));
            if (concept.getStatus() == ConceptStatus.MERGED && concept.getMergedToConceptId() != null) {
                return conceptRepository.findById(concept.getMergedToConceptId()).orElse(concept);
            }
            return concept;
        }
        if (record.getMatchedConceptId() != null) {
            KnowledgeConcept concept = conceptRepository.findById(record.getMatchedConceptId())
                .orElse(null);
            if (concept != null && concept.getStatus() != ConceptStatus.MERGED) {
                concept.setStatus(ConceptStatus.CONFIRMED);
                if (!normalizationService.isBlank(request.description())) {
                    concept.setDescription(request.description());
                }
                return conceptRepository.save(concept);
            }
        }
        KnowledgeConcept concept = new KnowledgeConcept();
        concept.setCanonicalName(record.getCandidateName());
        concept.setNormalizedName(normalizationService.normalize(record.getCandidateName()));
        concept.setDescription(request.description());
        concept.setStatus(ConceptStatus.CONFIRMED);
        return conceptRepository.save(concept);
    }

    private void addAliasIfMissing(Long conceptId, String aliasName, String source, Double confidence) {
        String normalized = normalizationService.normalize(aliasName);
        if (normalizationService.isBlank(normalized)
            || aliasRepository.existsByConceptIdAndNormalizedAliasName(conceptId, normalized)) {
            return;
        }
        ConceptAlias alias = new ConceptAlias();
        alias.setConceptId(conceptId);
        alias.setAliasName(aliasName.trim());
        alias.setNormalizedAliasName(normalized);
        alias.setSource(source);
        alias.setConfidence(confidence == null ? 0.5d : confidence);
        aliasRepository.save(alias);
    }

    private ConceptCandidateRecordDto toDto(ConceptCandidateRecord record) {
        return new ConceptCandidateRecordDto(
            record.getId(),
            record.getEventId(),
            record.getCandidateName(),
            record.getMatchedConceptId(),
            record.getConfidence(),
            record.getModelScore(),
            record.getLexicalScore(),
            record.getContextScore(),
            record.getHistoryScore(),
            record.getCandidateGapScore(),
            record.getStatus(),
            record.getReason(),
            record.getCreatedAt());
    }
}
