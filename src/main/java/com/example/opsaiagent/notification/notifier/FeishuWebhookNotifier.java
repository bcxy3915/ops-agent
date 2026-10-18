package com.example.opsaiagent.notification.notifier;

import com.example.opsaiagent.notification.NotificationChannelType;
import com.example.opsaiagent.notification.NotificationContent;
import com.example.opsaiagent.notification.Notifier;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 飞书自定义机器人 Webhook 通知（T09）
 * 配置示例：{"webhookUrl":"https://open.feishu.cn/open-apis/bot/v2/hook/xxx"}
 */
@Component
@RequiredArgsConstructor
public class FeishuWebhookNotifier implements Notifier {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.FEISHU_WEBHOOK;
    }

    @Override
    public void send(NotificationChannelConfigEntity channel, NotificationContent content) throws Exception {
        String webhookUrl = readConfig(channel, "webhookUrl");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("飞书渠道缺少 webhookUrl 配置");
        }
        Map<String, Object> body = Map.of(
                "msg_type", "text",
                "content", Map.of("text", content.getTitle() + "\n" + content.getContent()));
        restClientBuilder.build().post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity();
    }

    private String readConfig(NotificationChannelConfigEntity channel, String key) throws Exception {
        if (channel.getConfig() == null || channel.getConfig().isBlank()) {
            return null;
        }
        Map<?, ?> map = objectMapper.readValue(channel.getConfig(), Map.class);
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
