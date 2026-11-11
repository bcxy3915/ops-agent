package com.example.opsaiagent.inspection.dto;

/**
 * 单项观测证据，不包含目标地址、认证信息或未经脱敏的原始响应。
 *
 * @param name 证据名称
 * @param status 采集状态：AVAILABLE、UNAVAILABLE 或 INVALID
 * @param value 已验证的状态字符串或数值；采集失败时为 null
 * @param observedAt 本项采集完成时间戳
 */
public record Observation(String name, String status, Object value, long observedAt) {
}
