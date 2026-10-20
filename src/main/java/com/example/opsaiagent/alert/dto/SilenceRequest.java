package com.example.opsaiagent.alert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 手动静默请求（规格 2.5）：任意状态 → SILENCED（带到期时间 + 原因 + 可选备注）
 */
@Data
public class SilenceRequest {

    /** 静默原因（必填，枚举：PLANNED_MAINTENANCE / KNOWN_ISSUE / FALSE_POSITIVE / OTHER） */
    @NotBlank(message = "静默原因不能为空")
    private String reason;

    /** 静默到期时间（必填，> now+15min，最多 7 天） */
    @NotNull(message = "静默到期时间不能为空")
    private LocalDateTime silencedUntil;

    /** 静默备注（可选，最多 500 字） */
    @Size(max = 500, message = "静默备注最多 500 字")
    private String note;
}
