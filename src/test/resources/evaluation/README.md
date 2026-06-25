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

`multi-turn-reading-qa.jsonl` 包含 50 条最终回答测试。测试运行前，多轮对话、当前页、RAG 证据以及 MCP 最终结果已经写入样本；运行时不再执行 MCP，只判断最终回答是否处于本题 rubric 规定的范围内。

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
answer_score = criterion_score - length_penalty
```

违反 evidence_policy，例如把假设性例子冒充原文或 MCP 结果时，单条最高为 0.49。

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

`STRICT` 会执行三次 LLM Judge 并取各维度中位数。holdout 必须同时设置：

```bash
-Devaluation.split=holdout -Devaluation.allowHoldout=true
```

报告写入 `target/evaluation/<run-id>/report.json` 和 `report.md`。

低分和未通过样本默认只写入报告，不会导致 Maven 失败。报告包含 Agent 原始回答、每个 criterion 的得分与 Judge 理由、综合反馈和 evidence policy 违规信息。若需要在 CI 中把低于阈值视为测试失败，显式添加：

```bash
-Devaluation.failOnThreshold=true
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
