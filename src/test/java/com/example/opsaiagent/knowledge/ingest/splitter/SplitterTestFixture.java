package com.example.opsaiagent.knowledge.ingest.splitter;

import com.example.opsaiagent.knowledge.service.VectorIndexer;
import org.springframework.ai.vectorstore.VectorStore;

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

    public static VectorIndexer vectorIndexer(VectorStore vectorStore) {
        return new VectorIndexer(vectorStore, resolver(properties()));
    }
}
