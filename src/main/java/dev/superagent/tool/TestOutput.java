package dev.superagent.tool;

import java.util.*;
import java.util.regex.*;

/** 把真实构建输出压缩成结构化摘要；只做 best-effort 解析，任何解析失败都退化为原文末尾，不抛异常。 */
public final class TestOutput {
    private TestOutput() {}

    public record Summary(int exitCode, boolean timedOut, String command, List<String> failures,
                          int testsRun, String digest, String tail) {}

    private static final int MAX_FAILURES = 20;
    private static final int MAX_FAILURE_LENGTH = 160;
    private static final String TRUNCATED = "...";

    private static final Pattern SUREFIRE_LOCATION =
            Pattern.compile("^\\s*\\[ERROR\\]\\s+([A-Za-z_$][\\w$.]*)\\.([A-Za-z_$][\\w$]*):(\\d+)");
    private static final Pattern SUREFIRE_HEADER =
            Pattern.compile("^\\s*\\[ERROR\\]\\s+([A-Za-z_$][\\w$.]*)\\.([A-Za-z_$][\\w$]*)\\s+(?:--|<<<|Time elapsed)");
    private static final Pattern SUREFIRE_ERROR =
            Pattern.compile("^\\s*\\[ERROR\\]\\s+([A-Za-z_$][\\w$]*)\\(([A-Za-z_$][\\w$.]*)\\)");
    private static final Pattern FAILED_LINE = Pattern.compile("^\\s*(\\S.*?)\\s+FAILED\\s*$");
    private static final Pattern GO_FAIL = Pattern.compile("^\\s*--- FAIL: (\\S+)");
    private static final Pattern JAVAC_ERROR = Pattern.compile("^\\s*([^\\s:]+\\.java):(\\d+):\\s*error:\\s*(.*)$");
    private static final Pattern GO_COMPILE = Pattern.compile("^\\s*([\\w./-]+\\.go):(\\d+):(\\d+):\\s*(.*)$");
    private static final Pattern PYTEST_FAILED = Pattern.compile("^\\s*FAILED\\s+(\\S+)");
    private static final Pattern RUST_ERROR = Pattern.compile("^\\s*(error(?:\\[[^\\]]+\\])?:\\s*.+)$");
    private static final Pattern SUREFIRE_COUNT = Pattern.compile("Tests run:\\s*(\\d+)");
    private static final Pattern CARGO_COUNT = Pattern.compile("test result: (?:ok|FAILED)\\. (\\d+) passed; (\\d+) failed");
    private static final Pattern GRADLE_COUNT = Pattern.compile("(\\d+) tests? completed");
    private static final Pattern JEST_COUNT = Pattern.compile("Tests:\\s+.*?(\\d+) total");
    private static final Pattern DECLARED_FAILURES = Pattern.compile("(?:Failures|Errors):\\s*(\\d+)");

    public static Summary parse(String command, String output, int exitCode, boolean timedOut, int maxTail) {
        String safeCommand = command == null ? "" : command;
        String text = normalize(output);
        String tail = tail(text, maxTail);
        try {
            List<String> failures = List.copyOf(failures(text));
            int testsRun = testsRun(text);
            int declared = declaredFailures(text);
            return new Summary(exitCode, timedOut, safeCommand, failures, testsRun,
                    digest(safeCommand, exitCode, timedOut, failures, declared, testsRun), tail);
        } catch (Exception e) {
            return new Summary(exitCode, timedOut, safeCommand, List.of(), 0,
                    fallbackDigest(safeCommand, exitCode, timedOut), tail);
        }
    }

