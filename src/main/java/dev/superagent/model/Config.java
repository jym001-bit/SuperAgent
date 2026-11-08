package dev.superagent.model;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public record Config(Path stateDir, String baseUrl, String model, String apiKey, String provider,
                     boolean thinking, int workers, int maxCalls, long maxTokens,
                     Duration commandTimeout, Duration httpTimeout) {
    public Config {
        stateDir = stateDir.toAbsolutePath().normalize();
        if (workers < 1 || maxCalls < 1 || maxTokens < 1 || commandTimeout.isNegative()
                || commandTimeout.isZero() || httpTimeout.isNegative() || httpTimeout.isZero())
            throw new IllegalArgumentException("并发数、预算和超时必须大于零");
        if (!provider.equals("deepseek") && !provider.equals("standard"))
            throw new IllegalArgumentException("SUPERAGENT_PROVIDER 必须是 deepseek 或 standard");
    }
    public static Config environment() {
        Map<String,String> e = System.getenv();
        String baseUrl = e.getOrDefault("SUPERAGENT_BASE_URL", "https://api.deepseek.com");
        String provider = e.getOrDefault("SUPERAGENT_PROVIDER", "deepseek");
        return new Config(Path.of(e.getOrDefault("SUPERAGENT_STATE_DIR",
                Path.of(System.getProperty("user.home"), ".superagent-java").toString())),
                baseUrl,
                e.getOrDefault("SUPERAGENT_MODEL", "deepseek-flash"),
                resolveKey(e, Path.of(".env"), provider, baseUrl),
                provider,
                Boolean.parseBoolean(e.getOrDefault("SUPERAGENT_THINKING", "true")),
                integer(e,"SUPERAGENT_WORKERS",2), integer(e,"SUPERAGENT_MAX_MODEL_CALLS",30),
                integer(e,"SUPERAGENT_MAX_TOKENS",100000),
                Duration.ofSeconds(integer(e,"SUPERAGENT_COMMAND_TIMEOUT_SECONDS",120)),
                Duration.ofSeconds(integer(e,"SUPERAGENT_HTTP_TIMEOUT_SECONDS",120)));
    }

    static String resolveKey(Map<String, String> env, Path localFile, String provider, String baseUrl) {
        for (String name : new String[]{"SUPERAGENT_API_KEY", "DEEPSEEK_API_KEY"}) {
            String key = env.get(name);
            if (key != null && !key.isBlank()) return key.strip();
        }
        if (!provider.equals("deepseek") || !Files.exists(localFile)) return "";
        URI endpoint = URI.create(baseUrl);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || !"api.deepseek.com".equalsIgnoreCase(endpoint.getHost())
                || endpoint.getUserInfo() != null || (endpoint.getPort() != -1 && endpoint.getPort() != 443))
            return "";
        try {
            for (String raw : Files.readAllLines(localFile)) {
                String line = raw.replaceFirst("^\uFEFF", "").strip();
                int equals = line.indexOf('=');
                if (equals < 0 || !line.substring(0, equals).strip().equals("DEEPSEEK_API_KEY")) continue;
                String key = line.substring(equals + 1).strip();
                if (!key.isBlank()) return key;
            }
            return "";
        } catch (IOException error) {
            throw new IllegalArgumentException("无法读取本地 .env 配置", error);
        }
    }
    private static int integer(Map<String,String> e, String name, int fallback) {
        try { return Integer.parseInt(e.getOrDefault(name, "" + fallback)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(name + " 必须是整数"); }
    }
}
