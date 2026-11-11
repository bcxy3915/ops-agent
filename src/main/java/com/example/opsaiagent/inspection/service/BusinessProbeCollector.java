package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.inspection.config.BusinessProbeProperties.Definition;
import com.example.opsaiagent.inspection.dto.BusinessProbeReport;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.resilience.ResilienceProperties;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.function.LongSupplier;

/** GET 业务探针采集：固定同源路径、不跟随重定向、有界解析，不执行写请求。 */
@Component
public class BusinessProbeCollector {
    private static final int MAX_BODY_BYTES = 65_536;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final LongSupplier nanoTime;

    /**
     * 沿用项目连接/读取超时，关闭重定向，避免认证被带往其他目标。
     * @param mapper JSON 映射器
     * @param properties 外部调用超时配置
     * @return 无返回值
     */
    @Autowired
    public BusinessProbeCollector(ObjectMapper mapper, ResilienceProperties properties) {
        this(mapper, RestClient.builder().requestFactory(new NoRedirectRequestFactory(properties)).build());
    }

    /**
     * 使用指定客户端创建采集器，供无网络契约测试使用。
     * @param mapper JSON 映射器
     * @param client 只读客户端
     * @return 无返回值
     */
    BusinessProbeCollector(ObjectMapper mapper, RestClient client) {
        this(mapper, client, System::nanoTime);
    }

    /**
     * 注入单调时钟，支持确定性的耗时边界验证。
     * @param mapper JSON 映射器
     * @param client 只读客户端
     * @param nanoTime 纳秒单调时钟
     * @return 无返回值
     */
    BusinessProbeCollector(ObjectMapper mapper, RestClient client, LongSupplier nanoTime) {
        this.mapper = mapper;
        this.client = client;
        this.nanoTime = nanoTime;
    }

    /**
     * 执行已授权、预登记的业务 GET 探针。
     * @param service 已授权服务
     * @param probeId 预登记探针标识
     * @param probe 预登记定义
     * @return 不含原始业务内容的证据
     */
    public BusinessProbeReport collect(OpsServiceEntity service, String probeId, Definition probe) {
        long started = nanoTime.getAsLong();
        URI target;
        try {
            target = target(service.getBaseUrl(), probe.getPath());
            if (probe.getExpectedStatus() < 200 || probe.getExpectedStatus() > 299
                    || probe.getMaxLatencyMs() < 1 || probe.getMaxLatencyMs() > 60_000
                    || (probe.getJsonPointer() == null) != (probe.getExpectedValue() == null)) {
                throw new IllegalArgumentException("探针定义无效");
            }
            if (probe.getJsonPointer() != null) {
                if (probe.getJsonPointer().isBlank()) {
                    throw new IllegalArgumentException("探针 JSON Pointer 不能为空");
                }
                JsonPointer.compile(probe.getJsonPointer());
            }
        } catch (Exception failure) {
            return report(service, probeId, "INSUFFICIENT_EVIDENCE", "INVALID_CONFIGURATION", null, started);
        }
        try {
            var request = client.get().uri(target);
            if (service.getAuthToken() != null && !service.getAuthToken().isBlank()) {
                request.headers(headers -> headers.setBearerAuth(service.getAuthToken()));
            }
            return request.exchange((httpRequest, response) -> {
                int code = response.getStatusCode().value();
                if (code == 401 || code == 403 || (code >= 300 && code < 400)) {
                    return report(service, probeId, "INSUFFICIENT_EVIDENCE", "ACCESS_OR_REDIRECT", code, started);
                }
                if (code != probe.getExpectedStatus()) {
                    return report(service, probeId, "ABNORMAL", "UNEXPECTED_HTTP_STATUS", code, started);
                }
                if (probe.getJsonPointer() != null) {
                    byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
                    if (body.length > MAX_BODY_BYTES) {
                        return report(service, probeId, "INSUFFICIENT_EVIDENCE", "RESPONSE_TOO_LARGE", code, started);
                    }
                    JsonNode root;
                    try {
                        root = mapper.readTree(body);
                    } catch (Exception failure) {
                        return report(service, probeId, "INSUFFICIENT_EVIDENCE", "INVALID_JSON", code, started);
                    }
                    JsonNode value = root == null ? null : root.at(probe.getJsonPointer());
                    if (value == null || value.isMissingNode() || value.isNull() || !value.isValueNode()
                            || !probe.getExpectedValue().equals(value.asText())) {
                        return report(service, probeId, "ABNORMAL", "BUSINESS_ASSERTION_FAILED", code, started);
                    }
                }
                boolean slow = elapsed(started) >= probe.getMaxLatencyMs();
                return report(service, probeId, slow ? "ABNORMAL" : "HEALTHY",
                        slow ? "LATENCY_EXCEEDED" : "CHECK_PASSED", code, started);
            });
        } catch (Exception failure) {
            return report(service, probeId, "INSUFFICIENT_EVIDENCE", "REQUEST_FAILED", null, started);
        }
    }

    /**
     * 校验并拼接固定目标，不接受绝对路径跳转、编码绕过或查询参数。
     * @param baseUrl 已登记的服务基础地址
     * @param path 管理员确认只读的相对路径
     * @return 同源探针 URI
     */
    private URI target(String baseUrl, String path) {
        URI base = URI.create(baseUrl);
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || base.getRawPath().contains("%")
                || !base.normalize().equals(base)
                || path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("%") || path.contains("\\") || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException("探针目标无效");
        }
        URI relative = URI.create(path);
        if (!relative.normalize().equals(relative)) {
            throw new IllegalArgumentException("探针路径不能包含层级跳转");
        }
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    /**
     * 计算单调时钟耗时。
     * @param started 开始时间纳秒值
     * @return 耗时毫秒数
     */
    private long elapsed(long started) {
        return (nanoTime.getAsLong() - started) / 1_000_000;
    }

    /**
     * 构建不含原始响应的业务证据。
     * @param service 目标服务
     * @param probeId 探针标识
     * @param status 证据状态
     * @param reason 结果原因码
     * @param code HTTP 状态码，失败时为 null
     * @param started 开始时间纳秒值
     * @return 业务探针报告
     */
    private BusinessProbeReport report(OpsServiceEntity service, String probeId, String status,
                                        String reason, Integer code, long started) {
        return new BusinessProbeReport(service.getId(), probeId, status, reason, code,
                elapsed(started), System.currentTimeMillis());
    }

    /** 关闭底层 GET 自动重定向，保持请求在预登记目标。 */
    static class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {
        /**
         * 应用既有超时配置。
         * @param properties 项目外部调用配置
         * @return 无返回值
         */
        NoRedirectRequestFactory(ResilienceProperties properties) {
            setConnectTimeout(properties.getConnectTimeoutMs());
            setReadTimeout(properties.getReadTimeoutMs());
        }

        /**
         * 初始化 HTTP 连接并禁止重定向。
         * @param connection 本次连接
         * @param method 请求方法
         * @return 无返回值
         * @throws IOException 连接初始化失败
         */
        @Override
        protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
