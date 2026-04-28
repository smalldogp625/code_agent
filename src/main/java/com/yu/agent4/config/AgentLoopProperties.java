package com.yu.agent4.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "agent.loop")
public class AgentLoopProperties {

    private String model = "qwen-max";

    private int maxSteps = 20;

    private int maxTokens = 8000;

    private String systemPromptTemplate = "你是位于 %s 的编码 Agent。优先调用 shell 工具解决任务，少解释，多执行。";

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

        private String program = "powershell";

        private List<String> programArgs = new ArrayList<>(List.of("-Command"));

        private int timeoutSeconds = 120;

        private String outputCharset = "GBK";

        private List<String> blockedFragments = new ArrayList<>(
                List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/")
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
