package com.yu.agent4.agent;

import com.yu.agent4.config.AgentLoopProperties;
import com.yu.agent4.context.ContextAssembler;
import com.yu.agent4.model.AgentLoopTurnResult;
import com.yu.agent4.tool.ToolRegistry;
import com.yu.agent4.tool.toolManager.TodoManager;
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
        AgentLoopService agentLoopService = new AgentLoopService(
                fakeChatModel, toolRegistry, properties, new TodoManager(),
                new ContextAssembler(properties, toolRegistry, new TodoManager())
        );

        List<Message> history = new ArrayList<>();
        AgentLoopTurnResult result = agentLoopService.runTurn(history, "list current directory");

        assertEquals("task completed", result.finalAnswer());
        assertEquals(1, result.traces().size());
        assertEquals(2, fakeChatModel.callCount);
        assertTrue(fakeChatModel.secondCallSawToolResponse);
        assertTrue(fakeChatModel.firstSystemPrompt.contains(System.getProperty("user.dir")));
        assertTrue(fakeChatModel.firstSystemPrompt.contains(String.valueOf(properties.getMaxSteps())));
        assertTrue(fakeChatModel.firstSystemPrompt.contains("bash"));
        assertTrue(fakeChatModel.firstSystemPrompt.contains("工作方式要求"));
        assertEquals(4, history.size());
        assertTrue(output.getOut().contains("Step 1/5 — prompt_tokens"));
        assertTrue(output.getOut().contains("Get-ChildItem"));
    }

    @Test
    void shouldResetTodosWhenNewUserTaskStarts() {
        AgentLoopProperties properties = new AgentLoopProperties();
        properties.setModel("test-model");
        properties.setMaxSteps(2);
        properties.setMaxTokens(1000);

        ToolRegistry toolRegistry = new ToolRegistry(new ToolCallback[0]);
        TodoManager todoManager = new TodoManager();
        todoManager.createTodo("old task", null);
        AgentLoopService agentLoopService = new AgentLoopService(
                new ImmediateAnswerChatModel("done"), toolRegistry, properties, todoManager,
                new ContextAssembler(properties, toolRegistry, todoManager)
        );

        agentLoopService.runTurn(new ArrayList<>(), "new task");

        assertEquals("TodoManager state\n(empty)", todoManager.renderState());
    }

    @Test
    void shouldInjectTodoReminderAfterThreeRoundsWithoutTodoTool() {
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

        ReminderAwareChatModel fakeChatModel = new ReminderAwareChatModel();
        ToolRegistry toolRegistry = new ToolRegistry(new ToolCallback[]{fakeTool});
        AgentLoopService agentLoopService = new AgentLoopService(
                fakeChatModel,
                toolRegistry,
                properties,
                new TodoManager(),
                new ContextAssembler(properties, toolRegistry, new TodoManager())
        );

        AgentLoopTurnResult result = agentLoopService.runTurn(new ArrayList<>(), "complex task");

        assertEquals("done", result.finalAnswer());
        assertEquals(4, fakeChatModel.systemPrompts.size());
        assertTrue(fakeChatModel.systemPrompts.get(3).contains("todo"));
        assertTrue(fakeChatModel.systemPrompts.get(3).contains("TodoManager state"));
    }

    @Test
    void shouldInjectTodoKickoffPromptForObviousMultiStepTask() {
        AgentLoopProperties properties = new AgentLoopProperties();
        properties.setModel("test-model");
        properties.setMaxSteps(2);
        properties.setMaxTokens(1000);

        PromptCapturingChatModel chatModel = new PromptCapturingChatModel("done");
        ToolRegistry toolRegistry = new ToolRegistry(new ToolCallback[0]);
        AgentLoopService agentLoopService = new AgentLoopService(
                chatModel,
                toolRegistry,
                properties,
                new TodoManager(),
                new ContextAssembler(properties, toolRegistry, new TodoManager())
        );

        agentLoopService.runTurn(
                new ArrayList<>(),
                "重构文件 hello.py：添加类型提示、文档字符串和主函数保护；创建一个包含 __init__.py、utils.py 和 tests/test_utils.py 的 Python 包；审查所有 Python 文件并修复风格问题"
        );

        assertTrue(chatModel.firstSystemPrompt.contains("createTodo"));
        assertTrue(chatModel.firstSystemPrompt.contains("Todo kickoff reminder:"));
    }

    @Test
    void shouldNotInjectTodoKickoffPromptForSimpleTask() {
        AgentLoopProperties properties = new AgentLoopProperties();
        properties.setModel("test-model");
        properties.setMaxSteps(2);
        properties.setMaxTokens(1000);

        PromptCapturingChatModel chatModel = new PromptCapturingChatModel("done");
        ToolRegistry toolRegistry = new ToolRegistry(new ToolCallback[0]);
        AgentLoopService agentLoopService = new AgentLoopService(
                chatModel,
                toolRegistry,
                properties,
                new TodoManager(),
                new ContextAssembler(properties, toolRegistry, new TodoManager())
        );

        agentLoopService.runTurn(new ArrayList<>(), "读取 hello.py");

        assertTrue(!chatModel.firstSystemPrompt.contains("Todo kickoff reminder:"));
    }

    private static class ImmediateAnswerChatModel implements ChatModel {

        private final String answer;

        private ImmediateAnswerChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }

    private static class ReminderAwareChatModel implements ChatModel {

        private int callCount;

        private final List<String> systemPrompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            Message firstMessage = prompt.getInstructions().get(0);
            SystemMessage systemMessage = assertInstanceOf(SystemMessage.class, firstMessage);
            systemPrompts.add(systemMessage.getText());
            callCount++;

            if (callCount <= 3) {
                AssistantMessage assistantMessage = AssistantMessage.builder()
                        .content("keep working")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "tool-call-" + callCount,
                                "function",
                                "bash",
                                "{\"command\":\"Get-ChildItem\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(assistantMessage)));
            }

            return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }

    private static class PromptCapturingChatModel implements ChatModel {

        private final String answer;

        private String firstSystemPrompt;

        private PromptCapturingChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            Message firstMessage = prompt.getInstructions().get(0);
            SystemMessage systemMessage = assertInstanceOf(SystemMessage.class, firstMessage);
            firstSystemPrompt = systemMessage.getText();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
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
