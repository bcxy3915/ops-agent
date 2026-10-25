package com.example.opsaiagent.retrieval.bm25;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.retrieval.RetrievalScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * InMemoryBm25Retriever 单元测试
 * 覆盖：重建索引后关键词检索 / 数据库异常吞掉
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("内存 BM25 检索器")
class InMemoryBm25RetrieverTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private DataScope dataScope;

    @InjectMocks
    private InMemoryBm25Retriever retriever;

    @Test
    @DisplayName("rebuild + search：索引构建后能检索到中文文档")
    void rebuildThenSearch() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
                new Document("1", "智能运维服务注册说明", Map.of("source", "a.md")),
                new Document("2", "健康检查接口文档", Map.of("source", "b.md"))
        ));

        assertThatCode(retriever::rebuild).doesNotThrowAnyException();

        List<Document> result = retriever.search("智能运维", 5);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getText()).contains("智能运维");
    }

    @Test
    @DisplayName("rebuild：数据库查询异常被捕获，不影响后续调用")
    void rebuild_queryExceptionSwallowed() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenThrow(new RuntimeException("DB down"));

        assertThatCode(retriever::rebuild).doesNotThrowAnyException();

        // 索引为空，检索返回空列表
        assertThat(retriever.search("智能", 5)).isEmpty();
    }

    @Test
    @DisplayName("search：按租户/部门/个人范围过滤")
    void search_filtersByScope() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
                new Document("1", "智能运维公开知识",
                        Map.of("source", "public.md", "visibility", "PUBLIC")),
                new Document("2", "智能运维企业知识",
                        Map.of("source", "tenant.md", "visibility", "TENANT",
                                "tenantId", "t1", "reviewStatus", "APPROVED")),
                new Document("3", "智能运维部门知识",
                        Map.of("source", "dept.md", "visibility", "DEPT",
                                "tenantId", "t1", "deptId", "5", "reviewStatus", "APPROVED")),
                new Document("4", "智能运维个人知识",
                        Map.of("source", "personal.md", "visibility", "PERSONAL",
                                "tenantId", "t1", "owner", "u1")),
                new Document("5", "智能运维其他租户知识",
                        Map.of("source", "other.md", "visibility", "TENANT",
                                "tenantId", "t2", "reviewStatus", "APPROVED"))
        ));
        retriever.rebuild();

        List<Document> result = retriever.search("智能运维", 10,
                new RetrievalScope("t1", Set.of(5L), "u1"));

        assertThat(result).extracting(Document::getId)
                .containsExactlyInAnyOrder("1", "2", "3", "4");
    }

    @Test
    @DisplayName("search：未构建索引时返回空列表")
    void search_emptyIndex() {
        assertThat(retriever.search("智能", 5)).isEmpty();
    }
}
