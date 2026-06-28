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
    private static final List<FailureType> COMMON_TYPES = List.of(
        FailureType.TOO_CONCEPTUAL,
        FailureType.TOO_SIMPLE,
        FailureType.REPETITIVE,
        FailureType.MISSING_EXAMPLE,
        FailureType.MISSING_STORY_DETAIL,
        FailureType.NOT_DIRECT,
        FailureType.OFF_TOPIC);

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
        int caseCount = Math.max(1, Math.min(100, requestedCaseCount));
        List<MisunderstandingSignal> inputs = new ArrayList<>(
            minedSignals == null ? List.of() : minedSignals);
        int seedIndex = 0;
        while (inputs.size() < COMMON_TYPES.size()) {
            FailureType type = COMMON_TYPES.get(seedIndex % COMMON_TYPES.size());
            inputs.add(new MisunderstandingSignal(
                "common-" + seedIndex, "", "", type, 0.5,
                defaultBookId, defaultChapterIndex, Map.of("source", "common")));
            seedIndex++;
        }
        Map<String, EvidenceUseMode> generatedModes = generateEvidenceUseModes(
            inputs.subList(0, Math.min(inputs.size(), caseCount)));

        List<EvolutionEvalCase> cases = new ArrayList<>(caseCount);
        Set<String> seenQuestions = new LinkedHashSet<>();
        int cursor = 0;
        while (cases.size() < caseCount) {
            MisunderstandingSignal signal = inputs.get(cursor % inputs.size());
            int variant = cursor / inputs.size();
            CommonTemplate template = commonTemplate(signal.failureType());
            String question = question(signal, template, variant);
            cursor++;
            if (!seenQuestions.add(question)) {
                continue;
            }
            AiChatRequest request = request(signal, userId, defaultBookId, defaultChapterIndex, question, template);
            List<EvolutionEvalCase.DialogueTurn> dialogue = dialogue(signal, template);
            List<EvolutionEvalCase.CollectedEvidence> evidence =
                evidence(signal, template, dialogue, cases.size());
            EvidenceUseMode evidenceUseMode = isCommonSignal(signal)
                ? template.evidenceUseMode()
                : generatedModes.getOrDefault(modeKey(signal), fallbackEvidenceUseMode(signal, question));
            cases.add(new EvolutionEvalCase(
                "evolution-" + String.format("%02d", cases.size() + 1),
                signal.id(),
                request,
                signal.failureType(),
                anchors(signal),
                minimumChars(signal.failureType()),
                previousAnswer(signal.sourceText()),
                dialogue,
                evidence,
                mcpResults(signal, template, dialogue),
                finalAnswerInput(signal.failureType(), question, evidenceUseMode),
                expectedBehavior(signal.failureType(), question, evidenceUseMode),
                variant == 0 ? "MEDIUM" : "HARD",
                category(signal.failureType())));
        }
        return List.copyOf(cases);
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
            criterionDescription(type));
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
        List<EvolutionEvalCase.ScoringCriterion> criteria = List.of(
            new EvolutionEvalCase.ScoringCriterion(
                "answer_current_question",
                "明确回答本轮问题“" + question + "”，给出与该问题焦点直接相关的信息。",
                1.0),
            new EvolutionEvalCase.ScoringCriterion(
                criterionId(type), criterionDescription(type), 1.0));
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
        return base + suffix(variant + 1);
    }

    private String suffix(int variant) {
        return variant <= 1 ? "" : "（变体" + variant + "）";
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

    private record CommonTemplate(String question,
                                  String priorQuestion,
                                  String priorAnswer,
                                  String evidence,
                                  EvidenceUseMode evidenceUseMode) {
    }
}
