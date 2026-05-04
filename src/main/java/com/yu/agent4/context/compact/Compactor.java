package com.yu.agent4.context.compact;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 上下文压缩器 — 对消息列表执行一种特定策略的压缩变换。
 *
 * <p>每个 {@code Compactor} 聚焦单一压缩策略（如工具输出截断、历史滑窗），
 * 通过 {@link #order()} 决定在职责链中的执行顺序。
 * 多个 {@code Compactor} 由 {@code CompactionPipeline} 编排为完整的压缩管线。
 *
 * <p>设计意图（参见 arXiv:2604.14228v1 §7.3 Compaction Pipeline）：
 * <ul>
 *   <li>单一职责 — 每个实现类只做一种压缩</li>
 *   <li>可组合 — 通过 order() 任意排序组合</li>
 *   <li>可插拔 — 新增压缩策略不影响已有实现</li>
 * </ul>
 */
@FunctionalInterface
public interface Compactor {

    /**
     * 对消息列表执行压缩，返回压缩后的新列表。
     * <p>
     * 实现应注意：入参消息列表<strong>不可直接修改</strong>，
     * 应复制后变换再返回新列表。
     *
     * @param messages 待压缩的消息列表
     * @param context  压缩上下文信息
     * @return 压缩后的消息列表
     */
    List<Message> compact(List<Message> messages, CompactionContext context);

    /**
     * 执行顺序号，小值优先。
     * <p>
     * 内建顺序约定：
     * <ul>
     *   <li>10 — {@code BudgetCompactor}（先裁每条消息体）</li>
     *   <li>20 — {@code SnipCompactor}（再裁消息总量）</li>
     * </ul>
     *
     * @return 顺序号，默认 100
     */
    default int order() {
        return 100;
    }
}
