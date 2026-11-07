# LangChain4j 剩余迁移清单

核对日期：2026-11-08。以当前生产代码和配置为准；本清单区分模型调用已切换与框架依赖已移除。

## 1. 当前结论

同步问答、SSE 流式 Agent、工具循环、会话记忆、RAG 内容检索接入、结构化诊断和诊断追问，均已有 LangChain4j 生产实现。通过 `ops-agent.ai.provider=langchain4j` 选择；当前默认仍为 `spring-ai`。

embedding 切片先通过 `LangChain4jEmbeddingBridge` 接管向量生成；后续存储切片已新增 `KnowledgeVectorStore` 与 `LangChain4jPgVectorStore`，生产依赖齐备时，LangChain4j 分支的 `HybridRetriever` 和 `VectorIndexer` 直接调用 JDBC 存储，绕过 Spring AI `VectorStore`。第九切片已统一业务链路为项目 `knowledge/model/Document`；默认回退与自动配置仍保留，尚未移除 Spring AI 依赖。

## 2. 剩余项与顺序

| 优先级 | 当前依赖与入口 | 迁移内容 | 验收重点 |
| --- | --- | --- | --- |
| P1（已接线，待真实数据库验收） | LangChain4j 分支使用 `KnowledgeVectorStore`；Spring AI 回退仍依赖 `VectorStore/SearchRequest` | JDBC 存储已接管读写；后续补真实数据库验证并清理原存储自动配置 | 复用 `vector_store` 的 ID、content、metadata、embedding；审核状态、公开/租户/部门/个人范围一致；相似度阈值、topK、批次和失败降级一致 |
| P2（已完成业务类型替换） | BM25、融合、重排、引用、诊断与入库链路统一使用项目 `Document` | `SpringAiDocumentMapper` 仅留在回退存储边界；随 P4 清理 | 保留 ID、正文、完整 metadata、评分和引用映射；各算法仍能使用可变 metadata，不丢失来源与权限字段 |
| P3（已完成生产替换） | Markdown 直接解析 CommonMark，token 兜底直接使用 JTokkit，JDBC 格式化使用项目实现 | 旧 Markdown Reader 仅为测试对照依赖；原框架格式化仅留在待删除的 embedding bridge | 旧新实现的文本、元数据、分块和默认向量输入对照；现有三套切分契约回归 |
| P4 | `OpsAgentService` 的 Spring AI 分支、`AiConfig`、`ChatMemoryConfig`、`ai/adapter/springai` 和原工具注解 | 实际环境验收完成后，移除回退分支、聊天模型和记忆配置、工具注解及过渡 embedding 适配层 | 默认配置可启动；无 Spring AI 业务入口；SSE 顺序、取消、冷启动记忆、工具权限及诊断失败降级保持 |
| P5（部分完成） | Spring AI BOM、模型/向量 starter、测试用 Markdown reader，以及 `spring.ai.*` 配置 | PDF reader 已移除，PDFBox/CommonMark/JTokkit 已显式声明；后续清理回退和对照测试依赖，将有效配置统一到项目配置 | 主源码及生产依赖树不再使用 Spring AI；配置继承明确；无需重新初始化数据库或自动重建索引 |

P1 与 P2 可以连续推进，存储侧通过适配层衔接；P4、P5 应在前面的生产链路验证后清理，避免移除回退依赖时让知识入库无法运行。

`ai/api/VectorIndex` 与 `KnowledgeSearch` 是早期预留的框架无关接口。实际生产读写边界为 `KnowledgeVectorStore`，已使用项目 `Document` 与可信 `RetrievalScope`。本片未将预留接口接入生产；后续清理接口时需核对其删除语义和权限上下文，避免长期保留重复边界。

