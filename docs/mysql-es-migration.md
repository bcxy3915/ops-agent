# MySQL 与 Elasticsearch 迁移交付说明

日期：2026-11-09。

## 当前架构

- MySQL 8.4：业务事实、Markdown 原文、分块、权限、对话、索引任务和投递记录。
- Elasticsearch 8.19.6：向量与 BM25 检索，默认 1024 维 cosine；本地端口 9200。
- MySQL 本地端口 23306，数据库 opsdb。PostgreSQL 5433 保留，Compose 通过 legacy profile 管理，不再是当前应用的数据源。
- LangChain4j 负责模型、向量生成及既有 Agent 能力。原 PG 存储适配器已删除，ES 适配器统一处理向量和 BM25。

## 初始化与已有环境

1. 在 `.env` 配置 DB_PASSWORD，执行 `docker compose up -d mysql elasticsearch`。
2. MySQL 空数据卷自动执行 `init-scripts/mysql/schema.sql` 与 `seed.sql`，包含 32 张原有业务表和 1 张检索投影投递表。
3. 执行 `python scripts/initialize-elasticsearch.py` 创建模板、索引、读写别名；重复执行保留已有索引和别名指向。
4. 本地 application-local.yml 配置 MySQL 23306，与 `.env` 使用相同密码；其余模型与邮件配置保留。应用不会自动读取 Compose 的 `.env`，通过环境变量启动时需自行注入。
5. 已执行早期 MySQL 建表脚本的环境，需要按实际版本执行 `init-scripts/mysql/migrations/`。根目录 PostgreSQL SQL 保留供旧版本使用，不应再挂载到 MySQL。

ES 在本地仅绑定 127.0.0.1，关闭认证；远程部署必须另行启用认证与 TLS。当前映射使用无插件 standard 分词器，中文检索质量应以实际问题集评估，不宣称已完成全文质量基准。

## 一致性如何实现

`knowledge_search_projection` 同时承担有效检索投影和持久化投递任务。业务事务只修改 MySQL；ES 不参与或伪装成本地事务。

每次重建产生新的分块 ID，旧投影登记 deleted 墓碑，新正文、分块及新投影在同一事务提交。向量生成失败时整次回滚；提交成功后同步器逐批 Bulk 写入 ES。临时向量仅用于投递，ES 确认可见后清空 MySQL 中的 embedding 载荷。

同步器通过 FOR UPDATE SKIP LOCKED 领取投影，在有限批量和请求超时内持有行锁，防止旧 ES 写入覆盖并发删除。Bulk 逐项确认，部分失败保留整批待投递并退避重试；进程崩溃或 SQL 提交失败时重复投递使用稳定 ID，因此不会产生重复文档。删除 404 视为成功，删除墓碑保留用于后续对账。

只有全部有效分块投递成功且没有待执行/执行中的分块任务，文档才从 BUILDING 进入 PUBLISHED。发布状态在投递事务提交后单独更新，避免与索引事务反向加锁。重建期间文档暂不可检索；失败会保留事务提交前的数据，但不会绕过当前发布状态继续输出旧内容。这是当前实现的可用性边界，不是无缝版本切换。

向量查询在 knn.filter 内先执行 ACL，BM25 使用同一过滤条件。候选返回后通过 MySQL 核验投影未删除、文档存在、审核和发布状态以及最新租户/部门/用户权限。ES 删除或权限投影延迟时，旧候选不能进入模型上下文。扩大候选池后仍受预算限制，极端残留情况下可能少召回，需要持续监控投递积压。

当前采用自定义 ES HTTP 适配器承载 Bulk、ACL 预过滤和数据库复核，不依赖 LangChain4j 内置 ElasticsearchEmbeddingStore 的默认元数据映射。LangChain4j Elasticsearch 依赖已替换 PG 依赖；真实存储协议测试针对本项目适配器和 ES 8.19.6，不代表已验证该依赖全部高级 API。

索引任务通过 MySQL 事务领取、生成列活动任务唯一约束及 claim_token 阻止旧 worker 回写。按文档、任务顺序加锁，索引和编辑删除串行化。当前生成向量仍占用单个文档的索引事务，大文档需要评估锁等待；ES 投递已从该事务拆出。

## 旧数据工具

`scripts/migrate-local-postgres-to-mysql.py` 默认仅预检，`--execute` 才导入。工具使用源 PG 只读 REPEATABLE READ 快照，先保存源快照和新库备份，再导入目标事务。

目标必须仍是初始化状态：若已有非内置文档、聊天、告警等业务记录则拒绝覆盖。`--discard-generated-metrics` 仅用于确认由本次启动验证生成的临时指标，不可用于已有业务环境。工具不删除旧 PG，不复制旧实例活动问答租约，未完成索引任务恢复为待执行。

本次源快照包含：10 篇文档、223 个分块、4 个会话、14 条消息、15485 条指标；191 条已有向量直接复用。审核通过但缺失向量的文档另行入队重建。备份、导入 SQL、统计位于 `.runtime/migration-*`，包含业务数据，不纳入 Git。

源库未被强制停写；快照不是持续同步机制。最终业务切换前需停止旧应用写入并核对快照后的增量。新库开放写入后不能直接切回旧 PG，否则会丢失新增业务状态。

## 验证范围

常规测试：

```powershell
.\mvnw.cmd clean test
```

真实存储测试要求 MySQL/ES 已启动，DB_PASSWORD 通过环境变量提供：

```powershell
.\mvnw.cmd "-Dstorage.live-test=true" "-Dtest=StorageMigrationIT,ChatTurnMySqlIT,KnowledgeTaskMySqlIT" test
```

本机 Oracle JDK 21.0.12.1 的 Unix socket 回环存在异常。验证时临时添加 `-Djdk.net.unixdomain.tmpdir=C:/codex-no-unix-socket-directory`，使 JDK 自动回退 TCP 回环；该目录保持不存在。正常 JDK 环境不需要此参数，不将其加入业务默认配置。

本次通过 1066 项常规测试和 7 项真实 MySQL/ES 集成测试，均无失败、错误或跳过。

已验证的真实链路包括登录和权限、内置 BM25、在线 Markdown 原文与分块、异步发布、混合检索、跨租户隔离、删除后不可检索、ES 故障重试、事务回滚、双实例会话租约、过期及删除重建代次保护、索引任务旧令牌隔离、真实模型 SSE 引用与结束事件，以及应用重启后从 MySQL 回填记忆并准确回忆之前的验证标识。

服务、告警事件、规则、诊断、通知渠道/记录、维护窗口、值班、会话列表完成接口读取验证。未在本次迁移验证中重新触发真实邮件投递、完整故障告警闭环或执行自动部署/回滚；不能把读取成功当作这些操作已完成端到端验收。

## 后续运行注意

- 投影重试无限保留并退避，运维需关注 PENDING 数量、attempts、next_retry_at、last_error。文档未发布时也应检查 ES 投递状态，不只看分块任务 SUCCESS。
- 墓碑和检索正文目前保留，不自动清理；制定保留期前需结合备份与 ES 对账策略。
- ES 索引丢失后不能仅将所有 SYNCED 行改成 PENDING：已清空临时 embedding，应通过文档重新索引生成向量，再重建 ES。
- MySQL 自增 ID、密码哈希、加密字段保持；原本地加密密钥必须保留。JSON handler 已改为字符串绑定，租约和任务调度使用数据库 UTC 时钟，旧无时区业务字段按原字面值保留。
