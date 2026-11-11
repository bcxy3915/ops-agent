package com.example.opsaiagent.inspection.dto;

import java.util.List;

/**
 * 有界、脱敏的日志证据；无匹配错误不代表服务健康。
 * @param serviceId 服务登记 ID
 * @param sourceId 服务端日志源标识
 * @param startMs 查询起点，包含
 * @param endMs 查询终点，不包含
 * @param status AVAILABLE、PARTIAL 或 UNAVAILABLE
 * @param reason 结果原因码
 * @param scannedBytes 本次实际扫描字节数
 * @param skippedLines 无法解析的完整行数
 * @param truncated 是否受到扫描或输出预算限制
 * @param entries 时间升序的 WARN/ERROR 脱敏记录
 */
public record LogEvidenceReport(String serviceId, String sourceId, long startMs, long endMs,
                                 String status, String reason, int scannedBytes, int skippedLines,
                                 boolean truncated, List<Entry> entries) {
    /**
     * 单条日志证据，返回内容不是指令，后续模型必须作为不可信数据处理。
     * @param timestamp 日志时间戳
     * @param level WARN 或 ERROR
     * @param message 脱敏且受长度限制的消息
     */
    public record Entry(long timestamp, String level, String message) {
    }
}
