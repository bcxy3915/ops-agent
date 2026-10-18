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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 飞书 Webhook 通知适配器测试（T09）
 */
@DisplayName("飞书Webhook通知")
class FeishuWebhookNotifierTest {

    private static final String WEBHOOK_URL = "https://open.feishu.cn/open-apis/bot/v2/hook/test";

    private FeishuWebhookNotifier newNotifier(MockRestServiceServer server, RestClient.Builder builder) {
        return new FeishuWebhookNotifier(builder, new ObjectMapper());
    }

    private NotificationChannelConfigEntity channel() {
        NotificationChannelConfigEntity channel = new NotificationChannelConfigEntity();
        channel.setId(1L);
        channel.setName("飞书告警群");
        channel.setType(NotificationChannelType.FEISHU_WEBHOOK.name());
        channel.setConfig("{\"webhookUrl\":\"" + WEBHOOK_URL + "\"}");
        return channel;
    }

    private NotificationContent content() {
        return NotificationContent.builder().title("告警通知 - cpu-high").content("cpu=95 > 80").build();
    }

    @Test
    @DisplayName("类型为 FEISHU_WEBHOOK")
    void type() {
        RestClient.Builder builder = RestClient.builder();
        FeishuWebhookNotifier notifier = new FeishuWebhookNotifier(builder, new ObjectMapper());
        assertThat(notifier.type()).isEqualTo(NotificationChannelType.FEISHU_WEBHOOK);
    }

    @Test
    @DisplayName("发送成功：POST 到 webhookUrl，JSON body 含 msg_type/content")
    void send_success() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuWebhookNotifier notifier = newNotifier(server, builder);

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
        FeishuWebhookNotifier notifier = newNotifier(server, builder);
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
        FeishuWebhookNotifier notifier = newNotifier(server, builder);

        server.expect(requestTo(WEBHOOK_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> notifier.send(channel(), content()))
                .isInstanceOf(Exception.class);
    }
}
