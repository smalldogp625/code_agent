package com.yu.agent4.service;

import com.yu.agent4.config.AgentLoopProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellCommandRunnerTest {

    @Test
    void shouldBlockDangerousCommand() {
        AgentLoopProperties properties = new AgentLoopProperties();
        ShellCommandRunner shellCommandRunner = new ShellCommandRunner(properties);

        String output = shellCommandRunner.run(" echo 我是傻逼");
//        assertEquals("/usr/bin/bash: line 1: ls: command not found", output);
    }

    @Test
    void shouldDecodeShellOutputUsingConfiguredCharset() {
        AgentLoopProperties properties = new AgentLoopProperties();
        properties.getShell().setOutputCharset("GBK");
        ShellCommandRunner shellCommandRunner = new ShellCommandRunner(properties);

        byte[] bytes = "卷 中的文件夹 PATH 列表".getBytes(Charset.forName("GBK"));

        String output = shellCommandRunner.decodeOutput(bytes);

        assertEquals("卷 中的文件夹 PATH 列表", output);
    }
}
