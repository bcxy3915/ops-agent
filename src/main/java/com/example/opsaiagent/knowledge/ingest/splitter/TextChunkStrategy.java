package com.example.opsaiagent.knowledge.ingest.splitter;

import com.example.opsaiagent.knowledge.model.Document;

import java.util.List;

/**
 * 文本切分策略接口：按 SplitContext 路由，产出分块。
 *
 * 实现约束（不变量，单测强制校验）：
 * 1. 不丢内容：块按序拼接（去除相邻重叠前缀）后与原文一致；
 * 2. 不产出空白块；
 * 3. 每块 token 数不超过 options.maxChunkTokens（小块合并允许轻微超 chunkSizeTokens）；
 * 4. 输出块必须携带输入文档的全部 metadata（沿用 TextSplitter 行为）。
 */
public interface TextChunkStrategy {

    /** 当前切分算法代次：写入向量 metadata.splitVersion，用于 A/B 评测隔离与存量重索引识别 */
    int CURRENT_SPLIT_VERSION = 2;

    /** 策略名（全局唯一），写入向量 metadata.splitStrategy，也用于配置显式指定 */
    String name();

    /** 匹配优先级，小者优先；Resolver 按 order 升序取第一个 supports 的策略 */
    int order();

    /** 是否接管本次切分（依据 docType / layer 等上下文） */
    boolean supports(SplitContext context);

    /** 执行切分 */
    List<Document> split(List<Document> docs, SplitOptions options);
}
