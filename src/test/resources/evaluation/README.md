# 阅读 Agent 评测体系

本目录只提供测试集制作与评估，不包含 Prompt 自进化或自动修改业务代码。

## 两类测试

### 工具路由

`tool-routing.jsonl` 包含 50 条固定标签测试。它只判断一级 Planner 和内部工具是否选择正确，不使用回答 rubric，也不调用 LLM Judge。

```json
{
  "id": "route-011",
  "suite": "TOOL_ROUTING",
  "task_input": {
    "question": "本章作者如何定义乡土社会？",
    "context": {}
  },
  "expected_result": {
    "planner_mode": "BOUNDED_REACT",
    "planner_server": "self-local",
    "local_tools": ["rag.search"]
  },
  "difficulty": "MEDIUM",
  "category": "BOOK_FACT",
  "source": "golden",
  "split": "dev",
  "provenance": {}
}
```

指标为 mode/server accuracy、tool precision/recall/F1 和 exact match：

```text
routing_score = 0.5 × mode/server correctness + 0.5 × tool F1
```

### 多轮最终回答

`multi-turn-reading-qa.jsonl` 包含 50 条最终回答测试。`MULTI_TURN_QA` 是 FinalAnswerService 级别的 replay 测试：测试运行前，多轮对话、当前页、RAG 证据以及 MCP 最终结果已经写入样本；运行时禁止再次规划或调用工具。

该测试只判断最终回答本身是否合格，包括：是否理解本轮问题、承接历史对话、消解指代/省略/追问、正确使用给定证据、在证据不足时说明不足、完整满足用户要求、避免编造材料外事实、符合表达风格、避免模板化废话，并控制长度和结构。

该测试不判断 Planner 路由、MCP 执行、RAG 召回、Memory/Profile 检索或 EvidenceAggregator 的质量。

```json
{
  "id": "mt-001",
  "suite": "MULTI_TURN_QA",
  "task_input": {
    "question": "这里的‘它’具体指什么？为什么？",
    "reading_context": {},
    "dialogue": [],
    "collected_evidence": [
      {"id": "e001", "type": "rag", "title": "书籍 RAG 证据", "content": "...", "metadata": {}}
    ],
    "mcp_results": [
      {"tool": "context.get_recent_dialogue", "ok": true, "data": {}},
      {"tool": "context.get_current_page", "ok": true, "data": {}},
      {"tool": "rag.search", "ok": true, "data": {}}
    ]
  },
  "expected_behavior": {
    "scoring_criteria": [
      {"id": "identify_reference", "description": "明确指出问题中的代词具体指代什么。", "score": 1},
      {"id": "explain_reason", "description": "形成一段完整且有证据支撑的解释。", "score": 1}
    ],
    "max_score": 2,
    "evidence_policy": {
      "use_provided_evidence": true,
      "allow_general_explanation": true,
      "allow_hypothetical_example": false,
      "must_label_hypothetical_example": false
    },
    "must_include": [],
    "must_not_include": [],
    "style_constraints": [],
    "answer_shape": "direct_answer",
    "failure_mode": "",
    "max_chars": 500
  },
  "difficulty": "MEDIUM",
  "category": "PRONOUN_RESOLUTION",
  "source": "golden",
  "split": "dev",
  "provenance": {}
}
```

数据中不保存参考答案。每条题目根据自身问题设置不同数量的计分项；只有明确需要原因、对比、过程、场景、数字或案例时才增加相应 criterion。LLM Judge 逐项给 0 到该项满分之间的分数。

```text
criterion_score = sum(criterion earned score) / max_score
required_item_recall = matched_must_include_count / total_must_include_count
forbidden_item_hit_rate = hit_must_not_include_count / total_must_not_include_count
style_compliance = matched_style_constraints_count / total_style_constraints_count
answer_score = criterion_score
             - 0.15 × missing_required_item_count
             - 0.10 × style_violation_count
             - forbidden_penalty
             - length_penalty
```

`must_include` 用于完整性检查，缺普通项只扣分，不直接判 0。`must_not_include` 用于禁止失败模式，Judge 会标注 low/medium/high 严重度。违反 evidence_policy 或 high severity 禁止项，例如把假设性例子冒充原文、编造材料外事实、证据不足时硬下结论，单条最高为 0.49。

## 数据拆分

每套数据固定为 35 条 dev 和 15 条 holdout。Synthetic 和 SessionDB 候选只能进入 dev；正式测试运行时不会临时生成题目。

如需一次跑完整 50 条，可以显式使用 `-Devaluation.split=all`。这不会修改样本里的固定拆分，只是在本次运行中同时选取 dev 和 holdout。

## 离线校验

```bash
mvn -Dtest='com.example.httpreading.evaluation.*Test' test
```

## 真实模型评测

```bash
MODEL_API_KEY=... mvn -Dtest=ReadingEvaluationLiveTest \
  -Devaluation.live.routing=true test

MODEL_API_KEY=... mvn -Dtest=ReadingEvaluationLiveTest \
  -Devaluation.live.answer=true -Devaluation.mode=FAST test

MODEL_API_KEY=... mvn -Dtest=ReadingEvaluationLiveTest \
  -Devaluation.live.answer=true \
  -Devaluation.split=all \
  -Devaluation.limit=50 \
  -Devaluation.mode=FAST test
```

模型服务过载或只想先查看最终回答文本时，可以关闭 Judge，调用量会从“每条约 2 次”降到“每条约 1 次”：

```bash
MODEL_API_KEY=... mvn -Dtest=ReadingEvaluationLiveTest \
  -Devaluation.live.answer=true \
  -Devaluation.judge=false \
  -Devaluation.split=dev \
  -Devaluation.limit=5 test
```

如果模型侧出现 429，可以加 case 间隔降低连续请求压力：

```bash
-Devaluation.caseDelayMs=1000
```

如果第一次 429 后后续请求也连续失败，建议让本轮评测在模型过载时先停下来，避免把整批样例都打成 unscored：

```bash
-Devaluation.stopOnModelOverload=true
-Devaluation.overloadCooldownMs=30000
```

`STRICT` 会执行三次 LLM Judge 并取各维度中位数。holdout 必须同时设置：

```bash
-Devaluation.split=holdout -Devaluation.allowHoldout=true
```

报告写入 `target/evaluation/<run-id>/report.json` 和 `report.md`。

低分、未通过样本和模型过载导致的 unscored 样本默认只写入报告，不会导致 Maven 失败。报告包含 Agent 原始回答、每个 criterion 的得分与 Judge 理由、综合反馈和 evidence policy 违规信息。若需要在 CI 中把低于阈值或 unscored 比例过高视为测试失败，显式添加：

```bash
-Devaluation.failOnThreshold=true
-Devaluation.failOnUnscored=true
```

## 候选数据制作

Synthetic：

```bash
MODEL_API_KEY=... mvn -Dtest=EvaluationDatasetBuilderLiveTest \
  -Devaluation.generate.synthetic=true \
  -Devaluation.generate.suite=MULTI_TURN_QA \
  -Devaluation.generate.count=5 test
```

SessionDB 脱敏 JSONL 导入：

```bash
MODEL_API_KEY=... mvn -Dtest=EvaluationDatasetBuilderLiveTest \
  -Devaluation.generate.sessiondb=true \
  -Devaluation.sessiondb.input=/path/to/sessions.jsonl test
```

候选输出到 `target/evaluation-candidates/`，必须人工审查后才能加入正式数据集。
