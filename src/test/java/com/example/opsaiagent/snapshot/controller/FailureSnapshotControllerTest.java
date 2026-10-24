package com.example.opsaiagent.snapshot.controller;

import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.snapshot.dto.SnapshotData;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.service.FailureSnapshotService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 故障快照查询接口测试（T13）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("故障快照接口")
class FailureSnapshotControllerTest {

    @Mock
    private FailureSnapshotService snapshotService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @InjectMocks
    private FailureSnapshotController controller;

    private FailureSnapshotEntity entity() {
        FailureSnapshotEntity e = new FailureSnapshotEntity();
        e.setId(1L);
        e.setAlertEventId(100L);
        e.setRuleId(1L);
        e.setRuleName("cpu-high");
        e.setService("svc-a");
        e.setMetric("cpu");
        e.setStatus("FIRING");
        e.setCreatedAt(LocalDateTime.now());
        e.setSnapshotJson("{\"event\":{\"ruleName\":\"cpu-high\"}}");
        return e;
    }

    @Test
    @DisplayName("列表：返回快照实体列表")
    void list() {
        when(snapshotService.list(anyString(), anyLong(), anyInt())).thenReturn(List.of(entity()));
        ApiResponse<List<FailureSnapshotEntity>> resp = controller.list("svc-a", 100L, 50);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getRuleName()).isEqualTo("cpu-high");
    }

    @Test
    @DisplayName("列表：无过滤条件也能查询")
    void list_noFilter() {
        when(snapshotService.list(null, null, 50)).thenReturn(List.of(entity()));
        ApiResponse<List<FailureSnapshotEntity>> resp = controller.list(null, null, 50);
        assertThat(resp.getData()).hasSize(1);
    }

    @Test
    @DisplayName("详情：JSON 可解析时返回结构化快照")
    void detail_parsesJson() {
        when(snapshotService.getById(1L)).thenReturn(entity());
        ApiResponse<Map<String, Object>> resp = controller.detail(1L);
        assertThat(resp.getData().get("id")).isEqualTo(1L);
        assertThat(resp.getData().get("snapshot")).isInstanceOf(SnapshotData.class);
    }

    @Test
    @DisplayName("详情：JSON 解析失败时原样返回字符串")
    void detail_fallbackRawJson() throws Exception {
        FailureSnapshotEntity bad = entity();
        bad.setSnapshotJson("not-json");
        when(snapshotService.getById(1L)).thenReturn(bad);
        ApiResponse<Map<String, Object>> resp = controller.detail(1L);
        assertThat(resp.getData().get("snapshot")).isEqualTo("not-json");
    }
}
