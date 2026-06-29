# AI Agent Testing

本文说明 reading_assistant 的 AI Agent 测试分层、默认安全边界和常用运行方式。除显式启用的 live 测试外，测试不依赖真实模型、GitHub、MCP server 或网络。

## 测试体系总览

| 测试类 | 是否默认运行 | 是否调用真实 LLM | 是否调用真实 GitHub/MCP/网络 | 测试目标 |
|---|---|---|---|---|
| `AiCaseRunnerTest` | 是 | 否 | 否 | 验证一级 Planner、FinalAnswer、repair、证据边界和 case 断言规则 |
| `AiChatShadowCaseTest` | 是 | 否 | 否 | 使用 fake GitHub evidence 验证从 `AiChatService` 到最终回答、确认交互和 MemoryWriter 的完整链路 |
| `ExternalMcpAgentCaseTest` | 是 | 否 | 否 | 验证二级 MCP Agent 执行循环、安全校验、缺参、重复调用和 Not Found 目标记忆 |
| `McpToolOrchestratorTest` | 是 | 否 | 否 | 验证 `BOUNDED_REACT` 和 `mcp.server:*` 能正确传递给二级 Agent |
| `ExternalMcpToolPlannerServiceTest` | 是 | 否 | 否 | 验证二级 ToolPlanner 的 prompt、decision JSON 解析、options 限制、fallback 和 observation 截断 |
| `AiPlannerLiveCaseTest` | 否，需 `-Dai.live=true` | 是 | 只调用 LLM API，不调用 GitHub/MCP | 手动验证真实 LLM 的一级 Planner 输出 |
| `ExternalMcpToolPlannerLiveCaseTest` | 否，需 `-Dmcp.toolPlanner.live=true` | 是 | 只调用 LLM API，不调用 GitHub/MCP | 手动验证真实 LLM 第一轮工具选择 |
| `ExternalMcpAgentShadowLiveTest` | 否，需 `-Dmcp.agent.shadow.live=true` | 是 | 调用 LLM API；GitHub/MCP 工具结果为 fake | 验证真实 LLM 与真实二级 Agent 循环能否基于 fake observations 完成多轮决策 |

## 测试分层

### Mock regression tests

日常回归测试。模型输出和外部工具结果均由 mock 提供，稳定、快速、可重复，不需要 API Key。

该层覆盖 Planner 解析与校验、二级 Agent 安全边界、MCP 编排、证据聚合、最终回答 repair 和完整问答链路。

### Planner live tests

`AiPlannerLiveCaseTest` 使用真实 LLM，手动检查一级 Planner 能否生成符合 schema 和规划约束的 `ChatPlan`。它不会调用真实 GitHub 或 MCP 工具。

### ToolPlanner live tests

`ExternalMcpToolPlannerLiveCaseTest` 使用真实 LLM，根据 fake `allowedTools` 检查二级 ToolPlanner 的第一轮 decision。它只生成工具决策，不执行工具。

### Shadow live tests

`ExternalMcpAgentShadowLiveTest` 使用真实 LLM 和真实 `ExternalMcpAgentService` 循环，但 `ExternalMcpClientService` 是 mock，工具结果为固定 fake JSON。

该层会访问 LLM API，但不会访问真实 GitHub 或真实 MCP server。

### Real integration tests

当前未启用。未来如果接入真实 GitHub/MCP，应使用独立开关、独立凭证和只读账号，不能并入默认 `mvn test`。

## 默认安全策略

- 默认测试不调用真实 LLM。
- 默认测试不调用真实 GitHub。
- 默认测试不调用真实 MCP server。
- 默认测试不调用真实网络。
- `create_file` 等写操作工具不得被自动 MCP Agent 真实执行。
- GitHub、网页、实时信息等外部问题不能使用 RAG 或 Memory 冒充搜索结果。
- `AiChatShadowCaseTest` 会断言 GitHub evidence 进入 FinalAnswer prompt，同时断言本地 RAG/Memory 工具没有参与 GitHub 搜索。
- 所有 live 测试默认通过 JUnit assumption 跳过，只有显式设置启用参数且提供模型 Key 时才运行。

