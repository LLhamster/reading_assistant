# MCP 修改记录

这个文件用于记录 MCP 相关每次改动的目的、范围、涉及文件和验证结果。后续继续追加新日期条目，不覆盖旧记录。

从下一次 MCP 相关功能实现开始，每条完成日志都需要额外包含一份《设计决策说明》，用于记录“不只是做了什么，还包括为什么这样做”。说明应覆盖：

1. 本次实现做了哪些设计决策？
2. 每个决策的可选方案有哪些？
3. 为什么选择当前方案？
4. 当前方案适合什么场景？
5. 当前方案不适合什么场景？
6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？

建议模板：

```markdown
### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- ...

#### 2. 每个决策的可选方案有哪些？
- ...

#### 3. 为什么选择当前方案？
- ...

#### 4. 当前方案适合什么场景？
- ...

#### 5. 当前方案不适合什么场景？
- ...

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：...
- 最应该质疑：...
```

## 2026-06-04：第一阶段，内嵌本地 MCP Server

### 改动概括
- 在 Spring Boot 应用中内嵌标准 MCP Server，启动项目后同端口提供 `/mcp` endpoint。
- 第一版只暴露本地能力：Memory、RAG、Context 构建，不接入外部 MCP Server。
- MCP 入口和原 REST Controller 平级，二者都直接复用现有 Java Service，不通过 HTTP 自调用。

### 新增内容
- 新增 `src/main/java/com/example/httpreading/mcp/McpServerConfig.java`
  - 注册 `HttpServletStreamableServerTransportProvider`。
  - MCP endpoint 为 `/mcp`。
  - 注册 `http-reading-mcp` 同步 MCP Server。
  - 暴露 5 个 MCP tools：
    - `memory_search`
    - `memory_remember_turn`
    - `rag_retrieve`
    - `rag_answer`
    - `context_build`
- 新增 `src/main/java/com/example/httpreading/mcp/ReadingMcpToolService.java`
  - 将 MCP tool 参数转换为现有服务调用。
  - 复用 `AgentMemoryService`、`RagService`、`ContextManager`、`ContextBuilder`。
  - 统一返回 JSON 文本：
    - 成功：`{"ok":true,"data":...}`
    - 空结果：`{"ok":true,"data":[],"message":"未找到相关内容"}`
    - 参数错误：`{"ok":false,"error":"..."}`
- 新增 `src/test/java/com/example/httpreading/mcp/ReadingMcpToolServiceTest.java`
  - 覆盖 Memory、RAG、Context 工具适配逻辑。
- 新增 `src/test/java/com/example/httpreading/mcp/McpServerConfigTest.java`
  - 验证 MCP transport、servlet registration 和 5 个 tools 注册成功。

### 修改内容
- `pom.xml`
  - 新增官方 MCP Java SDK 依赖：`io.modelcontextprotocol.sdk:mcp:0.18.2`。
- `SecurityConfig.java`
  - 放行 `/mcp` 和 `/mcp/**`。
- `DocumentParseService.java`
  - 修复一个阻塞编译的 `ZipFile.stream()` 泛型问题：显式 cast 为 `ZipEntry`。

### 验证结果
- 已执行完整测试：`mvn test`
  - 结果：29 tests passed。
- 已执行 MCP 相关测试：`mvn test -Dtest=ReadingMcpToolServiceTest,McpServerConfigTest`
  - 结果：7 tests passed。

### 如何手动验证
1. 启动项目：
   ```bash
   cd java_src
   mvn spring-boot:run
   ```
2. 使用任意支持 Streamable HTTP 的 MCP Client 连接：
   ```text
   http://localhost:8080/mcp
   ```
3. 在 MCP Client 中执行 `tools/list`，应该能看到 5 个工具：
   - `memory_search`
   - `memory_remember_turn`
   - `rag_retrieve`
   - `rag_answer`
   - `context_build`
4. 调用一个轻量工具，例如 `context_build`：
   ```json
   {
     "question": "测试 MCP 是否可用",
     "systemInstructions": "你是阅读系统 Agent",
     "packets": [
       {
         "type": "tool_result",
         "content": "这是一条 MCP 测试上下文"
       }
     ]
   }
   ```
5. 如果返回 JSON 中包含 `"ok":true`，并且 `data.context` 中出现问题和上下文内容，说明 MCP tool 调用链路已经可用。

### 注意事项
- 当前 `/mcp` 是本地开发放行状态，公网部署前需要补充鉴权策略。
- 当前阶段项目是 MCP Server；调用网上外部 MCP Server 属于第二阶段 MCP Client 能力。
- MCP 是否被使用，不看 `AiChatService` 日志，而看外部 MCP Client 是否连接 `/mcp` 并成功执行 tools。

## 2026-06-04：补充浏览器可读的 MCP 状态检查

### 改动概括
- 解决浏览器直接打开 `/mcp` 时看到 MCP 协议错误/stack trace、难以判断服务是否可用的问题。
- 保留 `/mcp` 作为标准 MCP 协议入口，新增 `/mcp/status` 作为普通浏览器/REST 状态检查入口。

### 新增内容
- 新增 `src/main/java/com/example/httpreading/mcp/McpStatusController.java`
  - `GET /mcp/status` 返回 MCP Server 状态、endpoint、server 信息和已注册工具列表。
- 新增 `src/test/java/com/example/httpreading/mcp/McpStatusControllerTest.java`
  - 验证状态接口能返回 5 个 MCP tools。

### 修改内容
- `McpServerConfig.java`
  - MCP Servlet 从 `/mcp/*` 精确调整为 `/mcp`。
  - 避免 `/mcp/status` 被 MCP Servlet 拦截，让它由普通 Spring Controller 处理。
- `McpServerConfigTest.java`
  - 增加 `/mcp` servlet mapping 校验。

### 验证方式
1. 启动项目后，浏览器打开：
   ```text
   http://localhost:8080/mcp/status
   ```
2. 期望看到类似结构：
   ```json
   {
     "code": 0,
     "message": "ok",
     "data": {
       "enabled": true,
       "endpoint": "/mcp",
       "serverName": "http-reading-mcp",
       "serverVersion": "0.0.1-SNAPSHOT",
       "tools": [
         "memory_search",
         "memory_remember_turn",
         "rag_retrieve",
         "rag_answer",
         "context_build"
       ]
     }
   }
   ```
3. 注意：`/mcp/status` 只能证明 MCP Server 已启动且工具已注册；真正调用 MCP 仍然需要 MCP Client 连接 `/mcp` 并执行 `tools/list` / `tools/call`。

## 2026-06-04：第二阶段，Java 作为 MCP Client 接入外部 HTTP MCP

### 改动概括
- 在保留本地 `/mcp` MCP Server 的基础上，新增外部 HTTP MCP Client 能力。
- 第一版只支持 Streamable HTTP 外部 MCP Server，不支持 STDIO。
- 外部 MCP 工具不会由模型自动选择，只有请求中显式传入 `enableExternalMcp=true` 和 `externalMcpCalls` 时才会调用。
- 外部 MCP 调用失败时不会中断阅读问答主链路，系统继续使用本地 history、memory、chapter、RAG 生成回答。

### 新增内容
- 新增 `src/main/java/com/example/httpreading/mcp/ExternalMcpClientProperties.java`
  - 绑定 `mcp.client.servers` 配置。
  - 每个 server 支持 `name`、`url`、`enabled`、`timeoutSeconds`、`headers`。
- 新增 `src/main/java/com/example/httpreading/mcp/ExternalMcpClientService.java`
  - 按 `serverName` 创建并复用 MCP Client session。
  - 执行 `initialize()`、`listTools()`、`callTool()`。
  - 将外部 MCP text content 合并为字符串。
  - 统一返回 `serverName`、`toolName`、`ok`、`content`、`error`。
- 新增 `src/main/java/com/example/httpreading/mcp/SdkExternalMcpClientFactory.java`
  - 基于 MCP Java SDK 创建 Streamable HTTP Client。
  - 注入固定 headers，支持 `Authorization` 等配置项。
  - 应用 per-server timeout 配置。
- 新增 `src/main/java/com/example/httpreading/mcp/ExternalMcpClientController.java`
  - `GET /mcp/client/status`：查看已配置外部 MCP servers。
  - `GET /mcp/client/{serverName}/tools`：连接指定 server 并返回 tools/list。
- 新增 DTO：
  - `src/main/java/com/example/httpreading/dto/ExternalMcpCall.java`
  - `AiChatRequest.enableExternalMcp`
  - `AiChatRequest.externalMcpCalls`
  - `AiChatResponse.externalMcpRefs`
- 新增测试：
  - `ExternalMcpClientServiceTest`
  - `ExternalMcpClientPropertiesTest`
  - `AiChatServiceTest`

### 修改内容
- `src/main/java/com/example/httpreading/service/AiChatService.java`
  - 在本地 history、memory、current chapter、RAG 之后执行显式外部 MCP 调用。
  - 成功结果作为 `ContextPacket(type=tool_result)` 进入 `ContextBuilder` 的 Evidence。
  - 失败结果只进入 `externalMcpRefs`，不阻断 `modelClient.chat()`。
  - system instructions 增加说明：外部 MCP 工具结果只能作为辅助事实，需要区分书内证据和外部工具信息。
- `src/main/resources/application.yml`
  - 新增 `mcp.client.servers` 示例配置。
  - 默认提供 disabled 的 `self-local` 示例，指向 `http://localhost:8080/mcp`，用于后续本地联调。

### 验证结果
- 已执行编译检查：
  ```bash
  mvn test -DskipTests
  ```
  - 结果：Build success。
- 已执行第二阶段相关测试：
  ```bash
  mvn test -Dtest=ExternalMcpClientServiceTest,ExternalMcpClientPropertiesTest,AiChatServiceTest
  ```
  - 结果：9 tests passed。
- 已执行完整回归测试：
  ```bash
  mvn test
  ```
  - 结果：39 tests passed。

### 如何手动验证
1. 在 `application.yml` 中启用一个外部 MCP Server，例如把 `self-local.enabled` 临时改为 `true`，并确保本项目已启动：
   ```yaml
   mcp:
     client:
       servers:
         - name: self-local
           url: http://localhost:8080/mcp
           enabled: true
           timeoutSeconds: 10
           headers: {}
   ```
2. 查看外部 MCP Client 配置状态：
   ```text
   GET http://localhost:8080/mcp/client/status
   ```
3. 查看某个外部 MCP Server 的工具列表：
   ```text
   GET http://localhost:8080/mcp/client/self-local/tools
   ```
4. 通过原有 AI Chat 接口显式调用外部 MCP 工具：
   ```json
   {
     "userId": "default_user",
     "sessionId": "default_session",
     "bookId": 1,
     "chapterIndex": 1,
     "question": "结合外部 MCP 工具结果回答：MCP 是否可用？",
     "enableExternalMcp": true,
     "externalMcpCalls": [
       {
         "serverName": "self-local",
         "toolName": "context_build",
         "arguments": {
           "question": "测试外部 MCP Client 调用",
           "systemInstructions": "你是阅读系统 Agent",
           "packets": [
             {
               "type": "tool_result",
               "content": "这是通过 Java MCP Client 调用本地 MCP Server 得到的测试上下文"
             }
           ]
         }
       }
     ]
   }
   ```
5. 响应中如果 `externalMcpRefs` 包含类似 `OK self-local/context_build`，说明 `AiChatService -> 外部 MCP Client -> MCP Server tool -> ContextBuilder` 链路已经打通。

### 注意事项
- 当前外部 MCP Server 列表只从 `application.yml` 读取，不支持运行时动态新增。
- `/mcp/client/**` 是调试接口，用来验证 Java MCP Client 能力；它不替代 `AiChatService` 的显式工具调用集成。
- 外部 MCP 的结果会作为辅助证据进入上下文，不能默认当作书内证据。

## 2026-06-05：补充 GitHub 远程 MCP 配置模板

### 改动概括
- 在 `application.yml` 的 `mcp.client.servers` 中新增 `github` 外部 MCP Server 模板。
- 模板默认关闭，通过环境变量启用，避免把 token 写死到配置文件。

### 修改内容
- `src/main/resources/application.yml`
  - 新增 server：
    - `name: github`
    - `url: https://api.githubcopilot.com/mcp/`
    - `enabled: ${GITHUB_MCP_ENABLED:false}`
    - `headers.Authorization: Bearer ${GITHUB_TOKEN:}`

### 验证方式
1. 设置环境变量：
   ```bash
   export GITHUB_MCP_ENABLED=true
   export GITHUB_TOKEN=你的 GitHub token
   ```
2. 重启 Spring Boot 项目。
3. 浏览器或 HTTP 客户端访问：
   ```text
   http://localhost:8080/mcp/client/github/tools
   ```
4. 如果返回 tools/list，说明 Java MCP Client 已连接 GitHub 远程 MCP。

### 注意事项
- 修改 `application.yml` 后必须重启项目，正在运行的 Spring Boot 进程不会自动重新绑定外部 MCP server 列表。
- GitHub 远程 MCP 的鉴权策略以 GitHub 官方文档为准；当前项目第一版更适合使用固定 header/token 的方式接入。

## 2026-06-05：第三阶段，模型自动规划 MCP 工具调用

### 改动概括
- 在第二阶段“请求显式指定 MCP 工具”的基础上，新增“模型先规划工具调用”的链路。
- 触发规则：只有 `enableExternalMcp=true` 且 `externalMcpCalls` 为空时进入自动规划；如果请求已提供 `externalMcpCalls`，仍走显式调用逻辑。
- 自动规划只允许调用配置白名单中的工具，非白名单工具会被跳过。
- 执行流程变为：本地上下文准备 -> 模型生成工具计划 JSON -> 后端校验并执行 MCP -> 工具结果进入 Evidence -> 模型生成最终答案。

### 新增内容
- 新增 `ExternalMcpToolPlannerService`
  - 构造规划 prompt，向模型请求严格 JSON 工具计划。
  - 解析 `calls[].serverName`、`calls[].toolName`、`calls[].arguments`、`calls[].reason`。
  - 对模型计划执行白名单校验，最多保留 3 个自动调用。
  - 解析失败、空计划、非法工具时返回摘要，不抛出到主链路。
- 新增 `ExternalMcpPlanResult`
  - 保存模型规划出的 `ExternalMcpCall` 列表。
  - 保存 `externalMcpPlanRefs` 摘要。
- 扩展 `AiChatResponse`
  - 新增 `externalMcpPlanRefs`，用于返回自动规划摘要，例如：
    - `AUTO_PLAN github/get_me: 获取当前 GitHub 用户信息`
    - `AUTO_SKIP github/create_issue: tool not allowed`
    - `AUTO_PLAN_EMPTY`
    - `AUTO_PLAN_PARSE_FAILED`
- 新增测试：
  - `ExternalMcpToolPlannerServiceTest`
  - 扩展 `AiChatServiceTest`
  - 扩展 `ExternalMcpClientServiceTest`
  - 扩展 `ExternalMcpClientPropertiesTest`

### 修改内容
- `ExternalMcpClientProperties`
  - 每个 `mcp.client.servers[]` 新增 `allowedTools`。
- `ExternalMcpClientService`
  - `/mcp/client/status` 返回中新增 `allowedTools`。
  - 新增 `allowedToolDescriptors()`，为自动规划提供 enabled server 的白名单工具和 tools/list 元数据。
- `AiChatRequest`
  - `isExternalMcpEnabled()` 语义调整为只判断 `enableExternalMcp=true`。
  - 是否为显式调用由 `externalMcpCalls` 是否为空单独判断。
- `AiChatService`
  - 显式 `externalMcpCalls` 优先。
  - 当只开启 `enableExternalMcp=true` 时，先调用模型规划，再执行白名单内 MCP 工具。
  - 自动规划失败或 MCP 调用失败时继续生成最终回答。
- `application.yml`
  - `self-local.allowedTools` 默认包含 `context_build`。
  - `github.allowedTools` 默认包含只读工具 `get_me`、`get_file_contents`。

### 验证结果
- 已执行第三阶段相关测试：
  ```bash
  mvn test -Dtest=ExternalMcpToolPlannerServiceTest,ExternalMcpClientServiceTest,ExternalMcpClientPropertiesTest,AiChatServiceTest
  ```
  - 结果：17 tests passed。
- 已执行完整回归测试：
  ```bash
  mvn test
  ```
  - 结果：47 tests passed。

### 如何手动验证
1. 确保 GitHub MCP 已启用：
   ```bash
   export GITHUB_MCP_ENABLED=true
   export GITHUB_TOKEN=你的 GitHub token
   mvn spring-boot:run
   ```
2. 发送不包含 `externalMcpCalls` 的 AI Chat 请求：
   ```bash
   curl -X POST "http://localhost:8080/api/ai/chat" \
     -H "Content-Type: application/json" \
     -d '{
       "bookId": 1,
       "chapterIndex": 1,
       "question": "请查看我当前 GitHub 用户信息，并告诉我 MCP 是否调用成功",
       "enableMemory": false,
       "enableRag": false,
       "enableExternalMcp": true
     }'
   ```
3. 期望响应中包含：
   - `externalMcpPlanRefs`：模型规划摘要。
   - `externalMcpRefs`：实际 MCP 工具执行结果。
   - 如果规划并调用成功，应出现类似 `OK github/get_me`。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 自动规划只在 `enableExternalMcp=true` 且 `externalMcpCalls` 为空时触发。
- 显式工具调用优先于自动规划。
- 自动规划只允许调用配置中的 `allowedTools`。
- 规划使用现有 `ModelClient.chat(String)`，要求模型返回严格 JSON。
- 第一版只做一次规划、一次执行、一次最终回答。
- 规划摘要通过 `externalMcpPlanRefs` 返回，工具执行摘要继续通过 `externalMcpRefs` 返回。

#### 2. 每个决策的可选方案有哪些？
- 触发方式可选：默认自动规划、显式开启自动规划、新增独立开关。
- 工具权限可选：全部工具、按工具名前缀推断只读、配置白名单。
- 模型规划接口可选：普通 chat JSON、OpenAI/function calling 风格、MCP tool schema 直连模型。
- 执行轮次可选：单轮规划、多轮 ReAct、失败后重新规划。
- 响应可选：不返回规划信息、返回摘要、返回完整规划 prompt 和工具结果。

