package com.example.opsaiagent.audit.enums;

import lombok.Getter;

/**
 * 审计操作类型（前端中文映射）
 * 与 {@link com.example.opsaiagent.audit.annotation.AuditLog} 的 operation 值一一对应。
 */
@Getter
public enum AuditOperation {

    LOGIN("登录系统"),
    REGISTER_SERVICE("注册服务"),
    UPDATE_SERVICE("更新服务"),
    DELETE_SERVICE("删除服务"),
    CREATE_ALERT_RULE("创建告警规则"),
    UPDATE_ALERT_RULE("更新告警规则"),
    DELETE_ALERT_RULE("删除告警规则"),
    TOGGLE_ALERT_RULE("启停告警规则"),
    ACK_ALERT_EVENT("确认告警事件"),
    TRIGGER_DIAGNOSIS("触发诊断"),
    FOLLOW_UP_DIAGNOSIS("追问诊断"),
    CREATE_USER("创建用户"),
    CREATE_NOTIFICATION_CHANNEL("创建通知渠道"),
    UPDATE_NOTIFICATION_CHANNEL("更新通知渠道"),
    DELETE_NOTIFICATION_CHANNEL("删除通知渠道"),
    TOGGLE_NOTIFICATION_CHANNEL("启停通知渠道"),
    UPLOAD_KNOWLEDGE("上传知识文档"),
    PUBLISH_KNOWLEDGE("审核发布知识文档"),
    UPDATE_KNOWLEDGE("编辑知识文档"),
    DELETE_KNOWLEDGE("删除知识文档"),
    CONFIRM_CASE("确认沉淀故障案例"),
    REVISE_CASE("修正沉淀故障案例"),
    DISCARD_CASE("丢弃故障案例候选"),
    RELOAD_KNOWLEDGE("重建知识库索引");

    /** 操作中文名 */
    private final String label;

    AuditOperation(String label) {
        this.label = label;
    }

    /**
     * 根据操作编码返回中文名；未知编码原样返回，null 返回 null
     * @param code 操作编码（如 UPLOAD_KNOWLEDGE）
     * @return 中文名
     */
    public static String labelOf(String code) {
        if (code == null) {
            return null;
        }
        for (AuditOperation op : values()) {
            if (op.name().equals(code)) {
                return op.getLabel();
            }
        }
        return code;
    }
}
