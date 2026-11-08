package dev.superagent.server;

import dev.superagent.model.*;
import dev.superagent.router.Router;
import dev.superagent.storage.Store;
import dev.superagent.task.*;
import dev.superagent.tool.DockerCommandRunner;
import dev.superagent.tool.CommandRunner;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class Service {
    private Service() {}

    public static void runLocal(Path repository, Config config) throws Exception {
        if (config.apiKey().isBlank()) throw new IllegalArgumentException("本地服务需要真实模型的 API Key");
        String token = System.getenv("SUPERAGENT_OPERATOR_TOKEN");
        if (token == null || token.length() < 32 || token.equals(config.apiKey()))
            throw new IllegalArgumentException("请设置独立的 SUPERAGENT_OPERATOR_TOKEN，至少 32 字符");
        int port = Integer.parseInt(System.getenv().getOrDefault("SUPERAGENT_PORT", "8080"));
        Path source = repository.toRealPath();
        try (Store store = new Store(config.stateDir())) {
            store.requireLocal();
            if (store.list().stream().anyMatch(task -> !source.toString().equals(task.repo)))
                throw new IllegalArgumentException("本地 API 的状态目录必须只包含所选仓库的任务");
            ModelClient model = new HttpModelClient(config);
            try (TaskManager tasks = new TaskManager(config, store, model, source, Service::logWorker,
                        CommandRunner.disabled(), true);
                 AgentServer server = new AgentServer(port, token, store, tasks, new Router(store, tasks, model))) {
                serve(tasks, null, server);
            }
        }
    }

    private static void logWorker(String message) {
        System.err.println(Json.text(java.util.Map.of("component", "worker", "message", message)));
    }

    public static void run(Path repository, Config config) throws Exception {
        if (config.apiKey().isBlank()) throw new IllegalArgumentException("服务模式需要真实模型的 API Key");
        ServiceConfig settings = ServiceConfig.environment();
        try (Store store = new Store(config.stateDir())) {
            var workspaces = new Workspaces(config.stateDir());
            workspaces.verifyGitHubSource(repository, settings.repository(), settings.baseBranch());
            store.bindService(settings.repositoryId() + ":" + settings.repository().toLowerCase(java.util.Locale.ROOT)
                    + ":" + settings.baseBranch() + ":" + repository.toRealPath());
            var docker = new DockerCommandRunner(config.stateDir(), settings.sandboxImage());
            docker.verifyAndClean();
            var github = new GitHub(settings, workspaces);
            github.verifyRepository();
            try (TaskManager tasks = new TaskManager(config, store, new HttpModelClient(config), repository,
                        Service::logWorker,
                        docker, true);
                 Publisher publisher = new Publisher(store, github);
                 AgentServer server = new AgentServer(settings, store, tasks, publisher)) {
                serve(tasks, publisher, server);
            }
        }
    }

    private static void serve(TaskManager tasks, Publisher publisher, AgentServer server) throws Exception {
        CountDownLatch shutdown = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            shutdown.countDown();
            try { stopped.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "superagent-service-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            tasks.start();
            if (publisher != null) publisher.start();
            server.start();
            System.out.println("Super-agent 已监听 127.0.0.1:" + server.port());
            shutdown.await();
        } finally {
            try {
                server.close();
                if (publisher != null) publisher.close();
                tasks.close();
            } finally {
                stopped.countDown();
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException ignored) { }
            }
        }
    }
}
