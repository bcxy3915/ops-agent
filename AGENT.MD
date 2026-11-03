# Ops Agent 项目协作指南

本文为在本项目中工作的 AI 编程助手与开发者提供上下文、开发约定和验证要求。适用范围为当前后端项目目录。

前端为同级独立仓库 `../ops-agent-frontend/`，其约定见 `../ops-agent-frontend/AGENT.MD`。涉及跨仓库需求时分别检查两边的 Git 状态、接口实现和验证结果；仅修改后端时不要顺带修改前端。

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
- `docs/`：设计及版本相关资料。

定位时注意：业务代码按领域分包，配置类位于各模块的 `config/`，不存在统一的顶层 `controller/`、`service/` 或 `config/`。部分历史测试包名与实现包名不一致（例如 `service/OpsAgentServiceTest` 对应 `chat/service/OpsAgentService`），应按类名搜索，不要仅按目录推断。

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
- Spring Boot 4 默认 Jackson 3，但当前项目通过 `common/config/JacksonConfig` 与 `spring.http.converters.preferred-json-mapper: jackson2` 保持 Jackson 2；现有 DTO、脱敏序列化器使用 `com.fasterxml.jackson.*`。调整序列化配置时同步验证日期格式、未知请求字段兼容和敏感字段脱敏，不要单独切换 mapper 或混用两代注解。

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

### 5.1 异步上下文与数据隔离

- 租户、部门、用户及认证信息由 `rbac/context/` 与 Spring Security 上下文承载。跨线程前捕获 `RbacContextSnapshot`，执行时恢复，结束时清理；线程池沿用 `common/config/ContextPropagatingTaskDecorator`，不要假设 ThreadLocal 会随 `@Async`、Reactor 或线程池自动传播。
- SSE 延迟订阅、异步消息持久化、定时诊断和索引 worker 都应检查实际执行线程的数据范围。按租户执行的系统任务可参考 `RbacContextSnapshot.forTenant()`；禁止把上一条任务的上下文遗留给下一条。
- 数据查询范围优先复用 `rbac/service/DataScope`；知识检索同时检查向量过滤与 BM25 过滤。`RetrievalScope.deptIds` 的 `null` 表示同租户全部部门，空集合表示无部门可见，二者不得互换。
- 新增方法权限沿用现有 `@PreAuthorize` 与权限码；前端隐藏按钮不能替代后端校验。认证白名单和 ASYNC dispatch 放行规则以 `security/config/SecurityConfig` 为准，不能为解决 SSE 错误而放开业务路径。

## 6. AI 与检索相关约定

- 工具调用沿用服务注册与现有工具接口约定，避免在业务代码中硬编码服务地址。
- 修改 Prompt、工具定义、检索范围、融合与重排序逻辑时，检查引用来源、租户隔离和失败降级行为。
- 保持同步与流式输出在授权、错误处理和会话归属上的一致性。
- 聊天记忆以 `ops_message` 为唯一事实来源：内存 `ChatMemory`（`MessageWindowChatMemory`，窗口 20 条）只作缓存。调用模型前若 `chatMemory.get(sessionId)` 为空（进程重启、请求落到另一实例、冷会话），必须经 `ConversationService.loadRecentMessages()` 回填，且必须在 `saveUserMessage` 之前执行，禁止把当前问题重复注入记忆。
- 回填历史按自增 `id` 正序排列，不按 `createdAt`：助手消息是异步落库的，时间戳可能与用户消息乱序。
- 禁止只依赖进程内记忆实现多轮能力；多实例部署下靠 DB 回填保持一致，如需跨实例强一致再演进为共享 `ChatMemoryRepository`。
- 不伪造模型响应、检索命中、外部指标或诊断结论；测试中的模拟数据应明确为测试夹具。
- 默认使用 Mock 验证外部模型和通知调用，避免测试产生真实费用或向外部发送通知。

流式接口契约以 `chat/service/OpsAgentService.askStreamWithCitations()` 与前端 `src/api/chat.js` 为准：当前成功流依次输出答案 token、`event: citations`（引用列表 JSON）、`data: [DONE]`。该方法旧 Javadoc 仍写着“引用先发”，不能据此修改调用方。SSE 不套普通 `ApiResponse`；调整事件顺序、结束标记、取消或失败行为时同步核对前端解析与消息持久化测试。

