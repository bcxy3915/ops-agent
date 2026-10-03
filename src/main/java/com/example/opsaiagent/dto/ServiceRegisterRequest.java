package com.example.opsaiagent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "服务注册请求")
public class ServiceRegisterRequest {

    @Schema(description = "服务名", example = "blog-service", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "服务名不能为空")
    @Pattern(regexp = "[a-z0-9-]+", message = "服务名只能包含小写字母、数字、连字符")
    @Size(max = 128)
    private String name;

    @Schema(description = "基础地址", example = "http://localhost:8082", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "baseUrl 不能为空")
    @Pattern(regexp = "https?://.+", message = "baseUrl 格式错误")
    private String baseUrl;

    @Schema(description = "健康检查路径", example = "/actuator/health")
    private String healthPath = "/actuator/health";

    @Schema(description = "指标路径", example = "/actuator/metrics")
    private String metricsPath = "/actuator/metrics";

    @Schema(description = "负责人", example = "李四")
    private String owner;

    @Schema(description = "环境", example = "dev", allowableValues = {"dev", "test", "prod"})
    private String env = "dev";

    @Schema(description = "自定义标签，例如 {\"team\": \"backend\", \"level\": \"P1\"}")
    private Map<String, String> tags;
}