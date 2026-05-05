package com.yu.agent4.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.yu.agent4.context.History;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.yu.agent4.config.AgentContextProperties;
import com.yu.agent4.config.AgentLoopProperties;
import com.yu.agent4.context.ContextAssembler;
import com.yu.agent4.context.TokenTracker;
import com.yu.agent4.context.ToolResponseResult;
import com.yu.agent4.context.compact.BudgetCompactor;
import com.yu.agent4.context.compact.CompactionContext;
import com.yu.agent4.context.compact.SnipCompactor;
import com.yu.agent4.context.compact.SummaryCompactor;
import com.yu.agent4.context.transcript.SessionStore;
import com.yu.agent4.context.transcript.TranscriptEvent;
import com.yu.agent4.model.AgentLoopTurnResult;
import com.yu.agent4.model.ToolExecutionBatch;
import com.yu.agent4.model.ToolExecutionTrace;
import com.yu.agent4.tool.ToolRegistry;
import com.yu.agent4.tool.toolManager.TodoManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Agent 主循环 — 论文 Agent Loop 组件的核心实现。
 *
 * <p>设计哲学（参见 arXiv:2604.14228v1 §3 Architecture Overview）：
 * <ul>
 *   <li><b>决策与设施分离</b> — 循环本身不包含决策逻辑，
 *       只编排「模型决策 → 工具执行 → 观察结果」的闭环。
 *       所有「下一步做什么」的判断交由模型，代码仅承载设施。</li>
 *   <li><b>最小化编排</b> — 循环只关心「何时继续、何时终止」，
 *       不关心 prompt 组装、token 统计、工具注册等非编排职责。
 *       这些由独立组件承担：{@link ContextAssembler}、{@link TokenTracker}、{@link ToolRegistry}。</li>
 *   <li><b>主/子 Agent 同构</b> — 两者循环结构完全一致，
 *       差异点（工具集、todo 管理）通过参数注入而非继承/分支实现。</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentLoopService {

    private final ChatModel chatModel;

    private final ToolRegistry toolRegistry;

    private final AgentLoopProperties properties;

    private final AgentContextProperties contextProperties;

    private final TodoManager todoManager;

    private final ContextAssembler contextAssembler;

    private final BudgetCompactor budgetCompactor;

    private final SnipCompactor snipCompactor;

    private final SummaryCompactor summaryCompactor;

    private final SessionStore sessionStore;

    /** 会话内 turn 计数器：sessionId → 当前 turn 序号（从 1 开始递增） */
    private final Map<String, Integer> sessionTurnCounters = new ConcurrentHashMap<>();

    public AgentLoopService(
            @Qualifier("agentLoopChatModel") ChatModel chatModel,
            ToolRegistry toolRegistry,
            AgentLoopProperties properties,
            AgentContextProperties contextProperties,
            TodoManager todoManager,
            ContextAssembler contextAssembler,
            SessionStore sessionStore) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.contextProperties = contextProperties;
        this.todoManager = todoManager;
        this.contextAssembler = contextAssembler;
        this.sessionStore = sessionStore;
        this.budgetCompactor = new BudgetCompactor(
                contextProperties.getBudgetMaxChars(), contextProperties.getToolBudgets());
        this.snipCompactor = new SnipCompactor(contextProperties.getMinRecentTurns());
        this.summaryCompactor = new SummaryCompactor(chatModel, contextProperties.getMinTokensToSummarize());
    }

    // ========================================================================
    //  主 Agent 循环 — 面向外部用户的完整交互
    // ========================================================================

    /**
     * 执行一轮主 Agent 交互。
     *
     * <p>编排流程：
     * <ol>
     *   <li><b>初始化</b> — 重置 todo 状态、追加用户输入、初始化追踪器</li>
     *   <li><b>Step 迭代</b> — 每步包含一次完整的「决策→行动→观察」闭环</li>
     *   <li><b>终止</b> — 模型不再调用工具时返回最终回答；超限时抛异常</li>
     * </ol>
     *
     * @param history   对话历史（会被修改，追加本轮所有消息）
     * @param userInput 用户本轮输入
     * @param sessionId 当前上下文窗口的会话标识；同一用户/同一历史对应同一 sessionId
     * @return 模型最终回答与工具执行轨迹
     */
    public AgentLoopTurnResult runTurn(List<Message> history, String userInput, String sessionId) {
        // ---- 初始化：重置状态、追加用户输入 ----
        todoManager.resetForNewTask();
//        sessionStore.create(sessionId, null);
//        List<Message> messages = sessionStore.load("db70a7d2");
//        history.addAll(messages);
        history.add(new UserMessage(userInput));

        List<ToolExecutionTrace> traces = new ArrayList<>();
        int roundsWithoutTodo = 0;
        boolean injectTodoKickoff = shouldEncourageTodoAtStart(userInput);
        TokenTracker tokenTracker = new TokenTracker();
        int turnId = sessionTurnCounters.merge(sessionId, 1, Integer::sum);

        sessionStore.append(sessionId, new TranscriptEvent.User(turnId, userInput));

        log.info("[{}][turn-{}] Session started — userInput: \"{}\"",
                sessionId, turnId, truncate(userInput, 120));

        // ---- Step 迭代：决策→行动→观察 ----
        for (int step = 1; step <= properties.getMaxSteps(); step++) {
            log.info("[{}][turn-{}][step-{}] === Step begin ===", sessionId, turnId, step);

            /* 1. 上下文组装 — 将历史、系统提示、工具列表注入当前 step */
            Prompt prompt = contextAssembler.assembleMain(
                    history,
                    step == 1 && injectTodoKickoff,
                    roundsWithoutTodo >= 3
            );

            /* 2. 模型决策 — 选择工具调用或直接回复 */
            ChatResponse response = chatModel.call(prompt);
            //统计token数量
            tokenTracker.record(response);
            tokenTracker.logStep(log, step, properties.getMaxSteps(), response);

            AssistantMessage assistantMessage = extractAssistantMessage(response);
            history.add(assistantMessage);
            sessionStore.append(sessionId, new TranscriptEvent.Assistant(
                    turnId, step, assistantMessage.getText(),
                    assistantMessage.getToolCalls().stream()
                            .map(tc -> new TranscriptEvent.ToolCall(tc.id(), tc.name(), tc.arguments()))
                            .toList()));

            /* 3. 终止条件 — 模型不再调用工具 → 压缩后返回最终回答 */
            if (!assistantMessage.hasToolCalls()) {
                log.info("sessionId: [{}][turn-{}] Agent finished — no tool calls", sessionId, turnId);
                return new AgentLoopTurnResult(assistantMessage.getText(), List.copyOf(traces));
            }

            /* 4. 工具执行与观察 — 执行工具、记录轨迹、追加结果到历史 */
            ToolExecutionBatch executionBatch = toolRegistry.execute(assistantMessage.getToolCalls());
            traces.addAll(executionBatch.traces());

            // 持工具完整原文，再截断
            sessionStore.append(sessionId, new TranscriptEvent.ToolResponseBatch(
                    turnId, step,
                    executionBatch.toolResponseMessage().getResponses().stream()
                            .map(r -> new TranscriptEvent.ToolResponse(r.id(), r.name(), r.responseData()))
                            .toList()));

            // 预算削减：压缩每条工具响应
            var originalResponses = executionBatch.toolResponseMessage().getResponses();
            List<ToolResponseMessage.ToolResponse> compacted = new ArrayList<>(originalResponses.size());
            for (var resp : originalResponses) {
                // 先记录原始结果摘要
                ToolResponseResult result = ToolResponseResult.of(
                        sessionId, turnId, resp.id(), resp.name(), resp.responseData());
                log.info("[{}][turn-{}][tool-{}] {} — {} chars",
                        sessionId, turnId, resp.name(), result.toolId(), result.originalLength());

                // 再执行预算压缩
                compacted.add(budgetCompactor.compact(resp, sessionId, turnId));
            }
            ToolResponseMessage compactedMessage = ToolResponseMessage.builder()
                    .responses(compacted)
                    .build();
            history.add(compactedMessage);
            roundsWithoutTodo = containsTodoToolCall(assistantMessage) ? 0 : roundsWithoutTodo + 1;
            
            // 上下文压缩：在 turn 结束后释放 token 预算（仅对主 Agent 生效）
            runCompactionPipeline(history);
            // 工具执行摘要
            int toolCallCount = assistantMessage.getToolCalls().size();
            log.info("[{}][turn-{}][step-{}] Tools executed: {}, cumulative traces: {}",
                    sessionId, turnId, step, toolCallCount, traces.size());


        }

        throw new IllegalStateException("[" + sessionId + "][turn-" + turnId + "] Tool loop exceeded max steps: " + properties.getMaxSteps());
    }

    /**
     * 重置指定 session 的 turn 计数器。
     * 用于 /clear 等需要开启全新上下文窗口的场景。
     */
    public void resetSession(String sessionId) {
        sessionTurnCounters.remove(sessionId);
    }

    // ========================================================================
    //  子 Agent 循环 — 独立沙箱，父子上下文严格隔离
    // ========================================================================

    /**
     * 启动子 Agent 独立执行指定任务，返回执行总结。
     *
     * <p>子 Agent 拥有独立的对话上下文和受限工具集（不含 task/todo 工具），
     * 父子上下文严格隔离，中间步骤对外不可见。
     * 循环结构与主 Agent 同构，但省略 todo 追踪等主 Agent 专属逻辑。
     *
     * @param taskDescription 子 Agent 任务描述
     * @return 执行总结，或错误信息
     */
    public String runTurnWithSubAgent(String taskDescription) {
        // ---- 初始化：受限工具注册表、独立上下文 ----
        ToolRegistry subRegistry = toolRegistry.copyExcluding(List.of("task", "createTodo", "updateTodo"));
        List<Message> subHistory = new ArrayList<>();
        subHistory.add(new UserMessage(taskDescription));
        TokenTracker subTokenTracker = new TokenTracker();

        // ---- Step 迭代（结构与主 Agent 一致） ----
        for (int step = 1; step <= properties.getMaxSteps(); step++) {
            /* 1. 上下文组装 */
            Prompt prompt = contextAssembler.assembleSub(subHistory, subRegistry.getToolCallbacks());

            /* 2. 模型决策 */
            ChatResponse response = chatModel.call(prompt);
            subTokenTracker.record(response);
            subTokenTracker.logStep(log, step, properties.getMaxSteps(), response);

            AssistantMessage assistantMessage = extractAssistantMessage(response);
            subHistory.add(assistantMessage);

            /* 3. 终止条件 */
            if (!assistantMessage.hasToolCalls()) {
                String text = assistantMessage.getText();
                String result = (text != null && !text.isBlank()) ? text : "(no summary)";
                log.info("Sub-agent completed at step {}: {}", step, result);
                return result;
            }

            /* 4. 工具执行 */
            ToolExecutionBatch batch = subRegistry.execute(assistantMessage.getToolCalls());
            subHistory.add(batch.toolResponseMessage());
        }

        log.warn("Sub-agent exceeded max steps ({})", properties.getMaxSteps());
        return "Error: Sub-agent exceeded max steps";
    }

    // ========================================================================
    //  上下文压缩管道
    // ========================================================================

    /**
     * 在每轮 turn 结束后执行上下文压缩，释放 token 预算供后续轮次使用。
     *
     * <p>管道顺序：
     * <ol>
     *   <li><b>SnipCompactor</b> — 以 turn 为单位裁剪旧历史，优先保护最近 N 轮对话</li>
     *   <li><b>SummaryCompactor</b> — 兜底：当 Snip 因保护区超预算而无法释放时，
     *       对保护区外的已完成 turn 做 AI 摘要，用摘要消息替换原始内容</li>
     * </ol>
     *
     * <p>BudgetCompactor 不在此处执行 — 它在 step 循环内对每条工具响应单独做截断/落盘，
     * 属于即时压缩，与本管道的轮次后压缩职责分离。
     */
    private void runCompactionPipeline(List<Message> history) {
        CompactionContext ctx = CompactionContext.of(
                contextProperties.computeCompactionThreshold(),
                contextProperties.computeCompactionTarget(),
                history.size());

        // 1. Snip：裁剪旧 turn
        List<Message> afterSnip = snipCompactor.compact(history, ctx);
        if (afterSnip != history) {
            log.info("[pipeline] SnipCompactor released tokens, history: {} → {} messages",
                    history.size(), afterSnip.size());
            replaceHistory(history, afterSnip);
            return;
        }

        // 2. Snip 无释放（insufficient 或无可裁内容）→ Summary 兜底
        List<Message> afterSummary = summaryCompactor.compact(history, ctx);
        if (afterSummary != history) {
            log.info("[pipeline] SummaryCompactor summarized history: {} → {} messages",
                    history.size(), afterSummary.size());
            replaceHistory(history, afterSummary);
        } else {
            log.info("[pipeline] Snip insufficient and Summary skipped — history unchanged ({} msgs)",
                    history.size());
        }
    }

    /**
     * 用新消息列表替换原列表内容，使 caller 持有的引用仍有效。
     */
    private static void replaceHistory(List<Message> history, List<Message> newHistory) {
        history.clear();
        history.addAll(newHistory);
    }

    // ========================================================================
    //  内部工具方法
    // ========================================================================

    /**
     * 从模型响应中提取 AssistantMessage，空响应时快速失败。
     */
    private AssistantMessage extractAssistantMessage(ChatResponse response) {
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            throw new IllegalStateException("Model returned an invalid assistant message");
        }
        return generation.getOutput();
    }

    /**
     * 判断模型本轮是否调用了 todo 相关工具。
     */
    private boolean containsTodoToolCall(AssistantMessage assistantMessage) {
        return assistantMessage.getToolCalls().stream()
                .anyMatch(toolCall -> "createTodo".equals(toolCall.name()) || "updateTodo".equals(toolCall.name()));
    }

    // ========================================================================
    //  启发式：判断用户输入是否需要 todo 拆解提示
    // ========================================================================

    private boolean shouldEncourageTodoAtStart(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }

        String normalizedInput = userInput.toLowerCase(Locale.ROOT);
        int score = 0;

        if (containsAny(normalizedInput, "并且", "以及", "然后", "同时", ";", "；")) {
            score++;
        }
        if (containsAny(normalizedInput, "所有", "全部", "整个目录", "整个项目", "批量")) {
            score++;
        }
        if (countActionHits(normalizedInput) >= 2) {
            score++;
        }
        if (countFileIndicators(normalizedInput) >= 2) {
            score++;
        }

        return score >= 2;
    }

    private int countActionHits(String normalizedInput) {
        int hits = 0;
        if (containsAny(normalizedInput, "重构", "refactor")) {
            hits++;
        }
        if (containsAny(normalizedInput, "创建", "新增", "create")) {
            hits++;
        }
        if (containsAny(normalizedInput, "修改", "更改", "edit", "update")) {
            hits++;
        }
        if (containsAny(normalizedInput, "审查", "检查", "修复", "review", "fix")) {
            hits++;
        }
        if (containsAny(normalizedInput, "添加", "补充", "add")) {
            hits++;
        }
        return hits;
    }

    private int countFileIndicators(String normalizedInput) {
        int hits = 0;
        if (normalizedInput.contains(".py")) {
            hits++;
        }
        if (normalizedInput.contains(".java")) {
            hits++;
        }
        if (normalizedInput.contains(".js")) {
            hits++;
        }
        if (normalizedInput.contains(".ts")) {
            hits++;
        }
        if (normalizedInput.contains(".md")) {
            hits++;
        }
        if (containsAny(normalizedInput, "__init__", "tests/", "src/", "package", "文件")) {
            hits++;
        }
        return hits;
    }

    private boolean containsAny(String input, String... candidates) {
        for (String candidate : candidates) {
            if (input.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 截断长文本用于日志，避免日志行被撑爆 */
    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
