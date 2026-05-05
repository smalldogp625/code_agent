package com.yu.agent4.context.transcript;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Session Transcript 持久化存储 — 对应第9章 §9.1 Transcript Model。
 *
 * <p>三阶段演进：
 * <ol>
 *   <li><b>v1</b> — 仅落盘（{@link #create}、{@link #append}），{@link #load} 抛出异常</li>
 *   <li><b>v2</b> — 实现 {@link #load}，支持 {@code --resume} 从 JSONL 重建 List&lt;Message&gt;</li>
 *   <li><b>v3</b> — 支持 fork 会话链和跨会话引用</li>
 * </ol>
 */
public interface SessionStore {

    /**
     * 创建新会话 transcript 文件，写入 {@link TranscriptEvent.SessionMeta} 首行。
     *
     * @param sessionId       新会话 ID
     * @param parentSessionId fork 来源会话 ID；首次创建传 {@code null}
     */
    void create(String sessionId, String parentSessionId);

    /**
     * 追加一个事件到 transcript 文件末尾。
     *
     * @param sessionId 会话 ID
     * @param event     事件对象（序列化为一行 JSON）
     */
    void append(String sessionId, TranscriptEvent event);

    /**
     * 回放指定会话的 transcript，重建 {@link org.springframework.ai.chat.messages.Message} 列表。
     *
     * @param sessionId 会话 ID
     * @return 按原始顺序排列的消息列表
     * @throws UnsupportedOperationException v1 未实现
     */
    List<Message> load(String sessionId);
}
