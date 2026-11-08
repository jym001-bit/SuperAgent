package dev.superagent.server;

import java.util.*;

public record ServiceConfig(int port, String repository, long repositoryId, String baseBranch,
                            Set<Long> actors, String webhookSecret, String operatorToken,
                            String githubToken, String sandboxImage) {
    public ServiceConfig {
        actors = Set.copyOf(actors);
        if (port < 0 || port > 65535 || repositoryId <= 0 || actors.isEmpty()
                || actors.stream().anyMatch(id -> id <= 0))
            throw new IllegalArgumentException("端口、仓库 ID 或用户 ID 配置无效");
        if (!repository.matches("[a-zA-Z0-9-]+/[a-zA-Z0-9_.-]+")
                || repository.endsWith("/.") || repository.endsWith("/.."))
            throw new IllegalArgumentException("SUPERAGENT_GITHUB_REPO 格式必须为 owner/repo");
        if (!baseBranch.matches("[a-zA-Z0-9][a-zA-Z0-9_./-]{0,119}")
                || baseBranch.contains("..") || baseBranch.contains("//") || baseBranch.endsWith("/"))
            throw new IllegalArgumentException("SUPERAGENT_GITHUB_BASE 无效");
        if (webhookSecret.length() < 32 || operatorToken.length() < 32 || webhookSecret.equals(operatorToken)
                || githubToken.isBlank() || githubToken.equals(operatorToken) || githubToken.equals(webhookSecret))
            throw new IllegalArgumentException("Webhook 密钥和运维令牌须分别设置，至少 32 字符，并设置独立 GitHub 凭据");
    }

    public static ServiceConfig environment() {
        Map<String, String> env = System.getenv();
        return new ServiceConfig(Integer.parseInt(env.getOrDefault("SUPERAGENT_PORT", "8080")),
                required(env, "SUPERAGENT_GITHUB_REPO"), Long.parseLong(required(env, "SUPERAGENT_GITHUB_REPO_ID")),
                env.getOrDefault("SUPERAGENT_GITHUB_BASE", "main"),
                new HashSet<>(Arrays.stream(required(env, "SUPERAGENT_GITHUB_ACTOR_IDS").split(","))
                        .map(String::strip).map(Long::parseLong).toList()),
                required(env, "SUPERAGENT_GITHUB_WEBHOOK_SECRET"), required(env, "SUPERAGENT_OPERATOR_TOKEN"),
                required(env, "SUPERAGENT_GITHUB_TOKEN"), required(env, "SUPERAGENT_SANDBOX_IMAGE"));
    }

    private static String required(Map<String,String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("请设置 " + name);
        return value;
    }
}
