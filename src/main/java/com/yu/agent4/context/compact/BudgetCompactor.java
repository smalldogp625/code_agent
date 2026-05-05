package com.yu.agent4.context.compact;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 工具输出截断压缩器 — Budget Reduction。
 * 对工具执行结果（{@link ToolResponseMessage}）做字符级截断，
 * 防止长输出（如文件读取、Shell 命令结果）撑爆上下文窗口。
 *
 * <p><b>只处理 {@code ToolResponseMessage}，不影响系统/用户/助手消息。</b>
 * 截断时保留工具调用 ID 和名称，仅裁切消息体，并在末尾追加截断标记。
 *
 * <p><b>按工具独立预算：</b>不同工具可配置不同的截断阈值。
 * 例如 {@code read} 输出完整文件内容，预算应远大于 {@code bash} 的输出截断预算。
 * 未单独配置的工具使用默认预算；预算设为 {@code -1} 表示该工具永不截断。
 */
@Slf4j
public class BudgetCompactor implements Compactor {

    /** 永不截断的标记值 */
    private static final int UNLIMITED = -1;

    private final int defaultMaxChars;

    private final Map<String, Integer> toolBudgets;

    private static final String TRUNCATION_NOTICE =
            "\n\n--- [truncated: original %d chars, kept %d chars] ---";

    /** 截断标记前缀，用于检测消息是否已被截断，避免重复截断 */
    private static final String TRUNCATION_MARKER = "[truncated: original";

    /** spill 落盘根目录（相对 project 根） */
    private static final String SPILL_ROOT = ".agent4/toolResponse";

    /**
     * @param defaultMaxChars 所有工具的默认截断预算（字符数），{@code -1} 表示永不截断
     */
    public BudgetCompactor(int defaultMaxChars) {
        this(defaultMaxChars, Collections.emptyMap());
    }

    /**
     * @param defaultMaxChars 所有工具的默认截断预算（字符数），{@code -1} 表示永不截断
     * @param toolBudgets     按工具名独立配置的预算，未覆盖的工具使用默认值，{@code -1} 表示永不截断
     */
    public BudgetCompactor(int defaultMaxChars, Map<String, Integer> toolBudgets) {
        if (defaultMaxChars < 0 && defaultMaxChars != UNLIMITED) {
            throw new IllegalArgumentException("defaultMaxChars must be >= 0 or -1 (unlimited), got: " + defaultMaxChars);
        }
        for (Map.Entry<String, Integer> entry : toolBudgets.entrySet()) {
            int v = entry.getValue();
            if (v < 0 && v != UNLIMITED) {
                throw new IllegalArgumentException(
                        "Budget for tool '" + entry.getKey() + "' must be >= 0 or -1 (unlimited), got: " + v);
            }
        }
        this.defaultMaxChars = defaultMaxChars;
        this.toolBudgets = toolBudgets;
    }

    /**
     * 对单条工具响应执行预算削减。
     * <p>
     * 未超预算 → 返回原响应；超预算 → 将完整数据落盘到
     * {@code .agent4/toolResponse/{sessionId}/{turnId}/{toolId}.json}，
     * 并以文件 URI 替换原始响应数据，模型可用 {@code read} 工具读取 URI 取回完整内容。
     *
     * @param response  原始工具响应
     * @param sessionId 会话标识
     * @param turnId    turn 序号
     * @return 原响应或 URI 替换后的新响应
     */
    public ToolResponseMessage.ToolResponse compact(
            ToolResponseMessage.ToolResponse response,
            String sessionId,
            int turnId) {
        String data = response.responseData();
        if (data == null || alreadyTruncated(data)) {
            return response;
        }
        int budget = budgetFor(response);
        if (budget == UNLIMITED || data.length() <= budget) {
            return response;
        }
        // 超预算 → 落盘完整数据，返回文件 URI（模型可用 read 工具读取）
        String uri = spill(sessionId, turnId, response.id(), data);
        log.info("[spill] {} — original {} chars → {}", response.name(), data.length(), uri);
        String responseDate = "由于工具相应数据过长，现将工具相应结果存在如下地址:\n"+uri+"\n";
        return new ToolResponseMessage.ToolResponse(
                response.id(),
                response.name(),
                responseDate
        );
    }

    @Override
    public List<Message> compact(List<Message> messages, CompactionContext context) {
        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            result.add(truncateIfNeeded(message));
        }
        return result;
    }

    @Override
    public int order() {
        return 10;
    }

    /**
     * 单条消息截断：非 ToolResponseMessage 直接返回，不处理。
     */
    private Message truncateIfNeeded(Message message) {
        if (!(message instanceof ToolResponseMessage trm)) {
            return message;
        }

        List<ToolResponseMessage.ToolResponse> responses = trm.getResponses();
        List<ToolResponseMessage.ToolResponse> truncated = new ArrayList<>(responses.size());

        for (ToolResponseMessage.ToolResponse response : responses) {
            String data = response.responseData();
            int budget = budgetFor(response);

            if (budget == UNLIMITED || data == null || data.length() <= budget || alreadyTruncated(data)) {
                truncated.add(response);
            } else {
                String kept = data.substring(0, budget);
                truncated.add(new ToolResponseMessage.ToolResponse(
                        response.id(),
                        response.name(),
                        kept + TRUNCATION_NOTICE.formatted(data.length(), budget)
                ));
            }
        }

        return ToolResponseMessage.builder()
                .responses(truncated)
                .build();
    }

    /**
     * 获取指定工具响应的截断预算：优先查工具独立配置，无则使用默认值。
     */
    private int budgetFor(ToolResponseMessage.ToolResponse response) {
        return toolBudgets.getOrDefault(response.name(), defaultMaxChars);
    }

    /**
     * 检测工具响应是否已经被截断过或已是 spill URI，避免重复截断。
     */
    private static boolean alreadyTruncated(String data) {
        return data.contains(TRUNCATION_MARKER) || data.startsWith(SPILL_ROOT);
    }

    /**
     * 将超预算的工具输出完整写入文件系统，返回可被 read 工具读取的 URI。
     * <p>
     * 文件路径：{project}/.agent4/toolResponse/{sessionId}/{turnId}/{toolId}
     *
     * @param sessionId 会话标识
     * @param turnId    turn 序号
     * @param toolId    工具调用 ID（全局唯一）
     * @param data      工具输出的完整原始数据
     * @return 文件相对路径 URI
     */
    private String spill(String sessionId, int turnId, String toolId, String data) {
        Path spillDir = Path.of(System.getProperty("user.dir", "."),
                SPILL_ROOT, sessionId, String.valueOf(turnId));
        FileUtil.mkdir(spillDir.toFile());
        Path spillFile = spillDir.resolve(toolId + ".json");
        String json = JSONUtil.toJsonStr(Map.of("responseData", data));
        FileUtil.writeString(json, spillFile.toFile(), StandardCharsets.UTF_8);
        return SPILL_ROOT + "/" + sessionId + "/" + turnId + "/" + toolId + ".json";
    }

    // 包级可见，仅用于测试
    int getDefaultMaxChars() {
        return defaultMaxChars;
    }

    // 包级可见，仅用于测试
    Map<String, Integer> getToolBudgets() {
        return toolBudgets;
    }
}
