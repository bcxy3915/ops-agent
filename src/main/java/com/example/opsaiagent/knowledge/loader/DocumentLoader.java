package com.example.opsaiagent.knowledge.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.ingest.parser.DocumentParserStrategy;
import com.example.opsaiagent.knowledge.ingest.parser.DocumentParserStrategyFactory;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.service.VectorIndexer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
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
 * vector_store.metadata.contentHash 保存文档指纹，用于后续比对。
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

    // 向量入库公共组件（切分 + 分批写入）
    private final VectorIndexer vectorIndexer;

    // 文档解析策略工厂（与用户上传共用同一套解析配置）
    private final DocumentParserStrategyFactory parserStrategyFactory;

    // 资源加载器
    private final ResourcePatternResolver resourcePatternResolver;

    // 用于查询 vector_store 表的状态
    private final JdbcTemplate jdbcTemplate;

    // 内置知识文档元数据（knowledge_document，供统计/列表/溯源使用）
    private final KnowledgeDocumentMapper documentMapper;

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 重新加载内置知识库文档（增量）
     * 供启动时 @PostConstruct 与 /api/ops/knowledge/reload 接口共同调用。
     * @return 加载统计：status / loaded / targets / message
     */
    public Map<String, Object> reload() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 1. 列出知识库目录下所有文档及其内容指纹
            Map<String, String> expected = listKnowledgeFiles();
            if (expected.isEmpty()) {
                log.warn("知识库目录为空，无需加载");
                result.put("status", "skipped");
                result.put("message", "知识库目录为空");
                return result;
            }

            // 2. 查询向量库已有 source 及其指纹
            Map<String, String> existing = listExistingSourceHashes();
            log.info("向量库已包含 {} 个文档", existing.size());

            // 3. 计算增量加载目标（缺失 / 同名内容变更 / 旧数据无指纹）
            Set<String> loadTargets = computeLoadTargets(expected, existing);
            // 4. 计算残留内置文档（文件已从目录移除，仅限内置，不动 upload:/case:）
            Set<String> staleSources = computeStaleSources(existing.keySet(), expected.keySet());
            log.info("待加载 {} 个文档：{}；待清理残留 {} 个：{}",
                    loadTargets.size(), loadTargets, staleSources.size(), staleSources);

            // 5. 先清理残留，再删除变更文档的旧向量（防止残留），最后增量加载
            if (!staleSources.isEmpty()) {
                int deleted = deleteBySources(staleSources);
                log.info("清理 {} 个已移除文档的残留向量 {} 条", staleSources.size(), deleted);
            }
            if (!loadTargets.isEmpty()) {
                deleteBySources(loadTargets);
            }
            Map<String, String> loadMap = new LinkedHashMap<>();
            for (String s : loadTargets) {
                if (expected.containsKey(s)) {
                    loadMap.put(s, expected.get(s));
                }
            }
            int loaded = loadAndIndex(loadMap);
            // 同步 knowledge_document 元数据：官方知识包必须在文档表中有记录，
            // 否则总览/文档列表/溯源查询只看到用户上传的私有文档
            syncBuiltinDocuments(expected, loadTargets);

            result.put("status", "success");
            result.put("loaded", loaded);
            result.put("targets", loadTargets.size());
            result.put("message", loaded > 0 ? "文档加载完成" : "知识库已是最新，无需加载");
            return result;
        } catch (Exception e) {
            // vector_store 表不存在或查询失败：视为首次启动，全量加载
            log.info("向量库状态检查失败（可能是首次启动），准备全量加载文档：{}", e.getMessage());
            return fullLoadFallback(result);
        }
    }

    /**
     * 首次启动兜底：不依赖向量库状态，直接全量加载目录下所有文档
     */
    private Map<String, Object> fullLoadFallback(Map<String, Object> result) {
        try {
            Map<String, String> expected = listKnowledgeFiles();
            int loaded = loadAndIndex(expected);
            // 兜底全量加载时同样补齐内置文档元数据
            syncBuiltinDocuments(expected, expected.keySet());
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
    public int deleteBySources(java.util.Collection<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        List<String> list = new ArrayList<>(sources);
        String placeholders = String.join(",", Collections.nCopies(list.size(), "?"));
        String sql = "DELETE FROM vector_store WHERE metadata->>'source' IN (" + placeholders + ")";
        return jdbcTemplate.update(sql, list.toArray());
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
            sources.put(fileName, sha256Hex(r.getInputStream()));
        }
        return sources;
    }

    /**
     * 查询 vector_store 表里已有的 source -> contentHash 映射
     * 旧数据没有 contentHash 时值为 null（视为需要重载校验）
     */
    Map<String, String> listExistingSourceHashes() {
        String sql = "SELECT DISTINCT metadata->>'source' AS src, metadata->>'contentHash' AS hash "
                + "FROM vector_store WHERE metadata->>'source' IS NOT NULL";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object src = row.get("src");
            Object hash = row.get("hash");
            if (src != null) {
                result.put(String.valueOf(src), hash == null ? null : String.valueOf(hash));
            }
        }
        return result;
    }

    /**
     * 加载 resources/knowledge/ 下指定文档，切分后存入向量库
     * @param targetFiles source -> contentHash 映射；只加载 key 集合命中的文件
     * @return 实际写入向量库的 chunk 数量
     */
    public int loadAndIndex(Map<String, String> targetFiles) {
        if (targetFiles == null || targetFiles.isEmpty()) {
            return 0;
        }
        List<Document> allDocs = new ArrayList<>();
        int indexed = 0;

        try {
            // 1. 通过解析策略统一加载目录下各类型文档（与用户上传共用同一套解析配置）
            Resource[] resources = resourcePatternResolver.getResources("classpath*:knowledge/*");
            for (Resource res : resources) {
                String fileName = res.getFilename();
                if (fileName == null || !targetFiles.containsKey(fileName)) {
                    continue;
                }
                String docType = DocumentParserStrategyFactory.resolveDocType(fileName);
                if (docType == null) {
                    log.warn("跳过不支持的文件类型: {}", fileName);
                    continue;
                }
                String hash = targetFiles.get(fileName);
                DocumentParserStrategy strategy = parserStrategyFactory.getStrategy(docType);
                List<Document> docs = strategy.parse(res);
                for (Document d : docs) {
                    // 内置文档统一写入 source 与内容指纹，用于增量比对与残留清理
                    d.getMetadata().put("source", fileName);
                    d.getMetadata().put(HASH_METADATA_KEY, hash);
                }
                allDocs.addAll(docs);
                log.info("加载 {}: {}", docType, fileName);
            }

            // 2. 文本切分
            List<Document> chunks = vectorIndexer.split(allDocs);
            log.info("文档切分完成，共 {} 块", chunks.size());

            // 3. 填充内置知识元数据后分批写入向量库
            for (Document doc : chunks) {
                // 内置知识统一归属平台公共知识，供检索范围过滤使用
                doc.getMetadata().putIfAbsent("tenantId", "0");
                doc.getMetadata().putIfAbsent("deptId", "");
                doc.getMetadata().putIfAbsent("visibility", "PUBLIC");
                doc.getMetadata().putIfAbsent("reviewStatus", "APPROVED");
                doc.getMetadata().putIfAbsent("owner", "system");
                doc.getMetadata().putIfAbsent("layer", "BUILTIN");
            }
            indexed = vectorIndexer.write(chunks);
            log.info("向量入库完成，共 {} 条", indexed);
        } catch (Exception e) {
            log.error("文档加载失败", e);
        }
        return indexed;
    }

    /**
     * 同步内置知识文档元数据到 knowledge_document。
     * 内置文档此前只写入向量库，统计/列表/溯源查不到记录，前端显示 0 篇。
     * 规则：
     * - 目录中每个文件都必须有一条 layer=BUILTIN 的记录；
     * - 新增文件插入记录，内容变化/向量重载/缺少全文时刷新记录；
     * - 目录中已删除的文件，对应记录一并清理。
     * @param expected 目录中文件名 -> 内容指纹
     * @param loadedSources 本次实际（重新）加载向量的文件名集合
     */
    void syncBuiltinDocuments(Map<String, String> expected, Set<String> loadedSources) {
        try {
            List<KnowledgeDocumentEntity> rows = documentMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                            .eq(KnowledgeDocumentEntity::getLayer, LAYER_BUILTIN));
            Map<String, KnowledgeDocumentEntity> existingBySource = new HashMap<>();
            for (KnowledgeDocumentEntity row : rows) {
                if (StringUtils.hasText(row.getSource())) {
                    existingBySource.put(row.getSource(), row);
                }
            }

            Map<String, Integer> chunkCounts = listChunkCounts();
            Map<String, Resource> resources = listKnowledgeResources();
            LocalDateTime now = LocalDateTime.now();
            int inserted = 0;
            int updated = 0;

            for (String source : expected.keySet()) {
                KnowledgeDocumentEntity row = existingBySource.get(source);
                if (row == null) {
                    KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
                    fillBuiltinEntity(entity, source, resources.get(source), chunkCounts, now);
                    entity.setVersion(1);
                    entity.setHitCount(0L);
                    documentMapper.insert(entity);
                    inserted++;
                    continue;
                }
                boolean needRefresh = loadedSources.contains(source) || !StringUtils.hasText(row.getRawContent());
                if (!needRefresh && Objects.equals(row.getChunkCount(), chunkCounts.getOrDefault(source, 0))) {
                    continue;
                }
                String oldRaw = row.getRawContent();
                fillBuiltinEntity(row, source, resources.get(source), chunkCounts, now);
                // 全文发生变化才递增版本，向量重载不影响版本
                if (StringUtils.hasText(row.getRawContent()) && !Objects.equals(oldRaw, row.getRawContent())) {
                    row.setVersion(row.getVersion() == null ? 1 : row.getVersion() + 1);
                }
                documentMapper.updateById(row);
                updated++;
            }

            // 清理目录中已不存在文件的内置记录
            List<KnowledgeDocumentEntity> obsolete = existingBySource.entrySet().stream()
                    .filter(e -> !expected.containsKey(e.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();
            for (KnowledgeDocumentEntity row : obsolete) {
                documentMapper.deleteById(row.getId());
            }
            log.info("内置知识元数据同步完成：新增 {}，更新 {}，清理 {}", inserted, updated, obsolete.size());
        } catch (Exception e) {
            // 元数据同步失败不影响向量加载结果，仅记录告警
            log.warn("内置知识元数据同步失败（不影响向量加载）: {}", e.getMessage());
        }
    }

    /**
     * 填充内置知识文档元数据字段
     */
    private void fillBuiltinEntity(KnowledgeDocumentEntity entity, String source, Resource resource,
                                   Map<String, Integer> chunkCounts, LocalDateTime now) {
        entity.setTitle(fileNameWithoutExtension(source));
        entity.setDocType(DocumentParserStrategyFactory.resolveDocType(source));
        entity.setLayer(LAYER_BUILTIN);
        entity.setTenantId("0");
        entity.setDeptId(null);
        entity.setVisibility("PUBLIC");
        entity.setReviewStatus("APPROVED");
        entity.setReviewLevel("NONE");
        entity.setPublishStatus("PUBLISHED");
        entity.setPublishError(null);
        entity.setPublishedAt(now);
        entity.setTrustStatus("VERIFIED");
        entity.setOwner("system");
        entity.setSource(source);
        entity.setFilePath(CLASSPATH_KNOWLEDGE_PREFIX + source);
        entity.setRawContent(parseRawText(resource, source));
        entity.setChunkCount(chunkCounts.getOrDefault(source, 0));
        if (entity.getSubmittedAt() == null) {
            entity.setSubmittedAt(now);
        }
    }

    /**
     * 解析内置文档全文（与向量化共用同一套解析策略）
     */
    private String parseRawText(Resource resource, String fileName) {
        if (resource == null) {
            return null;
        }
        String docType = DocumentParserStrategyFactory.resolveDocType(fileName);
        if (docType == null) {
            return null;
        }
        try {
            List<Document> docs = parserStrategyFactory.getStrategy(docType).parse(resource);
            return docs.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.warn("内置文档解析失败 {}: {}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * 统计向量库中每个 source 的 chunk 数量
     */
    Map<String, Integer> listChunkCounts() {
        String sql = "SELECT metadata->>'source' AS src, COUNT(*) AS cnt FROM vector_store "
                + "WHERE metadata->>'source' IS NOT NULL GROUP BY metadata->>'source'";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        Map<String, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object src = row.get("src");
            Object cnt = row.get("cnt");
            if (src != null) {
                result.put(String.valueOf(src), cnt instanceof Number n ? n.intValue() : 0);
            }
        }
        return result;
    }

    /**
     * 列出知识库目录下所有资源，文件名为 key
     */
    private Map<String, Resource> listKnowledgeResources() throws IOException {
        Map<String, Resource> resources = new HashMap<>();
        for (Resource r : resourcePatternResolver.getResources("classpath*:knowledge/*")) {
            if (r.getFilename() != null) {
                resources.put(r.getFilename(), r);
            }
        }
        return resources;
    }

    /**
     * 去掉扩展名作为默认标题
     */
    private String fileNameWithoutExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
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
