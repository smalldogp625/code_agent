package com.yu.agent4.cli;

import com.yu.agent4.agent.AgentLoopService;
import com.yu.agent4.config.AgentLoopProperties;
import com.yu.agent4.model.AgentLoopTurnResult;
import com.yu.agent4.model.ToolExecutionTrace;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 控制台入口。
 * 当 agent.loop.cli.enabled=true 时，应用启动后会进入交互式对话模式。
 */
@Component
@ConditionalOnProperty(prefix = "agent.loop.cli", name = "enabled", havingValue = "true")
public class AgentLoopCliRunner implements ApplicationRunner {

    private final AgentLoopService agentLoopService;

    private final AgentLoopProperties properties;

    public AgentLoopCliRunner(AgentLoopService agentLoopService, AgentLoopProperties properties) {
        this.agentLoopService = agentLoopService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Message> history = new ArrayList<>();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print(properties.getCli().getPrompt());
                if (!scanner.hasNextLine()) {
                    break;
                }

                String query = scanner.nextLine();
                if (shouldExit(query)) {
                    break;
                }

                AgentLoopTurnResult result = agentLoopService.runTurn(history, query);
                printTraces(result.traces());

                if (result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
                    System.out.println(result.finalAnswer());
                }
                System.out.println();
            }
        }
    }

    private boolean shouldExit(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        return normalized.isEmpty() || "q".equals(normalized) || "exit".equals(normalized);
    }

    private void printTraces(List<ToolExecutionTrace> traces) {
        for (ToolExecutionTrace trace : traces) {
            System.out.println("$ " + trace.commandPreview());
            System.out.println(trace.outputPreview());
        }
    }
}
