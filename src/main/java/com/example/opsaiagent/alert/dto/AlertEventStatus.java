package com.example.opsaiagent.alert.dto;

/**
 * 告警事件状态
 */
public enum AlertEventStatus {
    /** 触发中 */
    FIRING,
    /** 已恢复 */
    RESOLVED,
    /** 静默（节流/静默期内被压制） */
    SILENCED,
    /** 人工确认 */
    ACKNOWLEDGED
}