#### 3. 为什么选择当前方案？
- 显式开启可以避免普通阅读问答无意触发外部网络调用。
- 显式调用优先能保持第二阶段接口兼容。
- 配置白名单比工具名前缀推断更安全，尤其 GitHub MCP 中存在评论、创建、修改类工具。
- 复用 `ModelClient.chat(String)` 改动最小，不引入新的模型协议依赖。
- 单轮规划更容易测试和排查，适合作为第一版自动工具调用。
- 返回摘要足够判断是否规划和调用成功，又不会把完整 prompt 暴露给前端。

#### 4. 当前方案适合什么场景？
- 本地开发和受控环境中测试 MCP 自动调用。
- 只读外部工具，例如查询 GitHub 用户信息、读取仓库文件。
- 需要保留阅读问答主链路稳定性，外部工具失败也能继续回答。
- 后端配置好 MCP server 和工具白名单，由用户请求决定是否启用。

#### 5. 当前方案不适合什么场景？
- 需要自动执行写操作、创建 issue、评论 PR、合并代码等高风险动作。
- 需要多轮工具推理、边查边问、连续规划的复杂 Agent 流程。
- 需要运行时动态新增 MCP server 或动态授权 OAuth 的生产场景。
- 需要严格结构化 function calling，而不是普通文本模型输出 JSON 的场景。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：`allowedTools` 白名单和显式调用优先级，这两点保护了安全边界和兼容性。
- 最应该质疑：用普通 `ModelClient.chat(String)` 产出 JSON 的方式。后续如果模型 API 支持稳定的结构化输出或 tool calling，应优先替换规划解析层。

## 2026-06-05：前端聊天框接入自动 MCP 规划

### 改动概括
- 让阅读页前端聊天框默认在 `/api/ai/chat` 请求中带上 `enableExternalMcp: true`。
- 前端不传 `externalMcpCalls`，因此会触发第三阶段的“模型自动规划 MCP 工具调用”链路。
- 在 AI 回答下方展示 `externalMcpPlanRefs` 和 `externalMcpRefs`，方便确认是否发生了工具规划和实际 MCP 调用。

### 修改内容
- `src/main/resources/static/reader.html`
  - `sendAiQuestion()` 请求体新增 `enableExternalMcp: true`。
  - AI 消息渲染新增两个元信息区块：
    - `工具规划`：展示 `externalMcpPlanRefs`。
    - `MCP 工具`：展示 `externalMcpRefs`。

### 验证方式
1. 确保后端已启用 GitHub MCP：
   ```bash
   export GITHUB_MCP_ENABLED=true
   export GITHUB_TOKEN=你的 GitHub token
   mvn spring-boot:run
   ```
2. 打开阅读页，选择一本书和章节。
3. 在前端聊天框输入：
   ```text
   请查看我当前 GitHub 用户信息，并告诉我 MCP 是否调用成功
   ```
4. 期望回答下方出现：
   - `工具规划` 中包含类似 `AUTO_PLAN github/get_me: ...`。
   - `MCP 工具` 中包含类似 `OK github/get_me`。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 前端默认对聊天请求开启 `enableExternalMcp: true`。
- 前端不生成 `externalMcpCalls`，把工具选择完全交给后端自动规划。
- 前端只展示规划和调用摘要，不展示工具原始返回内容。

#### 2. 每个决策的可选方案有哪些？
- 触发方式可选：默认开启、增加前端开关、只在检测到 GitHub/MCP 关键词时开启。
- 工具选择可选：前端显式组装 `externalMcpCalls`、后端自动规划、前端先请求 tools/list 再辅助选择。
- 展示方式可选：不展示 MCP 信息、展示摘要、展示完整工具结果。

#### 3. 为什么选择当前方案？
- 默认开启能满足“直接在聊天框输入就能触发 MCP”的目标。
- 不让前端组装工具调用，可以避免把工具选择逻辑分散到浏览器端。
- 展示摘要足够验证链路是否成功，又不会把 GitHub 用户信息或文件内容重复暴露在元信息区。

#### 4. 当前方案适合什么场景？
- 本地开发和演示自动 MCP 规划能力。
- 用户希望在普通聊天框中自然提问，由后端决定是否调用只读 MCP 工具。
- 后端已经通过 `allowedTools` 控制可调用工具范围。

#### 5. 当前方案不适合什么场景？
- 生产环境中希望用户明确选择是否启用外部 MCP，避免每次聊天都有额外模型规划成本。
- 需要按用户、书籍或会话动态控制 MCP 开关。
- 需要在前端展示完整工具原始结果进行深度调试。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：前端只传 `enableExternalMcp`，工具选择仍由后端白名单和规划服务控制。
- 最应该质疑：默认对所有聊天开启自动 MCP。后续如果用于真实用户，应考虑加一个前端开关或用户偏好配置。

## 2026-06-05：本地自动加载 GitHub MCP 环境变量

### 改动概括
- 新增本地开发启动脚本，自动读取 `.env.local` 中的 GitHub MCP 配置并启动 Spring Boot。
- `.env.local` 被加入 `.gitignore`，避免 GitHub token 被提交到仓库。
- 新增 `.env.local.example`，用于提示本地需要配置哪些变量。

### 新增内容
- `scripts/run-dev-with-mcp.sh`
  - 启动前自动 source `.env.local`。
  - 如果存在 `GITHUB_TOKEN`，默认设置 `GITHUB_MCP_ENABLED=true`。
  - 如果没有 `GITHUB_TOKEN`，保持 GitHub MCP disabled，并输出提示。
- `.env.local.example`
  - 示例：
    ```text
    GITHUB_MCP_ENABLED=true
    GITHUB_TOKEN=your_github_token_here
    ```

### 修改内容
- `.gitignore`
  - 忽略 `.env`、`.env.local`、`.env.*.local`。

### 验证结果
- 已执行脚本语法检查：
  ```bash
  bash -n scripts/run-dev-with-mcp.sh
  ```
  - 结果：通过。

### 使用方式
1. 第一次配置：
   ```bash
   cd java_src
   cp .env.local.example .env.local
   ```
2. 编辑 `.env.local`，填入自己的 GitHub token。
3. 后续启动：
   ```bash
   ./scripts/run-dev-with-mcp.sh
   ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 使用 `.env.local` 保存本机 GitHub MCP 配置。
- 使用启动脚本自动加载 `.env.local`。
- 不把真实 token 写入 `application.yml`。
- 如果没有 token，脚本不失败，只禁用 GitHub MCP 并提示。

#### 2. 每个决策的可选方案有哪些？
- 配置存放可选：直接写 `application.yml`、shell profile、IDE Run Configuration、`.env.local`。
- 自动加载可选：Spring Boot 额外配置库、Maven profile、启动脚本。
- 缺少 token 时可选：启动失败、禁用 GitHub MCP、继续启动但后续请求报错。

#### 3. 为什么选择当前方案？
- `.env.local` 简单直观，适合本地开发，且容易被 `.gitignore` 保护。
- 启动脚本不需要引入新依赖，也不改变 Spring Boot 配置体系。
- 缺少 token 时仍能启动项目，不影响普通阅读功能。

#### 4. 当前方案适合什么场景？
- 本地开发环境中经常启动项目并测试 GitHub MCP。
- 单人或小团队开发，不希望每次手动 export 环境变量。
- 不想把敏感 token 写进仓库文件。

#### 5. 当前方案不适合什么场景？
- 生产环境或容器化部署，应该使用部署平台的 secret/env 管理。
- 多用户动态 GitHub 授权场景，应该做 OAuth 或用户级 token 管理。
- 希望 IDE 自动注入环境变量但不通过脚本启动的场景。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：真实 token 只放本机未跟踪文件，不进入 `application.yml`。
- 最应该质疑：shell 脚本启动方式。后续如果全面使用 Docker 或 IDE Run Configuration，可以把同样的环境变量注入逻辑迁移过去。

## 2026-06-05：修复前端 GitHub 用户信息问题触发空计划

### 改动概括
- 修复前端聊天框询问“当前 GitHub 用户信息”时可能出现 `AUTO_PLAN_EMPTY` 的问题。
- curl 请求能成功，但前端请求会带当前章节正文、RAG、记忆和不同 session，模型规划阶段可能被阅读上下文带偏并返回 `{"calls":[]}`。
- 新增一个很窄的兜底：当问题明确包含 GitHub 用户信息意图，且 `github/get_me` 在 `allowedTools` 中时，自动补一个 `github/get_me` 调用计划。

### 修改内容
- `ExternalMcpToolPlannerService`
  - 在模型返回空 `calls` 时调用兜底判断。
  - 兜底仅匹配 GitHub 当前用户/用户信息/get_me 相关问题。
  - 兜底不会绕过白名单；如果 `github/get_me` 不在 `allowedTools` 中，仍返回 `AUTO_PLAN_EMPTY`。
- `ExternalMcpToolPlannerServiceTest`
  - 新增前端问题空计划时 fallback 到 `github/get_me` 的测试。
  - 新增 fallback 不绕过 `allowedTools` 的测试。

### 验证结果
- 已执行：
  ```bash
  mvn test -Dtest=ExternalMcpToolPlannerServiceTest,AiChatServiceTest
  ```
  - 结果：12 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：59 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 只对“GitHub 当前用户信息”这一类明确意图增加兜底。
- 兜底发生在模型返回空计划之后，不覆盖模型已经规划出的工具。
- 兜底仍必须经过 `allowedTools` 白名单。

#### 2. 每个决策的可选方案有哪些？
- 可选方案包括：不加兜底、增强规划 prompt、前端显式传 `externalMcpCalls`、后端增加意图兜底。
- 兜底范围可选：只支持 `github/get_me`、支持更多 GitHub 只读工具、做通用关键词路由。

#### 3. 为什么选择当前方案？
- 当前问题很明确：前端问题文本要求查看当前 GitHub 用户信息，对应工具就是 `github/get_me`。
- 增强 prompt 仍可能受模型不稳定影响；兜底能稳定解决这个演示和常用场景。
- 不让前端显式传工具，可以继续保持第三阶段“后端自动规划”的设计。
- 白名单约束仍在，避免兜底绕过安全边界。

#### 4. 当前方案适合什么场景？
- 用户明确询问当前 GitHub 用户信息。
- 前端带了大量阅读上下文，模型规划容易返回空计划的场景。
- 本地开发和演示 GitHub MCP 自动调用链路。

#### 5. 当前方案不适合什么场景？
- 更复杂的 GitHub 查询，例如读取某个仓库文件、搜索 issue、查看 PR。
- 需要通用自然语言到工具调用的完整路由系统。
- 需要多轮澄清参数的工具调用。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：兜底仍受 `allowedTools` 控制，不绕过安全策略。
- 最应该质疑：关键词兜底的可扩展性。后续应考虑可配置的 intent-to-tool 规则，而不是继续在代码中增加硬编码判断。

## 2026-06-05：分离自动 MCP 规划上下文与最终回答上下文

### 改动概括
- 修复自动 MCP 规划阶段被章节正文、RAG、Memory 和阅读系统指令带偏的问题。
- 自动规划阶段现在只接收极短规划上下文：当前用户问题、bookId/chapterIndex 和规划说明。
- 最终回答阶段仍保留完整上下文：当前章节、RAG、Memory、历史和 MCP 工具结果。

### 修改内容
- `AiChatService`
  - 自动规划调用从完整 `contextBuilder.build(...)` 改为 `buildPlanningContext(request)`。
  - `buildPlanningContext` 不包含章节正文、RAG chunk、Memory 内容和最终回答 system instructions。
  - 显式 `externalMcpCalls` 仍优先，不触发自动规划。
- `ExternalMcpToolPlannerService`
  - 规划 prompt 增强为“只依据用户问题和 allowedTools 判断是否需要工具”。
  - 明确说明当前 GitHub 用户信息应优先规划 `github/get_me`。
  - 明确说明读取 GitHub 仓库文件只有 owner/repo/path 参数足够时才规划 `github/get_file_contents`。
- 测试
  - `AiChatServiceTest` 新增断言：规划上下文不含章节/RAG/Memory，最终回答 prompt 仍包含这些证据和 MCP 结果。
  - `ExternalMcpToolPlannerServiceTest` 新增断言：规划 prompt 包含 GitHub 只读工具选择规则。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=ExternalMcpToolPlannerServiceTest,AiChatServiceTest
  ```
  - 结果：14 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：60 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：51 tests passed。

### 手动验证建议
1. 前端保持 `enableMemory=true`、`enableRag=true`。
2. 输入：
   ```text
   请查看我当前 GitHub 用户信息，并告诉我 MCP 是否调用成功
   ```
3. 期望看到：
   - `工具规划` 包含 `AUTO_PLAN github/get_me ...`
   - `MCP 工具` 包含 `OK github/get_me`

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 将“工具规划上下文”和“最终回答上下文”拆开。
- 规划上下文只保留当前问题和阅读位置。
- 最终回答上下文继续保留章节/RAG/Memory/MCP 结果。
- 保留 `github/get_me` 兜底，但把它作为最后防线。

#### 2. 每个决策的可选方案有哪些？
- 可选方案包括继续使用完整上下文、只截断上下文、给上下文加更强标签、完全拆分规划上下文。
- 规划 prompt 可选方案包括只写通用规则、为 GitHub 只读工具写明确选择规则、改用结构化 function calling。
- 兜底可选方案包括删除兜底、保留硬编码兜底、改为配置化 intent-to-tool 规则。

#### 3. 为什么选择当前方案？
- 完全拆分上下文能从根上减少阅读证据对工具规划的干扰。
- 保留最终回答完整上下文，不牺牲阅读问答质量。
- 增强 prompt 比只靠硬编码兜底更符合自动规划目标。
- 继续保留白名单和兜底安全边界，避免模型误调用写操作。

#### 4. 当前方案适合什么场景？
- 前端聊天默认开启 MCP，同时仍保留 RAG 和 Memory 的场景。
- 用户问题可能和阅读任务无关，需要调用外部工具辅助回答。
- 希望最终回答同时综合书内证据和外部工具结果。

#### 5. 当前方案不适合什么场景？
- 需要多轮工具调用和参数澄清的复杂 Agent 流程。
- 需要让规划器利用大量历史对话做长期任务规划的场景。
- 需要严格结构化工具调用协议而不是普通 JSON prompt 的生产级场景。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：规划上下文和回答上下文分离，这是自动工具调用稳定性的核心。
- 最应该质疑：规划仍依赖普通 chat JSON 输出。后续如果模型 API 支持结构化输出，应替换这层解析。

## 2026-06-05：强化最终回答对 MCP 工具结果的使用

### 改动概括
- 修复 MCP 工具已经成功调用，但最终回答仍按“阅读内容不足”拒答的问题。
- 明确区分两类问题：
  - 书籍/章节问题：继续优先使用当前章节和 RAG。
  - 外部工具/GitHub/MCP 问题：外部 MCP 工具结果是该部分问题的直接证据。
- 在工具结果 Evidence 标题中加入提示，要求最终回答优先使用 MCP 工具结果回答外部工具相关问题。

### 修改内容
- `AiChatService`
  - `buildSystemInstructions` 改为双轨证据规则。
  - `formatExternalMcpResults` 增加说明：这些结果已经由后端成功调用 MCP tools 得到，外部工具/GitHub/MCP 问题应优先使用该节。
