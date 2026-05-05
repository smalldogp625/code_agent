package com.yu.agent4.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 上下文窗口 & 压缩管线配置。
 * <p>
 * 三个核心概念的区分：
 * <ul>
 *   <li><b>windowSize</b> — 模型总上下文窗口大小（输入+输出），从模型规格获得</li>
 *   <li><b>阈值</b> — windowSize × compactionRatio，当输入接近此值时触发压缩</li>
 *   <li><b>目标</b> — windowSize × targetRatio，压缩到多少 token 以下</li>
 * </ul>
 * 输出 token 上限由 {@link AgentLoopProperties#maxTokens} 单独管理。
 */
@ConfigurationProperties(prefix = "agent.context")
public class AgentContextProperties {

    /** 模型总上下文窗口大小（token），从模型规格获得。如 qwen-max 为 131072 */
    private int windowSize = 131_072;

    /** 压缩触发阈值比例（相对 windowSize）。达到 windowSize × compactionRatio 时触发压缩 */
    private double compactionRatio = 0.7;

    /** 压缩目标比例（相对 windowSize）。压缩到 windowSize × targetRatio 以下 */
    private double targetRatio = 0.4;

    /** SnipCompactor 最少保留的已完成完整 turn 数 */
    private int minRecentTurns = 3;

    /** SummaryCompactor 最小 token 数，低于此值不调模型做摘要 */
    private int minTokensToSummarize = 500;

    /** BudgetCompactor 单条工具响应最大字符数，超出后截断/落盘 */
    private int budgetMaxChars = 3000;

    /** 按工具名独立配置的预算，未覆盖的工具使用 budgetMaxChars，{@code -1} 表示永不截断 */
    private Map<String, Integer> toolBudgets = new HashMap<>();

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public double getCompactionRatio() {
        return compactionRatio;
    }

    public void setCompactionRatio(double compactionRatio) {
        this.compactionRatio = compactionRatio;
    }

    public double getTargetRatio() {
        return targetRatio;
    }

    public void setTargetRatio(double targetRatio) {
        this.targetRatio = targetRatio;
    }

    public int getMinRecentTurns() {
        return minRecentTurns;
    }

    public void setMinRecentTurns(int minRecentTurns) {
        this.minRecentTurns = minRecentTurns;
    }

    public int getMinTokensToSummarize() {
        return minTokensToSummarize;
    }

    public void setMinTokensToSummarize(int minTokensToSummarize) {
        this.minTokensToSummarize = minTokensToSummarize;
    }

    public int getBudgetMaxChars() {
        return budgetMaxChars;
    }

    public void setBudgetMaxChars(int budgetMaxChars) {
        this.budgetMaxChars = budgetMaxChars;
    }

    public Map<String, Integer> getToolBudgets() {
        return toolBudgets;
    }

    public void setToolBudgets(Map<String, Integer> toolBudgets) {
        this.toolBudgets = toolBudgets;
    }

    // ========================================================================
    //  计算属性 — 由 windowSize + 比例推导
    // ========================================================================

    /** 压缩触发阈值 = windowSize × compactionRatio */
    public int computeCompactionThreshold() {
        return (int) (windowSize * compactionRatio);
    }

    /** 压缩目标 = windowSize × targetRatio */
    public int computeCompactionTarget() {
        return (int) (windowSize * targetRatio);
    }
}
