package com.yu.agent4.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "agent.loop")
public class AgentLoopProperties {

    private String model = "qwen-max";

    private int maxSteps = 20;

    private int maxTokens = 8000;

    private String systemPromptTemplate = """
            你是位于 %s 的单 Agent 编码助手。
            你最多可以执行 %s 个步骤。
            当前可用工具：%s。

            工作方式要求：
            1. 先理解用户目标并拆分步骤，再决定是否调用工具。
            2. 优先选择最合适的工具完成当前步骤，不要默认只使用 shell。
            3. 能直接回答的问题直接回答；需要观察、读取、修改或执行时再调用工具。
            4. 每次工具调用都要服务于当前任务推进，并基于工具结果继续下一步。
            5. 如果任务还不能完成，就继续规划下一步；如果已完成，给出最终结果。
            6. 如果因为工具能力、权限或上下文不足而无法完成，要明确说明阻塞原因。
            7. 保持回答简洁、准确、面向执行，避免无意义赘述。
            """;

    private final Cli cli = new Cli();

    private final Shell shell = new Shell();

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getSystemPromptTemplate() {
        return systemPromptTemplate;
    }

    public void setSystemPromptTemplate(String systemPromptTemplate) {
        this.systemPromptTemplate = systemPromptTemplate;
    }

    public Cli getCli() {
        return cli;
    }

    public Shell getShell() {
        return shell;
    }

    public static class Cli {

        private boolean enabled = false;

        private String prompt = "agent4 >> ";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }

    public static class Shell {

        private String program = "C:\\Program Files\\Git\\usr\\bin\\bash";

        private List<String> programArgs = new ArrayList<>(List.of("-c"));

        private int timeoutSeconds = 120;

        private String outputCharset = "UTF-8";

        private List<String> blockedFragments = new ArrayList<>(
                List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/", ":(){ :|:& };:", "mkfs.", "dd if=")
        );

        public String getProgram() {
            return program;
        }

        public void setProgram(String program) {
            this.program = program;
        }

        public List<String> getProgramArgs() {
            return programArgs;
        }

        public void setProgramArgs(List<String> programArgs) {
            this.programArgs = programArgs;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getOutputCharset() {
            return outputCharset;
        }

        public void setOutputCharset(String outputCharset) {
            this.outputCharset = outputCharset;
        }

        public List<String> getBlockedFragments() {
            return blockedFragments;
        }

        public void setBlockedFragments(List<String> blockedFragments) {
            this.blockedFragments = blockedFragments;
        }
    }
}
