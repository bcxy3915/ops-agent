# Ops Agent

通用运维智能体 —— 用自然语言诊断服务问题的 AI 应用

## 简介

基于 Spring AI + RAG + Function Calling，实现**服务无关**的运维诊断平台。
任何暴露 Spring Boot Actuator 端点的服务，都能被自动纳管，无需修改智能体代码。

## 技术栈

| 层级 | 技术 |
| :--- | :--- |
| 语言 | Java 21 |
| 框架 | Spring Boot 3.4.13 |
| AI 框架 | Spring AI 1.1.0 |
| Chat 模型 | DeepSeek（deepseek-chat） |
| Embedding | 阿里云百炼（text-embedding-v3，1024 维） |
| Reranker | 阿里云百炼（gte-rerank-v2） |
| 向量库 | Pgvector（pg16） |
| 数据库 | PostgreSQL 16 |
| 持久层 | MyBatis-Plus 3.5.5 |
| API 文档 | springdoc-openapi 2.9.1 |
| 流式输出 | WebFlux + SSE |

## 核心能力

### RAG 检索
- 多格式文档加载（Markdown / PDF）
- 增量加载，避免重复入库
- **混合检索**：向量检索 + BM25 关键词检索
- **RRF 融合**：两路结果加权合并
- **Reranker 精排**：gte-rerank-v2 提升首位命中率

### Tool Calling
- **服务无关设计**：工具签名只接收 `serviceName` 参数
- `queryServiceHealth`：查服务健康状态
- `queryMetric`：查指定指标值
- `listMetrics`：列出服务支持的所有指标
- `listServicesByStatus`：按状态查服务

### Agent 能力
- RAG + Tool 融合，System Prompt 引导模型选择信息源
- 多轮对话记忆（ChatMemory + 会话隔离）
- 流式输出（SSE，逐字返回）
- 工具错误信息精准分类（未注册 / 连接失败 / 404 / 5xx）

### 服务管理
- 数据库驱动的服务注册中心（MyBatis-Plus）
- REST API 动态注册 / 查询 / 更新 / 下线
- 定时健康检查（30 秒轮询）
- 服务状态实时更新（UP / DOWN / UNKNOWN）

## 架构图

```
┌─────────────────────────────────────────────────────┐
│                    User                             │
│         对话界面 · 服务面板 · 告警面板               │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│                   Gateway                           │
│      API 网关 · 认证授权 · 限流熔断 · 审计日志        │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│              Agent Core ★★★★★                       │
│   意图识别 · RAG 检索 · 工具编排 · 综合推理 · 记忆    │
└─────┬───────────────────────────────────┬───────────┘
      │                                   │
┌─────▼──────────┐              ┌─────────▼────────────┐
│ Service Registry│              │   Tool Layer         │
│ 服务元信息 ★★★★★│              │ 服务发现 · 健康检查   │
│ 注册接入        │              │ 指标查询 · 日志查询   │
│ 健康调度        │              │ + 扩展工具            │
│ 服务发现        │              └─────────┬────────────┘
└─────┬───────────┘                        │
      │                                    │
┌─────▼──────────┐              ┌──────────▼───────────┐
│  Knowledge     │              │    AI Infra          │
│ 通用运维知识    │              │ 模型能力 · Provider  │
│ 文档处理        │              │ 工具协议 · 可观测性   │
│ 混合检索        │              └──────────┬───────────┘
│ Rerank 重排    │                         │
└─────┬──────────┘                         │
      │                                    │
┌─────▼────────────────────────────────────▼───────────┐
│              Storage                                 │
│   Pgvector · PostgreSQL · Redis · 对象存储            │
└──────────────────────┬───────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────┐
│            Managed Services                          │
│    Service A · Service B · Service C                 │
│    /actuator/*  业务层 + 故障模拟 + 监控              │
└──────────────────────────────────────────────────────┘
```

## 快速开始

### 1. 前置准备

- JDK 21
- Maven 3.8+
- Docker Desktop
- DeepSeek API Key
- 阿里云百炼 API Key

### 2. 启动依赖服务

```bash
cd ops-ai-agent
docker compose up -d
```

首次启动会自动执行 `init-scripts/01-init.sql`：
- 启用 `vector` 和 `hstore` 扩展
- 创建 `ops_service` 表

### 3. 配置环境变量

复制 `.env.example` 为 `.env`，填入实际值：

```properties
DB_PASSWORD=your_password
```

复制 `application.yml` 中的占位符说明，创建 `application-local.yml`：

