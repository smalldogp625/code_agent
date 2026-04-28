package com.yu.agent4.tool;

import com.yu.agent4.service.ShellCommandRunner;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class BashTool {

    private final ShellCommandRunner shellCommandRunner;

    public BashTool(ShellCommandRunner shellCommandRunner) {
        this.shellCommandRunner = shellCommandRunner;
    }

    @Tool(
            name = "bash",
            description = "在当前工作目录执行一条 shell 命令，并返回标准输出与错误输出。"
    )
    public String run(@ToolParam(description = "要执行的 shell 命令") String command) {
        return shellCommandRunner.run(command);
    }
}
