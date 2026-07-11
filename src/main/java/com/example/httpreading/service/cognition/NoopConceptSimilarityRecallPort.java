package com.example.httpreading.service.cognition;

import java.util.List;

import com.example.httpreading.dto.cognition.ConceptCandidateDto;
import org.springframework.stereotype.Component;

@Component
public class NoopConceptSimilarityRecallPort implements ConceptSimilarityRecallPort {
    @Override
    public List<ConceptCandidateDto> recall(String candidateName, Long bookId, int limit) {
        return List.of();
    }
}