## 常用命令

以下命令均在 `java_src` 项目根目录执行。

### Self-Evolution 本地实验

Self-Evolution 默认不运行。它只读取指定用户的 episodic memory，生成固定 30 个用例，
分别运行 baseline 和一个候选 FinalAnswer Prompt，并将报告写入 `target/evolution`。实验调用不会写入用户记忆，
也不会修改 production Prompt。生成用例采用与 `multi-turn-reading-qa.jsonl` 相同的多轮结构，
包含 dialogue、collected_evidence、mcp_results、scoring_criteria 和 evidence_policy。
回答评估采用相同的 Hybrid Judge：确定性空值/长度检查加 LLM 逐项评分和证据边界检查。
Self-Evolution 用例不使用 must_include、must_not_include 或 style_constraints；所有理想回答要求
都作为正向 scoring_criteria 独立计分。
内容评分与证据审查相互独立：Scoring Judge 只按 scoring_criteria 加分，Evidence Boundary Judge
按照 `evidence_use_mode` 检查具体事实与来源归因。`STRICT_SOURCE` 只允许证据直接支持的事实；
`SOURCE_GROUNDED_NARRATIVE` 允许在历史或事实叙事中补充想象场景；回答任意位置说明“可能、
假设或用于理解”，或明确声明“以下为助手自主构造、没有资料依据”，即可覆盖整个连续场景，
无需逐项标注，但仍不能把补写内容归因成原文事实。`STRICT_SOURCE` 不能
通过免责声明绕过证据要求。`PEDAGOGICAL_ILLUSTRATION` 允许用人物、数字和生活场景解释理论，
无需声明例子是否真实，但不能冒充历史事实或原文记录。证据违规会成为硬失败；任一 Judge 调用
或解析失败会令整轮实验无效，不能推荐候选 Prompt。
通用测试模板直接声明自己的 `evidence_use_mode`；从真实 memory signal 生成用例时，由 LLM 在
用例生成阶段根据完整任务语义输出该字段。关键词规则只在模型调用失败、缺少字段或返回非法枚举时兜底。

FinalAnswer Prompt 使用 `EvolvablePromptTemplate` 分为两层：角色、输入输出格式、证据与安全边界属于
固定契约；表达顺序、详略、例子和叙事方式属于可进化策略。候选只追加到可进化策略区，
不会生成 Planner patch，也不能替换固定契约。这个分层与候选生成协议不依赖阅读业务字段，
可作为其他 Agent 的通用 Prompt 进化组件；当前阅读系统只是它的一个接入适配器。
实验执行时直接将用例中的 `final_answer_input` 和固定 evidence 传给 `FinalAnswerService`，
不运行 Planner、MCP、RAG 或记忆检索；baseline 和 candidate 的唯一差异是候选 patch。

```bash
MODEL_API_KEY=xxx mvn \
  -Dtest=SelfEvolutionLiveTest \
  -DselfEvolution.live=true \
  -DselfEvolution.userId=default_user \
  -DselfEvolution.caseCount=5 \
  test
```

可选参数包括 `selfEvolution.memoryLimit`、`selfEvolution.defaultBookId`、
`selfEvolution.defaultChapterIndex`、`selfEvolution.caseCount`（默认 30，范围 1–100）
和 `selfEvolution.reportDir`。输出目录包含 `report.json`、
`report.md` 和可逐行检查的 `eval-cases.jsonl`。一次完整实验除 FinalAnswer 调用外还会为
baseline/candidate 的每个回答调用 Judge 模型。
当前每个回答会调用一次 Scoring Judge 和一次 Evidence Boundary Judge。
Evidence Boundary Judge 使用 `temperature=0`；只有初始判断中的争议 claim 即将触发证据硬失败时，
才会额外批量调用一次 Entailment Judge，复核同义改写、理论展开、教学举例和新增外部事实。
如果 baseline 全部通过且没有硬失败，实验会提前结束，不生成候选 FinalAnswer patch，也不会运行 candidate
和第二轮 Judge。模型全部执行失败或无法生成有效 patch 时也会提前结束，不重复运行等价 candidate。
默认策略为 `BATCH_AGGREGATE`：先执行并评测全部 baseline，用全部失败的类型、数量、代表例子和
修正建议生成一个聚合候选 patch，再统一运行全部 candidate；不会按单个问答逐次修改 Prompt。
`report.json` 保留完整 claim 级证据审计，`report.md` 只按证据问题类型汇总并展示最多两个例子，
同时记录候选 patch 和追加 patch 后实际生效的完整可进化策略区。