- `AiChatServiceTest`
  - 增加断言，确保最终 prompt 包含“外部 MCP 工具结果是直接证据”和“优先使用本节回答”的规则。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=AiChatServiceTest,ExternalMcpToolPlannerServiceTest
  ```
  - 结果：14 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：51 tests passed。

### 手动验证建议
1. 重启后端并强刷前端。
2. 前端保持 `enableMemory=true`、`enableRag=true`、`enableExternalMcp=true`。
3. 输入：
   ```text
   请查看我当前 GitHub 用户信息，并告诉我 MCP 是否调用成功
   ```
4. 期望最终回答直接使用 GitHub MCP 返回的用户信息，而不是说“超出阅读范围”。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 最终回答系统指令改为“书内问题用书内证据优先，外部问题用 MCP 结果优先”。
- MCP 工具结果在 Evidence 中显式标注为已成功调用的外部工具结果。
- 不改变规划、执行和前端接口，只修正最终回答阶段的证据优先级。

#### 2. 每个决策的可选方案有哪些？
- 可选方案包括：只强化系统指令、只调整工具结果格式、把 MCP 结果放到 prompt 最前面、增加单独的外部问题回答模板。
- 也可以在前端检测 `OK github/get_me` 后直接展示工具结果，但这会绕开模型总结能力。

#### 3. 为什么选择当前方案？
- 当前问题不是 MCP 调用失败，而是最终模型误用“阅读内容不足”的规则。
- 双轨证据规则保留阅读问答的严谨性，同时允许外部工具问题正确使用 MCP 结果。
- 只改最终 prompt，风险小，不影响 MCP Client 和规划链路。

#### 4. 当前方案适合什么场景？
- 同一个聊天框既回答书籍内容，又回答 GitHub/MCP 外部工具问题。
- 外部工具结果已经成功返回，需要模型总结和解释。
- 希望最终答案明确区分书内证据和外部工具信息。

#### 5. 当前方案不适合什么场景？
- 需要完全禁止阅读页回答任何外部问题的场景。
- 需要前端直接展示原始工具 JSON、不经过模型总结的调试场景。
- 需要对不同外部工具设置不同回答模板的复杂生产场景。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：书内证据和外部 MCP 工具信息分开定优先级。
- 最应该质疑：仍然依赖自然语言 system instructions 约束模型。后续可以考虑把“外部工具答案”做成更结构化的 final prompt 模板。

## 2026-06-05：第四阶段，基于反思循环的动态 MCP Agent

### 改动概括
- 将自动 MCP 从“一次规划多个调用”升级为最多 5 轮的动态循环：单轮决策、工具调用、观察结果、再次决策。
- Java 后端不固定 GitHub 工具顺序；模型可以根据失败结果自行选择其他白名单只读工具继续探索。
- GitHub 自动工具白名单新增 `search_repositories`，支持从不完整仓库名称搜索完整 `owner/repo`，再继续读取文件。
- 显式 `externalMcpCalls` 继续沿用原执行逻辑，不进入 Agent 循环。

### 修改内容
- 新增 `ExternalMcpAgentService`
  - 最多执行 5 轮、5 次工具调用。
  - 每轮将用户目标、工具 Schema、历史调用参数和结果交给单轮决策器。
  - 工具错误和参数错误作为 Observation 返回下一轮，不立即中断主流程。
  - 每次执行前重新检查 server 状态和 `allowedTools`。
  - 阻止写操作、非白名单工具和相同参数的重复调用。
  - 支持 `complete`、`needs_confirmation`、`failed` 三种终止状态。
- 重构 `ExternalMcpToolPlannerService`
  - 从一次性调用列表生成器改为单轮决策器。
  - 严格解析 `status/assessment/reasoningSummary/call/message` JSON。
  - prompt 要求根据 Observation 动态反思，不依赖固定工具调用顺序。
- 扩展 `ExternalMcpClientService`
  - 新增 `isToolAllowed`，在每次自动调用前动态复查 server 和白名单状态。
- 修改 `AiChatService`
  - 自动模式调用 `ExternalMcpAgentService`。
  - Agent 成功结果继续作为 `tool_result` Evidence 进入最终回答。
  - `needs_confirmation` 提示也进入最终 prompt，并明确禁止模型自行选择候选。
  - 恢复“外部 MCP 结果是外部问题直接证据”的最终回答规则。
- 修改 `application.yml`
  - GitHub `allowedTools` 新增只读工具 `search_repositories`。
- 删除不再使用的单轮 `ExternalMcpPlanResult`。

### 可观察轨迹
自动执行摘要通过 `externalMcpPlanRefs` 返回，例如：
```text
AUTO_ROUND 1 CALL github/get_file_contents: 尝试读取目标文件
AUTO_OBSERVE 1 FAIL repository not found
AUTO_ROUND 2 CALL github/search_repositories: 搜索可能的完整仓库名
AUTO_OBSERVE 2 OK ...
AUTO_ROUND 3 CALL github/get_file_contents: 使用搜索结果中的完整仓库名
AUTO_OBSERVE 3 OK ...
AUTO_COMPLETE README 已取得
```

### 验证结果
- 目标测试覆盖：
  - 首次读取失败后动态选择 `search_repositories`。
  - 下一轮使用搜索结果中的完整 `owner/repo`。
  - 参数缺失作为 Observation 返回并允许修正。
  - 多候选进入用户确认状态。
  - 非白名单、写操作、重复调用和五轮上限均被阻止。
  - 显式调用不进入 Agent，最终回答仍能获得 MCP Evidence。
- 已执行：
  ```bash
  mvn test -Dtest=ExternalMcpToolPlannerServiceTest,ExternalMcpAgentServiceTest,AiChatServiceTest,ExternalMcpClientServiceTest
  ```
  - 结果：21 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：55 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 使用通用的 Observation 驱动 Agent 循环，不在 Java 中硬编码 GitHub 工具顺序。
- Planner 每轮只决定一个动作，循环和安全控制由后端负责。
- 自动模式只允许配置白名单中的只读工具。
- 工具失败不会直接结束任务，而是作为下一轮可以分析的事实。
- 多候选歧义必须交给用户确认。

#### 2. 每个决策的可选方案有哪些？
- 可选方案包括固定 `get_me -> search_repositories -> get_file_contents` 工作流、GitHub 专用仓库解析器、通用动态 Agent 循环。
- Planner 可以一次生成完整计划，也可以每轮基于最新 Observation 生成一个动作。
- 参数错误可以立即终止、由代码自动修正，或交回模型下一轮修正。
- 多候选可以自动选最高匹配、只接受精确匹配，或要求用户确认。

#### 3. 为什么选择当前方案？
- 动态循环能处理未预见的失败和其他 MCP 工具，不局限于仓库名称补全。
- 单步决策让后端能在每次真实调用前实施白名单、参数、重复和额度检查。
- 将错误作为 Observation 能保留 Agent 的恢复能力，同时不会让异常破坏阅读问答主链路。
- 用户确认优先于自动猜测，避免读取错误仓库后生成看似可信的答案。

#### 4. 当前方案适合什么场景？
- 工具之间存在数据依赖，后一步参数需要前一步结果的场景。
- 工具可能返回参数错误、空结果或模糊候选，需要继续探索的场景。
- 同一套 Agent 需要复用到 GitHub 之外的 HTTP MCP Server。
- 允许少量额外模型调用，以换取更强任务恢复能力的交互式应用。

#### 5. 当前方案不适合什么场景？
- 对响应延迟和模型调用成本极其敏感的高吞吐接口。
- 必须使用完全确定、可审计固定流程的业务操作。
- 需要自动执行写操作或跨请求持久化长期任务状态的场景。
- 模型无法稳定输出严格 JSON，且又没有原生 function calling 可用的环境。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：后端掌握循环、安全和执行权，模型只提出单步只读动作；Observation 必须进入下一轮。
- 最应该质疑：写操作识别目前依赖工具名称规则，未来应在配置或工具元数据中增加明确的 `readOnly`/风险等级；普通 chat JSON 也应在模型支持后替换为原生结构化工具调用。

## 2026-06-05：限制 MCP 大结果，修复 Moonshot 8K 上下文溢出

### 改动概括
- 修复 `list_commits` 等返回大量 JSON 的工具在 Agent 调用成功后，最终模型请求仍返回 HTTP 400 的问题。
- 根因是 `moonshot-v1-8k` 上下文有限，而旧 token 估算会漏算 JSON 标点、URL 和无空格英文；工具结果又未经长度控制同时进入后续规划和最终回答。
- 现在分别对 Agent Observation 和最终 MCP Evidence 设置内容预算，并改进上下文 token 估算。

### 修改内容
- `ExternalMcpToolPlannerService`
  - 单条 Observation 内容最多保留 3500 字符。
  - 全部 Observation JSON 最多保留 9000 字符。
  - 截断结果带明确标记，Agent 仍能知道结果不完整。
- `AiChatService`
  - 单个 MCP 工具结果最多进入最终 Evidence 6000 字符。
  - MCP Evidence 总长度最多 12000 字符。
  - 保留来源名称和截断标记。
- `ContextTokenCounter`
  - 中文字符仍按接近 1 token 估算。
  - 非中文可见字符改为约每 4 字符 1 token，覆盖密集 JSON、SHA、URL 和标点。
- `ModelClient`
  - HTTP 非成功响应现在输出响应 body，便于直接查看模型服务返回的上下文超限原因。
  - 使用 try-with-resources 正确关闭 OkHttp Response。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=ExternalMcpToolPlannerServiceTest,AiChatServiceTest,ContextTokenCounterTest
  ```
  - 结果：13 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：58 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 在规划和最终回答两个入口分别限制 MCP 大结果。
- 不直接更换模型，而是让当前 `moonshot-v1-8k` 下的上下文构建保持可控。
- 修正通用 token 估算，避免 JSON 内容再次绕过 ContextBuilder 预算。

#### 2. 每个决策的可选方案有哪些？
- 可以升级到更大上下文模型、只截断最终回答、只截断 Observation、对工具结果先调用模型摘要，或同时实施分层预算。
- token 统计可以继续使用简单分词、接入模型专用 tokenizer，或采用保守字符估算。
- 大工具结果可以直接丢弃、保留头部、保留头尾，或分页再次调用工具。

#### 3. 为什么选择当前方案？
- 两个阶段都会重复携带工具结果，只修一个入口仍可能在另一入口超过限制。
- 字符预算实现简单且确定，不需要额外模型调用，也不会增加网络延迟。
- 保守计数比旧算法更适合 GitHub 返回的密集 JSON。

#### 4. 当前方案适合什么场景？
- 使用 8K 左右上下文模型并调用提交列表、代码搜索、Issue 或 PR 列表等工具。
- 工具结果主要用于概括，而不要求逐字保留全部原始 JSON。
- 希望优先保证最终回答稳定返回的交互式应用。

#### 5. 当前方案不适合什么场景？
- 用户明确要求分析被截断部分中的某一条记录。
- 需要完整处理数百个提交或大型源码文件的批处理任务。
- 需要严格匹配供应商 tokenizer 和精确 token 计费的系统。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：规划 Observation 和最终 Evidence 必须拥有独立预算，不能让外部工具结果无限进入模型上下文。
- 最应该质疑：当前采用保留头部的字符截断；后续可改为工具分页、结构化字段筛选或专用摘要器，并将模型名和上下文预算配置化。

## 2026-06-05：强化动态 Agent 的仓库身份解析与失败假设淘汰

### 改动概括
- 修复用户只提供仓库简称时，Agent 可能把简称同时猜成 `owner/repo`，并在收到 404 后仍通过修改分页参数重复访问错误仓库的问题。
- 强制资源标识必须来自用户明确输入或成功工具 Observation，不允许模型凭空猜测。
- 后端记忆已经返回 Not Found 的仓库目标，阻止同一轮 Agent 再次使用相同 `owner/repo`。

### 修改内容
- `ExternalMcpToolPlannerService`
  - 增加资源标识来源规则：`owner/repo/path/SHA/编号` 必须有明确证据。
  - 明确禁止把仓库简称同时填为 owner 和 repo。
  - 404 后必须放弃旧目标，不能通过修改分页、分支等参数重试。
  - 搜索结果存在 `full_name` 或 `owner/name` 时，后续调用必须原样采用。
  - 明确代码搜索不能代替仓库身份解析或提交历史查询。
- `ExternalMcpAgentService`
  - 新增本次 Agent 执行范围内的 `rejectedRepositoryTargets`。
  - 仓库工具返回 404/Not Found 后记录 `server:owner/repo`。
  - 后续调用即使参数整体不同，只要仍指向已证伪仓库，就转成失败 Observation，不再发送到 MCP Server。

### 验证结果
- 已执行：
  ```bash
  mvn test -Dtest=ExternalMcpToolPlannerServiceTest,ExternalMcpAgentServiceTest
  ```
  - 结果：12 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 使用“证据约束的动态决策”，而不是为提交查询硬编码固定工具顺序。
- 将 404 视为对特定 `owner/repo` 假设的否定证据。
- 后端按资源身份去重失败目标，不再只按完整工具参数去重。

#### 2. 每个决策的可选方案有哪些？
- 可以固定先搜索仓库再列提交、完全信任模型反思、在 prompt 中加强约束，或增加后端失败目标保护。
- 失败去重可以按完整参数、按工具名、按资源身份，或永久缓存失败资源。
- 仓库简称可以默认归属当前用户、全局搜索，或要求用户补充 owner。

#### 3. 为什么选择当前方案？
- 用户要求 Agent 自主选择路径，因此不应把 GitHub 调用顺序写死。
- 仅靠 prompt 仍可能重复犯错，后端需要保证已经被事实证伪的目标不会再次消耗调用额度。
- 按 `owner/repo` 保护能拦截修改分页参数后的伪重试，同时允许 Agent 搜索并尝试新的真实候选。

#### 4. 当前方案适合什么场景？
- 用户提供仓库简称、模糊名称或拼写近似值的 GitHub 查询。
- MCP 工具需要多个资源标识，模型容易补全错误参数的场景。
- 希望保留动态探索能力，同时减少无效网络调用。

#### 5. 当前方案不适合什么场景？
- 仓库在 Agent 执行期间刚创建或权限刚变化，第一次 404 后立即重试可能本来有效。
- MCP Server 使用 404 表示临时故障而非资源不存在。
- 需要跨请求长期记忆失败资源的任务；当前只在单次 Agent 执行内生效。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：资源参数必须有证据来源，以及按资源身份淘汰已被 404 证伪的假设。
- 最应该质疑：目前 Not Found 依赖错误文本匹配；以后应让 MCP Client 返回结构化错误码，并为 Observation 建立明确的资源实体字段。

## 2026-06-05：撤回对 owner 与 repo 同名的过度限制

### 改动概括
- 删除“禁止把仓库简称同时作为 owner 和 repo”的规划规则。
- `owner` 与 `repo` 同名在 GitHub 中可能是合法仓库，不能仅凭两个字段相同判断 Agent 参数错误。
- 保留真正需要的约束：搜索成功后必须使用 Observation 中的 `full_name` 或 `owner/name`，不能继续沿用已被 404 证伪的旧路径。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 不使用 `owner == repo` 作为错误判定条件。
- 继续根据参数来源和工具 Observation 判断仓库路径是否可信。

#### 2. 每个决策的可选方案有哪些？
- 可以禁止 owner/repo 同名、允许但要求证据来源，或完全信任模型参数。

#### 3. 为什么选择当前方案？
- GitHub 允许用户创建与用户名同名的仓库，禁止该形式会误伤合法查询。
- 本次实际错误是 Agent 忽略搜索结果，而不是两个字段碰巧相同。

#### 4. 当前方案适合什么场景？
- 仓库所有者和仓库名可能相同，同时仍要求 Agent 使用真实搜索结果的场景。

#### 5. 当前方案不适合什么场景？
- 无法获得任何资源来源、只能依靠模型猜测仓库路径的场景。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：成功搜索结果必须成为后续调用参数的证据来源。
- 最应该质疑：不要把字段值模式当成资源真实性判断，应优先使用结构化 Observation 和错误码。

## 2026-06-05：提高 MCP Evidence 优先级，修复工具成功但最终回答看不到结果

### 改动概括
- 修复前端显示多个 `OK github/list_commits`，但最终回答仍声称没有提交信息的问题。
- MCP 调用确实成功；失败发生在最终 ContextBuilder 选包阶段：历史失败 Memory 和长章节先占满预算，整块 MCP Evidence 被跳过。
- 工具结果现在优先于章节、RAG、Memory 和历史进入最终上下文。

### 修改内容
- `ContextBuilder`
  - 增加 ContextPacket 类型优先级。
  - 顺序为：`tool_result`、`task_state`、`current_chapter`、RAG、Memory、History。
  - 同类型内部仍按相关度和时效性排序。
- `ExternalMcpToolPlannerService`
  - 搜索得到多个不同 owner 的合理同名仓库时，要求返回 `needs_confirmation`。
  - 禁止为了一个未明确归属的仓库简称，依次查询多个候选仓库并消耗全部调用额度。
- 新增 `ContextBuilderTest`
  - 验证超长章节与 Memory 存在时，`github/list_commits` 结果仍保留在最终上下文。

### 验证结果
- 已执行：
  ```bash
  mvn test -Dtest=ContextBuilderTest,ContextTokenCounterTest,AiChatServiceTest,ExternalMcpToolPlannerServiceTest
  ```
  - 结果：14 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 将真实工具结果设为 ContextBuilder 的最高 Evidence 优先级。
- 多候选仓库不再逐一查询，而是要求用户确认目标。

#### 2. 每个决策的可选方案有哪些？
- 可以关闭 Memory/RAG、扩大上下文预算、按相关度继续竞争，或为工具结果设置固定优先级。
- 多候选可以逐个查询、自动选第一项、按相似度选择，或要求用户确认。

#### 3. 为什么选择当前方案？
- 用户主动启用并成功执行 MCP 后，最终模型必须看到该结果，否则调用链没有实际价值。
- 历史失败回答不应覆盖本次实时工具事实。
- 多候选逐一查询既浪费额度，也混合了不同仓库的数据，无法给出可信的提交统计。

#### 4. 当前方案适合什么场景？
- 阅读上下文很长，但用户当前问题明确依赖外部工具结果。
- Memory 中存在先前失败回答，需要实时工具结果纠正旧信息。
- 仓库简称对应多个 GitHub 仓库，需要用户消除歧义。

#### 5. 当前方案不适合什么场景？
- 希望任何情况下章节正文都绝对优先于外部工具结果的纯阅读模式。
- 用户明确要求比较多个同名仓库，此时应该允许分别查询候选。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：成功工具 Evidence 不能因普通相关度排序而被整块丢弃。
- 最应该质疑：当前类型优先级是全局固定值；后续可以根据问题分类动态调整阅读证据与工具证据的预算比例。

## 2026-06-06：第五阶段，可暂停、可恢复的交互式 MCP Agent

### 改动概括
- 将动态 MCP Agent 从“遇到歧义时给最终模型一段提示”升级为真正的交互式暂停。
- 当 Agent 返回 `needs_confirmation` 时，后端返回结构化 `interaction`，前端展示候选按钮和自定义输入。
- 用户确认仍复用 `/api/ai/chat`，后端从内存 pending store 恢复原 Agent 现场，继续调用 MCP 并生成最终答案。

### 修改内容
- `AiChatRequest`
  - 新增 `confirmationId`、`selectedOptionId`、`customAnswer`。
  - 新增 `hasConfirmationId()`，用于区分新问题和确认请求。
- `AiChatResponse`
  - 新增 `status=completed|needs_confirmation`。
  - 新增 `interaction`，包含 `confirmationId`、确认问题、候选、是否允许自定义回答和过期时间。
- 新增交互 DTO
  - `AiChatInteraction`
  - `AiChatInteractionOption`
- `ExternalMcpToolPlannerService`
  - `needs_confirmation` 决策支持解析 `options[]`。
  - 规划 prompt 要求候选携带 `id`、`label`、`description`、结构化 `value`。
- `ExternalMcpAgentService`
  - 引入 `PendingMcpAgentState`，保存原始请求、observations、已获得结果、refs、已执行调用、已证伪仓库目标、轮数和调用额度。
  - 引入 `PendingMcpInteractionStore`，使用内存保存待确认任务，默认 30 分钟过期。
  - 新增 `resume(...)`，消费 confirmation 后把候选结构化 `value` 或自定义回答作为用户澄清 Observation 注入下一轮。
  - 新问题会取消同一 `userId + sessionId` 的旧 pending 任务。
  - 暂停时返回 `needs_confirmation`，不调用最终回答模型。
- `AiChatService`
  - 确认请求先恢复 Agent，再用原始问题和原始阅读上下文生成最终答案。
  - 暂停阶段直接返回交互对象，不调用 `modelClient.chat()`，不写 episodic memory。
