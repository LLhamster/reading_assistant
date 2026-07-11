package com.example.httpreading.service.cognition;

import java.util.List;

import com.example.httpreading.dto.cognition.ConceptCandidateDto;

public interface ConceptSimilarityRecallPort {
    List<ConceptCandidateDto> recall(String candidateName, Long bookId, int limit);
}
