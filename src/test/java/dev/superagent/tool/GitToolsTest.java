package dev.superagent.tool;

import dev.superagent.model.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 真实的 git 仓库、真实的提交和改动：验证只读 git 工具的行为与边界。 */
class GitToolsTest {
    @TempDir Path temporary;

    @BeforeAll static void requireGit() throws Exception {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            boolean done = process.waitFor(10, TimeUnit.SECONDS);
            if (!done || process.exitValue() != 0)
                Assumptions.abort("git 不可用: " + new String(output, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Assumptions.abort("git 不可用: " + e.getMessage());
        }
    }

    @Test void statusReportsCleanAndDirtyWorkspacesWithPathFilter() throws Exception {
        Path repo = repo();
        GitTools git = new GitTools(repo);
        assertEquals("工作区干净，无未提交改动", git.execute("git_status", Json.object()));

        Files.writeString(repo.resolve("a.txt"), "two\n");
        Files.writeString(repo.resolve("new file.txt"), "new\n");
        String dirty = git.execute("git_status", Json.object());
        assertTrue(dirty.contains(" M a.txt"), dirty);
        assertTrue(dirty.contains("?? new file.txt"), dirty);

        String filtered = git.execute("git_status", Json.object().put("path", "new file.txt"));
        assertTrue(filtered.contains("?? new file.txt"), filtered);
        assertFalse(filtered.contains("a.txt"), filtered);
    }

    @Test void diffStatAndLogReflectRealHistory() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        Files.writeString(repo.resolve("b.txt"), "b1\nb2\n");
        commit(repo, "second");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\nthree\n");
        Files.writeString(repo.resolve("b.txt"), "b1 changed\nb2\n");
        GitTools git = new GitTools(repo);

        String stat = git.execute("git_diff", Json.object().put("stat", true));
        assertTrue(stat.contains("a.txt") && stat.contains("b.txt"), stat);
        assertTrue(stat.contains("2 files changed"), stat);

        String full = git.execute("git_diff", Json.object());
        assertTrue(full.contains("+three"), full);
        assertTrue(full.contains("+b1 changed"), full);

        assertEquals(2, git.execute("git_log", Json.object()).strip().split("\n").length);
        assertEquals(2, git.execute("git_log", Json.object().put("count", 999)).strip().split("\n").length);
        assertEquals(1, git.execute("git_log", Json.object().put("count", 0)).strip().split("\n").length);
    }

    @Test void pathspecIsNeverInterpretedByAShell() throws Exception {
        Path repo = repo();
        Path weird = repo.resolve("weird; touch pwned.txt");
        Files.writeString(weird, "before\n");
        commit(repo, "weird name");
        Files.writeString(weird, "after\n");
        Files.writeString(repo.resolve("a.txt"), "one changed\n");
        GitTools git = new GitTools(repo);

        String diff = git.execute("git_diff", Json.object().put("path", "weird; touch pwned.txt"));
        assertTrue(diff.contains("+after"), diff);
        assertFalse(diff.contains("a.txt"), diff);
        assertFalse(Files.exists(repo.resolve("pwned.txt")));

        for (String payload : List.of("; rm -rf /", "$(touch pwned2.txt)", "`touch pwned3.txt`"))
            assertEquals("无差异", git.execute("git_diff", Json.object().put("path", payload)), payload);
        assertFalse(Files.exists(repo.resolve("pwned2.txt")));
        assertFalse(Files.exists(repo.resolve("pwned3.txt")));
        assertTrue(git.execute("git_status", Json.object().put("path", "; rm -rf /")).startsWith("路径干净"));
    }

    @Test void nonRepositoryAndNestedDirectoriesFailWithClearMessage() throws Exception {
        Path plain = Files.createDirectories(temporary.resolve("plain"));
        IllegalArgumentException notRepo = assertThrows(IllegalArgumentException.class, () -> new GitTools(plain));
        assertTrue(notRepo.getMessage().contains("不是 git 仓库"), notRepo.getMessage());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new GitTools(temporary.resolve("missing")));
        assertTrue(missing.getMessage().contains("工作目录不存在"), missing.getMessage());

        Path nested = Files.createDirectories(repo().resolve("src"));
        IllegalArgumentException nestedError = assertThrows(IllegalArgumentException.class, () -> new GitTools(nested));
        assertTrue(nestedError.getMessage().contains("不是 git 仓库"), nestedError.getMessage());
    }

    @Test void oversizedDiffIsTruncatedWithMarker() throws Exception {
        Path repo = repo();
        StringBuilder original = new StringBuilder();
        for (int i = 0; i < 4000; i++) original.append("line ").append(i).append(" original\n");
        Files.writeString(repo.resolve("big.txt"), original.toString());
        commit(repo, "big");
        Files.writeString(repo.resolve("big.txt"), original.toString().replace(" original", " changed"));

        String diff = new GitTools(repo).execute("git_diff", Json.object());
        assertTrue(diff.startsWith("diff --git"), diff.substring(0, Math.min(60, diff.length())));
        assertTrue(diff.contains("-line 0 original"), "截断前的内容应保留");
        assertTrue(diff.endsWith("[结果已截断]"), "超长 diff 必须带截断标记");
        assertTrue(diff.length() <= 24_000 + "\n[结果已截断]".length(), "长度: " + diff.length());
    }

    @Test void invalidArgumentsAndEmptyHistoryProduceClearErrors() throws Exception {
        GitTools git = new GitTools(repo());
        assertThrows(IllegalArgumentException.class, () -> git.execute("git_status", Json.object().put("path", 1)));
        assertThrows(IllegalArgumentException.class, () -> git.execute("git_diff", Json.object().put("stat", "yes")));
        assertThrows(IllegalArgumentException.class, () -> git.execute("git_log", Json.object().put("count", "many")));
        assertThrows(IllegalArgumentException.class, () -> git.execute("git_diff", Json.object().put("path", "../outside")));
        assertThrows(IllegalArgumentException.class, () -> git.execute("git_diff", Json.object().put("path", "/etc/passwd")));
        assertThrows(IllegalArgumentException.class, () -> git.execute("git_push", Json.object()));
        assertThrows(IllegalArgumentException.class, () -> git.execute("git_status", Json.array()));

        Path empty = Files.createDirectories(temporary.resolve("empty"));
        git(empty, "init", "-q");
        IllegalStateException noHistory = assertThrows(IllegalStateException.class,
                () -> new GitTools(empty).execute("git_log", Json.object()));
        assertTrue(noHistory.getMessage().contains("git log"), noHistory.getMessage());
        assertFalse(noHistory.getMessage().contains("NullPointerException"), noHistory.getMessage());
    }

    private Path repo() throws Exception {
        Path repo = Files.createDirectories(temporary.resolve("repo"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        commit(repo, "initial");
        return repo;
    }

    private static void commit(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", message);
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git", "-c", "core.hooksPath="));
        argv.addAll(List.of(args));
        Process process = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "git 超时: " + String.join(" ", argv));
        assertEquals(0, process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }
}
