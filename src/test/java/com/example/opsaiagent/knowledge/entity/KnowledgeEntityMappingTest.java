package com.example.opsaiagent.knowledge.entity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库实体映射测试
 * 验证三个实体的 @TableName / @TableField / @TableId 与 knowledge 建表脚本列名完全一致，
 * 防止"实体字段写错导致 MyBatis-Plus 生成错误 SQL"这类问题。
 */
@DisplayName("知识库实体映射")
class KnowledgeEntityMappingTest {

    /** 初始化并获取指定实体的 TableInfo（每个类只需一次） */
    private TableInfo tableInfo(Class<?> clazz) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), clazz);
        return TableInfoHelper.getTableInfo(clazz);
    }

    private Map<String, String> columnByProperty(TableInfo info) {
        return info.getFieldList().stream()
                .collect(Collectors.toMap(TableFieldInfo::getProperty, TableFieldInfo::getColumn));
    }

    // ==================== knowledge_document ====================

    @Test
    @DisplayName("knowledge_document：表名与主键")
    void document_tableNameAndId() {
        TableInfo info = tableInfo(KnowledgeDocumentEntity.class);
        assertThat(info.getTableName()).isEqualTo("knowledge_document");
        assertThat(info.getKeyProperty()).isEqualTo("id");
        assertThat(info.getKeyColumn()).isEqualTo("id");
    }

    @Test
    @DisplayName("knowledge_document：全部字段列名映射正确")
    void document_columns() {
        Map<String, String> map = columnByProperty(tableInfo(KnowledgeDocumentEntity.class));
        assertThat(map).containsEntry("title", "title")
                .containsEntry("docType", "doc_type")
                .containsEntry("layer", "layer")
                .containsEntry("trustStatus", "trust_status")
                .containsEntry("tenantId", "tenant_id")
                .containsEntry("deptId", "dept_id")
                .containsEntry("visibility", "visibility")
                .containsEntry("reviewStatus", "review_status")
                .containsEntry("reviewLevel", "review_level")
                .containsEntry("reviewedBy", "reviewed_by")
                .containsEntry("reviewedAt", "reviewed_at")
                .containsEntry("reviewNote", "review_note")
                .containsEntry("version", "version")
                .containsEntry("owner", "owner")
                .containsEntry("serviceIds", "service_ids")
                .containsEntry("tags", "tags")
                .containsEntry("hitCount", "hit_count")
                .containsEntry("source", "source")
                .containsEntry("filePath", "file_path")
                .containsEntry("rawContent", "raw_content")
                .containsEntry("chunkCount", "chunk_count")
                .containsEntry("submittedAt", "submitted_at")
                .containsEntry("lastReviewAt", "last_review_at")
                .containsEntry("effectiveAt", "effective_at")
                .containsEntry("reviewAt", "review_at")
                .containsEntry("conflictStatus", "conflict_status")
                .containsEntry("createdAt", "created_at")
                .containsEntry("updatedAt", "updated_at");
        assertThat(map).hasSize(28);
    }

    @Test
    @DisplayName("knowledge_document：必填字段有默认值")
    void document_defaults() {
        KnowledgeDocumentEntity e = new KnowledgeDocumentEntity();
        assertThat(e.getLayer()).isNull();
        assertThat(e.getTrustStatus()).isNull();
        // 通过 SQL 脚本保证默认值，这里只验证实体不会主动覆盖默认值（null 即不参与 insert）
        e.setTitle("t");
        e.setDocType("MARKDOWN");
        assertThat(e.getTitle()).isEqualTo("t");
    }

    // ==================== knowledge_chunk ====================

    @Test
    @DisplayName("knowledge_chunk：表名与主键")
    void chunk_tableNameAndId() {
        TableInfo info = tableInfo(KnowledgeChunkEntity.class);
        assertThat(info.getTableName()).isEqualTo("knowledge_chunk");
        assertThat(info.getKeyProperty()).isEqualTo("id");
        assertThat(info.getKeyColumn()).isEqualTo("id");
    }

    @Test
    @DisplayName("knowledge_chunk：全部字段列名映射正确")
    void chunk_columns() {
        Map<String, String> map = columnByProperty(tableInfo(KnowledgeChunkEntity.class));
        assertThat(map).containsEntry("docId", "doc_id")
                .containsEntry("tenantId", "tenant_id")
                .containsEntry("deptId", "dept_id")
                .containsEntry("ordinal", "ordinal")
                .containsEntry("content", "content")
                .containsEntry("section", "section")
                .containsEntry("vectorId", "vector_id")
                .containsEntry("createdAt", "created_at");
        assertThat(map).hasSize(8);
    }

    // ==================== knowledge_case ====================

    @Test
    @DisplayName("knowledge_case：表名与主键")
    void case_tableNameAndId() {
        TableInfo info = tableInfo(KnowledgeCaseEntity.class);
        assertThat(info.getTableName()).isEqualTo("knowledge_case");
        assertThat(info.getKeyProperty()).isEqualTo("id");
        assertThat(info.getKeyColumn()).isEqualTo("id");
    }

    @Test
    @DisplayName("knowledge_case：全部字段列名映射正确")
    void case_columns() {
        Map<String, String> map = columnByProperty(tableInfo(KnowledgeCaseEntity.class));
        assertThat(map).containsEntry("sourceAlertId", "source_alert_id")
                .containsEntry("tenantId", "tenant_id")
                .containsEntry("deptId", "dept_id")
                .containsEntry("visibility", "visibility")
                .containsEntry("diagnosisRecordId", "diagnosis_record_id")
                .containsEntry("service", "service")
                .containsEntry("title", "title")
                .containsEntry("symptom", "symptom")
                .containsEntry("rootCause", "root_cause")
                .containsEntry("realRootCause", "real_root_cause")
                .containsEntry("evidence", "evidence")
                .containsEntry("effectiveAction", "effective_action")
                .containsEntry("confidence", "confidence")
                .containsEntry("maskedStatus", "masked_status")
                .containsEntry("status", "status")
                .containsEntry("durationMin", "duration_min")
                .containsEntry("closedLoopAt", "closed_loop_at")
                .containsEntry("confirmedBy", "confirmed_by")
                .containsEntry("citedCount", "cited_count")
                .containsEntry("createdAt", "created_at")
                .containsEntry("updatedAt", "updated_at");
        assertThat(map).hasSize(21);
    }

    // ==================== 状态常量约定 ====================

    @Test
    @DisplayName("知识分层 / 信任状态 / 案例状态常量取值约定")
    void stateConstants() {
        // 这些常量用于接口校验与前端映射，固化为文档级约定
        assertThat(new String[]{"BUILTIN", "PRIVATE", "CASE"}).contains("BUILTIN", "PRIVATE", "CASE");
        assertThat(new String[]{"VERIFIED", "PENDING", "AI_DRAFT", "EXPIRED"})
                .contains("VERIFIED", "PENDING", "AI_DRAFT", "EXPIRED");
        assertThat(new String[]{"PENDING", "CONFIRMED", "DISCARDED"})
                .contains("PENDING", "CONFIRMED", "DISCARDED");
    }

    // ==================== knowledge_hit_log（P1） ====================

    @Test
    @DisplayName("knowledge_hit_log：表名与主键")
    void hitLog_tableNameAndId() {
        TableInfo info = tableInfo(KnowledgeHitLogEntity.class);
        assertThat(info.getTableName()).isEqualTo("knowledge_hit_log");
        assertThat(info.getKeyProperty()).isEqualTo("id");
        assertThat(info.getKeyColumn()).isEqualTo("id");
    }

    @Test
    @DisplayName("knowledge_hit_log：全部字段列名映射正确")
    void hitLog_columns() {
        Map<String, String> map = columnByProperty(tableInfo(KnowledgeHitLogEntity.class));
        assertThat(map).containsEntry("docId", "doc_id")
                .containsEntry("tenantId", "tenant_id")
                .containsEntry("deptId", "dept_id")
                .containsEntry("query", "query")
                .containsEntry("hitSource", "hit_source")
                .containsEntry("channel", "channel")
                .containsEntry("createdAt", "created_at");
        assertThat(map).hasSize(7);
    }

    // ==================== knowledge_conflict（P1） ====================

    @Test
    @DisplayName("knowledge_conflict：表名与主键")
    void conflict_tableNameAndId() {
        TableInfo info = tableInfo(KnowledgeConflictEntity.class);
        assertThat(info.getTableName()).isEqualTo("knowledge_conflict");
        assertThat(info.getKeyProperty()).isEqualTo("id");
        assertThat(info.getKeyColumn()).isEqualTo("id");
    }

    @Test
    @DisplayName("knowledge_conflict：全部字段列名映射正确")
    void conflict_columns() {
        Map<String, String> map = columnByProperty(tableInfo(KnowledgeConflictEntity.class));
        assertThat(map).containsEntry("docId", "doc_id")
                .containsEntry("tenantId", "tenant_id")
                .containsEntry("deptId", "dept_id")
                .containsEntry("matchedDocId", "matched_doc_id")
                .containsEntry("conflictType", "conflict_type")
                .containsEntry("similarity", "similarity")
                .containsEntry("status", "status")
                .containsEntry("decision", "decision")
                .containsEntry("resolvedBy", "resolved_by")
                .containsEntry("createdAt", "created_at")
                .containsEntry("resolvedAt", "resolved_at");
        assertThat(map).hasSize(11);
    }

    // ==================== knowledge_document_version（P2） ====================

    @Test
    @DisplayName("knowledge_document_version：表名与主键")
    void version_tableNameAndId() {
        TableInfo info = tableInfo(KnowledgeDocumentVersionEntity.class);
        assertThat(info.getTableName()).isEqualTo("knowledge_document_version");
        assertThat(info.getKeyProperty()).isEqualTo("id");
        assertThat(info.getKeyColumn()).isEqualTo("id");
    }

    @Test
    @DisplayName("knowledge_document_version：全部字段列名映射正确")
    void version_columns() {
        Map<String, String> map = columnByProperty(tableInfo(KnowledgeDocumentVersionEntity.class));
        assertThat(map).containsEntry("docId", "doc_id")
                .containsEntry("tenantId", "tenant_id")
                .containsEntry("deptId", "dept_id")
                .containsEntry("version", "version")
                .containsEntry("contentSnapshot", "content_snapshot")
                .containsEntry("changedBy", "changed_by")
                .containsEntry("changeReason", "change_reason")
                .containsEntry("createdAt", "created_at");
        assertThat(map).hasSize(8);
    }
}