前端当前使用 `POST /api/ops/ask/stream`，请求体为 `{ question, sessionId }`，通过 `fetch` 携带 Bearer 请求头并解析事件。保留长问题走请求体的行为，避免中文 URL 编码膨胀导致请求头超限；不要将 JWT 放入 URL。

### 6.1 知识入库（ingest）链路约定

适用于「上传 / 在线沉淀 / 内置加载 → 解析 → 切分 → 向量入库」全链路，核心组件位于 `knowledge/ingest/`、`knowledge/service/VectorIndexer`、`knowledge/loader/DocumentLoader`：

- 单文件上限的配置源是 `ops-agent.knowledge.upload.max-size-mb`（默认 50），multipart 的 `max-file-size` 引用它；请求总大小由 `ops-agent.knowledge.upload.max-request-mb`（默认 55）控制，multipart 的 `max-request-size` 引用后者，须留出表单字段开销。调整时同步核对两项，不允许在 multipart 配置中单独写死。
- 文件扩展名 → docType 的唯一映射源是 `DocumentParserStrategyFactory.resolveDocType()`；新增文件类型时在映射表和对应解析策略中同步登记。
- 所有入口（用户上传、案例沉淀、内置知识库加载）统一走 `DocumentParserStrategyFactory` 的解析策略做文本抽取，不单独引入其他 Reader。其中内置加载的 Markdown 直接读原文（保留标题结构交给切分策略），上传的 Markdown 由 `MarkdownDocumentParserStrategy` 抽取纯文本后存入 `raw_content`。
- 切分由 `VectorIndexer` 统一收口；解析后文本受 `ops-agent.knowledge.ingest.max-chars`（默认 1000 万字符）限制，超长必须显式拒绝，禁止用 `maxNumChunks` 之类参数静默截断。
- 切分策略位于 `knowledge/ingest/splitter`（策略模式，设计文档 `docs/design/text-split/`）：`TextChunkStrategy` 接口（name / order / supports / split）+ `TextChunkStrategyResolver` 四级路由——`strategyOverride` 显式指定 → docType 覆盖配置 → `supports` 自然匹配 → `default-strategy`；未知名与策略异常一律降级 `token` 兜底，切分永不失败。新增策略须登记唯一 name 并保持 order 语义（小者优先）。
- 路由现状：MARKDOWN → `markdown-structural`（自研 ATX 标题行扫描，不依赖 `MarkdownDocumentReader` 行为；headingPath 复用 `metadata.section`，零 DDL）；TXT / PDF / DOCX → `recursive`（段落→行→句终分隔符阶梯，冒号不切；围栏代码块与表格行是原子单元；相邻块携带整句 overlap；尾块不足 minChunkChars 并入前块）；`token` 仅作兜底（包装旧 `TokenTextSplitter` 口径）。
- 策略四条不变量由单测强制：块按序拼接（去相邻重叠）与原文完全一致、不产空白块、每块不超 `max-chunk-tokens`、输出块携带输入文档全部 metadata。修改切分算法须运行 `RecursiveBoundarySplitterTest`、`MarkdownStructuralSplitterTest` 和 `TextChunkStrategyResolverTest`。
- 切分配置唯一来源 `ops-agent.knowledge.split.*`（`application.yml`）。回退旧 TokenTextSplitter 口径：`default-strategy: token` + `markdown.strategy: token`，不要通过改代码回退。
- 每个向量块自动打 `splitStrategy`（策略名）与 `splitVersion`（算法代次，当前 2）标记，供 A/B 评测隔离、检索排查与存量重索引识别；调整算法代次时同步更新 `TextChunkStrategy.CURRENT_SPLIT_VERSION`。
- 展示与检索文本分离：`knowledge_chunk.content` 存原始块文本（前端展示）；embedding 文本在 `KnowledgeDocumentIndexer` 处加「【标题｜章节路径】」上下文头（`keep-heading-path` 控制），两者不得混用。
- 新旧策略 A/B 评测入口 `SplitEvalIT`（`.\mvnw.cmd -Dtest=SplitEvalIT test`，surefire 默认不含 *IT）：评测数据集放 `src/test/resources/splitter/eval/`（documents/ + qa-golden.json），缺失时自动跳过；需本地 PG + `DASHSCOPE_API_KEY`，报告输出至 `docs/design/text-split/eval/`，gate 判定见设计文档 §10.3。
- chunk 落库使用 `KnowledgeChunkMapper.insertBatch`（500 条/批），禁止逐条 `insert`；索引编排（清旧数据 → 切分 → 写分块 → 写向量）统一由 `KnowledgeDocumentIndexer` 完成，底层向量写入走 `VectorIndexer`。
- 索引必须异步执行：审核通过、案例确认等业务事务只调用 `KnowledgeIndexTaskService.enqueue()` 写入 `knowledge_index_task`，**禁止在业务事务内直接调用切分/embedding/向量写入**。分块与向量化由 `KnowledgeIndexTaskService` 的定时 worker 在独立事务中完成。
- 任务表并发约定：worker 用 `FOR UPDATE SKIP LOCKED` + 租约（默认 30 分钟，需覆盖大文档完整索引耗时）领取任务，多实例安全；同一文档只保留一条未完成任务（部分唯一索引 + UPSERT）；执行前校验文档 `reviewStatus=APPROVED` 且 `docVersion` 与入队时一致，否则跳过，执行后再次复核并回滚过期写入。
- 索引对同一 `vector_source` 幂等：每次执行先清空该文档旧分块与旧向量再重建；失败自动重试，默认 3 次后标记 FAILED，可通过任务表 `error_msg` 排查。
- 索引调度配置位于 `ops-agent.knowledge.index.poll-interval-ms`（默认 5000）与 `max-per-round`（默认 3）。
- 向量 source 口径：上传文档为 `upload:<docId>`，案例文档为 `case:<docId>`，内置文档为文件名；`DocumentLoader` 只清理内置文档，不得触碰 `upload:` / `case:` 前缀。
- 内置知识必须同时落两层：向量（`vector_store`）+ 元数据（`knowledge_document`，`layer=BUILTIN`、`tenant_id=0`、`visibility=PUBLIC`、`review_status=APPROVED`、`publish_status=PUBLISHED`、`trust_status=VERIFIED`、`owner=system`、`source=文件名`、`file_path=classpath:knowledge/<文件名>`）。`DocumentLoader.syncBuiltinDocuments()` 在每次 reload 时增量新增/刷新/清理这些行；只写向量不写元数据会导致总览、文档列表、层级计数全部为 0。
- 审核结论与索引可用性分离：`review_status` 只记录审核结论（DRAFT/PENDING_DEPT/PENDING_TENANT/APPROVED/REJECTED），`publish_status` 记录索引是否生效（UNPUBLISHED/BUILDING/FAILED/PUBLISHED）。审核通过仅置 `APPROVED + BUILDING` 并立即返回，worker 成功回写 `PUBLISHED + published_at`，重试耗尽置 `FAILED + publish_error`；索引失败不得回退 `review_status`。
- 审核通过 / 驳回事务内不再预删旧分块与旧向量；索引替换由 worker 的 `reindex` 整体幂等"先删后建"完成，避免失败后不可恢复。
- 检索过滤必须同时带 `reviewStatus == 'APPROVED'`（由 `RetrievalScope.filterExpression()` 统一追加），未通过 / 驳回文档即使存在残留向量也不可被命中。
- 索引状态运维接口：`GET /api/knowledge/documents/{id}/index-status` 轻量返回 publishStatus / publishError / 任务尝试次数供前端轮询；`POST /api/knowledge/documents/{id}/reindex` 仅对 `APPROVED` 且 `publish_status ∈ {FAILED, PUBLISHED}` 的文档重建索引，不改变审核结论。
- 上传「直接发布」走独立接口 `POST /api/knowledge/documents/{id}/publish-direct`（需 `knowledge:publish` 或 `knowledge:admin`，且限作者或知识管理员）：仅接受 `review_status ∈ {DRAFT, REJECTED}`，跳过审核直接置 `APPROVED + BUILDING` 并入队。旧 `/publish` 只接受 `PENDING_DEPT / PENDING_TENANT`，不得用于草稿，否则会报"当前文档不处于待审核状态"并让文档停留在待审核。
- 前端展示须以 `publishStatus` 为准：审核通过后 `trustStatus` 立即变为 `VERIFIED`，但索引可能仍为 `BUILDING` 或 `FAILED`；列表必须展示索引状态，`FAILED` 时提供「重试入库」入口，存在 `BUILDING` 行时按 4s 轮询 `index-status`，全部落定后停止。
- `publishStatus = BUILDING` 期间文档只读：服务端 `update / rollback / delete` 统一经 `ensureNotBuilding` 拒绝（错误码 `KNOWLEDGE_DOCUMENT_INVALID`），前端操作列只保留查看 / 检索命中 / 更新日志；不要为了"元数据不影响索引"放开编辑，版本并发会让索引任务回滚或产生新旧向量重叠。
- 任务领取 `KnowledgeIndexTaskMapper.claimOne` 使用 `@Select` 而非 `@Update`：MyBatis 的 `@Update` 只允许 `int/void` 返回类型，而该语句通过 `UPDATE ... RETURNING t.*` 返回整行实体；改成 `@Update` 会让调度每轮报 "unsupported return type" 且任务永远停留在 PENDING。

  注意该异常的抛出点在 SQL 执行之后：旧的 `@Update` 版本会先把任务置为 `RUNNING` 提交，再抛异常，留下没有 worker 处理的孤儿任务。`claimOne` 只领取 `PENDING`，孤儿任务要等租约（默认 30 分钟，`ops-agent.knowledge.index.lease-minutes`）到期后由 `recoverStale` 置回 `PENDING`；排查"任务没被执行"时先看 `status` 是否卡在 `RUNNING`、`lease_until` 是否未到期，必要时手工回收。
