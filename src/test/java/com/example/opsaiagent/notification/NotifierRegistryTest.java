package com.example.opsaiagent.notification;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.notification.notifier.FeishuWebhookNotifier;
import com.example.opsaiagent.notification.notifier.GenericWebhookNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 通知渠道适配器注册表测试（T09）
 */
@DisplayName("通知渠道注册表")
class NotifierRegistryTest {

    @Test
    @DisplayName("空注册表：supports 返回 false，get 抛业务异常")
    void emptyRegistry() {
        NotifierRegistry registry = new NotifierRegistry(List.of());
        assertThat(registry.supports(NotificationChannelType.FEISHU_WEBHOOK)).isFalse();
        assertThatThrownBy(() -> registry.get(NotificationChannelType.FEISHU_WEBHOOK))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("已注册类型：supports true，get 返回对应适配器")
    void registeredType() {
        NotifierRegistry registry = new NotifierRegistry(List.of(
                new FeishuWebhookNotifier(RestClient.builder(), new com.fasterxml.jackson.databind.ObjectMapper()),
                new GenericWebhookNotifier(RestClient.builder(), new com.fasterxml.jackson.databind.ObjectMapper())));
        assertThat(registry.supports(NotificationChannelType.FEISHU_WEBHOOK)).isTrue();
        assertThat(registry.get(NotificationChannelType.FEISHU_WEBHOOK))
                .isInstanceOf(FeishuWebhookNotifier.class);
    }

    @Test
    @DisplayName("同一类型只保留最后一个注册实现")
    void duplicateType_lastWins() {
        Notifier first = mock(Notifier.class);
        Notifier second = mock(Notifier.class);
        org.mockito.Mockito.when(first.type()).thenReturn(NotificationChannelType.EMAIL);
        org.mockito.Mockito.when(second.type()).thenReturn(NotificationChannelType.EMAIL);
        NotifierRegistry registry = new NotifierRegistry(List.of(first, second));
        assertThat(registry.get(NotificationChannelType.EMAIL)).isSameAs(second);
    }
}
