package com.example.opsaiagent.inspection.dto;

/**
 * 相邻同长度时间窗的指标证据，不将趋势变化直接解释为故障根因。
 * @param serviceId 服务登记 ID
 * @param metric 白名单 Actuator 指标名
 * @param previous 前一窗口
 * @param current 当前窗口
 * @param averageDelta 两个完整窗口的均值之差，证据不完整时为 null
 */
public record MetricWindowReport(String serviceId, String metric, Window previous,
                                 Window current, Double averageDelta) {
    /**
     * 有界时间窗摘要，不向模型注入全量时间序列。
     * @param startMs 起点，包含
     * @param endMs 终点，不包含
     * @param status AVAILABLE、PARTIAL、UNAVAILABLE 或 TRUNCATED
     * @param sampleCount 查询到的行数，截断时只代表已查询数量
     * @param validCount 去除非法值与重复时间戳后的有效数量
     * @param latestSampleAt 最后一个有效点时间，无有效点时为 null
     * @param min 最小值，截断或无数据时为 null
     * @param max 最大值，截断或无数据时为 null
     * @param average 均值，截断或无数据时为 null
     */
    public record Window(long startMs, long endMs, String status, int sampleCount,
                         int validCount, Long latestSampleAt, Double min, Double max, Double average) {
    }
}
