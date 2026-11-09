package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.ingest.splitter.MarkdownStructuralSplitter;
import com.example.opsaiagent.knowledge.ingest.splitter.SplitContext;
import com.example.opsaiagent.knowledge.ingest.splitter.SplitProperties;
import com.example.opsaiagent.knowledge.ingest.splitter.TextChunkStrategy;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
    private final KnowledgeVectorStore vectorStore;
    private final SplitProperties splitProperties;

    /**
     * 重建文档索引：清空旧数据 → 切分 → 写分块 → 写向量 → 回写分块数。
     *
     * @param entity       文档实体（含 rawContent）
     * @param vectorSource 向量 source 标识，同时用于清理旧向量
     * @return 无返回值
     */
    public void reindex(KnowledgeDocumentEntity entity, String vectorSource) {
        reindex(entity, vectorSource, Map.of(), true);
    }

    /**
     * 重建内置知识索引，复用分块落库流程并保留文件指纹和原有向量正文口径。
     * @param entity 平台内置文档实体，正文为完整源码
     * @param contentHash 文件内容指纹
     * @return 无返回值
     */
    public void reindexBuiltin(KnowledgeDocumentEntity entity, String contentHash) {
        reindex(entity, entity.getSource(), Map.of("contentHash", contentHash), false);
    }

    /**
     * 在调用方事务中替换文档分块和向量。
     * @param entity 文档实体
     * @param vectorSource 向量来源标识
     * @param extraMetadata 需附加到向量的元数据
     * @param addContextHeader 是否为向量正文添加上下文头
     * @return 无返回值
     */
    private void reindex(KnowledgeDocumentEntity entity, String vectorSource,
                         Map<String, Object> extraMetadata, boolean addContextHeader) {
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

        List<Document> vectorDocs = buildVectorDocs(entity, chunks, chunkEntities, vectorSource, addContextHeader);
        vectorDocs.forEach(doc -> doc.getMetadata().putAll(extraMetadata));
        vectorIndexer.write(vectorDocs);
        entity.setChunkCount(chunkEntities.size());
        documentMapper.updateById(entity);
        log.info("文档索引完成: docId={}, source={}, chunks={}", docId, vectorSource, chunkEntities.size());
    }

    /**
     * 切分文档：单次委托策略门面，按 docType 路由
     * （MARKDOWN → markdown-structural 结构化切分，其余 → recursive 递归边界切分，
     * 策略异常时由 Resolver 降级 token 兜底，详见切分设计文档）。
     */
    private List<Document> split(KnowledgeDocumentEntity entity, String content) {
        SplitContext context = new SplitContext(entity.getDocType(), entity.getSource(),
                entity.getTitle(), entity.getLayer(), entity.getTenantId(), null);
        return vectorIndexer.split(List.of(new Document(content)), context);
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
            chunkEntity.setSection(resolveSection(chunk, entity.getDocType()));
            chunkEntities.add(chunkEntity);
            ordinal++;
        }
        return chunkEntities;
    }

    /** 章节优先取策略产出的 headingPath（如「手册 > 数据库 > 主从切换」），缺失时退回首行启发式 */
    private String resolveSection(Document chunk, String docType) {
        Object headingPath = chunk.getMetadata().get(MarkdownStructuralSplitter.METADATA_SECTION);
        if (headingPath instanceof String path && StringUtils.hasText(path)) {
            return path.length() > 200 ? path.substring(0, 200) : path;
        }
        return extractSection(chunk.getText(), docType);
    }

    /**
     * 将展示分块转换为携带权限及溯源元数据的向量文档。
     * @param entity 原始文档实体
     * @param chunks 已切分的正文
     * @param chunkEntities 已保存的展示分块
     * @param vectorSource 向量来源标识
     * @param addContextHeader 是否允许附加检索上下文头
     * @return 待写入的向量文档
     */
    private List<Document> buildVectorDocs(KnowledgeDocumentEntity entity, List<Document> chunks,
                                           List<KnowledgeChunkEntity> chunkEntities, String vectorSource,
                                           boolean addContextHeader) {
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
        boolean keepHeader = addContextHeader && splitProperties.isKeepHeadingPath();
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
            // 章节路径（headingPath 优先）与切分策略标记，供检索展示与重索引识别
            metadata.put(MarkdownStructuralSplitter.METADATA_SECTION,
                    chunkEntity.getSection() == null ? "" : chunkEntity.getSection());
            metadata.put("splitStrategy",
                    String.valueOf(chunk.getMetadata().getOrDefault("splitStrategy", "")));
            metadata.put("splitVersion",
                    chunk.getMetadata().getOrDefault("splitVersion", TextChunkStrategy.CURRENT_SPLIT_VERSION));
            // 展示与检索文本分离：向量文本携带「标题｜章节」上下文头提升召回，
            // knowledge_chunk.content 永远存原始块文本用于前端展示
            Document vectorDocument = new Document(embeddingText(title, chunkEntity.getSection(), chunk.getText(), keepHeader), metadata);
            vectorDocs.add(vectorDocument);
            chunkEntity.setVectorId(vectorDocument.getId());
            chunkMapper.updateById(chunkEntity);
        }
        return vectorDocs;
    }

    /** embedding 上下文头：【标题｜章节路径】，两者均缺失时不加头 */
    private String embeddingText(String title, String section, String text, boolean keepHeader) {
        if (!keepHeader) {
            return text;
        }
        boolean hasTitle = StringUtils.hasText(title);
        boolean hasSection = StringUtils.hasText(section);
        if (!hasTitle && !hasSection) {
            return text;
        }
        String header = hasTitle && hasSection ? title + "｜" + section : (hasTitle ? title : section);
        return "【" + header + "】\n" + text;
    }

    /**
     * 在业务事务中登记旧投影删除，提交后由同步器处理 ES。
     * @param vectorSource 来源
     * @return 无返回值
     */
    private void deleteVectors(String vectorSource) {
        vectorStore.deleteBySources(List.of(vectorSource));
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
