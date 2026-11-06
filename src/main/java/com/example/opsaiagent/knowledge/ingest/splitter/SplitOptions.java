package com.example.opsaiagent.knowledge.ingest.splitter;

/**
 * 切分参数，由 SplitProperties 绑定配置生成。
 *
 * token 计量为 CL100K_BASE 近似（实际 embedding 模型 tokenizer 不一致），
 * 因此 chunkSizeTokens 只是预算，maxChunkTokens 是硬上限，双约束兜底。
 *
 * @param chunkSizeTokens   目标块 token 预算
 * @param overlapTokens     相邻块重叠 token 数（约 15%，落在句边界上）
 * @param minChunkChars     尾块最小字符数，不足则并入前一块
 * @param maxChunkTokens    单块 token 硬上限（防御 embedding 输入限制）
 * @param keepHeadingPath   embedding 文本是否携带「标题｜章节路径」上下文头
 * @param preserveCodeBlock 围栏代码块与表格是否作为原子单元不被切断
 */
public record SplitOptions(int chunkSizeTokens, int overlapTokens, int minChunkChars,
                           int maxChunkTokens, boolean keepHeadingPath, boolean preserveCodeBlock) {
}
