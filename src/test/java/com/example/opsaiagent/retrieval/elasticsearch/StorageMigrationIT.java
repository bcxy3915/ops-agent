package com.example.opsaiagent.retrieval.elasticsearch;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jElasticsearchStore;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.knowledge.model.EmbeddingMetadataMode;
import com.example.opsaiagent.retrieval.RetrievalScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 直接使用已启动的 MySQL/ES；仅清理本测试创建的文档和投影，不建独立环境。 */
@EnabledIfSystemProperty(named = "storage.live-test", matches = "true")
class StorageMigrationIT {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ElasticsearchGateway es;
    private SearchProjectionService projection;
    private LangChain4jElasticsearchStore store;
    private final String prefix = "migration-it-" + UUID.randomUUID();

    /**
     * 连接本地真实存储并准备固定模型输出，避免测试依赖外部模型计费。
     * @return 无返回值，无入参
     */
    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource(System.getProperty("storage.jdbc-url",
                "jdbc:mysql://localhost:23306/opsdb?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"),
                "root", System.getenv("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        es = new ElasticsearchGateway(new ObjectMapper(), "http://localhost:9200", "", "", Duration.ofSeconds(15),
                "ops-knowledge-read", "ops-knowledge-write");
        projection = new SearchProjectionService(jdbc, new ObjectMapper(), es, manager);
        for (int i = 0; i < 12; i++) projection.synchronize();
        var model = mock(EmbeddingModel.class);
        float[] vector = new float[1024];
        vector[0] = 1;
        lenient().when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(vector)));
        lenient().when(model.embedAll(anyList())).thenAnswer(call -> Response.from(
                ((List<?>) call.getArgument(0)).stream().map(item -> Embedding.from(vector)).toList()));
        store = new LangChain4jElasticsearchStore(model, EmbeddingMetadataMode.EMBED, 1024, es, projection);
    }

    /**
     * 清理测试专属记录，保留所有已有业务数据及其他测试状态。
     * @return 无返回值，无入参
     */
    @AfterEach
    void cleanup() {
        var ids = jdbc.queryForList("SELECT vector_id FROM knowledge_search_projection WHERE source LIKE ?", String.class, prefix + "%");
        if (!ids.isEmpty()) {
            StringBuilder body = new StringBuilder();
            for (var id : ids) body.append(projection.json(Map.of("delete", Map.of("_index", es.writeIndex(), "_id", id)))).append('\n');
            es.request("POST", "/_bulk?refresh=wait_for", body.toString());
        }
        jdbc.update("DELETE FROM knowledge_search_projection WHERE source LIKE ?", prefix + "%");
        jdbc.update("DELETE FROM knowledge_index_task WHERE vector_source LIKE ?", prefix + "%");
        jdbc.update("DELETE FROM knowledge_document WHERE source LIKE ?", prefix + "%");
    }

    /**
     * 验证真实向量与 BM25、租户和部门预过滤、MySQL 权限撤销复核以及删除投递。
     * @return 无返回值，无入参
     */
    @Test
    void publishesSearchesRevokesAndDeletesAgainstRealStores() {
        var own = stage("own", "tenant-a", "TENANT");
        stage("foreign", "tenant-b", "TENANT");
        stage("dept", "tenant-a", "DEPT");
        projection.synchronize();
        var scope = new RetrievalScope("tenant-a", Set.of(), "alice");
        assertThat(store.search("线程积压", 10, scope)).extracting(Document::getId).containsExactly(own.getId());
        assertThat(store.keywordSearch("线程积压", 100, scope).stream().filter(d -> d.getMetadata().get("source").toString().startsWith(prefix)))
                .extracting(Document::getId).containsExactly(own.getId());
        assertThat(store.search("线程积压", 10, new RetrievalScope("tenant-a", Set.of(12L), "alice"))).hasSize(2);
        jdbc.update("UPDATE knowledge_document SET review_status='REJECTED' WHERE id=?", own.getMetadata().get("docId"));
        assertThat(store.search("线程积压", 10, scope)).isEmpty();
        // ES 仍保留旧 ACL 时也不能命中；随后投递墓碑，验证实际物理删除。
        store.deleteBySources(List.of(prefix + "own"));
        projection.synchronize();
        var count = es.request("POST", "/" + es.readIndex() + "/_count", projection.json(Map.of("query", Map.of("ids", Map.of("values", List.of(own.getId()))))));
        assertThat(count.path("count").asInt()).isZero();
    }

    /**
     * 验证业务事务回滚不会留下投影和提前写入 ES 的半成品。
     * @return 无返回值，无入参
     */
    @Test
    void rollbackDoesNotPublishAnyProjection() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            stage("rollback", "tenant-a", "TENANT");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_search_projection WHERE source=?", Long.class, prefix + "rollback")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE source=?", Long.class, prefix + "rollback")).isZero();
    }

    /**
     * 验证 ES 失败会持久化退避，恢复后无需重新调用模型即可发布。
     * @return 无返回值，无入参
     */
    @Test
    void retriesAfterElasticsearchFailure() {
        var document = stage("retry", "tenant-a", "TENANT");
        var offline = mock(ElasticsearchGateway.class);
        when(offline.writeIndex()).thenReturn(es.writeIndex());
        when(offline.request(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("模拟 ES 暂不可用"));
        new SearchProjectionService(jdbc, new ObjectMapper(), offline, manager).synchronize();
        assertThat(jdbc.queryForObject("SELECT attempts FROM knowledge_search_projection WHERE vector_id=?", Integer.class, document.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT publish_status FROM knowledge_document WHERE id=?", String.class, document.getMetadata().get("docId"))).isEqualTo("BUILDING");
        jdbc.update("UPDATE knowledge_search_projection SET next_retry_at=UTC_TIMESTAMP(6) WHERE vector_id=?", document.getId());
        projection.synchronize();
        assertThat(store.search("线程积压", 5, new RetrievalScope("tenant-a", Set.of(), "alice"))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM knowledge_search_projection WHERE vector_id=?", Integer.class, document.getId())).isZero();
    }

    /**
     * 验证尚有分块任务时不能提前发布，任务完成后无新投递也能补记发布状态。
     * @return 无返回值，无入参
     */
    @Test
    void waitsForIndexTaskBeforePublishing() {
        var document = stage("pending-task", "tenant-a", "TENANT");
        Object docId = document.getMetadata().get("docId");
        jdbc.update("INSERT INTO knowledge_index_task(doc_id,vector_source) VALUES (?,?)", docId, prefix + "pending-task");
        projection.synchronize();
        assertThat(jdbc.queryForObject("SELECT publish_status FROM knowledge_document WHERE id=?", String.class, docId)).isEqualTo("BUILDING");
        jdbc.update("UPDATE knowledge_index_task SET status='SUCCESS' WHERE doc_id=?", docId);
        projection.synchronize();
        assertThat(jdbc.queryForObject("SELECT publish_status FROM knowledge_document WHERE id=?", String.class, docId)).isEqualTo("PUBLISHED");
    }

    /**
     * 写入专属于测试的文档与投影。
     * @param suffix 来源后缀
     * @param tenant 租户
     * @param visibility 可见性
     * @return 投影文档
     */
    private Document stage(String suffix, String tenant, String visibility) {
        return new TransactionTemplate(manager).execute(status -> {
            String source = prefix + suffix;
            jdbc.update("INSERT INTO knowledge_document (title, source, tenant_id, dept_id, owner, visibility, review_status, publish_status) "
                    + "VALUES (?, ?, ?, 12, 'alice', ?, 'APPROVED', 'BUILDING')", prefix, source, tenant, visibility);
            Long id = jdbc.queryForObject("SELECT id FROM knowledge_document WHERE source=?", Long.class, source);
            var document = new Document("线程积压，检查线程池队列并分析慢请求", Map.of("docId", id.toString(), "source", source,
                    "tenantId", tenant, "deptId", "12", "owner", "alice", "visibility", visibility, "reviewStatus", "APPROVED"));
            store.write(List.of(document));
            return document;
        });
    }
}
