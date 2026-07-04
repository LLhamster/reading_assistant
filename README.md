# HTTP Reading - 智能阅读平台

> 一个基于 Java Spring Boot 的阅读与问答平台，围绕“书籍管理、阅读进度、全文搜索、RAG 问答、记忆系统、MCP 工具接入”逐步演进。

---

## 项目简介

这个项目的核心目标，是把“读书”从单纯的内容展示，升级成一个可以检索、可以追问、可以记忆上下文、可以接入外部工具的智能阅读系统。

当前项目已经覆盖了以下方向：

- 用户注册、登录与身份认证
- 书籍浏览、章节查看、阅读进度记录
- 书架收藏与个人阅读状态管理
- 章节内容问答与检索增强回答
- 文档导入、切片、向量化与检索
- 会话记忆、上下文构建与提示词拼装
- MCP 工具暴露与外部 MCP 服务调用
- Redis 缓存、消息队列、日志与基础监控能力

---

## 当前能力

### 1. 账号与访问控制

- 支持用户注册、登录与 JWT 无状态认证
- 通过安全过滤链保护需要登录的接口
- 对异常登录、无权限访问等场景做统一处理

### 2. 书籍与阅读

- 支持书籍列表、详情、章节列表与章节内容查看
- 支持书架收藏，且对重复收藏做幂等处理
- 支持阅读进度记录与恢复，方便用户继续阅读
- 静态阅读页面可直接访问，用于快速查看内容

### 3. 检索与问答

- 支持对书籍和章节内容进行全文检索
- 支持将文档拆分为片段后做向量检索
- 支持基于检索结果生成答案，提高回答与原文的相关性
- 支持返回引用来源，方便用户追溯答案依据

### 4. 记忆与上下文

- 支持保存阅读问答过程中的关键记忆
- 支持按用户和会话维度检索历史记忆
- 支持把历史对话、外部输入片段和系统提示拼装成统一上下文
- 支持上下文压缩与长度控制，适配后续模型调用

### 5. MCP 接入

- 项目本身可以作为 MCP Server，对外提供标准化工具能力
- 项目也可以作为 MCP Client，按配置调用外部 MCP 服务
- 支持工具白名单、超时设置、调用路由与结果解析
- 支持在候选目标不明确时，让用户二次确认后继续执行

### 6. 数据与基础设施

- MySQL 负责核心业务数据存储
- Redis 用于缓存和部分状态管理
- Elasticsearch、RabbitMQ、Qdrant 等组件已纳入配置体系
- 支持日志分级、结构化日志输出和本地调试

---

## 模块划分

### 接口层

负责接收 HTTP 请求，向外提供用户、书籍、阅读、问答和 MCP 相关接口。

### 业务层

负责处理书籍、章节、书架、阅读进度、检索、记忆、问答等核心流程。

### 检索层

负责文档解析、切片、向量化、召回和相关内容排序。

### 上下文层

负责整理会话历史、记忆片段和用户输入，生成适合模型使用的上下文。

### MCP 层

负责工具注册、外部服务选择、工具调用控制、确认交互与调用结果封装。

### 基础设施层

负责数据库、缓存、消息队列、日志、配置和外部服务连接。

---

## 主要技术

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 3.3.4（Java 17） |
| 持久层 | Spring Data JPA + MySQL |
| 缓存 | Spring Data Redis |
| 安全 | Spring Security + JWT |
| 检索 | Elasticsearch + 向量检索 |
| 消息 | RabbitMQ |
| AI | 外部大模型 API、RAG、MCP 工具调用 |
| 构建 | Maven |
| 部署 | Docker / Docker Compose |

---

## 配置说明

建议本地开发时使用 [src/main/resources/application.yml.example](src/main/resources/application.yml.example) 作为模板，再复制为 [src/main/resources/application.yml](src/main/resources/application.yml) 并填写自己的真实配置。

配置主要包含以下内容：

- 服务器端口与文件上传限制
- MySQL、Redis、Elasticsearch、RabbitMQ 连接信息
- 缓存开关
- 本地存储目录
- MCP 客户端列表与工具白名单
- 向量数据库连接参数
- 模型 API Key 与嵌入模型配置

---

## 本地运行

### 方式一：直接启动

```bash
mvn spring-boot:run
```

### 方式二：打包后启动

```bash
mvn clean package
java -jar target/*.jar
```

### 方式三：Docker

```bash
docker compose up --build
```

启动后可访问阅读页面和后端接口，默认端口为 `8080`。

### 智能增量导入书籍

将 EPUB 文件统一命名为：

```text
书名__作者__状态.epub
乡土中国__费孝通__完结.epub
某书__张三__连载中.epub
```

然后执行：

```bash
bash scripts/import-books-batch.sh \
  --dir /home/hamster/books \
  --filename-metadata
```

脚本会自动读取书名、作者和状态。首次运行会为历史书籍建立 SHA-256
索引；之后通过目录中的 `.http-reading-import-state.tsv` 跳过未变化的文件，
相同内容即使改名也不会重复导入。

---

## AI Agent Testing

项目包含离线 mock 回归、Planner/ToolPlanner live 测试和使用 fake MCP 结果的 shadow live 测试。测试分层、安全边界与运行命令见 [docs/AI_TESTING.md](docs/AI_TESTING.md)。

---

## 项目结构

```text
java_src/
├── pom.xml
├── docker-compose.yml
├── Dockerfile
├── README.md
├── src/main/
│   ├── resources/
│   │   ├── application.yml
│   │   ├── application.yml.example
│   │   └── static/
│   │       └── reader.html
│   └── java/com/example/httpreading/
│       ├── controller/
│       ├── service/
│       ├── context/
│       ├── memory/
│       ├── mcp/
│       ├── mq/
│       ├── repository/
│       ├── security/
│       ├── config/
│       ├── domain/
│       └── dto/
```
