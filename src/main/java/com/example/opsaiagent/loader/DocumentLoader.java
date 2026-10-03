package com.example.opsaiagent.loader;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档加载器
 * 启动时判断：当前知识库中的所有文档是否都已入库
 *  - 全部已入库 → 跳过加载
 *  - 有缺失 → 重新加载（当前版本是全量重载，不做增量）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentLoader {

    // 向量存储
    private final VectorStore vectorStore;

    // 资源加载器
    private final ResourcePatternResolver resourcePatternResolver;

    // 用于查询 vector_store 表的状态
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            // 1. 列出知识库目录下所有文档
            Set<String> expectedSources = listKnowledgeFiles();
            if (expectedSources.isEmpty()) {
                log.warn("知识库目录为空，无需加载");
                return;
            }

            // 2. 查询库里已有的 source
            Set<String> existingSources = listExistingSources();
            log.info("向量库已包含 {} 个文档，分别是：{}", existingSources.size(), existingSources.stream()
                    .map(s -> s)
                    .collect(Collectors.joining("\r\n"))
            );

            // 3. 判断是否全部已加载
            if (existingSources.containsAll(expectedSources)) {
                log.info("向量库已包含全部 {} 个文档，跳过加载", expectedSources.size());
                return;
            }

            // 4. 找出缺失的文档，打印日志
            Set<String> missing = new HashSet<>(expectedSources);
            missing.removeAll(existingSources);
            log.info("检测到 {} 个文档未入库，准备加载：{}", missing.size(), missing);

            // 关键修复：先删除缺失文档的旧向量（防止残留），再增量加载
            deleteBySources(missing);
            // 5. 执行加载
            loadAndIndex(missing);
        } catch (Exception e) {
            // vector_store 表不存在，或查询失败，都认为是首次启动
            log.info("向量库状态检查失败（可能是首次启动），准备加载文档：{}", e.getMessage());
        }
    }

    /**
     * 删除指定 source 的所有向量
     */
    private void deleteBySources(Set<String> sources) {
        if (sources.isEmpty()) return;
        // 转成 PostgreSQL 数组文本：'{a,b,c}'
        String arrayLiteral = sources.stream()
                .map(s -> "'" + s.replace("'", "''") + "'")
                .collect(Collectors.joining(",", "{", "}"));
        String sql = "DELETE FROM vector_store WHERE metadata->>'source' = ANY(?::text[])";
        int deleted = jdbcTemplate.update(sql, arrayLiteral);
        log.info("删除缺失文档的旧向量 {} 条", deleted);
    }

    /**
     * 列出知识库目录下的所有文档文件名
     */
    private Set<String> listKnowledgeFiles() throws IOException {
        Set<String> sources = new HashSet<>();
        Resource[] mdFiles = resourcePatternResolver.getResources("classpath*:knowledge/*.md");
        Resource[] pdfFiles = resourcePatternResolver.getResources("classpath*:knowledge/*.pdf");
        for (Resource r : mdFiles) {
            sources.add(r.getFilename());
        }
        for (Resource r : pdfFiles) {
            sources.add(r.getFilename());
        }
        return sources;
    }

    /**
     * 查询 vector_store 表里已存在的 source 集合
     */
    private Set<String> listExistingSources() {
        List<String> sources = jdbcTemplate.queryForList(
                "SELECT DISTINCT metadata->>'source' FROM vector_store WHERE metadata->>'source' IS NOT NULL",
                String.class
        );
        return new HashSet<>(sources);
    }

    /**
     * 加载 resources/knowledge/ 下所有文档，切分后存入向量库
     * 只加载指定的文档（按文件名过滤）
     */
    public void loadAndIndex(Set<String> targetFiles) {
        List<Document> allDocs = new ArrayList<>();

        try {
            // 1.加载markdown文档
            Resource[] mdFiles = resourcePatternResolver.getResources("classpath*:knowledge/*.md");
            for (Resource md : mdFiles) {
                if (!targetFiles.contains(md.getFilename())) continue;
                MarkdownDocumentReaderConfig mdConfig = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("source", md.getFilename())
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(md, mdConfig);
                allDocs.addAll(reader.get());
                log.info("加载 Markdown: {}", md.getFilename());
            }

            // 2.加载pdf文档
            Resource[] pdfFiles = resourcePatternResolver.getResources("classpath*:knowledge/*.pdf");
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPageTopMargin(0)
                    .withPageBottomMargin(0)
                    .withPagesPerDocument(1)
                    .build();
            for (Resource pdf : pdfFiles) {
                if (!targetFiles.contains(pdf.getFilename())) continue;
                PagePdfDocumentReader reader = new PagePdfDocumentReader(pdf, config);
                allDocs.addAll(reader.get());
                log.info("加载 PDF: {}", pdf.getFilename());
            }

            // 统一设置 source 元数据
            for (Document doc : allDocs) {
                if (doc.getMetadata().containsKey("source")) {
                    continue;  // 已经有 source，跳过
                }
                // 针对pdf处理
                Object source = doc.getMetadata().get("filename");
                if (source == null) {
                    source = doc.getMetadata().get("file_name");
                }
                if (source != null) {
                    doc.getMetadata().put("source", source.toString());
                }
            }

            // 文本切分
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(500) // 每块500token
                    .withMinChunkSizeChars(200) //  每块最小200字符
                    .withMaxNumChunks(10000) // 最多切分10000块
                    .withKeepSeparator(true) // 保留分隔符
                    .build();
            List<Document> chunks = splitter.apply(allDocs);
            log.info("文档切分完成，共 {} 块", chunks.size());

            // 4.存入向量库（Spring AI 自动调用 EmbeddingModel 生成向量）
            // 分批存入向量库（阿里云百炼 embedding API 限制 batch size <= 10）
            int batchSize = 10;
            for (int i = 0; i < chunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, chunks.size());
                List<Document> batch = chunks.subList(i, end);
                vectorStore.add(batch);
                log.info("向量入库批次: {}-{}, 共 {} 条", i + 1, end, chunks.size());
            }
            log.info("向量入库完成");
        } catch (Exception e) {
            log.error("文档加载失败", e);
        }
    }
}
