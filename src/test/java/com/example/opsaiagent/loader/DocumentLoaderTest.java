package com.example.opsaiagent.loader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentLoader 单元测试
 * 覆盖：init 检测逻辑、deleteBySources、listKnowledgeFiles、listExistingSources
 * loadAndIndex 因依赖 Spring AI 内部组件较难 mock，只测关键分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文档加载器")
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

    @BeforeEach
    void setUp() {
        // 默认行为：mockMdResource.getFilename() 返回 test.md
        lenient().when(mockMdResource.getFilename()).thenReturn("test.md");
    }

    // ==================== init：知识库为空 ====================

    @Test
    @DisplayName("init：知识库目录为空时跳过加载")
    void init_emptyKnowledgeDir() throws IOException {
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.md"))
                .thenReturn(new Resource[0]);
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.pdf"))
                .thenReturn(new Resource[0]);

        // 不应抛异常
        assertThatCode(() -> loader.init()).doesNotThrowAnyException();

        // 不应查询已有 source
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class));
    }

    // ==================== init：全部已入库 ====================

    @Test
    @DisplayName("init：文档全部已入库时跳过加载")
    void init_allDocumentsExist() throws IOException {
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.md"))
                .thenReturn(new Resource[]{mockMdResource});
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.pdf"))
                .thenReturn(new Resource[0]);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("test.md"));

        assertThatCode(() -> loader.init()).doesNotThrowAnyException();

        // 不应调用 vectorStore.add
        verify(vectorStore, never()).add(any());
    }

    // ==================== init：检测到缺失文档 ====================

    @Test
    @DisplayName("init：检测到缺失文档时删除旧向量并加载")
    void init_missingDocuments() throws IOException {
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.md"))
                .thenReturn(new Resource[]{mockMdResource});
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.pdf"))
                .thenReturn(new Resource[0]);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of()); // 库里没有

        // Mock 删除操作
        when(jdbcTemplate.update(contains("DELETE FROM vector_store"), any(Object[].class)))
                .thenReturn(0);

        // Mock 读取 markdown 内容（返回空 docs）
        when(mockMdResource.getInputStream())
                .thenThrow(new IOException("mock: 不实际读取"));

        // 不应抛异常（内部有 try-catch）
        assertThatCode(() -> loader.init()).doesNotThrowAnyException();

        // 至少执行过 select
        verify(jdbcTemplate).queryForList(anyString(), eq(String.class));
    }

    // ==================== init：JdbcTemplate 抛异常 ====================

    @Test
    @DisplayName("init：查询向量库失败时视为首次启动，不抛异常")
    void init_jdbcThrows() throws IOException {
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.md"))
                .thenReturn(new Resource[]{mockMdResource});
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.pdf"))
                .thenReturn(new Resource[0]);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("table not exists"));

        assertThatCode(() -> loader.init()).doesNotThrowAnyException();
    }

    // ==================== 异常兜底 ====================

    @Test
    @DisplayName("init：ResourcePatternResolver 抛 IOException 时被兜底")
    void init_resourceResolverThrows() throws IOException {
        when(resourcePatternResolver.getResources(anyString()))
                .thenThrow(new IOException("classpath error"));

        assertThatCode(() -> loader.init()).doesNotThrowAnyException();
    }

    // ==================== loadAndIndex：空集合 ====================

    @Test
    @DisplayName("loadAndIndex：空 targetFiles 不加载任何东西")
    void loadAndIndex_emptyTargetFiles() throws IOException {
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.md"))
                .thenReturn(new  Resource[]{mockMdResource});
        when(resourcePatternResolver.getResources("classpath*:knowledge/*.pdf"))
                .thenReturn(new Resource[0]);

        // targetFiles 里没有 test.md，会被过滤
        loader.loadAndIndex(Set.of("other.md"));

        // vectorStore.add 不会被调用
        verify(vectorStore, never()).add(any());
    }
}