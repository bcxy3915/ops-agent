package com.example.opsaiagent.loader;

import com.example.opsaiagent.knowledge.loader.DocumentLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentLoader 单元测试
 * 覆盖：reload 增量加载（缺失/同名变更/旧数据无指纹/残留清理/首次启动兜底）、
 * loadAndIndex 过滤与向量化、deleteBySources、computeLoadTargets/computeStaleSources、sha256Hex。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文档加载")
class DocumentLoaderTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ResourcePatternResolver resourcePatternResolver;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DocumentLoader loader;

    @Mock
    private Resource mockMdResource;

    @Mock
    private Resource mockNewResource;

    /** 足够长以保证切分出至少 1 个 chunk 的内容 */
    private static final String MD_CONTENT = "故障排查手册内容说明".repeat(100);

    @BeforeEach
    void setUp() throws IOException {
        lenient().when(mockMdResource.getFilename()).thenReturn("test.md");
        lenient().when(mockNewResource.getFilename()).thenReturn("new.md");
        lenient().when(mockMdResource.getInputStream())
                .thenAnswer(inv -> new ByteArrayInputStream(MD_CONTENT.getBytes(StandardCharsets.UTF_8)));
        lenient().when(mockNewResource.getInputStream())
                .thenAnswer(inv -> new ByteArrayInputStream(MD_CONTENT.getBytes(StandardCharsets.UTF_8)));
    }

    private void stubKnowledgeFiles(Resource[] mdFiles, Resource[] pdfFiles) throws IOException {
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.md")).thenReturn(mdFiles);
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.pdf")).thenReturn(pdfFiles);
    }

    private Map<String, Object> row(String src, String hash) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("src", src);
        m.put("hash", hash);
        return m;
    }

    private String mdHash() throws IOException {
        return loader.sha256Hex(new ByteArrayInputStream(MD_CONTENT.getBytes(StandardCharsets.UTF_8)));
    }

    // ==================== reload：目录为空 ====================

    @Test
    @DisplayName("reload：知识库目录为空时跳过加载")
    void reload_emptyKnowledgeDir() throws IOException {
        stubKnowledgeFiles(new Resource[0], new Resource[0]);

        Map<String, Object> result = loader.reload();

        assertThat(result).containsEntry("status", "skipped");
        verify(jdbcTemplate, never()).queryForList(anyString());
        verify(vectorStore, never()).add(any());
    }

    // ==================== reload：全部已加载且指纹一致 ====================

    @Test
    @DisplayName("reload：全部已加载且指纹一致时跳过加载")
    void reload_allLoadedWithSameHash() throws IOException {
        stubKnowledgeFiles(new Resource[]{mockMdResource}, new Resource[0]);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row("test.md", mdHash())));

        Map<String, Object> result = loader.reload();

        assertThat(result).containsEntry("status", "success");
        assertThat(result).containsEntry("loaded", 0);
        assertThat(result).containsEntry("targets", 0);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(vectorStore, never()).add(any());
    }

    // ==================== reload：缺失文档 ====================

    @Test
    @DisplayName("reload：检测到缺失文档时删除旧向量并增量加载")
    void reload_missingDocuments() throws IOException {
        stubKnowledgeFiles(new Resource[]{mockMdResource}, new Resource[0]);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        Map<String, Object> result = loader.reload();

        assertThat(result).containsEntry("status", "success");
        assertThat(result).containsEntry("targets", 1);
        assertThat((Integer) result.get("loaded")).isGreaterThan(0);
        // 缺失文档先删旧向量，再入库
        verify(jdbcTemplate).update(contains("DELETE FROM vector_store"), any(Object[].class));
        verify(vectorStore, atLeastOnce()).add(any());
    }

    // ==================== reload：同名内容变更 ====================

    @Test
    @DisplayName("reload：同名文件内容变化（指纹不同）时重新加载")
    void reload_sameNameDifferentHash() throws IOException {
        stubKnowledgeFiles(new Resource[]{mockMdResource}, new Resource[0]);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row("test.md", "old-hash-value")));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        Map<String, Object> result = loader.reload();

        assertThat(result).containsEntry("targets", 1);
        assertThat((Integer) result.get("loaded")).isGreaterThan(0);
        verify(jdbcTemplate).update(contains("DELETE FROM vector_store"), any(Object[].class));
        verify(vectorStore, atLeastOnce()).add(any());
    }

    // ==================== reload：旧数据无指纹 ====================

    @Test
    @DisplayName("reload：向量库有记录但无指纹（旧数据）时保守重载")
    void reload_oldDataWithoutHash() throws IOException {
        stubKnowledgeFiles(new Resource[]{mockMdResource}, new Resource[0]);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(row("test.md", null)));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        Map<String, Object> result = loader.reload();

        assertThat(result).containsEntry("targets", 1);
        assertThat((Integer) result.get("loaded")).isGreaterThan(0);
        verify(vectorStore, atLeastOnce()).add(any());
    }

    // ==================== reload：残留清理 ====================

    @Test
    @DisplayName("reload：内置文档被移除时清理残留，且不触碰 upload:/case: 向量")
    void reload_cleanStaleSourcesOnlyBuiltin() throws IOException {
        stubKnowledgeFiles(new Resource[]{mockNewResource}, new Resource[0]);
        // 向量库有：old.md（已从目录移除）、upload:5（用户上传）、case:9（案例沉淀）
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                row("old.md", mdHash()),
                row("upload:5", mdHash()),
                row("case:9", mdHash())
        ));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        Map<String, Object> result = loader.reload();

        assertThat(result).containsEntry("status", "success");
        // new.md 缺失需加载，old.md 残留需清理
        assertThat(result).containsEntry("targets", 1);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(anyString(), argsCaptor.capture());

        // 两次删除：残留 old.md + 待加载 new.md；upload:/case: 不得出现在任何删除参数中
        List<Object[]> allArgs = argsCaptor.getAllValues();
        assertThat(allArgs).hasSize(2);
        for (Object[] args : allArgs) {
            assertThat(args).doesNotContain("upload:5", "case:9");
        }
        verify(vectorStore, atLeastOnce()).add(any());
    }

    // ==================== reload：首次启动兜底 ====================

    @Test
    @DisplayName("reload：向量库查询失败视为首次启动，全量加载")
    void reload_jdbcThrowsFallbackFullLoad() throws IOException {
        stubKnowledgeFiles(new Resource[]{mockMdResource}, new Resource[0]);
        when(jdbcTemplate.queryForList(anyString())).thenThrow(new RuntimeException("table not exists"));

        Map<String, Object> result = loader.reload();

        assertThat(result).containsEntry("status", "success");
        assertThat((Integer) result.get("loaded")).isGreaterThan(0);
        verify(vectorStore, atLeastOnce()).add(any());
    }

    // ==================== reload：资源读取失败 ====================

    @Test
    @DisplayName("reload：目录资源读取失败返回 failed 且不抛异常")
    void reload_resourceResolverThrows() throws IOException {
        when(resourcePatternResolver.getResources(anyString()))
                .thenThrow(new IOException("classpath error"));

        Map<String, Object> result = loader.reload();

        assertThat(result).containsEntry("status", "failed");
        verify(vectorStore, never()).add(any());
    }

    // ==================== loadAndIndex ====================

    @Test
    @DisplayName("loadAndIndex：空 targetFiles 不加载任何东西")
    void loadAndIndex_emptyTargetFiles() throws IOException {
        int indexed = loader.loadAndIndex(Map.of());

        assertThat(indexed).isZero();
        verify(vectorStore, never()).add(any());
    }

    @Test
    @DisplayName("loadAndIndex：只加载 targetFiles 命中的文件并写入 contentHash")
    void loadAndIndex_loadOnlyTargetsWithHash() throws IOException {
        stubKnowledgeFiles(new Resource[]{mockMdResource}, new Resource[0]);
        String hash = mdHash();

        int indexed = loader.loadAndIndex(Map.of("test.md", hash));

        assertThat(indexed).isGreaterThan(0);
        ArgumentCaptor<List> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(batchCaptor.capture());
        List<?> docs = batchCaptor.getAllValues().get(0);
        assertThat(docs).isNotEmpty();
        org.springframework.ai.document.Document d = (org.springframework.ai.document.Document) docs.get(0);
        assertThat(d.getMetadata()).containsEntry("source", "test.md");
        assertThat(d.getMetadata()).containsEntry(DocumentLoader.HASH_METADATA_KEY, hash);
    }

    @Test
    @DisplayName("loadAndIndex：未命中 targetFiles 的文件被过滤")
    void loadAndIndex_filterNonTarget() throws IOException {
        stubKnowledgeFiles(new Resource[]{mockMdResource}, new Resource[0]);

        int indexed = loader.loadAndIndex(Map.of("other.md", "hash"));

        assertThat(indexed).isZero();
        verify(vectorStore, never()).add(any());
    }

    // ==================== deleteBySources ====================

    @Test
    @DisplayName("deleteBySources：空集合直接返回 0")
    void deleteBySources_empty() {
        int deleted = loader.deleteBySources(Set.of());
        assertThat(deleted).isZero();
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("deleteBySources：按 IN 参数精确删除")
    void deleteBySources_inClause() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        int deleted = loader.deleteBySources(List.of("a.md", "b.md"));

        assertThat(deleted).isEqualTo(2);
        verify(jdbcTemplate).update(contains("IN (?,?)"), any(Object[].class));
    }

    // ==================== computeLoadTargets ====================

    @Test
    @DisplayName("computeLoadTargets：缺失/变更/无指纹加入，一致跳过")
    void computeLoadTargets_ok() {
        Map<String, String> expected = Map.of(
                "missing.md", "h1",
                "changed.md", "h2-new",
                "same.md", "h3",
                "old.md", "h4"
        );
        Map<String, String> existing = new java.util.HashMap<>();
        existing.put("changed.md", "h2-old");
        existing.put("same.md", "h3");
        existing.put("old.md", null);

        Set<String> targets = loader.computeLoadTargets(expected, existing);

        assertThat(targets).containsExactlyInAnyOrder("missing.md", "changed.md", "old.md");
        assertThat(targets).doesNotContain("same.md");
    }

    // ==================== computeStaleSources ====================

    @Test
    @DisplayName("computeStaleSources：仅清理内置残留，不动 upload:/case:")
    void computeStaleSources_ok() {
        Set<String> existing = Set.of("old.md", "upload:5", "case:9", "new.md");
        Set<String> expected = Set.of("new.md");

        Set<String> stale = loader.computeStaleSources(existing, expected);

        assertThat(stale).containsExactly("old.md");
    }

    // ==================== sha256Hex ====================

    @Test
    @DisplayName("sha256Hex：hello 的 SHA-256 值正确")
    void sha256Hex_knownValue() throws IOException {
        String hex = loader.sha256Hex(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(hex).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("sha256Hex：不同内容指纹不同")
    void sha256Hex_distinct() throws IOException {
        String a = loader.sha256Hex(new ByteArrayInputStream("A".getBytes(StandardCharsets.UTF_8)));
        String b = loader.sha256Hex(new ByteArrayInputStream("B".getBytes(StandardCharsets.UTF_8)));
        assertThat(a).isNotEqualTo(b);
    }
}
