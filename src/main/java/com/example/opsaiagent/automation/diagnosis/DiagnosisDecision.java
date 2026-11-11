package com.example.opsaiagent.automation.diagnosis;

/**
 * 模型的单步取证决策，服务端校验后才执行；不接收命令或目标地址。
 * @param action METRIC_WINDOW、LOGS、BUSINESS_PROBE 或 FINISH
 * @param argument 白名单指标或已登记源标识
 * @param summary 简短可验证的决策说明或最终结论，不要求内部思维链
 * @param evidenceIds 最终结论引用的实际证据 ID，逗号分隔
 * @param limitations 未证实假设与证据缺口
 */
public record DiagnosisDecision(String action, String argument, String summary,
                                 String evidenceIds, String limitations) {
}
