package com.example.httpreading.service.ai;

import java.util.List;

public record CollectedEvidence(List<EvidenceItem> items,
                                List<String> sources,
                                List<String> memoryRefs,
                                List<String> externalMcpRefs,
                                List<String> externalMcpPlanRefs,
                                String formattedEvidence) {
    public CollectedEvidence {
        items = items == null ? List.of() : List.copyOf(items);
        sources = sources == null ? List.of() : List.copyOf(sources);
        memoryRefs = memoryRefs == null ? List.of() : List.copyOf(memoryRefs);
        externalMcpRefs = externalMcpRefs == null ? List.of() : List.copyOf(externalMcpRefs);
        externalMcpPlanRefs = externalMcpPlanRefs == null ? List.of() : List.copyOf(externalMcpPlanRefs);
        formattedEvidence = formattedEvidence == null ? "" : formattedEvidence;
    }
}
