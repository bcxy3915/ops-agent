package com.example.opsaiagent.diagnosis.service;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 智能诊断提示词模板（T14）
 * 输入故障快照聚合数据，约束模型输出结构化诊断（根因/证据/置信度/建议）。
 * 实际 JSON Schema 约束由 ChatClient.entity(DiagnosisResult.class) 自动生成。
 */
@Component
public class DiagnosisPromptTemplate {

    /** 系统提示词：定义诊断专家角色与输出规范 */
    public static final String SYSTEM_PROMPT = """
            你是一名资深的 SRE 运维专家，负责对系统故障进行根因分析。
            你会收到一个故障快照（JSON），包含：告警事件信息、服务注册信息、服务健康状态、触发前指标窗口、最近审计日志。

            【任务】
            基于快照中的事实数据，对故障进行诊断，输出结构化结果：
            1. rootCause：根因判断，一句话说清故障最可能的原因；证据不足时明确说明"证据不足，无法确定根因"。
            2. evidence：列出 2-6 条支撑证据，每条必须是快照中实际出现的事实（指标值、状态、日志等），不要编造。
            3. confidence：0-100 的整数，表示对根因判断的置信度；证据越充分置信度越高。
            4. suggestions：按优先级给出 1-5 条可执行处置建议，必须是可落地动作（如重启服务、扩容、检查配置、回滚变更等）。
            5. summary：不超过 200 字的诊断摘要。

            【强制规则】
            - 只依据快照中的事实，禁止编造快照中不存在的数据。
            - 指标趋势描述必须与快照 metricWindow 中的数值一致。
            - 服务状态以 serviceStatus 为准；服务信息缺失时不要臆断。
            - 输出为合法 JSON，不要输出任何 JSON 之外的文字。
            """;

    /** 组装用户消息：把快照 JSON 原样传入 */
    public String renderUserMessage(String snapshotJson) {
        return new PromptTemplate("""
                请对以下故障快照进行诊断：

                ```json
                {snapshot}
                ```

                请输出结构化诊断结果。
                """).render(Map.of("snapshot", snapshotJson == null ? "{}" : snapshotJson));
    }
}
