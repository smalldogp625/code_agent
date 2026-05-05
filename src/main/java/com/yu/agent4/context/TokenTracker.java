package com.yu.agent4.context;

import org.slf4j.Logger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

/**
 * Token 统计器，追踪每轮 Agent 循环中的 token 消耗。
 * <p>
 * 具有两个能力：
 * <ul>
 * <li><b>后验记录</b> — 每次 {@code chatModel.call()} 后从 {@link ChatResponse} 元数据读取真实 token 数
 * <li><b>先验估算</b> — 对 {@link List<Message>} 做粗略估算，用于决定是否需要在模型调用前触发压缩
 * </ul>
 *
 * <p>
 * 每轮新 turn 开始前应调用 {@link #reset()}。
 */
public class TokenTracker {

    // 中文场景的估算系数：~0.4 token/字符
    private static final double ESTIMATE_FACTOR = 0.4;

    // 估算时每条消息的固定 overhead（消息类型标记等）
    private static final long MESSAGE_OVERHEAD = 8;

    private long totalPromptTokens;

    private long totalCompletionTokens;

    private int modelCallCount;

    public TokenTracker() {
        reset();
    }

    // ========== 后验记录 ==========

    /**
     * 从模型响应中提取真实 token 消耗并累计。
     *
     * @param response {@code chatModel.call()} 的返回值，不能为 null
     */
    public void record(ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            this.totalPromptTokens += safe(usage.getPromptTokens());
            this.totalCompletionTokens += safe(usage.getCompletionTokens());
            this.modelCallCount++;
        }
    }

    /**
     * 记录单步 token 消耗日志到指定的 SLF4J Logger。
     * <p>
     * 将 Agent 循环中的 token 日志聚合成一条简洁语句，
     * 避免 loop 代码中散落大段元数据提取与格式化逻辑。
     *
     * @param logger   SLF4J logger 实例（通常传入 {@code log}）
     * @param step     当前 step 序号（从 1 开始）
     * @param maxSteps 最大 step 数
     * @param response 刚返回的模型响应，从中提取 usage 元数据
     */
    public void logStep(Logger logger, int step, int maxSteps, ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            logger.info("Step {}/{} — prompt_tokens: {} | completion_tokens: {} | cumulative: {}",
                    step, maxSteps,
                    safe(usage.getPromptTokens()),
                    safe(usage.getCompletionTokens()),
                    estimateTotal());
        }
    }

    // ========== 先验估算 ==========

    /**
     * 对消息列表做快速 token 估算，用于在模型调用前判断上下文压力。
     * <p>
     * 使用简单启发式：中文字符数 × 0.4 + 消息条数 × overhead。
     * 不需要精确，只需能判断「是否接近上下文上限」即可。
     *
     * @param messages 需要估算的消息列表
     * @return 估算的 token 总数
     */
    public long estimateMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long totalTextLength = 0;
        for (Message message : messages) {
            String text = message.getText();
            if (text != null) {
                totalTextLength += text.length();
            }
        }
        return (long) (totalTextLength * ESTIMATE_FACTOR) + (long) messages.size() * MESSAGE_OVERHEAD;
    }

    // ========== 类型感知单条估算 ==========

    /**
     * 对单条消息做类型感知的 token 估算。
     * <p>
     * 不同消息类型使用不同系数，比 {@link #estimateMessages(List)} 的全局 0.4 更准确。
     * 用于 {@link com.yu.agent4.context.compact.SnipCompactor} 做 turn 级裁剪决策。
     *
     * @param message 单条消息
     * @return 估算 token 数
     */
    public static long estimateTokens(Message message) {
        if (message == null) return 0;

        double coefficient;
        if (message instanceof SystemMessage) {
            coefficient = 0.25;       // 英文指令 + tool schema
        } else if (message instanceof UserMessage) {
            coefficient = 0.65;       // 中文问句为主
        } else if (message instanceof AssistantMessage am && am.hasToolCalls()) {
            coefficient = 0.30;       // JSON 格式的工具声明
        } else if (message instanceof AssistantMessage) {
            coefficient = 0.40;       // 中英混合回复
        } else if (message instanceof ToolResponseMessage) {
            coefficient = 0.25;       // 代码/日志/文件内容
        } else {
            coefficient = 0.40;
        }

        long total = 0;
        String text = message.getText();
        if (text != null) {
            total += (long) Math.ceil(text.length() * coefficient);
        }

        // ToolResponseMessage 的 responseData 也要算入
        if (message instanceof ToolResponseMessage trm) {
            for (var resp : trm.getResponses()) {
                String data = resp.responseData();
                if (data != null) {
                    total += (long) Math.ceil(data.length() * coefficient);
                }
            }
        }

        return total + MESSAGE_OVERHEAD;
    }

    // ========== 决策辅助 ==========

    /**
     * 判断是否需要触发上下文压缩。
     * <p>
     * 判断依据：{@code 当前历史估算 + 本轮已消耗 token > 阈值}。
     * 将已消耗 token 计入是为了避免「上一轮刚压缩完，本轮回弹」的情况。
     *
     * @param messages  当前待发送的消息列表
     * @param threshold token 阈值，达到该值应触发压缩
     * @return 如果估算消耗超过阈值返回 true
     */
    public boolean shouldCompact(List<Message> messages, long threshold) {
        long estimated = estimateMessages(messages);
        return estimated + estimateTotal() > threshold;
    }

    /**
     * 重置统计，开始新 turn。
     */
    public void reset() {
        this.totalPromptTokens = 0;
        this.totalCompletionTokens = 0;
        this.modelCallCount = 0;
    }

    // ========== 访问器 ==========

    /**
     * 返回本轮已累积的输入 + 输出 token 总数（基于真实 API 响应）。
     */
    public long estimateTotal() {
        return totalPromptTokens + totalCompletionTokens;
    }

    /**
     * 本轮输入 token 累计（基于真实 API 响应）。
     */
    public long getTotalPromptTokens() {
        return totalPromptTokens;
    }

    /**
     * 本轮输出 token 累计（基于真实 API 响应）。
     */
    public long getTotalCompletionTokens() {
        return totalCompletionTokens;
    }

    /**
     * 本轮已调用模型的次数。
     */
    public int getModelCallCount() {
        return modelCallCount;
    }

    // ========== 内部 ==========

    private static int safe(Integer value) {
        return value != null ? value : 0;
    }
}
