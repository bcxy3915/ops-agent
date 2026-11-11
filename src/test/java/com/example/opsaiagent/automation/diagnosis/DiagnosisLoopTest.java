package com.example.opsaiagent.automation.diagnosis;

import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证模型反馈驱动分支与预算约束，脚本模型不代替真实模型规划验收。 */
class DiagnosisLoopTest {
    private final DiagnosisAgent agent = mock(DiagnosisAgent.class);
    private final DiagnosisToolGateway tools = mock(DiagnosisToolGateway.class);
    private final DiagnosisLoop loop = new DiagnosisLoop(agent, tools, new ObjectMapper(), () -> 0L);

    /**
     * 构造仅测试使用的初始巡检事实。
     * @param status 初始状态
     * @return 巡检报告夹具
     */
    private InspectionReport baseline(String status) {
        return new InspectionReport("svc", "mall", 1, 2, status, List.of("测试异常"), List.of());
    }

    /**
     * 配置白名单工具目录和动态初始动作。
     * @param action 第一步动作
     * @param argument 参数
     * @return 无返回值
     */
    private void prepare(String action, String argument) {
        when(tools.catalog("svc")).thenReturn(Map.of("METRIC_WINDOW", List.of("process.cpu.usage"),
                "LOGS", List.of("main"), "BUSINESS_PROBE", List.of("catalog")));
        when(agent.decide(anyString())).thenReturn(new DiagnosisDecision(action, argument, "查询相关证据", "", ""),
                new DiagnosisDecision("FINISH", "", "依据真实观测给出待验证判断", "e0,e1", "仍需验证根因"));
        when(tools.execute("svc", action, argument, 2)).thenReturn(Map.of("testFixture", "observed"));
    }

    /**
     * 正常巡检不消耗模型调用。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void healthyBaselineSkipsModel() {
        assertThat(loop.run(baseline("HEALTHY")).reason()).isEqualTo("NO_ANOMALY");
        verifyNoInteractions(agent, tools);
    }

    /**
     * 工具反馈决定下一步，模型能够先看日志再选择业务探针。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void observationDrivesDifferentNextAction() {
        when(tools.catalog("svc")).thenReturn(Map.of("LOGS", List.of("main"), "BUSINESS_PROBE", List.of("catalog")));
        when(tools.execute("svc", "LOGS", "main", 2)).thenReturn(Map.of("pattern", "business-error"));
        when(tools.execute("svc", "BUSINESS_PROBE", "catalog", 2)).thenReturn(Map.of("status", "ABNORMAL"));
        when(agent.decide(anyString())).thenAnswer(invocation -> {
            String context = invocation.getArgument(0);
            if (context.contains("ABNORMAL") && context.contains("BUSINESS_PROBE\",\"collectedAt")) {
                return new DiagnosisDecision("FINISH", "", "业务探针确认异常", "e1,e2", "根因待验证");
            }
            if (context.contains("business-error")) {
                return new DiagnosisDecision("BUSINESS_PROBE", "catalog", "日志指向业务异常，验证接口", "", "");
            }
            return new DiagnosisDecision("LOGS", "main", "先查询错误日志", "", "");
        });
        var result = loop.run(baseline("ABNORMAL"));
        assertThat(result.reason()).isEqualTo("AGENT_FINISHED");
        assertThat(result.steps()).extracting(AutonomousDiagnosisReport.Step::action).containsExactly("LOGS", "BUSINESS_PROBE");
        assertThat(result.evidenceIds()).containsExactly("e1", "e2");
    }

    /**
     * 指标分支可以不查询日志，与固定工具顺序区分。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void metricBranchUsesOnlySelectedTool() {
        prepare("METRIC_WINDOW", "process.cpu.usage");
        var result = loop.run(baseline("ABNORMAL"));
        assertThat(result.steps()).extracting(AutonomousDiagnosisReport.Step::action).containsExactly("METRIC_WINDOW");
        assertThat(result.evidence()).hasSize(2);
        verify(tools).execute("svc", "METRIC_WINDOW", "process.cpu.usage", 2);
    }

    /**
     * 非白名单操作在工具执行之前阻断。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsWriteAction() {
        when(tools.catalog("svc")).thenReturn(Map.of());
        when(agent.decide(anyString())).thenReturn(new DiagnosisDecision("RESTART", "svc", "重启", "", ""));
        var result = loop.run(baseline("ABNORMAL"));
        assertThat(result.reason()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(result.steps()).isEmpty();
    }

    /**
     * 最终引用必须来自实际证据，不能伪造来源。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsInventedEvidenceReference() {
        when(tools.catalog("svc")).thenReturn(Map.of());
        when(agent.decide(anyString())).thenReturn(new DiagnosisDecision("FINISH", "", "确定结论", "e999", ""));
        assertThat(loop.run(baseline("ABNORMAL")).reason()).isEqualTo("INVALID_EVIDENCE_REFERENCE");
    }

    /**
     * 工具重复不能无限续费或轮询。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void stopsRepeatedToolCall() {
        prepare("LOGS", "main");
        when(agent.decide(anyString())).thenReturn(new DiagnosisDecision("LOGS", "main", "查日志", "", ""));
        assertThat(loop.run(baseline("ABNORMAL")).reason()).isEqualTo("NO_NEW_EVIDENCE");
    }

    /**
     * 达到模型轮数上限后保留证据并要求人工处理。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void enforcesRoundBudget() {
        prepare("LOGS", "main");
        ReflectionTestUtils.setField(loop, "maxRounds", 1);
        var result = loop.run(baseline("ABNORMAL"));
        assertThat(result.status()).isEqualTo("NEEDS_MANUAL");
        assertThat(result.reason()).isEqualTo("ROUND_BUDGET_EXHAUSTED");
        assertThat(result.steps()).hasSize(1);
    }

    /**
     * 工具失败作为明确缺失反馈，不伪造成功响应。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void recordsToolFailureAsEvidence() {
        prepare("LOGS", "main");
        when(tools.execute("svc", "LOGS", "main", 2)).thenThrow(new IllegalStateException("private-url"));
        var result = loop.run(baseline("ABNORMAL"));
        assertThat(result.steps().getFirst().status()).isEqualTo("FAILED");
        assertThat(result.evidence().getLast().data().toString()).contains("TOOL_EXECUTION_FAILED").doesNotContain("private-url");
    }

    /**
     * 模型超过预算后不能继续执行工具。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void checksDeadlineAfterModelReturns() {
        var clock = new AtomicLong();
        var timed = new DiagnosisLoop(agent, tools, new ObjectMapper(), clock::get);
        when(tools.catalog("svc")).thenReturn(Map.of());
        when(agent.decide(anyString())).thenAnswer(invocation -> {
            clock.set(121_000_000_000L);
            return new DiagnosisDecision("FINISH", "", "过期结论", "e0", "");
        });
        assertThat(timed.run(baseline("ABNORMAL")).reason()).isEqualTo("TIME_BUDGET_EXHAUSTED");
    }
}
