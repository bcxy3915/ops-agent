package com.example.opsaiagent.knowledge.ingest.splitter;

/**
 * 切分上下文：由调用方构造，携带文档元信息供策略路由与切分使用。
 *
 * @param docType          文档类型（MARKDOWN/PDF/TXT/DOCX...）
 * @param fileName         来源文件名
 * @param title            文档标题（用于 embedding 上下文头）
 * @param layer            知识层级（BUILTIN/PRIVATE/CASE）
 * @param tenantId         租户 ID
 * @param strategyOverride 显式指定策略名，优先于类型路由与默认策略
 */
public record SplitContext(String docType, String fileName, String title,
                           String layer, String tenantId, String strategyOverride) {

    /** 旧签名（split(text) / split(docs)）委托新门面时使用的空上下文，走默认策略路由 */
    public static SplitContext empty() {
        return new SplitContext(null, null, null, null, null, null);
    }

    public boolean hasOverride() {
        return strategyOverride != null && !strategyOverride.isBlank();
    }
}
