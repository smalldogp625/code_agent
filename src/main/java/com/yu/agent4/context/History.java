package com.yu.agent4.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话历史 — 管理 Agent 交互过程中的消息列表。
 *
 * <p>历史中不含 {@link org.springframework.ai.chat.messages.SystemMessage}，
 * {@link ContextAssembler} 在组装 Prompt 时单独添加。
 */
public class History {

    private final List<Message> messages = new ArrayList<>();

    public void addUserMessage(String text) {
        messages.add(new UserMessage(text));
    }

    public void addAssistantMessage(AssistantMessage message) {
        messages.add(message);
    }

    public void addToolResponseMessage(ToolResponseMessage message) {
        messages.add(message);
    }

    public void clear() {
        messages.clear();
    }

    /**
     * @return 历史消息的只读视图
     */
    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public int size() {
        return messages.size();
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }
}
