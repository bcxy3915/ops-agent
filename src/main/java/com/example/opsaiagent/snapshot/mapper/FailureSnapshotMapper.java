package com.example.opsaiagent.snapshot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 故障快照 Mapper
 */
@Mapper
public interface FailureSnapshotMapper extends BaseMapper<FailureSnapshotEntity> {
}
