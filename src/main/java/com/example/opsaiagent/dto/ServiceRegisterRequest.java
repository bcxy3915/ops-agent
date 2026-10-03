package com.example.opsaiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.Map;

@Data
public class ServiceRegisterRequest {

    @NotBlank(message = "服务名不能为空")
    @Pattern(regexp = "[a-z0-9-]+", message = "服务名只能包含小写字母、数字、连字符")
    @Size(max = 128)
    private String name;

    @NotBlank(message = "baseUrl 不能为空")
    @Pattern(regexp = "https?://.+", message = "baseUrl 格式错误")
    private String baseUrl;

    private String healthPath = "/actuator/health";

    private String metricsPath = "/actuator/metrics";

    private String owner;

    private String env = "dev";

    private Map<String, String> tags;
}