- `reader.html`
  - Assistant 消息下方渲染候选按钮。
  - 支持自定义回答输入。
  - 点击候选或提交自定义回答后，复用 `/api/ai/chat` 发送 confirmation 请求。
  - 确认请求进行中禁用候选，防止重复提交。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=ExternalMcpAgentServiceTest,ExternalMcpToolPlannerServiceTest,AiChatServiceTest
  ```
  - 结果：23 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 使用内存 pending store 保存待确认 Agent 现场，不持久化到数据库。
- 确认请求继续复用 `/api/ai/chat`，不新增专门确认接口。
- 响应中只暴露候选 `id/label/description`，结构化 `value` 只保存在后端。
- 用户选择候选后，将后端保存的结构化 `value` 注入 Agent Observation；自定义回答则作为澄清文本注入。
- 暂停阶段不调用最终回答模型，也不写入 episodic memory。

#### 2. 每个决策的可选方案有哪些？
- 状态可以保存在内存、数据库、Redis，或直接把完整现场发给前端。
- API 可以复用聊天接口，也可以新增 `/api/ai/chat/confirm`。
- 候选结构化值可以暴露给前端，也可以只让前端提交 optionId。
- 恢复后可以直接把用户选择转换为固定工具参数，也可以作为 Observation 交给 Agent 再判断。
- 暂停时可以让最终模型生成自然语言确认问题，也可以由后端直接返回结构化确认对象。

#### 3. 为什么选择当前方案？
- 第一版交互状态不需要跨服务重启恢复，内存实现最轻，和当前单体 Spring Boot 结构匹配。
- 复用 `/api/ai/chat` 能保持前端和 Controller 链路简单，也符合用户继续对话的心理模型。
- 不暴露结构化 `value` 可以防止前端篡改候选参数，后端只信任 confirmationId 和 optionId。
- 把澄清作为 Observation 交给 Agent，保留“Agent 自主判断下一步”的能力，不把 GitHub 仓库恢复流程写死。
- 暂停不写记忆，避免把“尚未回答完成”的确认问题当成最终结论沉淀进长期记忆。

#### 4. 当前方案适合什么场景？
- 单机开发或单实例部署，允许服务重启后待确认任务失效。
- GitHub 仓库、文件、分支、提交等外部资源存在多个候选，需要用户选择。
- 工具调用链路需要先探索，再由用户消除歧义，然后继续执行。
- 前端聊天框希望保持一个统一入口，而不是跳转到独立确认页面。

#### 5. 当前方案不适合什么场景？
- 多实例部署且请求可能被负载均衡到不同节点，除非把 pending store 改成共享存储。
- 确认任务必须跨重启、跨设备或长期保留的场景。
- 需要复杂多选、批量确认或审批流的写操作场景。
- 安全要求前端必须展示完整结构化参数并由用户逐项审计的场景。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：后端保存完整 Agent 现场并只让前端提交 optionId；暂停阶段不生成虚假最终答案、不写长期记忆。
- 最应该质疑：内存 pending store 只适合第一版；后续如果部署到多实例，应改为 Redis/数据库，并为 pending 状态增加更细的审计字段和清理任务。

## 2026-06-06：修复交互式 MCP Agent 的启动注入与前端确认点击问题

### 改动概括
- 修复 `ExternalMcpAgentService` 有两个构造器时，真实 Spring 启动可能无法稳定选择带 `PendingMcpInteractionStore` 的构造器的问题。
- 前端候选按钮从 inline `onclick` 拼接改为 `data-*` 属性加事件委托，避免 optionId 中特殊字符导致点击确认失败。

### 修改内容
- `ExternalMcpAgentService`
  - 在四参数构造器上显式添加 `@Autowired`。
  - Spring 运行时会注入单例 `PendingMcpInteractionStore`，测试仍可继续使用三参数构造器手动创建服务。
- `reader.html`
  - 候选按钮使用 `data-msg-index`、`data-option-id` 保存点击参数。
  - `#aiMessages` 统一监听 `.ai-option-btn` 和 `.ai-custom-confirm` 点击事件。

### 验证结果
- 已执行：
  ```bash
  mvn test -Dtest=ExternalMcpAgentServiceTest,AiChatServiceTest
  ```
  - 结果：18 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 用 `@Autowired` 明确 Spring 应使用生产构造器。
- 保留三参数构造器作为单元测试便利入口。
- 前端改用事件委托处理动态渲染出的确认按钮。

#### 2. 每个决策的可选方案有哪些？
- 可以删除测试构造器、给测试改用 pending store 参数、添加 `@Autowired`，或改成字段注入。
- 前端可以继续 inline `onclick`、逐个按钮绑定事件，或使用事件委托。

#### 3. 为什么选择当前方案？
- `@Autowired` 改动最小，能直接消除 Spring 多构造器选择风险。
- 保留测试构造器不会破坏现有单元测试可读性。
- 事件委托适合聊天消息这种频繁重绘的动态 DOM。

#### 4. 当前方案适合什么场景？
- Spring Bean 需要一个生产构造器，同时测试希望保留轻量手动实例化。
- 前端消息列表会反复 `innerHTML` 重绘，按钮节点不断重建。

#### 5. 当前方案不适合什么场景？
- 如果后续要求所有构造器都必须只保留一个，则应删除三参数测试构造器并调整测试。
- 如果前端切换到组件框架，事件绑定应交给组件状态和事件系统。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：生产构造器的注入路径必须显式、稳定；动态消息按钮不要依赖脆弱的 inline JS 拼接。
- 最应该质疑：长期看应减少测试专用构造器，改用测试 fixture 或 Spring 测试配置来提供 pending store。

## 2026-06-06：强制多仓库搜索结果进入用户确认

### 改动概括
- 修复 Agent 搜索到多个 `httpread` 仓库后，没有暂停让用户选择，而是继续查询多个仓库并把结果混合回答的问题。
- 后端现在会解析 `github/search_repositories` 的成功结果；只要发现多个仓库候选，就立即返回 `needs_confirmation`。
- 这是一道安全控制，不依赖模型是否自觉返回 `needs_confirmation`。

### 修改内容
- `ExternalMcpAgentService`
  - 新增 `forceConfirmationForAmbiguousRepositorySearch(...)`。
  - 对 `search_repositories` 返回的 `items[].full_name` 生成最多 3 个候选。
  - 候选 `value` 保存 `{owner, repo}`，前端只拿到 optionId/label/description。
  - 进入确认后停止后续 Agent 轮次，不再调用 `list_commits` 等仓库工具。
- `ExternalMcpAgentServiceTest`
  - 新增测试：模型搜索到多个仓库后即使下一步想调用 `list_commits`，后端也会强制暂停确认。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=ExternalMcpAgentServiceTest,AiChatServiceTest
  ```
  - 结果：19 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：64 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 将“多个合理仓库候选必须用户确认”从 prompt 约束升级为后端强制控制。
- 只在 `search_repositories` 的结构化结果中提取候选，不为具体问题硬编码仓库名称。
- 暂停时保留已经完成的搜索结果和 Agent 现场，用户选择后继续恢复执行。

#### 2. 每个决策的可选方案有哪些？
- 可以继续只依赖模型自觉判断，也可以在后端按工具结果强制确认。
- 可以硬编码 `httpread` 规则，也可以解析通用 GitHub 仓库搜索结果。
- 可以自动选择第一个候选、查询全部候选，或交给用户确认。

#### 3. 为什么选择当前方案？
- 实际现象证明 prompt 不足以保证模型一定暂停。
- 查询多个同名仓库会混合不同项目的提交信息，最终答案不可信。
- 解析 `items[].full_name` 是明确的工具 Observation，不需要猜测，也不改变 Agent 自主规划能力。

#### 4. 当前方案适合什么场景？
- 用户给出仓库简称，GitHub 搜索返回多个同名或近似仓库。
- 查询提交、文件、分支等必须依赖唯一 `owner/repo` 的工具。
- 需要防止 Agent 把多个候选的结果合并成一个答案。

#### 5. 当前方案不适合什么场景？
- 用户明确要求“比较所有同名仓库”或“列出所有搜索结果”的任务；后续可以增加显式多目标模式。
- GitHub MCP 返回格式变化，且不再包含 `items[].full_name` 的场景。
- 非 GitHub MCP 的资源候选确认；当前实现只处理 GitHub 仓库搜索结果。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：后端必须在资源歧义时有强制停止能力，不能完全信任模型自律。
- 最应该质疑：当前候选解析是 GitHub 专用；后续可抽象成通用 “candidate extractor”，由每个 MCP Server 配置哪些工具结果代表候选集合。

## 2026-06-06：确认后的 GitHub 仓库目标强制生效

### 改动概括
- 修复用户在候选中选择了 `Heng-Bian/httpread` 后，模型仍可能继续调用 `psanford/httpread` 或其他仓库的问题。
- 用户确认的 `owner/repo` 现在会保存到 Agent pending state 中。
- 后续只要 MCP 工具调用参数中包含 `owner` 和 `repo`，后端会把它们重写为用户确认的仓库目标。

### 修改内容
- `PendingMcpAgentState`
  - 新增 `confirmedRepositoryOwner`、`confirmedRepositoryRepo`。
  - 新增 `setConfirmedRepositoryTarget(...)` 与 `hasConfirmedRepositoryTarget()`。
- `ExternalMcpAgentService`
  - 用户选择候选后，从候选结构化 `value` 中记录确认仓库。
  - 新增 `applyConfirmedRepositoryTarget(...)`。
  - 若模型后续生成了不同 `owner/repo`，后端会改写为用户确认值，并记录：
    `AUTO_REWRITE_REPOSITORY_TARGET oldOwner/oldRepo -> confirmedOwner/confirmedRepo`
- `ExternalMcpAgentServiceTest`
  - 新增测试：用户选择 `Heng-Bian/httpread` 后，即使模型规划 `psanford/httpread`，实际 MCP 调用也会被重写为 `Heng-Bian/httpread`。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=ExternalMcpAgentServiceTest,AiChatServiceTest
  ```
  - 结果：20 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：65 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 把用户确认的仓库目标作为后端权威状态保存。
- 对后续包含 `owner/repo` 的仓库工具调用执行参数重写。
- 不改变模型选择哪个工具，只约束工具中的仓库目标必须是用户确认过的目标。

#### 2. 每个决策的可选方案有哪些？
- 可以继续只把确认结果作为 Observation 交给模型，也可以后端拦截错误仓库，或后端直接重写仓库参数。
- 可以禁止后续任何 `search_repositories`，也可以只约束有 `owner/repo` 的仓库工具。
- 可以在前端把 owner/repo 再提交回来，也可以只由后端 pending store 保存结构化值。

#### 3. 为什么选择当前方案？
- 实际测试证明模型可能忽略用户确认结果，只靠 Observation 不够可靠。
- 用户已经明确选择了仓库，后端重写 `owner/repo` 比继续让模型试错更符合用户意图。
- 只重写仓库参数，不硬编码工具顺序，仍保留 Agent 自主选择 `list_commits`、`get_file_contents` 等工具的能力。

#### 4. 当前方案适合什么场景？
- 用户从多个 GitHub 仓库候选中单选一个，后续所有仓库工具都应作用于该目标。
- 模型可能保留旧候选或错误候选，但用户选择已经消除了资源歧义。
- 需要防止不同仓库结果混入同一个最终答案。

#### 5. 当前方案不适合什么场景？
- 用户希望在确认后仍比较多个仓库或跨仓库汇总。
- 一次确认需要绑定多个仓库目标的多选任务。
- 非 GitHub 仓库类资源；当前实现只识别 `owner/repo` 形态。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：用户确认后的结构化目标必须成为后端强约束，不能只作为自然语言提示。
- 最应该质疑：当前参数重写是 GitHub `owner/repo` 专用；后续应扩展为通用 resource binding，让不同 MCP 工具声明哪些参数受用户确认约束。

## 2026-06-06：确认仓库后过滤候选搜索 Evidence

### 改动概括
- 修复用户确认某个仓库后，最终回答仍提到候选搜索结果中的其他作者或其他仓库的问题。
- 根因是 `search_repositories` 的候选结果和确认后的 `list_commits` 结果一起进入最终回答 Evidence。
- 现在一旦存在 `AUTO_CONFIRMED_REPOSITORY owner/repo`，最终回答 Evidence 会跳过 `search_repositories` 结果，只保留后续针对确认仓库的工具结果。

### 修改内容
- `AiChatService`
  - `formatExternalMcpResults(...)` 增加 planRefs 参数。
  - 检测 `AUTO_CONFIRMED_REPOSITORY` 后，不再把 `search_repositories` 内容写入最终模型 prompt。
  - 增加一段 `MCP Agent 确认约束`，明确最终回答只能围绕已确认仓库。
- `AiChatServiceTest`
  - 新增测试：确认 `psanford/httpread` 后，即使搜索候选中包含 `Heng-Bian/httpread`，最终 prompt 也不包含该候选内容。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=ExternalMcpAgentServiceTest,AiChatServiceTest
  ```
  - 结果：21 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：66 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 把候选搜索结果视为“消歧过程证据”，不视为“最终回答事实证据”。
- 用户确认仓库后，最终模型只接收确认目标相关的后续工具结果。
- 在最终 prompt 中加入确认约束，明确其他候选不得作为回答对象。

#### 2. 每个决策的可选方案有哪些？
- 可以保留搜索候选并用 prompt 要求忽略，也可以完全过滤候选结果，或给每条工具结果增加 evidence 类型。
- 可以在 Agent 层删除 `search_repositories` 结果，也可以只在最终回答格式化阶段过滤。
- 可以不加确认约束，也可以在 prompt 中显式说明确认仓库。

#### 3. 为什么选择当前方案？
- 只靠 prompt 要求忽略候选不够稳定，模型仍可能引用候选中的其他作者。
- 不删除 Agent results，可以保留前端 `MCP 工具` 和 `工具规划` 的可观察轨迹。
- 在最终格式化阶段过滤影响范围最小，不改变 Agent 循环和 MCP 调用记录。

#### 4. 当前方案适合什么场景？
- 工具先搜索候选，再由用户确认唯一目标，随后围绕唯一目标回答。
- 候选列表中存在多个同名项目，不能混入最终答案。
- 需要前端仍展示“曾经搜索过候选”，但最终回答只使用确认后的工具结果。

#### 5. 当前方案不适合什么场景？
- 用户要求比较多个候选仓库，或明确询问“搜索到了哪些仓库”。
- 搜索结果本身就是最终答案，而不是后续工具调用的中间步骤。
- 未来支持多选仓库任务时，需要按目标分组保留多个仓库的 Evidence。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：候选发现和最终回答证据必须分层，不能把消歧候选直接作为最终事实。
- 最应该质疑：当前用 toolName 和 `AUTO_CONFIRMED_REPOSITORY` 判断过滤；后续应在 `ExternalMcpCallResult` 上增加结构化用途标记，例如 `DISCOVERY`、`EVIDENCE`、`CONTROL`。

## 2026-06-06：自动 MCP Agent 增加身份作用域绑定

### 改动概括
- 修复用户说“我的 GitHub / 我的仓库 / 当前账号”时，Agent 仍直接做全站仓库搜索，导致找不到用户自己仓库或混入其他作者仓库的问题。
- 根因是 GitHub MCP 的 token 只代表鉴权身份，不会自动把 `search_repositories` 限定到 token 所属用户；Agent 必须显式解析当前账号并把搜索作用域带入工具参数。
- 新增一套通用控制策略：当目标带有“我的/当前账号”等身份作用域时，后端先帮助 Agent 获取主体身份，再把该主体作为搜索约束；搜索仍不命中特定名称时，再扩大到该主体仓库列表并交给用户确认。

### 修改内容
- `PendingMcpAgentState`
  - 新增 `principalByServer`，保存每个 MCP server 当前解析出的主体身份，例如 GitHub login。
  - 新增 `expandedRepositorySearchServers`，避免同一个 server 对空结果反复扩大搜索。
- `ExternalMcpAgentService`
  - 在自动执行工具前加入身份作用域检查。
  - 当用户目标包含“我的 GitHub / 我的仓库 / my repo”等表达，且模型直接规划了 `search_repositories`，会先调用只读身份工具 `get_me`。
  - 从 `get_me` 结果中提取 `login`、`username` 或 `name`，作为后续搜索的主体。
  - 对主体已知的仓库搜索自动追加作用域，例如 `user:LLhamster`。
  - 如果带名称的主体内搜索返回空结果，会自动追加一次“列出该主体仓库”的搜索，让前端候选确认机制有机会展示相近仓库，例如 `HTTP_READING`。
- `ExternalMcpToolPlannerService`
  - 强化规划 prompt：当用户目标表达“我的/当前账号/authenticated/current user”时，必须先解析身份或使用已解析身份约束搜索，避免无 owner 的全站搜索。
- `ExternalMcpAgentServiceTest`
  - 新增测试：自有仓库搜索会先解析当前 GitHub 用户，再把 `user:LLhamster` 写入仓库搜索。
  - 新增测试：带名称搜索为空时，会扩大为当前用户仓库候选，并返回 `needs_confirmation`。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=ExternalMcpAgentServiceTest,ExternalMcpToolPlannerServiceTest,AiChatServiceTest
  ```
  - 结果：28 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：68 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 把“我的/当前账号”识别为身份作用域问题，而不是普通关键词搜索问题。
- 后端只做身份解析、作用域注入、空结果扩大搜索和安全边界，不把 `httpreading` 或某个仓库名写死。
- 继续让 Agent 自主决定工具目标和后续步骤，但对跨用户搜索这种容易偏离用户意图的场景加控制约束。
- 搜索为空时只扩大到“当前主体的仓库列表”，不扩大到全 GitHub 的相似仓库列表。

#### 2. 每个决策的可选方案有哪些？
- 可以完全依赖模型理解“我的 GitHub”，也可以后端显式解析身份并注入作用域。
- 可以把 GitHub 用户名写在配置里，也可以每次通过 `get_me` 从 MCP 工具动态获取。
- 可以为 `httpreading` 写特判，也可以实现“主体解析 + 资源发现 + 用户确认”的通用模式。
- 空结果时可以直接回答没有，也可以全站搜索，也可以只列出当前主体仓库候选。

#### 3. 为什么选择当前方案？
- GitHub token 不等于搜索作用域；如果不显式加 `user:<login>`，`search_repositories` 仍可能全站搜索或按工具默认逻辑搜索。
- 动态调用 `get_me` 比配置用户名更稳，换 token、换用户、换环境时不需要改配置。
- 不写死仓库名可以覆盖“我的某个项目”“当前账号下类似名字的仓库”“我的仓库 README”等同类问题。
- 空结果时扩大到当前主体仓库列表，可以解决大小写、下划线、简称、近似名等问题，同时避免把全站无关仓库混进来。

