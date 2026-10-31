# Ops Agent 项目协作指南

本文为在本项目中工作的 AI 编程助手与开发者提供上下文、开发约定和验证要求。适用范围为当前后端项目目录。

## 1. 项目概览

Ops Agent 是一个 AI 运维诊断后端，包含自然语言问答、流式响应、知识检索、服务健康与指标查询、告警、通知、故障诊断和权限管理等能力。

- 语言与构建：Java 21、Maven，仓库提供 Maven Wrapper。
- 核心框架：Spring Boot、Spring AI。
- 数据存储：PostgreSQL、Pgvector、MyBatis-Plus。
- 接口与安全：Spring MVC、WebFlux/SSE、Spring Security、JWT、RBAC。
- 检索：向量检索、BM25、RRF 融合与重排序。
- 测试：JUnit 5、Mockito、AssertJ、JaCoCo。

依赖的准确版本以 `pom.xml` 为准；README 中的版本说明可能滞后。修改前优先阅读当前实现、配置和相关测试。

## 2. 目录导航

主代码包：`src/main/java/com/example/opsaiagent/`。

| 目录 | 职责 |
| --- | --- |
| `ops/`、`chat/` | 运维 Agent 编排、问答及会话 |
| `tools/`、`registry/`、`metrics/` | AI 工具、服务注册与指标采集 |
| `knowledge/`、`retrieval/` | 知识文档、案例、检索与排序 |
| `diagnosis/`、`snapshot/` | 诊断记录与故障快照 |
| `alert/`、`maintenance/`、`oncall/` | 告警、维护窗口与值班 |
| `notification/` | 通知渠道、模板与投递 |
| `security/`、`rbac/` | 认证、授权、租户与数据权限 |
| `audit/`、`ratelimit/`、`resilience/` | 审计、限流与外部调用容错 |
| `crypto/`、`sensitive/` | 敏感字段加密与脱敏 |
| `common/`、`exception/` | 通用响应、公共能力与异常处理 |

其他重要路径：

- `src/main/resources/application.yml`：应用配置与配置占位符。
- `src/main/resources/mapper/`：MyBatis 映射文件。
- `src/main/resources/knowledge/`：内置知识文档。
- `src/test/java/com/example/opsaiagent/`：测试，按对应业务包组织。
- `init-scripts/schema.sql`、`init-scripts/seed.sql`：数据库初始化结构与种子数据。
- `docker-compose.yml`：本地依赖服务。
- `.github/workflows/ci.yml`：后端持续集成。
- `docs/`：设计及版本相关资料，部分子目录被 Git 忽略。

## 3. 工作方式

1. 明确需求，先定位相关业务模块、实现、配置和测试，再修改代码。
2. 开始前检查 `git status --short`，保留用户已有修改和未跟踪文件。
3. 尽量沿用现有实现，控制改动范围；不顺带重构无关模块或升级依赖。
4. 需求明确时直接推进；缺少影响接口、数据结构或业务含义的关键信息时再确认。
5. 跨模块修改同步检查调用方、权限、异常处理、数据库映射和相关测试。
6. 默认使用中文说明工作结果；代码标识符与包名保持项目现有英文风格。
7. 完成时说明修改内容、验证结果和仍存在的限制；未执行的检查应明确标注。

## 4. 编码与接口约定

- 遵循现有 Java 风格，使用 4 空格缩进；文件采用 UTF-8 编码，避免中文乱码。
- 按模块现有方式划分 Controller、Service、Mapper、Entity 和 DTO。
- 优先沿用构造器依赖注入及现有 Lombok 注解习惯。
- Controller 负责参数校验与接口适配，业务规则放在 Service，持久化逻辑放在 Mapper。
- 沿用 `common.api.ApiResponse`、`PageResult`、`BusinessException` 与现有错误码处理方式。
- 新增或调整接口时同步检查 Jakarta Validation、OpenAPI 注解、权限及审计约定。
- 保持接口路径、响应字段和 SSE 事件契约兼容；必要的契约变更应明确说明影响。
- 异常应携带可诊断的信息，不吞掉异常，不用成功响应掩盖失败。

## 5. 权限、数据与外部调用

