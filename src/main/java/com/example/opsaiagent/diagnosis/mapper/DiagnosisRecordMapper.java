package com.example.opsaiagent.diagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 诊断记录 Mapper（T15）
 */
@Mapper
public interface DiagnosisRecordMapper extends BaseMapper<DiagnosisRecordEntity> {
}