#### 4. 当前方案适合什么场景？
- 用户明确说“我的 GitHub”“我的仓库”“当前账号”“authenticated user”等带身份归属的问题。
- 仓库名存在大小写、下划线、简称或拼写不完全，例如用户说 `httpreading`，真实仓库是 `HTTP_READING`。
- 外部 MCP 工具没有自动限定 token 所属资源，需要调用方显式提供 owner、user 或 scope。
- 搜索到多个候选时，需要通过交互式确认让用户选择真实目标。

#### 5. 当前方案不适合什么场景？
- 用户明确想搜索全 GitHub 或比较不同作者的公开仓库。
- 用户没有表达“我的/当前账号”，且问题本身需要开放搜索。
- MCP server 没有身份工具，也没有可表达主体作用域的搜索语法。
- 未来需要跨多个账号、组织或团队空间同时搜索时，单一主体作用域不够，需要更丰富的资源范围模型。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：先解析主体身份，再带主体搜索；这是让 Agent 理解“我的资源”的关键，不应只靠自然语言 prompt。
- 最应该保留：后端不写死具体仓库名，只提供作用域约束、候选发现、确认和重复控制。
- 最应该质疑：当前作用域实现仍包含 GitHub 的 `get_me`、`search_repositories`、`user:<login>` 语法；后续可以抽象成每个 MCP server 可配置的 `identityTool`、`searchScopeTemplate` 和候选提取器。

## 2026-06-06：撤回外部 GitHub MCP 身份作用域绑定

### 改动概括
- 根据新的产品判断，外部 MCP 工具先按“公共外部工具”处理，不默认绑定当前用户身份。
- 身份信息暂时只适合本项目自己的私有能力，例如用户书架、记忆、笔记，而不是 GitHub 这类外部 MCP 工具的通用默认行为。
- 撤回上一版中对“我的 GitHub / 当前账号”进行后端强制身份解析、自动调用 `get_me`、自动追加 `user:<login>` 的逻辑。
- 保留动态 Agent、候选确认、确认后 owner/repo 绑定、失败目标去重、写工具拦截等通用能力。

### 修改内容
- `PendingMcpAgentState`
  - 移除 `principalByServer`。
  - 移除 `expandedRepositorySearchServers`。
- `ExternalMcpAgentService`
  - 移除自动身份作用域检查。
  - 移除强制替换为 `get_me` 的逻辑。
  - 移除从 `get_me` 结果缓存 GitHub login 的逻辑。
  - 移除空结果时自动扩展为 `user:<login>` 仓库搜索的逻辑。
- `ExternalMcpToolPlannerService`
  - 移除 prompt 中“我的/当前账号必须先解析账号身份”的要求。
  - 保留资源标识约束：owner、repo、路径、SHA 等必须来自用户输入或工具 Observation。
  - 将“代码搜索不能代替仓库身份解析”调整为“代码搜索不能代替仓库发现或提交历史查询”。
- `ExternalMcpAgentServiceTest`
  - 删除身份作用域专用测试。
- `ExternalMcpToolPlannerServiceTest`
  - 更新 prompt 断言，匹配新的通用仓库发现表述。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=ExternalMcpAgentServiceTest,ExternalMcpToolPlannerServiceTest,AiChatServiceTest
  ```
  - 结果：26 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：66 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 外部 MCP 默认不绑定当前用户身份。
- 身份作用域暂时只放在本项目内部私有数据能力中考虑，例如书架、记忆、笔记。
- GitHub 等外部工具的 owner、repo、query 由用户输入、工具结果或交互确认来确定。
- 保留通用 Agent 控制能力，不保留 GitHub 身份兜底。

#### 2. 每个决策的可选方案有哪些？
- 可以保留 GitHub 身份兜底，也可以完全移除，或改成配置开关。
- 可以把身份解析做成所有 MCP server 的通用机制，也可以只在本地私有工具里使用。
- 可以让模型自行决定是否调用 `get_me`，也可以后端强制插入 `get_me`。
- 可以继续扩大空搜索结果，也可以让 Agent 根据 Observation 自己选择下一步或请求用户确认。

#### 3. 为什么选择当前方案？
- 当前系统的 MCP 目标更像“外部能力接入”，不是“用户私有资源代理”。
- GitHub token 用于鉴权，不应被默认解释成“所有 GitHub 问题都限定到当前 token 用户”。
- 后端强行注入身份会让 Agent 行为变得偏 GitHub，不利于后续接入更多和用户无关的外部 MCP。
- 交互确认和资源标识约束已经能解决大部分“目标不明确”的问题，而且更通用。

#### 4. 当前方案适合什么场景？
- MCP 工具用于搜索外部公开信息、调用第三方只读能力、查询和用户身份无关的数据。
- 用户明确给出 owner/repo、URL、文件路径、编号等资源标识。
- 工具搜索返回多个候选，需要用户选择具体目标。
- 后续要接入更多非 GitHub MCP，不希望每个工具都被用户身份概念污染。

#### 5. 当前方案不适合什么场景？
- 用户明确要求“我的私有数据”“我的书架”“我的笔记”“我的阅读记忆”等本项目内部私有能力。
- 外部 MCP 的业务语义天然绑定当前登录用户，例如个人日历、私人邮箱、个人云盘。
- 需要严格实现“只在当前 token 用户资源内搜索”的 GitHub 私有工作流。
- 用户期望“我的 GitHub”自动等价于 token 所属账号，而不是公开搜索语义。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：资源标识必须来自用户输入、工具 Observation 或用户确认，不能让模型猜 owner/repo。
- 最应该保留：候选不唯一时暂停并让用户确认，而不是后端替用户选。
- 最应该质疑：是否未来需要一个可配置的“私有 MCP 工具类型”，只对书架、记忆、笔记、个人云盘这类工具启用身份作用域。

## 2026-06-06：阅读上下文与记忆压缩

### 改动概括
- 当前章节不再整章进入最终 prompt，改为由后端按用户问题抽取相关片段。
- 情景记忆不再保存完整问答长文本，改为保存短摘要式记忆。
- 保留现有 RAG、Memory、MCP Agent 链路，只在进入 prompt 和写入 memory 前做压缩。
- 本次虽不是 MCP 协议功能，但会直接改善 MCP 工具结果、RAG、章节片段和 Memory 混合进入最终回答 prompt 时的上下文质量。

### 修改内容
- `ReadingContextCompactionService`
  - 新增轻量章节裁剪服务。
  - 普通问题按关键词/字符重合度选择最多 3 个相关片段。
  - 每个片段和总章节上下文都有长度上限。
  - “概括本章 / 总结本章 / 本章讲了什么”等整体问题走章节概览片段。
- `AiChatService`
  - 使用 `ReadingContextCompactionService` 生成“当前章节相关片段”，不再塞整章前 6000 字符。
  - 将会话历史从 10 条降为 5 条。
  - 记忆展示最多 4 条，优先 episodic / semantic，每条最多 220 字符。
  - 写入记忆时传入本次 RAG sourceCount。
- `AgentMemoryService`
  - `rememberTurn(...)` 保留原签名，同时新增带 `sourceCount` 的重载。
  - working memory 中用户问题和助手回答分别限长。
  - episodic memory 改为“阅读问答摘要：问题 / 结论 / 位置”的短文本。
  - metadata 增加 `summary=true`，并在可用时记录 `sourceCount`。
- `ContextBuilder`
  - 调整上下文优先级：MCP 工具结果、RAG、当前章节片段、Memory、History。
- 测试
  - 新增 `ReadingContextCompactionServiceTest`。
  - 新增 `AgentMemoryServiceTest`。
  - 扩展 `AiChatServiceTest` 覆盖章节裁剪、概览问题和 sourceCount 写入。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=AiChatServiceTest,AgentMemoryServiceTest,ReadingContextCompactionServiceTest
  ```
  - 结果：17 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：73 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 章节内容在进入 prompt 前先做入口级裁剪，而不是依赖最后的总 token 截断。
- 第一版使用规则裁剪和规则摘要，不调用 LLM 生成摘要。
- 记忆写入改为短摘要，旧数据不迁移、不清理。
- RAG 仍作为主要书内证据，当前章节片段作为补充上下文。

#### 2. 每个决策的可选方案有哪些？
- 章节处理可以整章截断、相关片段抽取、章节摘要缓存，或 LLM 动态摘要。
- 记忆可以保存完整问答、摘要+原文、仅摘要，或只在重要时保存。
- 压缩可以放在前端、`AiChatService`、`ContextBuilder`，或记忆系统内部。
- 历史长度可以继续 10 条，也可以降低到 4-6 条，或由 token 预算动态决定。

#### 3. 为什么选择当前方案？
- 当前问题主要是 prompt 臃肿和记忆噪音，入口级裁剪比末尾截断更可控。
- 不调用 LLM 可以避免额外成本、延迟和失败点。
- 不改数据库结构，风险小，能快速改善新产生的上下文质量。
- 保留 RAG 主证据地位，可以避免章节片段抽取遗漏时完全失去书内依据。

#### 4. 当前方案适合什么场景？
- 章节正文很长，但用户通常只问局部问题。
- 需要控制 prompt 长度，同时保留当前阅读页的局部上下文。
- 记忆系统已经有长问答噪音，希望先改善新写入记忆。
- 不希望引入新的摘要模型调用或数据库迁移。

#### 5. 当前方案不适合什么场景？
- 用户需要严格基于完整章节做细粒度全局分析。
- 章节没有明显段落、句子或关键词，规则抽取可能不如 LLM 摘要。
- 需要对历史长记忆进行批量清洗或重写。
- 需要长期维护每章稳定摘要、主题图谱或多层阅读笔记。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：章节正文和记忆都应在进入 prompt 前压缩，不能只靠最后总 token 截断兜底。
- 最应该保留：记忆写入应保存“可复用结论”，而不是完整对话流水。
- 最应该质疑：规则裁剪对复杂章节的语义理解有限，后续可考虑章节摘要缓存或更强的 rerank。

## 2026-06-07：自动 MCP Agent 增加 Server 路由层

### 改动概括
- 在自动 MCP Agent 前新增轻量 MCP Server Router。
- 自动模式现在先根据用户问题和 server 描述选择 MCP server；如果不匹配任何 server，则直接跳过外部 MCP。
- 解决“毛泽东是谁”这类普通问题误触发 GitHub MCP 的问题。
- 显式 `externalMcpCalls` 仍保持最高优先级，不走 Router。

### 修改内容
- `application.yml`
  - 为 `mcp.client.servers[]` 增加 `description`。
  - `github` 描述限定为 GitHub 仓库、代码、commit、分支、README、文件内容等场景。
  - `self-local` 描述限定为本项目内部阅读系统能力调试。
- `ExternalMcpClientProperties`
  - `Server` 新增 `description` 字段。
- `ExternalMcpClientService`
  - 新增 `routableServers()`，只返回 enabled 且有 url/allowedTools 的 server 描述，不主动 listTools。
  - 新增 `allowedToolDescriptors(String serverName)`，只加载被选中 server 的 allowed tools。
- `ExternalMcpServerRouterService`
  - 新增模型路由服务，使用现有 `ModelClient.chat(String)`。
  - 严格 JSON 输出：`useMcp`、`serverName`、`reason`。
  - 解析失败、无 server、选择不可用 server 时安全跳过 MCP。
- `ExternalMcpAgentService`
  - `execute(...)` 首次运行时先调用 Router。
  - Router skip 时不调用 planner、不调用 listTools、不调用外部 tool。
  - Router 选中 server 后，只把该 server 的 allowed tools 交给后续动态 Agent。
  - pending confirmation 恢复时复用原 routed server，不重新路由。
