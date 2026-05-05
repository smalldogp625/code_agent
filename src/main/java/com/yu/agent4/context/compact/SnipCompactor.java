package com.yu.agent4.context.compact;

import com.yu.agent4.context.TokenTracker;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史滑窗压缩器 — Snip Reduction。
 * 以 turn（对话轮次）为单位裁剪旧历史，保留最近的 N 轮对话。
 *
 * <p><b>裁剪粒度是 turn 而非单条 Message</b>，确保不会切断 Assistant-ToolResponse 配对。
 *
 * <p>保护策略（见 {@link #selectRetainedTurns(ScanResult, long, List)}）：
 * <ul>
 *   <li>当前进行中的 turn 全部保护</li>
 *   <li>最近 {@code minRecentTurns} 个已完成 turn 为最低保护</li>
 *   <li>在 targetTokens 预算内向更早的已完成 turn 扩展</li>
 *   <li>保护区本身已超 targetTokens → 不处理（标记 insufficient）</li>
 * </ul>
 *
 * <p>生效顺序：先执行 {@link BudgetCompactor}（裁每条消息体），再执行此压缩器（裁消息总量）。
 */
public class SnipCompactor implements Compactor {

    private static final String SNIP_MARKER_PREFIX = "... (";

    private static final String SNIP_BOUNDARY_TEMPLATE =
            "... (%d messages, ~%d tokens omitted by context compaction) ...";

    private final int minRecentTurns;

    public SnipCompactor() {
        this(3);
    }

    /**
     * @param minRecentTurns 最少保留的已完成完整 turn 数，必须 &gt; 0
     */
    public SnipCompactor(int minRecentTurns) {
        if (minRecentTurns <= 0) {
            throw new IllegalArgumentException("minRecentTurns must be > 0, got: " + minRecentTurns);
        }
        this.minRecentTurns = minRecentTurns;
    }

    @Override
    public List<Message> compact(List<Message> messages, CompactionContext context) {
        if (messages.isEmpty()) {
            return messages;
        }

        // 1. 扫描边界
        ScanResult scan = scanMessages(messages);

        // 2. 裁剪决策（使用 context 传入的压缩目标值，而非 tokenThreshold 的一半）
        long targetTokens = context.tokenTarget();
        RetainResult retain = selectRetainedTurns(scan, targetTokens, messages);

        // 3. 无可裁内容或不满足条件 → 原样返回
        if (retain.isInsufficient() || retain.newRemovedCount() == 0) {
            return messages;
        }

        // 4. 组装结果
        return buildResult(messages, scan, retain);
    }

    @Override
    public int order() {
        return 20;
    }

    // ========================================================================
    //  Step 1: 边界扫描
    // ========================================================================

    /**
     * 扫描消息列表，提取 preMessages、已完成 turn、当前 turn 和旧 SnipBoundary 计数。
     * <p>
     * preMessages — 开头不属于任何 turn 的消息（如旧的 SnipBoundary）
     * completedTurns — 从第一个 UserMessage 到最后一个 UserMessage 之间按 turn 切分
     * currentTurn — 最后一个 UserMessage 到末尾（当前正在进行的 turn）
     */
    static ScanResult scanMessages(List<Message> messages) {
        // 查找第一个 UserMessage → preMessages 边界
        int firstUserIdx = -1;
        int oldBoundaryCount = 0;
        List<Message> preMessages = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage) {
                firstUserIdx = i;
                break;
            }
            preMessages.add(msg);
            oldBoundaryCount += extractBoundaryMessageCount(msg);
        }

        // 没有 UserMessage → 全是 preMessages，无法识别 turn
        if (firstUserIdx < 0) {
            return new ScanResult(messages, List.of(), null, oldBoundaryCount);
        }

        // 查找最后一个 UserMessage（当前 turn 起点）
        int lastUserIdx = messages.size() - 1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUserIdx = i;
                break;
            }
        }

        // 已完成 turn：[firstUserIdx, lastUserIdx)
        List<Turn> completedTurns = splitTurns(messages, firstUserIdx, lastUserIdx);

        // 当前 turn：[lastUserIdx, end)
        List<Message> currentMessages = listCopy(messages, lastUserIdx, messages.size());
        long currentTokens = estimateMessages(currentMessages);
        Turn currentTurn = new Turn(lastUserIdx, messages.size(), currentTokens);

        return new ScanResult(preMessages, completedTurns, currentTurn, oldBoundaryCount);
    }

    /**
     * 将指定范围 [start, endExcl) 内的消息按 UserMessage 切分为 turn。
     * 每个 turn 从 UserMessage 开始，到下一个 UserMessage（不含）或 endExcl 结束。
     */
    private static List<Turn> splitTurns(List<Message> messages, int start, int endExcl) {
        List<Turn> turns = new ArrayList<>();
        int turnStart = start;
        for (int i = start; i < endExcl; i++) {
            if (i > turnStart && messages.get(i) instanceof UserMessage) {
                turns.add(buildTurn(messages, turnStart, i));
                turnStart = i;
            }
        }
        if (turnStart < endExcl) {
            turns.add(buildTurn(messages, turnStart, endExcl));
        }
        return turns;
    }

    private static Turn buildTurn(List<Message> messages, int start, int end) {
        long tokens = 0;
        for (int i = start; i < end; i++) {
            tokens += TokenTracker.estimateTokens(messages.get(i));
        }
        return new Turn(start, end, tokens);
    }

    // ========================================================================
    //  Step 2: 裁剪决策
    // ========================================================================

    /**
     * 基于 targetTokens 决定保留哪些 turn。
     * <p>
     * 算法：
     * <ol>
     *   <li>强保护：当前 turn + 最后 {@code minRecentTurns} 个已完成 turn</li>
     *   <li>估算保护区 token 数，如果已超 target → 返回 insufficient（降级给其他 compactor）</li>
     *   <li>从旧到新把更早的已完成 turn 逐个加回，每加一个重新估算，不超就保留</li>
     *   <li>返回最终保留列表 + 释放量统计</li>
     * </ol>
     */
    RetainResult selectRetainedTurns(ScanResult scan, long targetTokens, List<Message> messages) {
        if (scan.currentTurn() == null) {
            return RetainResult.insufficient();
        }

        List<Turn> completed = scan.completedTurns();
        int nCompleted = completed.size();
        int protectCount = Math.min(minRecentTurns, nCompleted);

        // 保护区：[最后 protectCount 个已完成 turn] + [当前 turn]
        List<Turn> retainedCompleted = new ArrayList<>();
        long baselineTokens = scan.currentTurn().tokenEstimate();
        for (int i = nCompleted - protectCount; i < nCompleted; i++) {
            Turn turn = completed.get(i);
            retainedCompleted.add(turn);
            baselineTokens += turn.tokenEstimate();
        }

        if (baselineTokens > targetTokens) {
            return RetainResult.insufficient();
        }

        // 从旧到新试加更早的已完成 turn
        long currentTotal = baselineTokens;
        int earliestKept = nCompleted - protectCount;
        for (int i = earliestKept - 1; i >= 0; i--) {
            Turn earlierTurn = completed.get(i);
            if (currentTotal + earlierTurn.tokenEstimate() <= targetTokens) {
                retainedCompleted.add(0, earlierTurn); // prepend
                currentTotal += earlierTurn.tokenEstimate();
                earliestKept = i;
            } else {
                break;
            }
        }

        // 统计释放量
        int newRemovedCount = earliestKept;
        long originalTotal = currentTotal;
        for (int i = 0; i < earliestKept; i++) {
            originalTotal += completed.get(i).tokenEstimate();
        }
        long tokensFreed = originalTotal - currentTotal;

        // 完整保留列表：[保留的已完成 turn] + [当前 turn]
        List<Turn> allRetained = new ArrayList<>(retainedCompleted);
        allRetained.add(scan.currentTurn());

        return new RetainResult(allRetained, newRemovedCount, tokensFreed, false);
    }

    // ========================================================================
    //  Step 3: 组装结果
    // ========================================================================

    /**
     * 组装最终的消息列表：
     * [非 boundary 的 preMessages] + [新 SnipBoundary] + [保留 turn 的全部消息]
     */
    static List<Message> buildResult(List<Message> messages, ScanResult scan, RetainResult retain) {
        List<Message> result = new ArrayList<>();

        // 1. PreMessages（跳过旧的 boundary）
        for (Message msg : scan.preMessages()) {
            if (!isBoundary(msg)) {
                result.add(msg);
            }
        }

        // 2. 新 SnipBoundary
        int totalRemoved = scan.oldBoundaryCount() + retain.newRemovedCount();
        result.add(new SystemMessage(
                SNIP_BOUNDARY_TEMPLATE.formatted(totalRemoved, retain.tokensFreed())));

        // 3. 保留 turn 的消息（按原始顺序平铺）
        for (Turn turn : retain.retainedTurns()) {
            for (int i = turn.firstMsgIdx(); i < turn.endIdxExcl(); i++) {
                result.add(messages.get(i));
            }
        }

        return result;
    }

    // ========================================================================
    //  内部工具方法
    // ========================================================================

    private static long estimateMessages(List<Message> messages) {
        long total = 0;
        for (Message msg : messages) {
            total += TokenTracker.estimateTokens(msg);
        }
        return total;
    }

    /** 范围拷贝，避免 subList 视图的副作用 */
    private static List<Message> listCopy(List<Message> source, int from, int to) {
        List<Message> copy = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            copy.add(source.get(i));
        }
        return copy;
    }

    /**
     * 判断一条消息是否为 SnipBoundary。
     * <p>
     * 兼容旧格式 {@code "... (X messages removed by context compaction) ..."} 和新格式。
     */
    static boolean isBoundary(Message msg) {
        if (!(msg instanceof SystemMessage sm)) {
            return false;
        }
        String text = sm.getText();
        return text != null && text.startsWith(SNIP_MARKER_PREFIX);
    }

    /**
     * 从旧的 SnipBoundary 文本中提取已省略的消息条数。
     * 第一条目（X）即为消息数，支持新旧格式。
     */
    static int extractBoundaryMessageCount(Message msg) {
        if (!(msg instanceof SystemMessage sm)) {
            return 0;
        }
        String text = sm.getText();
        if (text == null || !text.startsWith(SNIP_MARKER_PREFIX)) {
            return 0;
        }
        try {
            int startIdx = SNIP_MARKER_PREFIX.length();
            int endIdx = text.indexOf(' ', startIdx);
            if (endIdx < 0) return 0;
            return Integer.parseInt(text.substring(startIdx, endIdx));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ========================================================================
    //  数据容器
    // ========================================================================

    /** 一个对话轮次：在原始消息列表中的索引范围及估算 token 数 */
    record Turn(int firstMsgIdx, int endIdxExcl, long tokenEstimate) {
        int messageCount() { return endIdxExcl - firstMsgIdx; }
    }

    /** 扫描结果 */
    record ScanResult(List<Message> preMessages, List<Turn> completedTurns, Turn currentTurn, int oldBoundaryCount) {}

    /** 裁剪决策结果 */
    record RetainResult(List<Turn> retainedTurns, int newRemovedCount, long tokensFreed, boolean isInsufficient) {
        static RetainResult insufficient() {
            return new RetainResult(List.of(), 0, 0, true);
        }
    }
}
