package dev.superagent.storage;

import dev.superagent.model.Json;
import dev.superagent.task.Task;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/** All returned objects are detached snapshots, never shared mutable task state. */
public final class Store implements AutoCloseable {
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Connection db;
    public Store(Path directory) throws Exception {
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException e) { lockChannel.close(); throw new IllegalStateException("状态目录已被另一个实例使用"); }
        if (acquired == null) { lockChannel.close(); throw new IllegalStateException("状态目录已被另一个实例使用"); }
        lock = acquired;
        Connection opened = null;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("state.db"));
            try (Statement s = opened.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, data TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT, kind TEXT, at TEXT DEFAULT CURRENT_TIMESTAMP, data TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS router_history (seq INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, content TEXT)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS incoming_comments (
                          event_key TEXT PRIMARY KEY, delivery TEXT NOT NULL, actor INTEGER NOT NULL,
                          task_id TEXT NOT NULL REFERENCES tasks(id))
                        """);
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS incoming_delivery ON incoming_comments(delivery)");
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS task_thread ON tasks(json_extract(data,'$.sourceThread'))");
                s.execute("CREATE INDEX IF NOT EXISTS task_status ON tasks(json_extract(data,'$.status'))");
                s.execute("CREATE INDEX IF NOT EXISTS publication_status ON tasks(json_extract(data,'$.publication.state'))");
                s.execute("CREATE TABLE IF NOT EXISTS service_binding (id INTEGER PRIMARY KEY CHECK(id=1), target TEXT NOT NULL)");
            }
            db = opened;
        } catch (Exception e) {
            if (opened != null) opened.close(); lock.release(); lockChannel.close(); throw e;
        }
    }
    public synchronized void create(Task task) {
        transaction(() -> insert(task));
    }
    private void insert(Task task) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("INSERT INTO tasks VALUES (?,?)")) {
            s.setString(1, task.id);
            s.setString(2, Json.text(task));
            s.executeUpdate();
        }
        event(task.id, "created", task.prompt);
    }

    public record Receipt(String taskId, boolean duplicate) {}
    public static final class IntakeFull extends RuntimeException {
        public IntakeFull(String message) { super(message); }
    }

    public synchronized void bindService(String target) {
        transaction(() -> {
            String binding = serviceBinding();
            if (binding != null) {
                if (!binding.equals(target)) throw new IllegalArgumentException("该状态目录已绑定到其他服务仓库或分支");
                return;
            }
            try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT 1 FROM tasks LIMIT 1")) {
                if (r.next()) throw new IllegalArgumentException("服务模式需要单独的空状态目录");
            }
            try (PreparedStatement s = db.prepareStatement("INSERT INTO service_binding VALUES (1,?)")) {
                s.setString(1, target);
                s.executeUpdate();
            }
        });
    }

    public synchronized void requireLocal() {
        if (serviceBinding() != null) throw new IllegalArgumentException("该状态目录属于常驻服务，请通过运维 API 操作");
    }

    private String serviceBinding() {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT target FROM service_binding WHERE id=1")) {
            return r.next() ? r.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deduplication, issue binding and the input are saved in the same transaction. */
    public synchronized Receipt receiveComment(String eventKey, String delivery, long actor,
            String thread, java.util.function.Supplier<Task> create, Consumer<Task> follow) {
        Receipt[] receipt = new Receipt[1];
        transaction(() -> {
            try (PreparedStatement s = db.prepareStatement(
                    "SELECT task_id FROM incoming_comments WHERE event_key=? OR delivery=?")) {
                s.setString(1, eventKey);
                s.setString(2, delivery);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        receipt[0] = new Receipt(r.getString(1), true);
                        return;
                    }
                }
            }
            Task task = null;
            try (PreparedStatement s = db.prepareStatement(
                    "SELECT data FROM tasks WHERE json_extract(data,'$.sourceThread')=?")) {
                s.setString(1, thread);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) task = Json.MAPPER.readValue(r.getString(1), Task.class);
                }
            }
            if (task == null) {
                try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("""
                        SELECT count(*) FROM tasks
                        WHERE json_extract(data,'$.status') IN ('QUEUED','RUNNING','WAITING_INPUT','WAITING_APPROVAL','READY')
                        """)) {
                    if (r.next() && r.getLong(1) >= 100) throw new IntakeFull("活跃任务已达 100，请先处理现有任务");
                }
                task = create.get();
                task.sourceThread = thread;
                insert(task);
            } else {
                if (task.inputs.size() >= 256) throw new IntakeFull("该工单的输入记录已达上限");
                follow.accept(task);
                save(task);
            }
            try (PreparedStatement s = db.prepareStatement("INSERT INTO incoming_comments VALUES (?,?,?,?)")) {
                s.setString(1, eventKey);
                s.setString(2, delivery);
                s.setLong(3, actor);
                s.setString(4, task.id);
                s.executeUpdate();
            }
            event(task.id, "github_comment", Json.text(Map.of("actor", actor, "event", eventKey, "delivery", delivery)));
            receipt[0] = new Receipt(task.id, false);
        });
        return receipt[0];
    }
    public synchronized Task get(String id) {
        try (PreparedStatement s = db.prepareStatement("SELECT data FROM tasks WHERE id=?")) {
            s.setString(1,id);
            try (ResultSet r=s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("任务不存在: " + id);
                return Json.MAPPER.readValue(r.getString(1),Task.class);
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public synchronized List<Task> list() {
        return select("SELECT data FROM tasks ORDER BY rowid");
    }
    public synchronized List<Task> queued() {
        return select("SELECT data FROM tasks WHERE json_extract(data,'$.status')='QUEUED' ORDER BY rowid");
    }
    public synchronized List<Task> executingPlans() {
        return select("SELECT data FROM tasks WHERE json_extract(data,'$.status') IN ('PLAN_RUNNING','BLOCKED') ORDER BY rowid");
    }

    public synchronized void startPlan(String id, Map<String, Task> children) {
        transaction(() -> {
            Task parent = get(id);
            if (!parent.planned || parent.status != Task.Status.PLAN_READY)
                throw new IllegalArgumentException("计划不在待批准状态");
            for (var entry : children.entrySet()) {
                insert(entry.getValue());
                parent.children.put(entry.getKey(), entry.getValue().id);
            }
            parent.status = Task.Status.PLAN_RUNNING;
            parent.progress = "计划已批准";
            save(parent);
            event(id, "plan_approved", Json.text(parent.children));
        });
    }

    public synchronized void cancelPlan(String id, Consumer<Task> cancel) {
        transaction(() -> {
            Task parent = get(id);
            cancel.accept(parent);
            save(parent);
            event(id, "plan_canceled", "");
            for (String childId : parent.children.values()) {
                Task child = get(childId);
                if (child.status == Task.Status.COMPLETED || child.status == Task.Status.CANCELED) continue;
                cancel.accept(child);
                save(child);
                event(childId, "canceled_with_plan", id);
            }
        });
    }
    public synchronized List<Task> publications() {
        return select("SELECT data FROM tasks WHERE json_extract(data,'$.publication.state') IN ('QUEUED','SENDING') ORDER BY rowid");
    }
    public synchronized List<Task> recent() {
        return select("SELECT data FROM tasks ORDER BY rowid DESC LIMIT 100");
    }
    public synchronized List<Task> waitingApprovals() {
        return select("SELECT data FROM tasks WHERE json_extract(data,'$.status')='WAITING_APPROVAL' ORDER BY rowid");
    }
    public synchronized Map<String, Long> counts() {
        var counts = new LinkedHashMap<String, Long>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(
                "SELECT json_extract(data,'$.status'),count(*) FROM tasks GROUP BY 1")) {
            while (r.next()) counts.put(r.getString(1), r.getLong(2));
            return counts;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
    private List<Task> select(String sql) {
        List<Task> tasks = new ArrayList<>();
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery(sql)) {
            while(r.next()) tasks.add(Json.MAPPER.readValue(r.getString(1),Task.class));
            return tasks;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public synchronized Task update(String id, String kind, Consumer<Task> change) {
        Task task = get(id);
        transaction(() -> {
            change.accept(task);
            save(task);
            event(id,kind,Json.text(Map.of("status",task.status,"progress",task.progress)));
        });
        return task;
    }
    private void save(Task task) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("UPDATE tasks SET data=? WHERE id=?")) {
            s.setString(1, Json.text(task));
            s.setString(2, task.id);
            s.executeUpdate();
        }
    }
    public synchronized void routerMessage(String role,String content) {
        transaction(() -> {
            try(PreparedStatement s=db.prepareStatement("INSERT INTO router_history(role,content) VALUES (?,?)")) {
                s.setString(1,role); s.setString(2,content); s.executeUpdate();
            }
        });
    }
    public synchronized List<com.fasterxml.jackson.databind.node.ObjectNode> routerHistory() {
        List<com.fasterxml.jackson.databind.node.ObjectNode> result=new ArrayList<>();
        try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT role,content FROM (SELECT * FROM router_history ORDER BY seq DESC LIMIT 12) ORDER BY seq")) {
            while(r.next()) result.add(Json.message(r.getString(1),r.getString(2)));
            return result;
        } catch(SQLException e) { throw new IllegalStateException(e); }
    }
    private void event(String id,String kind,String data) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("INSERT INTO events(task_id,kind,data) VALUES (?,?,?)")) {
            s.setString(1,id);s.setString(2,kind);s.setString(3,data);s.executeUpdate();
        }
    }
    @FunctionalInterface private interface SqlAction { void run() throws Exception; }
    private void transaction(SqlAction action) {
        try {
            db.setAutoCommit(false); action.run(); db.commit();
        } catch(Exception e) {
            try { db.rollback(); } catch(SQLException suppressed) { e.addSuppressed(suppressed); }
            if(e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e);
        } finally { try { db.setAutoCommit(true); } catch(SQLException e) { throw new IllegalStateException(e); } }
    }
    @Override public synchronized void close() throws Exception {
        try { db.close(); } finally { try { lock.release(); } finally { lockChannel.close(); } }
    }
}