```yaml
spring:
  ai:
    openai:
      api-key: sk-你的DeepSeekKey
      embedding:
        api-key: sk-你的百炼Key
  datasource:
    password: your_db_password
```

### 4. 启动应用

```bash
cd ops-ai-agent
mvn spring-boot:run
```

应用启动时会：
1. 从 `resources/knowledge/` 加载运维文档
2. 向量化后存入 Pgvector
3. 构建 BM25 内存索引
4. 定时检查所有注册服务的健康状态

### 5. 访问接口

| 用途 | 地址 |
| :--- | :--- |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| 流式测试页 | http://localhost:8080/stream-test.html |
| 同步问答 | `GET /api/ops/ask?question=xxx&sessionId=xxx` |
| 流式问答 | `GET /api/ops/ask/stream?question=xxx&sessionId=xxx` |
| 服务列表 | `GET /api/services` |

## 项目结构

```
ops-agent/
├── docker-compose.yml
├── init-scripts/
│   └── 01-init.sql                  # 数据库初始化脚本
├── .env.example                     # 环境变量模板
├── ops-ai-agent/                    # 运维智能体核心
│   ├── src/main/java/com/example/opsaiagent/
│   │   ├── controller/              # REST 接口
│   │   ├── dto/                     # 请求/响应对象
│   │   ├── exception/               # 全局异常处理
│   │   ├── registry/                # 服务注册中心
│   │   │   ├── entity/              # 数据库实体
│   │   │   ├── mapper/              # MyBatis-Plus Mapper
│   │   │   └── service/             # 业务逻辑
│   │   ├── retrieval/               # 检索模块
│   │   │   ├── bm25/                # BM25 关键词检索
│   │   │   ├── fusion/              # RRF 融合
│   │   │   └── rerank/              # Reranker 精排
│   │   ├── tools/                   # AI 工具集
│   │   ├── loader/                  # 知识库加载
│   │   ├── service/                 # Agent 核心
│   │   └── config/                  # 配置类
│   └── src/main/resources/
│       ├── application.yml          # 主配置（提交）
│       ├── application-local.yml    # 本地密钥（不提交）
│       ├── knowledge/               # 运维文档
│       └── static/                  # 前端测试页
└── todo-service/                    # 被监控示例服务
```

## 项目阶段

| 阶段 | 内容 | 状态 |
| :--- | :--- | :--- |
| **P1** | RAG + Tool Calling 融合链路 | ✅ 完成 |
| **P2** | 多轮对话 + 服务注册中心（DB + API）+ 定时健康检查 | ✅ 完成 |
| **P3** | 流式输出 + 工具错误优化 + Swagger + 混合检索 + Reranker | ✅ 完成 |
| **P4** | 安全合规（JWT + RBAC + 审计 + 限流） | 📋 待开始 |
| **P5** | 前端开发（Vue 3 + Element Plus） | 📋 待开始 |
| **P6** | 桌面化（Tauri） | 📋 待开始 |
| **P7** | 打包分发（jpackage + Inno Setup） | 📋 待开始 |

## 核心设计

### 服务无关性
工具签名只接收 `serviceName` 参数，服务地址从注册中心动态获取。
**加新服务不改代码，只改配置。**

### 可演进的检索架构
```
KeywordRetriever（接口）
    ├── InMemoryBm25Retriever（当前）
    ├── PostgresBm25Retriever（未来，10万+片段）
    └── ElasticsearchRetriever（未来，100万+片段）
```
通过 `ops-agent.retrieval.keyword.type` 配置切换。

### 分层解耦
- **写入口**（REST API）和**读入口**（智能体）通过数据库解耦
- **工具层**只负责"查"，**知识层**只负责"答"，**核心层**负责"编排"

## 关键数据

| 指标 | 数值 |
| :--- | :--- |
| 知识库文档 | 8 个 Markdown 文件 |
| 向量片段数 | 162 个 |
| 召回片段数 | 5 个（Reranker 精排后） |
| Reranker 延迟 | ~135ms |
| 单次问答成本 | ~0.0015 元（Reranker） |
| 定时健康检查间隔 | 30 秒 |

## 后续规划

- **P4**：接入 Spring Security，实现 JWT + RBAC，添加操作审计
- **P5**：Vue 3 前端，包含对话界面、服务管理、指标可视化
- **P6**：用 Tauri 打包为 Windows 桌面应用
- **P7**：用 jpackage + Inno Setup 制作安装包

## 许可

MIT License

---

**注意**：本项目为学习实践项目，代码仅供参考。生产使用前请补充完整的安全措施。