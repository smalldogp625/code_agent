package com.yu.agent4.model;

import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

public record ToolExecutionBatch(ToolResponseMessage toolResponseMessage, List<ToolExecutionTrace> traces) {
}
