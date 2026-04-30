package com.yu.agent4.tool;

import com.yu.agent4.agent.AgentLoopService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 将子任务委托给子 Agent 独立执行，返回执行总结。
 * <p>
 * 子 Agent 拥有独立的对话上下文和受限的工具集（不含 task、todo），
 * 父 Agent 等待子 Agent 完成后将总结作为工具调用结果存入上下文。
 * 使用 {@link Lazy} 注入 {@link AgentLoopService} 以打破循环依赖：
 * ToolRegistry → TaskTool → AgentLoopService → ToolRegistry。
 */
@Component
public class TaskTool {

    private final AgentLoopService agentLoopService;

    public TaskTool(@Lazy AgentLoopService agentLoopService) {
        this.agentLoopService = agentLoopService;
    }

    @Tool(
            name = "task",
            description = """
                    将指定的子任务委托给一个独立的子 Agent 完成，并返回执行总结。
                    子 Agent 拥有完整的文件读写和命令执行能力，但无法再创建子任务。
                    当前 Agent 可以继续处理其他事务，子 Agent 执行完毕后会返回结果摘要。
                    """
    )
    public String execute(
            @ToolParam(description = "子 Agent 需要完成的任务描述，应包含目标、上下文和关键约束")
            String taskDescription) {
        return agentLoopService.runTurnWithSubAgent(taskDescription);
    }
}
