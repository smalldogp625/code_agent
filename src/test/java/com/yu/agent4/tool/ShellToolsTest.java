package com.yu.agent4.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShellToolsTest {

    @Test
    void bash() {
        ShellTools shellTools = new ShellTools();
        String command = "dir";
        shellTools.bash(command, null, null,null);

    }
}