- 新增查询、更新、删除及批量操作时遵循现有租户、部门、用户和数据权限约束。
- 检索、向量元数据、会话和异步任务也需要保持相同的数据隔离语义。
- 身份与权限从服务端可信上下文获取，不直接信任客户端传入的身份或租户字段。
- 保留现有权限校验、审计、限流、脱敏、加密和 URL 安全校验机制。
- 外部 HTTP、模型、邮件和 Webhook 调用沿用现有超时、重试与熔断策略，注意重试幂等性。
- 不在代码、测试、文档或日志中写入真实 API Key、密码、JWT 密钥或其他敏感信息。
- `.env` 和 `application-local.yml` 属于本地配置；未经需求明确要求，不修改或提交其中的真实配置。
- 配置示例使用占位符；新增配置项应核对配置绑定位置并补充说明。
- 修改数据库结构前检查现有脚本组织方式；已有数据库的增量变更与新环境初始化都应考虑，不能仅依赖重新初始化。
- 不自动清空数据库、删除 Docker 数据卷、重建向量索引或执行生产数据迁移。

## 6. AI 与检索相关约定

- 工具调用沿用服务注册与现有工具接口约定，避免在业务代码中硬编码服务地址。
- 修改 Prompt、工具定义、检索范围、融合与重排序逻辑时，检查引用来源、租户隔离和失败降级行为。
- 保持同步与流式输出在授权、错误处理和会话归属上的一致性。
- 不伪造模型响应、检索命中、外部指标或诊断结论；测试中的模拟数据应明确为测试夹具。
- 默认使用 Mock 验证外部模型和通知调用，避免测试产生真实费用或向外部发送通知。

### 6.1 知识入库（ingest）链路约定

适用于「上传 / 在线沉淀 / 内置加载 → 解析 → 切分 → 向量入库」全链路，核心组件位于 `knowledge/ingest/`、`knowledge/service/VectorIndexer`、`knowledge/loader/DocumentLoader`：

- 上传大小限制的唯一配置源是 `ops-agent.knowledge.upload.max-size-mb`（默认 50）；multipart 的 `max-file-size` / `max-request-size` 通过占位符引用它，不允许单独写死或出现两套口径。
- 文件扩展名 → docType 的唯一映射源是 `DocumentParserStrategyFactory.resolveDocType()`；新增文件类型时在映射表和对应解析策略中同步登记。
- 所有入口（用户上传、案例沉淀、内置知识库加载）统一走 `DocumentParserStrategyFactory` 的解析策略，不单独引入其他 Reader；Markdown 一律先做结构化切分（`MarkdownDocumentParserStrategy`），短文本被全部丢弃时再用纯文本切分兜底。
- 切分由 `VectorIndexer` 统一收口；解析后文本受 `ops-agent.knowledge.ingest.max-chars`（默认 1000 万字符）限制，超长必须显式拒绝，禁止用 `maxNumChunks` 之类参数静默截断。
- chunk 落库使用 `KnowledgeChunkMapper.insertBatch`（500 条/批），禁止逐条 `insert`；索引编排（清旧数据 → 切分 → 写分块 → 写向量）统一由 `KnowledgeDocumentIndexer` 完成，底层向量写入走 `VectorIndexer`。
- 索引必须异步执行：审核通过、案例确认等业务事务只调用 `KnowledgeIndexTaskService.enqueue()` 写入 `knowledge_index_task`，**禁止在业务事务内直接调用切分/embedding/向量写入**。分块与向量化由 `KnowledgeIndexTaskService` 的定时 worker 在独立事务中完成。
- 任务表并发约定：worker 用 `FOR UPDATE SKIP LOCKED` + 租约（默认 30 分钟，需覆盖大文档完整索引耗时）领取任务，多实例安全；同一文档只保留一条未完成任务（部分唯一索引 + UPSERT）；执行前校验文档 `reviewStatus=APPROVED` 且 `docVersion` 与入队时一致，否则跳过，执行后再次复核并回滚过期写入。
- 索引对同一 `vector_source` 幂等：每次执行先清空该文档旧分块与旧向量再重建；失败自动重试，默认 3 次后标记 FAILED，可通过任务表 `error_msg` 排查。
- 索引调度配置位于 `ops-agent.knowledge.index.poll-interval-ms`（默认 5000）与 `max-per-round`（默认 3）。
- 向量 source 口径：上传文档为 `upload:<docId>`，案例文档为 `case:<docId>`，内置文档为文件名；`DocumentLoader` 只清理内置文档，不得触碰 `upload:` / `case:` 前缀。
- 审核结论与索引可用性分离：`review_status` 只记录审核结论（DRAFT/PENDING_DEPT/PENDING_TENANT/APPROVED/REJECTED），`publish_status` 记录索引是否生效（UNPUBLISHED/BUILDING/FAILED/PUBLISHED）。审核通过仅置 `APPROVED + BUILDING` 并立即返回，worker 成功回写 `PUBLISHED + published_at`，重试耗尽置 `FAILED + publish_error`；索引失败不得回退 `review_status`。
- 审核通过 / 驳回事务内不再预删旧分块与旧向量；索引替换由 worker 的 `reindex` 整体幂等"先删后建"完成，避免失败后不可恢复。
- 检索过滤必须同时带 `reviewStatus == 'APPROVED'`（由 `RetrievalScope.filterExpression()` 统一追加），未通过 / 驳回文档即使存在残留向量也不可被命中。
- 索引状态运维接口：`GET /api/knowledge/documents/{id}/index-status` 轻量返回 publishStatus / publishError / 任务尝试次数供前端轮询；`POST /api/knowledge/documents/{id}/reindex` 仅对 `APPROVED` 且 `publish_status ∈ {FAILED, PUBLISHED}` 的文档重建索引，不改变审核结论。
- 新增环境需执行 `init-scripts/schema.sql` 中的 `knowledge_index_task` 建表语句；已运行环境升级时需单独执行该段增量 DDL（含部分唯一索引）。
- 已运行环境升级 `publish_status`：执行 `schema.sql` 中的幂等 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（publish_status / publish_error / published_at）、存量 `publish_status='PUBLISHED'` 回填，以及 `vector_store.metadata.reviewStatus` 回填段（`DO $$` + `to_regclass('vector_store')` 保护，新库初始化时可安全执行）。
- 解析健壮性：PDF 先落地临时文件再用 PDFBox 按页读取（不整份载入堆内存，临时文件必须 finally 删除）；TXT 按 UTF-8 BOM → UTF-16 BOM → 严格 UTF-8 → GB18030 的顺序探测编码；DOCX 解析已启用 zip bomb 防护（`ZipSecureFile.setMinInflateRatio`），调整 POI 相关配置时不得移除。
- 大文件演进方向：文件上限扩大到 100MB+ 前，需先将 `DocumentParserStrategy.parse(Resource): List<Document>` 升级为流式签名（产出端逐段产出、消费端逐段切分入库），趁实现与调用方较少时改造成本最低。

