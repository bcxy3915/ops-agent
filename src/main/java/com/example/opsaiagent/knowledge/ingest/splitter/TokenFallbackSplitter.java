package com.example.opsaiagent.knowledge.ingest.splitter;

import com.example.opsaiagent.ai.adapter.springai.SpringAiDocumentMapper;

import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 兜底策略（order=999）：包装旧 TokenTextSplitter 口径。
 *
 * 仅在以下场景使用，不参与 supports 自然匹配：
 * - 配置回退（default-strategy / markdown.strategy 设为 token）；
 * - 显式 strategyOverride=token；
 * - 其他策略抛异常时由 Resolver 降级（切分永不失败）。
 */
@Component
public class TokenFallbackSplitter implements TextChunkStrategy {

    public static final String NAME = "token";

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(500)
            .withMinChunkSizeChars(200)
            .withMaxNumChunks(100_000)
            .withKeepSeparator(true)
            .build();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 999;
    }

    @Override
    public boolean supports(SplitContext context) {
        return false;
    }

    /**
     * 按原 token 兜底规则切分项目文档。
     * @param docs 待切分文档
     * @param options 路由切分选项，本策略沿用固定兜底参数
     * @return 携带原元数据的文档块
     */
    @Override
    public List<Document> split(List<Document> docs, SplitOptions options) {
        return SpringAiDocumentMapper.fromSpring(splitter.apply(SpringAiDocumentMapper.toSpring(docs)));
    }
}
