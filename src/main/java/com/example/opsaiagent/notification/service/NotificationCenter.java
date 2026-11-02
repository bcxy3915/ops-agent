package com.example.opsaiagent.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.example.opsaiagent.rbac.context.RbacContextSnapshot;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知中心（T10）
 * 统一入口：告警事件 → 渲染模板 → 遍历启用渠道发送 → 发送记录落库
 * 失败记录进入重试队列，由 {@link NotificationRetryTask} 定时重试。
 * T18 闭环：可附带 LLM 诊断结果一起通知。
 * P1 值班联动（规格 7）：发送前按事件租户解析值班收件人，动态邮箱优先于渠道配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCenter {

    private final NotificationChannelMapper channelMapper;
    private final NotificationRecordMapper recordMapper;
    private final NotifierRegistry notifierRegistry;
    private final NotificationTemplateService templateService;
    private final NotificationProperties properties;
    private final OncallMemberService oncallMemberService;
    private final OpsUserMapper opsUserMapper;
    private final AesGcmUtil aesGcmUtil;

    /**
     * 按告警事件通知所有启用渠道（告警触发时调用）
     */
    public void sendOnEvent(AlertEventEntity event) {
        sendOnEvent(event, null);
    }

    /**
     * 按告警事件通知所有启用渠道（T18 闭环：附带 LLM 诊断结果）
     * P1 值班联动：默认按 PRIMARY 解析收件人，空则回退 ADMIN，仍空则收件人为空
     * （EMAIL 渠道回退 config.recipients 兜底，Webhook 渠道不受影响）。
     */
    public void sendOnEvent(AlertEventEntity event, DiagnosisRecordEntity diagnosis) {
        List<String> recipients =
                oncallMemberService.resolveRecipients(event.getTenantId(), null, null, null);
        if (recipients.isEmpty()) {
            recipients = oncallMemberService.resolveRecipients(event.getTenantId(), null, "ADMIN", null);
        }
        sendOnEvent(event, diagnosis, resolveRecipientEmails(event.getTenantId(), recipients));
    }

    /**
     * 按告警事件通知所有启用渠道（升级场景：按级别解析收件人，L1→BACKUP、L2→ADMIN）
     *
     * @param levelNo 升级级别（告警升级服务调用）
     */
    public void sendOnEvent(AlertEventEntity event, DiagnosisRecordEntity diagnosis, Integer levelNo) {
        List<String> recipients =
                oncallMemberService.resolveRecipients(event.getTenantId(), null, null, levelNo);
        sendOnEvent(event, diagnosis, resolveRecipientEmails(event.getTenantId(), recipients));
    }

    /**
     * 按告警事件通知所有启用渠道，使用指定收件人邮箱（空列表表示未解析到，EMAIL 渠道回退配置兜底）
     */
    private void sendOnEvent(AlertEventEntity event, DiagnosisRecordEntity diagnosis,
                             List<String> recipientEmails) {
        LambdaQueryWrapper<NotificationChannelConfigEntity> channelQuery =
                new LambdaQueryWrapper<NotificationChannelConfigEntity>()
                        .eq(NotificationChannelConfigEntity::getEnabled, true);
        if (event.getTenantId() != null && !event.getTenantId().isBlank()) {
            channelQuery.eq(NotificationChannelConfigEntity::getTenantId, event.getTenantId());
        }
        List<NotificationChannelConfigEntity> channels = channelMapper.selectList(channelQuery);
        if (channels.isEmpty()) {
            log.info("无启用通知渠道，跳过告警通知 eventId={}", event.getId());
            return;
        }
        for (NotificationChannelConfigEntity channel : channels) {
            sendToChannel(channel, event, null, diagnosis, recipientEmails);
        }
    }

    /**
     * 按租户把值班人用户 ID 解析为邮箱列表（P1 值班联动）。
     * oncall_member.user_id 设计上存 ops_user.id（如 u-zhangwei），历史数据可能存 username，
     * 故按 id/username 双列匹配；【铁律】必须带 tenant_id 条件，防跨租户撞号越权读邮箱。
     * 邮箱为加密存储（email_enc），解密失败/为空的记录跳过。
     */
    private List<String> resolveRecipientEmails(String tenantId, List<String> userIds) {
        if (tenantId == null || tenantId.isBlank() || userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<OpsUserEntity> users = opsUserMapper.selectList(
                new LambdaQueryWrapper<OpsUserEntity>()
                        .eq(OpsUserEntity::getTenantId, tenantId)
                        .and(w -> w.in(OpsUserEntity::getId, userIds)
                                .or()
                                .in(OpsUserEntity::getUsername, userIds)));
        return users.stream()
                .map(u -> aesGcmUtil.decrypt(u.getEmailEnc()))
                .filter(e -> e != null && !e.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 测试发送：不依赖告警事件，用固定内容验证渠道连通性
     */
    public NotificationRecordEntity sendTest(Long channelId) {
        NotificationChannelConfigEntity channel = channelMapper.selectById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("通知渠道不存在: " + channelId);
        }
        AlertEventEntity dummy = new AlertEventEntity();
        dummy.setRuleName("测试通知");
        dummy.setService("-");
        dummy.setMetric("-");
        dummy.setStatus("TEST");
        dummy.setOperator("-");
        dummy.setThreshold(0d);
        dummy.setCurrentValue(0d);
        dummy.setDetail("这是一条渠道连通性测试消息");
        dummy.setFiredAt(LocalDateTime.now());
        return sendToChannel(channel, dummy, "TEST");
    }

    /**
     * 单渠道发送 + 记录落库
     */
    private NotificationRecordEntity sendToChannel(NotificationChannelConfigEntity channel,
                                                   AlertEventEntity event, String tag) {
        return sendToChannel(channel, event, tag, null, null);
    }

    /**
     * 单渠道发送 + 记录落库（T18：附带诊断结果时追加诊断摘要到内容）
     */
    private NotificationRecordEntity sendToChannel(NotificationChannelConfigEntity channel,
                                                   AlertEventEntity event, String tag,
                                                   DiagnosisRecordEntity diagnosis) {
        return sendToChannel(channel, event, tag, diagnosis, null);
    }

    /**
     * 单渠道发送 + 记录落库（P1 值班联动：recipientEmails 动态收件人，EMAIL 渠道优先使用）
     */
    private NotificationRecordEntity sendToChannel(NotificationChannelConfigEntity channel,
                                                   AlertEventEntity event, String tag,
                                                   DiagnosisRecordEntity diagnosis,
                                                   List<String> recipientEmails) {
        NotificationRecordEntity record = buildRecord(channel, event, tag);
        record.setStatus("PENDING");
        recordMapper.insert(record);

        try {
            NotificationChannelType type = NotificationChannelType.valueOf(channel.getType());
            Notifier notifier = notifierRegistry.get(type);
            NotificationContent content = templateService.render(event, null);
            if (recipientEmails != null && !recipientEmails.isEmpty()) {
                content = NotificationContent.builder()
                        .title(content.getTitle())
                        .content(content.getContent())
                        .dynamicRecipients(recipientEmails)
                        .build();
            }
            if (diagnosis != null && "SUCCESS".equals(diagnosis.getStatus())) {
                content = appendDiagnosis(content, diagnosis);
            }
            notifier.send(channel, content);

            record.setStatus("SUCCESS");
            record.setSentAt(LocalDateTime.now());
            record.setErrorMessage(null);
            record.setUpdatedAt(LocalDateTime.now());
            recordMapper.updateById(record);
            return record;
        } catch (Exception e) {
            log.warn("通知发送失败 channel={} eventId={}: {}", channel.getName(), event.getId(), e.getMessage());
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            record.setRetryCount(0);
            record.setNextRetryAt(LocalDateTime.now().plusSeconds(properties.getRetryDelaySec()));
            record.setUpdatedAt(LocalDateTime.now());
            recordMapper.updateById(record);
            return record;
        }
    }

    /**
     * 重试失败记录（由定时任务调用；测试可注入 now）
     */
    public void retryPending(LocalDateTime now) {
        List<NotificationRecordEntity> records = recordMapper.selectList(
                new LambdaQueryWrapper<NotificationRecordEntity>()
                        .eq(NotificationRecordEntity::getStatus, "FAILED")
                        .apply("retry_count < max_retry")
                        .le(NotificationRecordEntity::getNextRetryAt, now)
                        .orderByAsc(NotificationRecordEntity::getNextRetryAt)
                        .last("LIMIT 100"));
        for (NotificationRecordEntity record : records) {
            RbacContextSnapshot.forTenant(record.getTenantId()).run(() -> retryOne(record));
        }
    }

    private void retryOne(NotificationRecordEntity record) {
        NotificationChannelConfigEntity channel = channelMapper.selectById(record.getChannelId());
        if (channel == null) {
            // 渠道已删除：不再重试
            record.setStatus("FAILED");
            record.setUpdatedAt(LocalDateTime.now());
            recordMapper.updateById(record);
            return;
        }
        try {
            NotificationChannelType type = NotificationChannelType.valueOf(record.getChannelType());
            Notifier notifier = notifierRegistry.get(type);
            NotificationContent content = NotificationContent.builder()
                    .title(record.getTitle())
                    .content(record.getContent())
                    .build();
            notifier.send(channel, content);

            record.setStatus("SUCCESS");
            record.setSentAt(LocalDateTime.now());
            record.setErrorMessage(null);
            record.setUpdatedAt(LocalDateTime.now());
            recordMapper.updateById(record);
        } catch (Exception e) {
            record.setRetryCount(record.getRetryCount() == null ? 1 : record.getRetryCount() + 1);
            record.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            record.setNextRetryAt(LocalDateTime.now().plusSeconds(properties.getRetryDelaySec()));
            record.setUpdatedAt(LocalDateTime.now());
            recordMapper.updateById(record);
        }
    }

    /**
     * 把诊断结果摘要追加到通知内容（T18 闭环联动）
     */
    NotificationContent appendDiagnosis(NotificationContent content, DiagnosisRecordEntity diagnosis) {
        StringBuilder sb = new StringBuilder(content.getContent()).append("\n\n【智能诊断】\n");
        if (diagnosis.getRootCause() != null && !diagnosis.getRootCause().isBlank()) {
            sb.append("根因：").append(diagnosis.getRootCause()).append("\n");
        }
        if (diagnosis.getConfidence() != null) {
            sb.append("置信度：").append(diagnosis.getConfidence()).append("%\n");
        }
        if (diagnosis.getSuggestions() != null && !diagnosis.getSuggestions().isBlank()) {
            sb.append("建议：").append(diagnosis.getSuggestions()).append("\n");
        }
        if (diagnosis.getSummary() != null && !diagnosis.getSummary().isBlank()) {
            sb.append("摘要：").append(diagnosis.getSummary()).append("\n");
        }
        return NotificationContent.builder()
                .title(content.getTitle())
                .content(sb.toString())
                .dynamicRecipients(content.getDynamicRecipients())
                .build();
    }

    private NotificationRecordEntity buildRecord(NotificationChannelConfigEntity channel,
                                                 AlertEventEntity event, String tag) {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setAlertEventId(event.getId());
        record.setTenantId(event.getTenantId() != null && !event.getTenantId().isBlank()
                ? event.getTenantId() : channel.getTenantId());
        record.setDeptId(event.getDeptId() != null ? event.getDeptId() : channel.getDeptId());
        record.setRuleId(event.getRuleId());
        record.setRuleName(event.getRuleName());
        record.setService(event.getService());
        record.setMetric(event.getMetric());
        record.setChannelId(channel.getId());
        record.setChannelName(channel.getName());
        record.setChannelType(channel.getType());
        record.setTarget(resolveTarget(channel));
        record.setMaxRetry(properties.getMaxRetry());
        record.setRetryCount(0);
        return record;
    }

    private String resolveTarget(NotificationChannelConfigEntity channel) {
        String config = channel.getConfig();
        if (config == null || config.isBlank()) {
            return channel.getName();
        }
        // 简单提取 webhookUrl / recipients，失败则返回渠道名
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(config);
            if (node.has("webhookUrl")) {
                return node.get("webhookUrl").asText();
            }
            if (node.has("recipients")) {
                return node.get("recipients").asText();
            }
        } catch (Exception ignored) {
        }
        return channel.getName();
    }
}
