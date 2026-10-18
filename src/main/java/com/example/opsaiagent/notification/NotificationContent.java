package com.example.opsaiagent.notification;

import lombok.Builder;
import lombok.Data;

/**
 * 通知内容（模板渲染后的结果，T10）
 */
@Data
@Builder
public class NotificationContent {

    /** 标题 */
    private String title;

    /** 正文 */
    private String content;
}
