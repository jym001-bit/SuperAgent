package dev.superagent.storage;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.superagent.model.Json;
import dev.superagent.task.Task;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 持久化内核。
 *
 * <p>企业级取舍：任务骨架（状态、计划、审批、计数、结果）与对话、工具调用分表保存。
 * 状态推进只重写骨架，对话按 seq 追加，避免「历史越长、每步越慢」。
 * 写入串行（单写连接 + 写锁），读取走线程本地只读连接，依赖 WAL 快照隔离。
 * 所有返回值都是脱离数据库的副本，后台线程拿不到可修改的共享引用。
 */
public final class Store implements AutoCloseable {
    private static final int SCHEMA_VERSION = 2;
    private static final int READER_CACHE = 64;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Connection writer;
    private final Path databaseFile;
    private final List<Connection> readers = Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<Connection> threadReader = new ThreadLocal<>();

    public Store(Path directory) throws Exception {
        Files.createDirectories(directory);
        databaseFile = directory.resolve("state.db").toAbsolutePath();
        lockChannel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException e) { lockChannel.close(); throw new IllegalStateException("状态目录已被另一个实例使用"); }
        if (acquired == null) { lockChannel.close(); throw new IllegalStateException("状态目录已被另一个实例使用"); }
        lock = acquired;
        Connection opened = null;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("state.db"));
            writer = opened;
            migrate(directory.resolve("state.db"));
        } catch (Exception e) {
            if (opened != null) opened.close(); lock.release(); lockChannel.close(); throw e;
        }
    }

    // ---------------------------------------------------------------- schema

    /** 旧库就地升级；新建库直接是 v2。迁移前留一份 v1 备份，迁移可重复执行。 */
    private void migrate(Path database) throws Exception {
        try (Statement s = writer.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
        boolean hadTasks = tableExists("tasks");
        int version = userVersion();
        boolean legacy = hadTasks && !columnExists("tasks", "status");
        if (legacy && version < SCHEMA_VERSION) backup(database);
        createStatements();
        if (legacy) {
            try (Statement s = writer.createStatement()) {
                s.execute("ALTER TABLE tasks ADD COLUMN status TEXT");
                s.execute("ALTER TABLE tasks ADD COLUMN created_at TEXT");
                s.execute("ALTER TABLE tasks ADD COLUMN updated_at TEXT");
                s.execute("DROP INDEX IF EXISTS task_status");
            }
        }
        if (version < SCHEMA_VERSION && hadTasks) splitLegacyTasks();
        try (Statement s = writer.createStatement()) {
            s.execute("CREATE INDEX IF NOT EXISTS task_status ON tasks(status)");
            s.execute("CREATE INDEX IF NOT EXISTS task_thread ON tasks(json_extract(data,'$.sourceThread'))");
            s.execute("CREATE INDEX IF NOT EXISTS task_publication ON tasks(json_extract(data,'$.publication.state'))");
            s.execute("CREATE INDEX IF NOT EXISTS event_task ON events(task_id, seq)");
            s.execute("CREATE INDEX IF NOT EXISTS event_kind ON events(kind)");
            s.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        }
    }

    private void backup(Path database) throws java.io.IOException {
        try (Statement s = writer.createStatement()) { s.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
        catch (SQLException ignored) { /* 备份仍可进行，只是不含未检查点的 WAL */ }
        Path target = database.resolveSibling(database.getFileName() + ".v1.bak");
        Files.copy(database, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void createStatements() throws SQLException {
        try (Statement s = writer.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, status TEXT, created_at TEXT, updated_at TEXT, data TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS task_messages (task_id TEXT NOT NULL, seq INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY (task_id, seq))");
            s.execute("CREATE TABLE IF NOT EXISTS task_calls (task_id TEXT NOT NULL, seq INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY (task_id, seq))");
            s.execute("CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT, kind TEXT, at TEXT DEFAULT CURRENT_TIMESTAMP, data TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS router_history (seq INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, content TEXT)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS incoming_comments (
                      event_key TEXT PRIMARY KEY, delivery TEXT NOT NULL, actor INTEGER NOT NULL,
                      task_id TEXT NOT NULL REFERENCES tasks(id))
                    """);
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS incoming_delivery ON incoming_comments(delivery)");
            s.execute("CREATE TABLE IF NOT EXISTS service_binding (id INTEGER PRIMARY KEY CHECK(id=1), target TEXT NOT NULL)");
        }
    }

    /** v1 的整块 JSON 拆成骨架 + 追加表；重复执行安全。 */
    private void splitLegacyTasks() throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement s = writer.createStatement(); ResultSet r = s.executeQuery("SELECT id FROM tasks")) {
            while (r.next()) ids.add(r.getString(1));
        }
        transaction(() -> {
            for (String id : ids) {
                ObjectNode node;
                try (PreparedStatement s = writer.prepareStatement("SELECT data FROM tasks WHERE id=?")) {
                    s.setString(1, id);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) continue;
                        node = (ObjectNode) Json.parse(r.getString(1));
                    }
                }
                if (node.has("messages")) {
                    int seq = 0;
                    for (var message : node.get("messages")) write(MESSAGES, id, seq++, Json.text(message));
                    node.remove("messages");
                }
                if (node.has("tools")) {
                    int seq = 0;
                    for (var call : node.get("tools")) write(CALLS, id, seq++, Json.text(call));
                    node.remove("tools");
                }
                String status = node.path("status").asText("QUEUED");
                try (PreparedStatement s = writer.prepareStatement(
                        "UPDATE tasks SET data=?, status=?, updated_at=? WHERE id=?")) {
                    s.setString(1, Json.text(node));
                    s.setString(2, status);
                    s.setString(3, now());
                    s.setString(4, id);
                    s.executeUpdate();
                }
            }
        });
    }

    private static final String MESSAGES = "task_messages";
    private static final String CALLS = "task_calls";

    private void write(String table, String id, int seq, String data) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement(
                "INSERT OR REPLACE INTO " + table + "(task_id,seq,data) VALUES (?,?,?)")) {
            s.setString(1, id); s.setInt(2, seq); s.setString(3, data); s.executeUpdate();
        }
    }

    private int userVersion() {
        try (Statement s = writer.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
            return r.next() ? r.getInt(1) : 0;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private boolean tableExists(String name) {
        try (PreparedStatement s = writer.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            s.setString(1, name);
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private boolean columnExists(String table, String column) {
        try (Statement s = writer.createStatement(); ResultSet r = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (r.next()) if (column.equalsIgnoreCase(r.getString("name"))) return true;
            return false;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    /** 迁移与写路径的版本号，供诊断接口与测试读取。 */
    public int schemaVersion() { return userVersion(); }

    // ------------------------------------------------------------ connections

    private Connection db() {
        if (writeLock.isHeldByCurrentThread()) return writer;
        Connection existing = threadReader.get();
        if (existing != null) return existing;
        try {
            Connection opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            try (Statement s = opened.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA query_only=ON");
            }
            readers.add(opened);
            threadReader.set(opened);
            return opened;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    // ----------------------------------------------------------------- writes

    public void create(Task task) {
        writing(() -> transaction(() -> insert(task)));
    }

    private void insert(Task task) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO tasks VALUES (?,?,?,?,?)")) {
            s.setString(1, task.id);
            s.setString(2, task.status.name());
            s.setString(3, task.createdAt);
            s.setString(4, now());
            s.setString(5, skeletonJson(task));
            s.executeUpdate();
        }
        syncCalls(task);
        appendMessages(task, 0);
        event(task.id, "created", task.prompt);
    }

    public Task update(String id, String kind, Consumer<Task> change) {
        return update(id, kind, change, false);
    }

    /** 只有确实在追加对话时才用这个；其余状态推进走 {@link #update}，不读不写消息表。 */
    public Task updateConversation(String id, String kind, Consumer<Task> change) {
        return update(id, kind, change, true);
    }

    public Task update(String id, String kind, Consumer<Task> change, boolean withMessages) {
        return writing(() -> {
            Task task = load(id, withMessages);
            int base = withMessages ? task.messages.size() : -1;
            transaction(() -> {
                change.accept(task);
                save(task, base);
                event(id, kind, Json.text(Map.of("status", task.status, "progress", task.progress)));
            });
            return task;
        });
    }

    private void save(Task task, int messageBase) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement(
                "UPDATE tasks SET data=?, status=?, updated_at=? WHERE id=?")) {
            s.setString(1, skeletonJson(task));
            s.setString(2, task.status.name());
            s.setString(3, now());
            s.setString(4, task.id);
            s.executeUpdate();
        }
        syncCalls(task);
        if (messageBase >= 0) appendMessages(task, messageBase);
    }

    private void syncCalls(Task task) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("DELETE FROM task_calls WHERE task_id=? AND seq>=?")) {
            s.setString(1, task.id); s.setInt(2, task.tools.size()); s.executeUpdate();
        }
        for (int i = 0; i < task.tools.size(); i++) write(CALLS, task.id, i, Json.text(task.tools.get(i)));
    }

    private void appendMessages(Task task, int base) throws SQLException {
        for (int i = base; i < task.messages.size(); i++) write(MESSAGES, task.id, i, Json.text(task.messages.get(i)));
    }

    /** 序列化时临时摘掉对话与工具调用：它们已经是追加表里的独立行。 */
    private static String skeletonJson(Task task) {
        List<ObjectNode> messages = task.messages;
        List<Task.Call> tools = task.tools;
        task.messages = List.of();
        task.tools = List.of();
        try { return Json.text(task); }
        finally { task.messages = messages; task.tools = tools; }
    }

    private static String now() { return java.time.Instant.now().toString(); }

    // ------------------------------------------------------------------ reads

    public Task get(String id) { return load(id, true); }

    /** 状态推进只需要骨架与工具调用，不需要读整个对话。 */
    public Task skeleton(String id) { return load(id, false); }

    private Task load(String id, boolean withMessages) {
        return snapshot(connection -> {
            Task task;
            try (PreparedStatement s = connection.prepareStatement("SELECT data FROM tasks WHERE id=?")) {
                s.setString(1, id);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("任务不存在: " + id);
                    task = Json.MAPPER.readValue(r.getString(1), Task.class);
                }
            }
            task.tools = children(connection, CALLS, id, Task.Call.class);
            if (withMessages) task.messages = children(connection, MESSAGES, id, ObjectNode.class);
            return task;
        });
    }

    private <T> List<T> children(Connection connection, String table, String id, Class<T> type) {
        List<T> values = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement("SELECT data FROM " + table + " WHERE task_id=? ORDER BY seq")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) values.add(Json.MAPPER.readValue(r.getString(1), type));
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return values;
    }

    /**
     * 一次读取必须在同一个快照里完成：骨架在 tasks、对话在追加表，两条独立语句之间若有写入提交，
     * 会读到「旧骨架 + 新对话」的撕裂状态。写锁持有者已在事务内，直接复用写连接。
     */
    private <T> T snapshot(ReadAction<T> query) {
        Connection connection = db();
        if (connection == writer) {
            try { return query.run(connection); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        try {
            connection.setAutoCommit(false);
            try {
                T value = query.run(connection);
                connection.commit();
                return value;
            } catch (Exception e) {
                try { connection.rollback(); } catch (SQLException suppressed) { e.addSuppressed(suppressed); }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public List<Task> list() { return select("SELECT data FROM tasks ORDER BY rowid", false); }
    public List<Task> queued() { return select("SELECT data FROM tasks WHERE status='QUEUED' ORDER BY rowid", false); }
    public List<Task> executingPlans() {
        return select("SELECT data FROM tasks WHERE status IN ('PLAN_RUNNING','BLOCKED') ORDER BY rowid", false);
    }
    public List<Task> publications() {
        return select("SELECT data FROM tasks WHERE json_extract(data,'$.publication.state') IN ('QUEUED','SENDING') ORDER BY rowid", false);
    }
    public List<Task> recent() { return select("SELECT data FROM tasks ORDER BY rowid DESC LIMIT 100", false); }
    public List<Task> waitingApprovals() {
        return select("SELECT data FROM tasks WHERE status='WAITING_APPROVAL' ORDER BY rowid", false);
    }

    public Map<String, Long> counts() {
        var counts = new LinkedHashMap<String, Long>();
        try (Statement s = db().createStatement(); ResultSet r = s.executeQuery(
                "SELECT status,count(*) FROM tasks GROUP BY 1")) {
            while (r.next()) counts.put(r.getString(1), r.getLong(2));
            return counts;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private List<Task> select(String sql, boolean withMessages) {
        return snapshot(connection -> {
            List<String> payloads = new ArrayList<>();
            try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(sql)) {
                while (r.next()) payloads.add(r.getString(1));
            }
            List<Task> tasks = new ArrayList<>();
            for (String payload : payloads) {
                Task task = Json.MAPPER.readValue(payload, Task.class);
                task.tools = children(connection, CALLS, task.id, Task.Call.class);
                if (withMessages) task.messages = children(connection, MESSAGES, task.id, ObjectNode.class);
                tasks.add(task);
            }
            return tasks;
        });
    }

    // ------------------------------------------------------------- lifecycle

    public record ArchiveSummary(int tasks, int messages, int calls, int events, Path file) {}

    /**
     * 归档已结束的任务：先把完整任务与事件导出到 JSONL，再在同一个事务里删除主库记录。
     *
     * <p>安全边界：只处理 COMPLETED / CANCELED；父任务仍在运行的子任务不会被单独归档（否则父任务的
     * children 会指向不存在的任务）。导出先于删除，宁可重复归档也不丢证据。
     */
    public ArchiveSummary archiveBefore(java.time.Instant cutoff, Path directory) throws java.io.IOException {
        return writing(() -> {
            Files.createDirectories(directory);
            List<String> candidates = new ArrayList<>();
            try (PreparedStatement s = writer.prepareStatement(
                    "SELECT id FROM tasks WHERE status IN ('COMPLETED','CANCELED')"
                            + " AND coalesce(updated_at, created_at) < ? ORDER BY rowid")) {
                s.setString(1, cutoff.toString());
                try (ResultSet r = s.executeQuery()) { while (r.next()) candidates.add(r.getString(1)); }
            }
            Set<String> archive = new LinkedHashSet<>(candidates);
            List<String> ids = new ArrayList<>();
            for (String id : candidates) {
                Task task = load(id, false);
                if (task.parentId != null && !archive.contains(task.parentId)) continue;
                ids.add(id);
            }
            if (ids.isEmpty()) return new ArchiveSummary(0, 0, 0, 0, null);

            Path file = directory.resolve("archive-" + java.time.Instant.now().toString().replace(':', '-') + ".jsonl");
            int messages = 0, calls = 0, events = 0;
            try (var out = Files.newBufferedWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
                for (String id : ids) {
                    Task task = load(id, true);
                    List<Store.Event> history = events(id, null, 0, 500);
                    messages += task.messages.size();
                    calls += task.tools.size();
                    events += history.size();
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("task", task);
                    record.put("events", history);
                    out.write(Json.text(record));
                    out.write('\n');
                }
            }
            int archivedMessages = messages, archivedCalls = calls, archivedEvents = events;
            transaction(() -> {
                for (String id : ids) {
                    delete("DELETE FROM task_messages WHERE task_id=?", id);
                    delete("DELETE FROM task_calls WHERE task_id=?", id);
                    delete("DELETE FROM events WHERE task_id=?", id);
                    delete("DELETE FROM incoming_comments WHERE task_id=?", id);
                    delete("DELETE FROM tasks WHERE id=?", id);
                }
            });
            event(null, "archived", Json.text(Map.of("tasks", ids.size(), "file", file.getFileName().toString())));
            return new ArchiveSummary(ids.size(), archivedMessages, archivedCalls, archivedEvents, file);
        });
    }

    private void delete(String sql, String id) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement(sql)) {
            s.setString(1, id);
            s.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ audit

    public record Event(long seq, String taskId, String kind, String at, String data) {}

    /** 审计查询：按任务、类型游标分页。运维接口与成本报表共用。 */
    public List<Event> events(String taskId, String kind, long afterSeq, int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        StringBuilder sql = new StringBuilder("SELECT seq,task_id,kind,at,data FROM events WHERE seq>?");
        if (taskId != null && !taskId.isBlank()) sql.append(" AND task_id=?");
        if (kind != null && !kind.isBlank()) sql.append(" AND kind=?");
        sql.append(" ORDER BY seq LIMIT ?");
        try (PreparedStatement s = db().prepareStatement(sql.toString())) {
            int index = 1;
            s.setLong(index++, afterSeq);
            if (taskId != null && !taskId.isBlank()) s.setString(index++, taskId);
            if (kind != null && !kind.isBlank()) s.setString(index++, kind);
            s.setInt(index, bounded);
            List<Event> events = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) events.add(new Event(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)));
            }
            return events;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public Map<String, Long> eventCounts() {
        var counts = new LinkedHashMap<String, Long>();
        try (Statement s = db().createStatement(); ResultSet r = s.executeQuery(
                "SELECT kind,count(*) FROM events GROUP BY 1 ORDER BY 2 DESC")) {
            while (r.next()) counts.put(r.getString(1), r.getLong(2));
            return counts;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public long totalTokens() {
        try (Statement s = db().createStatement();
             ResultSet r = s.executeQuery("SELECT coalesce(sum(json_extract(data,'$.tokens')),0) FROM tasks")) {
            return r.next() ? r.getLong(1) : 0;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public long totalModelCalls() {
        try (Statement s = db().createStatement();
             ResultSet r = s.executeQuery("SELECT coalesce(sum(json_extract(data,'$.calls')),0) FROM tasks")) {
            return r.next() ? r.getLong(1) : 0;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    // -------------------------------------------------------------- plans etc

    public void startPlan(String id, Map<String, Task> children) {
        writing(() -> transaction(() -> {
            Task parent = load(id, false);
            if (!parent.planned || parent.status != Task.Status.PLAN_READY)
                throw new IllegalArgumentException("计划不在待批准状态");
            for (var entry : children.entrySet()) {
                insert(entry.getValue());
                parent.children.put(entry.getKey(), entry.getValue().id);
            }
            parent.status = Task.Status.PLAN_RUNNING;
            parent.progress = "计划已批准";
            save(parent, -1);
            event(id, "plan_approved", Json.text(parent.children));
        }));
    }

    public void cancelPlan(String id, Consumer<Task> cancel) {
        writing(() -> transaction(() -> {
            Task parent = load(id, false);
            cancel.accept(parent);
            save(parent, -1);
            event(id, "plan_canceled", "");
            for (String childId : parent.children.values()) {
                Task child = load(childId, false);
                if (child.status == Task.Status.COMPLETED || child.status == Task.Status.CANCELED) continue;
                cancel.accept(child);
                save(child, -1);
                event(childId, "canceled_with_plan", id);
            }
        }));
    }

    public record Receipt(String taskId, boolean duplicate) {}
    public static final class IntakeFull extends RuntimeException {
        public IntakeFull(String message) { super(message); }
    }

    public void bindService(String target) {
        writing(() -> transaction(() -> {
            String binding = serviceBinding();
            if (binding != null) {
                if (!binding.equals(target)) throw new IllegalArgumentException("该状态目录已绑定到其他服务仓库或分支");
                return;
            }
            try (Statement s = writer.createStatement(); ResultSet r = s.executeQuery("SELECT 1 FROM tasks LIMIT 1")) {
                if (r.next()) throw new IllegalArgumentException("服务模式需要单独的空状态目录");
            }
            try (PreparedStatement s = writer.prepareStatement("INSERT INTO service_binding VALUES (1,?)")) {
                s.setString(1, target);
                s.executeUpdate();
            }
        }));
    }

    public void requireLocal() {
        if (serviceBinding() != null) throw new IllegalArgumentException("该状态目录属于常驻服务，请通过运维 API 操作");
    }

    private String serviceBinding() {
        try (Statement s = db().createStatement(); ResultSet r = s.executeQuery("SELECT target FROM service_binding WHERE id=1")) {
            return r.next() ? r.getString(1) : null;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    /** 去重、工单绑定与首条输入在同一个事务里完成。 */
    public Receipt receiveComment(String eventKey, String delivery, long actor,
            String thread, java.util.function.Supplier<Task> create, Consumer<Task> follow) {
        return writing(() -> {
            Receipt[] receipt = new Receipt[1];
            transaction(() -> {
                try (PreparedStatement s = writer.prepareStatement(
                        "SELECT task_id FROM incoming_comments WHERE event_key=? OR delivery=?")) {
                    s.setString(1, eventKey);
                    s.setString(2, delivery);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) { receipt[0] = new Receipt(r.getString(1), true); return; }
                    }
                }
                Task task = null;
                try (PreparedStatement s = writer.prepareStatement(
                        "SELECT id FROM tasks WHERE json_extract(data,'$.sourceThread')=?")) {
                    s.setString(1, thread);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) task = load(r.getString(1), false);
                    }
                }
                if (task == null) {
                    try (Statement s = writer.createStatement(); ResultSet r = s.executeQuery("""
                            SELECT count(*) FROM tasks
                            WHERE status IN ('QUEUED','RUNNING','WAITING_INPUT','WAITING_APPROVAL','READY')
                            """)) {
                        if (r.next() && r.getLong(1) >= 100) throw new IntakeFull("活跃任务已达 100，请先处理现有任务");
                    }
                    Task created = create.get();
                    created.sourceThread = thread;
                    insert(created);
                    task = created;
                } else {
                    if (task.inputs.size() >= 256) throw new IntakeFull("该工单的输入记录已达上限");
                    follow.accept(task);
                    save(task, -1);
                }
                try (PreparedStatement s = writer.prepareStatement("INSERT INTO incoming_comments VALUES (?,?,?,?)")) {
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
        });
    }

    // ----------------------------------------------------------------- router

    public void routerMessage(String role, String content) {
        writing(() -> transaction(() -> {
            try (PreparedStatement s = writer.prepareStatement("INSERT INTO router_history(role,content) VALUES (?,?)")) {
                s.setString(1, role); s.setString(2, content); s.executeUpdate();
            }
        }));
    }

    public List<ObjectNode> routerHistory() {
        List<ObjectNode> result = new ArrayList<>();
        try (Statement s = db().createStatement(); ResultSet r = s.executeQuery(
                "SELECT role,content FROM (SELECT * FROM router_history ORDER BY seq DESC LIMIT 12) ORDER BY seq")) {
            while (r.next()) result.add(Json.message(r.getString(1), r.getString(2)));
            return result;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------- primitives

    private void event(String id, String kind, String data) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("INSERT INTO events(task_id,kind,data) VALUES (?,?,?)")) {
            s.setString(1, id); s.setString(2, kind); s.setString(3, data); s.executeUpdate();
        }
    }

    @FunctionalInterface private interface SqlAction { void run() throws Exception; }
    @FunctionalInterface private interface SqlQuery<T> { T run() throws Exception; }
    @FunctionalInterface private interface ReadAction<T> { T run(Connection connection) throws Exception; }

    private void writing(SqlAction action) {
        writeLock.lock();
        try { action.run(); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
        finally { writeLock.unlock(); }
    }

    private <T> T writing(SqlQuery<T> action) {
        writeLock.lock();
        try { return action.run(); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
        finally { writeLock.unlock(); }
    }

    private void transaction(SqlAction action) {
        try {
            writer.setAutoCommit(false);
            action.run();
            writer.commit();
        } catch (Exception e) {
            try { writer.rollback(); } catch (SQLException suppressed) { e.addSuppressed(suppressed); }
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e);
        } finally {
            try { writer.setAutoCommit(true); } catch (SQLException e) { throw new IllegalStateException(e); }
        }
    }

    @Override public void close() throws Exception {
        writeLock.lock();
        try {
            synchronized (readers) { for (Connection reader : readers) reader.close(); readers.clear(); }
            try { threadReader.remove(); } catch (RuntimeException ignored) { }
            writer.close();
        } finally {
            try { lock.release(); } finally { lockChannel.close(); writeLock.unlock(); }
        }
    }
}
