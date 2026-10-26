package com.example.opsaiagent.notification.notifier;

import com.example.opsaiagent.notification.NotificationChannelType;
import com.example.opsaiagent.notification.NotificationContent;
import com.example.opsaiagent.notification.Notifier;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.example.opsaiagent.resilience.UrlSafetyValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用 Webhook 通知（T09）
 * 配置示例：{"webhookUrl":"https://example.com/hook","secret":"可选签名"}
 * POST JSON：{"title":..,"content":..,"timestamp":..,"channel":..}
 */
@Component
@RequiredArgsConstructor
public class GenericWebhookNotifier implements Notifier {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.GENERIC_WEBHOOK;
    }

    @Override
    public void send(NotificationChannelConfigEntity channel, NotificationContent content) throws Exception {
        String webhookUrl = readConfig(channel, "webhookUrl");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("通用 Webhook 渠道缺少 webhookUrl 配置");
        }
        UrlSafetyValidator.assertSafeWebhookUrl(webhookUrl);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", content.getTitle());
        body.put("content", content.getContent());
        body.put("timestamp", Instant.now().toString());
        body.put("channel", channel.getName());
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
