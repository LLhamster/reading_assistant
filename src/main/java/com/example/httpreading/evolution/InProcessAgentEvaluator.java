package com.example.httpreading.evolution;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.example.httpreading.dto.AiChatRequest;
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
        List<AgentRun> results = new ArrayList<>();
        PromptOverride finalAnswerOnly = PromptOverride.finalAnswerOnly(
            promptOverride == null ? "" : promptOverride.finalAnswerPatch());
        for (EvolutionEvalCase evalCase : cases) {
            AiChatRequest request = copy(evalCase.request(), evalCase.id());
            ChatPlan plan = fixedPlan(evalCase);
            CollectedEvidence evidence = fixedEvidence(evalCase);
            long start = System.nanoTime();
            try {
                String answer = finalAnswerService.answer(request, plan, evidence, finalAnswerOnly);
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
            input.answerGuidance());
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
