package com.example.opsaiagent.knowledge.ingest.splitter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文本切分配置（ops-agent.knowledge.split.*）。
 *
 * 回退旧 TokenTextSplitter 口径：default-strategy: token + markdown.strategy: token。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-agent.knowledge.split")
public class SplitProperties {

    /** 全局默认策略：未命中 supports 且无类型覆盖时使用 */
    private String defaultStrategy = "recursive";

    /** 目标块 token 预算 */
    private int chunkSizeTokens = 400;

    /** 相邻块重叠 token 数（约 15%） */
    private int overlapTokens = 60;

    /** 尾块最小字符数，不足并入前一块 */
    private int minChunkChars = 100;

    /** 单块 token 硬上限 */
    private int maxChunkTokens = 800;

    /** embedding 文本是否携带「标题｜章节路径」上下文头 */
    private boolean keepHeadingPath = true;

    /** 围栏代码块与表格是否作为原子单元 */
    private boolean preserveCodeBlock = true;

    private final Markdown markdown = new Markdown();

    @Data
    public static class Markdown {

        /** MARKDOWN 类型的策略覆盖（markdown-structural / recursive / token） */
        private String strategy = "markdown-structural";

        /** headingPath 最深采集层级（H1~H3） */
        private int maxHeadingLevel = 3;
    }

    /**
     * 按 docType 取策略覆盖配置；返回 null 表示走默认路由。
     */
    public String strategyForDocType(String docType) {
        return "MARKDOWN".equals(docType) ? markdown.getStrategy() : null;
    }

    public SplitOptions toOptions() {
        return new SplitOptions(chunkSizeTokens, overlapTokens, minChunkChars,
                maxChunkTokens, keepHeadingPath, preserveCodeBlock);
    }
}