- 新增环境需执行 `init-scripts/schema.sql` 中的 `knowledge_index_task` 建表语句；已运行环境升级时需单独执行该段增量 DDL（含部分唯一索引）。
- 已运行环境升级 `publish_status`：执行 `schema.sql` 中的幂等 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（publish_status / publish_error / published_at）、存量 `publish_status='PUBLISHED'` 回填，以及 `vector_store.metadata.reviewStatus` 回填段（`DO $$` + `to_regclass('vector_store')` 保护，新库初始化时可安全执行）。
- 解析健壮性：PDF 先落地临时文件再用 PDFBox 按页读取（不整份载入堆内存，临时文件必须 finally 删除）；TXT 按 UTF-8 BOM → UTF-16 BOM → 严格 UTF-8 → GB18030 的顺序探测编码；DOCX 解析已启用 zip bomb 防护（`ZipSecureFile.setMinInflateRatio`），调整 POI 相关配置时不得移除。
- 大文件演进方向：文件上限扩大到 100MB+ 前，需先将 `DocumentParserStrategy.parse(Resource): List<Document>` 升级为流式签名（产出端逐段产出、消费端逐段切分入库），趁实现与调用方较少时改造成本最低。

## 7. 告警与诊断闭环约定

当前已实现的主链路为：`MetricSampler` 采样并持久化 → `AlertEvaluator` 评估启用规则 → 持续命中判断、去重与压制 → `alert_event` 落库 → `FailureSnapshotService` 保存现场 → `DiagnosisService` 结合知识检索生成诊断 → `NotificationCenter` 通知；人工处置、维护窗口、自动升级与通知重试构成配套闭环。修改这条链路时同步检查 `alert/`、`metrics/`、`snapshot/`、`diagnosis/`、`notification/`、`maintenance/` 和 `oncall/` 的调用关系。

