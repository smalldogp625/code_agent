# Agent Prompt Todo Kickoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让单 Agent 在明显多步骤或多文件任务中，于首轮优先使用 todo 工具做任务拆解，并保留现有的中途 reminder 机制。

**Architecture:** 更新系统提示词，明确复杂任务首轮优先 `createTodo`。在 `AgentLoopService` 中增加轻量启发式识别函数，用于在首轮 prompt 动态注入 todo kickoff 提示；保留已有的 3 轮未使用 todo reminder 逻辑。通过 `AgentLoopServiceTest` 固定复杂任务和简单任务的 prompt 行为边界。

**Tech Stack:** Java 17, Spring Boot, Spring AI, JUnit 5

---

### Task 1: Add failing tests for kickoff prompt behavior

**Files:**
- Modify: `src/test/java/com/yu/agent4/agent/AgentLoopServiceTest.java`

- [ ] Step 1: 写出明显多步骤任务首轮 prompt 应包含 todo kickoff 提示的失败测试
- [ ] Step 2: 写出简单任务首轮 prompt 不应包含 kickoff 提示的失败测试
- [ ] Step 3: 运行定向测试确认红灯

### Task 2: Update prompt template and dynamic prompt building

**Files:**
- Modify: `src/main/java/com/yu/agent4/config/AgentLoopProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/yu/agent4/agent/AgentLoopService.java`

- [ ] Step 1: 强化系统提示词中的 todo 规划规则
- [ ] Step 2: 在 `AgentLoopService` 中加入复杂任务启发式识别
- [ ] Step 3: 在首轮 prompt 中注入 todo kickoff 提示，并保留已有 reminder

### Task 3: Verify

**Files:**
- Verify only

- [ ] Step 1: 运行 `AgentLoopServiceTest`，确认绿灯
- [ ] Step 2: 运行全量测试，确认没有回归
