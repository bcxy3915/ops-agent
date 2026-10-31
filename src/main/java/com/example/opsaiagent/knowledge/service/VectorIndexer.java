package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量入库公共组件：统一「文本切分」与「分批写入向量库」逻辑。
 *
 * 内置知识库（DocumentLoader）、用户上传文档（KnowledgeDocumentService）、
 * 故障案例沉淀（KnowledgeCaseService）三处原本各自重复 TokenTextSplitter 配置
 * 与 batch=10 分批写入，现统一收敛到此处。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorIndexer {

    /** 分批写入大小（外部 embedding API 单批上限 10） */
    private static final int BATCH_SIZE = 10;

    /**
     * 切分器块数硬上限。该值仅作防御性兜底，实际长度约束由 maxChars 在切分前显式校验，
     * 避免 TokenTextSplitter 达到 maxNumChunks 后静默丢弃剩余内容。
     */
    private static final int MAX_NUM_CHUNKS = 100_000;

    /** 解析后文本字符上限，超出显式拒绝（默认 1000 万字符，约覆盖 50MB 中文文本） */
    @Value("${ops-agent.knowledge.ingest.max-chars:10000000}")
    private long maxChars = 10_000_000;

    private final VectorStore vectorStore;

    /**
     * 将单段文本切分为块；切分后若为空（短文本可能被切分器丢弃），保留一个块兜底保证可检索。
     */
    public List<Document> split(String text) {
        checkTextLength(text);
        List<Document> chunks = splitDocs(List.of(new Document(text)));
        if (chunks.isEmpty()) {
            return List.of(new Document(text));
        }
        return chunks;
    }

    /**
     * 对已解析出的文档列表（如 Markdown/PDF 阅读器产物）进行切分。
     */
    public List<Document> split(List<Document> docs) {
        long totalChars = docs.stream()
                .map(Document::getText)
                .filter(java.util.Objects::nonNull)
                .mapToLong(String::length)
                .sum();
        checkTextLength(totalChars);
        return splitDocs(docs);
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
     */
    public int write(List<Document> docs) {
        int indexed = 0;
        for (int i = 0; i < docs.size(); i += BATCH_SIZE) {
            List<Document> batch = docs.subList(i, Math.min(i + BATCH_SIZE, docs.size()));
            vectorStore.add(batch);
            indexed += batch.size();
        }
        return indexed;
    }

    private List<Document> splitDocs(List<Document> docs) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(200)
                .withMaxNumChunks(MAX_NUM_CHUNKS)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(docs);
        if (chunks.size() >= MAX_NUM_CHUNKS) {
            // 防御性兜底：maxChars 校验在前，理论上不应到达；一旦到达必须显式失败而非静默截断
            throw new IllegalStateException(
                    "文档切分块数达到上限 " + MAX_NUM_CHUNKS + "，内容可能被截断，请拆分后上传");
        }
        return chunks;
    }
}