package com.example.opsaiagent.ai.adapter.langchain4j;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.memory.ChatMemoryAccess;

/** LangChain4j 流式运维助手调用接口。 */
public interface OpsStreamingAssistant extends ChatMemoryAccess {

    /**
     * 在指定用户会话中执行流式运维问答。
     *
     * @param memoryId 经验证并编码的用户会话记忆标识
     * @param systemPrompt 当前请求的系统提示词
     * @param question 用户问题
     * @return LangChain4j 流式事件
     */
    @SystemMessage("{{systemPrompt}}")
    TokenStream askStream(@MemoryId String memoryId,
                          @UserMessage String question,
                          @V("systemPrompt") String systemPrompt);
}
