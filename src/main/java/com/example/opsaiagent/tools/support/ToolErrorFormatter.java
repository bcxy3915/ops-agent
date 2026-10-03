package com.example.opsaiagent.tools.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

/**
 * 工具错误信息格式化器
 * 把底层异常转换成模型能理解的精准描述
 */
@Slf4j
public class ToolErrorFormatter {

    /**
     * 格式化 HTTP 调用异常
     *
     * @param serviceName 服务名
     * @param url         请求的完整 URL
     * @param e           捕获的异常
     * @return 模型能理解的错误描述
     */
    public static String formatHttpError(String serviceName, String url, Exception e) {
        // 1. 连接类错误：服务已注册但连不上
        if (e instanceof ResourceAccessException) {
            Throwable cause = e.getCause();
            if (cause instanceof SocketTimeoutException) {
                return String.format(
                        "服务 %s 已注册（地址 %s），但请求超时。可能原因：服务响应慢、网络延迟、下游依赖阻塞。",
                        serviceName, url);
            }
            return String.format(
                    "服务 %s 已注册（地址 %s），但连接失败。可能原因：进程未启动、端口未监听、网络不通。底层错误：%s",
                    serviceName, url, cause != null ? cause.getMessage() : e.getMessage());
        }

        // 2.4xx 错误：客户端问题
        if (e instanceof HttpClientErrorException.NotFound) {
            return String.format(
                    "服务 %s 已注册（地址 %s），但接口返回 404。该端点可能不存在或未暴露。",
                    serviceName, url);
        }
        if (e instanceof HttpClientErrorException hce) {
            return String.format(
                    "服务 %s 已注册（地址 %s），但接口返回 %s。请检查端点配置。",
                    serviceName, url, hce.getStatusCode());
        }

        // 3. 5xx 错误：服务端问题
        if (e instanceof HttpServerErrorException hse) {
            return String.format(
                    "服务 %s 已注册（地址 %s），但服务端返回 %s。服务本身可能异常。",
                    serviceName, url, hse.getStatusCode());
        }

        // 4. 其他未知错误
        log.warn("未分类的工具异常: service={}, url={}", serviceName, url, e);
        return String.format(
                "查询服务 %s（地址 %s）失败：%s",
                serviceName, url, e.getMessage());
    }
}
