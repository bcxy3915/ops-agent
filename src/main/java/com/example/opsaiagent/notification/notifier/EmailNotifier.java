package com.example.opsaiagent.notification.notifier;

import com.example.opsaiagent.notification.NotificationChannelType;
import com.example.opsaiagent.notification.NotificationContent;
import com.example.opsaiagent.notification.Notifier;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;
import java.util.Map;

/**
 * 邮件通知（T09）
 * 配置示例：{"recipients":"a@x.com,b@x.com","subjectPrefix":"[Ops] "}
 * SMTP 服务器参数由 spring.mail.* 提供；未配置 spring.mail.host 时该渠道不注册，
 * 避免空配置下渠道可用但发送必失败（也不触发 MailHealthIndicator 启动连接报错）。
 */
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
@RequiredArgsConstructor
public class EmailNotifier implements Notifier {

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.EMAIL;
    }

    @Override
    public void send(NotificationChannelConfigEntity channel, NotificationContent content) throws Exception {
        String recipients = readConfig(channel, "recipients");
        if (recipients == null || recipients.isBlank()) {
            throw new IllegalArgumentException("邮件渠道缺少 recipients 配置");
        }
        String subjectPrefix = readConfig(channel, "subjectPrefix");
        String subject = (subjectPrefix == null || subjectPrefix.isBlank())
                ? content.getTitle()
                : subjectPrefix + content.getTitle();

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setTo(recipients.split("\\s*,\\s*"));
        helper.setSubject(subject);
        helper.setText(content.getContent(), false);
        mailSender.send(message);
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
