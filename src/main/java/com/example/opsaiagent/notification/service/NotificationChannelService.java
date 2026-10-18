package com.example.opsaiagent.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.notification.NotificationChannelType;
import com.example.opsaiagent.notification.NotifierRegistry;
import com.example.opsaiagent.notification.dto.NotificationChannelRequest;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import com.example.opsaiagent.notification.mapper.NotificationChannelMapper;
import com.example.opsaiagent.notification.mapper.NotificationRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知渠道配置管理（T11）：CRUD + 测试发送
 */
@Service
@RequiredArgsConstructor
public class NotificationChannelService {

    private final NotificationChannelMapper channelMapper;
    private final NotificationRecordMapper recordMapper;
    private final NotifierRegistry notifierRegistry;
    private final NotificationCenter notificationCenter;

    /**
     * 列表查询（可按类型 / 启用状态过滤）
     */
    public List<NotificationChannelConfigEntity> list(String type, Boolean enabled) {
        LambdaQueryWrapper<NotificationChannelConfigEntity> qw = new LambdaQueryWrapper<>();
        if (type != null && !type.isBlank()) {
            qw.eq(NotificationChannelConfigEntity::getType, type);
        }
        if (enabled != null) {
            qw.eq(NotificationChannelConfigEntity::getEnabled, enabled);
        }
        qw.orderByDesc(NotificationChannelConfigEntity::getId);
        return channelMapper.selectList(qw);
    }

    /**
     * 创建渠道
     */
    public NotificationChannelConfigEntity create(NotificationChannelRequest request) {
        validate(request);
        Long count = channelMapper.selectCount(new LambdaQueryWrapper<NotificationChannelConfigEntity>()
                .eq(NotificationChannelConfigEntity::getName, request.getName()));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.NOTIFICATION_CHANNEL_NAME_EXISTS, "渠道名称已存在: " + request.getName());
        }
        NotificationChannelConfigEntity entity = new NotificationChannelConfigEntity();
        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        entity.setConfig(request.getConfig());
        entity.setDescription(request.getDescription());
        channelMapper.insert(entity);
        return entity;
    }

    /**
     * 更新渠道
     */
    public NotificationChannelConfigEntity update(Long id, NotificationChannelRequest request) {
        NotificationChannelConfigEntity entity = getById(id);
        validate(request);
        NotificationChannelConfigEntity nameOwner = channelMapper.selectOne(
                new LambdaQueryWrapper<NotificationChannelConfigEntity>()
                        .eq(NotificationChannelConfigEntity::getName, request.getName()));
        if (nameOwner != null && !nameOwner.getId().equals(id)) {
            throw new BusinessException(ErrorCode.NOTIFICATION_CHANNEL_NAME_EXISTS, "渠道名称已存在: " + request.getName());
        }
        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        entity.setConfig(request.getConfig());
        entity.setDescription(request.getDescription());
        entity.setUpdatedAt(LocalDateTime.now());
        channelMapper.updateById(entity);
        return entity;
    }

    /**
     * 启用 / 停用
     */
    public NotificationChannelConfigEntity setEnabled(Long id, boolean enabled) {
        NotificationChannelConfigEntity entity = getById(id);
        entity.setEnabled(enabled);
        entity.setUpdatedAt(LocalDateTime.now());
        channelMapper.updateById(entity);
        return entity;
    }

    /**
     * 删除渠道（同时删除其发送记录，保留渠道名可复用）
     */
    public void delete(Long id) {
        getById(id);
        channelMapper.deleteById(id);
        recordMapper.delete(new LambdaQueryWrapper<NotificationRecordEntity>()
                .eq(NotificationRecordEntity::getChannelId, id));
    }

    /**
     * 测试发送：用固定内容发一条，返回发送记录
     */
    public NotificationRecordEntity testSend(Long id) {
        getById(id);
        return notificationCenter.sendTest(id);
    }

    public NotificationChannelConfigEntity getById(Long id) {
        NotificationChannelConfigEntity entity = channelMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOTIFICATION_CHANNEL_NOT_FOUND, "通知渠道不存在: " + id);
        }
        return entity;
    }

    private void validate(NotificationChannelRequest request) {
        try {
            NotificationChannelType type = NotificationChannelType.valueOf(request.getType());
            if (!notifierRegistry.supports(type)) {
                throw new BusinessException(ErrorCode.NOTIFICATION_CHANNEL_INVALID, "不支持的渠道类型: " + request.getType());
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.NOTIFICATION_CHANNEL_INVALID, "渠道类型不合法: " + request.getType());
        }
    }
}
