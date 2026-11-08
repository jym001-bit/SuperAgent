package dev.superagent;

import dev.superagent.tool.Processes;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static dev.superagent.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessesTest {
    @TempDir Path temporary;
    @Test void interruptionTerminatesTheStartedProcessAndItsChild()throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();AtomicReference<Process> parent=new AtomicReference<>();
        Path pid=temporary.resolve("child.pid");ProcessHandle child=null;
        try {
            Future<?> task=executor.submit(()->{
                try {
                    Processes.run(List.of(javaExecutable(),"-cp",classpath(),ProcessTreeProbe.class.getName(),pid.toString()),
                            temporary,temporary.resolve("process.log"),Duration.ofSeconds(30),parent::set);
                }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception e){throw new RuntimeException(e);}
            });
            await(()->Files.exists(pid));long childPid=Long.parseLong(Files.readString(pid));
            child=ProcessHandle.of(childPid).orElseThrow();assertTrue(child.isAlive());
            task.cancel(true);ProcessHandle observed=child;
            await(()->!observed.isAlive()&&!parent.get().isAlive());
        } finally {
            Processes.kill(parent.get());if(child!=null&&child.isAlive())child.destroyForcibly();
            executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));
        }
    }
}
