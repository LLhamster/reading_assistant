package com.example.httpreading.evolution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ModelClient;
import com.example.httpreading.service.ai.AnswerMode;
import com.example.httpreading.service.ai.AnswerRequirement;
import com.example.httpreading.service.ai.DetailLevel;
import com.example.httpreading.service.ai.EvidenceStrictness;
import com.example.httpreading.service.ai.SubIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EvalCaseGenerator {
    private static final Logger log = LoggerFactory.getLogger(EvalCaseGenerator.class);
    public static final int DEFAULT_CASE_COUNT = 30;
    private static final int MAX_SIGNAL_CASES = 2;

    private static final Map<FailureType, CommonTemplate> COMMON_TEMPLATES = Map.of(
        FailureType.TOO_CONCEPTUAL, new CommonTemplate(
            "不要先下定义，请用便利店顾客在两种早餐之间选择的生活场景，解释“机会成本是放弃的最佳替代方案”。",
            "机会成本是不是买早餐实际付出去的钱？",
            "不完全是；它更关注选择一种方案时放弃的最佳替代收益。",
            "顾客只有20元和十分钟，可以排队购买喜欢的现做早餐，也可以立即购买普通面包。选择其一会放弃另一选择带来的最好收益。",
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION),
        FailureType.TOO_SIMPLE, new CommonTemplate(
            "为什么“损失100元的心理冲击通常强于获得100元的满足感”？请从参照点、感受差异和决策影响讲清推导过程。",
            "损失100元和获得100元的心理感受应该完全对称吗？",
            "损失厌恶认为两者通常不对称，损失带来的感受更强。",
            "人们通常以当前拥有状态为参照点；同等金额下，损失区的心理反应强于收益区，这会让人更倾向于规避可能的损失。",
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION),
        FailureType.REPETITIVE, new CommonTemplate(
            "不要重复“新闻切换很快”这个结论，请补充说明快速切换为什么会削弱读者形成连续判断。",
            "为什么碎片化新闻让人难以形成连续理解？",
            "因为节目把互不相关的事件快速并置，上一条信息很快被下一条替代。",
            "灾难新闻之后立即切换到广告和天气，会中断因果追踪；注意力不断重置，使读者难以比较证据、保留前提并形成连续判断。",
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION),
        FailureType.MISSING_EXAMPLE, new CommonTemplate(
            "请用一家小店在“购买新设备”和“投放广告”之间选择的具体例子，说明机会成本，并解释例子如何对应这个概念。",
            "机会成本指什么？",
            "它是作出选择时放弃的最佳替代方案所能带来的收益。",
            "小店只有一笔预算，只能购买提高产能的设备或投放带来新顾客的广告；选择设备时，放弃的广告预期收益构成机会成本。",
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION),
        FailureType.MISSING_STORY_DETAIL, new CommonTemplate(
            "请把洞穴中的人从只能看影子到最终看见太阳的过程讲成完整故事，包含起点、发展、转折、结果，并回扣“认识需要逐步适应”的观点。",
            "走出洞穴是不是一瞬间完成的顿悟？",
            "不是；这个过程经历看影子、看映像、看事物本身，最后才能看太阳。",
            "被释放者起初只能看清影子；转身和走向洞外会感到刺痛与困惑，随后逐步适应映像、事物和天空，最后才能直视太阳。",
            EvidenceUseMode.SOURCE_GROUNDED_NARRATIVE),
        FailureType.NOT_DIRECT, new CommonTemplate(
            "农业社会人口流动通常较少的主要原因是什么？请先给核心结论，再解释土地和长期生活关系的作用。",
            "为什么传统农业社会中的人往往长期留在同一地方？",
            "农业生产依赖固定土地，长期定居也形成稳定的家庭和熟人关系。",
            "耕作需要持续照料固定土地；代际居住、互助和熟人信用进一步提高迁移成本，因此人口流动通常较少。",
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION),
        FailureType.OFF_TOPIC, new CommonTemplate(
            "只解释为什么熟人社会依赖长期重复交往，并说明重复交往如何形成信任。",
            "熟人社会中的信任从哪里来？",
            "共同生活和反复互动让人能够观察彼此行为，并形成稳定预期。",
            "长期重复交往使失信行为会在后续互动中付出代价，也让承诺能够被持续检验，因此逐渐形成稳定信任。",
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION));
    private static final List<BoundaryTemplate> BOUNDARY_TEMPLATES = boundaryTemplates();

    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;

    EvalCaseGenerator() {
        this.modelClient = null;
        this.objectMapper = new ObjectMapper();
    }

    @Autowired
    public EvalCaseGenerator(ModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public List<EvolutionEvalCase> generate(List<MisunderstandingSignal> minedSignals,
                                           String userId,
                                           Long defaultBookId,
                                           Integer defaultChapterIndex) {
        return generate(minedSignals, userId, defaultBookId, defaultChapterIndex, DEFAULT_CASE_COUNT);
    }

    public List<EvolutionEvalCase> generate(List<MisunderstandingSignal> minedSignals,
                                           String userId,
                                           Long defaultBookId,
                                           Integer defaultChapterIndex,
                                           int requestedCaseCount) {
        int caseCount = Math.max(1, Math.min(DEFAULT_CASE_COUNT, requestedCaseCount));
        List<MisunderstandingSignal> signals = (minedSignals == null
            ? List.<MisunderstandingSignal>of()
            : minedSignals).stream()
            .filter(signal -> signal != null && !isCommonSignal(signal))
            .distinct()
            .toList();
        Map<String, EvidenceUseMode> generatedModes = generateEvidenceUseModes(
            signals.subList(0, Math.min(signals.size(), caseCount)));

        List<EvolutionEvalCase> cases = new ArrayList<>(caseCount);
        Set<String> seenQuestions = new LinkedHashSet<>();
        int coverageSafeSignalBudget = caseCount >= ReadingBoundary.values().length
            ? caseCount - ReadingBoundary.values().length + 1
            : caseCount;
        int signalBudget = Math.min(
            coverageSafeSignalBudget,
            Math.min(signals.size() * MAX_SIGNAL_CASES, Math.max(1, caseCount / 3)));
        for (int variant = 0; variant < MAX_SIGNAL_CASES && cases.size() < signalBudget; variant++) {
            for (MisunderstandingSignal signal : signals) {
                if (cases.size() >= signalBudget) break;
                EvolutionEvalCase evalCase = signalCase(
                    signal, variant, userId, defaultBookId, defaultChapterIndex,
                    generatedModes, cases.size());
                if (seenQuestions.add(evalCase.request().getQuestion())) {
                    cases.add(evalCase);
                }
            }
        }

        Set<ReadingBoundary> coveredBoundaries = cases.stream()
            .map(evalCase -> evalCase.boundarySpec().boundary())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<ReadingBoundary> plannedInFirstPass = new LinkedHashSet<>();
        for (BoundaryTemplate template : BOUNDARY_TEMPLATES) {
            if (cases.size() >= caseCount) break;
            ReadingBoundary boundary = template.spec().boundary();
            if (!plannedInFirstPass.add(boundary) || coveredBoundaries.contains(boundary)) continue;
            if (!seenQuestions.add(template.template().question())) continue;
            cases.add(boundaryCase(
                template, userId, defaultBookId, defaultChapterIndex, cases.size()));
            coveredBoundaries.add(boundary);
        }
        for (BoundaryTemplate template : BOUNDARY_TEMPLATES) {
            if (cases.size() >= caseCount) break;
            if (!seenQuestions.add(template.template().question())) continue;
            cases.add(boundaryCase(
                template, userId, defaultBookId, defaultChapterIndex, cases.size()));
        }
        if (cases.size() != caseCount) {
            throw new IllegalStateException(
                "reading boundary plan produced " + cases.size() + " of " + caseCount + " cases");
        }
        return List.copyOf(cases);
    }

    private EvolutionEvalCase signalCase(MisunderstandingSignal signal,
                                         int variant,
                                         String userId,
                                         Long defaultBookId,
                                         Integer defaultChapterIndex,
                                         Map<String, EvidenceUseMode> generatedModes,
                                         int index) {
        CommonTemplate template = commonTemplate(signal.failureType());
        String question = signalQuestion(signal, variant);
        AiChatRequest request = request(
            signal, userId, defaultBookId, defaultChapterIndex, question, template);
        List<EvolutionEvalCase.DialogueTurn> dialogue = dialogue(signal, template);
        List<EvolutionEvalCase.CollectedEvidence> evidence =
            evidence(signal, template, dialogue, index);
        EvidenceUseMode mode = generatedModes.getOrDefault(
            modeKey(signal), fallbackEvidenceUseMode(signal, question));
        return new EvolutionEvalCase(
            caseId(index),
            signal.id(),
            request,
            signal.failureType(),
            anchors(signal),
            minimumChars(signal.failureType()),
            previousAnswer(signal.sourceText()),
            dialogue,
            evidence,
            mcpResults(signal, template, dialogue),
            finalAnswerInput(signal.failureType(), question, mode),
            expectedBehavior(signal.failureType(), question, mode),
            boundarySpec(signal.failureType(), mode),
            variant == 0 ? "MEDIUM" : "HARD",
            category(signal.failureType()));
    }

    private EvolutionEvalCase boundaryCase(BoundaryTemplate boundary,
                                           String userId,
                                           Long defaultBookId,
                                           Integer defaultChapterIndex,
                                           int index) {
        CommonTemplate template = boundary.template();
        MisunderstandingSignal signal = new MisunderstandingSignal(
            "boundary-" + boundary.spec().boundary().name().toLowerCase() + "-" + index,
            "", "", boundary.failureType(), 0.5,
            defaultBookId, defaultChapterIndex,
            Map.of("source", "boundary", "boundaryId", boundary.spec().boundary().name()));
        String question = template.question();
        AiChatRequest request = request(
            signal, userId, defaultBookId, defaultChapterIndex, question, template);
        List<EvolutionEvalCase.DialogueTurn> dialogue = List.of(
            new EvolutionEvalCase.DialogueTurn("user", template.priorQuestion()),
            new EvolutionEvalCase.DialogueTurn("assistant", template.priorAnswer()));
        List<EvolutionEvalCase.CollectedEvidence> evidence =
            evidence(signal, template, dialogue, index);
        return new EvolutionEvalCase(
            caseId(index),
            signal.id(),
            request,
            boundary.failureType(),
            List.of(),
            minimumChars(boundary.failureType()),
            template.priorAnswer(),
            dialogue,
            evidence,
            mcpResults(signal, template, dialogue),
            finalAnswerInput(
                boundary.failureType(), question, template.evidenceUseMode(),
                boundary.answerGuidance()),
            expectedBehavior(
                boundary.failureType(), question, template.evidenceUseMode(),
                boundary.criterionId(), boundary.criterionDescription()),
            boundary.spec(),
            boundary.difficulty(),
            boundary.spec().boundary().name());
    }

    private String caseId(int index) {
        return "evolution-" + String.format("%02d", index + 1);
    }

    private AiChatRequest request(MisunderstandingSignal signal,
                                  String userId,
                                  Long defaultBookId,
                                  Integer defaultChapterIndex,
                                  String question,
                                  CommonTemplate template) {
        AiChatRequest request = new AiChatRequest();
        request.setUserId(blankToDefault(userId, "default_user"));
        request.setBookId(signal.bookId() == null ? resolvedBookId(defaultBookId) : signal.bookId());
        request.setChapterIndex(signal.chapterIndex() == null
            ? resolvedChapterIndex(defaultChapterIndex) : signal.chapterIndex());
        request.setQuestion(question);
        request.setEnableMemory(false);
        request.setEnableRag(false);
        request.setEnableExternalMcp(false);
        request.setSelectedContext(signal.sourceText().isBlank()
            ? template.evidence()
            : "历史阅读问答摘要：" + truncate(signal.sourceText(), 700));
        return request;
    }

    private List<EvolutionEvalCase.DialogueTurn> dialogue(
        MisunderstandingSignal signal,
        CommonTemplate template) {
        String priorQuestion = section(signal.sourceText(), "问题：", "结论：");
        String priorAnswer = previousAnswer(signal.sourceText());
        if (priorQuestion.isBlank()) priorQuestion = template.priorQuestion();
        if (priorAnswer.isBlank()) priorAnswer = template.priorAnswer();
        return List.of(
            new EvolutionEvalCase.DialogueTurn("user", priorQuestion),
            new EvolutionEvalCase.DialogueTurn("assistant", priorAnswer));
    }

    private List<EvolutionEvalCase.CollectedEvidence> evidence(
        MisunderstandingSignal signal,
        CommonTemplate template,
        List<EvolutionEvalCase.DialogueTurn> dialogue,
        int index) {
        String dialogueText = dialogue.stream()
            .map(turn -> turn.role() + "：" + turn.content())
            .collect(java.util.stream.Collectors.joining("\n"));
        boolean common = signal.sourceText().isBlank();
        return List.of(
            new EvolutionEvalCase.CollectedEvidence(
                "dialogue-evolution-" + index, "recent_dialogue", "最近对话",
                dialogueText, Map.of()),
            new EvolutionEvalCase.CollectedEvidence(
                (common ? "page-" : "memory-") + "evolution-" + index,
                common ? "current_page" : "memory",
                common ? "测试用例提供的阅读证据" : "历史反馈记忆",
                common ? template.evidence() : signal.sourceText(),
                Map.of("isBookEvidence", common)));
    }

    private List<EvolutionEvalCase.McpResult> mcpResults(
        MisunderstandingSignal signal,
        CommonTemplate template,
        List<EvolutionEvalCase.DialogueTurn> dialogue) {
        boolean common = signal.sourceText().isBlank();
        return List.of(
            new EvolutionEvalCase.McpResult(
                "context.get_recent_dialogue", true, Map.of("dialogue", dialogue)),
            new EvolutionEvalCase.McpResult(
                common ? "context.get_current_page" : "memory.search", true,
                common
                    ? Map.of("content", template.evidence())
                    : Map.of("items", List.of(signal.sourceText()))));
    }

    private EvolutionEvalCase.FinalAnswerInput finalAnswerInput(FailureType type,
                                                                String question,
                                                                EvidenceUseMode evidenceUseMode) {
        return finalAnswerInput(type, question, evidenceUseMode, criterionDescription(type));
    }

    private EvolutionEvalCase.FinalAnswerInput finalAnswerInput(FailureType type,
                                                                String question,
                                                                EvidenceUseMode evidenceUseMode,
                                                                String answerGuidance) {
        boolean allowSupplement = evidenceUseMode != EvidenceUseMode.STRICT_SOURCE;
        AnswerMode mode = allowSupplement
            ? AnswerMode.CONTEXT_ANCHORED_MODEL_KNOWLEDGE
            : AnswerMode.TEXT_ONLY;
        EvidenceStrictness strictness = allowSupplement
            ? EvidenceStrictness.MEDIUM
            : EvidenceStrictness.STRICT;
        return new EvolutionEvalCase.FinalAnswerInput(
            question,
            subIntent(type),
            answerRequirement(type, allowSupplement),
            mode,
            strictness,
            true,
            answerGuidance);
    }

    private AnswerRequirement answerRequirement(FailureType type, boolean allowModelKnowledge) {
        return new AnswerRequirement(
            type == FailureType.TOO_CONCEPTUAL || type == FailureType.MISSING_EXAMPLE
                || type == FailureType.MISSING_STORY_DETAIL,
            false,
            type == FailureType.MISSING_STORY_DETAIL,
            type == FailureType.MISSING_STORY_DETAIL || type == FailureType.TOO_SIMPLE,
            type == FailureType.TOO_CONCEPTUAL || type == FailureType.MISSING_EXAMPLE
                || type == FailureType.MISSING_STORY_DETAIL || type == FailureType.NOT_DIRECT,
            type == FailureType.REPETITIVE,
            allowModelKnowledge,
            allowModelKnowledge,
            type == FailureType.REPETITIVE,
            type == FailureType.TOO_SIMPLE || type == FailureType.MISSING_STORY_DETAIL
                ? DetailLevel.HIGH : DetailLevel.MEDIUM);
    }

    private SubIntent subIntent(FailureType type) {
        return switch (type) {
            case TOO_CONCEPTUAL, MISSING_EXAMPLE -> SubIntent.CONCRETE_EXAMPLE;
            case TOO_SIMPLE -> SubIntent.DETAIL_REQUIRED;
            case REPETITIVE -> SubIntent.AVOID_REPEAT_EXPLANATION;
            case MISSING_STORY_DETAIL -> SubIntent.STORYTELLING_CASE;
            default -> SubIntent.NONE;
        };
    }

    private EvolutionEvalCase.ExpectedBehavior expectedBehavior(FailureType type,
                                                                 String question,
                                                                 EvidenceUseMode evidenceUseMode) {
        return expectedBehavior(
            type, question, evidenceUseMode, criterionId(type), criterionDescription(type));
    }

    private EvolutionEvalCase.ExpectedBehavior expectedBehavior(
        FailureType type,
        String question,
        EvidenceUseMode evidenceUseMode,
        String boundaryCriterionId,
        String boundaryCriterionDescription) {
        List<EvolutionEvalCase.ScoringCriterion> criteria = List.of(
            new EvolutionEvalCase.ScoringCriterion(
                "answer_current_question",
                "明确回答本轮问题“" + question + "”，给出与该问题焦点直接相关的信息。",
                1.0),
            new EvolutionEvalCase.ScoringCriterion(
                boundaryCriterionId, boundaryCriterionDescription, 1.0));
        return new EvolutionEvalCase.ExpectedBehavior(
            criteria, 2.0, new EvolutionEvalCase.EvidencePolicy(
                true, true, evidenceUseMode), 700);
    }

    private String criterionId(FailureType type) {
        return switch (type) {
            case TOO_CONCEPTUAL -> "concrete_explanation";
            case TOO_SIMPLE -> "explain_reasoning";
            case REPETITIVE -> "add_new_information";
            case MISSING_EXAMPLE -> "provide_requested_example";
            case MISSING_STORY_DETAIL -> "complete_story";
            case NOT_DIRECT -> "direct_answer";
            case OFF_TOPIC -> "stay_on_topic";
            default -> "answer_quality";
        };
    }

    private String criterionDescription(FailureType type) {
        return switch (type) {
            case TOO_CONCEPTUAL -> "开头直接进入具体生活场景，并用场景中的选择和结果解释目标概念。";
            case TOO_SIMPLE -> "给出核心结论，并完整说明前提、原因、机制以及这些步骤如何推出结论。";
            case REPETITIVE -> "承接历史对话，提供新的因果解释或影响分析，使本轮内容明显推进。";
            case MISSING_EXAMPLE -> "给出包含主体、选择、行动和结果的具体例子，并说明例子与概念的对应关系。";
            case MISSING_STORY_DETAIL -> "完整呈现故事的起点、发展、关键转折和结果，并回扣目标观点。";
            case NOT_DIRECT -> "第一段给出本轮问题的核心结论，随后用证据解释该结论。";
            case OFF_TOPIC -> "回答的主要内容集中解释本轮指定的因果关系和作用机制。";
            default -> "回答清晰、具体，并覆盖问题要求的核心信息。";
        };
    }

    private Map<String, EvidenceUseMode> generateEvidenceUseModes(
        List<MisunderstandingSignal> signals) {
        List<MisunderstandingSignal> minedSignals = signals.stream()
            .filter(signal -> !isCommonSignal(signal))
            .distinct()
            .toList();
        if (minedSignals.isEmpty() || modelClient == null) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(modelClient.chat(
                evidenceUseModePrompt(minedSignals))));
            JsonNode cases = root.isArray() ? root : root.path("cases");
            if (!cases.isArray()) {
                throw new IllegalArgumentException("cases must be a JSON array");
            }
            Set<String> expectedKeys = minedSignals.stream()
                .map(this::modeKey)
                .collect(java.util.stream.Collectors.toSet());
            Map<String, EvidenceUseMode> result = new java.util.LinkedHashMap<>();
            for (JsonNode node : cases) {
                String signalId = node.path("signalId").asText("").trim();
                String failureType = node.path("failureType").asText("").trim();
                String rawMode = node.has("evidenceUseMode")
                    ? node.path("evidenceUseMode").asText("")
                    : node.path("evidence_use_mode").asText("");
                String key = signalId + ":" + failureType;
                try {
                    EvidenceUseMode mode =
                        EvidenceUseMode.valueOf(rawMode.trim().toUpperCase());
                    if (expectedKeys.contains(key)) {
                        result.put(key, mode);
                    }
                } catch (IllegalArgumentException exception) {
                    log.warn("Self-Evolution signal={} 的 evidenceUseMode 非法，使用关键词兜底: {}",
                        signalId, rawMode);
                }
            }
            return Map.copyOf(result);
        } catch (Exception exception) {
            log.warn("Self-Evolution 测试用例 evidenceUseMode 生成失败，使用关键词兜底: {}",
                exception.getMessage());
            return Map.of();
        }
    }

    private String evidenceUseModePrompt(List<MisunderstandingSignal> signals) throws Exception {
        List<Map<String, Object>> input = signals.stream().map(signal -> Map.<String, Object>of(
            "signalId", signal.id(),
            "failureType", signal.failureType().name(),
            "sourceText", signal.sourceText(),
            "generatedQuestion", question(signal, commonTemplate(signal.failureType()), 0),
            "providedEvidence", signal.sourceText())).toList();
        return """
            你是测试用例生成器中的证据用途分类器。请根据每个测试任务的目标、问题和提供证据，
            直接为生成的测试用例输出 evidenceUseMode。不要依赖单个关键词机械分类，要判断回答中
            的具体内容在这个任务里承担什么用途。

            模式只能是：
            - STRICT_SOURCE：用户明确要求只能依据原文/给定资料，不能进行证据外补充。
            - SOURCE_GROUNDED_NARRATIVE：任务要求复述历史、原文、真实事件、出处或基于文本还原过程。
            - PEDAGOGICAL_ILLUSTRATION：任务是解释概念、原因或理论，生活人物、数字和场景用于帮助理解，
              不承担真实事件或原文记录声明。

            必须为每个输入恰好输出一项，保留原 signalId 和 failureType。
            只输出 JSON：
            {"cases":[
              {"signalId":"原值","failureType":"原值",
               "evidenceUseMode":"STRICT_SOURCE|SOURCE_GROUNDED_NARRATIVE|PEDAGOGICAL_ILLUSTRATION",
               "reason":"简短语义判断"}
            ]}

            inputs:
            %s
            """.formatted(objectMapper.writeValueAsString(input));
    }

    private EvidenceUseMode fallbackEvidenceUseMode(
        MisunderstandingSignal signal,
        String question) {
        String priorQuestion = section(signal.sourceText(), "问题：", "结论：");
        String intent = (priorQuestion + " " + question).toLowerCase();
        if (intent.matches(".*(只根据|只能依据|严格依据|不得补充|不要补充|逐字原文).*")) {
            return EvidenceUseMode.STRICT_SOURCE;
        }
        if (signal.failureType() == FailureType.MISSING_STORY_DETAIL
            || intent.matches(".*(真实.{0,8}(故事|案例|事件)|历史.{0,8}(事件|案例|故事|过程)|"
                + "原文.{0,8}(案例|故事|过程|复述)|出处|记载|事实核验|"
                + "基于.{0,8}(文本|原文).{0,8}(复述|讲述|还原)).*")) {
            return EvidenceUseMode.SOURCE_GROUNDED_NARRATIVE;
        }
        return EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION;
    }

    private boolean isCommonSignal(MisunderstandingSignal signal) {
        return signal.sourceText().isBlank()
            || "common".equals(String.valueOf(signal.metadata().get("source")));
    }

    private String modeKey(MisunderstandingSignal signal) {
        return signal.id() + ":" + signal.failureType().name();
    }

    private String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start = objectStart < 0 ? arrayStart
            : arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart);
        int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("model output is not JSON");
        }
        return text.substring(start, end + 1);
    }

    private String category(FailureType type) {
        return switch (type) {
            case MISSING_EXAMPLE -> "EXAMPLE_REQUEST";
            case MISSING_STORY_DETAIL -> "COMPLETE_STORY_REQUEST";
            case REPETITIVE -> "CROSS_TURN_SYNTHESIS";
            case TOO_CONCEPTUAL, TOO_SIMPLE -> "EXPLANATION_DEPTH";
            case NOT_DIRECT, OFF_TOPIC -> "RELEVANCE_AND_DIRECTNESS";
            default -> "ANSWER_QUALITY";
        };
    }

    private ReadingBoundarySpec boundarySpec(FailureType type, EvidenceUseMode mode) {
        ReadingBoundary boundary = switch (type) {
            case TOO_CONCEPTUAL -> ReadingBoundary.CONCEPT_EXPLANATION;
            case TOO_SIMPLE -> ReadingBoundary.CAUSAL_REASONING;
            case REPETITIVE -> ReadingBoundary.MULTI_TURN_FOLLOW_UP;
            case MISSING_EXAMPLE -> ReadingBoundary.PEDAGOGICAL_EXAMPLE;
            case MISSING_STORY_DETAIL -> ReadingBoundary.SOURCE_NARRATIVE;
            case OFF_TOPIC -> ReadingBoundary.MEMORY_TEXT_SEPARATION;
            default -> mode == EvidenceUseMode.STRICT_SOURCE
                ? ReadingBoundary.DIRECT_TEXT_FACT
                : ReadingBoundary.CONCEPT_EXPLANATION;
        };
        return new ReadingBoundarySpec(
            boundary,
            EvidenceCompleteness.PARTIAL,
            ConversationState.FOLLOW_UP);
    }

    private String signalQuestion(MisunderstandingSignal signal, int variant) {
        String first = question(signal, commonTemplate(signal.failureType()), 0);
        if (variant == 0) return first;
        return switch (signal.failureType()) {
            case MISSING_STORY_DETAIL ->
                "只把历史阅读摘要确认的态度变化作为事实骨架；需要补写时明确区分构造细节，"
                    + "再按起点、发展、转折和结果讲成故事。";
            case MISSING_EXAMPLE ->
                "围绕历史阅读摘要中的结论换一个具体场景说明，并逐步解释场景与结论的对应关系。";
            case TOO_SIMPLE ->
                "先指出历史阅读摘要能够确认的结论，再区分证据支持和一般推理，完整说明因果链。";
            case TOO_CONCEPTUAL ->
                "不要重复抽象定义；请从历史阅读摘要出发，用一个带行动和结果的场景解释观点。";
            case REPETITIVE ->
                "不要复述历史回答的结论；只补充它尚未说明的一层原因，并解释这一层如何推进理解。";
            case NOT_DIRECT ->
                "针对历史问题第一句直接作答，再分别说明摘要支持什么、目前不能确认什么。";
            case OFF_TOPIC ->
                "只围绕历史问题的核心焦点回答，并把 memory 摘要与可核验书籍事实明确区分。";
            default -> first + "请从另一个证据条件回答。";
        };
    }

    private String question(MisunderstandingSignal signal, CommonTemplate template, int variant) {
        String base = template.question();
        if (!signal.sourceText().isBlank()) {
            base = switch (signal.failureType()) {
                case MISSING_STORY_DETAIL ->
                    "根据提供的历史阅读摘要，把其中的态度变化讲成完整故事，包含起点、发展、转折和结果，并回扣观点。";
                case MISSING_EXAMPLE ->
                    "根据提供的历史阅读摘要，给出一个具体例子，并说明例子如何对应摘要中的观点。";
                case TOO_SIMPLE ->
                    "根据提供的历史阅读摘要，讲清其中结论的原因和推导过程。";
                case TOO_CONCEPTUAL ->
                    "根据提供的历史阅读摘要，用具体场景解释其中的核心观点。";
                case REPETITIVE ->
                    "承接提供的历史问答，只补充一个新的理解角度并说明原因。";
                case NOT_DIRECT ->
                    "针对提供的历史问题，先给核心结论，再解释理由。";
                case OFF_TOPIC ->
                    "围绕提供的历史问题焦点作答，并解释与结论直接相关的原因。";
                default -> template.question();
            };
        }
        return base;
    }

    private CommonTemplate commonTemplate(FailureType type) {
        return COMMON_TEMPLATES.getOrDefault(type, COMMON_TEMPLATES.get(FailureType.NOT_DIRECT));
    }

    private List<String> anchors(MisunderstandingSignal signal) {
        String source = signal.sourceText();
        if (source == null || source.isBlank()) return List.of();
        Set<String> result = new LinkedHashSet<>();
        for (String token : source.replaceAll("[：，。；、\\n]", " ").split("\\s+")) {
            String normalized = token.trim();
            if (normalized.length() >= 2 && normalized.length() <= 16
                && !isFeedbackPhrase(normalized)) {
                result.add(normalized);
            }
            if (result.size() >= 3) break;
        }
        return List.copyOf(result);
    }

    private boolean isFeedbackPhrase(String value) {
        return value.equals("问题") || value.equals("结论")
            || value.matches(".*(不理解|太概念|太简单|重复|没有例子|不要讲概念|直接举例|真实故事|完整讲出来|不是我要的|答非所问).*");
    }

    private int minimumChars(FailureType type) {
        return switch (type) {
            case TOO_SIMPLE, MISSING_STORY_DETAIL -> 140;
            case TOO_CONCEPTUAL, MISSING_EXAMPLE -> 100;
            default -> 60;
        };
    }

    private String previousAnswer(String source) {
        if (source == null) return "";
        int index = source.indexOf("结论：");
        return index < 0 ? "" : truncate(source.substring(index + 3), 300);
    }

    private String section(String source, String startMarker, String endMarker) {
        if (source == null) return "";
        int start = source.indexOf(startMarker);
        if (start < 0) return "";
        start += startMarker.length();
        int end = source.indexOf(endMarker, start);
        return truncate((end < 0 ? source.substring(start) : source.substring(start, end)).trim(), 300);
    }

    private Long resolvedBookId(Long value) {
        return value == null || value <= 0 ? 1L : value;
    }

    private Integer resolvedChapterIndex(Integer value) {
        return value == null || value <= 0 ? 1 : value;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }

    private static List<BoundaryTemplate> boundaryTemplates() {
        List<BoundaryTemplate> templates = new ArrayList<>();

        // Round one: every defined boundary appears before any boundary repeats.
        templates.add(bt(
            ReadingBoundary.DIRECT_TEXT_FACT, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.NOT_DIRECT, EvidenceUseMode.STRICT_SOURCE,
            "根据页面证据，作者认为村民长期留在当地的两个直接原因是什么？",
            "这一段主要解释什么？", "它解释农业社会为什么相对稳定。",
            "页面明确写道：耕作需要持续照料固定土地；代际居住形成的互助和熟人信用提高了迁移成本。",
            "use_text_facts", "只使用页面证据，准确给出固定土地和长期关系这两个原因。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.SOURCE_QUOTATION, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.NOT_DIRECT, EvidenceUseMode.STRICT_SOURCE,
            "请从提供的段落中摘出一句能够直接说明“认识需要逐步适应”的原句，不要改写。",
            "这段是否强调逐步认识？", "是，但上一轮没有给出原句。",
            "段落：被释放者不能立刻直视太阳。他先看影子，再看水中映像，随后看事物本身，最后才看太阳。",
            "quote_exact_source", "给出证据中确实存在的一句原文，不拼接或创造不存在的引语。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.INSUFFICIENT_EVIDENCE, EvidenceCompleteness.MISSING,
            ConversationState.SINGLE_TURN, FailureType.OFF_TOPIC, EvidenceUseMode.STRICT_SOURCE,
            "只根据当前页面回答：文中那位地主叫什么名字、出生于哪一年？",
            "这一段提到地主了吗？", "提到了一个群体，但没有具体姓名。",
            "当前页面只写道：一些中小地主起初反对农会，后来请求加入但未被接受。页面没有姓名和出生年份。",
            "state_evidence_gap", "明确说明姓名和出生年份均未提供，不猜测、不补写。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.CONFLICTING_EVIDENCE, EvidenceCompleteness.CONFLICTING,
            ConversationState.SINGLE_TURN, FailureType.NOT_DIRECT, EvidenceUseMode.STRICT_SOURCE,
            "两条版本说明给出的出版年份不一致。请指出冲突，并说明目前能否确定准确年份。",
            "这本书是哪一年出版的？", "上一轮直接采用了其中一个年份。",
            "版本说明A写出版于1984年；版本说明B写出版于1986年。当前没有版权页或其他可判定哪项正确的证据。",
            "surface_evidence_conflict", "同时呈现1984和1986的冲突，并说明证据不足以确定准确年份。", "HARD"));
        templates.add(bt(
            ReadingBoundary.CONCEPT_EXPLANATION, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.TOO_CONCEPTUAL,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "不要先下定义，请用乘客在公交和出租车之间选择的场景解释机会成本。",
            "机会成本是不是实际付出去的钱？", "不完全是，它关注被放弃的最佳替代收益。",
            "乘客只有30分钟，公交便宜但慢，出租车昂贵但能准时到达；选择公交会放弃准时到达的收益。",
            "explain_concept_in_scene", "直接进入选择场景，并说明被放弃的最佳替代收益为什么是机会成本。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.CAUSAL_REASONING, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.TOO_SIMPLE,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "为什么碎片化的信息切换会削弱连续判断？请从注意力重置、前提保留和证据比较讲清因果链。",
            "信息切换很快会有什么影响？", "可能使理解变得零散。",
            "灾难新闻后立即切换到广告会中断因果追踪；注意力反复重置，使读者难以保留前提并比较前后证据。",
            "explain_causal_chain", "给出从快速切换到注意力重置，再到无法保留前提和比较证据的完整链条。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.PEDAGOGICAL_EXAMPLE, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.MISSING_EXAMPLE,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "请用健身会员已经付费却不适合继续训练的生活例子解释沉没成本，并说明正确决策依据。",
            "已经付的钱是不是必须赚回来？", "已经无法收回的支出不应决定后续选择。",
            "会员年费已经支付且不能退款；继续训练会加重膝伤，改做游泳更符合当前健康收益。",
            "provide_pedagogical_example", "用人物、选择和结果解释已付年费为何是沉没成本，以及决策应看未来成本收益。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.SOURCE_NARRATIVE, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.MISSING_STORY_DETAIL,
            EvidenceUseMode.SOURCE_GROUNDED_NARRATIVE,
            "依据提供的过程证据，把囚徒从看影子到看见太阳讲成完整故事，并回扣“认识需要逐步适应”。",
            "走出洞穴是一瞬间完成的吗？", "不是，证据描述了多个适应阶段。",
            "囚徒起初只看影子；转身看火光时刺痛抗拒；走出洞外后先看水中映像，再看事物和夜空，最后才能直视太阳。",
            "reconstruct_supported_narrative", "完整覆盖起点、痛苦转折、逐级适应和结果，不把补充细节说成原文。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.MULTI_TURN_FOLLOW_UP, EvidenceCompleteness.COMPLETE,
            ConversationState.FOLLOW_UP, FailureType.REPETITIVE,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "不要重复“切换很快”这一结论；请只补充它为什么妨碍读者比较前后证据。",
            "为什么新闻让理解变碎片？", "因为不同消息切换得很快。",
            "快速切换会清空上一条消息仍在工作记忆中的前提，读者还没比较证据，注意力就被下一主题占用。",
            "advance_follow_up", "承接上一轮但不复述旧结论，新增工作记忆和证据比较机制。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.MEMORY_TEXT_SEPARATION, EvidenceCompleteness.PARTIAL,
            ConversationState.CORRECTION, FailureType.OFF_TOPIC, EvidenceUseMode.STRICT_SOURCE,
            "历史记忆说作者赞成这一政策，但当前页面只描述政策结果。现在能否说这是作者立场？",
            "作者是不是赞成这项政策？", "之前记录里似乎说作者赞成。",
            "memory摘要：之前回答称作者赞成该政策。current_page：政策实施后产量提高，但没有作者态度或评价语句。",
            "separate_memory_from_text", "指出 memory 不是原文证据；当前页面只能确认结果，不能确认作者立场。", "HARD"));

        // Round two: new topics and different evidence/conversation conditions.
        templates.add(bt(
            ReadingBoundary.DIRECT_TEXT_FACT, EvidenceCompleteness.COMPLETE,
            ConversationState.FOLLOW_UP, FailureType.NOT_DIRECT, EvidenceUseMode.STRICT_SOURCE,
            "根据证据直接回答：实验参与者在第二阶段做了什么？",
            "实验第一阶段做了什么？", "参与者先独立阅读材料。",
            "实验分两阶段：第一阶段独立阅读；第二阶段两人讨论并共同修改答案。",
            "answer_requested_fact", "第一句直接回答第二阶段是两人讨论并共同修改答案，不转去复述第一阶段。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.SOURCE_QUOTATION, EvidenceCompleteness.PARTIAL,
            ConversationState.SINGLE_TURN, FailureType.OFF_TOPIC, EvidenceUseMode.STRICT_SOURCE,
            "证据只有释义，没有原句。请问能否提供作者关于“信任”的逐字引语？",
            "作者如何理解信任？", "上一轮给出了概括。",
            "编辑摘要：作者认为反复交往会形成稳定预期。当前未提供书页扫描、逐字文本或引号内原句。",
            "refuse_unavailable_quote", "说明目前只能提供释义，不能把编辑摘要改写成作者逐字引语。", "HARD"));
        templates.add(bt(
            ReadingBoundary.INSUFFICIENT_EVIDENCE, EvidenceCompleteness.PARTIAL,
            ConversationState.FOLLOW_UP, FailureType.NOT_DIRECT, EvidenceUseMode.STRICT_SOURCE,
            "证据说明销量上升，但没有原因分析。请只根据证据回答销量为什么上升。",
            "销量发生了什么变化？", "第二季度销量比第一季度高。",
            "表格只列出第一季度销量1200册、第二季度销量1680册；没有营销、价格、渠道或读者调查信息。",
            "avoid_unsupported_cause", "确认销量上升，但明确现有证据不能判断原因，并指出缺少哪些信息。", "HARD"));
        templates.add(bt(
            ReadingBoundary.CONFLICTING_EVIDENCE, EvidenceCompleteness.CONFLICTING,
            ConversationState.CORRECTION, FailureType.OFF_TOPIC, EvidenceUseMode.STRICT_SOURCE,
            "上一轮说主人公主动离开，当前页却写他被迫离开。请根据现有证据纠正并说明冲突。",
            "主人公为什么离开家乡？", "之前记录说他主动外出寻找机会。",
            "recent_dialogue：上一轮回答称他主动离开。current_page：原句写“在债主逼迫下，他不得不连夜离乡”。",
            "resolve_dialogue_text_conflict", "以当前页原句为准纠正上一轮，并明确说明历史回答与文本冲突。", "HARD"));
        templates.add(bt(
            ReadingBoundary.CONCEPT_EXPLANATION, EvidenceCompleteness.COMPLETE,
            ConversationState.FOLLOW_UP, FailureType.TOO_CONCEPTUAL,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "不要重复“确认偏误是只看支持自己的信息”这一定义，请用读者选择书评的过程说明它如何发生。",
            "确认偏误是什么？", "它是更注意支持既有观点的信息。",
            "读者先认定一本书很差，只收藏负面书评，跳过正面评价，最后把筛选后的材料当成全面证据。",
            "explain_mechanism_with_example", "用选择、忽略和形成结论三个动作解释确认偏误，不只重复定义。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.CAUSAL_REASONING, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.TOO_SIMPLE,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "农业社会人口流动为什么通常较少？请解释土地照料、熟人信用和迁移成本之间的关系。",
            "农业社会的人为什么长期定居？", "生产依赖土地，也形成长期关系。",
            "耕作要求持续照料固定土地；代际互助和熟人信用依赖长期交往；迁移会同时失去生产条件和关系资源。",
            "connect_multiple_causes", "说明土地依赖与关系资源如何共同提高迁移成本并降低流动。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.PEDAGOGICAL_EXAMPLE, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.MISSING_EXAMPLE,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "用一个新社交平台因用户太少而难以吸引更多人的例子解释网络效应。",
            "网络效应为什么会形成强者更强？", "产品价值会随使用者数量增加。",
            "平台只有十名用户时很难找到朋友；用户增至一万人后，每位新用户更容易找到认识的人，加入价值随之提高。",
            "map_example_to_principle", "给出用户数量、可连接对象和加入价值的变化，并回扣网络效应。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.SOURCE_NARRATIVE, EvidenceCompleteness.PARTIAL,
            ConversationState.SINGLE_TURN, FailureType.MISSING_STORY_DETAIL,
            EvidenceUseMode.SOURCE_GROUNDED_NARRATIVE,
            "摘要只确认他们从反对农会转为请求加入但未被接受。请据此讲故事，并区分事实骨架和构造细节。",
            "有没有这一态度变化的完整故事？", "摘要只有态度变化结论，没有人物和过程。",
            "历史阅读摘要确认：部分中小地主、富农和中农起初反对农会，后来请求加入，但未被接受。摘要没有姓名、日期和具体事件。",
            "narrate_from_partial_summary", "以摘要结论为故事骨架；补写部分整体声明为构造，不伪称真实人物或原文情节。", "HARD"));
        templates.add(bt(
            ReadingBoundary.MULTI_TURN_FOLLOW_UP, EvidenceCompleteness.COMPLETE,
            ConversationState.CORRECTION, FailureType.REPETITIVE,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "上一轮把机会成本说成支付价格。请直接纠正这个错误，并用同一个早餐选择说明正确含义。",
            "买早餐的机会成本是多少？", "就是早餐实际支付的价格。",
            "顾客只能在现做三明治和普通面包中选一个；机会成本是被放弃的最佳方案带来的收益，不是已选方案价格。",
            "correct_previous_answer", "明确纠错，不延续错误定义；用被放弃的最佳早餐收益说明机会成本。", "HARD"));
        templates.add(bt(
            ReadingBoundary.MEMORY_TEXT_SEPARATION, EvidenceCompleteness.CONFLICTING,
            ConversationState.CORRECTION, FailureType.OFF_TOPIC, EvidenceUseMode.STRICT_SOURCE,
            "memory 记得结局是和解，当前页面写双方仍然决裂。回答时应采用哪个结局？",
            "他们最后和解了吗？", "历史记录里说已经和解。",
            "memory摘要：双方最终和解。current_page原句：会议结束后两人各自离开，此后再未往来。",
            "prioritize_current_text", "明确区分 memory 与当前文本，并以当前页面为本题书籍事实依据。", "HARD"));

        // Round three: additional topics complete pairwise coverage without suffix-only variants.
        templates.add(bt(
            ReadingBoundary.DIRECT_TEXT_FACT, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.NOT_DIRECT, EvidenceUseMode.STRICT_SOURCE,
            "页面列出的三种逐步适应对象依次是什么？请按原顺序回答。",
            "这一过程有没有顺序？", "有，从较容易观看的对象逐步过渡。",
            "页面顺序为：先看水中映像，再看夜空中的星月，最后在白天看太阳。",
            "preserve_source_order", "严格按水中映像、星月、太阳的顺序回答，不增加其他阶段。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.SOURCE_QUOTATION, EvidenceCompleteness.COMPLETE,
            ConversationState.FOLLOW_UP, FailureType.NOT_DIRECT, EvidenceUseMode.STRICT_SOURCE,
            "请区分原句和解释：先引用“选择意味着放弃”这句话，再用一句话解释。",
            "机会成本与放弃有什么关系？", "选择一个方案会失去其他可能收益。",
            "原句：“每一次选择，同时也是一次放弃。”上下文说明，被放弃方案中价值最高者构成机会成本。",
            "separate_quote_and_explanation", "逐字引用给定原句，并把后续解释明确放在引语之外。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.INSUFFICIENT_EVIDENCE, EvidenceCompleteness.MISSING,
            ConversationState.CORRECTION, FailureType.OFF_TOPIC, EvidenceUseMode.STRICT_SOURCE,
            "上一轮给出了作者的大学经历，但当前资料完全没有生平信息。请重新回答作者毕业于哪所大学。",
            "作者毕业于哪所大学？", "上一轮回答了一个具体校名，但没有给来源。",
            "当前提供的页面只有作品观点摘要，没有作者简历、教育经历或学校名称。",
            "withdraw_unsupported_answer", "撤回无来源校名，说明当前资料无法回答，并指出需要作者简历等证据。", "HARD"));
        templates.add(bt(
            ReadingBoundary.CONFLICTING_EVIDENCE, EvidenceCompleteness.CONFLICTING,
            ConversationState.SINGLE_TURN, FailureType.NOT_DIRECT, EvidenceUseMode.STRICT_SOURCE,
            "请指出两个译本对同一术语采用了“共同体”和“社群”两种不同译法，"
                + "并说明为什么当前证据不能判断哪种译法更准确。",
            "这个术语应该怎么翻译？", "不同译本可能有不同处理。",
            "译本甲使用“共同体”，译本乙使用“社群”；未提供原文术语、译者说明或版本权威信息。",
            "preserve_translation_uncertainty", "列出两种译法及缺失信息，避免无证据裁定哪一个唯一正确。", "HARD"));
        templates.add(bt(
            ReadingBoundary.CONCEPT_EXPLANATION, EvidenceCompleteness.COMPLETE,
            ConversationState.SINGLE_TURN, FailureType.TOO_CONCEPTUAL,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "用老师示范骑车但学生仍需亲自练习的场景解释“默会知识难以完全写成规则”。",
            "为什么有些技能看说明书仍学不会？", "部分技能依赖身体经验和情境判断。",
            "老师可以说出握把、平衡和刹车原则，但学生必须在实际摇晃和纠正中形成身体判断。",
            "embody_abstract_concept", "通过可说规则与亲身调整的差别解释默会知识。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.CAUSAL_REASONING, EvidenceCompleteness.PARTIAL,
            ConversationState.FOLLOW_UP, FailureType.TOO_SIMPLE,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "资料只说损失感受更强。请在不引入具体倍数的前提下，解释它如何导致规避损失的决策。",
            "损失和收益的感受一样强吗？", "资料说同额损失的感受通常更强。",
            "证据仅确认：以现状为参照点时，同等金额的损失比收益引起更强心理反应，因此人更倾向避免损失。没有提供实验比例。",
            "reason_without_invented_numbers", "用感受不对称推出决策倾向，不增加研究名称、比例或具体倍数。", "HARD"));
        templates.add(bt(
            ReadingBoundary.PEDAGOGICAL_EXAMPLE, EvidenceCompleteness.COMPLETE,
            ConversationState.FOLLOW_UP, FailureType.MISSING_EXAMPLE,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "换一个不同于买早餐的例子，用学生在复习和兼职之间选择解释机会成本。",
            "之前已经用早餐解释过机会成本。", "用户希望换一个领域，不要重复早餐。",
            "学生周末只有四小时，可以复习提高考试表现，也可以兼职获得收入；选择兼职会放弃复习带来的最佳预期收益。",
            "provide_non_repetitive_example", "使用学习与兼职场景，并说明所放弃的最佳替代收益。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.SOURCE_NARRATIVE, EvidenceCompleteness.COMPLETE,
            ConversationState.FOLLOW_UP, FailureType.MISSING_STORY_DETAIL,
            EvidenceUseMode.SOURCE_GROUNDED_NARRATIVE,
            "不要重讲开头；只根据证据补全实验从出现异常到研究者修正假设的转折和结果。",
            "实验起初按旧假设顺利进行。", "上一轮只讲到了实验开始。",
            "第三轮数据与旧假设相反；研究者复查仪器后确认数据有效，于是修改假设并设计新的对照组，后续结果支持新假设。",
            "complete_missing_narrative_stage", "只补充异常、核验、修改假设和新结果，不重复实验开头。", "HARD"));
        templates.add(bt(
            ReadingBoundary.MULTI_TURN_FOLLOW_UP, EvidenceCompleteness.COMPLETE,
            ConversationState.FOLLOW_UP, FailureType.NOT_DIRECT,
            EvidenceUseMode.PEDAGOGICAL_ILLUSTRATION,
            "这里的“它”具体指哪一种成本？请结合上一轮一句话回答，再说明判断依据。",
            "选择设备时会放弃广告带来的新增顾客，这就是机会成本。", "那它为什么不是设备价格？",
            "最近对话中“它”紧接着指向机会成本；页面区分设备购买价格与放弃广告所失去的新增顾客收益。",
            "resolve_cross_turn_reference", "明确“它”指机会成本，并用上下文解释为何不是设备价格。", "MEDIUM"));
        templates.add(bt(
            ReadingBoundary.MEMORY_TEXT_SEPARATION, EvidenceCompleteness.PARTIAL,
            ConversationState.FOLLOW_UP, FailureType.NOT_DIRECT, EvidenceUseMode.STRICT_SOURCE,
            "用户记忆显示我偏好故事化解释，但本题要求只摘录原文。应该如何回答？",
            "请记住我喜欢听故事。", "已记录这种表达偏好。",
            "profile/memory：用户偏好故事化解释。current_page原句：“制度依靠反复互动形成稳定预期。”本轮要求：只摘录原文，不补充。",
            "keep_preference_out_of_fact", "遵守本轮严格原文要求；偏好只能影响风格，不能改变证据边界或增加故事。", "HARD"));
        return List.copyOf(templates);
    }

    private static BoundaryTemplate bt(ReadingBoundary boundary,
                                       EvidenceCompleteness completeness,
                                       ConversationState conversationState,
                                       FailureType failureType,
                                       EvidenceUseMode mode,
                                       String question,
                                       String priorQuestion,
                                       String priorAnswer,
                                       String evidence,
                                       String criterionId,
                                       String criterionDescription,
                                       String difficulty) {
        return new BoundaryTemplate(
            new ReadingBoundarySpec(boundary, completeness, conversationState),
            failureType,
            new CommonTemplate(question, priorQuestion, priorAnswer, evidence, mode),
            criterionId,
            criterionDescription,
            criterionDescription,
            difficulty);
    }

    private record CommonTemplate(String question,
                                  String priorQuestion,
                                  String priorAnswer,
                                  String evidence,
                                  EvidenceUseMode evidenceUseMode) {
    }

    private record BoundaryTemplate(ReadingBoundarySpec spec,
                                    FailureType failureType,
                                    CommonTemplate template,
                                    String criterionId,
                                    String criterionDescription,
                                    String answerGuidance,
                                    String difficulty) {
    }
}
