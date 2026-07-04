package com.example.httpreading.evolution;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.ai.ChatPlan;
import com.example.httpreading.service.ai.CollectedEvidence;
import com.example.httpreading.service.ai.EvidenceItem;
import com.example.httpreading.service.ai.FinalAnswerService;
import com.example.httpreading.service.ai.PlannerTaskType;
import com.example.httpreading.service.ai.ToolExecutionMode;
import org.springframework.stereotype.Service;

/**
 * Executes only the FinalAnswer stage. Planner, tools, RAG and memory services are
 * intentionally outside this evaluator so baseline and candidate differ only by
 * the FinalAnswer prompt patch.
 */
@Service
public class InProcessAgentEvaluator {
    private final FinalAnswerService finalAnswerService;

    public InProcessAgentEvaluator(FinalAnswerService finalAnswerService) {
        this.finalAnswerService = finalAnswerService;
    }

    public List<AgentRun> evaluate(List<EvolutionEvalCase> cases,
                                   String variant,
                                   PromptOverride promptOverride) {
        return evaluate(cases, variant, promptOverride, true);
    }

    public List<AgentRun> evaluate(List<EvolutionEvalCase> cases,
                                   String variant,
                                   PromptOverride promptOverride,
                                   boolean deterministic) {
        List<AgentRun> results = new ArrayList<>();
        PromptOverride finalAnswerOnly = PromptOverride.finalAnswerOnly(
            promptOverride == null ? "" : promptOverride.finalAnswerPatch());
        for (EvolutionEvalCase evalCase : cases) {
            AiChatRequest request = copy(evalCase.request(), evalCase.id());
            ChatPlan plan = fixedPlan(evalCase);
            CollectedEvidence evidence = fixedEvidence(evalCase);
            long start = System.nanoTime();
            try {
                ModelClient.ChatOptions chatOptions = deterministic
                    ? ModelClient.ChatOptions.deterministic()
                    : ModelClient.ChatOptions.defaults();
                String answer = finalAnswerService.answer(
                    request, plan, evidence, finalAnswerOnly, chatOptions);
                results.add(new AgentRun(
                    evalCase.id(), answer, "completed", plan,
                    (System.nanoTime() - start) / 1_000_000, ""));
            } catch (Exception exception) {
                results.add(new AgentRun(
                    evalCase.id(), "", "execution_failed", plan,
                    (System.nanoTime() - start) / 1_000_000,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage()));
            }
        }
        return List.copyOf(results);
    }

    private ChatPlan fixedPlan(EvolutionEvalCase evalCase) {
        EvolutionEvalCase.FinalAnswerInput input = evalCase.finalAnswerInput();
        return new ChatPlan(
            evalCase.request().getQuestion(),
            input.standaloneQuestion(),
            "Self-Evolution FinalAnswer-only 固定输入",
            PlannerTaskType.READING_QA,
            input.subIntent(),
            input.answerRequirement(),
            input.answerMode(),
            input.evidenceStrictness(),
            input.dependsOnContext(),
            ToolExecutionMode.NO_TOOL,
            List.of(),
            List.of(),
            "仅评测 FinalAnswer 输出质量",
            0,
            "直接生成最终回答",
            evidenceModeGuidance(evalCase, input.answerGuidance()));
    }

    private String evidenceModeGuidance(EvolutionEvalCase evalCase, String guidance) {
        EvidenceUseMode mode = evalCase.expectedBehavior().evidencePolicy().evidenceUseMode();
        String modeRule = switch (mode) {
            case STRICT_SOURCE ->
                "evidenceUseMode=STRICT_SOURCE：只能依据测试证据，证据不足时直接说明，"
                    + "免责声明不能替代证据。";
            case SOURCE_GROUNDED_NARRATIVE ->
                "evidenceUseMode=SOURCE_GROUNDED_NARRATIVE：如需证据外叙事，回答的第一个"
                    + "非标题句必须是“以下为助手自主构造、没有资料依据，仅用于理解”。"
                    + "该句之前不得出现人物、时间、地点、事件或事实判断；声明后立即进入故事。"
                    + "不得把历史记忆摘要提升为原文、RAG 或书籍事实。";
            case PEDAGOGICAL_ILLUSTRATION ->
                "evidenceUseMode=PEDAGOGICAL_ILLUSTRATION：用于解释理论的教学人物、数字和"
                    + "生活场景无需真实性声明，但不能冒充原文或真实事件。";
        };
        return (guidance == null || guidance.isBlank())
            ? modeRule
            : guidance + "\n" + modeRule;
    }

    private CollectedEvidence fixedEvidence(EvolutionEvalCase evalCase) {
        List<EvidenceItem> items = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> memoryRefs = new ArrayList<>();
        for (EvolutionEvalCase.CollectedEvidence value : evalCase.collectedEvidence()) {
            items.add(new EvidenceItem(
                value.id(), value.type(), value.title(), value.content(),
                100, 1.0, value.metadata()));
            String summary = value.title() + "：" + value.content();
            if ("memory".equals(value.type()) || "recent_dialogue".equals(value.type())) {
                memoryRefs.add(summary);
            } else {
                sources.add(summary);
            }
        }
        String formatted = evalCase.collectedEvidence().isEmpty()
            ? "没有收集到足够证据。"
            : evalCase.collectedEvidence().stream()
                .map(value -> "【" + value.title() + "】\n"
                    + "type=" + value.type() + "\n" + value.content())
                .collect(Collectors.joining("\n\n", "已提供的固定测试证据：\n", ""));
        return new CollectedEvidence(
            items, sources, memoryRefs, List.of(), List.of(), formatted);
    }

    private AiChatRequest copy(AiChatRequest source, String caseId) {
        AiChatRequest target = new AiChatRequest();
        target.setBookId(source.getBookId());
        target.setChapterIndex(source.getChapterIndex());
        target.setQuestion(source.getQuestion());
        target.setUserId(source.getUserId());
        target.setSessionId("self-evolution-final-answer-" + caseId);
        target.setTopK(source.getTopK());
        target.setEnableMemory(false);
        target.setEnableRag(false);
        target.setEnableExternalMcp(false);
        target.setChapterTitle(source.getChapterTitle());
        target.setChapterContent(source.getChapterContent());
        target.setSelectedText(source.getSelectedText());
        target.setSelectedContext(source.getSelectedContext());
        return target;
    }

    public record AgentRun(String caseId,
                           String answer,
                           String status,
                           ChatPlan plan,
                           long latencyMs,
                           String error) {
        public AgentRun {
            answer = answer == null ? "" : answer;
            status = status == null ? "" : status;
            error = error == null ? "" : error;
        }
    }
}
