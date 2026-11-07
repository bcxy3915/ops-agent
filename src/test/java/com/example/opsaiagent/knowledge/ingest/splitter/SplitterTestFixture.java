package com.example.opsaiagent.knowledge.ingest.splitter;

import com.example.opsaiagent.knowledge.service.VectorIndexer;
import com.example.opsaiagent.retrieval.KnowledgeVectorStore;

import java.util.List;

/**
 * 测试夹具：不依赖 Spring 上下文组装真实策略链。
 */
public final class SplitterTestFixture {

    private SplitterTestFixture() {
    }

    public static SplitProperties properties() {
        return new SplitProperties();
    }

    public static TextChunkStrategyResolver resolver(SplitProperties properties) {
        TokenMeter tokenMeter = new TokenMeter();
        RecursiveBoundarySplitter recursive = new RecursiveBoundarySplitter(tokenMeter);
        MarkdownStructuralSplitter markdown = new MarkdownStructuralSplitter(recursive, tokenMeter, properties);
        TokenFallbackSplitter token = new TokenFallbackSplitter();
        return new TextChunkStrategyResolver(List.of(markdown, recursive, token), properties);
    }

    /**
     * 组装使用项目向量接口的真实切分索引器。
     * @param vectorStore 测试向量存储
     * @return 使用真实策略链的索引器
     */
    public static VectorIndexer vectorIndexer(KnowledgeVectorStore vectorStore) {
        return new VectorIndexer(vectorStore, resolver(properties()));
    }
}
