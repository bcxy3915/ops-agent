package com.example.opsaiagent.alert.dto;

import lombok.Data;

import java.util.List;

/**
 * 升级路径单级定义（规格 4.1）
 * 示例：Level 1 · 5 分钟未确认 → 通知 OPERATOR
 */
@Data
public class AlertRuleLevelDto {

    /** 触发条件计时秒数（after），1m ~ 24h，必须大于上一级 */
    private Integer afterSeconds;

    /** 触发条件：UNACKNOWLEDGED（未确认）/ UNRESOLVED（未恢复） */
    private String triggerOn = "UNACKNOWLEDGED";

    /** 通知角色：OPERATOR / ADMIN（与 toUsers 至少填一个） */
    private String toRole;

    /** 指定用户（覆盖角色通知，可空） */
    private List<String> toUsers;

    /** 指定渠道 ID（默认继承规则渠道，可空） */
    private List<String> channelIds;
}
