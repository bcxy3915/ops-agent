package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.ingest.parser.DocumentParserStrategyFactory;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识文档索引器：把文档全文切分为分块、写入 knowledge_chunk 并向量化入库。
 *
 * 由后台索引任务在独立事务中调用，不参与审核/确认等业务事务，
 * 一次执行对同一文档是幂等的（先清空旧分块与向量再重建）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentIndexer {

    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE);

    /** knowledge_chunk 批量插入的每批条数 */
    private static final int CHUNK_INSERT_BATCH_SIZE = 500;

    /** Markdown 类型标识 */
    private static final String DOC_TYPE_MARKDOWN = "MARKDOWN";

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final VectorIndexer vectorIndexer;
    private final DocumentParserStrategyFactory parserStrategyFactory;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 重建文档索引：清空旧数据 → 切分 → 写分块 → 写向量 → 回写分块数。
     *
     * @param entity       文档实体（含 rawContent）
     * @param vectorSource 向量 source 标识，同时用于清理旧向量
     */
    public void reindex(KnowledgeDocumentEntity entity, String vectorSource) {
        Long docId = entity.getId();
        // 幂等：先清空该文档已有的分块与向量，重复执行不会产生重复数据
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocId, docId));
        deleteVectors(vectorSource);

        String content = entity.getRawContent();
        if (!StringUtils.hasText(content)) {
            entity.setChunkCount(0);
            documentMapper.updateById(entity);
            log.warn("文档内容为空，仅清理旧索引: docId={}", docId);
            return;
        }

        List<Document> chunks = split(entity, content);
        List<KnowledgeChunkEntity> chunkEntities = buildChunks(entity, chunks);
        for (int i = 0; i < chunkEntities.size(); i += CHUNK_INSERT_BATCH_SIZE) {
            chunkMapper.insertBatch(
                    chunkEntities.subList(i, Math.min(i + CHUNK_INSERT_BATCH_SIZE, chunkEntities.size())));
        }

        vectorIndexer.write(buildVectorDocs(entity, chunks, chunkEntities, vectorSource));
        entity.setChunkCount(chunkEntities.size());
        documentMapper.updateById(entity);
        log.info("文档索引完成: docId={}, source={}, chunks={}", docId, vectorSource, chunkEntities.size());
    }

    /**
     * 切分文档：Markdown 先按标题层级结构化切分，短文本被全部丢弃时退化为整段切分。
     */
    private List<Document> split(KnowledgeDocumentEntity entity, String content) {
        if (DOC_TYPE_MARKDOWN.equals(entity.getDocType())) {
            List<Document> structured = parserStrategyFactory.getStrategy(DOC_TYPE_MARKDOWN)
                    .parse(new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
            List<Document> chunks = structured.isEmpty() ? List.of() : vectorIndexer.split(structured);
            if (!chunks.isEmpty()) {
                return chunks;
            }
        }
        return vectorIndexer.split(content);
    }

    private List<KnowledgeChunkEntity> buildChunks(KnowledgeDocumentEntity entity, List<Document> chunks) {
        List<KnowledgeChunkEntity> chunkEntities = new ArrayList<>(chunks.size());
        int ordinal = 1;
        for (Document chunk : chunks) {
            KnowledgeChunkEntity chunkEntity = new KnowledgeChunkEntity();
            chunkEntity.setDocId(entity.getId());
            chunkEntity.setTenantId(entity.getTenantId());
            chunkEntity.setDeptId(entity.getDeptId());
            chunkEntity.setOrdinal(ordinal);
            chunkEntity.setContent(chunk.getText());
            chunkEntity.setSection(extractSection(chunk.getText(), entity.getDocType()));
            chunkEntities.add(chunkEntity);
            ordinal++;
        }
        return chunkEntities;
    }

    private List<Document> buildVectorDocs(KnowledgeDocumentEntity entity, List<Document> chunks,
                                           List<KnowledgeChunkEntity> chunkEntities, String vectorSource) {
        String tenantId = entity.getTenantId() == null ? "" : entity.getTenantId();
        String deptId = entity.getDeptId() == null ? "" : String.valueOf(entity.getDeptId());
        String visibility = entity.getVisibility();
        if (visibility == null) {
            // 与原索引口径一致：案例文档默认租户可见，普通文档默认个人可见
            visibility = "CASE".equals(entity.getLayer()) ? "TENANT" : "PERSONAL";
        }
        String trustStatus = entity.getTrustStatus() == null ? "" : entity.getTrustStatus();
        String owner = entity.getOwner() == null ? "" : entity.getOwner();
        String reviewStatus = entity.getReviewStatus() == null ? "" : entity.getReviewStatus();
        String title = entity.getTitle() == null ? "" : entity.getTitle();
        String layer = entity.getLayer() == null ? "" : entity.getLayer();

        List<Document> vectorDocs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            KnowledgeChunkEntity chunkEntity = chunkEntities.get(i);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", vectorSource);
            metadata.put("docId", String.valueOf(entity.getId()));
            metadata.put("title", title);
            metadata.put("layer", layer);
            metadata.put("trustStatus", trustStatus);
            metadata.put("tenantId", tenantId);
            metadata.put("deptId", deptId);
            metadata.put("visibility", visibility);
            metadata.put("reviewStatus", reviewStatus);
            metadata.put("owner", owner);
            metadata.put("ordinal", String.valueOf(i + 1));
            metadata.put("chunkId", String.valueOf(chunkEntity.getId()));
            vectorDocs.add(new Document(chunk.getText(), metadata));
        }
        return vectorDocs;
    }

    private void deleteVectors(String vectorSource) {
        try {
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'source' = ?", vectorSource);
        } catch (Exception e) {
            log.warn("删除文档向量失败（表可能不存在）: source={}, err={}", vectorSource, e.getMessage());
        }
    }

    /**
     * 从块文本提取章节标题（markdown 取首个标题行；其他类型取首个非空行截断）。
     */
    static String extractSection(String text, String docType) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (DOC_TYPE_MARKDOWN.equals(docType)) {
            Matcher m = MD_HEADING.matcher(text);
            if (m.find()) {
                String heading = m.group(1).trim();
                return heading.length() > 100 ? heading.substring(0, 100) : heading;
            }
        }
        String firstLine = text.lines().filter(StringUtils::hasText).findFirst().orElse("");
        return firstLine.length() > 100 ? firstLine.substring(0, 100) : firstLine;
    }
}
