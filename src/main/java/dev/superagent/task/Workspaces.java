package dev.superagent.task;

import dev.superagent.tool.Processes;
import java.nio.file.*;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.*;

public final class Workspaces {
    private final Path state;
    public Workspaces(Path state) { this.state=state; }
    public record Source(Path repo,String commit,boolean dirty) {}
    public Source inspect(Path path) throws Exception {
        Path repo=path.toRealPath();
        String root=git(repo,"rev-parse","--show-toplevel").strip();
        if(!Path.of(root).toRealPath().equals(repo)) throw new IllegalArgumentException("--repo 必须是 Git 仓库根目录");
        String commit;
        try { commit=git(repo,"rev-parse","--verify","HEAD").strip(); }
        catch(IllegalArgumentException error) {
            throw new IllegalArgumentException("目标项目需至少一次提交: "+error.getMessage());
        }
        return new Source(repo,commit,!git(repo,"status","--porcelain").isBlank());
    }
    public Path path(String id) { return state.resolve("work").resolve(id); }
    public Source snapshot(Path repo) throws Exception {
        return new Source(repo, git(repo, Duration.ofSeconds(3), "rev-parse", "--verify", "HEAD").strip(), false);
    }
    public void verifyGitHubSource(Path path, String repository, String branch) throws Exception {
        Source source = inspect(path);
        String remote = git(source.repo, "remote", "get-url", "origin").strip();
        String https = "https://github.com/" + repository;
        String ssh = "git@github.com:" + repository;
        if (!Set.of(https, https + ".git", ssh, ssh + ".git").stream().anyMatch(remote::equalsIgnoreCase))
            throw new IllegalArgumentException("工作仓库 origin 与部署配置中的 GitHub 仓库不符");
        if (!git(source.repo, "symbolic-ref", "--short", "HEAD").strip().equals(branch))
            throw new IllegalArgumentException("工作仓库须检出配置的 GitHub 基线分支");
    }
    /**
     * 准备任务副本。
     *
     * <p>调用方只有在该任务 {@code workspaceReady=false} 时才会进来，所以目录里不可能有已完成的成果。
     * 因此区分两种「目录已存在」：
     * <ul>
     *   <li>不是有效 Git 仓库 —— 说明上次准备被打断（关闭进程会中断 {@code git clone}），直接重建；</li>
     *   <li>是有效仓库 —— 可能是被中断的克隆也可能是真在用的副本，一律保留并核对基线与分支。</li>
     * </ul>
     *
     * @return {@code true} 表示重建了从未完成准备的副本
     */
    public boolean prepare(Task task) throws Exception {
        Path workspace=Path.of(task.workspace);
        Files.createDirectories(workspace.getParent());
        boolean recreated=false;
        if(Files.exists(workspace) && !isRepository(workspace)) {
            deleteTree(workspace);
            recreated=true;
        }
        if(!Files.exists(workspace)) {
            git(workspace.getParent(),"clone","--no-hardlinks","--no-local","--no-checkout","--",task.repo,workspace.toString());
            git(workspace,"checkout","-b","agent/"+task.id,task.baseCommit);
        } else {
            // A crash during preparation may leave a clone. Do not erase it or reset its files.
            if(!git(workspace,"rev-parse","HEAD").strip().equals(task.baseCommit))
                throw new IllegalStateException("任务副本基线不符，请检查目录: "+workspace);
            String branch=git(workspace,"branch","--show-current").strip();
            if(!branch.equals("agent/"+task.id)) git(workspace,"checkout","-b","agent/"+task.id,task.baseCommit);
        }
        return recreated;
    }

    /**
     * 能解析出 HEAD 才算「可用仓库」：被打断的克隆可能留下一个 .git 目录，但解析不出提交，
     * 这种目录不能当副本用。失败不抛异常——它的用途就是决定要不要重建。
     */
    private boolean isRepository(Path directory) {
        try {
            List<String> argv=gitPrefix();
            argv.addAll(List.of("rev-parse","--verify","HEAD"));
            Processes.Result result=Processes.run(argv,directory,
                    state.resolve("logs").resolve("git-"+UUID.randomUUID()+".log"),Duration.ofSeconds(15),process->{},gitEnvironment());
            return !result.timedOut() && result.exitCode()==0;
        } catch(Exception error) {
            return false;
        }
    }

