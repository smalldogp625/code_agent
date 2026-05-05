package com.yu.agent4.cli;

import com.yu.agent4.agent.AgentLoopService;
import com.yu.agent4.config.AgentLoopProperties;
import com.yu.agent4.context.transcript.SessionStore;
import com.yu.agent4.model.AgentLoopTurnResult;
import com.yu.agent4.model.ToolExecutionTrace;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 控制台入口。
 * 当 agent.loop.cli.enabled=true 时，应用启动后会进入交互式对话模式。
 */
@Component
@ConditionalOnProperty(prefix = "agent.loop.cli", name = "enabled", havingValue = "true")
public class AgentLoopCliRunner implements ApplicationRunner {

    private static final String CONTINUATION_PROMPT = "... ";

    private static final String CLEAR_COMMAND = "/clear";

    private final AgentLoopService agentLoopService;

    private final AgentLoopProperties properties;

    private final SessionStore sessionStore;

    private String sessionId;

    public AgentLoopCliRunner(AgentLoopService agentLoopService, AgentLoopProperties properties,
                               SessionStore sessionStore) {
        this.agentLoopService = agentLoopService;
        this.properties = properties;
        this.sessionStore = sessionStore;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        sessionStore.create(sessionId, null);
        List<Message> history = new ArrayList<>();

        try (Terminal terminal = buildTerminal()) {
            LineReader lineReader = buildLineReader(terminal);
            PrintStream out = new PrintStream(terminal.output(), true, StandardCharsets.UTF_8);
            while (true) {
                String query = readNextQuery(lineReader);
                if (query == null) {
                    break;
                }
                if (!handleQuery(history, query, out)) {
                    break;
                }
            }
        }
    }

    String readNextQuery(LineReader lineReader) {
        StringBuilder buffer = new StringBuilder();

        while (true) {
            String prompt = buffer.isEmpty() ? properties.getCli().getPrompt() : CONTINUATION_PROMPT;
            String line;
            try {
                line = lineReader.readLine(prompt);
            }
            catch (UserInterruptException e) {
                if (buffer.isEmpty()) {
                    return "";
                }
                continue;
            }
            catch (EndOfFileException e) {
                if (buffer.isEmpty()) {
                    return null;
                }
                return buffer.toString();
            }

            String normalized = normalize(line);
            if (buffer.isEmpty() && shouldExit(normalized)) {
                return null;
            }
            if (line.isBlank()) {
                if (buffer.isEmpty()) {
                    continue;
                }
                return buffer.toString();
            }

            if (!buffer.isEmpty()) {
                buffer.append(System.lineSeparator());
            }
            buffer.append(line);
        }
    }

    boolean handleQuery(List<Message> history, String query, PrintStream out) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return true;
        }
        if (CLEAR_COMMAND.equals(normalized)) {
            history.clear();
            agentLoopService.resetSession(sessionId);
            String prevSessionId = sessionId;
            sessionId = UUID.randomUUID().toString().substring(0, 8);
            sessionStore.create(sessionId, prevSessionId);
            out.println("会话历史已清空。");
            out.println();
            return true;
        }
        if (shouldExit(normalized)) {
            return false;
        }

        AgentLoopTurnResult result = agentLoopService.runTurn(history, query, sessionId);
        printTraces(result.traces(), out);

        if (result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
            out.println(result.finalAnswer());
        }
        out.println();
        return true;
    }

    private Terminal buildTerminal() throws IOException {
        return TerminalBuilder.builder()
                .system(true)
                .encoding(StandardCharsets.UTF_8)
                .build();
    }

    private LineReader buildLineReader(Terminal terminal) {
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("Agent4")
                .build();
    }

    private boolean shouldExit(String normalized) {
        return "q".equals(normalized) || "exit".equals(normalized) || "/exit".equals(normalized);
    }

    private String normalize(String line) {
        return line == null ? "" : line.trim().toLowerCase();
    }

    private void printTraces(List<ToolExecutionTrace> traces, PrintStream out) {
        for (ToolExecutionTrace trace : traces) {
            out.println("$ " + trace.commandPreview());
            out.println(trace.outputPreview());
        }
    }
}
