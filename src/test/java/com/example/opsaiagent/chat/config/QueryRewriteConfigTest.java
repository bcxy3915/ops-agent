package com.example.opsaiagent.chat.config;

import com.example.opsaiagent.ai.adapter.langchain4j.ConversationQueryRewriter;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel.OpenAiChatModelBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 使用可控客户端验证轻量模型配置与注入隔离，不访问外部供应商。 */
class QueryRewriteConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(QueryRewriteConfig.class);

    /**
     * 禁用或缺少密钥时不阻塞应用，也不回退调用主模型。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void disabledOrMissingKeyUsesOriginalQuestion() {
        for (String[] properties : List.of(new String[]{"ops-agent.ai.query-rewrite.api-key="},
                new String[]{"ops-agent.ai.query-rewrite.enabled=false", "ops-agent.ai.query-rewrite.api-key=test"})) {
            runner.withPropertyValues(properties).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ConversationQueryRewriter.class)
                        .rewrite("那怎么调整？", List.of(UserMessage.from("商城连接池耗尽"))))
                        .isEqualTo("那怎么调整？");
                assertThat(context).doesNotHaveBean(ChatModel.class);
            });
        }
    }

    /**
     * 默认使用独立轻量模型，明确限制输出、超时及重试，主模型注入不受影响。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void usesDedicatedLightweightClientAndKeepsMainModel() {
        ChatModel mainModel = mock(ChatModel.class);
        OpenAiChatModel lightweightModel = mock(OpenAiChatModel.class);
        when(lightweightModel.chat(anyString())).thenReturn("商城连接池耗尽如何调整？");
        OpenAiChatModelBuilder builder = mock(OpenAiChatModelBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(lightweightModel);
        try (var factory = mockStatic(OpenAiChatModel.class)) {
            factory.when(OpenAiChatModel::builder).thenReturn(builder);
            runner.withBean("mainModel", ChatModel.class, () -> mainModel)
                    .withPropertyValues("ops-agent.ai.embedding.api-key=test-embedding-key")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class);
                        assertThat(context.getBean(ChatModel.class)).isSameAs(mainModel);
                        assertThat(context.getBean(ConversationQueryRewriter.class)
                                .rewrite("那怎么调整？", List.of(UserMessage.from("商城连接池耗尽"))))
                                .isEqualTo("商城连接池耗尽如何调整？");
                    });
            verify(builder).baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
            verify(builder).apiKey("test-embedding-key");
            verify(builder).modelName("qwen-turbo");
            verify(builder).temperature(0.0);
            verify(builder).maxTokens(256);
            verify(builder).timeout(Duration.ofSeconds(5));
            verify(builder).maxRetries(0);
            verify(lightweightModel).chat(anyString());
            verifyNoInteractions(mainModel);
        }
    }

    /**
     * 独立密钥、模型及地址可以覆盖默认值，不读取主模型配置。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void acceptsDedicatedProviderOverrides() {
        OpenAiChatModelBuilder builder = mock(OpenAiChatModelBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(mock(OpenAiChatModel.class));
        try (var factory = mockStatic(OpenAiChatModel.class)) {
            factory.when(OpenAiChatModel::builder).thenReturn(builder);
            runner.withPropertyValues("ops-agent.ai.query-rewrite.api-key=test-rewrite-key",
                            "ops-agent.ai.embedding.api-key=test-embedding-key",
                            "ops-agent.ai.query-rewrite.base-url=http://rewrite.example/v1",
                            "ops-agent.ai.query-rewrite.model=custom-small-model",
                            "ops-agent.ai.query-rewrite.timeout=3s")
                    .run(context -> assertThat(context).hasNotFailed());
            verify(builder).apiKey("test-rewrite-key");
            verify(builder).baseUrl("http://rewrite.example/v1");
            verify(builder).modelName("custom-small-model");
            verify(builder).timeout(Duration.ofSeconds(3));
        }
    }
}