## 7. 常用命令

以下命令在项目根目录执行。Windows PowerShell 优先使用 Maven Wrapper：

```powershell
# 编译
.\mvnw.cmd -DskipTests compile

# 运行某个测试类（替换为实际测试类名）
.\mvnw.cmd "-Dtest=KnowledgeDocumentServiceTest" test

# 运行测试
.\mvnw.cmd test

# 与当前 CI 对齐的清理和测试
.\mvnw.cmd -B clean test

# 构建
.\mvnw.cmd package

# 启动应用（需要先准备本地配置和依赖服务）
.\mvnw.cmd spring-boot:run

# 启动本地依赖服务
docker compose up -d
```

已安装 Maven 时可将 `.\mvnw.cmd` 替换为 `mvn`；Linux/macOS 使用 `./mvnw`。启动前确认 JDK 21、数据库和所需环境变量就绪。不要为了通过测试关闭安全配置或绕过业务校验。

## 8. 测试与交付

- 修复缺陷时，优先添加能复现问题的回归测试。
- 业务修改至少验证正常路径、关键边界和失败路径；权限相关修改覆盖越权与跨租户场景。
- 先执行相关测试，再按影响范围运行完整测试；涉及构建配置或公共基础设施时执行相应构建检查。
- 断言 Reactor 流在 `doFinally` / `publishOn` 等异步线程中的副作用时，使用 `Mockito.timeout(...)` 等待，避免 StepVerifier 与回调线程的竞态导致偶发失败。
- JaCoCo 报告位于 `target/site/jacoco/`，Surefire 测试报告位于 `target/surefire-reports/`。
- 纯文档修改检查内容、路径和编码即可，无需为此运行完整后端测试。
- 交付前检查差异，避免引入凭据、构建产物或与任务无关的文件。
- 不自动提交、推送或发布；这些操作遵循用户明确要求。

## 9. 文档维护

项目结构、常用命令或开发约定变化时，同步更新本文。涉及依赖版本、API 与配置的描述，应以仓库当前文件为依据，避免复制过时说明。
