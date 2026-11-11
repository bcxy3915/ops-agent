package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.config.InspectionLogProperties;
import com.example.opsaiagent.inspection.dto.LogEvidenceReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 受控日志取证入口，先验证服务权限与绑定，再读取固定文件。 */
@Service
@RequiredArgsConstructor
public class InspectionLogService {
    private final InspectionService inspection;
    private final InspectionLogProperties properties;
    private final InspectionLogReader reader;

    /**
     * 获取最近历史窗口内有界的 WARN/ERROR 日志。
     * @param serviceId 已登记服务 ID
     * @param sourceId 预登记源标识
     * @param startMs 起点，包含
     * @param endMs 终点，不包含，不能晚于现在
     * @param limit 返回条数，1～200
     * @return 带扫描完整性标记的脱敏证据
     */
    public LogEvidenceReport read(String serviceId, String sourceId, long startMs, long endMs, int limit) {
        if (startMs < 0 || startMs >= endMs || endMs > System.currentTimeMillis()
                || endMs - startMs > 3_600_000 || limit < 1 || limit > 200) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "日志窗口最长 1 小时，返回条数为 1～200");
        }
        inspection.requireVisibleService(serviceId);
        var source = properties.getSources().get(sourceId);
        if (source == null || !serviceId.equals(source.getServiceId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "日志源未绑定到当前服务");
        }
        return reader.read(serviceId, sourceId, source, startMs, endMs, limit);
    }
}
