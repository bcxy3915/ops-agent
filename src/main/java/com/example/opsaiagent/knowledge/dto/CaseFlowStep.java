package com.example.opsaiagent.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 案例闭环链路步骤（T20）
 * 五步链路：ALERT(告警触发) → SNAPSHOT(快照采集) → DIAGNOSIS(AI 诊断) → ACTION(有效处置) → RECOVERY(闭环恢复)。
 */
@Data
@Builder
public class CaseFlowStep {

    /** 步骤类型：ALERT / SNAPSHOT / DIAGNOSIS / ACTION / RECOVERY */
    private String step;

    /** 步骤标题 */
    private String title;

    /** 步骤内容摘要 */
    private String content;

    /** 步骤时间（可为空） */
    private LocalDateTime time;
}
