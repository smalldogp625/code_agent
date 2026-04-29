package com.yu.agent4.tool.toolManager;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class TodoManager {

    // 当前用户任务下的 todo 列表。每次新的用户任务开始时会整体重置。
    private final List<TodoItem> todos = new ArrayList<>();

    private int nextId = 1;

    // 创建待办时默认进入 pending 状态，并返回最新快照，便于模型立即感知当前计划。
    public synchronized String createTodo(String title, String note) {
        if (title == null || title.isBlank()) {
            return errorWithState("Todo title cannot be blank");
        }

        TodoItem todoItem = new TodoItem(String.valueOf(nextId++), title.trim(), TodoStatus.PENDING, normalize(note));
        todos.add(todoItem);
        return "Created todo: [" + todoItem.id + "] " + renderTitleWithDetail(todoItem) + "\n\n" + renderState();
    }

    // 更新待办时统一在这里做状态约束校验，避免规则分散在 Tool 或 AgentLoop 中。
    public synchronized String updateTodo(String id, String title, String status, String note) {
        TodoItem todoItem = findById(id);
        if (todoItem == null) {
            return errorWithState("Todo not found: " + id);
        }

        TodoStatus nextStatus = todoItem.status;
        if (status != null && !status.isBlank()) {
            try {
                nextStatus = TodoStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException ex) {
                return errorWithState("Unknown todo status: " + status);
            }
        }

        // 整个 todo 列表同一时间只允许一个任务处于 in_progress。
        if (nextStatus == TodoStatus.IN_PROGRESS && hasAnotherInProgress(todoItem.id)) {
            return errorWithState("Only one todo can be in_progress at a time");
        }

        if (title != null && !title.isBlank()) {
            todoItem.title = title.trim();
        }
        if (note != null) {
            todoItem.note = normalize(note);
        }
        todoItem.status = nextStatus;
        todoItem.updatedAt = Instant.now();

        return "Updated todo: [" + todoItem.id + "] " + renderTitleWithDetail(todoItem) + "\n\n" + renderState();
    }

    // 新的用户任务开始时重置当前 todo 状态，避免不同任务之间相互污染。
    public synchronized void resetForNewTask() {
        todos.clear();
        nextId = 1;
    }

    // 统一输出给模型和 CLI 的 Todo 快照格式。
    public synchronized String renderState() {
        if (todos.isEmpty()) {
            return "TodoManager state\n(empty)";
        }

        StringBuilder builder = new StringBuilder("TodoManager state");
        for (TodoItem todoItem : todos) {
            builder.append('\n')
                    .append(renderLine(todoItem));
        }
        return builder.toString();
    }

    // 检查除当前任务外，是否已经有别的任务处于 in_progress。
    private boolean hasAnotherInProgress(String currentTodoId) {
        return todos.stream()
                .anyMatch(todo -> todo.status == TodoStatus.IN_PROGRESS && !todo.id.equals(currentTodoId));
    }

    private TodoItem findById(String id) {
        return todos.stream()
                .filter(todo -> todo.id.equals(id))
                .findFirst()
                .orElse(null);
    }

    // 工具报错时也带上当前状态，方便模型根据上下文决定下一步。
    private String errorWithState(String message) {
        return "Error: " + message + "\n\n" + renderState();
    }

    // 渲染单条 todo 的展示符号。
    private String renderLine(TodoItem todoItem) {
        return switch (todoItem.status) {
            case PENDING -> "[ ] " + renderTitleWithDetail(todoItem);
            case IN_PROGRESS -> "[>] " + renderTitleWithDetail(todoItem) + "  <- doing";
            case COMPLETED -> "[x] " + renderTitleWithDetail(todoItem);
        };
    }

    private String renderTitleWithDetail(TodoItem todoItem) {
        if (todoItem.note == null || todoItem.note.isBlank()) {
            return todoItem.title;
        }
        return todoItem.title + "：" + todoItem.note;
    }

    // 将空白备注标准化为 null，减少后续状态判断分支。
    private String normalize(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.trim();
    }

    // 第一版仅保留 3 个核心状态，保持模型调用和状态约束简单稳定。
    private enum TodoStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }

    // 轻量内部对象，封装一条 todo 的最小必要信息。
    private static final class TodoItem {

        private final String id;

        private String title;

        private TodoStatus status;

        private String note;

        private final Instant createdAt;

        private Instant updatedAt;

        private TodoItem(String id, String title, TodoStatus status, String note) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.note = note;
            this.createdAt = Instant.now();
            this.updatedAt = this.createdAt;
        }
    }
}
