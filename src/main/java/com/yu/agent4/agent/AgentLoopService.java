package com.yu.agent4.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yu.agent4.config.AgentLoopProperties;
import com.yu.agent4.model.AgentLoopTurnResult;
import com.yu.agent4.model.ToolExecutionBatch;
import com.yu.agent4.model.ToolExecutionTrace;
import com.yu.agent4.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentLoopService {

    private final ChatModel chatModel;

    private final ToolRegistry toolRegistry;

    private final AgentLoopProperties properties;

    public AgentLoopService(
            @Qualifier("agentLoopChatModel") ChatModel chatModel,
            ToolRegistry toolRegistry,
            AgentLoopProperties properties) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    public AgentLoopTurnResult runTurn(List<Message> history, String userInput) {
        history.add(new UserMessage(userInput));
        List<ToolExecutionTrace> traces = new ArrayList<>();

        for (int step = 1; step <= properties.getMaxSteps(); step++) {
            log.info("Agent step {}/{}: calling model", step, properties.getMaxSteps());

            ChatResponse response = chatModel.call(buildPrompt(history));
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
        }

        throw new IllegalStateException("工具循环超过最大步数: " + properties.getMaxSteps());
    }

    private Prompt buildPrompt(List<Message> history) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(buildSystemPrompt()));
        promptMessages.addAll(history);

        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model(properties.getModel())
                .maxToken(properties.getMaxTokens())
                .internalToolExecutionEnabled(false)
                .toolCallbacks(toolRegistry.getToolCallbacks())
                .build();

        return new Prompt(promptMessages, options);
    }

    private AssistantMessage extractAssistantMessage(ChatResponse response) {
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            throw new IllegalStateException("模型没有返回有效的 assistant 消息");
        }
        return generation.getOutput();
    }

    private String buildSystemPrompt() {
        String toolNames = toolRegistry.getToolCallbacks().stream()
                .map(toolCallback -> toolCallback.getToolDefinition().name())
                .collect(Collectors.joining(", "));
        return properties.getSystemPromptTemplate().formatted(
                System.getProperty("user.dir"),
                properties.getMaxSteps(),
                toolNames
        );
    }
}
