# LangChain4j 迁移执行记录：W1 基线与 W2 隔离 PoC

日期：2026-11-07。分支：`migration/langchain4j`。迁移起点：`1d1a331b5262bea26921488af46fa62e7ee323ab`。

## 1. 当前进度

按《运维智能体后端开发周计划 v4》推进，不把相对周次理解为每次必须等待一周。

- W1：代码依赖盘点、协议及配置冻结、回归矩阵、只读夹具和本地测试基线已建立。真实模型质量、延迟、Token 消耗及真实数据库检索指标尚未测量，W1 的线上效果基线仍待补充。
- W2：完成隔离的本地 OpenAI 协议 PoC，验证同步、流式、结构化 DTO、连续两轮工具调用。尚未通过 W2 完整阶段门。
- W3 以后：未实施；生产聊天、诊断和 RAG 仍使用 Spring AI。未引入 LangGraph4j、MCP 或生产写工具。

## 2. 迁移影响矩阵

`baseline-inventory.json` 列出实际扫描得到的 35 个主代码文件、19 个测试文件及关键配置/代码 SHA-256。仅扫描 `org.springframework.ai` 的直接引用，不代表间接调用方只有这些文件；JSON 不含本地密钥配置。

| 范围 | 当前 Spring AI 耦合 | 迁移安排 | 主要回归依据 |
| --- | --- | --- | --- |
| `chat/config/AiConfig`、`ChatMemoryConfig` | ChatClient、ChatModel、Advisor、20 条消息窗口 | W3 边界、W4 模型、W5 记忆 | OpsAgentServiceTest、ConversationServiceTest |
| `chat/service/OpsAgentService` | PromptTemplate、ChatClient、Message、Document、工具注册 | W3–W6；RAG 先过渡适配 | 同步问答、SSE、引用、冷启动回填、取消保存 |
| `chat/controller/ChatController` | BM25 测试接口直接读取 Document；普通问答也间接依赖 | 保持 HTTP 契约，后续换业务 DTO | ChatControllerTest；后续需 HTTP 鉴权联调 |
| `tools/` 四工具 | Tool / ToolParam 注解，结果为中文字符串 | W5 迁移绑定，W7 结构化观察 | HealthCheckToolsTest、MetricQueryToolsTest、MetricDiscoveryToolsTest、ServiceDiscoveryToolsTest |
| `diagnosis/service/DiagnosisService`、`DiagnosisPromptTemplate` | ChatClient 结构化输出及 PromptTemplate | W4 DTO 与模型适配 | DiagnosisServiceTest、DiagnosisScopeTest |
| `retrieval/` | Document、VectorStore、SearchRequest、RRF / rerank | W9–W11 | HybridRetrieverTest、RetrievalScopeTest、RrfFuserTest、RetrievalDiversifierTest、rerank 与 BM25 测试 |
| `knowledge/ingest/` | parser / splitter 的 Document 与 TokenTextSplitter | W9 保留现有算法语义 | 解析与切分测试；splitVersion 保持不变 |
| `knowledge/service/`、`knowledge/loader/` | 向量写入、索引编排、元数据、引用映射及质量检索 | W9–W11 | DocumentLoaderTest、KnowledgeIndexTaskServiceTest、KnowledgeVectorMetadataServiceTest、知识服务测试 |
| 告警、维护、值班、通知 | 多为通过诊断/快照间接调用，无需随模型框架重写 | 保留业务行为，W16 再异步化 | AlertEvaluatorTest、AlertEventServiceTest、MaintenanceWindowServiceTest、NotificationCenterTest 等 |

## 3. 冻结契约与配置

### HTTP、SSE 与持久化

- 同步入口：`GET /api/ops/ask?question=...&sessionId=...`，返回 AskResponse。
- 流式入口：`POST /api/ops/ask/stream`，JSON `{question, sessionId}`，Bearer 头鉴权；不改为 JWT URL 参数。
- **实际成功顺序：答案 token → event: citations / JSON → data: [DONE]。** 原周计划 W6 和旧 Javadoc 的“引用先发”与实现不一致，本次更正说明，不修改运行顺序。
- 流异常不伪造正常完成；取消不发送 DONE。现有行为在完成、失败或取消时保存非空部分答案；迁移保留该行为，之后如需新增消息完成状态另立契约。
- `ops_message` 是历史事实来源，缓存窗口 20 条；冷启动回填先于保存当前用户消息，按消息 ID 正序回填；异步处理传播请求数据范围。
- 同步与流式当前持久化调用面存在差异，不能假定完全等价。同步 askWithCitations 不主动保存会话消息，后续迁移不要悄然更改。

