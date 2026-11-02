package com.example.opsaiagent.notification;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 通知内容（模板渲染后的结果，T10）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationContent {

    /** 标题 */
    private String title;

    /** 正文 */
    private String content;

    /**
     * 动态解析的收件人邮箱（值班表联动，P1 规格 7）
     * 仅 EMAIL 渠道消费：非空时优先使用；为空时渠道回退自身 config.recipients。
     * Webhook 类渠道忽略该字段。
     */
    private List<String> dynamicRecipients;
}
