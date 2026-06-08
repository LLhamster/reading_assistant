# CHANGELOG — 智能阅读平台迭代记录

---

## [2026-05-05] W7：RAG 书内问答 + W5 收尾

### 新增
- 新增 `EmbeddingService.java` — 文本向量化服务，封装 DashScope embedding 调用
- 新增 `ChunkDoc.java` — ES chunk 文档类（indexName: book_chunks），字段：bookId/chapterIndex/chunkIndex/bookTitle/chapterTitle/content/vector
- 新增 `ChunkRepository.java` — ES chunk 仓库接口
- 新增 `ChunkingService.java` — 章节切分服务（500字符/块，50字符重叠，滑动窗口）
- 新增 `RagService.java` — RAG 核心服务（向量检索 + prompt 构建 + Kimi 生成）
- 新增 `AiChatResponse.java` — AI 问答响应 DTO（answer + sources 引用列表）
- 新增 `ModelClient.embeddingByDashScope()` — DashScope embedding API 调用方法
- `AdminController.java` — 新增 `POST /api/admin/chunks/rebuild/{bookId}` 触发 chunk 索引重建

### 修改
- `AiChatService.java` — 原有直接 prompt 方式改为调用 RagService（检索 + 生成）
- `AiChatController.java` — 返回类型从 `String` 改为 `AiChatResponse`（含回答 + 引用来源）
- `ModelClient.java` — 新增 DashScope embedding 配置（`model.dashscope.apiKey` / `embeddingModel`）
- `application.yml` — 新增 DashScope 配置（apiKey、embeddingModel: text-embedding-v3）
- `SecurityConfig.java` — 新增 `/api/ai/**` 白名单放行

### 环境搭建
- 申请 DashScope（阿里云百炼）API Key，支持 `text-embedding-v3`（1024 维向量）
- 手动创建 ES `book_chunks` 索引 mapping（Spring Data ES 无法自动处理 `dense_vector` 字段）

### 遇到的问题及解决
- **Spring Data ES 无法处理 `List<Float>` 向量字段**：自动创建的 `book_chunks` 索引缺少 `vector` 字段 → 手动用 curl 创建 mapping，显式声明 `dense_vector` 类型
- **Kimi API Key 无 embedding 权限**：原 key 只有 chat 权限，embedding 返回 403 → 改用 DashScope（阿里云百炼）embedding 服务
- **ES 7.x Java API Client KNN 语法不兼容**：`knn { ... }` 语法报错 `Unknown key for a START_ARRAY` → 换用 `script_score` + `cosineSimilarity` 实现向量检索
- **ES `script_score` 查询维度不匹配**：curl 测试时向量维度 9 ≠ 1024 → 用完整 1024 维向量测试；Java 代码中改用 `JSONObject` 动态构造避免字符串拼接错误
- **ES `knn` 查询语法在 7.17 版本报错**：`Unknown key for a START_OBJECT in [knn]` → 改用 `script_score` + `cosineSimilarity(params.queryVector, 'vector') + 1.0` 替代方案
- **`/api/ai` 需要登录认证**：返回 401 → 在 SecurityConfig 中添加 `/api/ai/**` 白名单放行

### 技术方案
| 组件 | 实现 |
|------|------|
| 向量模型 | DashScope `text-embedding-v3`（1024 维） |
| 向量存储 | Elasticsearch `book_chunks` 索引（dense_vector） |
| 向量检索 | ES `script_score` + `cosineSimilarity`（兼容 ES 7.x） |
| chunk 切分 | 500 字符/块，50 字符重叠滑动窗口 |
| 生成模型 | Kimi（moonshot-v1-8k），复用现有 ModelClient |
| 检索数量 | 默认 top-3 相关片段 |

### 简历素材
> 基于书籍章节文本切分（500字符块/50字符重叠）构建 RAG 检索链路，接入 DashScope embedding 服务实现文本向量化，通过 Elasticsearch script_score + cosineSimilarity 实现向量相似度检索，再将检索片段与问题拼接后交由 Kimi 生成回答，并返回引用来源以提升回答可解释性。

---

## [2026-05-03] W4：Elasticsearch 全文搜索