### 7.1 采样、规则评估与压制

- 指标采样由 `MetricSampler` 每 10 秒执行一次，首次延迟 5 秒；告警评估读取 `MetricSampleStore` 中的持久化采样，不直接调用模型或重新拉取实时指标来决定是否触发。
- `AlertEvaluator.evaluate()` 默认每 30 秒执行，配置为 `ops-agent.alert.evaluate-cron`；先处理维护窗口到期，再逐条评估启用规则。每条规则在其租户的 `RbacContextSnapshot` 中执行，单条失败不阻断其他规则。
- 比较模式为 `THRESHOLD`（阈值）、`YOY`（24 小时前基线变化率）和 `SIGMA`（最近 1 小时统计偏差）。YOY 基线缺失或接近 0、SIGMA 样本不足 5 个或标准差接近 0 时不命中；调整算法时覆盖这些边界与上下方向。
- 命中须满足 `durationSeconds`（未配置时 60 秒），按 `ruleId` 记录连续命中起点；同规则已有 `FIRING` 事件时不重复触发。当前不命中或无最新采样时会清理持续状态，并恢复该规则最新 `FIRING` 事件；不要将这一实现描述为自动恢复所有未关闭事件。
- 触发顺序保持为：持续时长 → `FIRING` 去重 → 维护窗口 → 规则静默期 → 服务每小时节流 → `FIRING` 落库与闭环。规则静默及节流压制仍以 `SILENCED` 留痕，不进入快照、诊断和通知链路。
- `AlertThrottleService` 的规则静默按 `ruleId` 计时，服务节流按 `tenantId + service` 隔离，当前上限为每小时 50 条。连续命中状态与节流状态均在进程内、重启清零；修改部署或并发策略时检查这些状态，不将其视为分布式锁或跨实例精确计数。

