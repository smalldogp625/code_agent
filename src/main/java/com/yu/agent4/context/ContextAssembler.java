package com.yu.agent4.context;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yu.agent4.config.AgentLoopProperties;
import com.yu.agent4.tool.ToolRegistry;
import com.yu.agent4.tool.toolManager.TodoManager;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文组装器，负责将系统提示词、对话历史、工具注册表、Todo 状态等
 * 碎片拼装为最终发送给模型的 {@link Prompt}。
 * <p>
 * 对应论文 §7.1 Context Window Assembly 中的组装过程。
 * 原代码从 {@code AgentLoopService} 内联方法抽取为独立组件。
 */
@Component
public class ContextAssembler {

    private final AgentLoopProperties properties;

    private final ToolRegistry toolRegistry;

    private final TodoManager todoManager;

    public ContextAssembler(AgentLoopProperties properties, ToolRegistry toolRegistry, TodoManager todoManager) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.todoManager = todoManager;
    }

    /**
     * 为主 Agent 循环组装 Prompt。
     *
     * @param history            对话历史（不含 system message）
     * @param injectTodoKickoff  是否注入 todo 拆解提示
     * @param injectTodoReminder 是否注入 todo 超时提醒
     * @return 可用于 {@code chatModel.call()} 的 Prompt
     */
    public Prompt assembleMain(List<Message> history, boolean injectTodoKickoff, boolean injectTodoReminder) {
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
     * 为子 Agent 循环组装 Prompt。
     *
     * @param history      子 Agent 的独立对话历史
     * @param toolCallbacks 子 Agent 的受限工具回调列表
     * @return 可用于 {@code chatModel.call()} 的 Prompt
     */
    public Prompt assembleSub(List<Message> history, List<ToolCallback> toolCallbacks) {
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

    /**
     * 构建主 Agent 的系统提示词文本，包含基础模板和可选注入。
     */
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
}
