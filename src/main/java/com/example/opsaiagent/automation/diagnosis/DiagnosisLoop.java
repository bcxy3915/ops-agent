package com.example.opsaiagent.automation.diagnosis;

import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** 显式有界 ReAct 循环：模型决定下一项，服务端授权执行，再将真实反馈交给模型。 */
@Service
public class DiagnosisLoop {
    private final DiagnosisAgent agent;
    private final DiagnosisToolGateway tools;
    private final ObjectMapper mapper;
    private final LongSupplier clock;
    @Value("${ops-agent.inspection.diagnosis.max-rounds:8}")
    private int maxRounds = 8;
    @Value("${ops-agent.inspection.diagnosis.timeout-seconds:120}")
    private int timeoutSeconds = 120;
    private static final int MAX_CONTEXT_CHARS = 100_000;

    /**
     * 使用单调时钟创建循环。
     * @param agent 诊断决策代理
     * @param tools 受控工具网关
     * @param mapper JSON 映射器
     * @return 无返回值
     */
    @Autowired
    public DiagnosisLoop(DiagnosisAgent agent, DiagnosisToolGateway tools, ObjectMapper mapper) {
        this(agent, tools, mapper, System::nanoTime);
    }

    /**
     * 注入时钟用于确定性预算测试。
     * @param agent 决策代理
     * @param tools 工具网关
     * @param mapper JSON 映射器
     * @param clock 单调纳秒时钟
     * @return 无返回值
     */
    DiagnosisLoop(DiagnosisAgent agent, DiagnosisToolGateway tools, ObjectMapper mapper, LongSupplier clock) {
        this.agent = agent;
        this.tools = tools;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 从真实巡检快照开始有界取证，不正常时也不能开放写动作。
     * @param baseline 当前实际巡检报告
     * @return 诊断报告与步骤证据；预算耗尽或非法决策转人工
     */
    public AutonomousDiagnosisReport run(InspectionReport baseline) {
        long deadline = clock.getAsLong() + Math.clamp(timeoutSeconds, 1, 300) * 1_000_000_000L;
        List<AutonomousDiagnosisReport.Evidence> evidence = new ArrayList<>();
        List<AutonomousDiagnosisReport.Step> steps = new ArrayList<>();
        evidence.add(new AutonomousDiagnosisReport.Evidence("e0", "INSPECTION_REPORT",
                baseline.completedAt(), mapper.valueToTree(baseline)));
        if ("HEALTHY".equals(baseline.status())) {
            return new AutonomousDiagnosisReport("COMPLETED", "NO_ANOMALY", "本次巡检未发现规则异常",
                    "仅覆盖已配置的巡检项目", List.of("e0"), List.of(), List.copyOf(evidence));
        }
        var catalog = tools.catalog(baseline.serviceId());
        HashSet<String> executed = new HashSet<>();
        for (int round = 0; round < Math.clamp(maxRounds, 1, 8); round++) {
            if (clock.getAsLong() >= deadline) return stop("TIME_BUDGET_EXHAUSTED", steps, evidence);
            String context;
            try {
                context = mapper.writeValueAsString(Map.of("catalog", catalog, "evidence", evidence,
                        "remainingRounds", Math.clamp(maxRounds, 1, 8) - round));
            } catch (Exception failure) {
                return stop("CONTEXT_INVALID", steps, evidence);
            }
            if (context.length() > MAX_CONTEXT_CHARS) return stop("CONTEXT_BUDGET_EXHAUSTED", steps, evidence);
            DiagnosisDecision decision;
            try {
                decision = agent.decide(context);
            } catch (Exception failure) {
                return stop("MODEL_UNAVAILABLE", steps, evidence);
            }
            if (clock.getAsLong() >= deadline) return stop("TIME_BUDGET_EXHAUSTED", steps, evidence);
            if (!valid(decision)) return stop("INVALID_DECISION", steps, evidence);
            if ("FINISH".equals(decision.action())) {
                List<String> refs = Arrays.stream(decision.evidenceIds().split(",")).map(String::strip)
                        .filter(value -> !value.isBlank()).distinct().toList();
                var known = evidence.stream().map(AutonomousDiagnosisReport.Evidence::id).toList();
                if (refs.isEmpty() || !known.containsAll(refs)) return stop("INVALID_EVIDENCE_REFERENCE", steps, evidence);
                return new AutonomousDiagnosisReport("COMPLETED", "AGENT_FINISHED", decision.summary(),
                        decision.limitations(), refs, List.copyOf(steps), List.copyOf(evidence));
            }
            String action = decision.action();
            String argument = decision.argument();
            if (!catalog.getOrDefault(action, List.of()).contains(argument)) return stop("TOOL_NOT_ALLOWED", steps, evidence);
            if (!executed.add(action + ":" + argument)) return stop("NO_NEW_EVIDENCE", steps, evidence);
            Object result;
            String status = "SUCCEEDED";
            try {
                result = tools.execute(baseline.serviceId(), action, argument, baseline.completedAt());
            } catch (Exception failure) {
                result = Map.of("status", "UNAVAILABLE", "reason", "TOOL_EXECUTION_FAILED");
                status = "FAILED";
            }
            String id = "e" + evidence.size();
            evidence.add(new AutonomousDiagnosisReport.Evidence(id, action, System.currentTimeMillis(), mapper.valueToTree(result)));
            steps.add(new AutonomousDiagnosisReport.Step(steps.size() + 2, action, argument, id, status));
            if (clock.getAsLong() >= deadline) return stop("TIME_BUDGET_EXHAUSTED", steps, evidence);
        }
        return stop("ROUND_BUDGET_EXHAUSTED", steps, evidence);
    }

    /**
     * 限制模型输出长度并要求最终引用字段存在。
     * @param decision 模型输出
     * @return 是否符合最小结构约束
     */
    private boolean valid(DiagnosisDecision decision) {
        return decision != null && decision.action() != null && decision.argument() != null
                && decision.summary() != null && !decision.summary().isBlank() && decision.summary().length() <= 4000
                && decision.argument().length() <= 128 && decision.evidenceIds() != null
                && decision.evidenceIds().length() <= 512 && decision.limitations() != null
                && decision.limitations().length() <= 4000;
    }

    /**
     * 保留已有真实证据并明确要求人工处理，不编造最终根因。
     * @param reason 停止原因
     * @param steps 已执行步骤
     * @param evidence 已取得证据
     * @return 需人工处理的报告
     */
    private AutonomousDiagnosisReport stop(String reason, List<AutonomousDiagnosisReport.Step> steps,
                                            List<AutonomousDiagnosisReport.Evidence> evidence) {
        return new AutonomousDiagnosisReport("NEEDS_MANUAL", reason, "自动诊断未完成，请依据已采集证据继续排查",
                "未形成可验证的完整结论", List.of(), List.copyOf(steps), List.copyOf(evidence));
    }
}