### 7.2 告警状态、已读与维护窗口

- 事件状态为 `FIRING / ACKNOWLEDGED / SILENCED / RESOLVED`。确认与认领会记录 `acknowledgedBy / acknowledgedAt`，将非恢复事件转为已确认；手动恢复记录恢复人、时间与备注。操作入口统一走 `AlertEventService`，列表、详情与批量操作复用 `AlertScope` 的数据权限。
- **已读不等于已确认或已处理**：已读记录位于 `user_alert_read`，按租户和用户隔离；有效已读条件为 `readAt >= firedAt`。未读计数包含 `FIRING / ACKNOWLEDGED / SILENCED`，标记已读不能改变事件状态或停止升级。
- 人工静默保留首次 `silencedFrom`，到期时间范围为当前时间后 15 分钟至 7 天；解除或到期恢复到静默前状态（缺失时默认 `FIRING`）。静默到期恢复由 `AlertEscalationService.scanAndEscalate()` 调用，即使没有启用升级规则也会先执行。
- 维护窗口按租户与服务匹配；创建窗口同时压制存量未恢复告警。维护期新命中事件以 `SILENCED` 落库，`silencedReason` 使用 `MaintenanceWindowService.SILENCE_REASON_PREFIX`（`维护窗口：`）标记，不通知、不升级。不要将该来源标记与普通人工静默混用。
- 计划到期由 `AlertEvaluator` 调用 `expireDueWindows()` 识别，再重新评估维护压制事件：仍异常则转 `FIRING` 并执行快照、诊断、通知；规则删除、停用或已不命中则转 `RESOLVED`。维护到期重评估与普通静默回原状态是两种恢复路径，修改时分别验证。

### 7.3 故障快照、智能诊断与通知

