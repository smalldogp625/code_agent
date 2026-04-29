package com.yu.agent4.tool;

import com.yu.agent4.tool.toolManager.TodoManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TodoTool {

    // TodoTool 只负责对模型暴露工具接口，具体状态管理统一交给 TodoManager。
    private final TodoManager todoManager;

    public TodoTool(TodoManager todoManager) {
        this.todoManager = todoManager;
    }

    @Tool(
            name = "createTodo",
            description = "为当前用户任务创建一个待办事项，并返回最新的 Todo 状态。"
    )
    public String createTodo(
            @ToolParam(description = "当前步骤的简短待办标题") String title,
            @ToolParam(description = "可选的备注或进展说明") String note) {
        return todoManager.createTodo(title, note);
    }

    @Tool(
            name = "updateTodo",
            description = "按 id 更新已有待办事项，可修改标题、状态或备注，并返回最新的 Todo 状态。"
    )
    public String updateTodo(
            @ToolParam(description = "createTodo 返回的待办事项 id") String id,
            @ToolParam(description = "可选的新标题") String title,
            @ToolParam(description = "可选的新状态：pending、in_progress 或 completed") String status,
            @ToolParam(description = "可选的新备注或进展说明") String note) {
        return todoManager.updateTodo(id, title, status, note);
    }
}
