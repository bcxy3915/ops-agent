package com.example.opsaiagent.alert.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 告警汇总统计（P1 规格 2.3）
 * 供管理后台首页「待处理告警」统计卡与告警中心页顶部汇总卡共用。
 */
@Data
@Builder
public class AlertSummaryResponse {

    /** 待处理告警数（FIRING + ACKNOWLEDGED，与铃铛口径一致） */
    private long openCount;
    /** 仅 FIRING 数量（告警页汇总卡使用） */
    private long firingCount;
    /** 今日触发告警数（firedAt >= 今日 0 点） */
    private long todayCount;
    /** 有关联成功诊断的事件数（diagnosis_record.status=SUCCESS 去重 alert_event_id） */
    private long diagnosedCount;
    /** 平均诊断耗时（毫秒）；无数据返回 null */
    private Long avgDiagnoseMs;
}