### 新增
- 新增 `BooksDoc.java` — ES 文档类（indexName: books_doc），字段：id、title、author、intro、status、chapterTitles、chapterContents
- 新增 `BooksDocRepository.java` — ES 仓库接口，继承 `ElasticsearchRepository`
- 新增 `SearchService.java` — 搜索服务，多字段匹配（title^3/author^2/intro/chapterTitles^2/chapterContents）+ 分类过滤（status）+ 高亮（\<em\>标签）+ 分页
- 新增 `SearchController.java` — `/api/search` 搜索接口（GET，参数：keyword/category/page/pageSize）
- 新增 `AdminController.java` — `/api/admin/sync/{bookId}` 单本同步 + `/api/admin/sync/all` 批量同步
- 新增 `BooksSearchResult.java` — 搜索结果 DTO（包含 book + highlights）

### 修改
- `DataInitializer.java` — 初始化书籍时自动同步到 ES（searchService.indexBook）
- `BooksService.java` — 新增 saveBook()（MySQL + ES 同步）、syncAllBooksToEs()（批量同步）
- `SearchService.indexBook()` — 删除 createdAt/updatedAt 字段（避免 LocalDateTime 序列化问题）
- `SecurityConfig.java` — 新增 `/api/admin/**` 和 `/api/search/**` 白名单放行

### 环境搭建
- Docker 安装 ES 7.17.20（`docker run -d --name elasticsearch -p 9200:9200 elasticsearch:7.17.20`）
- 安装 IK 中文分词器（`docker exec elasticsearch bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/7.17.20`）
- IK 分词验证：`curl http://localhost:9200/_analyze -H "Content-Type: application/json" -d "{\"text\":\"Java编程思想\",\"analyzer\":\"ik_max_word\"}"`

### 遇到的问题及解决
- **ES 索引名大小写问题**：`booksDoc` 报错 "Invalid index name [booksDoc], must be lowercase" → 改成 `books_doc`
- **Security 白名单问题**：`/api/admin/**` 和 `/api/search/**` 未放行导致 401 → 在 SecurityConfig 加 `.requestMatchers()`
- **SearchService 编译错误**：删了 BooksDoc 的 createdAt/updatedAt 字段后，indexBook() 里的 setter 调用还在 → 删掉对应两行
- **BooksServiceTest 构造器参数不匹配**：新增 SearchService/ChaptersService 后测试用例构造器报错 → 加 @Mock + 更新 setUp()
- **日期转换错误（未解决）**：ES 存 LocalDateTime 序列化问题，方案是去掉日期字段

### 待做
- [ ] W4：修改前端 reader.html，将 `/api/books?keyword=` 改为 `/api/search?keyword=`，并渲染高亮（\<em\>标签显示为橙色）
- [ ] W4：前端分类过滤对接 ES 搜索的 category 参数
- [ ] W5：接入 RabbitMQ，书籍新增/更新改为异步同步到 ES（主链路更快）

### 简历素材
> 引入 Elasticsearch 构建书籍与章节全文索引，实现关键词检索、高亮展示与相关性排序（title^3 > author^2 > chapterTitles^2 > chapterContents），提升搜索体验并降低数据库模糊查询压力。

---

## [2026-04-27] W2-1：日志规范

### 新增
- 新增 `RequestIdFilter.java` — 为每个请求生成唯一请求ID（req-xxx），贯穿整个请求生命周期
- 新增 `WebConfig.java` — 注册 RequestIdFilter，统一过滤所有请求

### 修改
- `application.yml` — 新增日志格式配置，包含请求ID `%X{reqId}`，日志输出到 `server.log`
- `BooksController` — 新增 6 处日志（查询列表、查询详情、章节列表、章节内容）
- `UserController` — 新增 4 处日志（注册尝试/成功、登录尝试/成功/失败）
- `ReadingController` — 新增 4 处日志（获取进度、更新进度）
- `BookshelfController` — 新增 4 处日志（获取书架、添加书籍）
- `AiChatController` — 新增 2 处日志（问答请求/完成）
- `JwtAuthFilter` — 新增 3 处日志（JWT认证成功/失败）
- `GlobalExceptionHandler` — 全局异常处理器新增详细日志（业务异常/参数异常/认证异常/系统异常）

### 日志规范
| 场景 | 级别 | 示例 |
|------|------|------|
| 正常业务流程 | INFO | `log.info("用户登录成功 - userId:{}")` |
| 潜在问题 | WARN | `log.warn("用户登录失败 - reason:{}")` |
| 异常/系统错误 | ERROR | `log.error("系统异常 - {}", e.getMessage(), e)` |
| 开发调试 | DEBUG | `log.debug("方法入参: {}")` |

