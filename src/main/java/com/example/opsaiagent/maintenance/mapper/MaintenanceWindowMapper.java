package com.example.opsaiagent.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.maintenance.entity.MaintenanceWindowEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 维护窗口 Mapper（P1 规格 5）
 */
@Mapper
public interface MaintenanceWindowMapper extends BaseMapper<MaintenanceWindowEntity> {
}
