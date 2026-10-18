package com.example.opsaiagent.notification.service;

import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.notification.NotificationContent;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 通知模板渲染（T10）
 * 支持占位符：{{ruleName}} {{service}} {{metric}} {{status}} {{operator}}
 *            {{threshold}} {{currentValue}} {{detail}} {{firedAt}}
 * 未知占位符原样保留，空值渲染为空字符串。
 */
@Service
public class NotificationTemplateService {

    private static final String DEFAULT_TEMPLATE = """
            【告警 {{status}}】{{ruleName}}
            服务：{{service}}
            指标：{{metric}}
            当前值：{{currentValue}} {{operator}} 阈值：{{threshold}}
            触发时间：{{firedAt}}
            详情：{{detail}}
            """;

    /**
     * 用默认模板渲染告警事件通知内容
     */
    public NotificationContent render(AlertEventEntity event, String template) {
        String effective = (template == null || template.isBlank()) ? DEFAULT_TEMPLATE : template;
        Map<String, String> values = Map.of(
                "ruleName", nvl(event.getRuleName()),
                "service", nvl(event.getService()),
                "metric", nvl(event.getMetric()),
                "status", nvl(event.getStatus()),
                "operator", nvl(event.getOperator()),
                "threshold", event.getThreshold() == null ? "" : String.valueOf(event.getThreshold()),
                "currentValue", event.getCurrentValue() == null ? "" : String.valueOf(event.getCurrentValue()),
                "detail", nvl(event.getDetail()),
                "firedAt", event.getFiredAt() == null ? "" : event.getFiredAt().toString()
        );
        String rendered = effective;
        for (Map.Entry<String, String> e : values.entrySet()) {
            rendered = rendered.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        String title = "告警通知 - " + nvl(event.getRuleName());
        return NotificationContent.builder().title(title).content(rendered).build();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}
