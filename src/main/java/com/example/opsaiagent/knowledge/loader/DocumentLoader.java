package com.example.opsaiagent.knowledge.loader;

import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import java.util.Collection;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.ingest.parser.DocumentParserStrategyFactory;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.service.KnowledgeDocumentIndexer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档加载器（内置知识库增量加载）
 * 启动或手动触发 /knowledge/reload 时执行：
 * - 以文件内容 SHA-256 指纹判断「同名覆盖」：文件内容变化后自动重载
 * - 增量加载缺失/变更文档，不重复加载未变化文档
 * - 清理目录中已删除文档的残留向量（仅内置文档，不误删 upload:/case: 用户向量）
 * 检索投影 metadata.contentHash 保存文档指纹，用于后续比对。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentLoader {

    /** 用户上传文档向量 source 前缀（reload 时绝不触碰） */
    public static final String UPLOAD_SOURCE_PREFIX = "upload:";
    /** 故障案例沉淀向量 source 前缀（reload 时绝不触碰） */
    public static final String CASE_SOURCE_PREFIX = "case:";
    /** 向量 metadata 中保存内容指纹的字段名 */
    public static final String HASH_METADATA_KEY = "contentHash";
    /** 内置知识层级 */
    public static final String LAYER_BUILTIN = "BUILTIN";
    /** 内置知识文件路径前缀 */
    public static final String CLASSPATH_KNOWLEDGE_PREFIX = "classpath:knowledge/";

    // 复用索引器，在同一事务中保存文档、分块与向量。
    private final KnowledgeDocumentIndexer indexer;
    private final KnowledgeChunkMapper chunkMapper;
    private final PlatformTransactionManager transactionManager;

    // 文档解析策略工厂（与用户上传共用同一套解析配置）
    private final DocumentParserStrategyFactory parserStrategyFactory;

    // 资源加载器
    private final ResourcePatternResolver resourcePatternResolver;

    // 查询 MySQL 检索投影状态
    private final KnowledgeVectorStore vectorStore;

    // 内置知识文档元数据（knowledge_document，供统计/列表/溯源使用）
    private final KnowledgeDocumentMapper documentMapper;

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 重新加载内置知识库文档（增量）
     * 供启动时 @PostConstruct 与 /api/ops/knowledge/reload 接口共同调用。
     * @return 无入参，返回加载统计：status / loaded / targets / message
     */
    public synchronized Map<String, Object> reload() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, String> expected = listKnowledgeFiles();
            if (expected.isEmpty()) {
                result.put("status", "skipped");
                result.put("message", "知识库目录为空");
                return result;
            }
            Map<String, String> existing;
            try {
                existing = listExistingSourceHashes();
            } catch (Exception e) {
                log.warn("读取内置向量状态失败，尝试完整加载: {}", e.getMessage());
                return fullLoadFallback(result);
            }
            Set<String> targets = computeLoadTargets(expected, existing);
            targets.addAll(findIncompleteBuiltinSources(expected));
            Map<String, String> loadMap = new LinkedHashMap<>();
            expected.forEach((source, hash) -> {
                if (targets.contains(source)) loadMap.put(source, hash);
            });
            int loaded = loadAndIndex(loadMap);
            Set<String> staleSources = computeStaleSources(existing.keySet(), expected.keySet());
            if (!staleSources.isEmpty()) deleteBySources(staleSources);
            removeObsoleteBuiltinDocuments(expected.keySet());
            result.put("status", "success");
            result.put("loaded", loaded);
            result.put("targets", targets.size());
            result.put("message", targets.isEmpty() ? "知识库已是最新，无需加载" : "文档、分块与向量加载完成");
        } catch (Exception e) {
            log.error("内置知识库加载失败", e);
            result.put("status", "failed");
            result.put("message", "文档加载失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 首次启动兜底：不依赖向量库状态，直接全量加载目录下所有文档
     * @param result 待填充的加载结果
     * @return 全量加载结果
     */
    private Map<String, Object> fullLoadFallback(Map<String, Object> result) {
        try {
            Map<String, String> expected = listKnowledgeFiles();
            int loaded = loadAndIndex(expected);
            removeObsoleteBuiltinDocuments(expected.keySet());
            result.put("status", "success");
            result.put("loaded", loaded);
            result.put("targets", expected.size());
            result.put("message", "全量加载完成");
        } catch (Exception ex) {
            log.error("知识库文档全量加载失败", ex);
            result.put("status", "failed");
            result.put("message", "文档加载失败: " + ex.getMessage());
        }
        return result;
    }

    /**
     * 计算需要加载的文档 source：
     * - 向量库中不存在（缺失）
     * - 指纹不一致（同名覆盖，文件内容已变化）
     * - 向量库中有记录但无指纹（旧数据，保守重载一次，重载后写入指纹）
     */
    public Set<String> computeLoadTargets(Map<String, String> expected, Map<String, String> existing) {
        Set<String> targets = new HashSet<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String source = entry.getKey();
            String fileHash = entry.getValue();
            String storedHash = existing.get(source);
            if (storedHash == null) {
                targets.add(source); // 缺失
            } else if (!storedHash.equals(fileHash)) {
                targets.add(source); // 同名内容变更
            }
        }
        return targets;
    }

    /**
     * 计算残留的内置文档 source：
     * 向量库中存在、但知识库目录已无此文件，且不属于用户上传（upload:）或案例（case:）前缀。
     * @param existing 向量库中全部 source
     * @param expected 知识库目录中的文件 source
     * @return 需要清理的 source 集合
     */
    public Set<String> computeStaleSources(Set<String> existing, Set<String> expected) {
        Set<String> stale = new HashSet<>(existing);
        stale.removeAll(expected);
        return stale.stream()
                .filter(s -> !s.startsWith(UPLOAD_SOURCE_PREFIX) && !s.startsWith(CASE_SOURCE_PREFIX))
                .collect(Collectors.toSet());
    }

    /**
     * 删除指定 source 的所有向量（按 IN 参数精确删除）
     * @param sources source 集合
     * @return 删除条数
     */
    public int deleteBySources(Collection<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        return vectorStore.deleteBySources(sources);
    }

    /**
     * 列出知识库目录下的所有文档文件名及其内容指纹
     * @return source -> SHA-256 hex
     */
    Map<String, String> listKnowledgeFiles() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Resource[] resources = resourcePatternResolver.getResources("classpath*:knowledge/*");
        for (Resource r : resources) {
            String fileName = r.getFilename();
            if (fileName == null || DocumentParserStrategyFactory.resolveDocType(fileName) == null) {
                continue; // 仅统计可解析类型，与 loadAndIndex 保持一致
            }
            try (InputStream input = r.getInputStream()) {
                sources.put(fileName, sha256Hex(input));
            }
        }
        return sources;
    }

    /**
     * 查询检索投影中已有的 source -> contentHash 映射
     * 旧数据没有 contentHash 时值为 null（视为需要重载校验）
     */
    Map<String, String> listExistingSourceHashes() {
        Map<String, String> result = new HashMap<>();
        for (Document document : vectorStore.documents()) {
            Object source = document.getMetadata().get("source");
            Object hash = document.getMetadata().get("contentHash");
            if (source != null) result.put(source.toString(), hash == null ? null : hash.toString());
        }
        return result;
    }

    /**
     * 加载 resources/knowledge/ 下指定文档，切分后存入向量库
     * @param targetFiles source -> contentHash 映射；只加载 key 集合命中的文件
     * @return 实际写入向量库的 chunk 数量
     */
    public int loadAndIndex(Map<String, String> targetFiles) {
        if (targetFiles == null || targetFiles.isEmpty()) return 0;
        try {
            Map<String, Resource> resources = listKnowledgeResources();
            int indexed = 0;
            for (Map.Entry<String, String> target : targetFiles.entrySet()) {
                String source = target.getKey();
                Resource resource = resources.get(source);
                if (resource == null || DocumentParserStrategyFactory.resolveDocType(source) == null) continue;
                String rawContent = parseRawText(resource, source);
                Integer count = new TransactionTemplate(transactionManager).execute(status -> {
                    List<KnowledgeDocumentEntity> rows = documentMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                                    .eq(KnowledgeDocumentEntity::getLayer, LAYER_BUILTIN)
                                    .eq(KnowledgeDocumentEntity::getTenantId, "0")
                                    .eq(KnowledgeDocumentEntity::getSource, source));
                    KnowledgeDocumentEntity entity = rows.stream()
                            .filter(row -> source.equals(row.getSource())).findFirst().orElse(null);
                    boolean created = entity == null;
                    if (created) {
                        entity = new KnowledgeDocumentEntity();
                        entity.setVersion(1);
                        entity.setHitCount(0L);
                    } else if (!Objects.equals(entity.getRawContent(), rawContent)) {
                        entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
                    }
                    fillBuiltinEntity(entity, source, rawContent, LocalDateTime.now());
                    if (created) documentMapper.insert(entity);
                    indexer.reindexBuiltin(entity, target.getValue());
                    return entity.getChunkCount();
                });
                indexed += count == null ? 0 : count;
            }
            return indexed;
        } catch (IOException e) {
            throw new IllegalStateException("内置知识资源读取失败", e);
        }
    }

    /**
     * 检查旧内置数据是否缺正文、缺分块或源码被纯文本抽取破坏。
     * @param expected 当前目录文件及其指纹
     * @return 需要修复的内置文件集合
     */
    private Set<String> findIncompleteBuiltinSources(Map<String, String> expected) throws IOException {
        List<KnowledgeDocumentEntity> rows = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getLayer, LAYER_BUILTIN)
                        .eq(KnowledgeDocumentEntity::getTenantId, "0"));
        Map<String, KnowledgeDocumentEntity> bySource = new HashMap<>();
        rows.forEach(row -> bySource.put(row.getSource(), row));
        Map<String, Resource> resources = listKnowledgeResources();
        Set<String> repair = new HashSet<>();
        for (String source : expected.keySet()) {
            KnowledgeDocumentEntity row = bySource.get(source);
            if (row == null || !Objects.equals(row.getRawContent(), parseRawText(resources.get(source), source))) {
                repair.add(source);
                continue;
            }
            Long count = chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunkEntity>()
                    .eq(KnowledgeChunkEntity::getDocId, row.getId()));
            if (row.getChunkCount() == null || count == null || row.getChunkCount().longValue() != count
                    || (count == 0 && StringUtils.hasText(row.getRawContent()))) repair.add(source);
        }
        return repair;
    }

    /**
     * 删除目录中已不存在的内置文档和分块，不涉及上传及案例文档。
     * @param expectedSources 目录中仍存在的文件名集合
     * @return 无返回值
     */
    private void removeObsoleteBuiltinDocuments(Set<String> expectedSources) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<KnowledgeDocumentEntity> rows = documentMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDocumentEntity>().eq(KnowledgeDocumentEntity::getLayer, LAYER_BUILTIN)
                            .eq(KnowledgeDocumentEntity::getTenantId, "0"));
            for (KnowledgeDocumentEntity row : rows) {
                if (expectedSources.contains(row.getSource())) continue;
                vectorStore.deleteBySources(List.of(row.getSource()));
                chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>().eq(KnowledgeChunkEntity::getDocId, row.getId()));
                documentMapper.deleteById(row.getId());
            }
        });
    }

    /**
     * 填充平台公共内置文档，源码不经 Markdown 纯文本抽取。
     * @param entity 待保存实体
     * @param source 文件名
     * @param rawContent 完整 Markdown 源码或其他格式的抽取正文
     * @param now 本次入库时间
     * @return 无返回值
     */
    private void fillBuiltinEntity(KnowledgeDocumentEntity entity, String source, String rawContent, LocalDateTime now) {
        entity.setTitle(stripExtension(source));
        entity.setDocType(DocumentParserStrategyFactory.resolveDocType(source));
        entity.setLayer(LAYER_BUILTIN);
        entity.setTenantId("0");
        entity.setDeptId(null);
        entity.setVisibility("PUBLIC");
        entity.setReviewStatus("APPROVED");
        entity.setReviewLevel("NONE");
        entity.setPublishStatus("BUILDING");
        entity.setPublishError(null);
        entity.setPublishedAt(null);
        entity.setTrustStatus("VERIFIED");
        entity.setOwner("system");
        entity.setSource(source);
        entity.setFilePath(CLASSPATH_KNOWLEDGE_PREFIX + source);
        entity.setRawContent(rawContent);
        if (entity.getSubmittedAt() == null) entity.setSubmittedAt(now);
    }

    /**
     * 读取与索引相同的正文，Markdown 逐字保留标题、换行、表格及代码围栏。
     * @param resource 文件资源
     * @param fileName 文件名
     * @return 完整源码或抽取正文；读取失败时抛出异常，避免保存空正文
     */
    private String parseRawText(Resource resource, String fileName) throws IOException {
        if (resource == null) throw new IOException("内置文件不存在: " + fileName);
        String docType = DocumentParserStrategyFactory.resolveDocType(fileName);
        if ("MARKDOWN".equals(docType)) return resource.getContentAsString(StandardCharsets.UTF_8);
        return parserStrategyFactory.getStrategy(docType).parse(resource).stream()
                .map(Document::getText).filter(Objects::nonNull).collect(Collectors.joining("\n\n"));
    }

    /**
     * 列出内置资源。
     * @return 无入参，返回以文件名为键的资源映射
     */
    private Map<String, Resource> listKnowledgeResources() throws IOException {
        Map<String, Resource> resources = new HashMap<>();
        for (Resource resource : resourcePatternResolver.getResources("classpath*:knowledge/*")) {
            if (resource.getFilename() != null) resources.put(resource.getFilename(), resource);
        }
        return resources;
    }

    /**
     * 以文件名去掉扩展名作为默认标题。
     * @param fileName 文件名
     * @return 不含扩展名的标题
     */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * 计算输入流内容 SHA-256 hex
     */
    public String sha256Hex(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }
}
