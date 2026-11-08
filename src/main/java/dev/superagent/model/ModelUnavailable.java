package dev.superagent.model;

/**
 * 模型调用失败。
 *
 * <p>{@code retryable} 只在「请求确定没有被服务端处理」时为真：连接建立失败（请求没发出去）、
 * 连接建立超时、HTTP 429/503（服务端明确拒绝、未处理）。
 *
 * <p>请求超时、响应中途断开、5xx 网关错误一律**不可重试**：服务端可能已经算过这次调用，
 * 自动重试会造成重复计费，也会破坏「结果不明不自动重放」的一致语义。这类情况交给用户检查后
 * 用 {@code /resume} 显式恢复。
 */
public final class ModelUnavailable extends RuntimeException {
    private final String endpoint;
    private final String reason;
    private final boolean retryable;
    private final long retryAfterMillis;
    private final int attempts;

    public ModelUnavailable(String endpoint, String reason, boolean retryable, long retryAfterMillis,
                            int attempts, Throwable cause) {
        super(message(endpoint, reason, attempts, cause), cause);
        this.endpoint = endpoint;
        this.reason = reason;
        this.retryable = retryable;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
        this.attempts = Math.max(1, attempts);
    }

    public String endpoint() { return endpoint; }
    public String reason() { return reason; }
    public boolean retryable() { return retryable; }
    public long retryAfterMillis() { return retryAfterMillis; }
    public int attempts() { return attempts; }

    /** 给任务错误字段与运维日志用：必须能看出「打到哪、第几次、卡在哪一层」。 */
    private static String message(String endpoint, String reason, int attempts, Throwable cause) {
        StringBuilder text = new StringBuilder("模型请求失败");
        if (endpoint != null && !endpoint.isBlank()) text.append('[').append(endpoint).append(']');
        if (attempts > 1) text.append("，先后尝试 ").append(attempts).append(" 次");
        text.append("：").append(reason);
        if (cause != null) {
            String detail = cause.getMessage();
            String type = cause.getClass().getSimpleName();
            if (type == null || type.isBlank()) type = cause.getClass().getName();
            text.append("（").append(type);
            if (detail != null && !detail.isBlank()) text.append(": ").append(detail);
            text.append("）");
        }
        return text.toString();
    }
}
