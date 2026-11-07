package com.example.opsaiagent.ai.api;

public record ToolResult(String name, boolean success, Object value, String error) {
    /**
     * 创建成功的工具结果。
     *
     * @param name 工具名称
     * @param value 工具返回值
     * @return 成功结果
     */
    public static ToolResult success(String name, Object value) {
        return new ToolResult(name, true, value, null);
    }

    /**
     * 创建失败的工具结果。
     *
     * @param name 工具名称
     * @param error 错误信息
     * @return 失败结果
     */
    public static ToolResult failure(String name, String error) {
        return new ToolResult(name, false, null, error);
    }
}
