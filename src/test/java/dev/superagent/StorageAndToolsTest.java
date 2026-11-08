package dev.superagent;

import dev.superagent.model.Json;
import dev.superagent.storage.Store;
import dev.superagent.task.Task;
import dev.superagent.tool.FileTools;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageAndToolsTest {
    @TempDir Path temporary;
    @Test void transactionRollbackAndDetachedSnapshotsAndLock()throws Exception {
        try(Store store=new Store(temporary)) {
            Task t=new Task();t.id="t";t.prompt="original";store.create(t);
            assertThrows(IllegalStateException.class,()->new Store(temporary));
            Task snapshot=store.get("t");snapshot.prompt="leaked mutation";
            assertEquals("original",store.get("t").prompt);
            assertThrows(IllegalArgumentException.class,()->store.update("t","bad",v->{v.inputs.add(new Task.Input("lost"));throw new IllegalArgumentException("rollback");}));
            assertTrue(store.get("t").inputs.isEmpty());
            store.update("t","input",v->{v.inputs.add(new Task.Input("persisted"));v.status=Task.Status.WAITING_INPUT;});
        }
        try(Store reopened=new Store(temporary)) {
            assertEquals("persisted",reopened.get("t").inputs.get(0).text);
            assertEquals(Task.Status.WAITING_INPUT,reopened.get("t").status);
        }
    }
    @Test void traversalAndMetadataAreRejectedAndEditsRequireUniqueText()throws Exception {
        Path root=Files.createDirectory(temporary.resolve("root"));FileTools files=new FileTools(root);
        assertThrows(IllegalArgumentException.class,()->files.resolve("../outside"));
        assertThrows(IllegalArgumentException.class,()->files.resolve(temporary.resolve("outside").toString()));
        assertThrows(IllegalArgumentException.class,()->files.resolve(".git/config"));
        files.execute("write_file",Json.object().put("path","nested/a.txt").put("content","a\r\na\r\n"));
        assertThrows(IllegalArgumentException.class,()->files.execute("edit_file",Json.object().put("path","nested/a.txt").put("old_text","a").put("new_text","b")));
        files.execute("edit_file",Json.object().put("path","nested/a.txt").put("old_text","a\r\na").put("new_text","b\r\nc"));
        assertEquals("b\r\nc\r\n",Files.readString(root.resolve("nested/a.txt")));
        assertTrue(files.execute("search_text",Json.object().put("path",".").put("query","c")).contains("a.txt:2:c"));
    }
    @Test void symbolicLinkCannotExposeAnOutsideFile()throws Exception {
        Path root=Files.createDirectory(temporary.resolve("root"));Path outside=temporary.resolve("secret.txt");Files.writeString(outside,"secret");
        String link="link.txt";
        try{Files.createSymbolicLink(root.resolve(link),outside);}
        catch(java.io.IOException|UnsupportedOperationException|SecurityException e){
            if(!TestSupport.windows()) {Assumptions.abort("OS does not permit symlink creation: "+e.getMessage());return;}
            Path external=Files.createDirectory(temporary.resolve("external"));Files.writeString(external.resolve("secret.txt"),"secret");
            Process junction=new ProcessBuilder("cmd.exe","/c","mklink","/J",root.resolve("junction").toString(),external.toString()).redirectErrorStream(true).start();
            junction.getInputStream().readAllBytes();assertEquals(0,junction.waitFor());link="junction/secret.txt";
        }
        FileTools files=new FileTools(root);
        String linkedPath=link;
        assertThrows(IllegalArgumentException.class,()->files.execute("read_file",Json.object().put("path",linkedPath)));
        assertThrows(IllegalArgumentException.class,()->files.execute("write_file",Json.object().put("path",linkedPath).put("content","overwrite")));
        assertEquals("secret",Files.readString(outside));
    }
}
