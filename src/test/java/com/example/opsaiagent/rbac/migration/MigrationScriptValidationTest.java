package com.example.opsaiagent.rbac.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据库脚本静态校验。
 *
 * 校验 schema.sql 为每个业务表都包含 tenant_id（以及需要部门的表包含 dept_id），
 * 并包含审计租户索引和表/字段注释，避免实体字段与数据库列不一致。
 */
@DisplayName("数据库脚本")
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
        Path path = Path.of("init-scripts", "schema.sql");
        return Files.readString(path);
    }

    /**
     * 截取单张表的 CREATE TABLE 定义。
     *
     * @param sql   全量脚本
     * @param table 表名
     * @return 该表的建表语句
     */
    private String tableBlock(String sql, String table) {
        String marker = "CREATE TABLE IF NOT EXISTS " + table + " (";
        int start = sql.indexOf(marker);
        assertThat(start).as("schema.sql 缺少表 %s", table).isGreaterThanOrEqualTo(0);
        int end = sql.indexOf("\n);", start);
        assertThat(end).as("表 %s 定义未闭合", table).isGreaterThan(start);
        return sql.substring(start, end);
    }

    @Test
    @DisplayName("所有业务表均包含 tenant_id 列")
    void allTenantTablesHaveTenantColumn() throws IOException {
        String sql = sql();
        for (String table : TENANT_TABLES) {
            assertThat(tableBlock(sql, table))
                    .as("表 %s 缺少 tenant_id 列", table)
                    .contains("tenant_id ");
        }
    }

    @Test
    @DisplayName("需要部门的表均包含 dept_id 列")
    void deptTablesHaveDeptColumn() throws IOException {
        String sql = sql();
        for (String table : DEPT_TABLES) {
            assertThat(tableBlock(sql, table))
                    .as("表 %s 缺少 dept_id 列", table)
                    .contains("dept_id ");
        }
    }

    @Test
    @DisplayName("包含审计租户索引")
    void containsAuditTenantIndex() throws IOException {
        assertThat(sql()).contains("idx_audit_tenant");
    }

    @Test
    @DisplayName("tenant_id 为 NOT NULL")
    void tenantColumnsNotNull() throws IOException {
        for (String table : TENANT_TABLES) {
            assertThat(tableBlock(sql(), table))
                    .as("表 %s 的 tenant_id 应为 NOT NULL", table)
                    .containsPattern("tenant_id\\s+VARCHAR\\(32\\).*NOT NULL");
        }
    }

    @Test
    @DisplayName("所有表均带表注释")
    void allTablesHaveComments() throws IOException {
        String sql = sql();
        for (String table : TENANT_TABLES) {
            assertThat(sql)
                    .as("表 %s 缺少 COMMENT ON TABLE", table)
                    .contains("COMMENT ON TABLE " + table + " IS ");
        }
    }
}
