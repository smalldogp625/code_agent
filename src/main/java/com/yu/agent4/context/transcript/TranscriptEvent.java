package com.yu.agent4.context.transcript;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Transcript 事件类型层次 — 对应第9章 append-only JSONL 的三种内容事件 + 一种元信息事件。
 *
 * <p>所有事件通过 {@code type} 字段区分，Jackson 多态序列化。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TranscriptEvent.SessionMeta.class, name = "session_meta"),
        @JsonSubTypes.Type(value = TranscriptEvent.User.class, name = "user"),
        @JsonSubTypes.Type(value = TranscriptEvent.Assistant.class, name = "assistant"),
        @JsonSubTypes.Type(value = TranscriptEvent.ToolResponseBatch.class, name = "tool_response_batch"),
})
public sealed interface TranscriptEvent {

    /** 事件类型标识，与 Jackson {@code type} 字段值一致 */
    String type();

    /**
     * 会话元信息 — JSONL 文件第一行。
     *
     * @param sessionId       当前会话 ID
     * @param parentSessionId fork 来源会话 ID（首次创建时为 null）
     * @param createdAt       创建时间（ISO-8601）
     */
    record SessionMeta(String sessionId, String parentSessionId, String createdAt) implements TranscriptEvent {
        @Override
        public String type() {
            return "session_meta";
        }
    }

    /**
     * 用户输入事件。
     *
     * @param turn    第几次 runTurn（从 1 递增）
     * @param content 用户输入原文
     */
    record User(int turn, String content) implements TranscriptEvent {
        @Override
        public String type() {
            return "user";
        }
    }

    /**
     * 模型回复事件（含工具调用声明）。
     *
     * @param turn      第几次 runTurn
     * @param step      当前 turn 内的第几步（从 1 递增）
     * @param content   模型回复文本（可能为 null）
     * @param toolCalls 工具调用列表（可能为空）
     */
    record Assistant(int turn, int step, String content, List<ToolCall> toolCalls) implements TranscriptEvent {
        @Override
        public String type() {
            return "assistant";
        }
    }

    /**
     * 整批工具执行结果事件。
     *
     * <p><b>始终在 BudgetCompactor 截断之前写入</b>，保证原文完整持久化。</p>
     *
     * @param turn      第几次 runTurn
     * @param step      当前 turn 内的第几步
     * @param responses 工具响应列表（含完整 responseData）
     */
    record ToolResponseBatch(int turn, int step, List<ToolResponse> responses) implements TranscriptEvent {
        @Override
        public String type() {
            return "tool_response_batch";
        }
    }

    /** 简化版工具调用声明，从 {@code AssistantMessage.ToolCall} 转换而来。 */
    record ToolCall(String id, String name, String arguments) {}

    /** 简化版工具响应，从 {@code ToolResponseMessage.ToolResponse} 转换而来。 */
    record ToolResponse(String id, String name, String data) {}
}
