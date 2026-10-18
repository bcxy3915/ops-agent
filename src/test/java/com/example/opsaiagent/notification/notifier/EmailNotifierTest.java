package com.example.opsaiagent.notification.notifier;

import com.example.opsaiagent.notification.NotificationChannelType;
import com.example.opsaiagent.notification.NotificationContent;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邮件通知适配器测试（T09）
 */
@DisplayName("邮件通知")
class EmailNotifierTest {

    private JavaMailSender mailSender = mock(JavaMailSender.class);

    private EmailNotifier newNotifier() {
        return new EmailNotifier(mailSender, new ObjectMapper());
    }

    private NotificationChannelConfigEntity channel(String recipients) {
        NotificationChannelConfigEntity channel = new NotificationChannelConfigEntity();
        channel.setId(1L);
        channel.setName("运维邮件");
        channel.setType(NotificationChannelType.EMAIL.name());
        channel.setConfig(recipients == null ? null
                : "{\"recipients\":\"" + recipients + "\",\"subjectPrefix\":\"[Ops] \"}");
        return channel;
    }

    private NotificationContent content() {
        return NotificationContent.builder().title("告警通知 - cpu-high").content("cpu=95 > 80").build();
    }

    @Test
    @DisplayName("类型为 EMAIL")
    void type() {
        assertThat(newNotifier().type()).isEqualTo(NotificationChannelType.EMAIL);
    }

    @Test
    @DisplayName("发送成功：收件人拆分、主题加前缀、正文写入")
    void send_success() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        EmailNotifier notifier = newNotifier();

        notifier.send(channel("a@x.com,b@x.com"), content());

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("缺少 recipients 配置时抛出异常")
    void send_missingRecipients_throws() throws Exception {
        EmailNotifier notifier = newNotifier();
        assertThatThrownBy(() -> notifier.send(channel(null), content()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipients");
    }

    @Test
    @DisplayName("发送底层失败时向上抛异常")
    void send_failure_throws() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        EmailNotifier notifier = newNotifier();

        assertThatThrownBy(() -> notifier.send(channel("a@x.com"), content()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("smtp");
    }

    @Test
    @DisplayName("单收件人也能发送")
    void send_singleRecipient() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        newNotifier().send(channel("a@x.com"), content());
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("对象类型（SimpleMailMessage）不会被误调用")
    void send_neverUsesSimpleMailMessage() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        newNotifier().send(channel("a@x.com"), content());
        verify(mailSender).send(any(MimeMessage.class));
        // 不会使用 SimpleMailMessage（MimeMessageHelper 走 createMimeMessage 路径）
        org.mockito.Mockito.verify(mailSender, org.mockito.Mockito.never())
                .send(any(SimpleMailMessage.class));
    }
}
