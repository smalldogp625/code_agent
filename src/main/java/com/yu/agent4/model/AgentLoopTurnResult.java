package com.yu.agent4.model;

import java.util.List;

public record AgentLoopTurnResult(String finalAnswer, List<ToolExecutionTrace> traces) {
}