- 响应
  - `externalMcpPlanRefs` 增加 `MCP_ROUTE ...` 或 `MCP_ROUTE_SKIP ...`。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=ExternalMcpServerRouterServiceTest,ExternalMcpAgentServiceTest,ExternalMcpClientPropertiesTest,AiChatServiceTest
  ```
  - 结果：32 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：80 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 在 tool planner 之前增加 server router，而不是把所有 server 的所有工具直接交给 Agent。
- Router 只选择 server，不选择具体 tool。
- 无匹配时默认跳过 MCP，不回退旧的全工具规划逻辑。
- server 描述放在 `application.yml`，通过配置引导不同 MCP 的适用范围。

#### 2. 每个决策的可选方案有哪些？
- 可以继续单阶段 planner，也可以先 server router 再 tool planner，或为每个 server 写硬编码规则。
- Router 可以用规则关键词、模型判断，或规则+模型混合。
- 无匹配时可以跳过 MCP，也可以回退旧逻辑，或返回需要用户确认。
- server 描述可以放配置、代码常量或独立策略文件。

#### 3. 为什么选择当前方案？
- 两阶段路由更模块化：server 选择和 tool 选择职责分离。
- 模型路由比关键词规则更容易适配未来新增 MCP server。
- 无匹配直接跳过能最大限度减少误调用外部工具。
- 配置描述足够轻量，新增 server 不需要修改 planner prompt 代码。

#### 4. 当前方案适合什么场景？
- 多个 MCP server 并存，每个 server 有清晰领域边界。
- 外部 MCP 只应在用户问题明确需要该领域数据时调用。
- 需要避免 GitHub、搜索、日历等工具污染普通阅读问答。
- 希望前端继续无感，后端自动完成 MCP 是否使用的判断。

#### 5. 当前方案不适合什么场景？
- 一个问题需要同时调用多个 MCP server。
- server 描述写得很模糊，导致 Router 难以判断适用范围。
- 希望普通百科问题也强制走某个外部搜索 MCP。
- 需要完全可解释、零模型调用的确定性路由。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：先选 server，再选 tool，避免所有工具暴露给所有问题。
- 最应该保留：无匹配默认跳过 MCP，保护主阅读问答链路。
- 最应该质疑：第一版只支持单 server 路由；未来如果要跨 server 协作，需要扩展为 server plan 列表和调用预算分配。

## 2026-06-07：记忆语义压缩与划词上下文输入

### 改动概括
- 将新写入的工作记忆和情景记忆从规则截断改为优先使用大模型生成语义摘要。
- 大模型摘要失败时自动回退到原规则截断，避免记忆写入影响主聊天链路。
- 当前章节内容不再自动按整章或关键词片段进入最终 prompt。
- 前端阅读区支持用户划词；后端只在收到划词文本和附近上下文时加入“当前章节划词上下文”。

### 修改内容
- `AiChatRequest`
  - 新增 `selectedText` 和 `selectedContext` 字段。
- `reader.html`
  - 在阅读正文区域捕获用户选中文本。
  - 根据选中文本从当前章节中截取前后句作为 `selectedContext`。
  - 发送 `/api/ai/chat` 和确认请求时附带 `selectedText`、`selectedContext`。
  - 切换章节时清空旧划词上下文。
- `AiChatService`
  - 不再因为 `chapterContent` 非空就放入章节片段。
  - 仅当 `selectedText` 或 `selectedContext` 非空时加入当前章节证据。
  - 来源显示从“当前阅读页面”调整为“当前阅读页面划词”。
- `ReadingContextCompactionService`
  - 保留旧 `compactChapter(...)` 方法供测试和未来回退。
  - 新增 `selectedExcerpt(...)`，把用户划词和附近上下文格式化为受控 prompt 片段。
- `AgentMemoryService`
  - 注入 `ModelClient`。
  - `rememberTurn(...)` 写入前先调用模型生成两行摘要：`问题`、`结论`。
  - 模型异常、空结果或明显失败文本时回退规则摘要。
- 测试
  - 更新 `AiChatServiceTest`：无划词时整章不进 prompt；有划词时只加入划词上下文。
  - 更新 `AgentMemoryServiceTest`：验证模型语义摘要写入和失败回退。
  - 更新 `ReadingContextCompactionServiceTest`：验证划词上下文格式。

### 验证结果
- 已执行目标测试：
  ```bash
  mvn test -Dtest=AgentMemoryServiceTest,ReadingContextCompactionServiceTest,AiChatServiceTest
  ```
  - 结果：20 tests passed。
- 已执行完整回归：
  ```bash
  mvn test
  ```
  - 结果：82 tests passed。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 记忆写入优先使用大模型语义摘要，而不是纯字符截断。
- 模型摘要失败时保留规则摘要兜底。
- 章节上下文改为用户显式划词触发，不再自动抽取章节段落。
- 前端继续传 `chapterContent` 以兼容现有接口，但后端不再默认使用整章内容。

#### 2. 每个决策的可选方案有哪些？
- 记忆压缩可以规则截断、模型摘要、摘要+原文双写，或异步后台摘要。
- 模型摘要可以同步写入、异步队列写入，或只在重要对话时写入。
- 章节上下文可以整章截断、关键词抽取、RAG 检索、用户划词，或前端传当前可见窗口。
- 划词上下文可以取上下句、当前段落、固定字符窗口，或由模型二次压缩。

#### 3. 为什么选择当前方案？
- 语义摘要比字符截断更接近用户真正想保留的记忆。
- 同步摘要便于第一版快速验证效果，不需要新增队列或状态表。
- 失败兜底可以控制风险，不让模型摘要失败导致聊天失败。
- 用户划词是强意图信号，比后端猜测章节相关片段更干净。

#### 4. 当前方案适合什么场景？
- 用户希望记忆保留“语义结论”，而不是机械保留开头若干字符。
- 阅读问题经常围绕用户正在关注的一句话或一小段。
- 希望减少章节正文对 MCP、RAG、Memory 的干扰。
- 第一版需要快速试验模型摘要质量。

#### 5. 当前方案不适合什么场景？
- 对响应延迟非常敏感，不希望记忆写入多一次模型调用。
- 用户不习惯划词，但仍希望系统自动理解整章上下文。
- 需要完整章节级总结、跨段落推理或全局结构分析。
- 需要强一致、可审计、零模型不确定性的记忆压缩。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：用用户划词作为章节上下文入口，它是低成本、高信号的上下文选择方式。
- 最应该保留：模型摘要失败必须有兜底，记忆系统不能拖垮主流程。
- 最应该质疑：同步模型摘要会增加延迟；如果体验变慢，下一步应改为异步摘要或可配置开关。

## 2026-06-07：前端划词上下文状态可视化

### 改动概括
- 修复用户划词后点击聊天框时看不到已选内容的问题。
- 原生浏览器选区失焦后仍会消失，但现在 AI 输入框上方会显示已捕获的划词内容。
- 增加“清除”按钮，允许用户主动移除本次划词上下文。

### 修改内容
- `reader.html`
  - 新增 `aiSelectionChip` 展示当前已捕获划词。
  - `captureReadingSelection()` 捕获成功后刷新显示状态。
  - 切换章节时清空划词状态。
  - 新增 `clearSelectedReadingContext()`。
  - 增加 `touchend` 捕获，兼容移动端划词。

### 验证结果
- 本改动为前端状态显示和事件绑定，后续已纳入完整回归。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 不试图保持浏览器原生蓝色选区，而是把划词保存为应用状态并显示在 AI 面板中。
- 给用户提供显式清除入口。
- 划词状态在切换章节时清空。

#### 2. 每个决策的可选方案有哪些？
- 可以保留原生选区、给正文加高亮标记、显示输入区提示条，或做独立摘录面板。
- 可以自动清除划词，也可以让划词持续用于多次追问。
- 可以只支持鼠标选择，也可以同时支持触屏选择。

#### 3. 为什么选择当前方案？
- 原生选区失焦消失是浏览器行为，强行维持不稳定。
- 输入区提示条实现轻量，能明确告诉用户当前问题会携带哪段上下文。
- 清除按钮让用户能控制下一次提问是否继续使用该划词。

#### 4. 当前方案适合什么场景？
- 用户围绕同一段划词连续追问。
- 用户需要确认系统已经捕获划词。
- 前端希望保持简单，不引入复杂正文标注系统。

#### 5. 当前方案不适合什么场景？
- 需要在正文中长期保存多个高亮摘录。
- 需要对多个划词片段做组合提问。
- 需要笔记级别的划线、批注、同步和持久化。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：把划词变成可见状态，而不是依赖失焦后会消失的浏览器选区。
- 最应该保留：清除入口，避免旧划词悄悄影响后续问题。
- 最应该质疑：当前只保存一个划词片段；如果以后做笔记系统，应升级为多摘录选择。

## 2026-06-08：修复划词上下句窗口缺少上句

### 改动概括
- 修复前端划词上下文只稳定包含当前句和下句、缺少上句的问题。
- 划词上下文现在按“上一句 + 当前划词所在句 + 下一句”生成。

### 修改内容
- `reader.html`
  - 调整 `buildSelectedReadingContext(...)` 的窗口起点计算。
  - 新增 `sentenceStartBefore(...)`，避免从标点后位置反复找到同一个句首。
  - `findSentenceWindowStart(...)` 明确按上一句数量回退。

### 验证结果
- 本改动为前端句子窗口算法修正，后续已纳入完整回归。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 明确把划词上下文定义为上一句、当前句、下一句。
- 修复算法边界，而不是扩大为固定字符窗口。

#### 2. 每个决策的可选方案有哪些？
- 可以取固定字符窗口、当前段落、上一句/下一句，或更多句子。
- 可以在前端计算上下文，也可以把整章和划词发给后端计算。

#### 3. 为什么选择当前方案？
- 用户明确要求上句和下句都进入提示词。
- 句子窗口比固定字符窗口更可读，也更符合阅读问答证据形态。
- 前端已经持有章节全文，继续在前端计算改动最小。

#### 4. 当前方案适合什么场景？
- 用户针对某个句子或短语提问，需要附近语义补足。
- 章节正文较长，不希望整章进入 prompt。

#### 5. 当前方案不适合什么场景？
- 需要跨段落或跨多个自然段分析。
- 原文标点混乱，无法可靠识别句子边界。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：划词上下文必须包含用户划词前后的自然句。
- 最应该质疑：纯标点分句对特殊文本不够强，后续可升级为更稳健的段落/句子解析器。

## 2026-06-08：优化 AI 消息 Markdown 显示

### 改动概括
- 修复模型回答中的 `**粗体**` 原样显示问题。
- 前端 AI 消息现在会把常见 Markdown 渲染为更易读的样式。

### 修改内容
- `reader.html`
  - 新增 `renderAiContent(...)`，仅对 assistant 消息做轻量 Markdown 渲染。
  - 支持粗体、行内代码、数字列表、短横线列表和段落换行。
  - 保持先 HTML 转义再转换有限 Markdown，避免直接渲染模型原始 HTML。
  - 调整 AI 气泡样式，让段落和列表间距更自然。

### 验证结果
- 本改动为前端显示层优化，后续已纳入完整回归。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 使用轻量自定义 Markdown 渲染，而不是引入完整 Markdown 库。
- 只渲染 assistant 消息，用户消息和错误消息仍按纯文本显示。
- 先转义 HTML，再转换有限 Markdown 标记。

#### 2. 每个决策的可选方案有哪些？
- 可以继续纯文本显示、引入 markdown-it/marked，或写轻量转换器。
- 可以渲染所有消息，也可以只渲染模型回答。
- 可以允许 HTML，也可以完全禁止 HTML。

#### 3. 为什么选择当前方案？
- 当前需求主要是处理 `**...**`、列表和段落，不需要完整 Markdown 能力。
- 不新增依赖，符合轻量化原则。
- 先转义再渲染有限标记，风险比直接 `innerHTML` 渲染模型输出低。

#### 4. 当前方案适合什么场景？
- 模型回答包含粗体、编号列表、项目符号列表。
- 需要让聊天框显示更像正文，而不是 Markdown 源码。
- 前端希望保持单文件、少依赖。

#### 5. 当前方案不适合什么场景？
- 需要完整 Markdown 表格、代码块、引用、链接渲染。
- 需要数学公式、高亮代码或复杂富文本。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：先转义 HTML，再做有限 Markdown 转换。
- 最应该保留：只对模型回答渲染 Markdown，避免用户输入显示歧义。
- 最应该质疑：自定义 Markdown 渲染能力有限；如果文档化回答越来越复杂，应换成熟库。

## 2026-06-08：MCP Router 模型超时降级与日志降噪

### 改动概括
- 修复模型接口偶发超时时 `printStackTrace()` 刷屏的问题。
- MCP Server Router 调模型失败时现在会安全跳过外部 MCP，不影响本地阅读问答主链路。

### 修改内容
- `ModelClient`
  - 将 `System.out.println` 和 `e.printStackTrace()` 改为 SLF4J 日志。
  - HTTP 失败和 IO 异常记录为 `WARN`。
  - 模型原始返回改为 `DEBUG`，并做长度截断。
- `ExternalMcpServerRouterService`
  - 捕获 `modelClient.chat(...)` 抛出的异常。
  - 识别 `模型接口请求失败`、`调用模型接口异常`、`模型返回格式不符合预期` 等失败文本。
  - Router 模型失败时返回 `MCP_ROUTE_SKIP`，跳过外部 MCP。
- 测试
  - 增加 Router 模型失败文本和异常时跳过 MCP 的单元测试。

### 验证结果
- 后续已纳入目标测试和完整回归。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 模型网络异常不再打印完整堆栈到控制台。
- MCP Router 失败时默认跳过 MCP，而不是阻断主问答。
- 保留 `WARN` 日志用于排查，但不把偶发网络抖动当成系统崩溃。

#### 2. 每个决策的可选方案有哪些？
- 可以让异常继续抛出、在 Router 层捕获、在更外层 AiChatService 捕获，或给 ModelClient 增加结构化返回类型。
- Router 失败后可以跳过 MCP、重试模型调用，或回退到关键词规则。
- 日志可以保留完整堆栈、只打印摘要，或按配置决定。

#### 3. 为什么选择当前方案？
- Router 是辅助链路，失败时不应影响阅读问答主链路。
- 跳过 MCP 比错误调用外部工具更安全。
- 日志摘要足够定位超时/HTTP 失败，同时避免终端被堆栈淹没。

#### 4. 当前方案适合什么场景？
- 外部模型 API 偶发超时或网络抖动。
- 用户问题主要可由本地 RAG、Memory、阅读上下文回答。
- 希望 MCP 自动能力可降级，而不是强依赖。

#### 5. 当前方案不适合什么场景？
- 用户问题必须依赖外部 MCP 才能回答，且跳过 MCP 会导致答案不足。
- 需要对每次模型失败进行完整堆栈审计。
- 需要自动重试、熔断或多模型 fallback 的高可用场景。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：Router 失败默认跳过 MCP，保护主流程。
- 最应该保留：模型调用日志不要直接 `printStackTrace()`。
- 最应该质疑：`ModelClient.chat(String)` 用字符串表达失败不够结构化，后续可改成 `ModelResult`。

## 2026-06-08：统一前端相关记忆与实际 Prompt 记忆

### 改动概括
- 修复前端“相关记忆”展示了 working memory，但实际 prompt 未包含该 working memory 的不一致问题。
- 前端 `memoryRefs` 现在只返回实际被选入 prompt 的记忆。

### 修改内容
- `AiChatService`
  - 新增 `selectedMemories(...)`。
  - `formatMemories(...)` 和 `memoryRefs(...)` 共用同一套记忆选择逻辑。
  - 继续保持记忆优先级：`episodic` > `semantic` > `working`，最多 4 条。
- `AiChatServiceTest`
  - 增加测试：超过 4 条记忆时，未进入 prompt 的 working memory 不会出现在响应 `memoryRefs` 中。

### 验证结果
- 后续已纳入目标测试和完整回归。

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 前端只展示实际进入 prompt 的记忆，而不是展示全部检索结果。
- 复用同一套 `selectedMemories(...)`，避免两处排序和裁剪逻辑漂移。

#### 2. 每个决策的可选方案有哪些？
- 可以让前端展示全部检索记忆，也可以只展示 prompt 使用记忆，或分成“已使用/未使用”两组。
- 可以把 working memory 强行加入 prompt，也可以继续按优先级裁剪。

#### 3. 为什么选择当前方案？
- 用户看到的“相关记忆”应与模型实际看到的记忆一致，否则会误判模型依据。
- 当前目标是减少 working memory 噪音，所以不强行把 working memory 塞回 prompt。
- 复用选择函数能减少后续维护成本。

#### 4. 当前方案适合什么场景？
- 需要让前端显示的来源和模型实际证据保持一致。
- 记忆检索结果较多，需要控制 prompt 预算。

#### 5. 当前方案不适合什么场景？
- 希望前端展示“所有召回记忆”，用于调试检索质量。
- 希望用户知道哪些记忆被召回但未被模型使用。

#### 6. 如果让我自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：prompt 记忆和响应记忆必须来自同一选择结果。
- 最应该质疑：前端是否需要一个调试模式，额外展示“召回但未使用”的记忆。

## 2026-06-08：阅读问答案例/故事型追问意图改造

### 改动概括
- 修复用户追问“举一个实际的例子”“把某个企业故事完整说出来”时，系统仍按普通概念解释模板回答的问题。
- Planner 现在识别阅读问答的子意图，并把案例/故事型问题转换为更强的 standalone question 和 RAG 查询。
- FinalAnswerService 增加案例/故事型答案要求与质量检查，不合格时自动重写一次。

### 新增内容
- 新增 `src/main/java/com/example/httpreading/service/ai/SubIntent.java`
  - 支持 `CONCRETE_EXAMPLE`、`HISTORICAL_CASE`、`STORYTELLING_CASE`、`AVOID_REPEAT_EXPLANATION`、`DETAIL_REQUIRED` 等子意图。
- 新增 `src/main/java/com/example/httpreading/service/ai/AnswerRequirement.java`
  - 描述是否要求具体例子、具体实体、故事叙述、详细过程、避免概念开头和避免重复上一轮解释。
- 新增 `src/main/java/com/example/httpreading/service/ai/DetailLevel.java`
  - 标记答案细节密度要求。

### 修改内容
- `ChatPlan`
  - 新增 `subIntent` 和 `answerRequirement`。
  - 保留旧构造器，避免已有测试和调用点大面积改动。
- `PlannerService`
  - 识别“举个例子/真实案例/具体是谁/用案例说明”等为 `CONCRETE_EXAMPLE`。
  - 识别“完整说出来/讲一个完整故事/讲清楚发展过程/不要只概括”等为 `STORYTELLING_CASE`。
  - 当“举例”和“完整说出来”同时出现时，优先使用 `STORYTELLING_CASE`。
  - 对案例/故事型问题改写 standalone question，避免只拿“举一个实际的例子”这类弱查询去检索。
  - 对案例/故事型问题追加多条 RAG 查询，围绕案例、人物、企业、事件、过程、政策背景检索。
- `FinalAnswerService`
  - 当 `requiresConcreteExample=true` 时，要求直接给出具体例子、人物/群体、处境、处理方式和如何对应原文观点。
  - 当 `requiresStorytelling=true` 时，要求直接进入案例故事，讲起点、发展、转折、结果，再回扣原文。
  - 增加质量检查：如果案例/故事回答仍停留在概念解释、缺少具体实体或缺少过程，会重新生成一次。
  - 如果证据不足，要求回答“当前资料只支持概念解释，暂时不能给出有出处的具体案例”，避免编造。
- 测试
  - `PlannerServiceTest` 增加具体例子和完整故事子意图识别测试。
  - `FinalAnswerServiceTest` 增加故事型答案不合格后重写的测试。

### 验证结果
- 已执行完整回归：
  ```bash
  mvn test
  ```
- 结果：
  ```text
  Tests run: 87, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 在 `ChatPlan` 内增加 `subIntent` 和 `answerRequirement`，而不是只靠 `taskType=READING_QA`。
- 第一版使用规则识别案例/故事子意图，而不是再引入一次模型规划调用。
- 对案例/故事型问题进行多查询 RAG 检索，而不是只检索用户原句。
- 在 `FinalAnswerService` 内做一次轻量质量检查，不合格时重写一次。

#### 2. 每个决策的可选方案有哪些？
- 子意图可以放在 `PlannerTaskType` 中扩展，也可以单独建 `SubIntent`。
- 意图识别可以使用规则、模型 JSON planner，或规则加模型混合。
- RAG 查询可以只改写 standalone question，也可以生成多条查询。
- 答案质量检查可以完全依赖 prompt，也可以代码启发式检查，或再调用模型评审。

#### 3. 为什么选择当前方案？
- `taskType` 描述大类，`subIntent` 描述回答形态，二者分开更清楚。
- 规则识别对“举例子/完整说出来”这类表达稳定、可测试、成本低。
- 多查询 RAG 能提升案例、人物、企业、过程类材料的召回概率。
- 轻量质量检查能拦住最常见失败：用户要案例，模型仍复述概念。

#### 4. 当前方案适合什么场景？
- 阅读问答中的追问、举例、历史案例、企业故事和详细过程说明。
- 用户明确要求“不要只概括”“完整说出来”的问题。
- 需要在不增加额外 planner 模型调用的前提下提升稳定性。

#### 5. 当前方案不适合什么场景？
- 用户要求非常开放的跨书、跨知识库案例，需要更强检索和外部资料。
- RAG 本身没有收录具体案例，却希望模型凭常识补充的场景。
- 需要严格事实核验和引用出处的历史考证场景。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：`subIntent + answerRequirement`，它把“回答形态”从普通阅读问答中拆了出来。
- 最应该保留：案例/故事型问题的多查询 RAG 检索。
- 最应该质疑：当前质量检查是启发式规则，后续可能需要升级为结构化 evaluator，避免误判或漏判。

## 2026-06-08：阅读辅助型问题允许上下文锚定的模型常识补充

### 改动概括
- 修复阅读问答过度依赖 RAG/当前资料，导致“资料没有逐项列出就拒答”的问题。
- Planner 新增回答模式与证据严格度：区分“只看原文”“阅读辅助 + 常识补充”“需要外部搜索/明确出处”。
- FinalAnswerService 在理解辅助型问题中允许基于当前资料补充常识例子，但要求明确区分原文依据和补充说明。

### 新增内容
- 新增 `src/main/java/com/example/httpreading/service/ai/AnswerMode.java`
  - 支持 `TEXT_ONLY`、`CONTEXT_ANCHORED_MODEL_KNOWLEDGE`、`EXTERNAL_SEARCH_REQUIRED`。
- 新增 `src/main/java/com/example/httpreading/service/ai/EvidenceStrictness.java`
  - 支持 `STRICT`、`MEDIUM`、`LOOSE`，用于控制最终回答对证据的保守程度。

### 修改内容
- `AnswerRequirement`
  - 新增 `allowModelKnowledge`、`mustDistinguishTextEvidenceAndSupplement`、`avoidRepeatingSourcePhrases`。
  - 案例、故事和理解辅助型问题默认允许上下文锚定的模型常识补充。
- `ChatPlan`
  - 新增 `answerMode` 和 `evidenceStrictness`。
  - 保留旧构造器，避免破坏已有调用点。
- `PlannerService`
  - “举个例子/有哪些/具体有哪些/比如/现实中/生活中/怎么理解/说具体点”默认规划为 `CONTEXT_ANCHORED_MODEL_KNOWLEDGE`。
  - “只根据原文/书里怎么说/不要补充/必须有出处”规划为 `TEXT_ONLY`。
  - “最新/明确出处/具体史实/具体细节”等规划为 `EXTERNAL_SEARCH_REQUIRED`。
  - 对“举个例子有哪些税”优先改写为农村税费负担的具体税种、费用、劳务和摊派问题，避免落入通用历史案例查询。
- `FinalAnswerService`
  - prompt 增加 answerMode/evidenceStrictness 说明。
  - 当 `allowModelKnowledge=true` 时，不再因为 RAG 没逐项列出具体案例就直接拒答。
  - 增加低价值复述检查：如果回答只重复“税、费、劳务、摊派”而没有具体项目，会触发重写。
  - 对税费负担类追问要求至少出现多个具体项目，例如农业税、特产税、屠宰税、教育附加费、水利建设费、乡统筹、村提留、修路修渠出工、临时集资等。
- 测试
  - `PlannerServiceTest` 增加上下文锚定模型常识补充与严格原文模式测试。
  - `FinalAnswerServiceTest` 增加“资料不足拒答需重写”和“只复述关键词需重写”测试。

