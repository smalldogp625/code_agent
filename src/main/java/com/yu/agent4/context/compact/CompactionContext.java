package com.yu.agent4.context.compact;

/**
 * 压缩管线上下文 — 承载各 Compactor 执行时所需的共享状态。
 *
 * <p>管道调用方基于 {@link com.yu.agent4.config.AgentContextProperties} 计算
 * {@code tokenThreshold}（触发值）和 {@code tokenTarget}（目标值）后传入。
 *
 * @param tokenThreshold 触发压缩的 token 估算阈值（≈ windowSize × compactionRatio）
 * @param tokenTarget    SnipCompactor 压缩目标 token 数（≈ windowSize × targetRatio）
 * @param historySize    当前历史消息总数
 * @param stepCount      当前已执行的 step 数
 * @param maxSteps       本轮最大 step 数
 */
public record CompactionContext(
        long tokenThreshold,
        long tokenTarget,
        int historySize,
        int stepCount,
        int maxSteps
) {

    /**
     * 快速构造，适用于仅有阈值、目标和消息数的场景。
     */
    public static CompactionContext of(long tokenThreshold, long tokenTarget, int historySize) {
        return new CompactionContext(tokenThreshold, tokenTarget, historySize, 0, 0);
    }
}
