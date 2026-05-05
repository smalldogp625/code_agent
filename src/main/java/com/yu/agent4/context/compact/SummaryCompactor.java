package com.yu.agent4.context.compact;

import com.yu.agent4.context.TokenTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要压缩器 — Summary Reduction。
 * 当 SnipCompactor 无法释放足够 token（insufficient）时，
 * 对保护区内的已完成 turn 调用模型做摘要，用 SystemMessage 替代原始消息。
 *
 * <p>策略：
 * <ol>
 *   <li>找到当前 turn（最后一个 UserMessage 到末尾）</li>
 *   <li>当前 turn 之前的所有消息 → 摘要候选</li>
 *   <li>如果候选范围 token 少于阈值则跳过（不值得调模型）</li>
 *   <li>调用模型生成摘要，用 SystemMessage 替换候选范围</li>
 *   <li>当前 turn 保持不变</li>
 * </ol>
 *
 * <p>执行顺序：30（在 Budget 10、Snip 20 之后）
 */
public class SummaryCompactor implements Compactor {

    private static final Logger log = LoggerFactory.getLogger(SummaryCompactor.class);

    private static final int DEFAULT_MIN_TOKENS = 500;

    private final ChatModel chatModel;

    /** 最小 token 阈值，低于此值不调模型 */
    private final int minTokensToSummarize;

    public SummaryCompactor(ChatModel chatModel) {
        this(chatModel, DEFAULT_MIN_TOKENS);
    }

    public SummaryCompactor(ChatModel chatModel, int minTokensToSummarize) {
        this.chatModel = chatModel;
        this.minTokensToSummarize = minTokensToSummarize;
    }

    @Override
    public List<Message> compact(List<Message> messages, CompactionContext context) {
        if (messages.isEmpty()) return messages;

        // 1. 找到可摘要范围（当前 turn 之前的已完成消息）
        SummaryTarget target = findRange(messages);
        if (target == null || target.tokens() < minTokensToSummarize) {
            return messages; // 不值得调模型
        }

        // 2. 调用模型做摘要
        String summary = callModel(messages.subList(target.start(), target.end()));
        log.info("[summary] compressed {} messages (~{} tokens)",
                target.end() - target.start(), target.tokens());

        // 3. 替换为摘要
        return buildResult(messages, target, summary);
    }

    @Override
    public int order() {
        return 30;
    }

    // ========================================================================
    //  内部方法
    // ========================================================================

    /**
     * 在消息列表中找到可摘要的范围。
     * 范围 = 第一个非-boundary 消息 到 最后一个 UserMessage（不含）。
     */
    static SummaryTarget findRange(List<Message> messages) {
        // 从后往前找到当前 turn 起点（最后一个 UserMessage）
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx <= 0) return null; // 没有 UserMessage 或只有当前 turn

        // 跳过开头的 boundary（如果有）
        int start = 0;
        if (isBoundary(messages.get(0))) {
            start = 1;
        }

        // 没有足够内容
        if (start >= lastUserIdx) return null;

        // 估算 token 数
        long tokens = 0;
        for (int i = start; i < lastUserIdx; i++) {
            tokens += TokenTracker.estimateTokens(messages.get(i));
        }

        return new SummaryTarget(start, lastUserIdx, tokens);
    }

    /**
     * 调用模型生成摘要。
     */
    private String callModel(List<Message> messages) {
        // 构建 prompt
        var promptMessages = new ArrayList<Message>();
        promptMessages.add(new SystemMessage("""
                请总结以下 Agent 对话历史，保留已完成步骤和关键发现。
                用中文简洁回复，不要添加新信息。
                """));
        promptMessages.addAll(messages);

        ChatResponse response = chatModel.call(new Prompt(promptMessages));
        String text = response.getResult().getOutput().getText();
        return text != null && !text.isBlank() ? text : "(empty summary)";
    }

    /**
     * 用摘要消息替换候选范围。
     * 结果 = [范围之前的内容] + [摘要 AssistantMessage] + [当前 turn 及之后]
     */
    private List<Message> buildResult(List<Message> messages, SummaryTarget target, String summary) {
        List<Message> result = new ArrayList<>();
        // 范围之前（boundary 或其他 preMessages）
        if (target.start() > 0) {
            result.addAll(messages.subList(0, target.start()));
        }
        // 摘要（AssistantMessage 可被下次再摘要）
        result.add(new AssistantMessage(
                "... (%d messages, ~%d tokens summarized) ...\n%s"
                        .formatted(target.end() - target.start(), target.tokens(), summary)));
        // 当前 turn
        result.addAll(messages.subList(target.end(), messages.size()));
        return result;
    }

    static boolean isBoundary(Message msg) {
        if (!(msg instanceof SystemMessage sm)) return false;
        String text = sm.getText();
        return text != null && text.startsWith("... (");
    }

    // ========================================================================
    //  数据容器
    // ========================================================================

    record SummaryTarget(int start, int end, long tokens) {}
}
