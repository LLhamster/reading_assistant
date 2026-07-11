package com.example.httpreading.service.cognition;

import com.example.httpreading.domain.cognition.KnowledgeConcept;

record MatchedConcept(KnowledgeConcept concept, double score, String reason, boolean aliasMatch) {
}
