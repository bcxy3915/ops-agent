package com.example.opsaiagent.retrieval.elasticsearch;

import java.util.Collections;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.retrieval.RetrievalScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MySQL 检索投影与持久化投递：提交后异步同步 ES，删除墓碑保留以支持对账。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchProjectionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ElasticsearchGateway es;
    private final PlatformTransactionManager manager;
    @Value("${ops-agent.ai.elasticsearch.sync-batch-size:30}")
    private int batchSize = 30;

    /**
     * 将已生成向量与检索正文写入事务投影，业务回滚时不会留下 ES 写入。
     * @param document 检索文档
     * @param vector 向量
     * @return 无返回值
     */
    @Transactional(rollbackFor = Exception.class)
    public void stage(Document document, float[] vector) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put("active", true);
        metadata.put("indexVersion", document.getId());
        jdbc.update("""
                INSERT INTO knowledge_search_projection
                (vector_id, doc_id, source, content, metadata, embedding, deleted, sync_status)
                VALUES (?, ?, ?, ?, ?, ?, FALSE, 'PENDING')
                ON DUPLICATE KEY UPDATE content=VALUES(content), metadata=VALUES(metadata),
                  embedding=VALUES(embedding), deleted=FALSE, sync_status='PENDING', next_retry_at=UTC_TIMESTAMP(6)
                """, document.getId(), Long.valueOf(metadata.get("docId").toString()), metadata.get("source"),
                document.getText(), json(metadata), json(vector));
    }

    /**
     * 登记来源删除墓碑，与文档或分块删除共用业务事务。
     * @param sources 来源集合
     * @return 影响分块数
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteSources(Collection<String> sources) {
        if (sources == null || sources.isEmpty()) return 0;
        return jdbc.update("UPDATE knowledge_search_projection SET deleted=TRUE, sync_status='PENDING', "
                + "next_retry_at=UTC_TIMESTAMP(6) WHERE deleted=FALSE AND source IN ("
                + String.join(",", Collections.nCopies(sources.size(), "?")) + ")", sources.toArray());
    }

    /**
     * 列出当前事务中的有效投影，供内置指纹与元数据验收使用。
     * @return 文档列表，无入参
     */
    public List<Document> documents() {
        return jdbc.query("SELECT vector_id, content, metadata FROM knowledge_search_projection WHERE deleted=FALSE",
                (rs, row) -> new Document(rs.getString(1), rs.getString(2), metadata(rs.getString(3))));
    }

    /**
     * 从 MySQL 复核候选是否仍有效、审核通过及权限未变更。
     * @param candidates ES 候选
     * @param scope 可信检索范围
     * @param limit 最多返回数量
     * @return 已验证候选，保持排序
     */
    public List<Document> verify(List<Document> candidates, RetrievalScope scope, int limit) {
        if (candidates.isEmpty()) return List.of();
        List<String> ids = candidates.stream().map(Document::getId).toList();
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<String, Map<String, Object>> allowed = new HashMap<>();
        jdbc.query("""
                SELECT p.vector_id, p.metadata, d.tenant_id, d.dept_id, d.owner, d.visibility, d.review_status,
                  d.trust_status FROM knowledge_search_projection p
                JOIN knowledge_document d ON d.id=p.doc_id
                WHERE p.deleted=FALSE AND d.review_status='APPROVED' AND d.publish_status='PUBLISHED'
                  AND p.vector_id IN (
                """ + marks + ")", rs -> {
            Map<String, Object> md = metadata(rs.getString("metadata"));
            md.put("tenantId", rs.getString("tenant_id"));
            md.put("deptId", rs.getString("dept_id"));
            md.put("owner", rs.getString("owner"));
            md.put("visibility", rs.getString("visibility"));
            md.put("reviewStatus", rs.getString("review_status"));
            md.put("trustStatus", rs.getString("trust_status"));
            if (scope.matches(md)) allowed.put(rs.getString("vector_id"), md);
        }, ids.toArray());
        List<Document> results = new ArrayList<>();
        for (Document candidate : candidates) {
            Map<String, Object> md = allowed.get(candidate.getId());
            if (md == null) continue;
            if (candidate.getMetadata().containsKey("distance")) md.put("distance", candidate.getMetadata().get("distance"));
            results.add(new Document(candidate.getId(), candidate.getText(), md, candidate.getScore()));
            if (results.size() == limit) break;
        }
        return results;
    }

    /**
     * 投递到期投影；短事务锁住待投递行，避免新写入或删除被旧请求覆盖。
     * @return 无返回值，无入参
     */
    @Scheduled(fixedDelayString = "${ops-agent.ai.elasticsearch.sync-interval-ms:2000}", initialDelay = 5000)
    public void synchronize() {
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                var rows = jdbc.queryForList("SELECT * FROM knowledge_search_projection WHERE sync_status='PENDING' "
                        + "AND next_retry_at<=UTC_TIMESTAMP(6) ORDER BY next_retry_at, vector_id LIMIT ? FOR UPDATE SKIP LOCKED",
                        Math.max(1, Math.min(batchSize, 100)));
                if (rows.isEmpty()) return;
                try {
                    StringBuilder body = new StringBuilder();
                    for (var row : rows) {
                        boolean deleted = Boolean.TRUE.equals(row.get("deleted")) || "1".equals(String.valueOf(row.get("deleted")));
                        body.append(json(Map.of(deleted ? "delete" : "index", Map.of("_index", es.writeIndex(), "_id", row.get("vector_id"))))).append('\n');
                        if (!deleted) body.append("{\"content\":").append(json(row.get("content")))
                                .append(",\"metadata\":").append(row.get("metadata"))
                                .append(",\"embedding\":").append(row.get("embedding")).append("}\n");
                    }
                    var response = es.request("POST", "/_bulk?refresh=wait_for&require_alias=true", body.toString());
                    for (var item : response.path("items")) {
                        var result = item.elements().next();
                        int code = result.path("status").asInt();
                        if (code >= 300 && !(item.has("delete") && code == 404)) throw new IllegalStateException("ES 批量投递存在失败项");
                    }
                    if (response.path("items").size() != rows.size()) throw new IllegalStateException("ES 批量响应数量不一致");
                    for (var row : rows) jdbc.update("UPDATE knowledge_search_projection SET sync_status='SYNCED', "
                            + "last_error=NULL, attempts=0, embedding=NULL WHERE vector_id=?", row.get("vector_id"));
                } catch (Exception failure) {
                    for (var row : rows) jdbc.update("UPDATE knowledge_search_projection SET attempts=attempts+1, "
                            + "next_retry_at=TIMESTAMPADD(SECOND, LEAST(300, 5*(attempts+1)), UTC_TIMESTAMP(6)), "
                            + "last_error=? WHERE vector_id=?", failure.getClass().getSimpleName(), row.get("vector_id"));
                    log.warn("检索投影投递失败，已登记退避重试：{}", failure.getMessage());
                }
            });
            publishReadyDocuments();
        } catch (Exception failure) {
            log.warn("检索投影同步暂不可用：{}", failure.getMessage());
        }
    }

    /**
     * 投递事务提交后再更新文档，避免投影行锁与索引事务的文档锁形成反向加锁。
     * @return 无返回值，无入参
     */
    private void publishReadyDocuments() {
        jdbc.update("""
                UPDATE knowledge_document d SET publish_status='PUBLISHED', published_at=UTC_TIMESTAMP(6), publish_error=NULL
                WHERE d.publish_status='BUILDING' AND d.review_status='APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM knowledge_index_task t WHERE t.doc_id=d.id AND t.status IN ('PENDING','RUNNING'))
                  AND EXISTS (SELECT 1 FROM knowledge_search_projection p WHERE p.doc_id=d.id AND p.deleted=FALSE)
                  AND NOT EXISTS (SELECT 1 FROM knowledge_search_projection p WHERE p.doc_id=d.id AND p.deleted=FALSE AND p.sync_status<>'SYNCED')
                """);
    }

    /**
     * 序列化请求内容。
     * @param value 对象
     * @return JSON 文本
     */
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("检索投影序列化失败", e); }
    }

    /**
     * 读取持久化元数据，解析异常直接失败而非放宽权限。
     * @param text JSON 文本
     * @return 元数据
     */
    public Map<String, Object> metadata(String text) {
        try { return mapper.readValue(text, new TypeReference<HashMap<String, Object>>() { }); }
        catch (Exception e) { throw new IllegalStateException("检索投影元数据解析失败", e); }
    }
}