JDBC 存储已使用项目 `EmbeddingMetadataMode` 与 `EmbeddingContentFormatter`，不再将项目文档转换为 Spring AI 文档以格式化。保留默认模板、系统元数据换行、正文前双换行与元数据收集顺序；所有模式均有旧新对照测试。旧 `LangChain4jEmbeddingBridge` 继续使用 Spring AI 默认格式化，后续随回退存储删除，不支持在新 JDBC 路径注入框架自定义模板。

## 3. 可以继续复用的能力

- BM25、RRF、去重、多样性控制与重排算法：属于业务检索能力，调整数据类型和边界即可。
- PDFBox、POI、TXT 编码识别：已是通用解析实现，不需要改成 Agent 框架的解析器。
- Markdown 结构化切分、递归边界切分和 JTokkit token 计量：保留既有业务规则与测试。
- PostgreSQL、Pgvector、MyBatis、RBAC、索引任务、告警、通知、部署和巡检等领域服务：框架切换不能改变其业务语义。

MCP、多 Agent 协作与 LangGraph4j 是后续新增能力，不是完成这次 Spring AI 替换的先决条件。

## 4. 本轮 embedding 配置与边界

新增 `knowledge/config/LangChain4jEmbeddingConfig`，只在 `ops-agent.ai.provider=langchain4j` 时生效。默认复用当前 `text-embedding-v3`、1024 维和独立百炼地址；向量地址与密钥不继承聊天服务地址与密钥。

| 配置 | 环境变量 | 默认/继承来源 |
| --- | --- | --- |
| `ops-agent.ai.embedding.base-url` | `OPS_EMBEDDING_BASE_URL` | 原 `spring.ai.openai.embedding.base-url` |
| `ops-agent.ai.embedding.api-key` | `OPS_EMBEDDING_API_KEY` | 原 `spring.ai.openai.embedding.api-key` |
| `ops-agent.ai.embedding.model` | `OPS_EMBEDDING_MODEL` | 原 embedding 模型，默认 `text-embedding-v3` |
| `ops-agent.ai.embedding.dimensions` | `OPS_EMBEDDING_DIMENSIONS` | `spring.ai.vectorstore.pgvector.dimensions`，默认 1024 |
| `ops-agent.ai.embedding.timeout` | `OPS_EMBEDDING_TIMEOUT` | 60 秒 |
| `ops-agent.ai.embedding.max-retries` | `OPS_EMBEDDING_MAX_RETRIES` | 2 次 |

保留 `spring.ai.openai.embedding.metadata-mode` 的格式化语义。每批最多 10 条；返回数量、维度和非有限数值都经适配层校验；不支持通过单次请求改变模型或维度。模型维度须与 Pgvector **配置**维度一致，此启动校验不等于已读取真实表的列维度。

`LangChain4jEmbeddingBridge` 使用 `@Primary`，确保现有存储注入它。Spring AI 2.0.2 的自动配置根据 `OpenAiEmbeddingModel` 具体类型判断缺失，因此原生模型 Bean 仍可能同时创建，原有启动凭据配置暂时仍需保留；后续 P4/P5 一起清理。此次迁移没有执行数据库变更、真实 embedding 请求或存量重索引。

仅切换调用 SDK 时须保持原模型、维度与内容格式化规则；如果主动更换 embedding 模型，即使维度相同也不能直接把新旧向量混在同一索引中，应另行评估重建和切换方案。

## 5. 验证与未验收范围

定向测试验证批次顺序、metadata 格式化、数量与维度错误、非有限数值、请求参数约束、异常传播、默认回退和 LangChain4j 主候选选择。

本地 OpenAI 协议夹具验证 11 条文本按 10＋1 分批、模型/维度参数以及实际 Pgvector 组件的写入和查询向量均经过 LangChain4j。JDBC 为 Mock，仅验证接口衔接与过滤语句保留；真实数据库读写、生产表维度、外部模型精度与页面交互仍需实际环境验收。

本轮定向测试 15 项通过；默认全量 1038 项通过；含 LangChain4j 兼容测试的全量 1051 项通过，均无失败、错误或跳过。`git diff --check` 通过。
