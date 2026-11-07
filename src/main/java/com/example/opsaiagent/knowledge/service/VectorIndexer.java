package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.ingest.splitter.SplitContext;
import com.example.opsaiagent.knowledge.ingest.splitter.TextChunkStrategyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 向量入库公共组件：统一「文本切分」与「分批写入向量库」逻辑。
 *
 * 切分已收敛为策略模式（knowledge.ingest.splitter 包）：
 * 按文档类型路由 markdown-structural / recursive / token，
 * 详见 docs/design/text-split/文本切分策略-技术设计与评估-20261031.md。
 *
 * 旧签名 split(text) / split(docs) 保留并委托新门面，调用方零改动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorIndexer {

    /** 分批写入大小（外部 embedding API 单批上限 10） */
    private static final int BATCH_SIZE = 10;

    /**
     * 切分器块数硬上限。该值仅作防御性兜底，实际长度约束由 maxChars 在切分前显式校验，
     * 避免达到上限后静默丢弃剩余内容。
     */
    private static final int MAX_NUM_CHUNKS = 100_000;

    /** 解析后文本字符上限，超出显式拒绝（默认 1000 万字符，约覆盖 50MB 中文文本） */
    @Value("${ops-agent.knowledge.ingest.max-chars:10000000}")
    private long maxChars = 10_000_000;

    private final KnowledgeVectorStore knowledgeVectorStore;
    private final TextChunkStrategyResolver strategyResolver;

    /**
     * 将单段文本切分为块；切分后若为空（短文本可能被切分器丢弃），保留一个块兜底保证可检索。
     */
    public List<Document> split(String text) {
        return split(List.of(new Document(text)), SplitContext.empty());
    }

    /**
     * 对已解析出的文档列表（如 PDF 按页产物）进行切分；无上下文时走默认策略路由。
     */
    public List<Document> split(List<Document> docs) {
        return split(docs, SplitContext.empty());
    }

    /**
     * 切分门面：长度校验 → 策略路由切分 → 空结果兜底 → 块数防御。
     * @param context 切分上下文（docType/title/layer 等），决定命中策略
     */
    public List<Document> split(List<Document> docs, SplitContext context) {
        long totalChars = docs.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .mapToLong(String::length)
                .sum();
        checkTextLength(totalChars);

        List<Document> chunks = strategyResolver.split(docs, context == null ? SplitContext.empty() : context);

        if (chunks.isEmpty()) {
            // 兜底：切分结果为空时保留原文块（继承旧 split(text) 语义），保证可检索
            chunks = docs.stream()
                    .filter(d -> d.getText() != null && !d.getText().isBlank())
                    .toList();
        }
        if (chunks.size() >= MAX_NUM_CHUNKS) {
            // 防御性兜底：maxChars 校验在前，理论上不应到达；一旦到达必须显式失败而非静默截断
            throw new IllegalStateException(
                    "文档切分块数达到上限 " + MAX_NUM_CHUNKS + "，内容可能被截断，请拆分后上传");
        }
        return chunks;
    }

    /**
     * 显式校验文本长度，超出上限时抛出异常而不是静默截断。
     * 供上传入口在解析完成后提前调用，尽早给用户明确反馈。
     */
    public void checkTextLength(String text) {
        checkTextLength(text == null ? 0 : text.length());
    }

    private void checkTextLength(long chars) {
        if (chars > maxChars) {
            log.warn("文档文本超长被拒绝: chars={}, maxChars={}", chars, maxChars);
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_INVALID,
                    "文档文本过长（" + chars + " 字符，上限 " + maxChars + "），请拆分后上传");
        }
    }

    /**
     * 分批写入向量库，返回实际写入条数。
     * @param docs 待写入的项目文档
     * @return 实际写入条数
     */
    public int write(List<Document> docs) {
        int indexed = 0;
        for (int i = 0; i < docs.size(); i += BATCH_SIZE) {
            List<Document> batch = docs.subList(i, Math.min(i + BATCH_SIZE, docs.size()));
            knowledgeVectorStore.write(batch);
            indexed += batch.size();
        }
        return indexed;
    }
}
