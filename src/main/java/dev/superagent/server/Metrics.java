package dev.superagent.server;

import dev.superagent.storage.Store;

import java.util.Map;

/**
 * Prometheus 文本格式指标。
 *
 * <p>只暴露真实采集到的数据：任务状态、队列深度、token 与调用累计、事件计数、schema 版本、进程存活时间。
 * 没有采集模型延迟直方图和审批等待时长，就不编造这两个指标——留白比假数据好。
 */
final class Metrics {
    private Metrics() {}

    static String render(Store store, long startedAtMillis) {
        StringBuilder out = new StringBuilder();
        Map<String, Long> statuses = store.counts();

        out.append("# HELP superagent_tasks 按状态统计的任务数\n");
        out.append("# TYPE superagent_tasks gauge\n");
        long total = 0;
        for (var entry : statuses.entrySet()) {
            total += entry.getValue();
            out.append("superagent_tasks{status=\"").append(entry.getKey()).append("\"} ")
                    .append(entry.getValue()).append('\n');
        }

        out.append("# HELP superagent_tasks_total 任务总数\n");
        out.append("# TYPE superagent_tasks_total gauge\n");
        out.append("superagent_tasks_total ").append(total).append('\n');

        out.append("# HELP superagent_queue_depth 排队中的任务数\n");
        out.append("# TYPE superagent_queue_depth gauge\n");
        out.append("superagent_queue_depth ").append(statuses.getOrDefault("QUEUED", 0L)).append('\n');

        out.append("# HELP superagent_waiting_approvals 等待命令审批的任务数\n");
        out.append("# TYPE superagent_waiting_approvals gauge\n");
        out.append("superagent_waiting_approvals ").append(statuses.getOrDefault("WAITING_APPROVAL", 0L)).append('\n');

        out.append("# HELP superagent_tokens_total 任务累计 token\n");
        out.append("# TYPE superagent_tokens_total counter\n");
        out.append("superagent_tokens_total ").append(store.totalTokens()).append('\n');

        out.append("# HELP superagent_model_calls_total 任务累计模型调用次数\n");
        out.append("# TYPE superagent_model_calls_total counter\n");
        out.append("superagent_model_calls_total ").append(store.totalModelCalls()).append('\n');

        out.append("# HELP superagent_events_total 按类型统计的审计事件数\n");
        out.append("# TYPE superagent_events_total counter\n");
        for (var entry : store.eventCounts().entrySet())
            out.append("superagent_events_total{kind=\"").append(entry.getKey()).append("\"} ")
                    .append(entry.getValue()).append('\n');

        out.append("# HELP superagent_schema_version 状态库 schema 版本\n");
        out.append("# TYPE superagent_schema_version gauge\n");
        out.append("superagent_schema_version ").append(store.schemaVersion()).append('\n');

        out.append("# HELP superagent_uptime_seconds 进程存活秒数\n");
        out.append("# TYPE superagent_uptime_seconds gauge\n");
        out.append("superagent_uptime_seconds ")
                .append((System.currentTimeMillis() - startedAtMillis) / 1000).append('\n');
        return out.toString();
    }
}
