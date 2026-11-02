package com.example.opsaiagent.notification.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.crypto.util.AesGcmUtil;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.notification.NotificationChannelType;
import com.example.opsaiagent.notification.NotificationContent;
import com.example.opsaiagent.notification.NotificationProperties;
import com.example.opsaiagent.notification.Notifier;
import com.example.opsaiagent.notification.NotifierRegistry;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import com.example.opsaiagent.notification.mapper.NotificationChannelMapper;
import com.example.opsaiagent.notification.mapper.NotificationRecordMapper;
import com.example.opsaiagent.oncall.service.OncallMemberService;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知中心测试（T10）：统一入口、发送记录、失败重试、值班动态收件人（P1）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("通知中心")
class NotificationCenterTest {

    @Mock
    private NotificationChannelMapper channelMapper;
    @Mock
    private NotificationRecordMapper recordMapper;
    @Mock
    private NotifierRegistry notifierRegistry;
    @Mock
    private NotificationTemplateService templateService;
    @Mock
    private OncallMemberService oncallMemberService;
    @Mock
    private OpsUserMapper opsUserMapper;
    @Mock
    private AesGcmUtil aesGcmUtil;
    @Mock
    private Notifier notifier;

    @Spy
    private NotificationProperties properties = new NotificationProperties();

    @InjectMocks
    private NotificationCenter notificationCenter;

    @BeforeEach
    void setUp() {
        properties.setMaxRetry(2);
        properties.setRetryDelaySec(300);
    }

    private NotificationChannelConfigEntity channel(Long id, String name, String type) {
        NotificationChannelConfigEntity channel = new NotificationChannelConfigEntity();
        channel.setId(id);
        channel.setName(name);
        channel.setType(type);
        channel.setEnabled(true);
        channel.setConfig("{\"webhookUrl\":\"https://hook\"}");
        return channel;
    }

    private AlertEventEntity event(Long id) {
        AlertEventEntity event = new AlertEventEntity();
        event.setId(id);
        event.setRuleId(1L);
        event.setRuleName("cpu-high");
        event.setService("svc-a");
        event.setMetric("cpu");
        event.setStatus("FIRING");
        event.setFiredAt(LocalDateTime.now());
        return event;
    }

    private OpsUserEntity user(String id, String username, String emailEnc) {
        OpsUserEntity user = new OpsUserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setEmailEnc(emailEnc);
        return user;
    }

    // ==================== sendOnEvent ====================

    @Test
    @DisplayName("无启用渠道：跳过通知，不落库")
    void sendOnEvent_noChannels() {
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        notificationCenter.sendOnEvent(event(1L));
        verify(recordMapper, never()).insert(any(NotificationRecordEntity.class));
    }

