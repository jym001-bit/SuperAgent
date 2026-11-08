package dev.superagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DockerCommandRunnerTest {
    @TempDir Path state;

    @Test void commandCannotBecomeDockerArgumentsAndSandboxRequiresPinnedImage() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new DockerCommandRunner(state, "java:latest"));
        var runner = new DockerCommandRunner(state, "example/java@sha256:" + "a".repeat(64));
        String command = "--privileged; touch /work/output";
        var args = runner.arguments(command, state, "test-container");
        assertEquals(command, args.get(args.size() - 1));
        assertFalse(args.contains("--privileged"));
        assertTrue(args.contains("--network=none"));
        assertTrue(args.contains("--cap-drop=ALL"));
        assertTrue(args.contains("--read-only"));
        assertTrue(args.stream().anyMatch(arg -> arg.endsWith("target=/input,readonly,bind-recursive=disabled")));
        assertThrows(IllegalArgumentException.class, () -> runner.arguments(command, state.resolve("bad,readonly=false"), "bad"));
    }
}
