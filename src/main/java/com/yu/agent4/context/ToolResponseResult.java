package com.yu.agent4.context;

/**
 * 工具调用结果记录 — 预算削减的基础数据单元。
 *
 * <p>每次工具执行完成时填充，记录工具调用的原始输出及其上下文坐标
 * （session/turn/tool），供后续预算削减（BudgetCompactor）决策使用。
 *
 * @param sessionId      当前上下文窗口的会话标识
 * @param turnId         当前是第几次 runTurn（从 1 递增）
 * @param toolId         ToolResponse.id，如 "call_2da4e19b021741559db5d3"
 * @param toolName       工具名称，如 "bash"、"read"、"write"
 * @param rawResponseData  工具执行原始输出
 * @param originalLength  rawResponseData 的字符长度（构造时自动计算）
 */

public record ToolResponseResult(
        String sessionId,
        int turnId,
        String toolId,
        String toolName,
        String rawResponseData,
        int originalLength
) {

    public ToolResponseResult {
        if (rawResponseData == null) {
            rawResponseData = "";
        }
        originalLength = rawResponseData.length();
    }

    /**
     * 便捷工厂，自动计算 originalLength。
     */
    public static ToolResponseResult of(String sessionId, int turnId,
                                         String toolId, String toolName,
                                         String rawResponseData) {
        return new ToolResponseResult(sessionId, turnId, toolId, toolName,
                rawResponseData, 0);
    }
}
