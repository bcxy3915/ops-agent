# 问答并发、记忆缓存与检索一致性修复

## 1. 本次行为

同一租户、同一会话的同步与 SSE 问答共用 PostgreSQL `ops_chat_turn` 租约。同一时刻仅允许一轮，重复请求以 `CHAT_TURN_BUSY` 拒绝；不同会话可以并行。模型执行期间不持有数据库长事务。

每轮令牌包含请求 ID 和会话实体 ID。用户消息、助手答案写入前都在短事务中按“租约 → 会话”顺序加锁，复核租约期限、令牌、会话代次与归属。删除也使用相同顺序，旧请求不能将答案写入已删除或重建的会话，不能释放其他请求持有的新租约。完整答案提交失败不输出成功尾事件；异常及取消时尝试保存已经输出的部分答案。

每轮采用独立记忆标识，先从数据库刷新最近 20 条历史，再保存本轮用户问题。不同实例之间不共享本地缓存，而是共享数据库事实与租约；任意实例开始下一轮都会读到已提交历史。完成、异常、取消后清理两套 LangChain4j 框架记忆与项目缓存，关闭后的旧回调不能重新创建轮次窗口。

缓存限制项数与存活时间：非活动项按最近使用顺序淘汰，活动项满额时拒绝新请求，避免中途丢失工具调用上下文。过期在访问及写入时惰性清理，容量始终受限；活动项按创建时间过期，普通项按最近访问时间过期。

聊天仅执行一次混合检索，过滤空正文后绑定防御性快照；模型 RAG、引用及命中流水共用该快照。诊断也只检索一次，模型上下文与保存引用共用同组文档；检索失败时仅根据故障快照诊断并保存空引用。

## 2. 部署准备

已有数据库在启动新版本前手动执行：

```text
init-scripts/migrations/20261108-chat-turn.sql
```

脚本只新增租约表，不修改历史会话、消息或向量。新环境的 `schema.sql` 已包含该表。本次开发验证未对用户业务数据库执行迁移。

多实例部署时先停止旧版本接收问答，再执行脚本并统一升级；旧版本不会遵守新租约协议，不能以混合版本运行来验收并发保护。

默认配置位于 `application.yml`：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `ops-agent.chat.turn.lease-seconds` | 360 | 崩溃后允许接管的租约秒数 |
| `ops-agent.chat.turn.timeout` | 300s | SSE 整轮执行超时 |
| `ops-agent.chat.memory.max-entries` | 1000 | 每个实例的记忆最大项数 |
| `ops-agent.chat.memory.ttl` | 600s | 本地记忆存活时间 |

租约必须大于问答超时，缓存过期时间也必须大于问答超时；配置不满足时启动失败。同步调用依赖模型客户端超时、工具超时和租约写回校验，不承诺到达 SSE 截止时间就强制终止同步线程。

## 3. 回归验证

默认自动化测试使用模拟模型，不调用真实模型、SMTP 或业务服务：

```powershell
.\mvnw.cmd "-Dtest=ConversationChatMemoryStoreTest,ConversationDeletionTransactionTest,OpsAgentServiceTest,AiProviderConfigurationTest,ConversationServiceTest,DiagnosisServiceTest" test
```

覆盖历史刷新、跨租户隔离、关闭后旧回调拒绝、缓存容量/过期/活动项保护、检索快照副本、真实 LangChain4j 编排的单次检索、SSE 顺序与失败不发送 DONE、取消及超时清理。

`ChatTurnPostgresIT` 使用真实 PostgreSQL，两个服务实例争抢同一会话，检查消息顺序、计数、过期接管与删除重建后的旧请求写回。会话创建为数据库测试夹具；租约领取、校验与答案持久化调用生产 `ChatTurnService`。每个用例创建并删除独立 schema，租约 DDL 加载本次迁移脚本。它不在默认 `test` 命名范围，需显式指定独立测试库：

```powershell
.\mvnw.cmd "-Dtest=ChatTurnPostgresIT" "-Dchat.test.jdbc-url=jdbc:postgresql://127.0.0.1:15439/chat_turn_test" "-Dchat.test.username=postgres" "-Dchat.test.password=<测试库密码>" test
```

当前验证环境的 Windows JDK 在创建 HTTP 客户端时出现 `Unable to establish loopback connection`，底层为 Unix Domain Socket 的 `Invalid argument: connect`。这类环境失败必须与业务断言失败分别报告；不应通过修改应用配置或跳过失败断言将其当作通过。

## 4. 验证边界

- 流式请求会定期检查删除和过期，模型 token 与工具执行前也校验令牌。已经发出的网络请求、已经执行的只读工具及已经展示的 token 无法撤回。
- 进程崩溃后租约需到期才能接管；无心跳续租，不支持无限长问答。
- 本次数据库测试使用独立临时 PostgreSQL，不替代用户运行环境的重启、真实模型和浏览器验收。
- 引用使用同一次检索的正文与文档集合，但页面标题等治理元数据仍可能由现有引用转换器查询当前数据库；不保证模型严格遵循或引用全部参考片段。

## 5. 本轮执行结果（2026-11-08）

- 最终针对性回归 81 项通过，0 failures、0 errors、0 skipped，包含真实 LangChain4j 编排与模拟模型的单次检索验证。
- `ChatTurnPostgresIT` 在独立临时 PostgreSQL 容器中执行，2 项通过，覆盖双实例竞争、消息顺序、租约接管和删除重建。测试 schema 及临时容器均已清理，未连接用户业务库。
- 启用 `langchain4j-poc` 的全量回归共 1077 项，1043 项通过，6 failures、28 errors；失败均涉及当前 Windows JDK 创建本地 socket/HTTP 夹具时的 `Unable to establish loopback connection`，未宣称全量通过。尝试 `forkCount=0` 后环境错误仍存在。
- `git diff --check` 通过。未调用真实模型，未做浏览器或重启验收，未提交 Git；用户升级前仍需执行增量 SQL 并重启后端。

### 配置测试修复补记

用户环境完整回归报告 1064 项中 3 failures、2 errors，均来自 `AiProviderConfigurationTest`：轻量 `ApplicationContextRunner` 未配置 Spring Boot 的 `ApplicationConversionService`，新增的 `@Value Duration` 无法转换。先前本地 HTTP 客户端的 socket 错误发生得更早，掩盖了此项测试装配缺陷；不能将用户此次失败归因于 socket 环境。

已在配置测试初始化时安装与 Spring Boot 一致的类型转换服务，生产配置不变。新增 `ChatDurationConfigurationTest` 直接装配生产记忆工厂方法和 `ChatTurnService` 构造器，不创建 HTTP 客户端，验证默认秒数、分钟/ISO 格式及无效 TTL 校验。与记忆、问答测试一起运行，22 项全部通过。原完整配置装配测试在当前开发环境仍受 JDK socket 错误阻塞，用户环境全量结果需重新运行确认。

```powershell
.\mvnw.cmd "-Dtest=ChatDurationConfigurationTest,ConversationChatMemoryStoreTest,OpsAgentServiceTest" test
.\mvnw.cmd "-Dtest=AiProviderConfigurationTest" test
```
