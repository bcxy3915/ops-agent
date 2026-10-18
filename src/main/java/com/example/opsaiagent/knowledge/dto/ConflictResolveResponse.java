package com.example.opsaiagent.knowledge.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识冲突裁定响应（P1）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflictResolveResponse {

    /** 冲突记录 ID */
    private Long conflictId;
    /** 冲突状态（RESOLVED） */
    private String status;
    /** 裁定结果：KEEP_NEW / KEEP_OLD / MANUAL */
    private String decision;
    /** 裁定人 */
    private String resolvedBy;
    /** 裁定时间 */
    private LocalDateTime resolvedAt;
}