### 验证结果
- 已执行局部回归：
  ```bash
  mvn test -Dtest=PlannerServiceTest,FinalAnswerServiceTest
  ```
- 结果：
  ```text
  Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 在 `ChatPlan` 中直接加入 `answerMode` 和 `evidenceStrictness`，让最终回答阶段明确知道证据使用边界。
- 保留 `TEXT_ONLY` 的严格模式，避免用户明确要求只看原文时系统擅自扩展。
- 对阅读辅助型“举例/有哪些/具体点”问题开放模型常识补充，但要求显式区分原文和补充例子。
- 用轻量质量检查拦截“只复述资料关键词”的回答。

#### 2. 每个决策的可选方案有哪些？
- 可以新增完整 `EvidencePolicy` record，也可以先用 `answerMode + evidenceStrictness + AnswerRequirement` 承载策略。
- 模型常识补充可以完全放开，也可以只在理解辅助型子意图下启用。
- 质量检查可以只靠 prompt，也可以用规则检查或模型评审。

#### 3. 为什么选择当前方案？
- 当前链路已有 `ChatPlan`，把回答模式放在计划中最少侵入，且便于测试。
- 用户问题的核心不是要外部检索，而是要阅读辅助解释，所以不应把 RAG 空结果等同于不能回答。
- 规则检查足够覆盖当前高频失败：回答只重复“税、费、劳务、摊派”，没有提供理解增量。

#### 4. 当前方案适合什么场景？
- 阅读时追问“举个例子”“有哪些”“具体点”“现实中是什么样”。
- 当前资料提供了概念，但没有逐项列出具体例子的理解辅助场景。
- 用户不要求严格出处，只希望把正在读的内容看懂。

#### 5. 当前方案不适合什么场景？
- 用户明确要求“只根据原文”“必须有出处”的问题。
- 需要精确年份、政策细节、人物企业史实的事实核验问题。
- 需要最新数据或外部资料的问题。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：`TEXT_ONLY` 与 `CONTEXT_ANCHORED_MODEL_KNOWLEDGE` 的分流，它直接解决“资料复读机”的问题。
- 最应该保留：补充常识时必须区分“当前资料说了什么”和“为了理解补充什么”。
- 最应该质疑：税费项目质量检查目前是启发式词表，后续可以抽成更通用的“具体项目密度”检查。

## 2026-06-08：追问答案避免复述最近一轮助手回答

### 改动概括
- 修复用户连续追问时，系统把最近对话或 working memory 中的上一轮助手回答再次输出，导致两轮答案几乎一模一样的问题。
- FinalAnswerService 现在会检查最终回答与最近助手回答的重合度，尤其拦截税费负担这类列表型答案的重复清单。

### 新增内容
- `FinalAnswerService` 新增重复检测辅助逻辑：
  - 识别 `recent_dialogue` 和 memory 中的历史助手回答。
  - 检查长文本片段是否被原样复用。
  - 检查税费关键词清单是否高度重合。
  - 如果回答没有“前面/上一轮/这次/具体场景/严格说”等追问承接动作，则判定为低价值重复。

### 修改内容
- `FinalAnswerService`
  - prompt 增加规则：最近对话或相关记忆里已经有上一轮助手回答时，不能重新输出那段答案。
  - 追问时要求补充新增解释、换成具体场景、说明区别，或用“前面已经列过，这次重点看……”自然承接。
  - 质量检查中新增 `answerSubstantiallyRepeatsRecentAssistant(...)`。
  - 修复提示中要求：如果前面已经列过税费名目，重写时改成具体场景、分类区别、为什么这些都会变成农民负担。
- 测试
  - `FinalAnswerServiceTest` 新增：当第二轮回答重复上一轮税费清单时，必须触发重写。

### 验证结果
- 已执行局部回归：
  ```bash
  mvn test -Dtest=FinalAnswerServiceTest
  ```
- 结果：
  ```text
  Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 在 FinalAnswerService 做重复检测，而不是在 EvidenceAggregator 过滤掉最近对话。
- 最近对话和 working memory 仍保留给模型理解追问指向，但不能被当作可复述正文。
- 对税费清单采用领域词表检测，因为这是当前截图暴露出的高频失败模式。

#### 2. 每个决策的可选方案有哪些？
- 可以直接从 evidence 中移除上一轮助手回答，也可以保留 evidence 但在最终答案阶段约束使用方式。
- 重复检测可以做字符相似度、关键词重合度、模型评审，或三者组合。
- 税费清单可以写专门规则，也可以做通用实体列表相似度检测。

#### 3. 为什么选择当前方案？
- 完全移除最近对话会削弱追问理解能力；保留但禁止复述更符合阅读问答场景。
- 字符片段 + 税费词表能覆盖“几乎一样”和“换了开头但清单一样”两种重复。
- 规则实现轻量、可测试，不增加额外模型调用成本。

#### 4. 当前方案适合什么场景？
- 用户连续追问“有哪些/举例/具体点”，上一轮已经列过一组名词。
- working memory 召回了上一轮助手回答，模型容易照搬的场景。
- 税费负担这类列表型解释场景。

#### 5. 当前方案不适合什么场景？
- 用户明确要求“重复一遍”“整理成清单”“照刚才那样再列一次”。
- 需要严格比较两个长答案语义相似度的开放问题。
- 非税费领域的大型实体列表重复，后续需要抽象成通用列表相似度。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：最近对话可用于理解，但不能直接复述给用户。
- 最应该保留：质量检查失败后自动重写一次，能快速修正明显重复。
- 最应该质疑：税费词表是局部修复，后续最好升级成通用“上一轮答案相似度 + 本轮新增信息密度”检查。

## 2026-06-08：区分“税”和“暗税”的阅读追问语义

### 改动概括
- 修复用户问“有哪些税”和“暗税有哪些”时，系统把两者混为同一类税费清单的问题。
- “税”现在优先回答正式税种和税费名目；“暗税”优先回答隐性负担、变相收费、摊派、义务工、集资、价格剪刀差等“名义上不是税、效果像税”的负担。

### 新增内容
- `PlannerService` 新增暗税问题识别：
  - 识别 `暗税`、`隐性税`、`隐形税`、`变相税`。
  - 为暗税生成独立 standalone question 和 RAG 查询。
- `FinalAnswerService` 新增暗税质量检查：
  - 如果暗税回答缺少隐性负担、变相收费、摊派、义务工、集资或价格剪刀差等例子，会触发重写。
  - 如果暗税回答主要列农业税、特产税、屠宰税等正式税种，会判定为混淆“税”和“暗税”并重写。

### 修改内容
- `PlannerService`
  - 暗税问题不再走“农村税费负担中常见的具体税种”改写。
  - 暗税 RAG 查询改为围绕“隐性负担、变相收费、摊派、劳务、乡统筹、村提留、义务工、集资”等检索。
- `FinalAnswerService`
  - prompt 明确要求：暗税不是正式税种，不要把农业税、特产税、屠宰税直接当作暗税回答。
  - 修复提示明确要求暗税回答围绕“名义上不是税、实际像税”的负担展开。
- 测试
  - `PlannerServiceTest` 新增：暗税问题不会使用正式税种改写。
  - `FinalAnswerServiceTest` 新增：把正式税种当暗税时必须重写。

### 验证结果
- 已执行局部回归：
  ```bash
  mvn test -Dtest=PlannerServiceTest,FinalAnswerServiceTest
  ```
- 结果：
  ```text
  Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 将“暗税”作为税费负担问题中的一个独立语义分支，而不是继续复用“有哪些税”的回答模板。
- 在 Planner 阶段先拆分查询意图，在 FinalAnswer 阶段再做输出质量保护。
- 用专门规则拦截“正式税种冒充暗税”的答案。

#### 2. 每个决策的可选方案有哪些？
- 可以新增一个 `SubIntent.HIDDEN_TAX_EXAMPLE`，也可以先用 `CONCRETE_EXAMPLE + isHiddenTaxQuestion` 处理。
- 可以只改 prompt，也可以同时改 Planner rewrite、RAG query 和质量检查。
- 暗税例子可以完全依赖模型常识，也可以要求 RAG 命中后再回答。

#### 3. 为什么选择当前方案？
- 当前改动范围小，能快速纠正“税”和“暗税”混淆。
- 只改 prompt 不够稳，Planner 仍可能把暗税检索成正式税种；所以需要从查询阶段拆开。
- 阅读辅助场景允许常识补充，但必须把概念边界讲清楚。

#### 4. 当前方案适合什么场景？
- 用户围绕农民负担追问“暗税有哪些”“隐性负担有哪些”“变相收费是什么”。
- 当前文本只讲“税、费、劳务、摊派”，用户需要进一步区分正式税和隐性负担。

#### 5. 当前方案不适合什么场景？
- 用户要求精确史料出处、具体年份或地方政策细节。
- “暗税”在某本书中有特殊定义，需要严格按书中术语体系解释的场景。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：税和暗税必须分流，不能用同一个清单回答。
- 最应该保留：FinalAnswer 需要显式说明“暗税不是正式税种”。
- 最应该质疑：当前仍用关键词识别暗税，后续可以把这种概念区分抽象成通用术语辨析能力。

## 2026-06-08：从“暗税特例”改为通用焦点术语边界

### 改动概括
- 撤掉上一版针对“暗税”的专用提示词和专用质量检查，避免以后每遇到一个例子就新增一条固定指示。
- 改为通用识别：当用户问的是一个复合焦点术语，例如“暗税有哪些”，系统提取 `focusTerm=暗税`、`broaderTerm=税`，先处理二者边界，再回答焦点术语本身。

### 新增内容
- `PlannerService` 新增通用 `ConceptBoundary`：
  - 从“有哪些/是什么/什么意思/怎么理解”等问题中提取焦点术语。
  - 根据后缀母概念识别边界，例如 `X税 -> 税`、`X成本 -> 成本`、`X权力 -> 权力`。
  - 生成通用 standalone question：先界定焦点术语和母概念的区别，再给焦点术语的表现或例子。
- `FinalAnswerService` 新增通用概念边界检查：
  - 如果答案没有说明焦点术语和母概念的区别，会触发重写。
  - 修复提示要求不要退回母概念的一般清单。

### 修改内容
- `PlannerService`
  - 删除 `isHiddenTaxQuestion` 特例分支。
  - RAG 查询改为优先加入：
    - `焦点术语 具体例子 表现`
    - `焦点术语 与 母概念 区别`
  - 边界查询前置，避免被查询数量上限截断。
- `FinalAnswerService`
  - 删除暗税专用 prompt。
  - 删除暗税专用例子检查和“正式税种冒充暗税”的定制规则。
  - 增加通用要求：更具体的焦点术语不能因为关键词重叠而回答成母概念的一般例子。
- 测试
  - `PlannerServiceTest` 改为验证复合术语边界改写，而不是验证暗税特例。
  - `FinalAnswerServiceTest` 改为验证焦点术语退化成母概念时触发重写。

### 验证结果
- 已执行局部回归：
  ```bash
  mvn test -Dtest=PlannerServiceTest,FinalAnswerServiceTest
  ```
- 结果：
  ```text
  Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 把“暗税”问题抽象为“复合焦点术语 vs 母概念”的通用问题。
- 在 Planner 阶段提取术语边界，在 FinalAnswer 阶段检查答案是否尊重这个边界。
- 保留少量母概念后缀表，而不是为每个具体术语写提示词。

#### 2. 每个决策的可选方案有哪些？
- 可以继续维护暗税、隐性成本、政治权力等专用规则，也可以抽象为通用术语边界。
- 焦点术语可以由规则提取，也可以由模型规划输出结构化字段。
- 质量检查可以只看 prompt，也可以在代码中检查焦点术语、母概念和边界表达。

#### 3. 为什么选择当前方案？
- 用户指出的问题本质是概念边界缺失，不是少了某条暗税提示。
- 通用焦点术语机制能覆盖更多类似问题，避免提示词无穷膨胀。
- 规则提取成本低、可测试，也符合当前 PlannerService 的规则规划风格。

#### 4. 当前方案适合什么场景？
- “暗税有哪些”“隐性成本是什么”“政治权力怎么理解”这类复合术语问题。
- 用户追问一个更窄概念，但该概念包含一个更宽泛关键词的场景。

#### 5. 当前方案不适合什么场景？
- 焦点术语不在问题开头，或句式复杂到规则无法稳定提取。
- 母概念不是后缀关系的术语，例如需要语义解析才能看出上下位关系。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：从具体例子抽象到“焦点术语边界”的方向。
- 最应该保留：Planner 负责识别边界，FinalAnswer 负责遵守边界。
- 最应该质疑：母概念后缀表仍是启发式，后续可以让 Planner 输出结构化 `focusTerm` 和 `broaderTerm`。

## 2026-06-08：识别“既然有问题为什么还要做”的让步型为什么

### 改动概括
- 修复用户问“既然 A 会带来困难，为什么还要执行 B”时，系统被 A 的后果带跑、没有回答 B 的执行理由的问题。
- 新增通用 `CONTRASTIVE_WHY` 子意图，专门处理“既然/虽然 A，但为什么仍然 B”的权衡型阅读追问。

### 新增内容
- `SubIntent`
  - 新增 `CONTRASTIVE_WHY`。
- `PlannerService`
  - 新增让步型为什么识别：
    - `既然 ... 为什么还要 ...`
    - `既然 ... 为什么仍然/依然/还是 ...`
  - 新增 `ContrastiveParts`，从问题中提取：
    - `premise`：用户承认的负面后果或矛盾。
    - `action`：用户真正追问“为什么仍然要做”的政策或行动。
  - standalone question 改写为：在承认负面后果的前提下，解释为什么仍然选择该行动，重点讲更大问题、优先目标、权衡取舍和代价。
- `FinalAnswerService`
  - 增加 `CONTRASTIVE_WHY` prompt 规则。
  - 增加质量检查：答案必须解释为什么仍然选择该政策或行动，必须承认代价，并且必须出现优先目标/权衡取舍层面的解释。

### 修改内容
- `PlannerService`
  - `CONTRASTIVE_WHY` 使用 `CONTEXT_ANCHORED_MODEL_KNOWLEDGE` 和 `MEDIUM` 证据严格度。
  - RAG 查询增加：
    - `行动 背景 原因 目标`
    - `行动 政策取舍 影响`
    - `前提 + 行动 + 为什么仍然执行`
- `FinalAnswerService`
  - 如果答案主要复述困难、负担、后果，而没有解释政策目标和取舍，会触发重写。
  - 修复提示要求：不要继续复述 A 的后果，要回答为什么仍然做 B。
- 测试
  - `PlannerServiceTest` 新增：分税制让步型为什么问题会改写为权衡型 standalone question。
  - `FinalAnswerServiceTest` 新增：只复述后果、不讲权衡的回答必须重写。

### 验证结果
- 已执行局部回归：
  ```bash
  mvn test -Dtest=PlannerServiceTest,FinalAnswerServiceTest
  ```
- 结果：
  ```text
  Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 把“问不达意”抽象为让步型为什么识别，而不是给“分税制”写专用提示。
- Planner 负责提取 `premise/action`，FinalAnswer 负责保证答案聚焦 action 的执行理由。
- 质量检查要求答案同时包含“承认代价”和“解释权衡”，避免只讲好处或只讲坏处。

#### 2. 每个决策的可选方案有哪些？
- 可以只加一条分税制 prompt，也可以新增通用 `CONTRASTIVE_WHY`。
- `premise/action` 可以用规则提取，也可以交给模型 planner 输出结构化字段。
- 质量检查可以仅靠 prompt，也可以用启发式规则检查是否出现权衡和代价。

#### 3. 为什么选择当前方案？
- 用户的问题不是分税制知识点本身，而是系统没识别“既然 A，为什么仍然 B”的问法结构。
- 规则识别该句式稳定、成本低，能覆盖更多类似阅读追问。
- 最终答案阶段加检查，可以防止模型又被证据中的负面后果带跑。

#### 4. 当前方案适合什么场景？
- “既然这个政策有副作用，为什么还要执行？”
- “既然会造成困难，为什么当时仍然这么做？”
- “既然有代价，为什么还要推进？”这类需要解释政策取舍的问题。

#### 5. 当前方案不适合什么场景？
- 用户只是单纯问某政策的后果，而不是问“为什么仍然执行”。
- 问题句式复杂到规则无法准确抽取 premise/action，需要模型规划补足。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：把让步型为什么作为独立子意图。
- 最应该保留：答案必须讲“更大问题、优先目标、权衡取舍、代价”。
- 最应该质疑：`premise/action` 目前是字符串规则抽取，后续可以升级为 Planner 输出结构化字段。

## 2026-06-08：输出最终发送给大模型的 Prompt 日志

### 改动概括
- 在 `FinalAnswerService` 中增加最终模型 Prompt 日志，方便排查回答不贴题、重复、质量检查重写等问题。
- 日志会输出正常最终回答阶段和质量检查失败后的重写阶段 Prompt。

### 新增内容
- `FinalAnswerService`
  - 新增 `Logger`。
  - 新增 `logPrompt(stage, prompt)` 方法。
  - Prompt 日志使用固定边界：
    - `AI_MODEL_PROMPT_BEGIN`
    - `AI_MODEL_PROMPT_END`
  - 日志中包含：
    - `stage`
    - prompt 字符数
    - 完整 prompt 内容

### 修改内容
- `FinalAnswerService.answer(...)`
  - 在调用 `modelClient.chat(prompt)` 前输出 `stage=FINAL_ANSWER` 的完整 prompt。
  - 当质量检查未通过并生成 repair prompt 时，在二次调用模型前输出 `stage=REPAIR_ANSWER` 的完整 prompt。

### 验证结果
- 已执行局部回归：
  ```bash
  mvn test -Dtest=FinalAnswerServiceTest
  ```
- 结果：
  ```text
  Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 日志放在 `FinalAnswerService`，因为这里是最终 prompt 拼装和模型调用的唯一入口。
- 正常回答和修复回答分别使用 `FINAL_ANSWER`、`REPAIR_ANSWER` 两个 stage，方便区分是哪一次模型调用。
- 使用固定 begin/end 边界，方便从长日志中复制或检索完整 prompt。

#### 2. 每个决策的可选方案有哪些？
- 可以只打印 prompt 摘要，也可以打印完整 prompt。
- 可以在 `ModelClient` 统一打印所有模型请求，也可以只在 `FinalAnswerService` 打印最终回答 prompt。
- 可以使用 `DEBUG` 级别，也可以使用 `INFO` 级别。

