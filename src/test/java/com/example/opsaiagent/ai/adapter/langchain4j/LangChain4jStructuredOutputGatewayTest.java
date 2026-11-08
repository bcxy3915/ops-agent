package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.ai.api.ChatRequest;
import com.example.opsaiagent.diagnosis.dto.DiagnosisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证生产结构化网关对模型响应的严格解析，不调用真实模型。 */
class LangChain4jStructuredOutputGatewayTest {

    /**
     * 验证合法 JSON 可解析，而空值、尾随对象及非 JSON 响应不能伪装成成功。
     * @return 无返回值
     */
    @Test
    void parsesOneJsonValueAndRejectsEmptyOrTrailingOutput() {
        ChatModel model = mock(ChatModel.class);
        var gateway = new LangChain4jStructuredOutputGateway(model, new ObjectMapper());
        var request = new ChatRequest("diagnosis-test", "诊断", "输出 JSON", null);
        for (String invalid : new String[]{"null", "{} {}", "不是 JSON", ""}) {
            when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(invalid)).build());
            assertThatThrownBy(() -> gateway.generate(request, DiagnosisResult.class))
                    .isInstanceOf(IllegalStateException.class);
        }
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"rootCause\":\"证据不足\"}")).build());
        assertThat(gateway.generate(request, DiagnosisResult.class).getRootCause()).isEqualTo("证据不足");
    }
}
