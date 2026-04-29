package com.yu.agent4.service;

import com.yu.agent4.config.AgentLoopProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class ShellCommandRunner {

    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final AgentLoopProperties properties;

    public ShellCommandRunner(AgentLoopProperties properties) {
        this.properties = properties;
    }

    public String run(String command) {
        if (isDangerous(command)) {
            log.warn("Blocked dangerous bash command: {}", command);
            return "Error: Dangerous command blocked";
        }

        List<String> processCommand = new ArrayList<>();
        processCommand.add(properties.getShell().getProgram());
        processCommand.addAll(properties.getShell().getProgramArgs());

        // Git Bash 需要 Unix 风格的 PATH（/usr/bin、/bin）才能找到 ls 等内部命令，
        // 而 Windows 环境变量中的 PATH 是 Windows 格式，bash 不会自动转换。
        // 因此在命令前加 PATH 前缀，确保 bash 能找到自己的工具。
        command = "PATH=/usr/bin:/bin:$PATH " + command;
        processCommand.add(command);

        ProcessBuilder processBuilder = new ProcessBuilder(processCommand);
        processBuilder.directory(new java.io.File(System.getProperty("user.dir")));
        processBuilder.redirectErrorStream(true);

        processBuilder.environment().put("MSYS_NO_PATHCONV", "1");

        try {
            Process process = processBuilder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(process.getInputStream()));

            boolean finished = process.waitFor(properties.getShell().getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Bash command timed out after {} seconds: {}", properties.getShell().getTimeoutSeconds(), command);
                return "Error: Timeout (" + properties.getShell().getTimeoutSeconds() + "s)";
            }

            String output = outputFuture.get(5, TimeUnit.SECONDS).trim();
            log.info("Bash command output: {}", output);
            if (output.isEmpty()) {
                return "(no output)";
            }

            return truncate(output);
        }
        catch (IOException e) {
            log.error("Bash command execution failed: {}", command, e);
            return "Error: " + e.getMessage();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Bash command execution interrupted: {}", command, e);
            return "Error: Command execution interrupted";
        }
        catch (ExecutionException | TimeoutException e) {
            log.error("Failed to collect bash command output: {}", command, e);
            return "Error: " + e.getMessage();
        }
    }

    private boolean isDangerous(String command) {
        String normalized = command.toLowerCase(Locale.ROOT);
        return properties.getShell().getBlockedFragments().stream()
                .map(fragment -> fragment.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private String readOutput(InputStream inputStream) {
        try {
            return decodeOutput(inputStream.readAllBytes());
        }
        catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    String decodeOutput(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        String configuredCharset = properties.getShell().getOutputCharset();
        Charset charset = (configuredCharset == null || configuredCharset.isBlank())
                ? StandardCharsets.UTF_8
                : Charset.forName(configuredCharset);
        return new String(bytes, charset);
    }

    private String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS);
    }
}
