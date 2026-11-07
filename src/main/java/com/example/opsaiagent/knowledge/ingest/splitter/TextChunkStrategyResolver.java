package com.example.opsaiagent.knowledge.ingest.splitter;

import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

/**
 * 切分策略路由器：
 * 1. strategyOverride 显式指定（评测/运维干预）；
 * 2. docType 覆盖配置（当前仅 MARKDOWN）；
 * 3. supports 自然匹配（按 order 升序）；
 * 4. default-strategy，未命中再退 token。
 *
 * 任何策略执行异常均降级 token 兜底，切分永不失败。
 */
@Slf4j
@Component
public class TextChunkStrategyResolver {

    private final List<TextChunkStrategy> strategies;
    private final SplitProperties properties;

    public TextChunkStrategyResolver(List<TextChunkStrategy> strategies, SplitProperties properties) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(TextChunkStrategy::order))
                .toList();
        this.properties = properties;
    }

    /**
     * 路由并执行切分；同时为每个块打上 splitStrategy / splitVersion 标记，
     * 供检索排查、统计与存量重索引识别使用。
     */
    public List<Document> split(List<Document> docs, SplitContext context) {
        TextChunkStrategy strategy = resolve(context);
        List<Document> chunks;
        try {
            chunks = strategy.split(docs, properties.toOptions());
        } catch (Exception e) {
            TextChunkStrategy fallback = byName(TokenFallbackSplitter.NAME);
            log.warn("切分策略 {} 执行失败，降级 token 兜底: {}", strategy.name(), e.getMessage(), e);
            chunks = fallback.split(docs, properties.toOptions());
            strategy = fallback;
        }
        for (Document chunk : chunks) {
            chunk.getMetadata().put("splitStrategy", strategy.name());
            chunk.getMetadata().put("splitVersion", TextChunkStrategy.CURRENT_SPLIT_VERSION);
        }
        log.info("文本切分完成: strategy={}, docs={}, chunks={}", strategy.name(), docs.size(), chunks.size());
        return chunks;
    }

    public TextChunkStrategy resolve(SplitContext context) {
        // 1. 显式指定策略名（评测与运维干预入口）
        if (context != null && context.hasOverride()) {
            TextChunkStrategy strategy = byName(context.strategyOverride());
            if (strategy != null) {
                return strategy;
            }
            log.warn("未知策略名 {}，回退类型路由", context.strategyOverride());
        }
        // 2. docType 覆盖配置
        if (context != null) {
            String typeStrategy = properties.strategyForDocType(context.docType());
            if (StringUtils.hasText(typeStrategy)) {
                TextChunkStrategy strategy = byName(typeStrategy);
                if (strategy != null) {
                    return strategy;
                }
                log.warn("docType={} 配置的策略 {} 不存在，回退默认路由", context.docType(), typeStrategy);
            }
        }
        // 3. supports 自然匹配
        if (context != null) {
            for (TextChunkStrategy strategy : strategies) {
                if (strategy.supports(context)) {
                    return strategy;
                }
            }
        }
        // 4. 默认策略，未命中再退 token
        TextChunkStrategy def = byName(properties.getDefaultStrategy());
        return def != null ? def : byName(TokenFallbackSplitter.NAME);
    }

    private TextChunkStrategy byName(String name) {
        return strategies.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
