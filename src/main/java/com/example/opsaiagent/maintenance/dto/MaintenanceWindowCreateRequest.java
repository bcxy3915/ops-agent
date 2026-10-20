package com.example.opsaiagent.maintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 维护窗口创建请求（P1 规格 5）
 * 多选服务 + 原因 + 到期时间；设置窗口时内部对存量告警批量静默。
 */
@Data
public class MaintenanceWindowCreateRequest {

    /** 多选服务名列表 */
    @NotEmpty(message = "至少选择一个服务")
    private List<String> services;

    /** 维护原因 */
    @NotBlank(message = "维护原因不能为空")
    @Size(max = 256, message = "维护原因最多 256 字")
    private String reason;

    /** 到期时间 */
    @NotNull(message = "到期时间不能为空")
    private LocalDateTime until;

    /** 备注 */
    @Size(max = 500, message = "备注最多 500 字")
    private String note;
}
