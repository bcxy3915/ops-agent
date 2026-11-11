package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.inspection.config.BusinessProbeProperties.Definition;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.resilience.ResilienceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

/** 业务探针 HTTP 契约与同源边界测试，不访问真实商城。 */
class BusinessProbeCollectorTest {
    private MockRestServiceServer server;
    private RestClient client;
    private BusinessProbeCollector collector;
    private OpsServiceEntity target;
    private Definition probe;

    /**
     * 准备仅测试使用的目标、认证与探针定义。
     * 无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        collector = new BusinessProbeCollector(new ObjectMapper(), client, () -> 0L);
        target = new OpsServiceEntity();
        target.setId("mall-id");
        target.setBaseUrl("http://mall:8081/api");
        target.setAuthToken("test-only-token");
        probe = new Definition();
        probe.setServiceId("mall-id");
        probe.setPath("/products");
        probe.setJsonPointer("/code");
        probe.setExpectedValue("0");
    }

    /**
     * 正常业务断言通过，只发 GET 且不回传业务数据与凭据。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void checksBusinessValueWithoutExposingPayload() {
        server.expect(requestTo("http://mall:8081/api/products")).andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer test-only-token"))
                .andRespond(withSuccess("{\"code\":0,\"secret\":\"private-business-data\"}", MediaType.APPLICATION_JSON));
        var result = collector.collect(target, "products", probe);
        assertThat(result.status()).isEqualTo("HEALTHY");
        assertThat(result.reason()).isEqualTo("CHECK_PASSED");
        assertThat(result.toString()).doesNotContain("private-business-data", "test-only-token", "http://");
        server.verify();
    }

    /**
     * HTTP 成功但业务码错误时不能报告正常。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void detectsBusinessFailureInsideHttpSuccess() {
        server.expect(requestTo("http://mall:8081/api/products"))
                .andRespond(withSuccess("{\"code\":500}", MediaType.APPLICATION_JSON));
        var result = collector.collect(target, "products", probe);
        assertThat(result.status()).isEqualTo("ABNORMAL");
        assertThat(result.reason()).isEqualTo("BUSINESS_ASSERTION_FAILED");
        server.verify();
    }

    /**
     * 显式区分业务 HTTP 错误与无法判断业务的访问条件。
     * @param code 测试状态码
     * @param expected 预期证据状态
     * @return 无返回值
     */
    @ParameterizedTest
    @CsvSource({"500,ABNORMAL", "404,ABNORMAL", "401,INSUFFICIENT_EVIDENCE",
            "403,INSUFFICIENT_EVIDENCE", "302,INSUFFICIENT_EVIDENCE"})
    void classifiesHttpStatus(int code, String expected) {
        server.expect(requestTo("http://mall:8081/api/products"))
                .andRespond(withStatus(HttpStatus.valueOf(code)).header("Location", "http://other-host/"));
        assertThat(collector.collect(target, "products", probe).status()).isEqualTo(expected);
        server.verify();
    }

    /**
     * 非 JSON 响应无法证明业务断言。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void invalidJsonIsInsufficientEvidence() {
        server.expect(requestTo("http://mall:8081/api/products"))
                .andRespond(withSuccess("<html>login</html>", MediaType.TEXT_HTML));
        assertThat(collector.collect(target, "products", probe).reason()).isEqualTo("INVALID_JSON");
        server.verify();
    }

    /**
     * 超大响应不被完整解析或返回给模型。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void oversizedBodyIsInsufficientEvidence() {
        server.expect(requestTo("http://mall:8081/api/products"))
                .andRespond(withSuccess("x".repeat(65_537), MediaType.APPLICATION_JSON));
        assertThat(collector.collect(target, "products", probe).reason()).isEqualTo("RESPONSE_TOO_LARGE");
        server.verify();
    }

    /**
     * 断连不能被断言为服务业务已停止。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void connectionFailureDoesNotInventBusinessState() {
        server.expect(requestTo("http://mall:8081/api/products"))
                .andRespond(withException(new IOException("test failure")));
        var result = collector.collect(target, "products", probe);
        assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.httpStatus()).isNull();
        server.verify();
    }

    /**
     * 使用确定性时钟验证慢响应，无需休眠等待。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void detectsLatencyThreshold() {
        AtomicLong clock = new AtomicLong();
        collector = new BusinessProbeCollector(new ObjectMapper(), client,
                () -> clock.getAndAdd(3_000_000_000L));
        server.expect(requestTo("http://mall:8081/api/products"))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));
        assertThat(collector.collect(target, "products", probe).reason()).isEqualTo("LATENCY_EXCEEDED");
        server.verify();
    }

    /**
     * 路径绕过在 HTTP 请求前拒绝。
     * @param path 非法测试路径
     * @return 无返回值
     */
    @ParameterizedTest
    @ValueSource(strings = {"//other-host/a", "http://other-host/a", "/a/../b", "/%2e%2e/b",
            "/products?delete=true", "/products#part", "/a\\b"})
    void rejectsUnsafePaths(String path) {
        probe.setPath(path);
        assertThat(collector.collect(target, "products", probe).reason()).isEqualTo("INVALID_CONFIGURATION");
        server.verify();
    }

    /**
     * 只检查状态码的探针允许正常空响应。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void statusOnlyProbeSupportsNoContent() {
        probe.setExpectedStatus(204);
        probe.setJsonPointer(null);
        probe.setExpectedValue(null);
        server.expect(requestTo("http://mall:8081/api/products"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        assertThat(collector.collect(target, "products", probe).status()).isEqualTo("HEALTHY");
        server.verify();
    }

    /**
     * JSON 断言配置必须成对给出，防止静默变成弱检查。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsIncompleteAssertionBeforeRequest() {
        probe.setExpectedValue(null);
        assertThat(collector.collect(target, "products", probe).reason()).isEqualTo("INVALID_CONFIGURATION");
        server.verify();
    }

    /**
     * 验证生产连接工厂确实关闭 GET 自动重定向。
     * 无入参。
     * @return 无返回值
     * @throws IOException 测试连接初始化失败
     */
    @Test
    void disablesRedirectsInProductionTransport() throws IOException {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        new BusinessProbeCollector.NoRedirectRequestFactory(new ResilienceProperties())
                .prepareConnection(connection, "GET");
        verify(connection).setInstanceFollowRedirects(false);
    }
}
