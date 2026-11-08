package dev.superagent.eval;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.superagent.model.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.superagent.eval.EvalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 判据本身的语义：no_changes 真的发现改动，command 真的执行命令，文件判据真的比较内容。 */
class EvalChecksTest {
    @TempDir Path temporary;

    @Test
    void noChangesDetectsModifiedUntrackedAndCommittedFiles() throws Exception {
        Path repository = repository(temporary.resolve("repo"), Map.of("tracked.txt", "original\n"));
        String base = head(repository);

        assertTrue(evaluate(repository, base, check("no_changes")).get(0).passed());

        Files.writeString(repository.resolve("tracked.txt"), "tampered\n");
        EvalChecks.Outcome modified = evaluate(repository, base, check("no_changes")).get(0);
        assertFalse(modified.passed());
        assertTrue(modified.detail().contains("tracked.txt"), modified.detail());

        git(repository, "checkout", "--", "tracked.txt");
        Files.writeString(repository.resolve("untracked.txt"), "sneaky\n");
        EvalChecks.Outcome untracked = evaluate(repository, base, check("no_changes")).get(0);
        assertFalse(untracked.passed());
        assertTrue(untracked.detail().contains("untracked.txt"), untracked.detail());

        git(repository, "add", "-A");
        git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "-q", "-m", "sneaky");
        EvalChecks.Outcome committed = evaluate(repository, base, check("no_changes")).get(0);
        assertFalse(committed.passed(), "把改动提交上去也必须被 no_changes 发现");
        assertTrue(committed.detail().contains("HEAD"), committed.detail());
    }

    @Test
    void commandCheckReallyExecutesAndReportsNonZeroExit() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("command-workspace"));
        EvalChecks.Context context = new EvalChecks.Context(workspace, null, "",
                temporary.resolve("evidence"), Duration.ofSeconds(120));

        assertTrue(EvalChecks.evaluate(List.of(command(javaVersionCommand(), 0, "version")), context)
                .get(0).passed(), "java -version 必须真实执行并匹配输出");

        EvalChecks.Outcome outcome = EvalChecks.evaluate(List.of(command(writeThenFailCommand(), 0)), context).get(0);
        assertFalse(outcome.passed());
        assertTrue(outcome.detail().contains("7"), outcome.detail());
        assertTrue(Files.readString(workspace.resolve("proof.txt")).contains("ran"),
                "命令必须真的跑过，而不是只被解析");

        assertTrue(EvalChecks.evaluate(List.of(command(writeThenFailCommand(), 7)), context)
                .get(0).passed(), "exitCode 字段必须决定期望的退出码");
    }

    @Test
    void fileAndAnswerChecksCompareRealContent() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("file-workspace"));
        Files.writeString(workspace.resolve("app.properties"), "version=1.0.0\r\nmode=eval\r\n\r\n");
        Files.writeString(workspace.resolve("hello.txt"), "hello world\n");

        assertTrue(evaluate(workspace, null, fileEquals("app.properties", "version=1.0.0", "mode=eval"))
                .get(0).passed(), "file_equals 应忽略 CRLF 与末尾空行");
        EvalChecks.Outcome different = evaluate(workspace, null, fileEquals("app.properties", "version=2.0.0", "mode=eval")).get(0);
        assertFalse(different.passed());
        assertTrue(different.detail().contains("第 1 行"), different.detail());

        assertFalse(evaluate(workspace, null, fileEquals("missing.txt", "x")).get(0).passed());
        assertTrue(evaluate(workspace, null, fileContains("hello.txt", "hello world")).get(0).passed());
        assertFalse(evaluate(workspace, null, fileContains("hello.txt", "HELLO")).get(0).passed());
        assertTrue(evaluate(workspace, null, check("file_absent", "missing.txt")).get(0).passed());
        assertFalse(evaluate(workspace, null, check("file_absent", "app.properties")).get(0).passed());
        assertTrue(evaluate(workspace, null, answerContains("OK")).get(0).passed());
        assertFalse(evaluate(workspace, null, answerContains("NOPE")).get(0).passed());

        EvalChecks.Outcome traversal = evaluate(workspace, null, fileContains("../outside.txt", "x")).get(0);
        assertFalse(traversal.passed());
        assertTrue(traversal.detail().contains("越出任务副本"), traversal.detail());
    }

    // ---------------------------------------------------------------- helpers

    private List<EvalChecks.Outcome> evaluate(Path workspace, String baseCommit, EvalCheck... checks) {
        return EvalChecks.evaluate(List.of(checks), new EvalChecks.Context(workspace, baseCommit,
                "结论：OK", temporary.resolve("evidence"), Duration.ofSeconds(60)));
    }

    private static EvalCheck check(String op) {
        return EvalCheck.fromJson(Json.object().put("op", op));
    }

    private static EvalCheck check(String op, String path) {
        return EvalCheck.fromJson(Json.object().put("op", op).put("path", path));
    }

    private static EvalCheck fileEquals(String path, String... lines) {
        ObjectNode node = Json.object().put("op", "file_equals").put("path", path);
        node.set("contentLines", lines(lines));
        return EvalCheck.fromJson(node);
    }

    private static EvalCheck fileContains(String path, String text) {
        return EvalCheck.fromJson(Json.object().put("op", "file_contains").put("path", path).put("text", text));
    }

    private static EvalCheck answerContains(String text) {
        return EvalCheck.fromJson(Json.object().put("op", "answer_contains").put("text", text));
    }

    private static EvalCheck command(String command, int exitCode) {
        return command(command, exitCode, null);
    }

    private static EvalCheck command(String command, int exitCode, String outputContains) {
        ObjectNode node = Json.object().put("op", "command").put("command", command).put("exitCode", exitCode);
        if (outputContains != null) node.put("outputContains", outputContains);
        return EvalCheck.fromJson(node);
    }

    private static ArrayNode lines(String... values) {
        ArrayNode array = Json.array();
        for (String value : values) array.add(value);
        return array;
    }
}
