package com.yu.agent4.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bash 输出摘要器 — 将冗长的命令输出转化为结构化摘要。
 *
 * <p>pip install、大型构建等命令会产生大量低价值输出（下载进度条、编译日志），
 * 模型真正需要知道的是：成功/失败、错误在哪、最后状态。
 * 本类将原始输出压缩为包含关键信息的结构化文本。
 *
 * <p>摘要格式：
 * <pre>
 *   Command: pip install numpy
 *   Exit code: 0
 *   Status: SUCCESS
 *   Total output: 1243 lines
 *
 *   Last 60 lines:
 *     ...
 * </pre>
 *
 * <p>当输出长度低于阈值时，直接返回原始输出（不做无谓压缩）。
 */
public class BashOutputSummarizer {

    /** 触发摘要的输出字符数下限 — 短输出不做摘要 */
    private static final int MIN_OUTPUT_LENGTH = 1500;

    /** 尾部保留行数 */
    static final int DEFAULT_TAIL_LINES = 60;

    /** 错误片段最大展示数 */
    private static final int MAX_ERROR_SNIPPETS = 20;

    /** ANSI 转义序列 */
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\[[;\\d]*[ -/]*[@-~]");

    /** 错误关键词（不区分大小写） */
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(?i)(error|failed|failure|exception|traceback|fatal|cannot|denied|not found|No such|segmentation|core\\s+dumped)"
    );

    /** 警告关键词（不区分大小写） */
    private static final Pattern WARN_PATTERN = Pattern.compile(
            "(?i)(warning|warn|deprecated)"
    );

    private BashOutputSummarizer() {
    }

    /**
     * 将原始 bash 输出转化为结构化摘要。
     *
     * @param command  执行的命令
     * @param rawOutput 原始输出（stdout + stderr）
     * @param exitCode 命令退出码
     * @return 摘要文本，或短输出直接返回原始内容
     */
    /**
     * 使用默认阈值（{@value #MIN_OUTPUT_LENGTH}）的便捷方法。
     */
    public static String summarize(String command, String rawOutput, int exitCode) {
        return summarize(command, rawOutput, exitCode, MIN_OUTPUT_LENGTH);
    }

    /**
     * @param command   执行的命令
     * @param rawOutput 原始输出
     * @param exitCode  退出码
     * @param minLength 触发摘要的最小字符数（短输出不做摘要）
     */
    public static String summarize(String command, String rawOutput, int exitCode, int minLength) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return rawOutput;
        }

        if (rawOutput.length() < minLength) {
            return rawOutput;
        }

        String cleanOutput = stripAnsi(rawOutput);
        String[] lines = cleanOutput.split("\n", -1);
        int totalLines = lines.length;

        List<ErrorLine> errors = findErrorLines(lines);
        List<ErrorLine> warnings = findWarningLines(lines);

        int tailCount = Math.min(DEFAULT_TAIL_LINES, totalLines);
        String[] tail = new String[tailCount];
        System.arraycopy(lines, totalLines - tailCount, tail, 0, tailCount);

        // 构建摘要
        StringBuilder sb = new StringBuilder();

        appendHeader(sb, command, exitCode, totalLines, rawOutput.length());

        if (!errors.isEmpty()) {
            appendErrorSection(sb, "Errors", errors);
        }
        if (!warnings.isEmpty() && errors.size() <= 3) {
            // 错误多时不重复展示警告
            appendErrorSection(sb, "Warnings", warnings);
        }

        appendTail(sb, tail);

        String summary = sb.toString();

        // 如果摘要没有压缩效果，退回原始输出
        if (summary.length() >= rawOutput.length()) {
            return rawOutput;
        }

        return summary;
    }

    private static void appendHeader(StringBuilder sb, String command, int exitCode,
                                      int totalLines, int rawChars) {
        sb.append("──────────────────────────────────────────────────────\n");
        sb.append("  Command: ").append(command).append("\n");
        sb.append("  Exit code: ").append(exitCode).append("\n");
        sb.append("  Status: ").append(exitCode == 0 ? "SUCCESS" : "FAILED").append("\n");
        sb.append("  Total output: ").append(totalLines).append(" lines, ")
                .append(rawChars).append(" chars\n");
        sb.append("  (summarized from ").append(rawChars).append(" chars to ~")
                .append(sb.length() + 200).append(" chars)\n");
    }

    private static void appendErrorSection(StringBuilder sb, String label, List<ErrorLine> lines) {
        int showCount = Math.min(lines.size(), MAX_ERROR_SNIPPETS);
        sb.append("\n  ").append(label).append(" (").append(lines.size())
                .append(" found, showing ").append(showCount).append("):\n");
        for (int i = 0; i < showCount; i++) {
            ErrorLine el = lines.get(i);
            sb.append("    L").append(el.lineNum).append(": ").append(el.text).append("\n");
        }
    }

    private static void appendTail(StringBuilder sb, String[] tail) {
        sb.append("\n  Last ").append(tail.length).append(" lines:\n");
        for (String line : tail) {
            sb.append("    ").append(line).append("\n");
        }
        sb.append("──────────────────────────────────────────────────────");
    }

    /** 去除 ANSI 转义序列（颜色、光标控制等） */
    static String stripAnsi(String text) {
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    /** 找出包含错误关键词的行 */
    static List<ErrorLine> findErrorLines(String[] lines) {
        List<ErrorLine> result = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line != null && ERROR_PATTERN.matcher(line).find()) {
                result.add(new ErrorLine(i + 1, line.trim()));
            }
        }
        return result;
    }

    /** 找出包含警告关键词的行 */
    static List<ErrorLine> findWarningLines(String[] lines) {
        List<ErrorLine> result = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line != null && WARN_PATTERN.matcher(line).find()) {
                result.add(new ErrorLine(i + 1, line.trim()));
            }
        }
        return result;
    }

    static record ErrorLine(int lineNum, String text) {
    }
}
