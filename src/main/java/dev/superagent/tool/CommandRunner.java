package dev.superagent.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

@FunctionalInterface
public interface CommandRunner {
    Processes.Result run(String command, Path workspace, Path log, Duration timeout,
                         Consumer<Process> started) throws Exception;

    default boolean enabled() { return true; }

    static CommandRunner disabled() {
        return new CommandRunner() {
            @Override public boolean enabled() { return false; }
            @Override public Processes.Result run(String command, Path workspace, Path log, Duration timeout,
                                                  Consumer<Process> started) {
                throw new IllegalStateException("当前入口已关闭命令执行");
            }
        };
    }

    static CommandRunner local() {
        return (command, workspace, log, timeout, started) ->
                Processes.run(Processes.shell(command), workspace, log, timeout, started);
    }
}
