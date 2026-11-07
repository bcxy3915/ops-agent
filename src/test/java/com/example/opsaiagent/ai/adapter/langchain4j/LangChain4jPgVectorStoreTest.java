package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.retrieval.RetrievalScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.knowledge.model.EmbeddingMetadataMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.ArgumentCaptor;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 新向量存储的 JDBC 协议与权限参数测试，不连接真实数据库。 */
class LangChain4jPgVectorStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final LangChain4jPgVectorStore store = new LangChain4jPgVectorStore(
            jdbc, model, new ObjectMapper(), 3, EmbeddingMetadataMode.EMBED);

    /**
     * 设置查询向量及空结果夹具。
     * @return 无返回值
     */
    private void mockSearch() {
        when(model.embed("查询")).thenReturn(Response.from(Embedding.from(new float[]{1, 0, 0})));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    }

    /**
     * 验证公开查询始终要求审核通过，不产生租户分支。
     * @return 无返回值
     */
    @Test
    void publicSearchRequiresApprovalBeforeRanking() {
        mockSearch();
        store.search("查询", 20, null);
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("reviewStatus' = 'APPROVED'", "visibility' = 'PUBLIC'",
                "ORDER BY distance ASC LIMIT ?").doesNotContain("tenantId");
        assertThat(args.getValue()).containsExactly("[1.0, 0.0, 0.0]", 0.5, 20);
    }

    /**
     * 验证租户、部门与用户名作为参数绑定，不能注入 SQL。
     * @return 无返回值
     */
    @Test
    void tenantDepartmentsAndOwnerAreBoundParameters() {
        mockSearch();
        String tenant = "tenant' OR true --";
        store.search("查询", 5, new RetrievalScope(tenant, Set.of(12L), "alice'"));
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("deptId' IN (?)", "PERSONAL").doesNotContain(tenant, "alice'");
        assertThat(args.getValue()).containsExactly("[1.0, 0.0, 0.0]", tenant, tenant, "12",
                tenant, "alice'", 0.5, 5);
    }

    /**
     * 验证空部门集合不等于同租户全部部门。
     * @return 无返回值
     */
    @Test
    void emptyDepartmentsExcludeDepartmentBranch() {
        mockSearch();
        store.search("查询", 5, new RetrievalScope("tenant-a", Set.of(), "alice"));
        verify(jdbc).query(argThat((String sql) -> !sql.contains("'DEPT'")),
                any(RowMapper.class), any(Object[].class));
    }

    /**
     * 验证部门管理员范围保留全部部门分支。
     * @return 无返回值
     */
    @Test
    void nullDepartmentsAllowTenantDepartmentBranch() {
        mockSearch();
        store.search("查询", 5, new RetrievalScope("tenant-a", null, null));
        verify(jdbc).query(argThat((String sql) -> sql.contains("'DEPT'") && !sql.contains(" IN (")),
                any(RowMapper.class), any(Object[].class));
    }

    /**
     * 验证分批写入保留 ID、原文、metadata，且模型格式化单独处理。
     * @return 无返回值
     */
    @Test
    void writesInBatchesWithStableIdsAndOriginalContent() {
        List<Document> documents = IntStream.range(0, 11)
                .mapToObj(i -> new Document(UUID.randomUUID().toString(), "正文" + i,
                        Map.<String, Object>of("source", "fixture.md"))).toList();
        when(model.embedAll(anyList())).thenAnswer(call -> Response.from(
                ((List<?>) call.getArgument(0)).stream().map(x -> Embedding.from(new float[]{1, 0, 0})).toList()));
        store.write(documents);
        var rows = ArgumentCaptor.forClass(List.class);
        verify(jdbc, times(2)).batchUpdate(contains("ON CONFLICT (id)"), rows.capture());
        assertThat(rows.getAllValues().get(0)).hasSize(10);
        assertThat(rows.getAllValues().get(1)).hasSize(1);
        Object[] first = (Object[]) rows.getAllValues().get(0).getFirst();
        assertThat(first).containsExactly(UUID.fromString(documents.getFirst().getId()), "正文0",
                "{\"source\":\"fixture.md\"}", "[1.0, 0.0, 0.0]");
    }

    /**
     * 验证向量数量或维度错误时不写入数据库。
     * @return 无返回值
     */
    @Test
    void rejectsBadEmbeddingsBeforeWriting() {
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of()))
                .thenReturn(Response.from(List.of(Embedding.from(new float[]{1, 0}))));
        var documents = List.of(new Document("正文"));
        assertThatThrownBy(() -> store.write(documents)).hasMessageContaining("数量");
        assertThatThrownBy(() -> store.write(documents)).hasMessageContaining("维度");
        verifyNoInteractions(jdbc);
    }

    /**
     * 验证存量 UUID、metadata、余弦距离和相似度转换。
     * @return 无返回值
     * @throws Exception 结果集夹具异常
     */
    @Test
    void mapsStoredMetadataAndDistance() throws Exception {
        when(model.embed("查询")).thenReturn(Response.from(Embedding.from(new float[]{1, 0, 0})));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("existing-id");
        when(rs.getString("content")).thenReturn("原文");
        when(rs.getString("metadata")).thenReturn("{\"source\":\"upload:1\",\"docId\":\"1\"}");
        when(rs.getDouble("distance")).thenReturn(0.2);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(call -> List.of(((RowMapper<Document>) call.getArgument(1)).mapRow(rs, 0)));
        Document result = store.search("查询", 5, null).getFirst();
        assertThat(result.getId()).isEqualTo("existing-id");
        assertThat(result.getText()).isEqualTo("原文");
        assertThat(result.getScore()).isEqualTo(0.8);
        assertThat(result.getMetadata()).containsEntry("source", "upload:1").containsEntry("distance", 0.2);
    }
}
