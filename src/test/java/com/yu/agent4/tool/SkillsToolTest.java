package com.yu.agent4.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.*;

class SkillsToolTest {
    private static final String SKILLS_PROJECT_DIR = "src/main/java/com/yu/agent4/skills";
    @Test
    public void Test1(){
        ToolCallback build = SkillsTool.builder().addSkillsDirectory(SKILLS_PROJECT_DIR).build();
        System.out.println(build);



    }

}