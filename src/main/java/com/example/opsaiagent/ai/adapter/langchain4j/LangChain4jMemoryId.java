package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.rbac.context.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 生成包含用户归属的 LangChain4j 记忆标识。 */
public final class LangChain4jMemoryId {

    private LangChain4jMemoryId() {
    }

    /**
     * 将当前可信租户、用户名和会话 ID 编码为不会产生分隔符歧义的记忆标识。
     *
     * @param username 当前用户名
     * @param sessionId 会话 ID
     * @return 用户隔离的记忆标识
     */
    public static String of(String username, String sessionId) {
        String tenantId = TenantContext.get();
        String tenant = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((tenantId == null ? "" : tenantId).getBytes(StandardCharsets.UTF_8));
        String user = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(username.getBytes(StandardCharsets.UTF_8));
        String session = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
        return tenant + "." + user + "." + session;
    }

    /**
     * 从记忆标识中解析租户、用户名和会话 ID。
     *
     * @param memoryId LangChain4j 记忆标识
     * @return 包含租户、用户名和会话 ID 的归属信息
     */
    public static Identity parse(Object memoryId) {
        String[] parts = String.valueOf(memoryId).split("\\.", -1);
        if (parts.length != 3 && parts.length != 4) {
            throw new IllegalArgumentException("无效的 LangChain4j 记忆标识");
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return new Identity(
                parts[0].isEmpty() ? null : new String(decoder.decode(parts[0]), StandardCharsets.UTF_8),
                new String(decoder.decode(parts[1]), StandardCharsets.UTF_8),
                new String(decoder.decode(parts[2]), StandardCharsets.UTF_8));
    }

    /** 用户隔离的会话标识。 */
    public record Identity(String tenantId, String username, String sessionId) {
    }
}
