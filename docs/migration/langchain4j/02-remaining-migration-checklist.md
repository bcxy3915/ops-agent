# LangChain4j 迁移完成情况与待验收清单

核对日期：2026-11-08。第十二切片已删除 Spring AI 的全部生产和测试依赖、BOM、适配层、配置类及工具注解。代码迁移完成不代表真实环境验收完成。

## 1. 当前生产入口

| 能力 | 当前实现 |
| --- | --- |
| 同步与流式运维 Agent | LangChain4j AiServices、OpsAssistant、OpsStreamingAssistant，最多 8 轮工具调用 |
| 会话记忆 | ConversationChatMemoryStore + 20 条窗口，按租户、用户名和 sessionId 隔离，从 ops_message 冷启动回填 |
| 诊断结构化输出与追问 | LangChain4j StructuredOutputGateway / ChatGateway |
| 只读工具 | LangChain4jReadOnlyToolBindings 调用原领域服务，保留服务查询权限与外部 HTTP 容错 |
| 知识解析与切分 | CommonMark、PDFBox、POI、JTokkit 与项目结构化/递归策略 |
| 向量生成与存储 | LangChain4j embedding + KnowledgeVectorStore / JDBC，复用 public.vector_store |
| 混合检索与引用 | 项目 Document、BM25、RRF、多样性治理和重排，保留权限范围 |

`ops-agent.ai.provider` 已从默认配置移除；历史本地值不再控制框架选择。生产 Bean 强制注入，依赖缺失时启动失败，不静默选择其他框架。`langchain4j-poc` 仅添加兼容测试源。

## 2. 当前配置与兼容

项目配置统一使用 `ops-agent.ai.*`：聊天为 base-url/api-key/model/timeout/temperature；向量为 embedding.base-url/api-key/model/dimensions/metadata-mode/timeout/max-retries；存储为 vector-store.dimensions/schema/table/distance-type。

为保护已有本地配置，原 `spring.ai.openai.*` 与 `spring.ai.vectorstore.pgvector.*` 仅在占位符中作为后备取值来源。它们不加载任何旧框架组件，不再拥有 initialize-schema、index-type 或 provider 切换效果。本片未读取、修改或输出 application-local.yml 中的真实密钥；可以逐步手动改写为项目配置，不需要立即迁移密钥。

聊天和向量使用独立服务地址与密钥。仍使用原 embedding 模型、1024 维与默认格式化规则；更换模型或维度必须另行评估已有向量兼容性。模型维度与存储配置维度启动校验不等于真实数据库列维度检查。

遗漏审计已补齐流式模型的 temperature 参数，同步与流式使用相同的模型、温度与超时配置。配置类自身的默认模型与 application.yml 统一为 DeepSeek；历史 chat.model、chat.temperature 与 chat.timeout 继续作为后备值。配置装配测试同时覆盖 OpsAgentService 入口，避免只验证底层模型 Bean。

存储仅支持 public.vector_store 和 COSINE_DISTANCE。新环境通过 init-scripts/schema.sql 创建表和索引，现有环境直接复用，不自动创建或重建。

## 3. 尚需实际环境验收

1. 重启后确认默认 LangChain4j 链路正常：同步问答、SSE 答案 → citations → DONE、取消与失败时的部分答案保存。
2. 使用真实模型执行健康查询、指标查询和连续工具调用，验证响应与业务服务数据一致。
3. 上传、审核、异步索引与检索真实文档，验证 PG 实际读写、事务回滚、表维度和跨租户/部门/个人隔离。
4. 进程重启后追问，验证历史消息按 ID 正序回填且当前问题不重复注入。
5. 告警诊断与诊断追问在模型失败时保留原失败状态和通知闭环。
6. 需要时在专用测试库显式运行 SplitEvalIT；常规 test/package 不执行真实模型评测。

上述项目本轮没有执行，不应以 Mock 或本地协议夹具替代。

核心链路实现审计已修复会话租户与归属隔离、同步消息持久化、BM25 审核和候选过滤、诊断结果校验及前端提前断流误判。实际环境测试仍未执行；详细修复、自动化验证结果和操作步骤见 [03-core-chains-audit.md](03-core-chains-audit.md)。

## 4. 后续能力与优化

- 引用记录与 Agent RAG 目前仍各检索一次，后续可统一检索快照以减少重复调用并保证完全相同的引用集合。
- 流式首片段返回前尚未获得取消句柄，无法保证立即中断底层 HTTP；后续片段会取消，后续工具不再开始。
- MCP、自动部署/回滚、复杂工作流与多 Agent 为后续业务开发，不属于本次框架移除的完成条件。
- ai/api/VectorIndex 与 KnowledgeSearch 为早期预留接口，实际存储使用 KnowledgeVectorStore；后续按生产需求收敛重复边界。

迁移前的解析、token 切分及格式化结果已采集为 src/test/resources/migration/ingest-legacy-golden.json，当前回归直接比对固定结果，不再依赖旧 SDK。详细逐片进度与本轮验证数字见 01-baseline-and-poc.md。
