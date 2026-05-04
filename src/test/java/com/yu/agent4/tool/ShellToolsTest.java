package com.yu.agent4.tool;

import com.yu.agent4.config.AgentLoopProperties;
import org.junit.jupiter.api.Test;

class ShellToolsTest {

    @Test
    void bash() {
        ShellTools shellTools = new ShellTools(new AgentLoopProperties());
        String command = "dir";
        shellTools.bash(command, null, null, null);
    }
}