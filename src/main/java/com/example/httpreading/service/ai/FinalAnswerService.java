package com.example.httpreading.service.ai;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.evolution.EvolvablePromptTemplate;
import com.example.httpreading.evolution.PromptOverride;
import com.example.httpreading.service.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FinalAnswerService {
    private static final Logger log = LoggerFactory.getLogger(FinalAnswerService.class);
    private static final String DEFAULT_EVOLVABLE_POLICY = """
        四、回答策略
        - 直接回答问题，不要模板化开头，例如“简单说”“总的来说”。
        - 表达要像给普通读者解释；普通阅读追问控制在 4-6 段，每段短一些。
        - 追问时只回答新增点，不重复上一轮解释。
        - 如果有补充解释，明确区分“当前资料支持什么”和“补充理解是什么”。
        - subIntent=CONTRASTIVE_WHY 时，重点回答“为什么仍然选择 B”：更大问题、优先目标、权衡取舍和代价。
        """;

    private final ModelClient modelClient;

    public FinalAnswerService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public String answer(AiChatRequest request, ChatPlan plan, CollectedEvidence evidence) {
        return answer(request, plan, evidence, PromptOverride.none());
    }

    public String answer(AiChatRequest request,
                         ChatPlan plan,
                         CollectedEvidence evidence,
                         PromptOverride promptOverride) {
        PromptOverride override = promptOverride == null ? PromptOverride.none() : promptOverride;
        String prompt = buildPrompt(request, plan, evidence, override.finalAnswerPatch());
        logPrompt("FINAL_ANSWER", prompt);
        String answer = modelClient.chat(prompt);
        String issue = qualityIssue(plan, evidence, answer);
        if (issue.isBlank()) {
            return answer;
        }
        String repairPrompt = repairPrompt(prompt, answer, issue, plan);
        logPrompt("REPAIR_ANSWER", repairPrompt);
        return modelClient.chat(repairPrompt);
    }

    private void logPrompt(String stage, String prompt) {
        log.info("""
            ===== AI_MODEL_PROMPT_BEGIN stage={} chars={} =====
            {}
            ===== AI_MODEL_PROMPT_END stage={} =====
            """, stage, prompt == null ? 0 : prompt.length(), prompt, stage);
    }

    String buildPrompt(AiChatRequest request, ChatPlan plan, CollectedEvidence evidence) {
        return buildPrompt(request, plan, evidence, "");
    }

    String buildPrompt(AiChatRequest request,
                       ChatPlan plan,
                       CollectedEvidence evidence,
                       String candidatePatch) {
        String evidenceText = evidence == null || evidence.formattedEvidence().isBlank()
            ? "没有收集到足够证据。"
            : evidence.formattedEvidence();
        String guidance = plan == null ? "" : plan.answerGuidance();
        String fixedContract = """
            你是这个阅读系统中的 AI 助手。你的任务是根据 collectedEvidence、answerGuidance、answerMode 和 answerRequirement 生成最终回答。
            
            一、硬边界
            - 不允许提出、规划或执行工具调用。
            - 回答以用户问题为中心，collectedEvidence 用于提供上下文、事实锚点和边界。
            - 不要把公共知识、类比、推理补充说成原文或工具结果。
            - 证据不足时说明缺少什么，不要伪造来源、人物、时间、地点或搜索结果。
            - 使用 memoryRefs 时只能说“历史记忆/之前记录”，不能说成“本次搜索结果”；最近对话只用于理解追问，不能大段复述。
            - type=profile_detail 或 type=profile_search_result 且 usage=style_guidance 的证据是用户画像，只能用于调整解释风格、回答深度、例子类型和背景补充方式。
            - 用户画像不是书籍原文、不是 RAG 检索结果、不是事实来源；不能用画像替代原文证据。
            - 如果画像和当前问题无关，或 profile.search_relevant 没有匹配结果，不要提画像，正常回答。
            - 用户没有主动要求时，不要频繁显式说“根据你的画像”。

            二、answerMode
            - TEXT_ONLY：只能基于 collectedEvidence。资料没有就说明没有，不补充资料外事实。
            - CONTEXT_ANCHORED_MODEL_KNOWLEDGE：以 collectedEvidence 为锚点，可以用公共知识补充解释、背景、类比或帮助理解型例子；资料没有直接解释时先说明“当前资料没有直接解释”，再给一般知识辅助理解，并明确这不是原文直接证据。
            - EXTERNAL_SEARCH_REQUIRED：必须依据 externalMcpRefs。没有 externalMcpRefs 时，说明当前没有实际执行外部/GitHub/实时搜索，不能声称搜索过。

            三、evidenceStrictness
            - STRICT：严格依据证据，不能扩展事实细节。
            - MEDIUM：优先依据证据；可做有限常识补充，但要标明补充性质。
            - LOOSE：可以更自由地辅助理解，但不能伪造来源。

            四、固定输出契约
            - 只输出面向用户的最终回答，不输出规划、工具调用或内部检查过程。
            - 回答必须围绕 originalQuestion，并遵守下面的运行时输入和证据边界。
            - 最后列出关键来源；没有证据时写“关键来源：当前未收集到足够证据；以上为一般知识辅助理解”。

            五、answerRequirement
            - allowModelKnowledge=true：允许公共知识补充，但不能伪装成原文证据。
            - mustDistinguishTextEvidenceAndSupplement=true：必须区分资料证据和补充解释。
            - requiresConcreteExample=true：需要给例子。先判断是理解辅助型例子，还是严格考据型真实案例。
            - requiresStorytelling=true：直接进入案例，讲起点、发展、转折、结果，再回扣原文观点。
            - requiresDetailedProcess=true：讲清过程，不只给结论。
            - avoidConceptualOpening=true：不要概念式开头。
            - avoidRepeatingPreviousExplanation=true：不要复述上一轮内容。

            案例处理规则：
            - 如果用户要求的是“理解辅助型例子”，并且 answerMode=CONTEXT_ANCHORED_MODEL_KNOWLEDGE 或 allowModelKnowledge=true，可以使用公共知识给出具体例子，但必须说明这是补充理解，不是当前资料直接提供的案例。
            - 如果用户要求的是“严格考据型真实案例”，例如要求出处、时间、地点、姓名、原文案例或外部核验，必须依据 collectedEvidence 或 externalMcpRefs；证据不足时说明缺少什么。
            - 如果 collectedEvidence 只有背景线索，没有完整案例，可以先说明资料支持的背景，再给出“帮助理解的补充案例”，但不能说成原文案例。

            当前阅读位置：bookId=%s, chapterIndex=%s
            originalQuestion：%s
            standaloneQuestion：%s
            subIntent：%s
            answerMode=%s
            evidenceStrictness=%s
            answerRequirement：%s
            answerGuidance：%s

            collectedEvidence：
            %s
            """.formatted(
            request.getBookId(),
            request.getChapterIndex(),
            plan == null ? request.getQuestion() : plan.originalQuestion(),
            plan == null ? request.getQuestion() : plan.standaloneQuestion(),
            plan == null ? SubIntent.NONE : plan.subIntent(),
            plan == null ? AnswerMode.TEXT_ONLY : plan.answerMode(),
            plan == null ? EvidenceStrictness.STRICT : plan.evidenceStrictness(),
            plan == null ? AnswerRequirement.normal() : plan.answerRequirement(),
            guidance,
            evidenceText);
        return new EvolvablePromptTemplate(
            fixedContract, DEFAULT_EVOLVABLE_POLICY).render(candidatePatch);
    }

    public static String effectiveEvolvablePolicy(String candidatePatch) {
        return new EvolvablePromptTemplate(
            "", DEFAULT_EVOLVABLE_POLICY).renderPolicy(candidatePatch);
    }

    private String qualityIssue(ChatPlan plan, CollectedEvidence evidence, String answer) {
        if (plan == null || answer == null || answer.isBlank()) {
            return "";
        }
        AnswerRequirement requirement = plan.answerRequirement();
        if (requirement == null) {
            return "";
        }
        if (plan.answerMode() == AnswerMode.EXTERNAL_SEARCH_REQUIRED) {
            String issue = externalSearchIssue(evidence, answer);
            if (!issue.isBlank()) {
                return issue;
            }
        }
        if (requirement.avoidRepeatingPreviousExplanation()
            && answerSubstantiallyRepeatsRecentAssistant(evidence, answer)) {
            return "回答和最近一轮助手回答高度重合，用户是在继续追问，不能把上一轮列表或解释原样再讲一遍。";
        }
        if (plan.subIntent() == SubIntent.CONTRASTIVE_WHY) {
            String issue = contrastiveWhyIssue(plan, answer);
            if (!issue.isBlank()) {
                return issue;
            }
        }
        if (requirement.requiresConcreteExample()) {
            if (answer.contains("当前资料只支持概念解释")) {
                if (requirement.allowModelKnowledge() && !isRealCaseRequest(plan)) {
                    return "用户要求的是理解辅助型例子，允许模型常识补充，不能直接用“当前资料只支持概念解释”拒答。";
                }
                return "";
            }
            if (!hasReliableConcreteEvidence(evidence) && hasInsufficientConcreteEvidenceNotice(answer)) {
                return "";
            }
            if (!requirement.allowModelKnowledge() && !hasReliableConcreteEvidence(evidence) && !hasConcreteCaseSignal(answer)) {
                return "用户要求具体案例，但 collectedEvidence 没有可靠案例证据，且回答没有说明资料不足。";
            }
            if (isTaxBurdenQuestion(plan) && !hasConcreteTaxFeeItems(answer)) {
                return "用户要求税费负担的具体例子，但回答没有给出足够具体的税、费、劳务或摊派项目。";
            }
            ConceptBoundary boundary = conceptBoundary(plan);
            if (boundary.required() && !answersConceptBoundary(boundary, answer)) {
                return "用户问的是“" + boundary.focusTerm() + "”这个焦点术语，不是泛泛问“"
                    + boundary.broaderTerm() + "”；回答需要先说明二者区别，并给出属于焦点术语本身的例子。";
            }
            if (!hasConcreteCaseSignal(answer)) {
                return "用户要求具体案例，但回答没有出现具体人物、企业、组织或历史事件。";
            }
            if (!answersHowCaseMapsToText(answer)) {
                return "用户要求案例说明，但回答没有说明这个例子如何对应原文观点。";
            }
        }
        if (requirement.requiresStorytelling()) {
            if (startsWithConceptExplanation(answer)) {
                return "用户要求讲完整故事，但回答先从概念解释开头。";
            }
            if (!hasStoryProcess(answer)) {
                return "用户要求完整说出来，但回答没有讲清起点、发展、转折或结果。";
            }
            if (!hasMinimumDetailDensity(answer)) {
                return "用户要求完整案例故事，但回答缺少企业/地区/人物/业务/政策/阶段/转折等具体信息。";
            }
        }
        if (requirement.avoidRepeatingPreviousExplanation() && looksLikeRepeatedConceptAnswer(answer)) {
            return "用户是在追问新角度，但回答仍像上一轮抽象解释的复述。";
        }
        return "";
    }

    private String repairPrompt(String originalPrompt, String failedAnswer, String issue, ChatPlan plan) {
        return """
            下面这个回答没有通过质量检查，请只输出重写后的最终回答，不要解释检查过程。

            不合格原因：
            %s

            必须修正的具体点：
            - 针对上面的“不合格原因”重写，不要只做同义改写。
            - 继续遵守原始生成提示中的 answerMode、evidenceStrictness、answerRequirement 和证据边界。
            - TEXT_ONLY 不能补充资料外事实；EXTERNAL_SEARCH_REQUIRED 且没有 externalMcpRefs 时必须说明没有实际执行外部/GitHub/实时搜索。
            - 公共知识、帮助理解型例子、类比和推理补充必须标明是“补充理解”，不能伪装成原文证据或工具结果。
            - 严格考据型真实案例必须有 collectedEvidence 或 externalMcpRefs 支持；证据不足时说明缺少姓名、地点、日期、出处或外部核验。
            - 如果要求例子、故事或过程，要给出具体例子、起点、发展、转折、结果，并说明它如何对应原文观点。
            - 如果是在追问，不要复述上一轮内容；如果是 CONTRASTIVE_WHY，重点回答为什么仍然选择该政策或行动。

            原始生成提示：
            %s

            未通过的回答：
            %s

            当前 subIntent：%s
            当前 answerMode：%s
            """.formatted(
            issue,
            originalPrompt,
            failedAnswer == null ? "" : failedAnswer,
            plan == null ? SubIntent.NONE : plan.subIntent(),
            plan == null ? AnswerMode.TEXT_ONLY : plan.answerMode());
    }

    private String externalSearchIssue(CollectedEvidence evidence, String answer) {
        String text = normalize(answer);
        boolean hasExternalEvidence = evidence != null && !evidence.externalMcpRefs().isEmpty();
        if (!hasExternalEvidence && !text.matches(".*(没有|无法|不能|未实际|未执行|不可用|没有可用).*?(github|外部|实时|网页|搜索|工具).*")) {
            return "问题需要外部搜索，但没有 externalMcpRefs，回答必须明确说明当前没有实际执行外部/GitHub/实时搜索。";
        }
        boolean hasOnlyMemory = evidence != null && !evidence.memoryRefs().isEmpty() && evidence.externalMcpRefs().isEmpty();
        if (hasOnlyMemory && text.matches(".*(本次搜索|搜索结果|github搜索结果|实时结果|刚搜索到).*")) {
            return "回答把历史记忆说成了本次 GitHub 或外部实时搜索结果。";
        }
        return "";
    }

    private boolean hasReliableConcreteEvidence(CollectedEvidence evidence) {
        return evidence != null && evidence.items().stream()
            .anyMatch(item -> item.type().startsWith("rag") || "current_page".equals(item.type()));
    }

    private boolean hasInsufficientConcreteEvidenceNotice(String answer) {
        String text = normalize(answer);
        return text.matches(".*(当前资料|collectedEvidence|证据|材料|原文).*?(缺少|没有|不足|未提供|找不到).*?(具体案例|真实案例|人物|事件|姓名|地点|日期|出处|线索).*")
            || text.matches(".*(需要|建议).*?(更多资料|外部搜索|明确出处|具体线索).*");
    }

    private boolean isRealCaseRequest(ChatPlan plan) {
        if (plan == null) {
            return false;
        }
        String text = normalize(plan.originalQuestion() + " " + plan.standaloneQuestion() + " " + plan.answerGuidance());
        return plan.subIntent() == SubIntent.HISTORICAL_CASE
            || plan.subIntent() == SubIntent.STORYTELLING_CASE
            || plan.answerRequirement().requiresStorytelling()
            || text.matches(".*(真实案例|具体案例|历史案例|具体人物|具体事件|某一个企业|某个企业|完整故事|完整地说|有出处).*");
    }

    private boolean hasConcreteCaseSignal(String answer) {
        String text = normalize(answer);
        int score = 0;
        if (hasTimeInfo(text)) {
            score++;
        }
        if (hasEntityInfo(text)) {
            score++;
        }
        if (hasPlaceInfo(text)) {
            score++;
        }
        if (hasStoryProcess(answer)) {
            score++;
        }
        if (answersHowCaseMapsToText(answer)) {
            score++;
        }
        return score >= 2
            || hasConcreteTaxFeeItems(answer)
            || text.matches(".*[《》].*");
    }

    private boolean answersHowCaseMapsToText(String answer) {
        String text = normalize(answer);
        return text.contains("说明")
            || text.contains("对应")
            || text.contains("体现")
            || text.contains("回到原文")
            || text.contains("这正是")
            || text.contains("原文")
            || text.contains("这里")
            || text.contains("重点")
            || text.contains("负担");
    }

    private boolean startsWithConceptExplanation(String answer) {
        String text = normalize(answer);
        return text.startsWith("简单说")
            || text.startsWith("总的来说")
            || text.startsWith("这句话的意思是")
            || text.startsWith("可以理解为");
    }

    private boolean hasStoryProcess(String answer) {
        String text = normalize(answer);
        int score = 0;
        if (text.matches(".*(起初|最初|一开始|背景|创办|成立).*")) {
            score++;
        }
        if (text.matches(".*(后来|发展|扩大|扩张|市场|生产).*")) {
            score++;
        }
        if (text.matches(".*(转折|关键|改制|承包|政策|放权|竞争).*")) {
            score++;
        }
        if (text.matches(".*(结果|最后|说明|体现|回扣|对应).*")) {
            score++;
        }
        return score >= 3;
    }

    private boolean hasMinimumDetailDensity(String answer) {
        String text = normalize(answer);
        int score = 0;
        if (hasEntityInfo(text)) {
            score++;
        }
        if (hasPlaceInfo(text)) {
            score++;
        }
        if (hasPersonOrRoleInfo(text)) {
            score++;
        }
        if (text.matches(".*(产品|生产|业务|市场|经营|收入|土地|税费|农会|作保|求情|组织|行动).*")) {
            score++;
        }
        if (text.matches(".*(改革开放|承包|改制|政策|放权|地方政府).*")) {
            score++;
        }
        if (text.matches(".*(起初|最初|一开始|从前|后来|此刻|转折|阶段|扩大|结果|不可得|接纳).*")) {
            score++;
        }
        return score >= 3;
    }

    private boolean hasTimeInfo(String text) {
        return text.matches(".*(\\d{4}年|\\d{1,2}月|\\d{1,2}日|当时|后来|从前|此刻|改革开放|近代|民国|清末|上世纪).*");
    }

    private boolean hasEntityInfo(String text) {
        return text.matches(".*(企业|公司|工厂|合作社|公社|农会|组织|政府|地方政府|村委|协会|学校|军队|会党|土匪|团体|机构|部门|项目|家庭|农户|群体).*");
    }

    private boolean hasPlaceInfo(String text) {
        return text.matches(".*(省|市|县|区|乡|镇|村|地区|地方|乡村|农村|城市|沿海|内地|公社|社区).*");
    }

    private boolean hasPersonOrRoleInfo(String text) {
        return text.matches(".*(人物|个人|创始人|负责人|厂长|干部|委员|农民|地主|富农|中农|工人|商人|学生|官员|领导|成员|家庭|农户).*");
    }

    private boolean looksLikeRepeatedConceptAnswer(String answer) {
        String text = normalize(answer);
        if (text.contains("税") && text.contains("费") && text.contains("劳务") && text.contains("摊派")
            && !hasConcreteTaxFeeItems(answer)) {
            return true;
        }
        int labels = 0;
        if (text.contains("引导")) {
            labels++;
        }
        if (text.contains("教育")) {
            labels++;
        }
        if (text.contains("组织")) {
            labels++;
        }
        if (text.contains("转化")) {
            labels++;
        }
        if (text.contains("抽象") || text.contains("概念")) {
            labels++;
        }
        return labels >= 3 && !hasConcreteCaseSignal(answer);
    }

    private String contrastiveWhyIssue(ChatPlan plan, String answer) {
        String text = normalize(answer);
        if (!text.matches(".*(仍然|还要|之所以|原因|为了|目的|解决|优先|权衡|取舍|代价|更大问题|中央|稳定).*")) {
            return "用户问的是让步型为什么，但回答没有解释为什么仍然选择该政策或行动。";
        }
        if (!text.matches(".*(代价|问题|困难|负担|后果|承认|确实|虽然).*")) {
            return "用户问的是在承认负面后果下的政策选择，回答需要承认代价而不是只讲好处。";
        }
        if (!text.matches(".*(权衡|取舍|优先|更大问题|相比|两害|代价|当时).*")) {
            return "用户问的是为什么在明知有问题时仍然执行，回答需要讲清当时的优先目标和权衡取舍。";
        }
        String action = contrastiveAction(plan);
        if (!action.isBlank() && !mentionsImportantChars(text, action)) {
            return "用户问的是为什么仍然要“" + action + "”，但回答没有聚焦这个政策或行动本身。";
        }
        int consequenceScore = countMatches(text, "困难", "负担", "农民", "县乡", "财政困难", "征税", "收费");
        int rationaleScore = countMatches(text, "为了", "解决", "目标", "优先", "权衡", "取舍", "中央", "财政", "能力", "稳定", "统一", "宏观调控");
        if (consequenceScore >= 5 && rationaleScore < 3) {
            return "回答被负面后果带跑了，主要在复述问题，没有回答为什么仍然执行。";
        }
        return "";
    }

    private String contrastiveAction(ChatPlan plan) {
        if (plan == null) {
            return "";
        }
        String question = normalize(plan.originalQuestion());
        int why = question.indexOf("为什么");
        if (why < 0) {
            why = question.indexOf("为何");
        }
        if (why < 0) {
            return "";
        }
        String action = question.substring(why)
            .replaceFirst("^(为什么|为何)", "")
            .replaceFirst("^(还要|仍然要|依然要|还是要|要|还|仍然|依然|还是)", "")
            .replace("？", "")
            .replace("?", "")
            .trim();
        return action.length() > 18 ? action.substring(0, 18) : action;
    }

    private boolean mentionsImportantChars(String text, String value) {
        String compact = value.replaceAll("\\s+", "");
        if (compact.isBlank()) {
            return true;
        }
        int matched = 0;
        for (int i = 0; i < compact.length(); i++) {
            char ch = compact.charAt(i);
            if (Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                if (text.indexOf(ch) >= 0) {
                    matched++;
                }
            }
        }
        return matched >= Math.min(3, compact.length());
    }

    private int countMatches(String text, String... terms) {
        int count = 0;
        for (String term : terms) {
            if (text.contains(term)) {
                count++;
            }
        }
        return count;
    }

    private boolean answerSubstantiallyRepeatsRecentAssistant(CollectedEvidence evidence, String answer) {
        String normalizedAnswer = normalize(answer);
        if (evidence == null || normalizedAnswer.length() < 80) {
            return false;
        }
        for (EvidenceItem item : evidence.items()) {
            if (!isDialogueOrMemory(item)) {
                continue;
            }
            String content = normalize(item.content());
            if (!looksLikeAssistantHistory(content)) {
                continue;
            }
            if (sharesLongSnippet(content, normalizedAnswer)) {
                return true;
            }
            if (isTaxHeavyOverlap(content, normalizedAnswer) && !hasFollowUpMove(normalizedAnswer)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDialogueOrMemory(EvidenceItem item) {
        return item != null && ("recent_dialogue".equals(item.type()) || item.type().endsWith("_memory"));
    }

    private boolean looksLikeAssistantHistory(String content) {
        return content.contains("助手")
            || content.contains("assistant")
            || content.contains("AI")
            || content.contains("回答")
            || content.contains("用户问题");
    }

    private boolean sharesLongSnippet(String history, String answer) {
        String compactHistory = history.replaceAll("\\s+", "");
        String compactAnswer = answer.replaceAll("\\s+", "");
        if (compactAnswer.length() < 120 || compactHistory.length() < 120) {
            return false;
        }
        int window = Math.min(120, compactAnswer.length());
        for (int start = 0; start + window <= compactAnswer.length(); start += 40) {
            String snippet = compactAnswer.substring(start, start + window);
            if (compactHistory.contains(snippet)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTaxHeavyOverlap(String history, String answer) {
        String[] terms = {
            "农业税", "特产税", "农业特产税", "屠宰税", "教育附加", "教育费附加",
            "水利建设", "乡统筹", "村提留", "义务工", "修路", "修渠", "出工", "集资", "摊派"
        };
        int overlap = 0;
        for (String term : terms) {
            if (history.contains(term) && answer.contains(term)) {
                overlap++;
            }
        }
        return overlap >= 6;
    }

    private boolean hasFollowUpMove(String answer) {
        return answer.contains("前面")
            || answer.contains("上一轮")
            || answer.contains("刚才")
            || answer.contains("这次")
            || answer.contains("换成")
            || answer.contains("具体场景")
            || answer.contains("举个场景")
            || answer.contains("一个农户")
            || answer.contains("严格说");
    }

    private boolean isTaxBurdenQuestion(ChatPlan plan) {
        if (plan == null) {
            return false;
        }
        String text = normalize(plan.originalQuestion() + " " + plan.standaloneQuestion());
        if (conceptBoundary(plan).required()) {
            return false;
        }
        return text.matches(".*(税|费|负担|摊派|劳务|有哪些).*")
            && text.matches(".*(税|费|负担|摊派|劳务).*");
    }

    private ConceptBoundary conceptBoundary(ChatPlan plan) {
        if (plan == null) {
            return new ConceptBoundary("", "");
        }
        String focusTerm = focusTerm(plan.originalQuestion());
        if (focusTerm.isBlank()) {
            focusTerm = focusTerm(plan.standaloneQuestion());
        }
        return new ConceptBoundary(focusTerm, broaderTerm(focusTerm));
    }

    private boolean hasConcreteTaxFeeItems(String answer) {
        String text = normalize(answer);
        int score = 0;
        if (text.contains("农业税")) {
            score++;
        }
        if (text.contains("特产税") || text.contains("农业特产税")) {
            score++;
        }
        if (text.contains("屠宰税")) {
            score++;
        }
        if (text.contains("教育附加") || text.contains("教育费附加")) {
            score++;
        }
        if (text.contains("水利建设")) {
            score++;
        }
        if (text.contains("乡统筹")) {
            score++;
        }
        if (text.contains("村提留")) {
            score++;
        }
        if (text.contains("义务工") || text.contains("修路") || text.contains("修渠") || text.contains("出工")) {
            score++;
        }
        if (text.contains("集资") || text.contains("捐款") || text.contains("摊派")) {
            score++;
        }
        return score >= 3;
    }

    private boolean answersConceptBoundary(ConceptBoundary boundary, String answer) {
        String text = normalize(answer);
        if (!text.contains(boundary.focusTerm())) {
            return false;
        }
        if (!text.contains(boundary.broaderTerm())) {
            return false;
        }
        return text.matches(".*(不是|不同|区别|不等于|而是|相对于|严格说|名义|实际|更具体|泛泛).*");
    }

    private String focusTerm(String question) {
        String value = normalize(question);
        if (value.isBlank()) {
            return "";
        }
        for (String marker : java.util.List.of("有哪些", "是什么", "什么意思", "怎么理解")) {
            int index = value.indexOf(marker);
            if (index > 0) {
                return cleanupFocusTerm(value.substring(0, index));
            }
        }
        return "";
    }

    private String cleanupFocusTerm(String value) {
        String term = normalize(value)
            .replace("举个例子", "")
            .replace("举一个例子", "")
            .replace("具体", "")
            .replace("请问", "")
            .replace("那么", "")
            .replace("那", "")
            .trim();
        if (term.length() < 2 || term.length() > 12 || term.matches(".*[，。？！、\\s].*")) {
            return "";
        }
        return term;
    }

    private String broaderTerm(String focusTerm) {
        if (focusTerm == null || focusTerm.length() < 2) {
            return "";
        }
        for (String base : java.util.List.of("税", "费", "成本", "权力", "负担", "记忆", "资本", "阶级", "市场", "政府")) {
            if (focusTerm.endsWith(base) && !focusTerm.equals(base)) {
                return base;
            }
        }
        return "";
    }

    private record ConceptBoundary(String focusTerm, String broaderTerm) {
        boolean required() {
            return focusTerm != null && !focusTerm.isBlank()
                && broaderTerm != null && !broaderTerm.isBlank();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
