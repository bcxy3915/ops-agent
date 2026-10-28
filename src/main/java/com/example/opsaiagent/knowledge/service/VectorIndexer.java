package com.example.opsaiagent.knowledge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量入库公共组件：统一「文本切分」与「分批写入向量库」逻辑。
 *
 * <p>内置知识库（DocumentLoader）、用户上传文档（KnowledgeDocumentService）、
 * 故障案例沉淀（KnowledgeCaseService）三处原本各自重复 TokenTextSplitter 配置
 * 与 batch=10 分批写入，现统一收敛到此处。</p>
 */
@Component
@RequiredArgsConstructor
public class VectorIndexer {

    /** 分批写入大小（外部 embedding API 单批上限 10） */
    private static final int BATCH_SIZE = 10;

    private final VectorStore vectorStore;

    /**
     * 将单段文本切分为块；切分后若为空（短文本可能被切分器丢弃），保留一个块兜底保证可检索。
     */
    public List<Document> split(String text) {
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
        return splitDocs(docs);
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
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        return splitter.apply(docs);
    }
}