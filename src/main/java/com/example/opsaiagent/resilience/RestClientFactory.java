package com.example.opsaiagent.resilience;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient 工厂：统一为第三方调用配置连接 / 读取超时。
 */
public final class RestClientFactory {

    private RestClientFactory() {
    }

    /**
     * 使用默认容错配置创建 RestClient.Builder
     */
    public static RestClient.Builder builder() {
        return builder(new ResilienceProperties());
    }

    /**
     * 使用指定容错配置创建 RestClient.Builder（超时从配置读取）
     */
    public static RestClient.Builder builder(ResilienceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        return RestClient.builder().requestFactory(requestFactory);
    }
}
