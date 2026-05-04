package com.yu.agent4.context.compact;

/**
 * 压缩管线上下文 — 承载各 Compactor 执行时所需的共享状态。
 *
 * <p>当 {@code TokenTracker.shouldCompact()} 返回 {@code true} 时，
 * 管道调用方（如 {@code ContextAssembler}）构造此记录传入压缩链。
 *
 * @param tokenThreshold 触发压缩的 token 估算阈值
 * @param historySize    当前历史消息总数
 * @param stepCount      当前已执行的 step 数
 * @param maxSteps       本轮最大 step 数
 */
public record CompactionContext(
        long tokenThreshold,
        int historySize,
        int stepCount,
        int maxSteps
) {

    /**
     * 快速构造，适用于仅有阈值和消息数的场景。
     */
    public static CompactionContext of(long tokenThreshold, int historySize) {
        return new CompactionContext(tokenThreshold, historySize, 0, 0);
    }
}
