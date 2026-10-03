package com.example.opsaiagent.dto;

import lombok.Data;

import javax.validation.constraints.Pattern;
import java.util.Map;

@Data
public class ServiceUpdateRequest {

    @Pattern(regexp = "https?://.+", message = "baseUrl 格式错误")
    private String baseUrl;

    private String owner;

    private String env;

    private Map<String, String> tags;
}