package com.yu.agent4.demo;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yu.agent4.config.AgentLoopProperties;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 最简单的模型连通性示例。
 * 这个类保留 demo 的角色，适合单独验证 DashScope 文本对话是否通。
 */
@Component
public class ChatAgentDemo {

    private final ChatModel chatModel;

    private final AgentLoopProperties properties;

    public ChatAgentDemo(@Qualifier("agentLoopChatModel") ChatModel chatModel,
                         AgentLoopProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    public String simpleChat(String userPrompt) {
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage("你是一个简洁的中文助手。"),
                        new UserMessage(userPrompt)
                ),
                DashScopeChatOptions.builder()
                        .model(properties.getModel())
                        .maxToken(properties.getMaxTokens())
                        .build()
        );

        ChatResponse response = chatModel.call(prompt);
        return response.getResult() == null ? "" : response.getResult().getOutput().getText();
    }
}
