package com.example.httpreading.controller;

import java.util.List;

import com.example.httpreading.api.CommonResponse;
import com.example.httpreading.domain.cognition.ConceptCandidateStatus;
import com.example.httpreading.dto.cognition.ConceptCandidateRecordDto;
import com.example.httpreading.dto.cognition.ConceptManagementResponse;
import com.example.httpreading.dto.cognition.ConceptResolutionResult;
import com.example.httpreading.dto.cognition.ConfirmCandidateRequest;
import com.example.httpreading.service.cognition.ConceptManagementService;
import com.example.httpreading.service.cognition.ConceptResolverService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cognition/concepts")
public class CognitionConceptController {
    private final ConceptResolverService conceptResolverService;
    private final ConceptManagementService conceptManagementService;

    public CognitionConceptController(ConceptResolverService conceptResolverService,
                                      ConceptManagementService conceptManagementService) {
        this.conceptResolverService = conceptResolverService;
        this.conceptManagementService = conceptManagementService;
    }

    @GetMapping("/resolutions/{eventId}")
    public CommonResponse<ConceptResolutionResult> resolution(@PathVariable String eventId) {
        return CommonResponse.success(conceptResolverService.getResolution(eventId));
    }

    @GetMapping("/candidates")
    public CommonResponse<List<ConceptCandidateRecordDto>> candidates(
        @RequestParam(defaultValue = "PENDING") ConceptCandidateStatus status) {
        return CommonResponse.success(conceptManagementService.listCandidates(status));
    }

    @PostMapping("/candidates/{id}/confirm")
    public CommonResponse<ConceptManagementResponse> confirmCandidate(
        @PathVariable Long id,
        @RequestBody(required = false) ConfirmCandidateRequest request) {
        return CommonResponse.success(conceptManagementService.confirmCandidate(id, request));
    }

    @PostMapping("/{sourceId}/merge/{targetId}")
    public CommonResponse<ConceptManagementResponse> mergeConcept(@PathVariable Long sourceId,
                                                                  @PathVariable Long targetId) {
        return CommonResponse.success(conceptManagementService.mergeConcept(sourceId, targetId));
    }
}
