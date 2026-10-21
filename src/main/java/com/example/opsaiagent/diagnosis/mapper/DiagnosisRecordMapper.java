package com.example.opsaiagent.diagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 诊断记录 Mapper（T15）
 */
@Mapper
public interface DiagnosisRecordMapper extends BaseMapper<DiagnosisRecordEntity> {

    /**
     * 已成功诊断的事件数（P1 告警汇总 diagnosedCount）
     * 按 alert_event_id 去重，仅统计 status=SUCCESS 且有关联事件的成功诊断。
     */
    @Select("SELECT COUNT(DISTINCT alert_event_id) FROM diagnosis_record " +
            "WHERE status = 'SUCCESS' AND alert_event_id IS NOT NULL")
    long countDistinctSuccessEvents();

    /**
     * 成功诊断平均耗时（P1 告警汇总 avgDiagnoseMs，毫秒）
     * 无耗时数据时返回 null。
     */
    @Select("SELECT AVG(duration_ms) FROM diagnosis_record " +
            "WHERE status = 'SUCCESS' AND duration_ms IS NOT NULL")
    Long avgDiagnoseMs();
}
