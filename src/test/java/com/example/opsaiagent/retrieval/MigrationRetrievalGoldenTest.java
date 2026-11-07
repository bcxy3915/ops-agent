package com.example.opsaiagent.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 用共享夹具锁定迁移前可见性语义；数据库 filter 的等价性仍须在 Pgvector PoC 验证。 */
class MigrationRetrievalGoldenTest {

    @TestFactory
    List<DynamicTest> visibilityGoldenCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode fixture;
        try (InputStream input = getClass().getResourceAsStream("/migration/readonly-golden.json")) {
            assertThat(input).isNotNull();
            fixture = mapper.readTree(input);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : fixture.path("retrievalIsolationCases")) {
            JsonNode scope = entry.path("scope");
            Set<Long> departments = null;
            if (!scope.path("deptIds").isNull()) {
                departments = new LinkedHashSet<>();
                for (JsonNode deptId : scope.path("deptIds")) {
                    departments.add(deptId.asLong());
                }
            }
            RetrievalScope retrievalScope = new RetrievalScope(scope.path("tenantId").asText(null),
                    departments, scope.path("userId").asText(null));
            Map<String, Object> metadata = mapper.convertValue(entry.path("metadata"), new TypeReference<>() { });
            tests.add(DynamicTest.dynamicTest(entry.path("id").asText(), () ->
                    assertThat(retrievalScope.matches(metadata))
                            .isEqualTo(entry.path("expectedVisible").asBoolean())));
        }
        assertThat(tests).hasSize(6);
        return tests;
    }
}
