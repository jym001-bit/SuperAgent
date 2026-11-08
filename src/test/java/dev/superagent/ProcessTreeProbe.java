package dev.superagent;

import java.nio.file.*;

public final class ProcessTreeProbe {
    public static void main(String[] args)throws Exception {
        if(args[0].equals("child")){Thread.sleep(60000);return;}
        Process child=new ProcessBuilder(TestSupport.javaExecutable(),"-cp",TestSupport.classpath(),ProcessTreeProbe.class.getName(),"child").start();
        Path destination = Path.of(args[0]);
        Path pending = destination.resolveSibling(destination.getFileName() + ".pending");
        Files.writeString(pending, Long.toString(child.pid()));
        Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
        child.waitFor();
    }
}
