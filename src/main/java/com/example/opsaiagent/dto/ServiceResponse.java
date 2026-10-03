package com.example.opsaiagent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "服务信息响应")
public class ServiceResponse {

    @Schema(description = "服务唯一标识（UUID）", example = "550e8400e29b41d4a716446655440000")
    private String id;

    @Schema(description = "服务名", example = "todo-service")
    private String name;

    @Schema(description = "基础地址", example = "http://localhost:8081")
    private String baseUrl;

    @Schema(description = "健康检查路径", example = "/actuator/health")
    private String healthPath;

    @Schema(description = "指标路径", example = "/actuator/metrics")
    private String metricsPath;

    @Schema(description = "负责人", example = "开发者")
    private String owner;

    @Schema(description = "环境", example = "dev", allowableValues = {"dev", "test", "prod"})
    private String env;

    @Schema(description = "当前健康状态", example = "UP", allowableValues = {"UP", "DOWN", "UNKNOWN"})
    private String status;

    @Schema(description = "最后检查时间", example = "2026-10-04T10:30:00")
    private LocalDateTime lastCheckedAt;

    @Schema(description = "注册时间", example = "2026-10-04T09:00:00")
    private LocalDateTime registeredAt;

    @Schema(description = "更新时间", example = "2026-10-04T10:30:00")
    private LocalDateTime updatedAt;
}