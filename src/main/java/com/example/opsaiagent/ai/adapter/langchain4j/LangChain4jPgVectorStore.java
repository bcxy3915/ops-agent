package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.ai.adapter.springai.SpringAiDocumentMapper;

import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import com.example.opsaiagent.retrieval.RetrievalScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** LangChain4j 生成向量，JDBC 兼容当前 vector_store；不创建表或重建索引。 */
public class LangChain4jPgVectorStore implements KnowledgeVectorStore {
    private final JdbcTemplate jdbc;
    private final EmbeddingModel model;
    private final ObjectMapper mapper;
    private final int dimensions;
    private final MetadataMode metadataMode;

    /**
     * 创建兼容现有数据格式的向量存储。
     * @param jdbc 数据库客户端
     * @param model LangChain4j 向量模型
     * @param mapper JSON 映射器
     * @param dimensions 配置向量维度
     * @param metadataMode 现有 embedding 内容格式化方式
     * @return 无返回值
     */
    public LangChain4jPgVectorStore(JdbcTemplate jdbc, EmbeddingModel model, ObjectMapper mapper,
                                  int dimensions, MetadataMode metadataMode) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("向量维度必须大于零");
        }
        this.jdbc = jdbc;
        this.model = model;
        this.mapper = mapper;
        this.dimensions = dimensions;
        this.metadataMode = metadataMode;
    }

    /**
     * 在同一事务中分批生成并更新向量，失败时回滚当前调用的写入。
     * @param documents 待写文档
     * @return 无返回值
     */
    @Override
    @Transactional
    public void write(List<Document> documents) {
        for (int start = 0; start < documents.size(); start += 10) {
            List<Document> batch = documents.subList(start, Math.min(start + 10, documents.size()));
            var vectors = model.embedAll(batch.stream()
                    .map(doc -> TextSegment.from(SpringAiDocumentMapper.toSpring(doc).getFormattedContent(metadataMode))).toList()).content();
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException("向量模型返回数量与输入文档数量不一致");
            }
            List<Object[]> rows = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                Document doc = batch.get(index);
                try {
                    rows.add(new Object[]{UUID.fromString(doc.getId()), doc.getText(),
                            mapper.writeValueAsString(doc.getMetadata()), vectorLiteral(vectors.get(index).vector())});
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException("向量 metadata 序列化失败", e);
                }
            }
            jdbc.batchUpdate("INSERT INTO public.vector_store (id, content, metadata, embedding) "
                    + "VALUES (?, ?, CAST(? AS jsonb), CAST(? AS vector)) "
                    + "ON CONFLICT (id) DO UPDATE SET content=EXCLUDED.content, "
                    + "metadata=EXCLUDED.metadata, embedding=EXCLUDED.embedding", rows);
        }
    }

    /**
     * 在 SQL 内先限制审核和数据范围，再按余弦距离筛选与排序。
     * @param query 查询文本
     * @param limit 最大返回数量
     * @param scope 服务端检索范围，缺省时仅公开知识
     * @return 命中文档及距离、相似度
     */
    @Override
    public List<Document> search(String query, int limit, RetrievalScope scope) {
        if (!StringUtils.hasText(query) || limit <= 0) {
            return List.of();
        }
        RetrievalScope effective = scope == null ? RetrievalScope.publicOnly() : scope;
        String vector = vectorLiteral(model.embed(query).content().vector());
        List<Object> parameters = new ArrayList<>();
        parameters.add(vector);
        String visibility = visibilitySql(effective, parameters);
        parameters.add(0.5);
        parameters.add(limit);
        String sql = "SELECT id, content, metadata, distance FROM (SELECT id, content, metadata, "
                + "embedding <=> CAST(? AS vector) AS distance FROM public.vector_store "
                + "WHERE metadata->>'reviewStatus' = 'APPROVED' AND (" + visibility + ")) scoped "
                + "WHERE distance <= ? ORDER BY distance ASC LIMIT ?";
        return jdbc.query(sql, (rs, row) -> {
            Map<String, Object> metadata;
            try {
                metadata = new HashMap<>(mapper.readValue(rs.getString("metadata"),
                        new TypeReference<Map<String, Object>>() { }));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("向量 metadata 解析失败", e);
            }
            double distance = rs.getDouble("distance");
            metadata.put("distance", distance);
            return new Document(rs.getString("id"), rs.getString("content"), metadata, 1 - distance);
        }, parameters.toArray());
    }

    /**
     * 构造权限谓词，租户、用户名和部门均通过参数绑定。
     * @param scope 可信权限范围
     * @param parameters SQL 参数列表
     * @return SQL 可见性谓词
     */
    private String visibilitySql(RetrievalScope scope, List<Object> parameters) {
        List<String> clauses = new ArrayList<>();
        clauses.add("metadata->>'visibility' = 'PUBLIC'");
        if (StringUtils.hasText(scope.tenantId())) {
            clauses.add("(metadata->>'visibility' = 'TENANT' AND metadata->>'tenantId' = ?)");
            parameters.add(scope.tenantId());
            if (scope.deptIds() == null || !scope.deptIds().isEmpty()) {
                String dept = "(metadata->>'visibility' = 'DEPT' AND metadata->>'tenantId' = ?";
                parameters.add(scope.tenantId());
                if (scope.deptIds() != null) {
                    dept += " AND metadata->>'deptId' IN ("
                            + String.join(",", java.util.Collections.nCopies(scope.deptIds().size(), "?")) + ")";
                    scope.deptIds().forEach(id -> parameters.add(String.valueOf(id)));
                }
                clauses.add(dept + ")");
            }
            if (StringUtils.hasText(scope.userId())) {
                clauses.add("(metadata->>'visibility' = 'PERSONAL' AND metadata->>'tenantId' = ? "
                        + "AND metadata->>'owner' = ?)");
                parameters.add(scope.tenantId());
                parameters.add(scope.userId());
            }
        }
        return String.join(" OR ", clauses);
    }

    /**
     * 校验向量并转换为 Pgvector 可解析的文本参数。
     * @param vector 模型向量
     * @return 向量文本
     */
    private String vectorLiteral(float[] vector) {
        if (vector == null || vector.length != dimensions) {
            throw new IllegalStateException("向量维度与现有向量库不一致");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("向量模型返回非有限数值");
            }
        }
        return java.util.Arrays.toString(vector);
    }
}