- `AlertEvaluator.afterFiring()` 当前顺序执行“快照 → 诊断 → 通知”，各环节独立捕获失败；快照失败则跳过诊断，诊断失败仍发送基础告警通知，不允许模型故障导致告警事件丢失或通知被整体取消。
- `FailureSnapshotService.buildAndSave()` 按事件查询已有快照并复用，保存告警、服务信息与状态、指标窗口和审计数据。事件、快照、诊断及通知记录须保留 `alertEventId / ruleId / tenantId / deptId` 关联，不按服务名重新猜测归属。
- `DiagnosisService` 从快照构建检索词，通过 `DiagnosisScope.of(snapshot).retrievalScope()` 限定知识范围，结构化输出根因、证据、置信度、建议与摘要；记录 `SUCCESS / FAILED`、错误信息、`durationMs` 与 `referencesJson`。检索失败可降级为仅基于快照事实诊断，不能伪造引用。
- 诊断调用使用独立的 `diagnosis-...` 会话 ID，避免混入聊天记忆；手动诊断入口为 `POST /api/diagnoses/snapshot/{snapshotId}`，追问为 `POST /api/diagnoses/{id}/follow-up`，追问新增记录并继承事件与快照关联，不能覆盖原诊断。
- 通知统一经 `NotificationCenter` → `NotificationTemplateService` → `NotifierRegistry` 分发，当前渠道类型为 `EMAIL / FEISHU_WEBHOOK / GENERIC_WEBHOOK`。发送事件所属租户的启用渠道，只有 `SUCCESS` 诊断才附加诊断摘要；新增渠道沿用 `Notifier` 扩展点。
- 初次通知默认解析本租户 `PRIMARY` 值班人，空则尝试 `ADMIN`；用户 ID / 历史 username 解析邮箱时必须附带租户条件，并通过现有 AES 工具解密。动态邮箱优先，未解析到时 EMAIL 使用渠道配置收件人兜底，Webhook 按渠道配置投递。
- 通知记录先写 `PENDING`，发送后写 `SUCCESS / FAILED`。失败由 `NotificationRetryTask` 扫描 `FAILED`、未达最大次数且已到重试时间的记录；配置为 `ops-agent.notification.max-retry`（默认 2）、`retry-delay-sec`（默认 300）与 `retry-cron`（默认每 5 分钟），每轮最多 100 条。通知失败与告警状态相互独立，不把发送失败改写为告警恢复。

### 7.4 升级与验证

- `AlertEscalationService` 默认每 30 秒扫描，配置为 `ops-agent.alert.escalation-cron`；仅对启用且开启升级的规则，读取 `alert_rule_escalation_level` 多级路径，处理 `FIRING / ACKNOWLEDGED`，跳过静默与已恢复事件。
- 当前 `UNACKNOWLEDGED` 匹配 `FIRING` 并从 `firedAt` 计时；`UNRESOLVED` 匹配 `ACKNOWLEDGED` 并从 `acknowledgedAt`（缺失时用 `firedAt`）计时。每轮每事件最多升到一个到期级别，更新事件级别、写 `alert_escalation` 和审计日志，再通知；通知失败不回退升级记录。
- 升级实际通知经 `NotificationCenter` 按级别解析值班人：L1 为 `BACKUP`，L2 及以上为 `ADMIN`。规则的 `toUsers / toRole` 也用于升级记录及审计收件人描述；调整目标配置时同时核对实际投递路径，不能只依据审计文字断言邮件收件人。
- 修改本链路时按影响范围运行 `MetricSamplerTest`、`AlertEvaluatorTest`、`AlertThrottleServiceTest`、`AlertEventServiceTest`、`AlertReadServiceTest`、`AlertEscalationServiceTest`、`MaintenanceWindowServiceTest`、`OncallMemberServiceTest`、`FailureSnapshotServiceTest`、`DiagnosisServiceTest` 与 `NotificationCenterTest`，涉及数据权限或接口时补跑对应 Scope / Controller 测试。
- 关键回归场景包括：持续命中与恢复、三种比较模式、去重和节流、维护压制与到期重评估、已读不改变状态、人工静默回原状态、未确认与未恢复升级、跨租户同名服务和值班人隔离、快照或模型失败仍通知、通知失败重试。模型、SMTP 和 Webhook 使用 Mock，不因验证文档或单测发送真实通知。

## 8. 常用命令

### 8.1 本地配置与依赖准备

- 应用默认启用 `local` profile；本地覆盖文件应放在 `src/main/resources/application-local.yml`。只读取完成任务所需的配置，不在工具输出或交付说明中展开真实密钥。
- `.env.example` 是 Docker Compose 的数据库变量模板，`.env` 由 Compose 读取；Spring Boot 不会自动把该文件加载为进程环境变量。启动 Java 时需通过 IDE、当前终端环境变量或本地 YAML 单独提供配置。
- 默认 PostgreSQL 为 `localhost:5433/opsdb`，用户 `postgres`；应用端口为 `8080`。Compose 的 `DB_PORT`、`DB_NAME`、`DB_USER` 仅改变容器配置，修改后还须同步应用的 `spring.datasource.*`，不能假设自动联动。
- Compose 只启动 PostgreSQL（含 Pgvector），不启动后端、前端或示例业务服务。`init-scripts/` 挂载到 PostgreSQL 初始化目录，仅在数据目录首次初始化时自动执行；已有 `ops_pgdata` 数据卷不会因修改 SQL 或重启容器而重跑脚本。

