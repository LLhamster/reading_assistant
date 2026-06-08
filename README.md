# HTTP Reading - 智能阅读平台

> 一个基于 Java Spring Boot 的在线阅读平台，经历 8 周升级后将成为"有搜索、有异步事件、有监控、有书内问答"的智能阅读平台。

---

## 项目规划

- 📋 **升级计划**：[8-WEEK-PLAN.md](./8-WEEK-PLAN.md)（8周详细任务清单）
- 📝 **迭代记录**：[CHANGELOG.md](./CHANGELOG.md)

---

## 当前技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 3.3.4（Java 17） |
| 持久层 | Spring Data JPA + MySQL |
| 缓存 | Spring Data Redis（穿透/击穿/雪崩防护） |
| 安全 | Spring Security + JWT（BCrypt密码加密） |
| AI | Moonshot（Kimi）大模型 API |
| 构建 | Maven + Docker |

## 已完成功能

- ✅ 用户系统（注册/登录，JWT无状态认证）
- ✅ 书籍管理（分页浏览、关键词搜索、书籍详情、章节列表）
- ✅ 书架功能（用户收藏书籍，支持幂等）
- ✅ 阅读进度（记录并恢复阅读位置，Redis缓存）
- ✅ AI 问答（基于章节内容调用Kimi大模型）
- ✅ 统一返回体（`CommonResponse<T>`）
- ✅ 全局异常处理
- ✅ Docker 一键部署（MySQL + Redis + Spring Boot）

## 升级中功能（8周计划）

| 周次 | 主题 | 状态 |
|------|------|------|
| W1 | 工程基础（Swagger、参数校验、错误码） | ⬜ |
| W2 | 日志、测试、数据准备 | ⬜ |
| W3 | JMeter压测 + 性能优化 | ⬜ |
| W4 | Elasticsearch全文搜索 | ⬜ |
| W5 | RabbitMQ异步事件 | ⬜ |
| W6 | Micrometer + Prometheus + Grafana监控 | ⬜ |
| W7 | 书内RAG问答 | ⬜ |
| W8 | SSE流式输出 + 项目收尾 | ⬜ |

---

## 项目结构

```
java_src/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── README.md
├── CHANGELOG.md
├── 8-WEEK-PLAN.md          # 8周升级计划
│
└── src/main/
    ├── resources/
    │   ├── application.yml
    │   └── static/
    │       └── reader.html
    │
    └── java/com/example/httpreading/
        ├── HttpReadingApplication.java
        │
        ├── api/
        │   ├── CommonResponse.java
        │   └── GlobalExceptionHandler.java
        │
        ├── controller/
        │   ├── UserController.java      # /api/auth/*
        │   ├── BooksController.java     # /api/books/*
        │   ├── BookshelfController.java # /api/user/bookshelf/*
        │   ├── ReadingController.java   # /api/user/books/*/progress
        │   └── AiChatController.java    # /api/ai
        │
        ├── domain/
        │   ├── entity/ (Books, Chapters)
        │   └── user/ (User, Bookshelf, Reading)
        │
        ├── dto/
        │   └── AuthRequest.java
        │
        ├── repository/
        │
        ├── security/
        │   ├── JwtService.java
        │   ├── JwtAuthFilter.java
        │   └── SecurityConfig.java
        │
        └── service/
            ├── UserService.java
            ├── BooksService.java         # Redis缓存（穿透/击穿/雪崩）
            ├── ChaptersService.java
            ├── BookshelfService.java
            ├── ReadingService.java       # Redis缓存
            ├── AiChatService.java
            └── ModelClient.java         # Kimi API封装
```

---

## 配置文件

`src/main/resources/application.yml` 关键配置项：

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://${SPRING_DATASOURCE_HOST:mysql}:3306/${SPRING_DATASOURCE_NAME:bookstore}
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:root123}
  data:
    redis:
      host: ${SPRING_REDIS_HOST:redis}
      port: ${SPRING_REDIS_PORT:6379}

cache:
  enabled: true   # true=启用Redis缓存，false=禁用缓存直连数据库

model:
  apiKey: "sk-..."   # Moonshot API Key
```

---

## 接口一览

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/api/auth/register` | 用户注册 | 否 |
| POST | `/api/auth/login` | 用户登录，返回JWT | 否 |
| GET | `/api/books` | 分页查询书籍（关键词搜索） | 否 |
| GET | `/api/books/{id}` | 获取书籍详情 | 否 |
| GET | `/api/books/{id}/chapters` | 获取书籍章节列表 | 否 |
| GET | `/api/books/{id}/chapters/{index}` | 获取章节内容 | 否 |
| GET | `/api/user/bookshelf` | 获取用户书架 | 是 |
| POST | `/api/user/bookshelf/{bookId}` | 添加书籍到书架 | 是 |
| GET | `/api/user/books/{bookId}/progress` | 获取阅读进度 | 是 |
| POST | `/api/user/books/{bookId}/progress` | 更新阅读进度 | 是 |
| GET | `/api/ai` | AI章节问答（基于Kimi） | 否 |

---

## 本地运行

```bash
# 开发环境
mvn spring-boot:run

# Docker环境（需要 Docker Desktop）
docker compose up --build
```

访问 `http://localhost:8080/reader.html` 打开阅读器页面。

---

## 面试话术（最终简历版本）

```
智能阅读平台｜Java 后端项目
技术栈：Spring Boot / MySQL / Redis / Elasticsearch / RabbitMQ / Prometheus / Grafana / Kimi API

- 设计用户、书籍、书架、阅读进度等核心模块，完成 RESTful API 开发，并通过统一错误码、参数校验与接口文档提升后端工程规范性。
- 针对热点访问场景设计 Redis 缓存方案，结合 JMeter 压测、慢查询分析与索引优化完成性能调优，在模拟数据库慢查询场景下将核心接口响应时间由 30ms 降至 11ms。
- 引入 Elasticsearch 构建书籍与章节全文索引，实现关键词检索、高亮展示与相关性排序，优化搜索体验。
- 基于 RabbitMQ 将阅读行为、搜索日志与问答记录异步化，并通过手动确认、重试与幂等处理提升消息消费可靠性。
- 基于 Micrometer、Prometheus 与 Grafana 构建监控体系，对接口耗时、JVM、缓存命中率与消息积压情况进行可视化监控。
- 构建书内 RAG 问答链路，基于章节片段检索增强 Kimi API 回答效果，并通过 SSE 实现流式输出与引用来源返回。
```
