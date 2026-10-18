package com.example.opsaiagent.diagnosis.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 诊断追问请求（T15/T16）
 */
@Data
public class DiagnosisFollowUpRequest {

    @NotBlank(message = "追问问题不能为空")
    private String question;
}
