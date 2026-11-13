# Xinference 本地精排接入

模型通过 Xinference 部署，运维智能体只调用 HTTP 接口，不需要安装 Python 服务或改动聊天、向量模型和 ES。

## 配置

```yaml
ops-agent:
  retrieval:
    rerank:
      enabled: true
      min-score: ${RERANK_MIN_SCORE:0.1}
      provider: xinference
      model: ops-bge-reranker
      url: http://127.0.0.1:9997/v1/rerank
      api-key: ${XINFERENCE_API_KEY:}
      connect-timeout-ms: 1000
      read-timeout-ms: 45000
```

密钥可以放在被 Git 忽略的 `application-local.yml` 的 `ops-agent.retrieval.rerank.api-key` 下。不要写在 `ops-agent.ai.retrieval` 下。模型填写运行页中的 UID，而不是底层模型名称；访问映射端口 9997，不使用副本内部地址。启用 local profile 后重启后端。

## 行为

向量 top20 与 BM25 top20 → RRF top20 → 同文档候选治理 → Xinference 精排打分 → 相关性阈值过滤 → 最多 topK → 模型快照及引用。向量为空时，BM25 候选同样精排。topK 是数量上限，全部候选不相关时返回空列表，不用低分候选补足 5 条。

请求为平铺的 `model/query/documents/top_n/return_documents/normalize`，显式发送 `normalize: true`，响应从顶层 `results` 读取 `index/relevance_score`。仅发送候选正文，不发送权限元数据；下标只能映射到当前已授权候选。校验下标、重复项和有限的 0～1 分数后，按 `min-score` 过滤并降序截断。服务端返回 top_n 前会对全部候选打分，客户端仍校验排序与数量上限。

保留原候选 ID、正文、权限元数据和原始 `Document.score`，只对副本写入 `rerankScore/rerankModel` 和最终流水线 `metadata.score`。模型上下文使用通过精排阈值的结果，来源直接复用这些结果并展示精排分数，不再重新计算 embedding 相似度，也不以短词命中覆盖精排结论。关闭精排时才保留旧的引用兼容校验。

客户端每个应用实例最多一个在途精排请求，繁忙时不排队；服务失败、超时、熔断及响应非法时返回空知识上下文，不自动重试、不回填未验证候选。问答仍可使用通用知识或实时工具。客户端超时不能保证取消远端仍在执行的推理任务；CPU 请求限制建议仍设为 1。

## 阈值校准

`min-score` 是当前精排模型的相关性门槛，不是向量余弦阈值，也不是正确率。切换模型或供应方后需重新校准；不能将精排分数在候选内做 min-max 归一化，否则全不相关时第一名也会被人为变成高分。

2026-11-13，使用本机 `ops-bge-reranker`、`normalize: true`，对实际内置 `04-部署手册.md` 第 3 节完整文本（数据库部署、发布、备份）测试：

| 问题 | 归一化精排分数 | 当前 0.1 门槛 |
| --- | ---: | --- |
| es | 0.171878 | 保留 |
| ES的部署地址和端口是什么 | 0.220219 | 保留 |
| Elasticsearch怎么部署 | 0.835528 | 保留 |
| es可视化工具 | 0.031887 | 排除，节选未覆盖该内容 |
| 红烧肉怎么做 | 0.000421 | 排除 |

0.1 是上述小样本的初始配置。该测试只比较同一真实节选与若干问题，不代表完整知识库、实际召回的 20 个分块或所有问法的验收。后续应收集正例、完全无关问题、同主题但未覆盖的问题，以及 `es` 等缩写的真实查询，比较误召回和漏召回后调整 `RERANK_MIN_SCORE`。

## 验证

1. Xinference 运行页确认 `ops-bge-reranker` 为 READY，创建可用 API Key。
2. 重启运维智能体，在知识库检索测试中输入知识库确有内容的问题。
3. 后端日志应出现 `Xinference 精排完成，输入 ... 个，阈值 ...，有效输出 ... 个`。
4. 测试正常问答及多轮追问，确认页面引用和答案仍能正常输出。
5. 测试知识库不相关问题和同主题但未覆盖的问题，确认有效结果可以为零，模型上下文和引用均不含被淘汰候选。
6. 停止精排模型后重复查询，日志应记录失败且返回空知识上下文，问答仍可继续；恢复模型后注意熔断器可能需要等待恢复窗口。
7. 用实际 20 条片段测 CPU 耗时，必要时调整读取超时。两个短文档的耗时不能代表实际批次耗时。

本机实测：2 条短文本约 1.3 秒；9 条每条最多 1200 字符的内置文档节选约 12.2 秒；将这些节选循环组成 20 条候选约 25.7 秒。最后一组是合成批次，不代表真实召回效果，但说明 CPU 精排可能显著增加问答首字等待时间，因此当前读取超时为 45 秒，后续应结合实际分块长度评估。

回退为不精排：`enabled: false`；回到百炼时将 `provider` 改为 `dashscope`，并将 `model` 恢复为百炼模型名称。

本次相关测试：

```powershell
mvn '-Dtest=RerankPipelineTest,XinferenceRerankerTest,DashScopeRerankerTest,NoopRerankerTest,HybridRetrieverTest,KnowledgeRetrievalServiceTest,KnowledgeRetrievalControllerTest,LangChain4jKnowledgeContentRetrieverTest,OpsAgentServiceTest,ChatControllerTest,DiagnosisServiceTest,MultiTenantRetrievalIsolationTest' test
```
