package com.example.opsaiagent.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知渠道配置 Mapper
 */
@Mapper
public interface NotificationChannelMapper extends BaseMapper<NotificationChannelConfigEntity> {
}
