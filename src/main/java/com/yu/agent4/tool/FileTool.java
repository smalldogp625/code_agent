package com.yu.agent4.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FileTool {

    private static final int MAX_OUTPUT_CHARS = 50_000;

    @Tool(
            name = "read",
            description = "读取工作区内的文本文件，可选返回前 limit 行，超出部分会提示剩余行数。"
    )
    public String read(
            @ToolParam(description = "相对工作区的文件路径") String path,
            @ToolParam(description = "最多返回多少行，传 null 或不传表示返回全部") Integer limit) {
        try {
            Path safe = safePath(path);
            byte[] bytes = Files.readAllBytes(safe);
            String text = decodeWithFallback(bytes);
            List<String> allLines = text.lines().toList();
            List<String> lines = new ArrayList<>(allLines);
            if (limit != null && limit > 0 && limit < lines.size()) {
                lines = new ArrayList<>(allLines.subList(0, limit));
                lines.add("... (" + (allLines.size() - limit) + " more lines)");
            }
            return truncate(String.join("\n", lines));
        }
        catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(
            name = "write",
            description = "向工作区内文件写入完整内容；如果父目录不存在会自动创建。写入后必须立即用 read 工具验证内容是否正确。"
    )
    public String write(
            @ToolParam(description = "相对工作区的文件路径") String path,
            @ToolParam(description = "要写入的完整文本内容") String content) {
        try {
            Path filePath = safePath(path);
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            return "Wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + path;
        }
        catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(
            name = "edit",
            description = "在工作区内编辑文件，将首次出现的 old_text 替换为 new_text；如果未找到则返回错误。编辑后必须立即用 read 工具验证结果是否正确。"
    )
    public String edit(
            @ToolParam(description = "相对工作区的文件路径") String path,
            @ToolParam(description = "要被替换的原始文本") String oldText,
            @ToolParam(description = "替换后的新文本") String newText) {
        try {
            Path filePath = safePath(path);
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }

            String updatedContent = content.replaceFirst(
                    Pattern.quote(oldText),
                    Matcher.quoteReplacement(newText)
            );
            Files.writeString(filePath, updatedContent, StandardCharsets.UTF_8);
            return "Edited " + path;
        }
        catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // 统一将相对路径解析到当前工作区，并阻止越界访问。
    private Path safePath(String path) {
        Path workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path resolvedPath = workspace.resolve(path).normalize();
        if (!resolvedPath.startsWith(workspace)) {
            throw new IllegalArgumentException("Path escapes workspace: " + path);
        }
        return resolvedPath;
    }

    private String truncate(String output) {
        if (output == null || output.isEmpty()) {
            return "(no output)";
        }
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS);
    }

    /** 尝试 UTF-8 解码，失败时回退到系统默认编码（如 Windows 的 GBK） */
    private static String decodeWithFallback(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.defaultCharset());
        }
    }
}
