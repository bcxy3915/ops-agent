# LangChain4j 剩余迁移清单

核对日期：2026-11-08。以当前生产代码和配置为准；本清单区分模型调用已切换与框架依赖已移除。

## 1. 当前结论

同步问答、SSE 流式 Agent、工具循环、会话记忆、RAG 内容检索接入、结构化诊断和诊断追问，均已有 LangChain4j 生产实现。通过 `ops-agent.ai.provider=langchain4j` 选择；当前默认仍为 `spring-ai`。

embedding 切片先通过 `LangChain4jEmbeddingBridge` 接管向量生成；后续存储切片已新增 `KnowledgeVectorStore` 与 `LangChain4jPgVectorStore`，生产依赖齐备时，LangChain4j 分支的 `HybridRetriever` 和 `VectorIndexer` 直接调用 JDBC 存储，绕过 Spring AI `VectorStore`。第九切片已统一业务链路为项目 `knowledge/model/Document`；默认回退与自动配置仍保留，尚未移除 Spring AI 依赖。

## 2. 剩余项与顺序

| 优先级 | 当前依赖与入口 | 迁移内容 | 验收重点 |
| --- | --- | --- | --- |
| P1（已接线，待真实数据库验收） | LangChain4j 分支使用 `KnowledgeVectorStore`；Spring AI 回退仍依赖 `VectorStore/SearchRequest` | JDBC 存储已接管读写；后续补真实数据库验证并清理原存储自动配置 | 复用 `vector_store` 的 ID、content、metadata、embedding；审核状态、公开/租户/部门/个人范围一致；相似度阈值、topK、批次和失败降级一致 |
| P2（已完成业务类型替换） | BM25、融合、重排、引用、诊断与入库链路统一使用项目 `Document` | 原 Reader、token 兜底、embedding 格式化与回退存储边界使用 `SpringAiDocumentMapper`；后续随 P3/P4 清理 | 保留 ID、正文、完整 metadata、评分和引用映射；各算法仍能使用可变 metadata，不丢失来源与权限字段 |
| P3 | `MarkdownDocumentParserStrategy` 的 `MarkdownDocumentReader`、`TokenFallbackSplitter` 的 `TokenTextSplitter` | 替换 Markdown 解析与 token 兜底切分；其他解析器只调整文档类型接口 | Markdown 代码块/引用内容不丢失；切分重叠、硬上限、metadata 和降级策略保持；运行三套现有切分契约测试 |
| P4 | `OpsAgentService` 的 Spring AI 分支、`AiConfig`、`ChatMemoryConfig`、`ai/adapter/springai` 和原工具注解 | 实际环境验收完成后，移除回退分支、聊天模型和记忆配置、工具注解及过渡 embedding 适配层 | 默认配置可启动；无 Spring AI 业务入口；SSE 顺序、取消、冷启动记忆、工具权限及诊断失败降级保持 |
| P5 | `pom.xml` 中 Spring AI BOM、模型/向量 starter、Markdown/PDF reader，以及 `spring.ai.*` 配置 | 移除框架依赖，显式声明仍使用的 PDFBox、JTokkit 等库，将有效配置统一到项目配置 | 主源码及生产依赖树不再使用 Spring AI；配置继承明确；无需重新初始化数据库或自动重建索引 |

P1 与 P2 可以连续推进，存储侧通过适配层衔接；P4、P5 应在前面的生产链路验证后清理，避免移除回退依赖时让知识入库无法运行。

`ai/api/VectorIndex` 与 `KnowledgeSearch` 是早期预留的框架无关接口。实际生产读写边界为 `KnowledgeVectorStore`，已使用项目 `Document` 与可信 `RetrievalScope`。本片未将预留接口接入生产；后续清理接口时需核对其删除语义和权限上下文，避免长期保留重复边界。

embedding 的 `MetadataMode` 与默认内容格式化仍来自 Spring AI，JDBC 存储通过适配器临时复用该规则，避免无意改变已有向量的输入文本。它需要在移除 Maven 依赖前单独替换并验证格式一致性。

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
