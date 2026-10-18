package com.example.opsaiagent.notification.service;

import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.notification.NotificationContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知模板渲染测试（T10）
 */
@DisplayName("通知模板渲染")
class NotificationTemplateServiceTest {

    private final NotificationTemplateService templateService = new NotificationTemplateService();

    private AlertEventEntity event() {
        AlertEventEntity event = new AlertEventEntity();
        event.setRuleId(1L);
        event.setRuleName("cpu-high");
        event.setService("svc-a");
        event.setMetric("cpu");
        event.setStatus("FIRING");
        event.setOperator("gt");
        event.setThreshold(80.0);
        event.setCurrentValue(95.5);
        event.setDetail("cpu 超阈值");
        event.setFiredAt(LocalDateTime.of(2026, 10, 18, 15, 0));
        return event;
    }

    @Test
    @DisplayName("默认模板：所有占位符被替换")
    void render_defaultTemplate() {
        NotificationContent content = templateService.render(event(), null);

        assertThat(content.getTitle()).isEqualTo("告警通知 - cpu-high");
        assertThat(content.getContent())
                .contains("【告警 FIRING】cpu-high")
                .contains("服务：svc-a")
                .contains("指标：cpu")
                .contains("当前值：95.5 gt 阈值：80.0")
                .contains("触发时间：2026-10-18T15:00")
                .contains("详情：cpu 超阈值");
    }

    @Test
    @DisplayName("自定义模板：按模板渲染")
    void render_customTemplate() {
        NotificationContent content = templateService.render(event(), "【{{status}}】{{ruleName}} 服务={{service}} 值={{currentValue}}");

        assertThat(content.getContent()).isEqualTo("【FIRING】cpu-high 服务=svc-a 值=95.5");
    }

    @Test
    @DisplayName("空白模板回退默认模板")
    void render_blankTemplateFallsBack() {
        NotificationContent content = templateService.render(event(), "   ");
        assertThat(content.getContent()).contains("【告警 FIRING】cpu-high");
    }

    @Test
    @DisplayName("null 字段渲染为空字符串，不抛异常")
    void render_nullFields() {
        AlertEventEntity event = new AlertEventEntity();
        NotificationContent content = templateService.render(event, "{{ruleName}}|{{service}}|{{detail}}|{{firedAt}}");

        assertThat(content.getContent()).isEqualTo("|||");
    }

    @Test
    @DisplayName("未知占位符原样保留")
    void render_unknownPlaceholderKept() {
        NotificationContent content = templateService.render(event(), "值={{currentValue}} 未知={{unknown}}");
        assertThat(content.getContent()).contains("未知={{unknown}}");
    }
}
