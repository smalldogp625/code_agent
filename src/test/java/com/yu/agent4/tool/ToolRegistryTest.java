package com.yu.agent4.tool;

import com.yu.agent4.model.ToolExecutionBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class ToolRegistryTest {

    @Test
    void shouldRegisterAllToolBeans() {
        ToolRegistry toolRegistry = new ToolRegistry(new Object[]{new FakeBashTool(), new FileTool(), new TodoTool(new com.yu.agent4.tool.toolManager.TodoManager())});

        assertEquals(6, toolRegistry.getToolCallbacks().size());
    }

    @Test
    void shouldRegisterAndExecuteToolWithLogging(CapturedOutput output) {
        ToolCallback fakeTool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("bash")
                        .description("execute shell command")
                        .inputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "command": {
                                      "type": "string"
                                    }
                                  },
                                  "required": ["command"]
                                }
                                """)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "shell-output";
            }
        };

        ToolRegistry toolRegistry = new ToolRegistry(new ToolCallback[]{fakeTool});
        ToolExecutionBatch batch = toolRegistry.execute(List.of(new AssistantMessage.ToolCall(
                "tool-call-1",
                "function",
                "bash",
                "{\"command\":\"Get-ChildItem\"}"
        )));

        assertEquals(1, toolRegistry.getToolCallbacks().size());
        assertEquals(1, batch.traces().size());
        assertEquals("bash", batch.traces().get(0).toolName());
        assertEquals("Get-ChildItem", batch.traces().get(0).commandPreview());
        assertTrue(output.getOut().contains("bash"));
        assertTrue(output.getOut().contains("Get-ChildItem"));
    }

    private static class FakeBashTool {

        @Tool(name = "bash", description = "execute shell command")
        String run(@ToolParam(description = "command") String command) {
            return "(no output)";
        }
    }
}
