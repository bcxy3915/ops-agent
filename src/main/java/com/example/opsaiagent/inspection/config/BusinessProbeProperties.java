package com.example.opsaiagent.inspection.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/** 服务端预登记的只读业务探针，调用方只能选择探针标识，不能传入地址或请求体。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "ops-agent.inspection.business")
public class BusinessProbeProperties {
    /** 探针总量有限，默认没有绑定，不会自动探测未知业务接口。 */
    @Valid
    @Size(max = 50)
    private Map<@Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String, @Valid Definition> probes = new LinkedHashMap<>();

    /** 人工确认只读后登记的 GET 探针定义。 */
    @Data
    public static class Definition {
        /** 绑定已有服务 ID，避免同名服务跨租户歧义。 */
        @NotBlank
        private String serviceId;

        /** 相对于已登记 baseUrl 的固定路径，不支持查询参数与重定向。 */
        @NotBlank
        @Size(max = 512)
        private String path;

        /** 预期成功状态码。 */
        @Min(200)
        @Max(299)
        private int expectedStatus = 200;

        /** 响应耗时达到此阈值时标记异常，单位毫秒。 */
        @Min(1)
        @Max(60_000)
        private long maxLatencyMs = 2000;

        /** 可选 JSON Pointer，例如 /code；填写后要求标量值匹配。 */
        @Size(max = 256)
        private String jsonPointer;

        /** JSON 标量的预期文本表示，例如数字 0 写为字符串 "0"。 */
        @Size(max = 256)
        private String expectedValue;
    }
}
