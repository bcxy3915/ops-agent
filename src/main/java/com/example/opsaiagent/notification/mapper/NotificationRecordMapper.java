package com.example.opsaiagent.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知发送记录 Mapper
 */
@Mapper
public interface NotificationRecordMapper extends BaseMapper<NotificationRecordEntity> {
}
