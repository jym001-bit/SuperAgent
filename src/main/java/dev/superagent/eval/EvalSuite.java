package dev.superagent.eval;

import dev.superagent.model.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 数据集：一组 id 唯一、判据合法的 {@link EvalTask}。 */
public final class EvalSuite {
    private final List<EvalTask> tasks;

    private EvalSuite(List<EvalTask> tasks) {
        Set<String> ids = new HashSet<>();
        for (EvalTask task : tasks)
            if (!ids.add(task.id())) throw new IllegalArgumentException("任务 id 重复: " + task.id());
        this.tasks = List.copyOf(tasks);
    }

    public static EvalSuite of(List<EvalTask> tasks) {
        if (tasks == null || tasks.isEmpty()) throw new IllegalArgumentException("评测数据集不能为空");
        return new EvalSuite(tasks);
    }

    /** 读取目录下所有 *.json，按文件名排序，保证每次运行顺序一致。 */
    public static EvalSuite load(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("评测数据集目录不存在: " + root);
        List<Path> files;
        try (Stream<Path> listing = Files.list(root)) {
            files = listing.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().toList();
        }
        if (files.isEmpty()) throw new IllegalArgumentException("评测数据集目录没有 .json 文件: " + root);
        List<EvalTask> tasks = new ArrayList<>();
        for (Path file : files) {
            try {
                tasks.add(EvalTask.fromJson(Json.parse(Files.readString(file, StandardCharsets.UTF_8))));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(file.getFileName() + " 解析失败: " + error.getMessage(), error);
            }
        }
        return new EvalSuite(tasks);
    }

    public List<EvalTask> tasks() {
        return tasks;
    }

    public int size() {
        return tasks.size();
    }

    /** 只保留指定 id，未找到的 id 明确报错（避免拼错 id 后静默跑全量）。 */
    public EvalSuite only(List<String> ids) {
        if (ids == null || ids.isEmpty()) return this;
        List<EvalTask> selected = new ArrayList<>();
        for (String id : ids) {
            EvalTask match = tasks.stream().filter(task -> task.id().equals(id.strip())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("数据集里没有任务: " + id));
            selected.add(match);
        }
        return new EvalSuite(selected);
    }
}