#### 3. 为什么选择当前方案？
- 用户当前要排查的是最终回答为什么问不达意，最关键的是最终回答阶段实际输送给模型的完整 prompt。
- 质量检查重写也会再次调用模型，如果不打印 repair prompt，很难判断二次生成是否被正确约束。
- 当前项目日志配置允许业务包输出，`INFO` 级别更容易在控制台和 `server.log` 中直接看到。

#### 4. 当前方案适合什么场景？
- 排查最终回答重复上一轮内容。
- 排查 Planner 输出、Evidence 汇总、AnswerRequirement 是否真正进入最终 prompt。
- 排查质量检查触发后，repair prompt 是否表达了正确的重写原因。

#### 5. 当前方案不适合什么场景？
- 生产环境长期高流量运行时，完整 prompt 日志会比较大，也可能包含用户阅读内容和对话内容。
- 如果后续需要更严格的隐私控制，应增加配置开关或脱敏策略。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：在模型调用前输出最终完整 prompt，而不是只输出 Planner 或 Evidence 的中间状态。
- 最应该保留：`FINAL_ANSWER` 和 `REPAIR_ANSWER` 的 stage 区分。
- 最应该质疑：默认使用 `INFO` 级别是否适合长期运行，后续可以改为配置开关控制。

## 2026-06-09：PlannerService 改为 LLM 决策型规划器

### 改动概括
- 将 `PlannerService` 从关键词规则路由改为 LLM 决策型 Planner。
- 新流程为：`PlannerPromptBuilder -> ModelClient -> LlmPlanResponse(JSON) -> PlanValidator -> ChatPlan`。
- 后端不直接信任模型输出；模型调用失败、JSON 解析失败或校验失败时统一回退到安全阅读兜底计划。

### 新增内容
- 新增 `PlannerPromptBuilder`
  - 构造 Planner prompt。
  - prompt 中包含启用工具白名单、参数说明、JSON schema 和规划规则。
  - 明确要求模型只能输出纯 JSON，不能输出 Markdown、代码块或解释文字。
- 新增 `ToolRegistry` 和 `AvailableTool`
  - 维护工具白名单。
  - 第一版只启用内部只读工具：
    - `context.get_recent_dialogue`
    - `context.get_current_page`
    - `memory.search`
    - `rag.search`
  - `note.search`、`reading_progress.query`、`learning_plan.save` 仅预留，默认不暴露、不执行。
- 新增 `LlmPlanResponse`、`LlmToolStep`、`LlmAnswerRequirement`
  - 承接模型输出 JSON。
  - `LlmAnswerRequirement` 使用 `Boolean` 字段，便于识别缺字段。
- 新增 `PlanValidator` 和 `PlanValidationException`
  - 校验枚举、白名单、执行模式、maxSteps、toolPlan 和写操作约束。

### 修改内容
- `PlannerService`
  - 注入 `ModelClient`、`ObjectMapper`、`PlannerPromptBuilder`、`PlanValidator`。
  - 删除旧主路径中的关键词路由逻辑。
  - 不再根据 `externalMcpCalls` 生成计划，字段仍保留以兼容 API。
  - 新增 Planner prompt 日志，使用 `AI_MODEL_PROMPT_BEGIN/END stage=PLANNER` 包裹。
  - 新增 `fallbackReadingPlan`：
    - 默认调用 `context.get_recent_dialogue`。
    - 有划词或划词上下文时调用 `context.get_current_page`。
    - `enableMemory=true/null` 时调用 `memory.search`。
    - `enableRag=true/null` 时调用 `rag.search`。
- `PlannerTaskType`
  - 补充指导文件要求的枚举：
    - `GENERAL_QA`
    - `NOTE_QA`
    - `READING_PLAN`
    - `TOOL_ACTION`
  - 保留旧枚举，降低对其他类的编译冲击。
- 测试
  - `PlannerServiceTest` 改为 mock `ModelClient` 输出 JSON。
  - 新增 `PlanValidatorTest`。
  - 新增 `PlannerPromptBuilderTest`。

### JSON schema
- Planner JSON 必须包含：
  - `taskType`
  - `subIntent`
  - `standaloneQuestion`
  - `dependsOnContext`
  - `executionMode`
  - `allowedTools`
  - `toolPlan`
  - `taskGoal`
  - `maxSteps`
  - `stopCondition`
  - `answerGuidance`
  - `answerMode`
  - `evidenceStrictness`
  - `answerRequirement`
  - `planningReason`
- 相比指导文件，额外保留现有回答质量控制字段：
  - `subIntent`
  - `answerMode`
  - `evidenceStrictness`
  - `answerRequirement`

### fallback 触发条件
- 模型调用异常。
- 模型输出为空。
- 模型输出不是纯 JSON。
- JSON 解析失败。
- 字段缺失。
- 枚举非法。
- 工具不在启用白名单中。
- `toolPlan.toolName` 不属于 `allowedTools`。
- `maxSteps` 小于 0 或大于 5。
- `NO_TOOL/SINGLE_TOOL/MULTI_TOOL` 与 `toolPlan` 数量不匹配。
- 模型输出外部 MCP 工具。
- 模型输出写操作或需要确认的工具。
- 模型输出 `BOUNDED_REACT`。

### 验证结果
- 已执行局部回归：
  ```bash
  mvn test -Dtest=PlannerServiceTest,PlanValidatorTest,PlannerPromptBuilderTest
  ```
- 结果：
  ```text
  Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 让 LLM Planner 负责语义判断和工具计划生成，后端只做结构、安全和兼容性校验。
- ToolRegistry 第一版只暴露当前已经可执行的内部只读工具。
- 保留并扩展输出 `subIntent/answerMode/evidenceStrictness/answerRequirement`，避免 FinalAnswerService 的答案质量控制退化。
- fallbackReadingPlan 保守偏阅读问答，保证模型规划失败时仍能继续回答。

#### 2. 每个决策的可选方案有哪些？
- 可以完全按指导文件最小 schema 输出，也可以扩展当前项目已有回答质量字段。
- 可以让 ToolRegistry 暴露未来工具，也可以只暴露已经可执行工具。
- 可以在 PlannerService 内直接写 prompt，也可以拆出 PlannerPromptBuilder。
- 可以允许 BOUNDED_REACT，也可以第一版全部拒绝。

#### 3. 为什么选择当前方案？
- 用户明确希望模型负责语义判断，但不希望模型绕过后端安全边界。
- 当前系统已经依赖 `subIntent` 等字段控制举例、故事、让步型为什么和避免重复，去掉会造成回答质量回退。
- 只暴露已可执行工具可以避免模型规划出后端无法执行的工具。
- 第一版拒绝 BOUNDED_REACT，能保持计划执行稳定，后续再单独开放探索型任务。

#### 4. 当前方案适合什么场景？
- 阅读问答、记忆辅助、RAG 检索、当前页面/划词上下文补充。
- 需要让模型根据自然语言判断是否需要工具的场景。
- 需要后端强校验工具白名单和执行模式的场景。

#### 5. 当前方案不适合什么场景？
- 当前还不适合 GitHub、文件系统、代码仓库探索类任务，因为外部 MCP 默认不开放。
- 当前还不适合保存计划或写操作工具，因为写操作需要单独确认流程。
- 当前还不适合依赖未实现的 note/search 或 reading progress 工具。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：LLM 做语义规划、后端做白名单校验和 fallback。
- 最应该保留：ToolRegistry 只向 prompt 暴露真正可执行的工具。
- 最应该质疑：Planner prompt 目前较长，后续可以压缩 schema 或增加更强的 few-shot 示例。

## 2026-06-09：修正 LLM Planner 外部工具不可用时的工具选择

### 改动概括
- 修复用户请求 GitHub/外部搜索时，Planner 错误规划 `memory.search` 和 `rag.search` 凑数的问题。
- 当当前 ToolRegistry 没有 GitHub/外部搜索工具时，Planner 会返回 `NO_TOOL + EXTERNAL_SEARCH_REQUIRED`，最终回答必须说明没有实际执行外部搜索。

### 新增内容
- 新增 `PlannerIntentClassifier`
  - 识别 GitHub、网页、外部搜索、实时信息、最新信息等外部工具需求。
  - 识别阅读相关问题，用于限制 `rag.search` 的适用范围。
- `PlannerService`
  - 新增 `unsupportedExternalToolPlan`。
  - 当模型计划失败且问题需要外部工具时，不再进入 `fallbackReadingPlan`，而是进入 unsupported plan。
- `PlanValidator`
  - 新增意图一致性校验：
    - 外部/实时请求不能用 `rag.search`、`memory.search`、`context.get_current_page` 替代。
    - 需要实时外部事实时不能使用 `TEXT_ONLY`。
    - 非阅读问题不能规划 `rag.search`。
    - `dependsOnContext=false` 时不能规划 context 工具。
- `FinalAnswerService`
  - 新增外部搜索回答质量检查：
    - `answerMode=EXTERNAL_SEARCH_REQUIRED` 且没有 `externalMcpRefs` 时，必须说明没有实际执行外部搜索。
    - 只有 memoryRefs 时，不能把历史记忆说成本次 GitHub 或实时搜索结果。

### 修改内容
- `PlannerPromptBuilder`
  - 增加工具适用边界：
    - `rag.search` 仅用于书籍/章节/划词/作者观点/原文证据相关问题。
    - `memory.search` 仅用于用户明确要求结合历史记忆、历史偏好、之前问过的内容，或问题明显需要历史上下文。
    - `context.get_current_page` 仅用于当前页面、划词、“这里/这句话/上文”等阅读上下文问题。
    - GitHub/网页/外部搜索/实时信息没有对应工具时，不能改用 RAG 或 Memory 凑数。
- `ChatPlan`
  - `maxSteps` 允许为 0，以正确表达 `NO_TOOL` 计划。
- 测试
  - `PlannerServiceTest` 增加：“使用github搜索httpreading的项目”在没有 GitHub 工具时返回 `NO_TOOL + EXTERNAL_SEARCH_REQUIRED`。
  - `PlanValidatorTest` 增加外部请求不能用 RAG/Memory 替代、实时外部事实不能 `TEXT_ONLY`、`dependsOnContext=false` 不允许 context 工具等测试。
  - `PlannerPromptBuilderTest` 增加工具边界规则断言。
  - `FinalAnswerServiceTest` 增加没有外部证据时不能伪称 GitHub 搜索结果的重写测试。

### 验证结果
- 已执行局部回归：
  ```bash
  mvn test -Dtest=PlannerServiceTest,PlanValidatorTest,PlannerPromptBuilderTest,FinalAnswerServiceTest
  ```
- 结果：
  ```text
  Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 把 GitHub/外部搜索/实时信息识别抽成 `PlannerIntentClassifier`，避免在多个类里散落关键词判断。
- 外部工具不可用时使用 `unsupportedExternalToolPlan`，而不是阅读兜底计划。
- Validator 不只校验字段合法，还校验“工具和用户意图是否一致”。
- FinalAnswerService 再加一层回答侧保护，防止把记忆或模型常识伪装成实时搜索。

#### 2. 每个决策的可选方案有哪些？
- 可以只在 prompt 中提醒模型，也可以在 Validator 中硬性拦截。
- 可以让外部工具问题也进入 fallbackReadingPlan，也可以单独 unsupported plan。
- 可以直接开放 GitHub 工具，也可以在 ToolRegistry 没有真实适配器前明确拒绝实时搜索。

#### 3. 为什么选择当前方案？
- 只靠 prompt 不够稳定，模型仍可能把 GitHub 搜索错规划成 RAG/Memory。
- fallbackReadingPlan 是阅读问答兜底，不适合外部实时搜索请求。
- 当前 ToolRegistry 没有 GitHub 工具，诚实说明不可用比伪造搜索结果更安全。

#### 4. 当前方案适合什么场景？
- 用户请求 GitHub 搜索、网页搜索、联网搜索、最新信息，而系统当前没有对应工具。
- 模型错误规划阅读工具替代外部工具，需要后端拦截。
- 最终回答需要区分“历史记忆”和“本次实时搜索结果”。

#### 5. 当前方案不适合什么场景？
- 后续真正接入 GitHub MCP 工具后，需要把对应工具加入 ToolRegistry，并调整 Validator 允许 GitHub 工具。
- 用户只是问一般代码概念，不要求 GitHub 或实时搜索时，不应触发 unsupported plan。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：外部工具不可用时必须 NO_TOOL，不允许 RAG/Memory 冒充。
- 最应该保留：Validator 的意图一致性校验。
- 最应该质疑：外部工具需求识别目前仍是启发式，后续可以随 ToolRegistry 能力扩展成更结构化的工具需求分类。

## 2026-06-09：LLM Planner 改为 MCP Server 级白名单选择

### 改动概括
- 修正 Planner 只看到本地 `context/memory/rag` 工具的问题。
- Planner prompt 现在同时展示本地工具白名单和外部 MCP server 白名单。
- 一级 Planner 对外部 MCP 不再选择具体工具，只选择 `mcp.server:<serverName>`。
- 具体工具调用交给该 server 对应的 ReAct agent，agent 只拿到该 server 允许的工具列表。

### 新增内容
- `PlannerPromptBuilder`
  - 注入 `ExternalMcpClientService`。
  - 当 `enableExternalMcp=true` 时，读取 `routableServers()` 并输出 server 名称、描述和允许工具名。
  - 新增外部 MCP server 规划示例：`executionMode=BOUNDED_REACT`、`allowedTools=["mcp.server:github"]`、`toolPlan=[]`。
- `PlanValidator`
  - 注入 `ExternalMcpClientService`。
  - 新增 `mcp.server:` token 校验。
  - `BOUNDED_REACT` 模式要求 `allowedTools` 中只能有一个已启用 MCP server，且 `toolPlan` 必须为空。
- `ExternalMcpAgentService`
  - 新增带 `routedServerName` 的 `execute(...)` 重载。
  - 当 Planner 已选择 server 时，跳过原来的 server router，直接进入该 server 的 ReAct 工具规划与调用。
- `McpToolOrchestrator`
  - 从 `ChatPlan.allowedTools` 中识别 `mcp.server:<serverName>`。
  - 如果存在 server token，则调用新的 `ExternalMcpAgentService.execute(request, planningContext, serverName)`。

### 修改内容
- `PlannerService`
  - fallback 逻辑保留阅读兜底，但外部 MCP 请求在模型规划失败且存在可用 server 时，会回退到 MCP server router。
  - 外部 MCP 不可用时仍返回 `unsupportedExternalToolPlan`，不使用 RAG/Memory 冒充外部搜索。
- `PlannerPromptBuilder`
  - 明确规则：GitHub、网页、外部搜索、实时信息先看 MCP server 白名单。
  - 如果有匹配 server，只输出 server token，不输出具体 `external.*` 工具名。
  - 如果没有匹配 server，输出 `NO_TOOL + EXTERNAL_SEARCH_REQUIRED`。
- 测试
  - `PlannerPromptBuilderTest` 覆盖 prompt 中的 MCP server 白名单展示。
  - `PlanValidatorTest` 覆盖启用 server token 可通过、无 server token 的 `BOUNDED_REACT` 会被拒绝。
  - `PlannerServiceTest` 覆盖 GitHub server 可用时输出 `BOUNDED_REACT + mcp.server:github`。
  - `McpToolOrchestratorTest` 覆盖带 server token 时委托到指定 server 的 agent。
  - `ExternalMcpAgentServiceTest` 覆盖预选 server 时跳过 router，并只读取该 server 的 allowed tools。

### 验证结果
- 已执行局部回归：
  ```bash
  mvn test -Dtest=PlannerServiceTest,PlanValidatorTest,PlannerPromptBuilderTest,McpToolOrchestratorTest,ExternalMcpAgentServiceTest
  ```
- 结果：
  ```text
  Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
  ```

### 设计决策说明

#### 1. 本次实现做了哪些设计决策？
- 一级 Planner 只负责判断“是否需要哪个 MCP server”，不直接规划外部 MCP 具体工具。
- 外部 MCP server 用 `mcp.server:<serverName>` 作为受控白名单 token。
- server 内部工具调用仍交给已有 ReAct agent，由它在该 server 的 allowed tools 范围内逐步调用。
- 本地阅读工具仍保留 `context/memory/rag` 的确定性规划。

#### 2. 每个决策的可选方案有哪些？
- 可以把所有外部 MCP 工具直接暴露给 Planner，也可以只暴露 server 级能力。
- 可以让 Planner 输出 `external.github.search_code` 这类具体工具，也可以输出 `mcp.server:github`。
- 可以完全依赖原来的 server router，也可以让 Planner 先选 server，再让 agent 执行。

#### 3. 为什么选择当前方案？
- 用户明确希望 Planner 根据 MCP server 名称和描述判断是否调用，而不是只看本地工具。
- server 级白名单能减少 Planner 的工具选择噪声，也避免把外部具体工具泄漏到一级计划。
- ReAct agent 本来就适合在单个 MCP server 的工具集合内做多步探索。

#### 4. 当前方案适合什么场景？
- GitHub、网页、仓库、外部搜索等需要先选择外部能力域，再由专属 agent 多步探索的任务。
- 多个 MCP server 并存，需要让 Planner 在 server 级别做路由的任务。
- 需要保留本地阅读链路稳定性，同时开放外部探索能力的任务。

#### 5. 当前方案不适合什么场景？
- 单个外部工具调用非常确定、无需 ReAct 的场景，后续可以增加 `MCP_SINGLE_TOOL` 类模式优化。
- MCP server 描述不清晰时，Planner 仍可能选错 server，需要配置层补充更好的描述。
- 如果某个 server 的 allowed tools 过宽，风险仍需要在 ReAct agent 的 schema、去重、确认和写操作拦截中控制。

#### 6. 如果自己重写，最应该保留哪部分，最应该质疑哪部分？
- 最应该保留：一级 Planner 只选 server，二级 agent 才看具体工具。
- 最应该保留：`mcp.server:<serverName>` token 必须经后端白名单校验。
- 最应该质疑：当 Planner 失败时是否应优先进入旧 router，还是直接 unsupported；当前实现对存在可用 server 的外部请求保留 router 兜底。