    private static String normalize(String output) {
        return output == null ? "" : output.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String tail(String text, int maxTail) {
        int limit = Math.max(0, maxTail);
        return text.length() <= limit ? text : text.substring(text.length() - limit);
    }

    private static Set<String> failures(String text) {
        Set<String> found = new LinkedHashSet<>();
        for (String line : text.split("\n", -1)) {
            String entry = failureOf(line);
            if (entry != null) {
                found.add(entry);
                if (found.size() >= MAX_FAILURES) break;
            }
        }
        return found;
    }

    private static String failureOf(String line) {
        Matcher location = SUREFIRE_LOCATION.matcher(line);
        if (location.find()) return failure(location.group(1), location.group(2));
        Matcher header = SUREFIRE_HEADER.matcher(line);
        if (header.find()) return failure(header.group(1), header.group(2));
        Matcher error = SUREFIRE_ERROR.matcher(line);
        if (error.find()) return failure(error.group(2), error.group(1));
        Matcher go = GO_FAIL.matcher(line);
        if (go.find()) return clean(go.group(1));
        Matcher javac = JAVAC_ERROR.matcher(line);
        if (javac.find()) return clean(javac.group(1) + ":" + javac.group(2) + ": error: " + javac.group(3));
        Matcher goCompile = GO_COMPILE.matcher(line);
        if (goCompile.find()) return clean(goCompile.group(1) + ":" + goCompile.group(2) + ":" + goCompile.group(3) + ": " + goCompile.group(4));
        Matcher pytest = PYTEST_FAILED.matcher(line);
        if (pytest.find()) return clean(pytest.group(1));
        Matcher failed = FAILED_LINE.matcher(line);
        if (failed.find()) {
            String name = failedName(failed.group(1));
            if (name != null) return name;
        }
        Matcher rust = RUST_ERROR.matcher(line);
        if (rust.find()) return clean(rust.group(1));
        return null;
    }

    /** Gradle、Cargo、Maven 的 FAILED 行都形如 “用例名 ... FAILED”；任务路径不是用例。 */
    private static String failedName(String raw) {
        String name = raw.trim();
        if (name.startsWith("> ")) name = name.substring(2).trim();
        if (name.startsWith(":") || name.startsWith("Task ") || name.equals("BUILD") || name.equals("BUILD FAILURE"))
            return null;
        boolean cargo = name.endsWith(" ...");
        if (cargo) name = name.substring(0, name.length() - 4).trim();
        if (cargo && name.startsWith("test ")) name = name.substring(5).trim();
        int arrow = name.indexOf(" > ");
        if (arrow > 0) {
            String owner = name.substring(0, arrow).trim(), method = name.substring(arrow + 3).trim();
            if (method.endsWith("()")) method = method.substring(0, method.length() - 2).trim();
            name = owner + "." + method;
        }
        return name.isBlank() ? null : clean(name);
    }

    private static String failure(String owner, String method) {
        int dot = owner.lastIndexOf('.');
        return clean((dot >= 0 ? owner.substring(dot + 1) : owner) + "." + method);
    }

    private static String clean(String value) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return text.length() <= MAX_FAILURE_LENGTH ? text
                : text.substring(0, MAX_FAILURE_LENGTH - TRUNCATED.length()) + TRUNCATED;
    }

    private static int testsRun(String text) {
        Matcher surefire = SUREFIRE_COUNT.matcher(text);
        int count = 0;
        while (surefire.find()) count = Integer.parseInt(surefire.group(1));
        if (count > 0) return count;
        Matcher cargo = CARGO_COUNT.matcher(text);
        while (cargo.find()) count = Math.max(count, Integer.parseInt(cargo.group(1)) + Integer.parseInt(cargo.group(2)));
        if (count > 0) return count;
        Matcher gradle = GRADLE_COUNT.matcher(text);
        while (gradle.find()) count = Math.max(count, Integer.parseInt(gradle.group(1)));
        if (count > 0) return count;
        Matcher jest = JEST_COUNT.matcher(text);
        while (jest.find()) count = Math.max(count, Integer.parseInt(jest.group(1)));
        return count;
    }

    private static int declaredFailures(String text) {
        Matcher matcher = DECLARED_FAILURES.matcher(text);
        int declared = 0;
        while (matcher.find()) declared = Math.max(declared, Integer.parseInt(matcher.group(1)));
        return declared;
    }

    private static String digest(String command, int exitCode, boolean timedOut, List<String> failures,
                                 int declared, int testsRun) {
        StringBuilder text = new StringBuilder(shortCommand(command)).append(" => ");
        text.append(timedOut ? "超时" : "exit=" + exitCode);
        int count = Math.max(failures.size(), declared);
        if (count > 0) {
            text.append(", 失败 ").append(count).append(" 个");
            if (failures.isEmpty()) text.append("（未提取到用例名，见输出末尾）");
            else {
                text.append(": ").append(String.join(", ", failures.subList(0, Math.min(5, failures.size()))));
                if (failures.size() > 5) text.append(" 等");
            }
        } else if (timedOut) {
            text.append(", 执行超时，输出不完整");
        } else if (exitCode == 0) {
            text.append(testsRun > 0 ? ", 测试 " + testsRun + " 个全部通过" : ", 未识别测试计数");
        } else {
            text.append(", 未识别失败用例（见输出末尾）");
        }
        return text.toString();
    }

    private static String fallbackDigest(String command, int exitCode, boolean timedOut) {
        return shortCommand(command) + " => " + (timedOut ? "超时" : "exit=" + exitCode) + "（解析失败，见输出末尾）";
    }

    private static String shortCommand(String command) {
        String text = command == null || command.isBlank() ? "test" : command.strip().replaceAll("\\s+", " ");
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}
