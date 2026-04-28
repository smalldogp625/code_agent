package com.yu.agent4.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class ChatAgentDemoTest {

    @Autowired
    private ChatAgentDemo chatAgentDemo;

    @Test
    void shouldLoadDemoBean() {
        assertNotNull(chatAgentDemo);
    }
}
