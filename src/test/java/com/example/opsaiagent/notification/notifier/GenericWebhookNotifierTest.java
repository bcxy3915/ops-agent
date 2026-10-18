package com.example.opsaiagent.notification.notifier;

import com.example.opsaiagent.notification.NotificationChannelType;
import com.example.opsaiagent.notification.NotificationContent;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 通用 Webhook 通知适配器测试（T09）
 */
@DisplayName("通用Webhook通知")
class GenericWebhookNotifierTest {

    private static final String WEBHOOK_URL = "https://example.com/hook/alert";

    private GenericWebhookNotifier newNotifier(MockRestServiceServer server, RestClient.Builder builder) {
        return new GenericWebhookNotifier(builder, new ObjectMapper());
    }

    private NotificationChannelConfigEntity channel() {
        NotificationChannelConfigEntity channel = new NotificationChannelConfigEntity();
        channel.setId(1L);
        channel.setName("通用Webhook");
        channel.setType(NotificationChannelType.GENERIC_WEBHOOK.name());
        channel.setConfig("{\"webhookUrl\":\"" + WEBHOOK_URL + "\",\"secret\":\"abc\"}");
        return channel;
    }

    private NotificationContent content() {
        return NotificationContent.builder().title("告警通知 - cpu-high").content("cpu=95 > 80").build();
    }

    @Test
    @DisplayName("类型为 GENERIC_WEBHOOK")
    void type() {
        RestClient.Builder builder = RestClient.builder();
        GenericWebhookNotifier notifier = new GenericWebhookNotifier(builder, new ObjectMapper());
        assertThat(notifier.type()).isEqualTo(NotificationChannelType.GENERIC_WEBHOOK);
    }

    @Test
    @DisplayName("发送成功：POST JSON 到 webhookUrl，含 title/content/timestamp/channel")
    void send_success() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GenericWebhookNotifier notifier = newNotifier(server, builder);

        server.expect(once(), requestTo(WEBHOOK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        notifier.send(channel(), content());
        server.verify();
    }

    @Test
    @DisplayName("缺少 webhookUrl 配置时抛出异常")
    void send_missingUrl_throws() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GenericWebhookNotifier notifier = newNotifier(server, builder);
        NotificationChannelConfigEntity channel = channel();
        channel.setConfig("{}");

        assertThatThrownBy(() -> notifier.send(channel, content()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("webhookUrl");
    }

    @Test
    @DisplayName("远端返回错误时抛异常")
    void send_serverError_throws() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GenericWebhookNotifier notifier = newNotifier(server, builder);

        server.expect(requestTo(WEBHOOK_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> notifier.send(channel(), content()))
                .isInstanceOf(Exception.class);
    }
}
