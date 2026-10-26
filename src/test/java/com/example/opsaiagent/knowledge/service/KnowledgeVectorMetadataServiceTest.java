package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.knowledge.dto.VectorMetadataValidationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * KnowledgeVectorMetadataService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("向量 metadata 校验")
class KnowledgeVectorMetadataServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("validate：统计缺失字段与内置/案例约定")
    void validate_counts() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
                "{\"source\":\"manual.md\",\"tenantId\":\"0\",\"visibility\":\"PUBLIC\",\"reviewStatus\":\"APPROVED\",\"owner\":\"system\"}",
                "{\"source\":\"upload:1\",\"tenantId\":\"t1\",\"visibility\":\"TENANT\",\"reviewStatus\":\"APPROVED\",\"owner\":\"u1\"}",
                "{\"source\":\"upload:2\",\"visibility\":\"PERSONAL\",\"reviewStatus\":\"DRAFT\"}",
                "{\"source\":\"case:1\",\"tenantId\":\"t1\",\"visibility\":\"DEPT\",\"reviewStatus\":\"APPROVED\",\"owner\":\"u\"}",
                "{\"source\":\"builtin.md\",\"tenantId\":\"t1\",\"visibility\":\"TENANT\",\"reviewStatus\":\"APPROVED\",\"owner\":\"system\"}",
                "{\"source\":\"upload:3\",\"tenantId\":\"t1\",\"visibility\":\"BAD\",\"reviewStatus\":\"APPROVED\",\"owner\":\"u\"}"
        ));
        KnowledgeVectorMetadataService service =
                new KnowledgeVectorMetadataService(jdbcTemplate, objectMapper);

        VectorMetadataValidationResponse response = service.validate();

        assertThat(response.getTotal()).isEqualTo(6);
        assertThat(response.getValid()).isEqualTo(2);
        assertThat(response.getMissingTenantId()).isEqualTo(1);
        assertThat(response.getMissingOwner()).isEqualTo(1);
        assertThat(response.getInvalidVisibility()).isEqualTo(1);
        assertThat(response.getCaseNotTenant()).isEqualTo(1);
        assertThat(response.getBuiltinNotPublic()).isEqualTo(1);
        assertThat(response.getBuiltinTenantNotPlatform()).isEqualTo(1);
        assertThat(response.getIssues()).hasSize(4);
    }

    @Test
    @DisplayName("validate：空 vector_store 返回全零")
    void validate_empty() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        KnowledgeVectorMetadataService service =
                new KnowledgeVectorMetadataService(jdbcTemplate, objectMapper);

        VectorMetadataValidationResponse response = service.validate();

        assertThat(response.getTotal()).isZero();
        assertThat(response.getValid()).isZero();
        assertThat(response.getIssues()).isEmpty();
    }
}
