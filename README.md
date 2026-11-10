![Backend CI](https://github.com/bcxy3915/ops-agent/actions/workflows/ci.yml/badge.svg)

> 当前分支：`migration/langchain4j`。生产代码及 Maven 依赖已统一为 LangChain4j，Spring AI 已移除。
> 同步/流式 Agent、记忆、工具、诊断与知识链路已切换；真实模型、数据库与页面验收仍需进行。
> 迁移范围、测试命令和进度见 [迁移执行记录](docs/migration/langchain4j/01-baseline-and-poc.md)。
> 配置统一使用 `ops-agent.ai.*`，原 `spring.ai.*` 仅保留取值兼容；`provider` 不再生效。向量表使用现有初始化脚本，不自动重建。
> 旧版本保留在 `maintenance/spring-ai`；下文历史功能与版本信息在迁移中逐项核对。

<h1 align="center">Ops Agent</h1>

<p align="center">
  通用运维智能体 —— 用自然语言诊断服务问题的 AI 应用<br />
  基于 LangChain4j + RAG + Tool Calling，实现「服务无关」的自动化运维诊断平台
</p>

<p align="center">
  <a href="#特性">特性</a> ·
  <a href="#技术栈">技术栈</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#api-概览">API 概览</a> ·
  <a href="#项目结构">项目结构</a> ·
  <a href="#测试">测试</a> ·
  <a href="#roadmap">Roadmap</a>
</p>

---

## ✨ 特性

### 🧠 AI 运维诊断
- **自然语言问答**：输入问题即可获得根因分析与处置建议，支持流式输出（SSE）
- **RAG + Tool Calling 融合**：System Prompt 引导模型自动选择「查知识库」或「调工具」
- **多轮对话记忆**：ChatMemory + 会话隔离，追问上下文自动衔接
- **来源溯源**：诊断结论携带参考知识（文档/案例）的引用片段、得分与版本，回答可追溯
- **内嵌追问**：基于告警上下文继续深挖根因，无需离开页面

### 🔎 RAG 检索架构
- 多格式文档加载（Markdown / PDF / Word）
- **混合检索**：向量检索（Elasticsearch）+ BM25 关键词检索，增量加载避免重复入库
- **RRF 融合 + Reranker 精排**：gte-rerank-v2 提升首位命中率
- **可演进设计**：向量与 BM25 共用 Elasticsearch ACL 预过滤，并通过 MySQL 权威状态复核候选

### 🛠 服务无关设计
- 工具签名只接收 `serviceName`，服务地址从注册中心动态获取 —— **加新服务不改代码**
- `queryServiceHealth` / `queryMetric` / `listMetrics` / `listServicesByStatus`
- 数据库驱动的服务注册中心 + REST API 动态注册/更新/下线
- 定时健康检查（30 秒轮询），服务状态实时更新（UP / DOWN / UNKNOWN）

### 🚨 告警中心
- **告警状态机**：FIRING → ACKNOWLEDGED → RESOLVED，支持静默/解除静默/认领/接管
- **多级升级策略**：L1/L2 逐级升级（OPERATOR / ADMIN），触发条件 + 升级通知
- **批量静默**：一次静默多条告警，跳过已恢复项并回显处理结果
- **告警汇总**：待处理/触发中/今日/已诊断等实时统计，驱动首页仪表盘
- **告警已读**：未读红点、全部已读，状态回退视为新事件

### 🧰 运维治理
- **维护窗口**：指定服务+原因+到期时间，窗口期内自动抑制告警与通知，到期自动解除
- **值班管理**：主值班/备值班多人配置，首报→主值班、L1→备值班、L2→ADMIN，支持角色兜底
- **通知渠道**：邮件等渠道统一注册，通知记录可查
- **故障快照**：故障现场自动留存，便于复盘与案例沉淀

### 📚 知识库管理
- 文档库：上传/重建索引/版本管理（Markdown / PDF / Word）
- 故障案例：从诊断沉淀案例，三态流转（真实根因/有效处置/脱敏），支持确认与修正
- 检索可视化：混合检索过程可视化，命中日志与质量评估
- 统计看板：文档数、待沉淀案例、本月知识命中

### 🔐 安全合规
- **JWT + RBAC**：Spring Security 认证授权，ADMIN / OPERATOR / VIEWER 三级角色
- **操作审计**：AOP 注解式审计日志，关键操作全程留痕
- **接口限流**：Bucket4j 令牌桶，注解式限流
- **敏感信息脱敏**：`@Sensitive` 注解 + Jackson 序列化器，日志/响应自动脱敏
- **字段加密**：敏感字段加密存储
- **第三方容错**：Resilience4j 超时/重试/熔断

## 📦 技术栈

| 层级 | 技术 |
| :--- | :--- |
| 语言 | Java 21 |
| 框架 | Spring Boot 4.1.2 |
| AI 框架 | LangChain4j 1.23.0 |
| Chat 模型 | DeepSeek（deepseek-chat，OpenAI 协议） |
| Embedding | 阿里云百炼 text-embedding-v3（1024 维） |
| Reranker | 阿里云百炼 gte-rerank-v2 |
| 向量与关键词检索 | Elasticsearch 8.19.6（dense_vector + BM25） |
| 业务数据库 | MySQL 8.4 + MyBatis-Plus 3.5.18 |
| 安全 | Spring Security + JWT（jjwt 0.12.6） |
| 限流 | Bucket4j 8.10.1 |
| 容错 | Resilience4j 2.3.0 |
| 通知 | JavaMail |
| 文档解析 | Apache POI（docx） |
| API 文档 | springdoc-openapi 3.2.0 |
| 流式输出 | WebFlux + SSE |
| 测试 | JUnit + JaCoCo（覆盖率） |

## 🚀 快速开始

### 1. 前置准备

- JDK 21、Maven 3.8+
- Docker Desktop
- DeepSeek API Key、阿里云百炼 API Key

### 2. 启动依赖服务

```bash
cd ops-ai-agent
cp .env.example .env # 先填入 DB_PASSWORD
docker compose up -d mysql elasticsearch
python scripts/initialize-elasticsearch.py
```

首次启动 MySQL 空数据卷时自动执行 `init-scripts/mysql/schema.sql` 和 `seed.sql`。MySQL 映射到 `127.0.0.1:23306`，ES 映射到 `127.0.0.1:9200`。ES 初始化单独执行，不随业务库建表脚本混跑。已有数据卷不会重复初始化；结构升级使用 `init-scripts/mysql/migrations/`。

MySQL 保存原文、分块、权限及持久化投递记录；ES 保存向量与 BM25 检索投影。投递失败自动退避重试，删除保留墓碑；投递向量只在 MySQL 暂存，ES 确认后清空。文档在全部分块同步成功后才标记为已发布。

### 3. 配置环境变量

```bash
cp .env.example .env        # 填入 DB_PASSWORD 等
```

创建 `application-local.yml`（参考 `application.yml` 中的占位符说明）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:23306/opsdb?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8
    username: root
    password: ${DB_PASSWORD}
ops-agent:
  ai:
    api-key: ${OPS_AI_API_KEY}
    embedding:
      api-key: ${OPS_EMBEDDING_API_KEY}
    elasticsearch:
      url: http://localhost:9200
```

### 4. 启动应用

```bash
mvn spring-boot:run
```

启动时自动：加载 `resources/knowledge/` 完整原文及分块 → LangChain4j 生成向量 → MySQL 持久化投递 → 同步 ES 向量与 BM25 索引 → 开启健康检查调度。

本地后端默认端口为 `18080`，前端开发地址为 `http://localhost:8888`，前端 `/api` 代理至 `http://localhost:18080`。默认跨域白名单允许 `http://localhost:8888` 和 `http://127.0.0.1:8888`；自定义部署地址通过 `OPS_CORS_ALLOWED_ORIGINS` 配置。

旧 PostgreSQL 数据可先通过 `python scripts/migrate-local-postgres-to-mysql.py` 预检。工具只接受尚未开展业务的新 MySQL 初始化库，显式 `--execute` 才导入，并先保存源快照和目标备份；不得对已有 MySQL 业务库重复导入。切换前应停写旧应用，核对迁移期间增量。不要删除旧 PostgreSQL 数据卷。

### 5. 验证

| 用途 | 地址 |
| :--- | :--- |
| Swagger UI | http://localhost:18080/swagger-ui.html |
| 流式测试页 | http://localhost:18080/stream-test.html |
| 流式问答 | `POST /api/ops/ask/stream`（JSON：question / sessionId） |

## 🔌 API 概览

| 模块 | 前缀 | 说明 |
| :--- | :--- | :--- |
| 智能体问答 | `/api/ops` | 同步/流式问答 |
| 会话管理 | `/api/chat` | 会话列表/历史/清理 |
| 服务注册 | `/api/services` | 服务 CRUD + 健康状态 |
| 指标查询 | `/api/services/{serviceName}/metrics` | 实时指标 |
| 知识库 | `/api/knowledge` | 文档/案例/检索/质量/统计/命中日志 |
| 诊断 | `/api/diagnoses` | 诊断记录 + 来源溯源 + 追问 |
| 告警事件 | `/api/alerts/events` | 状态机/批量静默/汇总 |
| 告警规则 | `/api/alerts/rules` | 多级升级策略 |
| 维护窗口 | `/api/maintenance/windows` | 维护窗口 CRUD |
| 值班管理 | `/api/oncall/members` | 主/备值班成员 |
| 通知 | `/api/notifications` | 渠道/记录 |
| 故障快照 | `/api/snapshots` | 故障现场留存 |
| 认证 | `/api/auth` | 登录/令牌 |
| 用户管理 | `/api/users` | 用户 CRUD + 角色 |
| 审计日志 | `/api/audit` | 操作审计查询 |

## 📁 项目结构

```
ops-ai-agent/
├── docker-compose.yml                 # MySQL + Elasticsearch；legacy profile 保留旧 PG
├── init-scripts/                      # mysql/ 业务表与数据；elasticsearch/ 检索索引
├── .env.example                       # 环境变量模板
└── src/
    ├── main/java/com/example/opsaiagent/
    │   ├── service/                   # Agent 核心（RAG + Tool 编排 + 流式）
    │   ├── tools/                     # AI 工具集（健康/指标/服务发现）
    │   ├── retrieval/                 # 检索（bm25 / fusion / rerank）
    │   ├── loader/                    # 知识库加载器
    │   ├── knowledge/                 # 知识库管理（文档/案例/质量/版本）
    │   ├── diagnosis/                 # 诊断（溯源 + 追问）
    │   ├── alert/                     # 告警（事件/规则/升级/已读）
    │   ├── maintenance/               # 维护窗口
    │   ├── oncall/                    # 值班管理
    │   ├── notification/              # 通知渠道/模板
    │   ├── snapshot/                  # 故障快照
    │   ├── security/                  # JWT + RBAC + 用户
    │   ├── audit/                     # 操作审计
    │   ├── ratelimit/                 # 接口限流
    │   ├── sensitive/                 # 敏感信息脱敏
    │   ├── crypto/                    # 字段加密
    │   ├── resilience/                # 第三方容错
    │   ├── registry/                  # 服务注册中心
    │   ├── metrics/                   # 指标采集
    │   ├── controller/ dto/ entity/ mapper/ exception/ config/
    │   └── util/
    └── main/resources/
        ├── application.yml            # 主配置（提交）
        ├── application-local.yml      # 本地密钥（不提交）
        ├── knowledge/                 # 内置运维知识文档
        └── static/                    # 测试页
```

## 🧪 测试

```bash
mvn test
```

- 全量 **776 个用例**，0 失败 0 错误
- JaCoCo 覆盖率报告：`target/site/jacoco/index.html`
- GitHub Actions：push / PR 自动跑 CI，上传 surefire 与覆盖率报告

## 🗺 Roadmap

| 阶段 | 内容 | 状态 |
| :--- | :--- | :--- |
| P1 | RAG + Tool Calling 融合链路 | ✅ |
| P2 | 多轮对话 + 服务注册中心 + 健康检查 | ✅ |
| P3 | 流式输出 + 混合检索 + Reranker + Swagger | ✅ |
| P4 | 安全合规（JWT + RBAC + 审计 + 限流 + 脱敏） | ✅ |
| P5 | 知识库管理 + 告警中心 + 诊断溯源 + 维护窗口/值班 | ✅ |
| P6 | 桌面化（Tauri） | 📋 规划中 |
| P7 | 打包分发（jpackage + Inno Setup） | 📋 规划中 |

## 📄 许可

MIT License

---

**注意**：本项目为学习实践项目，代码仅供参考。生产使用前请补充完整的安全措施。
