package com.example.opsaiagent.diagnosis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 诊断提示词模板测试（T14）
 */
@DisplayName("诊断提示词模板")
class DiagnosisPromptTemplateTest {

    private final DiagnosisPromptTemplate template = new DiagnosisPromptTemplate();

    @Test
    @DisplayName("系统提示词包含诊断输出要素：根因/证据/置信度/建议/摘要")
    void systemPrompt_containsCoreSections() {
        assertThat(DiagnosisPromptTemplate.SYSTEM_PROMPT)
                .contains("rootCause")
                .contains("evidence")
                .contains("confidence")
                .contains("suggestions")
                .contains("summary")
                .contains("SRE");
    }

    @Test
    @DisplayName("用户消息包含快照 JSON 内容")
    void renderUserMessage_containsSnapshot() {
        String rendered = template.renderUserMessage("{\"event\":{\"service\":\"svc-a\"}}");
        assertThat(rendered).contains("svc-a").contains("故障快照");
    }

    @Test
    @DisplayName("用户消息空快照时兜底为空对象")
    void renderUserMessage_nullSnapshot() {
        String rendered = template.renderUserMessage(null);
        assertThat(rendered).contains("{}");
    }
}
