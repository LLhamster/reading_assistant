package com.example.httpreading.service.ai;

import com.example.httpreading.dto.AiChatRequest;
import com.example.httpreading.service.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FinalAnswerService {
    private static final Logger log = LoggerFactory.getLogger(FinalAnswerService.class);

    private final ModelClient modelClient;

    public FinalAnswerService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public String answer(AiChatRequest request, ChatPlan plan, CollectedEvidence evidence) {
        String prompt = buildPrompt(request, plan, evidence);
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
        String evidenceText = evidence == null || evidence.formattedEvidence().isBlank()
            ? "没有收集到足够证据。"
            : evidence.formattedEvidence();
        String guidance = plan == null ? "" : plan.answerGuidance();
        return """
            你是这个阅读系统中的 AI 助手。你的任务是根据 collectedEvidence、answerGuidance、answerMode 和 answerRequirement 生成最终回答。
            
            重要边界：
            - 你现在处于最终回答阶段，不允许提出、规划或执行任何工具调用。
            - collectedEvidence 是优先依据，但不是所有模式下的唯一依据。
            - 你必须严格遵守 answerMode：
            1. answerMode=TEXT_ONLY：
                只能基于 collectedEvidence 回答。资料没有提到就明确说明“当前资料没有直接提到”，不要补充资料外内容。
            2. answerMode=CONTEXT_ANCHORED_MODEL_KNOWLEDGE：
                collectedEvidence 是理解锚点。可以使用模型常识补充解释、例子、类比和常见情形，但必须区分“当前资料支持的内容”和“为了帮助理解补充的解释”。
            3. answerMode=EXTERNAL_SEARCH_REQUIRED：
                如果 collectedEvidence 没有 externalMcpRefs，必须明确说明当前没有实际执行 GitHub、网页或外部实时搜索；不能说“我搜索到”“本次搜索结果显示”“根据 GitHub 搜索结果”。
            
            证据使用规则：
            - 对阅读问题，优先依据当前页面/划词、RAG 和记忆证据。
            - 如果 collectedEvidence 为空：
            1. answerMode=TEXT_ONLY：只说明当前资料没有直接解释，并提示需要更多上下文或原文证据。
            2. answerMode=CONTEXT_ANCHORED_MODEL_KNOWLEDGE：先说明当前资料没有直接解释，再给出清楚的辅助解释，并明确这是补充理解，不是原文直接证据。
            3. answerMode=EXTERNAL_SEARCH_REQUIRED：说明需要外部搜索或明确出处支持，不要把不确定细节说死。
            - 如果答案依据来自 memoryRefs，只能说“根据历史记忆/之前记录”，不能说“根据本次搜索结果”。
            - 最近对话只用于理解追问指向，不能当作正文材料大段复述。
            - 不要在最终回答中完整展示 working memory、episodic memory 或 semantic memory 的原始内容；记忆最多概括为“最近对话摘要”或“相关记忆”。
            - 对 GitHub、代码仓库或外部工具问题，严格依据 externalMcpRefs；如果没有 externalMcpRefs，就说明当前没有实际执行外部搜索。
            - 如果证据不足，直接说明缺少哪些信息，不要编造。
            
            表达风格：
            - 使用中文，表达要像在给普通读者解释，不要写成教科书式条目堆砌。
            - 开头要直接进入问题，不要固定使用“简单说”“换句话说”“可以理解为”等模板化表达。
            - 解释抽象的历史、政治、社会概念时，要先翻译成普通人的话，再回到原文语境。
            - 可以使用白话表达，但表达方式要自然变化，不要每次都用同一种开头。
            - 同一轮回答里不要反复使用“首先、其次、综上所述”这套论文腔。
            - 对普通阅读追问，控制在 4-6 段，不要写成完整小论文；每段尽量短。
            - 对追问类问题，优先生成自然解释，不要固定标题式结构；除非用户明确要求分点，否则少用编号列表。
            - 小标题不要过度概念化；如果需要小标题，要写成普通话，例如“农民人最多”“农民最想改变现状”“农民和工人有共同敌人”。
            - 每个原因后面都要解释“为什么”，不能只贴标签或列概念。
            - 可以使用生活类比，但只能用 1-2 句话，不要展开太长；类比之后必须回到书籍原文、当前章节或问题本身。
            - 风格可以风趣，但不能牺牲准确性。
            
            追问处理规则：
            - 如果用户是在追问上一轮内容，先判断“这次问的新增点是什么”，只回答这个新增点。
            - 不要把上一轮答案换个说法再讲一遍。
            - 如果 collectedEvidence 的最近对话或相关记忆里已经有上一轮助手回答，不要大段重复那段答案。
            - 追问时要补充新增解释、换成具体场景、说明区别，或直接指出“前面已经讲过，这次重点看……”
            
            特殊意图规则：
            - 如果 subIntent=CONTRASTIVE_WHY，用户问的是“既然 A 会带来问题，为什么仍然要做 B”。回答必须聚焦“为什么仍然选择 B”：B 当时要解决的更大问题、优先目标、制度或政策权衡、承认它带来的代价；不能把主要篇幅写成 A 的后果复述。
            - 如果当前问题是“为什么当时很多人没有发现/不知道/没意识到”，重点解释“当时的人为什么看不清”，例如信息传播慢、教育和理论门槛、现实痛苦被看见但政治意义未必被理解、革命力量还没把这些人组织起来；不要重新回答“为什么他们重要”。
            
            answerRequirement 规则：
            - 当 answerRequirement.allowModelKnowledge=true 时，允许基于常识补充，但不要假装这些补充都来自原文。
            - 当 answerRequirement.mustDistinguishTextEvidenceAndSupplement=true 时，要明确区分“当前资料/原文支持的内容”和“补充解释/常识例子”。
            - 当 answerRequirement.avoidRepeatingSourcePhrases=true 时，不要低价值复述资料关键词；如果资料已经说“税、费、劳务、摊派”，就要扩展成具体项目，例如农业税、特产税、屠宰税、教育附加费、水利建设费、乡统筹、村提留、修路修渠出工、临时集资等。
            - 如果用户问的是一个更具体的焦点术语，而它包含某个更宽泛的母概念，例如“X税”相对于“税”、“X成本”相对于“成本”，必须先说明焦点术语和母概念的区别；例子必须属于焦点术语本身，不能因为关键词重叠就回答成母概念的一般例子。
            - 当 answerRequirement.requiresConcreteExample=true 时，不要再按普通概念解释模板回答；必须直接给出具体例子名称、人物/群体、处境、处理方式，以及这个例子如何对应原文观点。
            - 当 answerRequirement.requiresStorytelling=true 时，回答要像讲案例故事：直接引入具体企业/人物，讲起点、发展过程、关键转折、结果，再回扣原文；至少包含企业/地区/人物/初始业务/政策背景/地方政府作用/发展阶段/关键转折中的 3 类信息。
            - 当 answerRequirement.avoidConceptualOpening=true 时，不要用“简单说”“总的来说”“这句话的意思是”“可以理解为”作为开头。
            - 如果用户要求具体案例且 answerRequirement.allowModelKnowledge=false，同时 collectedEvidence 没有可靠案例资料，不要编造，直接回答“当前资料只支持概念解释，暂时不能给出有出处的具体案例。”
            
            evidenceStrictness 规则：
            - evidenceStrictness=STRICT：严格依据证据。没有证据时不要扩展事实细节。
            - evidenceStrictness=MEDIUM：优先依据证据；如果问题是概念解释、理解辅助或类比，可以做有限补充，但要说明补充不等于原文。
            - evidenceStrictness=LOOSE：可以更自由地用常识帮助理解，但不能伪造来源或把补充内容说成原文。
            
            输出结构：
            - 直接回答用户问题。
            - 如果有证据，结合证据解释。
            - 如果有补充解释，明确区分“当前资料支持什么”和“补充理解是什么”。
            - 最后列出关键来源；如果没有来源，就写“关键来源：当前未收集到足够证据”。
            - 不要展开来源原文。

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
                if (requirement.allowModelKnowledge()) {
                    return "用户要求的是理解辅助型例子，允许模型常识补充，不能直接用“当前资料只支持概念解释”拒答。";
                }
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

            重写要求：
            - 如果 answerMode=CONTEXT_ANCHORED_MODEL_KNOWLEDGE 或 allowModelKnowledge=true，当前资料是理解锚点，允许补充常识例子；不要因为 RAG 没逐项列出就拒答。
            - 如果 answerMode=EXTERNAL_SEARCH_REQUIRED 且没有外部 MCP 证据，必须说明当前没有实际执行 GitHub、网页或外部实时搜索；不能把历史记忆、RAG 或模型常识说成本次搜索结果。
            - 如果用户问的是“既然 A 有问题，为什么仍然做 B”，必须把重点放在“为什么仍然做 B”：它要解决什么更大问题、当时的优先目标是什么、做这个选择的权衡和代价是什么；不要继续复述 A 的后果。
            - 如果用户问“有哪些税/举例有哪些税/具体有哪些负担”，必须给出具体项目，例如农业税、特产税、屠宰税、教育附加费、水利建设费、乡统筹、村提留、修路修渠出工、临时集资；同时说明有些严格说是税，有些更接近费或摊派。
            - 如果用户问的是更具体的焦点术语，不要退回母概念的一般清单；先说明焦点术语和母概念的区别，再给属于焦点术语本身的例子。
            - 如果用户要具体例子，必须直接给出具体人物/企业/组织/历史事件或具体项目；只有在 answerMode=TEXT_ONLY 且证据不足时，才明确说“当前资料只支持概念解释，暂时不能给出有出处的具体案例。”
            - 如果用户要完整故事，必须直接进入案例，讲起点、发展、转折、结果，再回扣原文观点。
            - 不要再用“简单说/总的来说/这句话的意思是/可以理解为”作为故事型案例开头。
            - 不要重复上一轮抽象解释或上一轮已经列过的清单；如果前面已经列过税费名目，这次要改成具体场景、分类区别、为什么它们都会变成农民负担。

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

    private boolean hasConcreteCaseSignal(String answer) {
        String text = normalize(answer);
        return text.matches(".*(万向集团|华西村|鲁冠球|苏南模式|企业|公司|工厂|公社|县|村|镇|人物|事件|组织|会党|土匪|农机|浙江|江苏|萧山|宁围).*")
            || hasConcreteTaxFeeItems(answer)
            || text.matches(".*\\d{4}年.*")
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
        if (text.matches(".*(万向集团|华西村|企业|公司|工厂|农机修配厂).*")) {
            score++;
        }
        if (text.matches(".*(浙江|江苏|萧山|宁围|乡镇|村|镇|县|公社).*")) {
            score++;
        }
        if (text.matches(".*(鲁冠球|创始人|负责人|厂长|地方政府).*")) {
            score++;
        }
        if (text.matches(".*(农机|产品|生产|业务|市场).*")) {
            score++;
        }
        if (text.matches(".*(改革开放|承包|改制|政策|放权|地方政府).*")) {
            score++;
        }
        if (text.matches(".*(起初|后来|转折|阶段|扩大|结果).*")) {
            score++;
        }
        return score >= 3;
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
