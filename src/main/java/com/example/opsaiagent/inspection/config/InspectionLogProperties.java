package com.example.opsaiagent.inspection.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/** 服务端固定日志映射；默认关闭，不允许请求或模型指定文件路径。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "ops-agent.inspection.logs")
public class InspectionLogProperties {
    /** 最多绑定 50 个本机日志源，应用身份必须本身具有文件读取权限。 */
    @Valid
    @Size(max = 50)
    private Map<@Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String, @Valid Source> sources = new LinkedHashMap<>();

    /** 人工登记的单一日志文件，只读访问，不支持通配符或自动目录扫描。 */
    @Data
    public static class Source {
        /** 绑定的已登记服务 ID。 */
        @NotBlank
        private String serviceId;
        /** 允许读取的专用日志根目录。 */
        @NotBlank
        private String root;
        /** 根目录内的相对日志文件名。 */
        @NotBlank
        private String file;
        /** 支持 JSONL 和默认 Spring 风格单行日志。 */
        @Pattern(regexp = "JSONL|SPRING")
        private String format = "SPRING";
        /** 无时区日志使用的时区，避免依赖后端所在机器的默认时区。 */
        @NotBlank
        private String zoneId = "Asia/Shanghai";
    }
}
