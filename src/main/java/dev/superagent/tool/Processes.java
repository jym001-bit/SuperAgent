package dev.superagent.tool;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class Processes {
    private Processes() {}
    public record Result(int exitCode, boolean timedOut, String output, Path log) {}
    public static Result run(List<String> argv, Path cwd, Path log, Duration timeout,
                             Consumer<Process> started) throws Exception {
        return run(argv,cwd,log,timeout,started,Map.of());
    }
    public static Result run(List<String> argv, Path cwd, Path log, Duration timeout,
                             Consumer<Process> started,Map<String,String> extraEnvironment) throws Exception {
        return run(argv, cwd, log, timeout, started, extraEnvironment, Long.MAX_VALUE);
    }
    public static Result runLimited(List<String> argv, Path cwd, Path log, Duration timeout,
                                    Consumer<Process> started, long maxLogBytes) throws Exception {
        return run(argv, cwd, log, timeout, started, Map.of(), maxLogBytes);
    }
    private static Result run(List<String> argv, Path cwd, Path log, Duration timeout,
                              Consumer<Process> started, Map<String,String> extraEnvironment, long maxLogBytes) throws Exception {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Files.createDirectories(log.getParent());
        ProcessBuilder builder=new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Map<String,String> inherited=new HashMap<>(builder.environment());
        builder.environment().clear();
        Set<String> allowed=Set.of("PATH","SYSTEMROOT","WINDIR","JAVA_HOME","MAVEN_HOME","M2_HOME",
                "TEMP","TMP","COMSPEC","PATHEXT","USERPROFILE","HOME","APPDATA","LOCALAPPDATA","LANG");
        inherited.forEach((k,v)->{ if(allowed.contains(k.toUpperCase(Locale.ROOT))) builder.environment().put(k,v); });
        builder.environment().putAll(extraEnvironment);
        Process process=builder.start();
        // Close stdin: background commands may not consume the interactive console.
        process.getOutputStream().close();
        try {
            started.accept(process);
            boolean done;
            boolean outputLimit = false;
            if (maxLogBytes == Long.MAX_VALUE) {
                done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                long until = System.nanoTime() + timeout.toNanos();
                do {
                    done = process.waitFor(25, TimeUnit.MILLISECONDS);
                    outputLimit = Files.size(log) > maxLogBytes;
                } while (!done && !outputLimit && System.nanoTime() < until);
            }
            if(!done) { kill(process); process.waitFor(2,TimeUnit.SECONDS); }
            if (outputLimit) {
                try (var channel = java.nio.channels.FileChannel.open(log, StandardOpenOption.WRITE)) {
                    channel.truncate(maxLogBytes);
                }
            }
            byte[] bytes;
            try(var in=Files.newInputStream(log)) { bytes=in.readNBytes(24_000); }
            String out=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            if(Files.size(log)>bytes.length) out+="\n[输出已截断，完整日志: "+log+"]";
            if (outputLimit) out += "\n[命令输出超过限制，已结束执行]";
            return new Result(done && !outputLimit ? process.exitValue() : -1, !done && !outputLimit, out, log);
        } finally {
            kill(process);
        }
    }
    public static void kill(Process process) {
        if(process==null) return;
        var descendants=process.descendants().toList();
        for(int i=descendants.size()-1;i>=0;i--) descendants.get(i).destroyForcibly();
        if(process.isAlive()) process.destroyForcibly();
    }
    public static List<String> shell(String command) {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ?List.of("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-Command",command)
                :List.of("/bin/sh","-c",command);
    }
}
