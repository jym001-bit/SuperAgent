package dev.superagent.server;

import dev.superagent.storage.Store;
import dev.superagent.task.Task;
import java.util.concurrent.*;

/** Durable publishing intent. A lost response is reconciled against GitHub before an explicit retry. */
final class Publisher implements AutoCloseable {
    interface Remote {
        void verifyRepository() throws Exception;
        String branchCommit(String branch) throws Exception;
        boolean baseContains(String ancestor, String head) throws Exception;
        void push(Task task) throws Exception;
        String existingPull(Task task) throws Exception;
        String openDraft(Task task) throws Exception;
    }
    private final Store store;
    private final Remote remote;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor();

    Publisher(Store store, Remote remote) {
        this.store = store;
        this.remote = remote;
        for (Task task : store.publications()) {
            if (task.publication.state == Task.Publication.State.SENDING)
                uncertain(task.id, "上次发布中断，请先核对 GitHub");
        }
    }

    void start() {
        loop.scheduleWithFixedDelay(() -> {
            try { tick(); }
            catch (RuntimeException error) { System.err.println("发布队列处理失败: " + error.getClass().getSimpleName()); }
        }, 0, 1, TimeUnit.SECONDS);
    }

    void tick() {
        for (Task pending : store.publications()) {
            if (pending.publication.state != Task.Publication.State.QUEUED) continue;
            Task task = store.update(pending.id, "publication_sending",
                    t -> t.publication.state = Task.Publication.State.SENDING);
            try {
                remote.verifyRepository();
                String existing = remote.existingPull(task);
                if (existing != null) {
                    published(task.id, existing);
                    continue;
                }
                String base = remote.branchCommit(task.publication.base);
                if (base == null || (!task.baseCommit.equals(base) && !remote.baseContains(task.baseCommit, base)))
                    throw new IllegalStateException("远端基线已重写或不存在，需要重新基于当前代码验收");
                String remoteCommit = remote.branchCommit(task.publication.branch);
                if (remoteCommit == null) remote.push(task);
                if (!task.publication.commit.equals(remote.branchCommit(task.publication.branch)))
                    throw new IllegalStateException("远端分支提交与批准版本不符");
                published(task.id, remote.openDraft(task));
            } catch (Exception e) {
                uncertain(task.id, e instanceof IllegalStateException ? e.getMessage() : "发布请求失败，远端结果需核对");
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    Task retry(String id, String hash) {
        return store.update(id, "publication_retry_authorized", t -> {
            if (t.publication == null || t.publication.state != Task.Publication.State.UNCERTAIN
                    || !t.publication.patchSha256.equals(hash))
                throw new IllegalArgumentException("需要一笔待核对的发布及其原补丁哈希");
            t.publication.state = Task.Publication.State.QUEUED;
            t.publication.error = null;
        });
    }

    private void published(String id, String url) {
        store.update(id, "publication_published", t -> {
            t.publication.state = Task.Publication.State.PUBLISHED;
            t.publication.url = url;
            t.publication.error = null;
        });
    }

    private void uncertain(String id, String error) {
        store.update(id, "publication_uncertain", t -> {
            t.publication.state = Task.Publication.State.UNCERTAIN;
            t.publication.error = error;
        });
    }

    @Override public void close() {
        loop.shutdownNow();
        try { loop.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