    /** 递归删除。Windows 上 git 的对象文件是只读的，需要先去掉只读位才能删。 */
    private static void deleteTree(Path root) throws Exception {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths=Files.walk(root)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); }
                catch(java.nio.file.AccessDeniedException denied) {
                    if(!path.toFile().setWritable(true)) throw denied;
                    Files.deleteIfExists(path);
                }
            }
        }
    }
    public record Delivery(String files,Path patch,List<String> validations) {}
    public static final int MAX_PATCH_BYTES = 8 * 1024 * 1024;
    public Delivery delivery(Task task) throws Exception {
        return delivery(task, state.resolve("deliveries").resolve(task.id));
    }
    private Delivery delivery(Task task, Path export) throws Exception {
        Path workspace=Path.of(task.workspace);
        // Alternate index includes untracked nonignored files without touching the worker's index.
        Files.createDirectories(export);
        Path index=export.resolve("export.index");
        Files.deleteIfExists(index);
        runGitIndex(workspace,index,"read-tree",task.baseCommit);
        runGitIndex(workspace,index,"add","-A","--",".");
        String files=runGitIndex(workspace,index,"diff","--cached","--name-status",task.baseCommit,"--");
        Path patch=export.resolve("changes.patch");
        // --output preserves binary patches and avoids Java text decoding/newline conversion.
        runGitIndex(workspace,index,"diff","--cached","--binary","--full-index","--output="+patch,task.baseCommit,"--");
        Files.deleteIfExists(index);
        return new Delivery(files,patch,List.copyOf(task.validations));
    }
    Task.PatchArtifact freeze(Task task) throws Exception {
        Path folder = state.resolve("deliveries").resolve(task.id).resolve(UUID.randomUUID().toString());
        Delivery delivery = delivery(task, folder);
        return new Task.PatchArtifact(delivery.patch().toString(), patchHash(readPatch(delivery.patch())), delivery.files());
    }

    Delivery frozenDelivery(Task task) throws Exception {
        verify(task.artifact);
        return new Delivery(task.artifact.files(), Path.of(task.artifact.path()), List.copyOf(task.validations));
    }

    private static byte[] verify(Task.PatchArtifact artifact) throws Exception {
        if (artifact == null) throw new IllegalStateException("找不到已冻结的补丁");
        byte[] bytes = readPatch(Path.of(artifact.path()));
        if (!patchHash(bytes).equals(artifact.sha256()))
            throw new IllegalStateException("冻结补丁哈希不匹配: " + artifact.path());
        return bytes;
    }

    Task attempt(Task task, String stage) {
        Task attempt = new Task();
        attempt.id = task.id;
        attempt.repo = task.repo;
        attempt.baseCommit = task.baseCommit;
        attempt.workspace = path(task.id + "-" + stage + "-" + UUID.randomUUID()).toString();
        attempt.validations = task.validations;
        return attempt;
    }

    void applyFrozen(Task attempt, Task dependency) throws Exception {
        byte[] bytes = verify(dependency.artifact);
        if (bytes.length == 0) return;
        Path workspace = Path.of(attempt.workspace);
        Path input = Files.createTempFile(workspace.resolve(".git"), "superagent-input-", ".patch");
        try {
            Files.write(input, bytes);
            git(workspace, "apply", "--index", "--binary", "--whitespace=nowarn", input.toString());
        } catch (Exception error) {
            if (error instanceof InterruptedException) throw error;
            throw new PatchConflict(dependency.nodeKey, dependency.artifact.files(), error);
        } finally {
            Files.deleteIfExists(input);
        }
    }

    void commitEffectiveBase(Task attempt) throws Exception {
        Path workspace = Path.of(attempt.workspace);
        String tree = git(workspace, "write-tree").strip();
        String commit = git(workspace, "-c", "user.name=Super-agent", "-c", "user.email=superagent@users.noreply.github.com",
                "commit-tree", tree, "-p", attempt.baseCommit, "-m", "Super-agent dependency baseline").strip();
        git(workspace, "reset", "--soft", commit);
        attempt.baseCommit = commit;
    }

    void checkResolution(Task task) throws Exception {
        Path workspace = Path.of(task.workspace);
        if (!git(workspace, "rev-parse", "HEAD").strip().equals(task.baseCommit)
                || !git(workspace, "ls-files", "-u").isBlank())
            throw new IllegalArgumentException("合成目录须保留原始 HEAD，且没有未解决的索引冲突");
    }

    static final class PatchConflict extends Exception {
        PatchConflict(String node, String files, Throwable cause) {
            super("子任务 " + node + " 的补丁无法应用。涉及文件:\n" + files + "\n" + cause.getMessage(), cause);
        }
    }
    public static String patchHash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
    public static byte[] readPatch(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            byte[] content = input.readNBytes(MAX_PATCH_BYTES + 1);
            if (content.length > MAX_PATCH_BYTES) throw new IllegalArgumentException("补丁超过 8 MiB");
            return content;
        }
    }
    public String reviewedCommit(Task task, Path patch, String expected) throws Exception {
        byte[] content = readPatch(patch);
        if (content.length == 0) throw new IllegalArgumentException("任务没有代码改动");
        if (!patchHash(content).equals(expected)) throw new IllegalArgumentException("补丁已变化，请重新下载并验收");
        Path workspace = Path.of(task.workspace);
        Path index = patch.getParent().resolve("review-" + UUID.randomUUID() + ".index");
        try {
            runGitIndex(workspace, index, "read-tree", task.baseCommit);
            runGitIndex(workspace, index, "apply", "--cached", "--whitespace=nowarn", patch.toString());
            String tree = runGitIndex(workspace, index, "write-tree").strip();
            return git(workspace, "-c", "user.name=Super-agent", "-c", "user.email=superagent@users.noreply.github.com",
                    "commit-tree", tree, "-p", task.baseCommit, "-m", "Super-agent " + task.id).strip();
        } finally {
            Files.deleteIfExists(index);
        }
    }
    public void pushPublication(Task task, String repository, String token) throws Exception {
        Task.Publication p = task.publication;
        String url = "https://github.com/" + repository + ".git";
        String ref = "refs/heads/" + p.branch;
        var args = gitPrefix();
        args.addAll(List.of("push", "--porcelain", "--force-with-lease=" + ref + ":", url, p.commit + ":" + ref));
        Map<String,String> env = gitEnvironment();
        String credentials = Base64.getEncoder().encodeToString(
                ("x-access-token:" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        env.put("GIT_CONFIG_COUNT", "2");
        env.put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
        env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + credentials);
        env.put("GIT_CONFIG_KEY_1", "http.followRedirects");
        env.put("GIT_CONFIG_VALUE_1", "false");
        var result = Processes.run(args, Path.of(task.workspace),
                state.resolve("logs").resolve("publish-" + task.id + ".log"), Duration.ofSeconds(90), process -> {}, env);
        if (result.timedOut() || result.exitCode() != 0) throw new IllegalStateException("Git 推送未确认，查看发布日志");
    }
    private String runGitIndex(Path cwd,Path index,String... args) throws Exception {
        List<String> argv=gitPrefix();
        argv.addAll(List.of(args));
        Path log=state.resolve("logs").resolve("git-"+UUID.randomUUID()+".log");
        Map<String,String> environment=gitEnvironment();environment.put("GIT_INDEX_FILE",index.toAbsolutePath().toString());
        var result=Processes.run(argv,cwd,log,Duration.ofSeconds(30),p->{},environment);
        if(result.timedOut()||result.exitCode()!=0)throw new IllegalStateException("Git 导出失败: "+result.output());
        return result.output();
    }
    private String git(Path cwd,String... args) throws Exception {
        return git(cwd, Duration.ofSeconds(60), args);
    }
    private String git(Path cwd, Duration timeout, String... args) throws Exception {
        List<String> argv=gitPrefix();
        argv.addAll(List.of(args));
        Processes.Result r=Processes.run(argv,cwd,state.resolve("logs").resolve("git-"+UUID.randomUUID()+".log"),timeout,p->{},gitEnvironment());
        // 错误信息必须指出是哪条命令、哪个目录失败；不要把所有 git 失败都归结为「需要提交」。
        if(r.timedOut()) throw new IllegalStateException("Git 命令超时（"+timeout.toSeconds()+" 秒）: git "
                +String.join(" ",args)+"（目录: "+cwd+"）");
        if(r.exitCode()!=0) throw new IllegalArgumentException("Git 操作失败: "+r.output().strip()
                +"（命令: git "+String.join(" ",args)+"，目录: "+cwd+"）");
        return r.output();
    }
    private static List<String> gitPrefix() {
        return new ArrayList<>(List.of("git","-c","core.hooksPath=","-c","core.fsmonitor=false","-c","core.autocrlf=false","-c","core.quotepath=false","--no-pager","--no-optional-locks"));
    }
    private static Map<String,String> gitEnvironment() {
        return new HashMap<>(Map.of("GIT_CONFIG_NOSYSTEM","1","GIT_CONFIG_GLOBAL",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"NUL":"/dev/null","GIT_TERMINAL_PROMPT","0"));
    }
}
