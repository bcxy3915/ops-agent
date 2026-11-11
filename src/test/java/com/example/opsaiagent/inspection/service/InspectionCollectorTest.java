package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 验证只读采集的真实 HTTP 解析契约，不访问外部网络。 */
class InspectionCollectorTest {
    private MockRestServiceServer server;
    private InspectionCollector collector;
    private OpsServiceEntity service;

    /**
     * 初始化模拟 HTTP 与已登记服务。
     * 无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        collector = new InspectionCollector(new ObjectMapper(), builder.build());
        service = new OpsServiceEntity();
        service.setBaseUrl("http://mall:8081");
        service.setHealthPath("/manage/health");
        service.setMetricsPath("/manage/metrics/");
    }

    /**
     * 确认明确的 503 健康响应保留 DOWN，认证不会进入证据。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void preservesExplicitDownAndUsesRegisteredAuthentication() {
        service.setAuthToken("test-token");
        server.expect(requestTo("http://mall:8081/manage/health"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"DOWN\"}"));
        var result = collector.health(service);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.value()).isEqualTo("DOWN");
        assertThat(result.toString()).doesNotContain("test-token", "http://");
        server.verify();
    }

    /**
     * 确认自定义指标路径有效，数值零为可用证据。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void acceptsRealZeroFromCustomMetricsPath() {
        server.expect(requestTo("http://mall:8081/manage/metrics/process.cpu.usage"))
                .andRespond(withSuccess("{\"measurements\":[{\"statistic\":\"VALUE\",\"value\":0}]}",
                        MediaType.APPLICATION_JSON));
        var result = collector.metric(service, "process.cpu.usage");
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.value()).isEqualTo(0.0);
        server.verify();
    }

    /**
     * 确认认证失败不能作为健康或零值指标。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void doesNotInterpretAuthenticationFailureAsDown() {
        server.expect(requestTo("http://mall:8081/manage/health"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        var result = collector.health(service);
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.value()).isNull();
        server.verify();
    }

    /**
     * 确认缺少 VALUE 的响应不会伪造零值。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsMissingMetricValue() {
        server.expect(requestTo("http://mall:8081/manage/metrics/process.cpu.usage"))
                .andRespond(withSuccess("{\"measurements\":[{\"statistic\":\"COUNT\",\"value\":1}]}",
                        MediaType.APPLICATION_JSON));
        var result = collector.metric(service, "process.cpu.usage");
        assertThat(result.status()).isEqualTo("INVALID");
        assertThat(result.value()).isNull();
        server.verify();
    }

    /**
     * 确认不允许将端点路径换成另一主机或查询任意指标。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsUnregisteredTargetAndMetricWithoutHttp() {
        service.setHealthPath("//other-host/health");
        assertThat(collector.health(service).status()).isEqualTo("INVALID");
        assertThatThrownBy(() -> collector.metric(service, "arbitrary.metric"))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}
