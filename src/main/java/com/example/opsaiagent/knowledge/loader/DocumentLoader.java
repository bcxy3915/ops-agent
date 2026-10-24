package com.example.opsaiagent.knowledge.loader;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // 向量存储
    private final VectorStore vectorStore;

    // 资源加载器
    private final ResourcePatternResolver resourcePatternResolver;

    // 用于查询 vector_store 表的状态
    private final JdbcTemplate jdbcTemplate;

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
        Resource[] mdFiles = resourcePatternResolver.getResources("classpath*:knowledge/*.md");
        Resource[] pdfFiles = resourcePatternResolver.getResources("classpath*:knowledge/*.pdf");
        for (Resource r : mdFiles) {
            sources.put(r.getFilename(), sha256Hex(r.getInputStream()));
        }
        for (Resource r : pdfFiles) {
            sources.put(r.getFilename(), sha256Hex(r.getInputStream()));
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
            // 1. 加载 markdown 文档
            Resource[] mdFiles = resourcePatternResolver.getResources("classpath*:knowledge/*.md");
            for (Resource md : mdFiles) {
                String fileName = md.getFilename();
                if (!targetFiles.containsKey(fileName)) {
                    continue;
                }
                String hash = targetFiles.get(fileName);
                MarkdownDocumentReaderConfig mdConfig = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("source", fileName)
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(md, mdConfig);
                List<Document> docs = reader.get();
                for (Document d : docs) {
                    d.getMetadata().put(HASH_METADATA_KEY, hash);
                }
                allDocs.addAll(docs);
                log.info("加载 Markdown: {}", fileName);
            }

            // 2. 加载 pdf 文档
            Resource[] pdfFiles = resourcePatternResolver.getResources("classpath*:knowledge/*.pdf");
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPageTopMargin(0)
                    .withPageBottomMargin(0)
                    .withPagesPerDocument(1)
                    .build();
            for (Resource pdf : pdfFiles) {
                String fileName = pdf.getFilename();
                if (!targetFiles.containsKey(fileName)) {
                    continue;
                }
                String hash = targetFiles.get(fileName);
                PagePdfDocumentReader reader = new PagePdfDocumentReader(pdf, config);
                List<Document> docs = reader.get();
                for (Document d : docs) {
                    d.getMetadata().put(HASH_METADATA_KEY, hash);
                }
                allDocs.addAll(docs);
                log.info("加载 PDF: {}", fileName);
            }

            // 3. 统一兜底设置 source 元数据
            for (Document doc : allDocs) {
                if (doc.getMetadata().containsKey("source")) {
                    continue; // 已有 source，跳过
                }
                Object source = doc.getMetadata().get("filename");
                if (source == null) {
                    source = doc.getMetadata().get("file_name");
                }
                if (source != null) {
                    doc.getMetadata().put("source", source.toString());
                }
            }

            // 4. 文本切分
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(500)
                    .withMinChunkSizeChars(200)
                    .withMaxNumChunks(10000)
                    .withKeepSeparator(true)
                    .build();
            List<Document> chunks = splitter.apply(allDocs);
            log.info("文档切分完成，共 {} 块", chunks.size());

            // 5. 分批存入向量库（外部 embedding API batch <= 10）
            int batchSize = 10;
            for (int i = 0; i < chunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, chunks.size());
                List<Document> batch = chunks.subList(i, end);
                vectorStore.add(batch);
                indexed += batch.size();
                log.info("向量入库批次: {}-{}, 共 {} 条", i + 1, end, chunks.size());
            }
            log.info("向量入库完成，共 {} 条", indexed);
        } catch (Exception e) {
            log.error("文档加载失败", e);
        }
        return indexed;
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
