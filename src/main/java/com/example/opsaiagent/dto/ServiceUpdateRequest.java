package com.example.opsaiagent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Pattern;
import java.util.Map;

@Data
@Schema(description = "服务更新请求（所有字段可选，只传需要更新的字段）")
public class ServiceUpdateRequest {

    @Schema(description = "基础地址", example = "http://localhost:8081")
    @Pattern(regexp = "https?://.+", message = "baseUrl 格式错误")
    private String baseUrl;

    @Schema(description = "负责人", example = "张三")
    private String owner;

    @Schema(description = "环境", example = "prod", allowableValues = {"dev", "test", "prod"})
    private String env;

    @Schema(description = "自定义标签，例如 {\"team\": \"backend\", \"level\": \"P1\"}")
    private Map<String, String> tags;
}