### 默认 mock 回归测试

统一运行：

```bash
bash scripts/run-ai-mock-tests.sh
```

单独运行：

```bash
mvn -q -Dtest=AiCaseRunnerTest test
mvn -q -Dtest=AiChatShadowCaseTest test
mvn -q -Dtest=ExternalMcpAgentCaseTest,McpToolOrchestratorTest test
mvn -q -Dtest=ExternalMcpToolPlannerServiceTest test
```

### 可选 live 和 shadow live 测试

统一运行时使用环境变量：

```bash
MODEL_API_KEY=xxx bash scripts/run-ai-live-tests.sh
```

单独运行时可以使用环境变量，也可以直接传 `-Dmodel.apiKey=xxx`：

```bash
mvn -Dtest=AiPlannerLiveCaseTest -Dai.live=true -Dmodel.apiKey=xxx test
mvn -Dtest=ExternalMcpToolPlannerLiveCaseTest -Dmcp.toolPlanner.live=true -Dmodel.apiKey=xxx test
mvn -Dtest=ExternalMcpAgentShadowLiveTest -Dmcp.agent.shadow.live=true -Dmodel.apiKey=xxx test
```

`run-ai-live-tests.sh` 只接受 `MODEL_API_KEY` 环境变量，避免把 Key 放进命令历史。三个测试类本身同时支持 `MODEL_API_KEY` 和 `-Dmodel.apiKey`。

## 失败定位

| 失败测试 | 优先检查 |
|---|---|
| `AiCaseRunnerTest` | Planner JSON、`answerRequirement`、FinalAnswer repair、case rules 和 fallback |
| `AiChatShadowCaseTest` | MCP evidence 是否进入 FinalAnswer、RAG/Memory 是否错误参与、interaction 是否中断 FinalAnswer 和 MemoryWriter |
| `ExternalMcpToolPlannerServiceTest` | 二级 ToolPlanner prompt、JSON 提取与解析、options 和 observation 压缩 |
| `ExternalMcpAgentCaseTest` | `validateCall`、写操作/缺参拦截、重复调用、Not Found 目标记忆 |
| `McpToolOrchestratorTest` | `mcp.server:*` 解析、三参 Agent overload、plan refs 传递 |
| `AiPlannerLiveCaseTest` | 真实模型是否遵守一级 Planner JSON schema、任务分类和 MCP server 选择规则 |
| `ExternalMcpToolPlannerLiveCaseTest` | 真实模型是否根据 `allowedTools` 和资源标识选择正确工具 |
| `ExternalMcpAgentShadowLiveTest` | 真实模型是否选错工具、是否正确利用 observations，或 live 断言是否限制了合理路径 |

live 测试失败前先确认 API Key、模型服务状态和 429/5xx 响应。Shadow live 允许合理的只读工具路径差异，但写操作不得真实执行。

## AI Case 报告

`AiCaseRunnerTest` 会输出：

```text
[AI_CASE_REPORT]
total=9
passed=9
failed=0
```

- `total`：本次参与执行的 case 数量。
- `passed`：通过的 case 数量。
- `failed`：失败的断言数量。

失败时会追加 `[AI_CASE_FAILED]`：

- `caseId`：稳定的 case 标识。
- `caseName`：可读的 case 名称。
- `stage`：失败阶段，例如 Planner、FinalAnswer 或 semantic check。
- `reason`：失败原因。
- `expected`：期望值。
- `actual`：实际值。