### 模型与检索

| 项目 | 主配置/代码基线 |
| --- | --- |
| Java / Boot / Spring AI | 21 / 4.1.2 / 2.0.2 |
| 序列化 | 保留 Jackson 2 配置及现有脱敏 DTO |
| Chat | DeepSeek 兼容接口，deepseek-chat，temperature 0.7 |
| Embedding | 百炼 text-embedding-v3，1024 维 |
| 向量索引 | Pgvector，HNSW，COSINE_DISTANCE |
| 检索 | 向量与 BM25 各召回 20，向量阈值 0.5；RRF、多样性、可选 rerank |
| 切分 | 主配置 recursive；Markdown 结构化路由；480 token、80 overlap；splitVersion=2 |
| 重排序 | 主配置关闭，候选模型 gte-rerank-v2 |

此表来自已提交主配置，**不读取或展开 application-local.yml**，不能认定运行实例没有其他覆盖。后续真实对照需固定实际模型参数和数据集，不同时更换 embedding、切分和向量库。

### 权限与只读黄金集

共享夹具：`src/test/resources/migration/readonly-golden.json`。

- 8 条迁移对比定义：健康 UP / DOWN / 未注册 / 不可达、指标查询、指标发现、服务发现、纯知识问答。
- 4 条 W7–W8 目标定义：DOWN 才查指标，UP / 不可达 / 未知停止。它们是后续验收要求，不声称旧实现已经稳定满足。
- 6 条可执行权限夹具：公开已审核、跨租户、空部门、全租户部门、跨租户同名个人、未审核企业知识；由 MigrationRetrievalGoldenTest 执行。
- 问答定义目前没有真实模型评测 runner，夹具中的 observation 不是线上观测。真实检索 Recall@K / MRR、引用正确率、延迟、Token 与规划 10 次至少 9 次正确尚未测量。
- 内存 matches 与数据库过滤表达式的等价性仍须在 Pgvector 阶段验证；不能用上述 6 条内存断言替代真实数据库权限验收。

## 4. W2 隔离 PoC 的范围

`pom.xml` 增加显式 `langchain4j-poc` profile，固定候选版本 1.23.0，只有 test scope 的 langchain4j / langchain4j-open-ai 依赖及 `src/poc/java` 测试源码。默认运行不启用该 profile；不注入新生产 Bean，不引入 LangChain4j starter，不更改 Boot 版本。

版本来源：Maven Central 的 `dev/langchain4j/langchain4j-open-ai/maven-metadata.xml`，本次验证以实际下载及编译结果为准，不使用浮动版本。

`LangChain4jCompatibilityPocTest` 在 127.0.0.1 随机端口启动 OpenAI 协议夹具，使用明确的占位 API Key：

1. 同步模型请求能使用指定 endpoint / model 并返回中文。
2. AI Services 将结构化 JSON 转为业务 record DTO，使用当前项目依赖组合编译运行。
3. 脚本模型先请求健康工具，再请求指标工具；真实执行本地只读夹具方法，逐轮验证 tool_call_id 及工具结果回填，最后输出摘要。
4. 流式中文 token 与最终回调正常，验证请求确实开启 stream。

工具错误处理显式配置为失败，不默认把任意异常内部信息发回模型。PoC 不调用真实模型、SMTP、数据库或正在运行的 todo-service，也不证明现有 Spring AI 四工具已完成迁移。两个脚本工具仅为框架协议夹具。

### 尚待完成的 W2 验证

- 相同模型的真实同步、流式、结构化输出与多轮工具试跑；需要单独记录实际费用/延迟和错误，不把脚本响应算成模型成功率。
- Pgvector 原数据 ID、metadata、距离/score、事务及权限过滤兼容。使用专用测试库，不清空现有向量，不做全量重算。
- 同步/流式失败、超时、取消与历史数据兼容的进一步验证；本 PoC 不代替生产 Spring 上下文启动验证。
- MCP 有测试 Server 时验证只读连接，暂无 Server 时先做契约；Agentic / LangGraph4j 另行评估，均不作为本次引入依赖。

未完成上述项目之前，不标记 W2 完整通过，不提前移除 Spring AI。

## 5. 执行与验证记录

首次默认全量测试：1004 项，0 failures、15 errors、0 skipped。错误集中在三个工具测试的 HttpServer 创建阶段：Windows JDK Unix-domain socket `Invalid argument: connect`，不是业务断言失败。

仅切换 SelectorProvider 未解决问题。随后指定项目 `target/socket-tmp` 作为 Unix-domain socket 临时目录，15 项工具测试全部通过；未修改系统配置或禁用测试。

