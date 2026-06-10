package com.example.httpreading.ai;

import java.util.List;
import java.util.Map;

class AiCaseSpec {
    public String caseId;
    public String caseName;
    public String category;
    public AiCaseInput input;
    public MockEvidence mockEvidence;
    public ExpectedPlan expectedPlan;
    public ExpectedAnswerRules expectedAnswerRules;
    public List<Map<String, Object>> mcpServers;
    public String mockPlannerResponse;
    public String mockFinalAnswer;

    static class AiCaseInput {
        public String userId;
        public String sessionId;
        public Long bookId;
        public Integer chapterIndex;
        public String chapterTitle;
        public String question;
        public String selectedText;
        public String selectedContext;
        public Boolean memoryEnabled;
        public Boolean ragEnabled;
        public Boolean externalMcpEnabled;
    }

    static class MockEvidence {
        public String formattedEvidence;
        public List<Map<String, Object>> items;
        public List<String> sources;
        public List<String> memoryRefs;
        public List<String> externalMcpRefs;
        public List<String> externalMcpPlanRefs;
    }

    static class ExpectedPlan {
        public String taskType;
        public List<String> subIntentAnyOf;
        public String subIntent;
        public String executionMode;
        public List<String> executionModeAnyOf;
        public String answerMode;
        public String evidenceStrictness;
        public List<String> evidenceStrictnessAnyOf;
        public List<String> allowedTools;
        public List<String> mustUseToolsAnyOf;
        public List<String> mustNotUseTools;
        public Boolean toolPlanEmpty;
        public String answerGuidanceContains;
        public ExpectedRequirements requirements;
    }

    static class ExpectedRequirements {
        public Boolean requiresConcreteExample;
        public Boolean requiresSpecificEntity;
        public Boolean requiresStorytelling;
        public Boolean requiresDetailedProcess;
        public Boolean avoidConceptualOpening;
        public Boolean avoidRepeatingPreviousExplanation;
        public Boolean allowModelKnowledge;
        public Boolean mustDistinguishTextEvidenceAndSupplement;
        public Boolean avoidRepeatingSourcePhrases;
    }

    static class ExpectedAnswerRules {
        public List<String> mustContain;
        public List<String> mustNotContain;
        public List<String> requiredSemanticChecks;
    }
}
