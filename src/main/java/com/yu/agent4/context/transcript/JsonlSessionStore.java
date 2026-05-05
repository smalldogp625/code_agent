package com.yu.agent4.context.transcript;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSONL 文件实现的 SessionStore — append-only 持久化。
 *
 * <p>文件路径：{@code <projectDir>/.agent4/transcripts/<sessionId>.jsonl}</p>
 *
 * <p>线程安全：同一 session 文件通过 {@link #fileLocks} 上的 {@code synchronized} 串行化写入，
 * 不同 session 不受影响。</p>
 */
@Component
public class JsonlSessionStore implements SessionStore {

    private static final Path DEFAULT_DIR = Path.of(
            System.getProperty("user.dir", "."), ".agent4", "transcripts");

    private static final JSONConfig NON_NULL_CONFIG = JSONConfig.create().setIgnoreNullValue(false);

    private final Path transcriptsDir;

    /** 按文件路径持有锁对象，保证同文件写入串行化 */
    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    /** Spring 自动装配入口 */
    public JsonlSessionStore() {
        this.transcriptsDir = DEFAULT_DIR;
    }

    /** 包级可见，仅用于测试 */
    public JsonlSessionStore(Path transcriptsDir) {
        this.transcriptsDir = transcriptsDir;
    }

    @Override
    public void create(String sessionId, String parentSessionId) {
        FileUtil.mkdir(transcriptsDir.toFile());
        var meta = new TranscriptEvent.SessionMeta(
                sessionId, parentSessionId, DateUtil.now());
        appendLine(resolve(sessionId), meta);
    }

    @Override
    public void append(String sessionId, TranscriptEvent event) {
        appendLine(resolve(sessionId), event);
    }

    @Override
    public List<Message> load(String sessionId) {
        Path file = resolve(sessionId);
        if (!file.toFile().exists()) {
            return List.of();
        }

        List<String> lines = FileUtil.readLines(file.toFile(), StandardCharsets.UTF_8);
        List<Message> messages = new ArrayList<>();

        for (String line : lines) {
            TranscriptEvent event = parseEvent(line);
            Message message = toMessage(event);
            if (message != null) {
                messages.add(message);
            }
        }

        return Collections.unmodifiableList(messages);
    }

    // ========================================================================
    //  Internal
    // ========================================================================

    private Path resolve(String sessionId) {
        return transcriptsDir.resolve(sessionId + ".jsonl");
    }

    private void appendLine(Path file, TranscriptEvent event) {
        JSONObject json = eventToJson(event);
        json.set("type", event.type());
        String line = json.toString() + System.lineSeparator();
        synchronized (fileLocks.computeIfAbsent(file.toString(), k -> new Object())) {
            FileUtil.appendString(line, file.toFile(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 手动构造 JSON — HuTool 的 {@link JSONObject#JSONObject(Object, JSONConfig)} 不支持 Java record。
     */
    private JSONObject eventToJson(TranscriptEvent event) {
        if (event instanceof TranscriptEvent.SessionMeta m) {
            return new JSONObject(NON_NULL_CONFIG)
                    .set("sessionId", m.sessionId())
                    .set("parentSessionId", m.parentSessionId())
                    .set("createdAt", m.createdAt());
        }
        if (event instanceof TranscriptEvent.User u) {
            return new JSONObject(NON_NULL_CONFIG)
                    .set("turn", u.turn())
                    .set("content", u.content());
        }
        if (event instanceof TranscriptEvent.Assistant a) {
            return new JSONObject(NON_NULL_CONFIG)
                    .set("turn", a.turn())
                    .set("step", a.step())
                    .set("content", a.content())
                    .set("toolCalls", toolCallsToJson(a.toolCalls()));
        }
        if (event instanceof TranscriptEvent.ToolResponseBatch b) {
            return new JSONObject(NON_NULL_CONFIG)
                    .set("turn", b.turn())
                    .set("step", b.step())
                    .set("responses", responsesToJson(b.responses()));
        }
        throw new RuntimeException("Unknown event type: " + event);
    }

    private JSONArray toolCallsToJson(List<TranscriptEvent.ToolCall> calls) {
        JSONArray arr = new JSONArray();
        for (TranscriptEvent.ToolCall tc : calls) {
            arr.add(new JSONObject()
                    .set("id", tc.id())
                    .set("name", tc.name())
                    .set("arguments", tc.arguments()));
        }
        return arr;
    }

    private JSONArray responsesToJson(List<TranscriptEvent.ToolResponse> responses) {
        JSONArray arr = new JSONArray();
        for (TranscriptEvent.ToolResponse r : responses) {
            arr.add(new JSONObject()
                    .set("id", r.id())
                    .set("name", r.name())
                    .set("data", r.data()));
        }
        return arr;
    }

    /**
     * 反序列化 JSON 行为 TranscriptEvent。
     * HuTool 的 toBean 不支持 Java record，手动按 {@code type} 字段派发构造。
     */
    private TranscriptEvent parseEvent(String line) {
        JSONObject obj = JSONUtil.parseObj(line);
        return switch (obj.getStr("type")) {
            case "session_meta" -> new TranscriptEvent.SessionMeta(
                    obj.getStr("sessionId"),
                    obj.getStr("parentSessionId"),
                    obj.getStr("createdAt"));
            case "user" -> new TranscriptEvent.User(
                    obj.getInt("turn"),
                    obj.getStr("content"));
            case "assistant" -> new TranscriptEvent.Assistant(
                    obj.getInt("turn"),
                    obj.getInt("step"),
                    obj.getStr("content"),
                    parseToolCalls(obj.getJSONArray("toolCalls")));
            case "tool_response_batch" -> new TranscriptEvent.ToolResponseBatch(
                    obj.getInt("turn"),
                    obj.getInt("step"),
                    parseToolResponses(obj.getJSONArray("responses")));
            default -> throw new RuntimeException("Unknown event type: " + obj.getStr("type"));
        };
    }

    private List<TranscriptEvent.ToolCall> parseToolCalls(JSONArray arr) {
        if (arr == null) return List.of();
        return arr.stream()
                .map(o -> (JSONObject) o)
                .map(j -> new TranscriptEvent.ToolCall(
                        j.getStr("id"), j.getStr("name"), j.getStr("arguments")))
                .toList();
    }

    private List<TranscriptEvent.ToolResponse> parseToolResponses(JSONArray arr) {
        if (arr == null) return List.of();
        return arr.stream()
                .map(o -> (JSONObject) o)
                .map(j -> new TranscriptEvent.ToolResponse(
                        j.getStr("id"), j.getStr("name"), j.getStr("data")))
                .toList();
    }

    /**
     * 将 TranscriptEvent 转换为 Spring AI Message。
     * SessionMeta 返回 null（跳过）。
     */
    private Message toMessage(TranscriptEvent event) {
        if (event instanceof TranscriptEvent.SessionMeta) {
            return null;
        }
        if (event instanceof TranscriptEvent.User user) {
            return new UserMessage(user.content());
        }
        if (event instanceof TranscriptEvent.Assistant assistant) {
            List<AssistantMessage.ToolCall> toolCalls = assistant.toolCalls().stream()
                    .map(tc -> new AssistantMessage.ToolCall(
                            tc.id(), "function", tc.name(), tc.arguments()))
                    .toList();
            return AssistantMessage.builder()
                    .content(assistant.content())
                    .toolCalls(toolCalls)
                    .build();
        }
        if (event instanceof TranscriptEvent.ToolResponseBatch batch) {
            List<ToolResponseMessage.ToolResponse> responses = batch.responses().stream()
                    .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.data()))
                    .toList();
            return ToolResponseMessage.builder()
                    .responses(responses)
                    .build();
        }
        return null;
    }
}
