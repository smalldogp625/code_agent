package com.yu.agent4.agent;

import com.yu.agent4.config.AgentLoopProperties;
import com.yu.agent4.model.AgentLoopTurnResult;
import com.yu.agent4.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class AgentLoopServiceTest {

    @Test
    void shouldAppendToolResultAndContinueLoopUntilModelStops(CapturedOutput output) {
        AgentLoopProperties properties = new AgentLoopProperties();
        properties.setModel("test-model");
        properties.setMaxSteps(5);
        properties.setMaxTokens(1000);

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
        FakeChatModel fakeChatModel = new FakeChatModel();
        AgentLoopService agentLoopService = new AgentLoopService(fakeChatModel, toolRegistry, properties);

        List<Message> history = new ArrayList<>();
        AgentLoopTurnResult result = agentLoopService.runTurn(history, "list current directory");

        assertEquals("task completed", result.finalAnswer());
        assertEquals(1, result.traces().size());
        assertEquals(2, fakeChatModel.callCount);
        assertTrue(fakeChatModel.secondCallSawToolResponse);
        assertTrue(fakeChatModel.firstSystemPrompt.contains(System.getProperty("user.dir")));
        assertTrue(fakeChatModel.firstSystemPrompt.contains(String.valueOf(properties.getMaxSteps())));
        assertTrue(fakeChatModel.firstSystemPrompt.contains("bash"));
        assertTrue(fakeChatModel.firstSystemPrompt.contains("调用工具"));
        assertEquals(4, history.size());
        assertTrue(output.getOut().contains("Agent step 1/5: calling model"));
        assertTrue(output.getOut().contains("Get-ChildItem"));
    }

    private static class FakeChatModel implements ChatModel {

        private int callCount;

        private boolean secondCallSawToolResponse;

        private String firstSystemPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount++;

            if (callCount == 1) {
                Message firstMessage = prompt.getInstructions().get(0);
                SystemMessage systemMessage = assertInstanceOf(SystemMessage.class, firstMessage);
                firstSystemPrompt = systemMessage.getText();

                AssistantMessage assistantMessage = AssistantMessage.builder()
                        .content("I will inspect the directory first")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "tool-call-1",
                                "function",
                                "bash",
                                "{\"command\":\"Get-ChildItem\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(assistantMessage)));
            }

            Message lastMessage = prompt.getInstructions().get(prompt.getInstructions().size() - 1);
            ToolResponseMessage toolResponseMessage = assertInstanceOf(ToolResponseMessage.class, lastMessage);
            secondCallSawToolResponse = "shell-output".equals(toolResponseMessage.getResponses().get(0).responseData());

            AssistantMessage assistantMessage = new AssistantMessage("task completed");
            return new ChatResponse(List.of(new Generation(assistantMessage)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }
}
