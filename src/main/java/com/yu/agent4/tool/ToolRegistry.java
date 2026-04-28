package com.yu.agent4.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent4.model.ToolExecutionBatch;
import com.yu.agent4.model.ToolExecutionTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ToolRegistry {

    private static final int TRACE_OUTPUT_PREVIEW_CHARS = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, ToolCallback> toolCallbackMap = new LinkedHashMap<>();

    @Autowired
    public ToolRegistry(BashTool bashTool) {
        this(new Object[]{bashTool});
    }

    ToolRegistry(Object[] toolBeans) {
        for (Object toolBean : toolBeans) {
            register(toolBean);
        }
    }

    public ToolRegistry(ToolCallback[] toolCallbacks) {
        for (ToolCallback toolCallback : toolCallbacks) {
            register(toolCallback);
        }
    }

    public void register(Object toolBean) {
        for (ToolCallback toolCallback : ToolCallbacks.from(toolBean)) {
            register(toolCallback);
        }
    }

    public void register(ToolCallback toolCallback) {
        String toolName = toolCallback.getToolDefinition().name();
        toolCallbackMap.put(toolName, toolCallback);
        log.info("向工具箱中注册一个新工具：{}", toolName);
    }

    public List<ToolCallback> getToolCallbacks() {
        return new ArrayList<>(toolCallbackMap.values());
    }

    public ToolExecutionBatch execute(List<AssistantMessage.ToolCall> toolCalls) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        List<ToolExecutionTrace> traces = new ArrayList<>();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            ToolCallback toolCallback = toolCallbackMap.get(toolCall.name());
            if (toolCallback == null) {
                throw new IllegalArgumentException("未找到工具: " + toolCall.name());
            }

            String argumentPreview = extractArgumentPreview(toolCall.arguments());
            log.info("正在调用【{}】参数：【{}】", toolCall.name(), argumentPreview);

            String output = toolCallback.call(toolCall.arguments());
            responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), output));

            ToolExecutionTrace trace = new ToolExecutionTrace(
                    toolCall.name(),
                    argumentPreview,
                    preview(output)
            );
            traces.add(trace);
            log.info("工具【{}】执行完成，输出预览：【{}】", toolCall.name(), trace.outputPreview());
        }

        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(responses)
                .build();
        return new ToolExecutionBatch(toolResponseMessage, traces);
    }

    private String extractArgumentPreview(String argumentsJson) {
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            JsonNode commandNode = root.get("command");
            if (commandNode != null && !commandNode.isNull()) {
                return commandNode.asText();
            }
            return root.toString();
        }
        catch (IOException ignored) {
            log.debug("Failed to parse tool arguments for preview", ignored);
            return argumentsJson;
        }
    }

    private String preview(String output) {
        if (output == null || output.isBlank()) {
            return "(no output)";
        }
        if (output.length() <= TRACE_OUTPUT_PREVIEW_CHARS) {
            return output;
        }
        return output.substring(0, TRACE_OUTPUT_PREVIEW_CHARS);
    }
}
