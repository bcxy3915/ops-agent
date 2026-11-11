package com.example.opsaiagent.automation.diagnosis;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 复用生产 LangChain4j 模型，不将诊断上下文与聊天窗口混用。 */
@Configuration
public class DiagnosisAgentConfig {
    /**
     * 注册单步诊断决策代理，工具执行和预算由服务端循环控制。
     * @param chatModel 生产聊天模型
     * @return 诊断 Agent
     */
    @Bean
    public DiagnosisAgent diagnosisAgent(ChatModel chatModel) {
        return AiServices.builder(DiagnosisAgent.class).chatModel(chatModel).build();
    }
}