补充流式取消部分答案保存测试后，全量基线：1005 项，0 failures、0 errors、0 skipped。独立 PoC 首次运行：4 项全部通过。加入 6 条权限黄金夹具并显式配置 PoC 工具错误处理后的合并回归：**1015 项，0 failures、0 errors、0 skipped，BUILD SUCCESS**，包含现有测试与 4 项 PoC。未运行真实 embedding 评测或页面集成测试。

Windows 复现命令（避免将执行日志中的测试夹具异常误认为真实服务异常）：

```powershell
New-Item -ItemType Directory -Force target/socket-tmp | Out-Null
# 原有基线及迁移权限夹具；不启用 LangChain4j profile
.\mvnw.cmd -B "-Djdk.net.unixdomain.tmpdir=$PWD\target\socket-tmp" test
# 只跑框架 PoC
.\mvnw.cmd -B -Plangchain4j-poc "-Djdk.net.unixdomain.tmpdir=$PWD\target\socket-tmp" -Dtest=LangChain4jCompatibilityPocTest test
# 验证两套测试依赖共存时的全部回归
.\mvnw.cmd -B -Plangchain4j-poc "-Djdk.net.unixdomain.tmpdir=$PWD\target\socket-tmp" test
```

原始日志在 `target/migration-baseline-test.log`、`target/migration-baseline-verified.log`、`target/langchain4j-poc-test.log`、`target/langchain4j-poc-regression.log`，属于本地产物，不提交。

增量执行期间修改注释后出现过 JaCoCo 历史数据不匹配提示。正式生成覆盖率报告时应在没有占用 target 的进程下执行 clean test；本次不报告覆盖率数值。

## 6. W2 阶段门复核（2026-11-07）

本轮按“真实模型 + 原数据兼容”要求做了环境探测，结果如下：

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| 真实模型同步/流式/结构化/多轮工具 | 未通过阶段门 | 本机使用已存在的 DashScope 环境凭据发起有界 HTTPS 请求时 TLS 握手失败；没有得到模型响应，也没有记录或输出凭据。DeepSeek key 未出现在环境变量中，不能替换测试目标。 |
| 应用 PostgreSQL | 未通过阶段门 | `application.yml` 指向 `localhost:5433/opsdb`，该端口当前不可达；`5432` 的可达性不代表应用数据库可用。未尝试启动 Docker，也未触碰业务数据。 |
| Pgvector 适配器依赖 | 已确认可解析 | `dev.langchain4j:langchain4j-pgvector:1.23.0-beta33` 可从 Maven Central 获取；该版本为 beta，不能据此证明与 Spring AI `vector_store` 兼容。 |
| schema 对照 | 已完成静态核对 | 当前表为 `id uuid / content text / metadata jsonb / embedding vector(1024)`；LangChain4j 默认 builder 采用独立列布局，不能直接复用而不做适配。 |

结论：W2 **未通过**，未满足真实模型和数据库兼容的迁移前提。后续真实验证必须在 PostgreSQL 5433 恢复、TLS/模型 endpoint 可用后，用隔离表和最小数据集完成；现有 `vector_store` 不清表、不重建索引。

## 7. 下一步和回退

在 W2 环境条件补齐后补做阶段门；当前继续实现 W3 框架中立 DTO / Gateway。生产迁移仍按聊天、记忆/工具、流式/诊断、知识链路顺序逐项替换。W3 边界只被新测试或显式适配 Bean 使用，默认运行仍由 Spring AI 提供，回退方式为不启用新适配器。

### W3 首个增量（本轮）

已新增 `ai/api` 业务边界：`ChatGateway`、`StreamingChatGateway`、`StructuredOutputGateway`、`ToolExecutor`、`KnowledgeSearch`、`VectorIndex` 及其请求/响应、工具、检索和索引 DTO。边界使用 JDK `Flow.Publisher` 和项目 DTO，不泄漏 Spring AI / LangChain4j 类型；流式 DTO 固定 `TOKEN → CITATIONS → DONE` 的顺序语义。`AiBoundaryContractTest` 已覆盖不可变上下文、事件顺序和空向量拒绝。

本增量未替换生产 Bean，未改变现有聊天、诊断、RAG 或数据库行为；下一步是为这些接口补 Spring AI 过渡适配器和 LangChain4j 对照适配器，并在 W2 阶段门条件恢复后运行真实对照。

本次不改业务运行配置、数据库结构和真实凭据，不重启服务。回退 PoC 只需不启用 Maven profile；默认运行仍是旧实现。所有更改由用户手动提交。