| 环境变量 | 对应配置 / 用途 |
| --- | --- |
| `DB_PASSWORD` | `spring.datasource.password`；也供 Compose 使用，Java 进程需单独可见 |
| `AI_API_KEY` | 对话模型 API Key；当前默认 DeepSeek 兼容接口 |
| `DASHSCOPE_API_KEY` | 百炼 embedding API Key；当前模型 `text-embedding-v3`、向量维度 1024 |
| `JWT_SECRET` | JWT 签名密钥，按 UTF-8 字节使用，至少 32 字节 |
| `OPS_AES_KEY` | AES-256-GCM 密钥，Base64 编码的 32 字节密钥 |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | 邮件通知配置；未配置时默认不做邮件健康检查 |
| `OPS_CORS_ALLOWED_ORIGINS` | 逗号分隔的前端来源白名单 |
| `LOG_LEVEL` | 根日志及业务包默认日志级别（默认 INFO） |

模型、embedding、向量维度与 Pgvector 索引需保持一致；切换 embedding 模型前评估已有向量的兼容与重建方案。默认重排序关闭（`ops-agent.retrieval.rerank.enabled: false`），不可仅凭 README 假设已启用。

### 8.2 开发与验证命令

以下命令在项目根目录执行。Windows PowerShell 优先使用 Maven Wrapper：

```powershell
# 编译
.\mvnw.cmd -DskipTests compile

# 运行某个测试类（替换为实际测试类名）
.\mvnw.cmd "-Dtest=KnowledgeDocumentServiceTest" test

# 运行切分策略相关测试（PowerShell 下多个类名放在同一引号内）
.\mvnw.cmd "-Dtest=RecursiveBoundarySplitterTest,MarkdownStructuralSplitterTest,TextChunkStrategyResolverTest" test

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

启动后可用 `GET /actuator/health` 验证基础健康状态，Swagger UI 位于 `/swagger-ui.html`，OpenAPI JSON 位于 `/v3/api-docs`。健康检查通过不代表模型、检索及通知链路均可用，应按本次需求验证对应功能。

## 9. 测试与交付

- 修复缺陷时，优先添加能复现问题的回归测试。
- 业务修改至少验证正常路径、关键边界和失败路径；权限相关修改覆盖越权与跨租户场景。
- 先执行相关测试，再按影响范围运行完整测试；涉及构建配置或公共基础设施时执行相应构建检查。
- 断言 Reactor 流在 `doFinally` / `publishOn` 等异步线程中的副作用时，使用 `Mockito.timeout(...)` 等待，避免 StepVerifier 与回调线程的竞态导致偶发失败。
- JaCoCo 报告位于 `target/site/jacoco/`，Surefire 测试报告位于 `target/surefire-reports/`。
- 当前 CI 在 JDK 21 下执行 `mvn -B clean test`，未启动数据库服务，也未配置独立覆盖率门槛。不要把历史用例数量或覆盖率写成固定验收指标，以本次报告为准。
- `SplitEvalIT` 不在 Surefire 默认测试命名范围内，`test` / `package` 不代表已跑真实 embedding 评测；需要时显式指定 `"-Dtest=SplitEvalIT"`。它会访问真实模型、写入和清理评测向量，应使用专用本地测试库，并在交付时区分通过、跳过和未运行。
- 纯文档修改检查内容、路径和编码即可，无需为此运行完整后端测试。
- 交付前检查差异，避免引入凭据、构建产物或与任务无关的文件。
- 不自动提交、推送或发布；这些操作遵循用户明确要求。

## 10. 文档维护

项目结构、常用命令或开发约定变化时，同步更新本文。涉及依赖版本、API 与配置的描述，应以仓库当前文件为依据，避免复制过时说明。

- 本文维护长期协作规则、关键契约和可执行命令；临时排查记录、完整评测结果与任务进度放入对应专题资料，避免将一次性现象固化为通用规则。
- 前后端契约变化时同时检查两边的 `AGENT.MD` 与相关实现；发现注释、README 与代码冲突时，在本次修改范围内明确依据和待同步位置。
