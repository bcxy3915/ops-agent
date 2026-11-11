package com.example.opsaiagent.controller;

import com.example.opsaiagent.chat.controller.ChatController;
import com.example.opsaiagent.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** 全局普通业务接口响应契约检查，不启动应用或连接外部服务。 */
class ApiResponseContractTest {

    /**
     * 检查项目所有业务 Controller，普通接口必须显式返回统一响应，SSE 按协议独立输出。
     * 无入参。
     * @return 无返回值
     * @throws ClassNotFoundException 扫描到的 Controller 无法加载
     */
    @Test
    void allBusinessEndpointsUseApiResponseExceptSse() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var controllers = scanner.findCandidateComponents("com.example.opsaiagent");
        assertThat(controllers).isNotEmpty();

        for (var definition : controllers) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                if (controller == ChatController.class && method.getName().equals("askStream")) {
                    assertThat(method.getGenericReturnType().getTypeName())
                            .isEqualTo("reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<java.lang.String>>");
                    continue;
                }
                assertThat(method.getReturnType())
                        .as("普通接口 %s.%s 必须返回 ApiResponse", controller.getSimpleName(), method.getName())
                        .isEqualTo(ApiResponse.class);
            }
        }
    }
}
