package com.example.opsaiagent.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ServiceResponse {
    private Long id;
    private String name;
    private String baseUrl;
    private String healthPath;
    private String metricsPath;
    private String owner;
    private String env;
    private String status;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime registeredAt;
    private LocalDateTime updatedAt;
}