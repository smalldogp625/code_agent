package com.yu.agent4.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yu.agent4.config.AgentLoopProperties;
import com.yu.agent4.model.AgentLoopTurnResult;
import com.yu.agent4.model.ToolExecutionBatch;
import com.yu.agent4.model.ToolExecutionTrace;
import com.yu.agent4.tool.ToolRegistry;
import com.yu.agent4.tool.toolManager.TodoManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentLoopService {

    private final ChatModel chatModel;

    private final ToolRegistry toolRegistry;

    private final AgentLoopProperties properties;

    private final TodoManager todoManager;

    public AgentLoopService(
            @Qualifier("agentLoopChatModel") ChatModel chatModel,
            ToolRegistry toolRegistry,
            AgentLoopProperties properties,
            TodoManager todoManager) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.todoManager = todoManager;
    }

    public AgentLoopTurnResult runTurn(List<Message> history, String userInput) {
        todoManager.resetForNewTask();
        history.add(new UserMessage(userInput));
        List<ToolExecutionTrace> traces = new ArrayList<>();
        int roundsWithoutTodo = 0;
        boolean injectTodoKickoff = shouldEncourageTodoAtStart(userInput);

        for (int step = 1; step <= properties.getMaxSteps(); step++) {
            log.info("Agent step {}/{}: calling model", step, properties.getMaxSteps());

            ChatResponse response = chatModel.call(buildPrompt(
                    history,
                    step == 1 && injectTodoKickoff,
                    roundsWithoutTodo >= 3
            ));
            AssistantMessage assistantMessage = extractAssistantMessage(response);
            history.add(assistantMessage);

            if (!assistantMessage.hasToolCalls()) {
                log.info("Agent step {}/{}: no tool call, returning final answer", step, properties.getMaxSteps());
                return new AgentLoopTurnResult(assistantMessage.getText(), List.copyOf(traces));
            }

            log.info(
                    "Agent step {}/{}: model requested {} tool call(s)",
                    step,
                    properties.getMaxSteps(),
                    assistantMessage.getToolCalls().size()
            );

            ToolExecutionBatch executionBatch = toolRegistry.execute(assistantMessage.getToolCalls());
            traces.addAll(executionBatch.traces());
            history.add(executionBatch.toolResponseMessage());
            roundsWithoutTodo = containsTodoToolCall(assistantMessage) ? 0 : roundsWithoutTodo + 1;
        }

        throw new IllegalStateException("Tool loop exceeded max steps: " + properties.getMaxSteps());
    }

    /**
     * 启动一个子 Agent 独立执行指定任务，返回执行总结。
     * <p>
     * 子 Agent 拥有独立的对话上下文和受限的工具集（不含 task、todo 工具），
     * 执行过程中父 Agent 完全等待，子 Agent 返回最终文本后作为工具调用结果返回。
     * 父子上下文严格隔离，子 Agent 的中间步骤对外部不可见。
     *
     * @param taskDescription 子 Agent 需要完成的任务描述
     * @return 子 Agent 返回的执行总结，或错误信息
     */
    public String runTurnWithSubAgent(String taskDescription) {
        // 创建子 Agent 的受限工具注册表（排除 task、createTodo、updateTodo）
        ToolRegistry subRegistry = toolRegistry.copyExcluding(List.of("task", "createTodo", "updateTodo"));
        // 子 Agent 独立上下文，从零开始
        List<Message> subHistory = new ArrayList<>();
        subHistory.add(new UserMessage(taskDescription));

        // 简化的 agent 循环：模型调用 → 工具执行 → 重复，不含 todo 管理
        for (int step = 1; step <= properties.getMaxSteps(); step++) {
            log.info("Sub-agent step {}/{}: calling model", step, properties.getMaxSteps());

            ChatResponse response = chatModel.call(buildSubPrompt(subHistory, subRegistry.getToolCallbacks()));
            AssistantMessage assistantMessage = extractAssistantMessage(response);
            subHistory.add(assistantMessage);

            if (!assistantMessage.hasToolCalls()) {
                // 子 Agent 已完成任务，返回最终文本
                String text = assistantMessage.getText();
                String result = (text != null && !text.isBlank()) ? text : "(no summary)";
                log.info("Sub-agent completed at step {}: {}", step, result);
                return result;
            }

            log.info("Sub-agent step {}/{}: executing {} tool call(s)",
                    step, properties.getMaxSteps(), assistantMessage.getToolCalls().size());

            ToolExecutionBatch batch = subRegistry.execute(assistantMessage.getToolCalls());
            subHistory.add(batch.toolResponseMessage());
        }

        // 子 Agent 超过最大步数仍未完成
        log.warn("Sub-agent exceeded max steps ({})", properties.getMaxSteps());
        return "Error: Sub-agent exceeded max steps";
    }

    private Prompt buildPrompt(List<Message> history, boolean injectTodoKickoff, boolean injectTodoReminder) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(buildSystemPrompt(injectTodoKickoff, injectTodoReminder)));
        promptMessages.addAll(history);

        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model(properties.getModel())
                .maxToken(properties.getMaxTokens())
                .internalToolExecutionEnabled(false)
                .toolCallbacks(toolRegistry.getToolCallbacks())
                .build();

        return new Prompt(promptMessages, options);
    }

    /**
     * 构造子 Agent 的 Prompt，使用子 Agent 专用系统提示词和受限工具集。
     */
    private Prompt buildSubPrompt(List<Message> history, List<ToolCallback> toolCallbacks) {
        String toolNames = toolCallbacks.stream()
                .map(tc -> tc.getToolDefinition().name())
                .collect(Collectors.joining(", "));

        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(properties.getSubAgentSystemPrompt().formatted(toolNames)));
        promptMessages.addAll(history);

        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model(properties.getModel())
                .maxToken(properties.getMaxTokens())
                .internalToolExecutionEnabled(false)
                .toolCallbacks(toolCallbacks)
                .build();

        return new Prompt(promptMessages, options);
    }

    private AssistantMessage extractAssistantMessage(ChatResponse response) {
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            throw new IllegalStateException("Model returned an invalid assistant message");
        }
        return generation.getOutput();
    }

    private String buildSystemPrompt(boolean injectTodoKickoff, boolean injectTodoReminder) {
        String toolNames = toolRegistry.getToolCallbacks().stream()
                .map(toolCallback -> toolCallback.getToolDefinition().name())
                .collect(Collectors.joining(", "));
        String basePrompt = properties.getSystemPromptTemplate().formatted(
                System.getProperty("user.dir"),
                properties.getMaxSteps(),
                toolNames
        );

        StringBuilder promptBuilder = new StringBuilder(basePrompt);
        if (injectTodoKickoff) {
            promptBuilder.append("\n\nTodo kickoff reminder:\n")
                    .append("当前请求明显包含多个子任务、多个文件或多个阶段。")
                    .append("请先使用 createTodo 拆解任务，再进入读取、修改或执行步骤。")
                    .append("title 使用简短任务名，note 写明任务细节。");
        }
        if (injectTodoReminder) {
            promptBuilder.append("\n\nTodo reminder:\n")
                    .append("You have gone 3 rounds without using a todo tool. ")
                    .append("If the task has multiple steps, consider using createTodo or updateTodo to track progress. ")
                    .append("Only one todo may be in_progress at a time.\n")
                    .append(todoManager.renderState());
        }
        return promptBuilder.toString();
    }

    private boolean containsTodoToolCall(AssistantMessage assistantMessage) {
        return assistantMessage.getToolCalls().stream()
                .anyMatch(toolCall -> "createTodo".equals(toolCall.name()) || "updateTodo".equals(toolCall.name()));
    }

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
}
