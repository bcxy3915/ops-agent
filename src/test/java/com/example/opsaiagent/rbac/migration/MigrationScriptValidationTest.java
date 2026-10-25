package com.example.opsaiagent.rbac.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移脚本静态校验。
 *
 * 校验 10-rbac.sql 为每个业务表都补齐 tenant_id（以及需要部门的表补齐 dept_id），
 * 并包含审计租户索引，避免实体字段与数据库列不一致。
 */
@DisplayName("迁移脚本")
class MigrationScriptValidationTest {

    private static final List<String> TENANT_TABLES = List.of(
            "ops_user", "ops_service", "metric_sample",
            "alert_rule", "alert_event", "user_alert_read", "alert_rule_escalation_level", "alert_escalation",
            "notification_channel", "notification_record", "failure_snapshot", "diagnosis_record",
            "knowledge_document", "knowledge_chunk", "knowledge_case", "knowledge_hit_log",
            "knowledge_conflict", "knowledge_document_version",
            "maintenance_window", "oncall_member", "ops_audit_log", "ops_conversation", "ops_message");

    private static final List<String> DEPT_TABLES = List.of(
            "ops_user", "ops_service", "alert_rule", "alert_event", "user_alert_read", "alert_escalation",
            "notification_channel", "notification_record", "failure_snapshot", "diagnosis_record",
            "knowledge_document", "knowledge_chunk", "knowledge_case", "knowledge_hit_log",
            "knowledge_conflict", "knowledge_document_version",
            "maintenance_window", "oncall_member", "ops_audit_log");

    private String sql() throws IOException {
        Path path = Path.of("init-scripts", "10-rbac.sql");
        // 折叠连续空白，避免对齐空格影响断言
        return Files.readString(path).replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("所有业务表均补齐 tenant_id 列")
    void allTenantTablesHaveTenantColumn() throws IOException {
        String sql = sql();
        for (String table : TENANT_TABLES) {
            assertThat(sql)
                    .as("表 %s 缺少 tenant_id 列", table)
                    .contains("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS tenant_id");
        }
    }

    @Test
    @DisplayName("需要部门的表均补齐 dept_id 列")
    void deptTablesHaveDeptColumn() throws IOException {
        String sql = sql();
        for (String table : DEPT_TABLES) {
            assertThat(sql)
                    .as("表 %s 缺少 dept_id 列", table)
                    .contains("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS dept_id");
        }
    }

    @Test
    @DisplayName("包含审计租户索引")
    void containsAuditTenantIndex() throws IOException {
        assertThat(sql()).contains("idx_audit_tenant");
    }

    @Test
    @DisplayName("tenant_id 回填为 NOT NULL")
    void tenantColumnsNotNull() throws IOException {
        assertThat(sql())
                .contains("ALTER TABLE ops_user")
                .contains("ALTER COLUMN tenant_id SET NOT NULL");
    }
}
