package com.example.opsaiagent.retrieval.elasticsearch;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** ES 请求边界：统一超时、认证及失败处理，不隐式创建索引。 */
@Component
public class ElasticsearchGateway {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String url;
    private final String authorization;
    private final Duration timeout;
    private final String readIndex;
    private final String writeIndex;

    /**
     * 创建 ES 请求客户端。
     * @param mapper JSON 映射器
     * @param url ES 地址
     * @param username 用户名，无认证环境可为空
     * @param password 密码
     * @param timeout 请求超时
     * @param readIndex 读取别名
     * @param writeIndex 写入别名
     * @return 无返回值
     */
    public ElasticsearchGateway(ObjectMapper mapper,
            @Value("${ops-agent.ai.elasticsearch.url:http://localhost:9200}") String url,
            @Value("${ops-agent.ai.elasticsearch.username:}") String username,
            @Value("${ops-agent.ai.elasticsearch.password:}") String password,
            @Value("${ops-agent.ai.elasticsearch.timeout:15s}") Duration timeout,
            @Value("${ops-agent.ai.elasticsearch.read-index:ops-knowledge-read}") String readIndex,
            @Value("${ops-agent.ai.elasticsearch.write-index:ops-knowledge-write}") String writeIndex) {
        this.mapper = mapper;
        this.url = url.replaceAll("/+$", "");
        this.timeout = timeout;
        this.readIndex = indexName(readIndex);
        this.writeIndex = indexName(writeIndex);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.authorization = username.isBlank() ? null : "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 执行 ES JSON 或 NDJSON 请求，服务端失败时抛出异常以触发持久化重试。
     * @param method HTTP 方法
     * @param path 可信路径
     * @param body 请求文本
     * @return JSON 响应
     */
    public JsonNode request(String method, String path, String body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(url + path)).timeout(timeout)
                    .header("Content-Type", path.contains("_bulk") ? "application/x-ndjson" : "application/json");
            if (authorization != null) builder.header("Authorization", authorization);
            var response = client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ES 请求失败，HTTP 状态=" + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ES 请求被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("ES 连接或响应解析失败", e);
        }
    }

    /**
     * 获取读别名。
     * @return 读别名，无入参
     */
    public String readIndex() { return readIndex; }

    /**
     * 获取写别名。
     * @return 写别名，无入参
     */
    public String writeIndex() { return writeIndex; }

    /**
     * 校验别名避免路径或查询参数注入。
     * @param name 别名
     * @return 校验后的别名
     */
    private static String indexName(String name) {
        if (!name.matches("[a-z0-9][a-z0-9._-]*")) throw new IllegalArgumentException("ES 别名格式不合法");
        return name;
    }
}
