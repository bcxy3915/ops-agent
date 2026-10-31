package com.example.opsaiagent.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.alert.entity.AlertReadEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户告警已读 Mapper
 */
@Mapper
public interface AlertReadMapper extends BaseMapper<AlertReadEntity> {

    /**
     * 幂等插入已读记录（已存在则忽略，PostgreSQL ON CONFLICT 匹配三列主键）
     */
    @Insert("""
            INSERT INTO user_alert_read(tenant_id, user_id, alert_event_id, read_at)
            VALUES (#{tenantId}, #{userId}, #{alertEventId}, NOW())
            ON CONFLICT (tenant_id, user_id, alert_event_id) DO NOTHING
            """)
    int insertIgnore(@Param("tenantId") String tenantId,
                     @Param("userId") String userId,
                     @Param("alertEventId") Long alertEventId);
}
