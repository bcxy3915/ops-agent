package com.example.opsaiagent.automation.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 有界自主诊断结果，所有依据都必须来自本轮实际工具调用。
 * @param status COMPLETED 或 NEEDS_MANUAL
 * @param reason 停止原因码
 * @param summary 已确认事实和待验证判断
 * @param limitations 证据局限
 * @param evidenceIds 最终结论引用的实际证据 ID
 * @param steps 本轮动态选择的步骤
 * @param evidence 真实观测与明确失败的证据
 */
public record AutonomousDiagnosisReport(String status, String reason, String summary, String limitations,
                                         List<String> evidenceIds, List<Step> steps, List<Evidence> evidence) {
    /**
     * 单项取证步骤，不保存模型内部思维链。
     * @param number 顺序号
     * @param action 工具名
     * @param argument 白名单参数
     * @param evidenceId 结果证据 ID
     * @param status SUCCEEDED 或 FAILED
     */
    public record Step(int number, String action, String argument, String evidenceId, String status) {
    }

    /**
     * 观测快照，内容只来自受控、已脱敏的工具输出。
     * @param id 证据 ID
     * @param type 工具来源
     * @param collectedAt 实际采集完成时间
     * @param data 有界结构化结果
     */
    public record Evidence(String id, String type, long collectedAt, JsonNode data) {
    }
}
