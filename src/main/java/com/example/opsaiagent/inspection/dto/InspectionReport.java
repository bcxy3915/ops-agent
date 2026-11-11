package com.example.opsaiagent.inspection.dto;

import java.util.List;

/**
 * 一次只读巡检报告；发现异常不等于已确定根因。
 *
 * @param serviceId 已登记服务标识
 * @param serviceName 服务名称
 * @param startedAt 开始采集时间戳
 * @param completedAt 完成时间戳
 * @param status HEALTHY、ABNORMAL 或 INSUFFICIENT_EVIDENCE
 * @param findings 规则发现的异常事实
 * @param observations 本轮实时证据
 */
public record InspectionReport(String serviceId, String serviceName, long startedAt,
                               long completedAt, String status, List<String> findings,
                               List<Observation> observations) {
}
