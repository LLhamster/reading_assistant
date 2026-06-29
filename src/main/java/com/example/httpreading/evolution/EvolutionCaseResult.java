package com.example.httpreading.evolution;

import java.util.List;

import com.example.httpreading.service.ai.ChatPlan;

public record EvolutionCaseResult(String caseId,
                                  String answer,
                                  String status,
                                  ChatPlan plan,
                                  double score,
                                  boolean passed,
                                  boolean hardFailure,
                                  List<FailureType> failureTypes,
                                  List<String> reasons,
                                  List<EvidenceIssue> evidenceIssues,
                                  List<EvidenceBoundaryJudge.ClaimReview> evidenceClaims,
                                  long latencyMs) {
    public EvolutionCaseResult {
        caseId = safe(caseId);
        answer = safe(answer);
        status = safe(status);
        failureTypes = failureTypes == null ? List.of() : List.copyOf(failureTypes);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        evidenceIssues = evidenceIssues == null ? List.of() : List.copyOf(evidenceIssues);
        evidenceClaims = evidenceClaims == null ? List.of() : List.copyOf(evidenceClaims);
    }

    public EvolutionCaseResult(String caseId,
                               String answer,
                               String status,
                               ChatPlan plan,
                               double score,
                               boolean passed,
                               boolean hardFailure,
                               List<FailureType> failureTypes,
                               List<String> reasons,
                               long latencyMs) {
        this(caseId, answer, status, plan, score, passed, hardFailure,
            failureTypes, reasons, List.of(), List.of(), latencyMs);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