### 日志格式
```
2026-04-27 00:30:00.123 [http-nio-8080-exec-1] INFO  [req-abc12345] BooksController - 查询书籍列表完成 - 返回 10 条
```

### W2完成清单
- ✅ 日志规范（请求ID串联、Controller日志、全局异常日志）

### 待做
- [ ] W2：单元测试、集成测试、模拟数据脚本

---

## [2026-04-25] W1收尾：排序/筛选 + Security认证处理器

### 新增
- 新增 `AuthenticationErrorHandler.java` — 未认证时返回 CommonResponse JSON 格式（401）
- 新增 `AccessDeniedErrorHandler.java` — 未授权时返回 CommonResponse JSON 格式（403）

### 修改
- BooksController：新增 `category`、`sortBy`、`order` 参数，支持分类筛选和排序
- BooksService：新增 Sort 逻辑，支持按 updatedAt/createdAt/title/author/status 排序
- BooksRepository：新增 `findByStatus(status, pageable)` 方法
- ErrorCode：新增 `toException()` 方法（返回异常对象，用于 orElseThrow 场景）
- ChaptersService：修复 `orElseThrow` 编译错误，改为 `toException()`
- SecurityConfig：注册 AuthenticationErrorHandler + AccessDeniedHandler

### 问题修复
- 修复 ChaptersService 编译错误：`.throwException()` 改为 `.toException()`
- 修复 sortBy 默认值拼写错误：`updateAt` → `updatedAt`
- 解决 MySQL 建表问题：application.yml 新增 `spring.jpa.hibernate.ddl-auto: update`
- 解决分类筛选为空：数据库 status 字段中文乱码，UPDATE 修正为正确中文值
- 解决 Hibernate Dialect 检测失败：显式指定 `spring.jpa.database-platform: MySQLDialect`

### W1完成清单
- ✅ Swagger 接入
- ✅ 参数校验（@Min/@Max/@Positive/@NotNull）
- ✅ 统一错误码（ErrorCode + BusinessException）
- ✅ 分页 + 排序 + 筛选
- ✅ Security 自定义认证处理器（统一 401/403 JSON 格式）

### 待做
- [ ] W2：日志规范、单元测试、模拟数据脚本

---

## [2026-04-20] W1：统一错误码

### 新增
- 新增 `ErrorCode.java` — 13个错误码枚举（40001~50001）
- 新增 `BusinessException.java` — 业务异常，支持 `throwException()`
- `CommonResponse` 新增 `error(ErrorCode)` / `error(ErrorCode, String)` 工厂方法

### 修改（Service层5处）
- UserService: `IllegalArgumentException` → `ErrorCode.DUPLICATE_USERNAME`
- ChaptersService: → `ErrorCode.CHAPTER_NOT_FOUND` / `INTERNAL_ERROR`
- AiChatService: → `BusinessException` / `ErrorCode.CHAPTER_NOT_FOUND`

### 重要发现（今日）
- GlobalExceptionHandler 里的 `AuthenticationException` / `AccessDeniedException` **永远不会被触发**
- 原因：Spring Security 的 `ExceptionTranslationFilter` 先于 `@ControllerAdvice` 拦截这两个异常，直接由 Security 内置 handler 处理
- JwtAuthFilter 同时塞了两份 userId：`request.setAttribute`（实际用）+ `SecurityContextHolder`（存了没用）
- SecurityConfig 目前 `/api/user/**` 需要认证，但无 token 时返回的是 Spring Security 默认格式，不是 CommonResponse

### 待做
- [ ] W1：分页 + 排序 + 筛选
- [ ] Security：自定义 AuthenticationEntryPoint / AccessDeniedHandler，统一 401/403 格式

---

## [2026-04-16] 项目升级计划启动

### 新增

- 新增 `8-WEEK-PLAN.md`，规划8周升级路线
- 确定项目最终定位：智能阅读平台（有搜索、有异步事件、有监控、有书内问答）

### 现状

- 项目为单体 Spring Boot 3.3.4 阅读平台
- 已具备：JWT认证、Redis缓存（穿透/击穿/雪崩）、统一返回体、全局异常、Docker部署
- 待升级：工程化、压测、ES搜索、RabbitMQ异步、监控、RAG+SSE

---

## 后续记录格式

每次迭代完成后，在此文件添加一条记录：

```
## [YYYY-MM-DD] 周次 + 主题

### 新增功能
- ...

### 修改优化
- ...

### 简历素材
> 一句完整的话，可直接写进简历
```