    @Test
    @DisplayName("发送成功：记录 PENDING→SUCCESS 落库")
    void sendOnEvent_success() throws Exception {
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "飞书", "FEISHU_WEBHOOK")));
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                com.example.opsaiagent.notification.NotificationContent.builder()
                        .title("告警通知 - cpu-high").content("cpu=95").build());

        notificationCenter.sendOnEvent(event(1L));

        verify(recordMapper).insert(any(NotificationRecordEntity.class));
        verify(notifier).send(any(NotificationChannelConfigEntity.class), any());
        // 记录最终 SUCCESS
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationRecordEntity.class);
        verify(recordMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("发送失败：记录 FAILED，设置重试时间，不抛异常")
    void sendOnEvent_failure() throws Exception {
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "飞书", "FEISHU_WEBHOOK")));
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                com.example.opsaiagent.notification.NotificationContent.builder()
                        .title("t").content("c").build());
        doThrow(new RuntimeException("timeout")).when(notifier).send(any(), any());

        notificationCenter.sendOnEvent(event(1L));

        var captor = org.mockito.ArgumentCaptor.forClass(NotificationRecordEntity.class);
        verify(recordMapper).insert(captor.capture());
        NotificationRecordEntity record = captor.getValue();
        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getErrorMessage()).contains("timeout");
        assertThat(record.getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("多渠道：逐个发送")
    void sendOnEvent_multipleChannels() throws Exception {
        when(channelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(channel(1L, "飞书", "FEISHU_WEBHOOK"), channel(2L, "邮件", "EMAIL")));
        when(notifierRegistry.get(any(NotificationChannelType.class))).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                com.example.opsaiagent.notification.NotificationContent.builder()
                        .title("t").content("c").build());

        notificationCenter.sendOnEvent(event(1L));

        verify(notifier, org.mockito.Mockito.times(2)).send(any(), any());
        verify(recordMapper, org.mockito.Mockito.times(2)).insert(any(NotificationRecordEntity.class));
    }

    // ==================== sendOnEvent + 诊断联动（T18） ====================

    @Test
    @DisplayName("闭环通知：附带诊断成功时内容追加诊断摘要")
    void sendOnEvent_withDiagnosis_appendsContent() throws Exception {
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "飞书", "FEISHU_WEBHOOK")));
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                NotificationContent.builder().title("告警通知 - cpu-high").content("cpu=95").build());

        DiagnosisRecordEntity diag = new DiagnosisRecordEntity();
        diag.setStatus("SUCCESS");
        diag.setRootCause("CPU 打满");
        diag.setConfidence(85);
        diag.setSuggestions("[\"扩容\",\"回滚\"]");
        diag.setSummary("CPU 持续打满导致服务不可用");

        notificationCenter.sendOnEvent(event(1L), diag);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notifier).send(any(NotificationChannelConfigEntity.class), captor.capture());
        assertThat(captor.getValue().getContent())
                .contains("【智能诊断】")
                .contains("根因：CPU 打满")
                .contains("置信度：85%")
                .contains("扩容")
                .contains("摘要：CPU 持续打满");
    }

    @Test
    @DisplayName("闭环通知：诊断失败（FAILED）时不追加诊断摘要")
    void sendOnEvent_withFailedDiagnosis_noAppend() throws Exception {
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "飞书", "FEISHU_WEBHOOK")));
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                NotificationContent.builder().title("t").content("c").build());

        DiagnosisRecordEntity diag = new DiagnosisRecordEntity();
        diag.setStatus("FAILED");
        diag.setErrorMessage("LLM timeout");

        notificationCenter.sendOnEvent(event(1L), diag);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notifier).send(any(NotificationChannelConfigEntity.class), captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("c");
        assertThat(captor.getValue().getContent()).doesNotContain("【智能诊断】");
    }

    @Test
    @DisplayName("appendDiagnosis：诊断字段为空时只输出已有字段")
    void appendDiagnosis_partialFields() {
        DiagnosisRecordEntity diag = new DiagnosisRecordEntity();
        diag.setStatus("SUCCESS");
        diag.setRootCause("根因A");

        NotificationContent result = notificationCenter.appendDiagnosis(
                NotificationContent.builder().title("t").content("base").build(), diag);

        assertThat(result.getContent()).contains("根因：根因A");
        assertThat(result.getContent()).doesNotContain("置信度");
    }

    // ==================== sendOnEvent + 值班动态收件人（P1 规格 7） ====================

    @Test
    @DisplayName("值班联动：PRIMARY 有值班人时解出邮箱并传给 Notifier")
    void sendOnEvent_primaryRecipients_resolvedToEmails() throws Exception {
        AlertEventEntity e = event(1L);
        e.setTenantId("t-xinghai");
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "邮件", "EMAIL")));
        when(notifierRegistry.get(NotificationChannelType.EMAIL)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any()))
                .thenReturn(NotificationContent.builder().title("t").content("c").build());
        when(oncallMemberService.resolveRecipients(eq("t-xinghai"), isNull(), isNull(), isNull()))
                .thenReturn(List.of("u-zhangwei"));
        when(opsUserMapper.selectList(any())).thenReturn(
                List.of(user("u-zhangwei", "zhangwei", "enc:mail")));
        when(aesGcmUtil.decrypt("enc:mail")).thenReturn("zhangwei@xinghai.com");

        notificationCenter.sendOnEvent(e, null);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notifier).send(any(NotificationChannelConfigEntity.class), captor.capture());
        assertThat(captor.getValue().getDynamicRecipients())
                .containsExactly("zhangwei@xinghai.com");
    }

    @Test
    @DisplayName("值班联动：PRIMARY 为空时回退 ADMIN 再解析一次")
    void sendOnEvent_emptyPrimary_fallsBackToAdmin() throws Exception {
        AlertEventEntity e = event(1L);
        e.setTenantId("t-xinghai");
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "邮件", "EMAIL")));
        when(notifierRegistry.get(NotificationChannelType.EMAIL)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any()))
                .thenReturn(NotificationContent.builder().title("t").content("c").build());
        when(oncallMemberService.resolveRecipients(eq("t-xinghai"), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
        when(oncallMemberService.resolveRecipients(eq("t-xinghai"), isNull(), eq("ADMIN"), isNull()))
                .thenReturn(List.of("u-admin"));
        when(opsUserMapper.selectList(any())).thenReturn(List.of(user("u-admin", "admin", "enc:admin")));
        when(aesGcmUtil.decrypt("enc:admin")).thenReturn("admin@ops.com");

        notificationCenter.sendOnEvent(e, null);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notifier).send(any(NotificationChannelConfigEntity.class), captor.capture());
        assertThat(captor.getValue().getDynamicRecipients()).containsExactly("admin@ops.com");
    }

    @Test
    @DisplayName("值班联动：PRIMARY/ADMIN 均为空时不解邮箱，内容不带动态收件人")
    void sendOnEvent_noRecipients_noDynamicEmails() throws Exception {
        AlertEventEntity e = event(1L);
        e.setTenantId("t-xinghai");
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "邮件", "EMAIL")));
        when(notifierRegistry.get(NotificationChannelType.EMAIL)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any()))
                .thenReturn(NotificationContent.builder().title("t").content("c").build());
        when(oncallMemberService.resolveRecipients(anyString(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
        when(oncallMemberService.resolveRecipients(anyString(), isNull(), eq("ADMIN"), isNull()))
                .thenReturn(List.of());

        notificationCenter.sendOnEvent(e, null);

        verify(opsUserMapper, never()).selectList(any());
        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notifier).send(any(NotificationChannelConfigEntity.class), captor.capture());
        assertThat(captor.getValue().getDynamicRecipients()).isNullOrEmpty();
    }

    @Test
    @DisplayName("升级通知：按 levelNo 解析收件人（不查 PRIMARY 默认路径）")
    void sendOnEvent_withLevelNo_resolvesByLevel() throws Exception {
        AlertEventEntity e = event(1L);
        e.setTenantId("t-xinghai");
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "邮件", "EMAIL")));
        when(notifierRegistry.get(NotificationChannelType.EMAIL)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any()))
                .thenReturn(NotificationContent.builder().title("t").content("c").build());
        when(oncallMemberService.resolveRecipients(eq("t-xinghai"), isNull(), isNull(), eq(2)))
                .thenReturn(List.of("u-admin"));
        when(opsUserMapper.selectList(any())).thenReturn(List.of(user("u-admin", "admin", "enc:admin")));
        when(aesGcmUtil.decrypt("enc:admin")).thenReturn("admin@ops.com");

        notificationCenter.sendOnEvent(e, null, 2);

        // 只按级别解析一次，不再走 PRIMARY 默认路径
        verify(oncallMemberService).resolveRecipients(eq("t-xinghai"), isNull(), isNull(), eq(2));
        verify(oncallMemberService, never())
                .resolveRecipients(anyString(), isNull(), isNull(), isNull());
        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notifier).send(any(NotificationChannelConfigEntity.class), captor.capture());
        assertThat(captor.getValue().getDynamicRecipients()).containsExactly("admin@ops.com");
    }

    // ==================== sendTest ====================

    @Test
    @DisplayName("测试发送：渠道不存在抛异常")
    void sendTest_channelNotFound() {
        when(channelMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> notificationCenter.sendTest(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("测试发送：渠道存在则发送并返回记录")
    void sendTest_success() throws Exception {
        NotificationChannelConfigEntity ch = channel(1L, "飞书", "FEISHU_WEBHOOK");
        when(channelMapper.selectById(1L)).thenReturn(ch);
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                com.example.opsaiagent.notification.NotificationContent.builder()
                        .title("测试通知 - 测试通知").content("测试").build());

        NotificationRecordEntity record = notificationCenter.sendTest(1L);

        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getAlertEventId()).isNull();
        verify(notifier).send(any(), any());
    }

    // ==================== retryPending ====================

    @Test
    @DisplayName("重试：无到期记录不处理")
    void retry_none() {
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        notificationCenter.retryPending(LocalDateTime.now());
        verify(channelMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("重试：发送成功记录转 SUCCESS")
    void retry_success() throws Exception {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setId(1L);
        record.setChannelId(1L);
        record.setChannelType("FEISHU_WEBHOOK");
        record.setStatus("FAILED");
        record.setRetryCount(0);
        record.setTitle("t");
        record.setContent("c");
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        NotificationChannelConfigEntity ch = channel(1L, "飞书", "FEISHU_WEBHOOK");
        when(channelMapper.selectById(1L)).thenReturn(ch);
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);

        notificationCenter.retryPending(LocalDateTime.now());

        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getSentAt()).isNotNull();
        verify(recordMapper).updateById(record);
    }

    @Test
    @DisplayName("重试：再次失败则 retryCount 递增并顺延重试时间")
    void retry_failureIncrements() throws Exception {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setId(1L);
        record.setChannelId(1L);
        record.setChannelType("FEISHU_WEBHOOK");
        record.setStatus("FAILED");
        record.setRetryCount(1);
        record.setTitle("t");
        record.setContent("c");
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, "飞书", "FEISHU_WEBHOOK"));
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        doThrow(new RuntimeException("still down")).when(notifier).send(any(), any());

        notificationCenter.retryPending(LocalDateTime.now());

        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getRetryCount()).isEqualTo(2);
        assertThat(record.getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("重试：渠道已删除则标记失败不再重试")
    void retry_channelDeleted() throws Exception {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setId(1L);
        record.setChannelId(99L);
        record.setChannelType("FEISHU_WEBHOOK");
        record.setStatus("FAILED");
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        when(channelMapper.selectById(99L)).thenReturn(null);

        notificationCenter.retryPending(LocalDateTime.now());

        assertThat(record.getStatus()).isEqualTo("FAILED");
        verify(notifier, never()).send(any(), any());
        verify(recordMapper).updateById(record);
    }

    @Test
    @DisplayName("渠道类型无适配器：重试时失败并保留")
    void retry_unsupportedType() {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setId(1L);
        record.setChannelId(1L);
        record.setChannelType("UNKNOWN_TYPE");
        record.setStatus("FAILED");
        record.setRetryCount(0);
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, "未知", "UNKNOWN_TYPE"));

        notificationCenter.retryPending(LocalDateTime.now());

        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getRetryCount()).isEqualTo(1);
    }
}
