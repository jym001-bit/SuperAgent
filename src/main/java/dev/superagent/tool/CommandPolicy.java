package dev.superagent.tool;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 只读命令白名单：允许「明显只读」的命令免去逐次人工点击，但**仍然写入审批记录与审计事件**。
 *
 * <p>默认关闭，必须显式开启（环境变量 {@value #ENABLED_ENV} 或系统属性
 * {@code superagent.autoApproveReadOnly}）。企业部署里对只读排查类命令开启很划算，
 * 但这是一个安全敏感的开关，所以默认保持人工逐次审批。
 *
 * <p>判定顺序（任何一步不满足就退回人工）：
 * <ol>
 *   <li>命令里不能有任何 shell 元字符（重定向、管道、串联、命令替换、通配、换行）；</li>
 *   <li>首个 token 必须是白名单程序；</li>
 *   <li>程序级限制：git 只允许显式只读子命令，且参数里不能出现能执行外部程序的选项
 *       （{@code -c}、{@code --ext-diff}、{@code --output}、{@code --upload-pack} 等）。</li>
 * </ol>
 * 这份白名单是有意写窄的：漏掉的命令退回人工审批，代价只是多点一次；写宽了就是绕过审批。
 */
public final class CommandPolicy {
    public static final String ENABLED_ENV = "SUPERAGENT_AUTO_APPROVE_READONLY";
    public static final String ENABLED_PROPERTY = "superagent.autoApproveReadOnly";

    private static final Pattern METACHARACTERS = Pattern.compile("[;&|<>$`(){}\\[\\]\\n\\r%^*?!]");
    private static final Set<String> GIT_READ_ONLY = Set.of(
            "status", "diff", "log", "show", "branch", "rev-parse", "ls-files", "describe", "blame", "shortlog");
    /** 这些 git 选项可以借配置或外部程序间接执行命令，绝不放行。 */
    private static final Set<String> GIT_FORBIDDEN = Set.of(
            "-c", "--exec", "--ext-diff", "--textconv", "--output", "--upload-pack", "--receive-pack", "--config-env");
    private static final Set<String> PLAIN_READ_ONLY = Set.of(
            "pwd", "ls", "dir", "cat", "type", "head", "tail", "wc", "grep", "findstr", "stat", "file");
    private static final Set<String> VERSION_FLAGS = Set.of("-v", "--version", "-version");

    private CommandPolicy() {}

    public record Decision(boolean autoApprove, String reason) {
        public static Decision manual(String reason) { return new Decision(false, reason); }
        public static Decision auto(String reason) { return new Decision(true, reason); }
    }

    public static boolean enabled() {
        if (Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"))) return true;
        String raw = System.getenv(ENABLED_ENV);
        return raw != null && Set.of("1", "true", "yes", "on").contains(raw.strip().toLowerCase(Locale.ROOT));
    }

    public static Decision classify(String command) { return classify(command, enabled()); }

    public static Decision classify(String command, boolean autoApproveEnabled) {
        if (command == null || command.isBlank()) return Decision.manual("命令为空");
        if (!autoApproveEnabled) return Decision.manual("只读命令自动批准未开启");
        String trimmed = command.strip();
        if (trimmed.indexOf('\0') >= 0) return Decision.manual("命令包含 NUL");
        if (METACHARACTERS.matcher(trimmed).find()) return Decision.manual("命令包含 shell 元字符，必须人工审批");
        String[] tokens = trimmed.split("\\s+");
        String program = basename(tokens[0]);
        for (String token : tokens) if (token.contains("=") && program.equals("git") && token.startsWith("-"))
            return Decision.manual("git 参数里带赋值，必须人工审批");

        if (program.equals("git")) {
            if (tokens.length < 2 || !GIT_READ_ONLY.contains(tokens[1].toLowerCase(Locale.ROOT)))
                return Decision.manual("不是只读 git 子命令");
            for (String token : tokens)
                if (GIT_FORBIDDEN.contains(token)) return Decision.manual("git 参数 " + token + " 可能执行外部程序");
            return Decision.auto("只读 git 子命令: " + tokens[1]);
        }
        if (program.equals("mvn") || program.equals("mvnw") || program.equals("gradle") || program.equals("gradlew")) {
            for (int i = 1; i < tokens.length; i++)
                if (VERSION_FLAGS.contains(tokens[i])) return Decision.auto("构建工具版本查询");
            return Decision.manual("构建工具只有版本查询属于只读");
        }
        if (Set.of("java", "javac", "node", "npm", "python", "python3", "go", "cargo", "dotnet", "git").contains(program)) {
            for (int i = 1; i < tokens.length; i++)
                if (VERSION_FLAGS.contains(tokens[i])) return Decision.auto("工具版本查询");
            return Decision.manual("只有版本查询属于只读");
        }
        if (PLAIN_READ_ONLY.contains(program)) return Decision.auto("只读文件查看命令");
        return Decision.manual("不在只读白名单: " + program);
    }

    private static String basename(String token) {
        String value = token.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") ? lower.substring(0, lower.length() - 4) : lower;
    }

    /** 供运维文档与诊断使用：白名单里允许的程序。 */
    public static List<String> allowedPrograms() {
        List<String> programs = new java.util.ArrayList<>(PLAIN_READ_ONLY);
        programs.add("git");
        programs.addAll(List.of("mvn", "mvnw", "gradle", "gradlew",
                "java", "javac", "node", "npm", "python", "python3", "go", "cargo", "dotnet"));
        return List.copyOf(programs);
    }
}
