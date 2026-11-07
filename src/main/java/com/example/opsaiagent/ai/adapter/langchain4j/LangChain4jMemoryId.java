package com.example.opsaiagent.ai.adapter.langchain4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 生成包含用户归属的 LangChain4j 记忆标识。 */
public final class LangChain4jMemoryId {

    private LangChain4jMemoryId() {
    }

    /**
     * 将用户名和会话 ID 编码为不会产生分隔符歧义的记忆标识。
     *
     * @param username 当前用户名
     * @param sessionId 会话 ID
     * @return 用户隔离的记忆标识
     */
    public static String of(String username, String sessionId) {
        String user = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(username.getBytes(StandardCharsets.UTF_8));
        String session = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
        return user + "." + session;
    }

    /**
     * 从记忆标识中解析用户名和会话 ID。
     *
     * @param memoryId LangChain4j 记忆标识
     * @return 包含用户名和会话 ID 的二元组
     */
    public static Identity parse(Object memoryId) {
        String[] parts = String.valueOf(memoryId).split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("无效的 LangChain4j 记忆标识");
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return new Identity(
                new String(decoder.decode(parts[0]), StandardCharsets.UTF_8),
                new String(decoder.decode(parts[1]), StandardCharsets.UTF_8));
    }

    /** 用户隔离的会话标识。 */
    public record Identity(String username, String sessionId) {
    }
}
