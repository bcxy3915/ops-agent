package com.example.opsaiagent.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ActuatorMetricFetcher 单元测试
 * 覆盖：指标 JSON 解析 / 空 measurements / 非法响应 / 服务端错误降级
 */
@DisplayName("Actuator 指标拉取")
class ActuatorMetricFetcherTest {

    private MockRestServiceServer server;
    private ActuatorMetricFetcher fetcher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        fetcher = new ActuatorMetricFetcher(new ObjectMapper());
        ReflectionTestUtils.setField(fetcher, "restClient", restClient);
    }

    @Test
    @DisplayName("fetch：正常解析 measurements")
    void fetch_parsesMeasurements() {
        server.expect(requestTo("http://svc:8080/actuator/metrics/process.cpu.usage"))
                .andRespond(withSuccess(
                        "{\"measurements\":[{\"statistic\":\"VALUE\",\"value\":0.12}]}",
                        MediaType.APPLICATION_JSON));

        ActuatorMetricFetcher.MetricValue value =
                fetcher.fetch("http://svc:8080", "process.cpu.usage");

        assertThat(value).isNotNull();
        assertThat(value.value()).isEqualTo(0.12);
    }

    @Test
    @DisplayName("fetch：COUNT/TOTAL_TIME/MAX 分别解析")
    void fetch_parsesAllStatistics() {
        server.expect(requestTo("http://svc:8080/actuator/metrics/jvm.gc.pause"))
                .andRespond(withSuccess(
                        "{\"measurements\":[" +
                                "{\"statistic\":\"COUNT\",\"value\":10}," +
                                "{\"statistic\":\"TOTAL_TIME\",\"value\":2.5}," +
                                "{\"statistic\":\"MAX\",\"value\":0.3}" +
                                "]}",
                        MediaType.APPLICATION_JSON));

        ActuatorMetricFetcher.MetricValue value =
                fetcher.fetch("http://svc:8080", "jvm.gc.pause");

        assertThat(value.count()).isEqualTo(10);
        assertThat(value.totalTime()).isEqualTo(2.5);
        assertThat(value.max()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("fetch：measurements 为空返回 null")
    void fetch_emptyMeasurements_returnsNull() {
        server.expect(requestTo("http://svc:8080/actuator/metrics/x"))
                .andRespond(withSuccess("{\"measurements\":[]}", MediaType.APPLICATION_JSON));

        assertThat(fetcher.fetch("http://svc:8080", "x")).isNull();
    }

    @Test
    @DisplayName("fetch：非法 JSON 返回 null")
    void fetch_invalidJson_returnsNull() {
        server.expect(requestTo("http://svc:8080/actuator/metrics/x"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        assertThat(fetcher.fetch("http://svc:8080", "x")).isNull();
    }

    @Test
    @DisplayName("fetch：服务端错误返回 null")
    void fetch_serverError_returnsNull() {
        server.expect(requestTo("http://svc:8080/actuator/metrics/x"))
                .andRespond(withServerError());

        assertThat(fetcher.fetch("http://svc:8080", "x")).isNull();
    }
}
