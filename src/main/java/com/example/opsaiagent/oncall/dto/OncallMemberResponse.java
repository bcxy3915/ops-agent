package com.example.opsaiagent.oncall.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 值班人响应（P1 规格 7）
 */
@Data
@Builder
public class OncallMemberResponse {

    private Long id;
    private Long userId;
    private String dutyRole;
    private String addedBy;
    private LocalDateTime addedAt;
}
