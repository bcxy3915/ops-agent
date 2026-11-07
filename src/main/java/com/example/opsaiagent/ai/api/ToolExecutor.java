package com.example.opsaiagent.ai.api;

/** 本地工具和 MCP 工具共用的执行边界。 */
public interface ToolExecutor {
    /**
     * 在调用上下文中执行工具。
     *
     * @param call 工具调用
     * @param context 工具执行上下文
     * @return 工具执行结果
     */
    ToolResult execute(ToolCall call, ToolExecutionContext context);
}
