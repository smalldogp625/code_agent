package com.yu.agent4.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Loop 相关 Bean 装配。
 * 这里显式创建一个专用的 DashScopeChatModel，
 * 既保留 demo 里的接法，也避免和其他场景互相耦合。
 */
@Configuration
@EnableConfigurationProperties(AgentLoopProperties.class)
public class  AgentLoopConfiguration {

    @Bean("agentLoopChatModel")
    public DashScopeChatModel agentLoopChatModel(
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            AgentLoopProperties properties) {

        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();

        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model(properties.getModel())
                .maxToken(properties.getMaxTokens())
                .build();

        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(options)
                .build();
    }
}
