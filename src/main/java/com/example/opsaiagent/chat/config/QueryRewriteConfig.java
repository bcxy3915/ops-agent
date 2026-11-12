package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.ai.adapter.langchain4j.ConversationQueryRewriter;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** 查询改写使用独立轻量客户端，不参与主聊天模型的注入选择。 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class QueryRewriteConfig {
    @Value("${ops-agent.ai.query-rewrite.enabled:true}")
    private boolean enabled;

    @Value("${ops-agent.ai.query-rewrite.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${ops-agent.ai.query-rewrite.api-key:${ops-agent.ai.embedding.api-key:}}")
    private String apiKey;

    @Value("${ops-agent.ai.query-rewrite.model:qwen-turbo}")
    private String modelName;

    @Value("${ops-agent.ai.query-rewrite.timeout:5s}")
    private String timeout;

    /**
     * 创建改写器，专用客户端不暴露为通用 ChatModel，避免影响主问答及诊断。
     * 无入参。
     * @return 使用独立模型的改写器；无密钥或禁用时返回原问题
     */
    @Bean
    public ConversationQueryRewriter conversationQueryRewriter() {
        if (!enabled || !StringUtils.hasText(apiKey)) {
            log.info("查询改写未启用或未配置密钥，将使用用户原问题检索");
            return new ConversationQueryRewriter(null);
        }
        var requestTimeout = DurationStyle.detectAndParse(timeout);
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("查询改写超时必须大于零");
        }
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .maxTokens(256)
                .timeout(requestTimeout)
                .maxRetries(0)
                .build();
        return new ConversationQueryRewriter(model);
    }
}
