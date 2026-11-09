package com.example.opsaiagent.ai.adapter.langchain4j;

import java.util.HashMap;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.knowledge.model.EmbeddingMetadataMode;
import com.example.opsaiagent.retrieval.RetrievalScope;
import com.example.opsaiagent.retrieval.elasticsearch.ElasticsearchGateway;
import com.example.opsaiagent.retrieval.elasticsearch.SearchProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

/** ES 权限 DSL、向量协议与分数转换回归测试。 */
class LangChain4jElasticsearchStoreTest {
    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final ElasticsearchGateway es = mock(ElasticsearchGateway.class);
    private final SearchProjectionService projection = mock(SearchProjectionService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LangChain4jElasticsearchStore store = new LangChain4jElasticsearchStore(
            model, EmbeddingMetadataMode.EMBED, 3, es, projection);

    /**
     * 验证空部门集合与全部部门具有不同权限，特殊字符作为 JSON 值处理。
     * @return 无返回值，无入参
     * @throws Exception JSON 处理异常
     */
    @Test
    void preservesDepartmentAndTenantScopeSemantics() throws Exception {
        String empty = mapper.writeValueAsString(LangChain4jElasticsearchStore.filter(new RetrievalScope("tenant'", Set.of(), "alice\"")));
        String all = mapper.writeValueAsString(LangChain4jElasticsearchStore.filter(new RetrievalScope("tenant'", null, null)));
        assertThat(empty).contains("APPROVED", "PERSONAL", "tenant'", "alice\\\"").doesNotContain("\"DEPT\"");
        assertThat(all).contains("\"DEPT\"", "must_not");
        assertThat(mapper.writeValueAsString(LangChain4jElasticsearchStore.filter(RetrievalScope.publicOnly())))
                .contains("PUBLIC", "APPROVED").doesNotContain("TENANT", "PERSONAL");
    }

    /**
     * 验证分批向量化、稳定文档 ID 和原正文写入持久化投影。
     * @return 无返回值，无入参
     */
    @Test
    void batchesEmbeddingsAndStagesOriginalDocuments() {
        var docs = IntStream.range(0, 11).mapToObj(i -> new Document("正文" + i)).toList();
        when(model.embedAll(anyList())).thenAnswer(call -> Response.from(((List<?>) call.getArgument(0)).stream()
                .map(value -> Embedding.from(new float[]{1, 0, 0})).toList()));
        store.write(docs);
        verify(model, times(2)).embedAll(anyList());
        for (var doc : docs) verify(projection).stage(eq(doc), aryEq(new float[]{1, 0, 0}));
        verifyNoInteractions(es);
    }

    /**
     * 验证错误维度、空向量数量及非有限向量不会进入投影。
     * @return 无返回值，无入参
     */
    @Test
    void rejectsInvalidEmbeddings() {
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of()))
                .thenReturn(Response.from(List.of(Embedding.from(new float[]{1, 0}))))
                .thenReturn(Response.from(List.of(Embedding.from(new float[]{Float.NaN, 0, 0}))));
        var docs = List.of(new Document("正文"));
        assertThatThrownBy(() -> store.write(docs)).hasMessageContaining("数量");
        assertThatThrownBy(() -> store.write(docs)).hasMessageContaining("维度");
        assertThatThrownBy(() -> store.write(docs)).hasMessageContaining("非有限");
        verifyNoInteractions(projection, es);
    }

    /**
     * 验证 kNN 预过滤、ES cosine 分数转换及权威状态复核。
     * @return 无返回值，无入参
     * @throws Exception JSON 夹具异常
     */
    @Test
    void convertsCosineScoreAndVerifiesMysqlState() throws Exception {
        when(model.embed("问题")).thenReturn(Response.from(Embedding.from(new float[]{1, 0, 0})));
        when(es.readIndex()).thenReturn("read");
        when(projection.json(any())).thenAnswer(call -> mapper.writeValueAsString(call.getArgument(0)));
        when(es.request(anyString(), anyString(), anyString())).thenReturn(mapper.readTree(
                "{\"hits\":{\"hits\":[{\"_id\":\"id\",\"_score\":0.9,\"_source\":{\"content\":\"正文\",\"metadata\":{\"docId\":\"1\"}}}]}}"));
        when(projection.metadata(anyString())).thenReturn(new HashMap<>(Map.of("docId", "1")));
        when(projection.verify(anyList(), any(), anyInt())).thenAnswer(call -> call.getArgument(0));
        var results = store.search("问题", 5, RetrievalScope.publicOnly());
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getScore()).isCloseTo(0.8, within(0.00001));
        var body = ArgumentCaptor.forClass(String.class);
        verify(es).request(eq("POST"), eq("/read/_search"), body.capture());
        assertThat(mapper.readTree(body.getValue()).path("knn").path("filter").toString()).contains("APPROVED", "PUBLIC");
        assertThat(body.getValue()).doesNotContain("post_filter");
    }
}
