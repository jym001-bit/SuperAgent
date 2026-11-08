package dev.superagent.task;

import java.util.regex.Pattern;

/**
 * 改动范围闸门：需求指不出「改哪里」时，不允许写入。
 *
 * <p>动机来自真实运行：需求只说「优化一下这个项目的性能」，模型会以「仓库里只有一处可优化的代码」
 * 为理由推断范围，然后直接改。这类推断在共享仓库里正是事故来源——agent 不该替用户猜目标。
 *
 * <p>闸门是机械的、可审计的：命中时写入工具直接返回拒绝结果，模型必须先用 {@code ask_user}
 * 问清目标与验收方式；用户补充了带锚点的需求后闸门自动打开。
 *
 * <p>默认严格，显式设置 {@code SUPERAGENT_SCOPE_EXPLORATORY=1} 可切到探索模式（本地人工在场时）。
 */
public final class ScopeGate {
    public static final String EXPLORATORY_ENV = "SUPERAGENT_SCOPE_EXPLORATORY";
    public static final String EXPLORATORY_PROPERTY = "superagent.scopeExploratory";

    /** 文件/路径：Hello.java、config/app.properties、debug.log */
    private static final Pattern FILE = Pattern.compile("[\\w./\\\\-]*[\\w-]\\.[A-Za-z][A-Za-z0-9]{0,7}\\b");
    /** 类.成员：StringTools.normalize、FixedQueue.size */
    private static final Pattern MEMBER = Pattern.compile("\\b[A-Za-z_$][\\w$]*\\.[A-Za-z_$][\\w$]*\\b");
    /** 驼峰标识符：UsernameValidator、Calculator */
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z][a-z0-9]+[A-Z][\\w$]*\\b");
    /** 引号内的字面量：期望输出、精确内容 */
    private static final Pattern LITERAL = Pattern.compile("[\"'`「『].+?[\"'`」』]", Pattern.DOTALL);
    /** 明确命令：mvn ... test、javac、git */
    private static final Pattern COMMAND = Pattern.compile(
            "\\b(mvn|gradle|javac|java|npm|pnpm|yarn|git|pytest|cargo|docker|tsc|dotnet)\\b");
    /** 目录式路径：src/main/java、test/java */
    private static final Pattern DIRECTORY = Pattern.compile("[\\w.-]+/[\\w./-]+");

    private ScopeGate() {}

    public record Decision(boolean allowed, String reason) {}

    /** 默认严格：只有显式开启探索模式才放行无锚点的改动。 */
    public static boolean strict() {
        if (Boolean.parseBoolean(System.getProperty(EXPLORATORY_PROPERTY, "false"))) return false;
        String raw = System.getenv(EXPLORATORY_ENV);
        return !(raw != null && java.util.Set.of("1", "true", "yes", "on")
                .contains(raw.strip().toLowerCase(java.util.Locale.ROOT)));
    }

    /** 请求里出现可定位的锚点（文件、成员、标识符、字面量、命令、目录）才算指出了改哪里。 */
    public static Decision check(String requestText) {
        if (requestText == null || requestText.isBlank())
            return new Decision(false, "需求为空，无法确定要改什么");
        if (FILE.matcher(requestText).find() || MEMBER.matcher(requestText).find()
                || IDENTIFIER.matcher(requestText).find() || LITERAL.matcher(requestText).find()
                || COMMAND.matcher(requestText).find() || DIRECTORY.matcher(requestText).find())
            return new Decision(true, "需求指出了具体目标");
        return new Decision(false,
                "需求没有指明要改哪个文件、类、方法或预期结果，仅凭「项目里只有一处可优化」这类推断不足以动手");
    }
}
