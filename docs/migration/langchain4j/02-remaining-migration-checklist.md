# LangChain4j 剩余迁移清单

核对日期：2026-11-08。以当前生产代码和配置为准；本清单区分模型调用已切换与框架依赖已移除。

## 1. 当前结论

同步问答、SSE 流式 Agent、工具循环、会话记忆、RAG 内容检索接入、结构化诊断和诊断追问，均已有 LangChain4j 生产实现。通过 `ops-agent.ai.provider=langchain4j` 选择；当前默认仍为 `spring-ai`。

知识向量生成与存储已固定使用 LangChain4j + JDBC，与聊天 provider 无关。`HybridRetriever` 和 `VectorIndexer` 强制依赖 `KnowledgeVectorStore`；Spring AI Pgvector starter、存储回退和 embedding bridge 已删除。业务文档使用项目 `knowledge/model/Document`，原框架转换器已移至测试源。聊天模型、记忆和工具注解仍有 Spring AI 依赖。

## 2. 剩余项与顺序

| 优先级 | 当前依赖与入口 | 迁移内容 | 验收重点 |
| --- | --- | --- | --- |
| P1（已删除存储回退，待真实数据库验收） | 生产仅使用 `KnowledgeVectorStore`，不再依赖 `VectorStore/SearchRequest` | JDBC 存储固定接管读写，原 starter 已移除；待补真实数据库验收 | 复用 `vector_store` 的 ID、content、metadata、embedding；审核状态、公开/租户/部门/个人范围一致；相似度阈值、topK、批次和失败降级一致 |
| P2（已完成业务类型替换） | BM25、融合、重排、引用、诊断与入库链路统一使用项目 `Document` | `SpringAiDocumentMapper` 已移至测试源，仅用于迁移对照 | 保留 ID、正文、完整 metadata、评分和引用映射；各算法仍能使用可变 metadata，不丢失来源与权限字段 |
| P3（已完成生产替换） | Markdown 直接解析 CommonMark，token 兜底直接使用 JTokkit，JDBC 格式化使用项目实现 | 旧 Markdown Reader 仅为测试对照依赖；embedding bridge 已删除 | 旧新实现的文本、元数据、分块和默认向量输入对照；现有三套切分契约回归 |
| P4 | `OpsAgentService` 的 Spring AI 分支、`AiConfig`、`ChatMemoryConfig`、`ai/adapter/springai` 和原工具注解 | 实际环境验收完成后，移除回退分支、聊天模型和记忆配置、工具注解；embedding 过渡层已删除 | 默认配置可启动；无 Spring AI 业务入口；SSE 顺序、取消、冷启动记忆、工具权限及诊断失败降级保持 |
| P5（部分完成） | Spring AI BOM、聊天模型 starter、测试用 Markdown reader，以及 `spring.ai.*` 配置 | 向量 starter 与 PDF reader 已移除，PDFBox/CommonMark/JTokkit 已显式声明；后续清理回退和对照测试依赖，将有效配置统一到项目配置 | 主源码及生产依赖树不再使用 Spring AI；配置继承明确；无需重新初始化数据库或自动重建索引 |

下一切片清理聊天回退和 provider 选择，再移除剩余 starter 与旧配置。真实模型和数据库验收仍独立保留，不能用 Mock 结果替代。

`ai/api/VectorIndex` 与 `KnowledgeSearch` 是早期预留的框架无关接口。实际生产读写边界为 `KnowledgeVectorStore`，已使用项目 `Document` 与可信 `RetrievalScope`。本片未将预留接口接入生产；后续清理接口时需核对其删除语义和权限上下文，避免长期保留重复边界。

JDBC 存储已使用项目 `EmbeddingMetadataMode` 与 `EmbeddingContentFormatter`，不再将项目文档转换为 Spring AI 文档以格式化。保留默认模板、系统元数据换行、正文前双换行与元数据收集顺序；所有模式均有旧新对照测试。旧 `LangChain4jEmbeddingBridge` 已删除，新 JDBC 路径不支持注入框架自定义模板。

## 3. 可以继续复用的能力

- BM25、RRF、去重、多样性控制与重排算法：属于业务检索能力，调整数据类型和边界即可。
- PDFBox、POI、TXT 编码识别：已是通用解析实现，不需要改成 Agent 框架的解析器。
- Markdown 结构化切分、递归边界切分和 JTokkit token 计量：保留既有业务规则与测试。
- PostgreSQL、Pgvector、MyBatis、RBAC、索引任务、告警、通知、部署和巡检等领域服务：框架切换不能改变其业务语义。

MCP、多 Agent 协作与 LangGraph4j 是后续新增能力，不是完成这次 Spring AI 替换的先决条件。

## 4. 本轮 embedding 配置与边界

`knowledge/config/LangChain4jEmbeddingConfig` 现固定生效，不再受聊天 provider 控制。默认复用当前 `text-embedding-v3`、1024 维和独立百炼地址；向量地址与密钥不继承聊天服务地址与密钥。

| 配置 | 环境变量 | 默认/继承来源 |
| --- | --- | --- |
| `ops-agent.ai.embedding.base-url` | `OPS_EMBEDDING_BASE_URL` | 原 `spring.ai.openai.embedding.base-url` |
| `ops-agent.ai.embedding.api-key` | `OPS_EMBEDDING_API_KEY` | 原 `spring.ai.openai.embedding.api-key` |
| `ops-agent.ai.embedding.model` | `OPS_EMBEDDING_MODEL` | 原 embedding 模型，默认 `text-embedding-v3` |
| `ops-agent.ai.embedding.dimensions` | `OPS_EMBEDDING_DIMENSIONS` | `spring.ai.vectorstore.pgvector.dimensions`，默认 1024 |
| `ops-agent.ai.embedding.timeout` | `OPS_EMBEDDING_TIMEOUT` | 60 秒 |
| `ops-agent.ai.embedding.max-retries` | `OPS_EMBEDDING_MAX_RETRIES` | 2 次 |

保留 `spring.ai.openai.embedding.metadata-mode` 的格式化语义。每批最多 10 条；返回数量、维度和非有限数值都经 JDBC 存储校验；存储接口不暴露单次请求改变模型或维度的选项。模型维度须与 Pgvector **配置**维度一致，此启动校验不等于已读取真实表的列维度。

应用排除 `OpenAiEmbeddingAutoConfiguration`，避免剩余聊天 starter 再创建原生 embedding Bean。Pgvector starter 已删除，`initialize-schema/index-type` 不再生效，不自动执行 DDL；新环境应使用 `init-scripts/schema.sql` 创建现有表和索引。原 `spring.ai.*` 向量配置仅作为兼容属性源，后续统一到项目配置。

仅切换调用 SDK 时须保持原模型、维度与内容格式化规则；如果主动更换 embedding 模型，即使维度相同也不能直接把新旧向量混在同一索引中，应另行评估重建和切换方案。

## 5. 验证与未验收范围

定向验证固定向量 Bean 注册、聊天 provider 独立性、缺失数据库和非法配置启动失败、分批写入、存储异常传播、BM25 降级和内置知识加载。协议夹具改为验证 LangChain4j 模型与项目 JDBC 存储；真实数据库读写、事务回滚、表列维度及外部模型精度尚未验收。

A/B 评测入口已移除旧 VectorStore Bean，改为在测试代码中仅查询带 evalDoc 和 splitVersion 标记的评测数据；不向生产接口开放任意元数据过滤。本片未运行真实评测、数据库迁移或重索引。逐切片测试结果见 `01-baseline-and-poc.md`。
