package com.example.opsaiagent.inspection.dto;

/**
 * 业务接口只读探针证据；不返回地址、凭据、响应正文及匹配字段内容。
 * @param serviceId 已登记服务 ID
 * @param probeId 服务端探针标识
 * @param status HEALTHY、ABNORMAL 或 INSUFFICIENT_EVIDENCE
 * @param reason 确定性的结果原因码
 * @param httpStatus 已收到的 HTTP 状态码，连接失败时为 null
 * @param elapsedMs 观测耗时，单位毫秒
 * @param observedAt 本次观测完成时间戳
 */
public record BusinessProbeReport(String serviceId, String probeId, String status, String reason,
                                  Integer httpStatus, long elapsedMs, long observedAt) {
}
