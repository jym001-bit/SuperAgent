package dev.superagent.tool;

import dev.superagent.model.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

public final class FileTools {
    private final Path root;
    public FileTools(Path root) throws Exception { this.root=root.toRealPath(); }
    public Path resolve(String value) throws Exception {
        Path path=root.resolve(value).normalize();
        if(!path.startsWith(root)) throw new IllegalArgumentException("路径越出任务目录");
        Path relative=root.relativize(path), cursor=root;
        for(Path part:relative) {
            cursor=cursor.resolve(part);
            if(part.toString().equalsIgnoreCase(".git")) throw new IllegalArgumentException("文件工具不允许操作 .git");
            if(Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("不允许通过符号链接访问文件");
            if(Files.exists(cursor,LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().startsWith(root))
                throw new IllegalArgumentException("真实路径越出任务目录");
        }
        return path;
    }
    public static String required(JsonNode args,String key) {
        JsonNode value=args.get(key);
        if(value==null || !value.isTextual() || (value.textValue().isEmpty()&&!key.equals("content")&&!key.equals("new_text")))
            throw new IllegalArgumentException("缺少字符串参数: "+key);
        return value.textValue();
    }
    public String execute(String name,JsonNode args) throws Exception {
        if(!args.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
        Path path=resolve(required(args,"path"));
        return switch(name) {
            case "list_directory" -> {
                try(Stream<Path> paths=Files.list(path)) {
                    yield String.join("\n",paths.filter(p->!p.getFileName().toString().equals(".git"))
                            .sorted().limit(300).map(p->p.getFileName()+(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)?"/":"")).toList());
                }
            }
            case "read_file" -> {
                if(Files.size(path)>1_000_000) throw new IllegalArgumentException("文件超过 1MB，请用搜索定位");
                yield bounded(Files.readString(path));
            }
            case "write_file" -> {
                String content=required(args,"content");
                if(content.length()>1_000_000) throw new IllegalArgumentException("单次写入超过 1MB");
                Files.createDirectories(path.getParent());
                atomicWrite(path,content); yield "已写入 "+root.relativize(path);
            }
            case "edit_file" -> {
                if(Files.size(path)>1_000_000) throw new IllegalArgumentException("文件超过 1MB");
                String old=required(args,"old_text"), replacement=required(args,"new_text"), content=Files.readString(path);
                int start=content.indexOf(old);
                if(start<0 || content.indexOf(old,start+1)>=0) throw new IllegalArgumentException("old_text 必须精确且唯一匹配");
                atomicWrite(path,content.substring(0,start)+replacement+content.substring(start+old.length())); yield "已修改 "+root.relativize(path);
            }
            case "search_text" -> {
                String query=required(args,"query");
                StringBuilder found=new StringBuilder();
                try(Stream<Path> paths=Files.walk(path)) {
                    Iterator<Path> it=paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                            .filter(p->!root.relativize(p).toString().replace('\\','/').startsWith(".git/"))
                            .limit(5000).iterator();
                    while(it.hasNext()&&found.length()<24_000) {
                        Path file=it.next();
                        try {
                            resolve(root.relativize(file).toString());
                            if(Files.size(file)>1_000_000) continue;
                            List<String> lines=Files.readAllLines(file);
                            for(int i=0;i<lines.size()&&found.length()<24_000;i++)
                                if(lines.get(i).contains(query)) found.append(root.relativize(file)).append(':').append(i+1).append(':').append(lines.get(i)).append('\n');
                        } catch(java.io.IOException ignored) { /* binaries and unreadable files */ }
                    }
                }
                yield bounded(found.toString());
            }
            default -> throw new IllegalArgumentException("未知文件工具: "+name);
        };
    }
    public static void atomicWrite(Path path,String content) throws Exception {
        Path temporary=Files.createTempFile(path.getParent(),".superagent-",".tmp");
        try {
            Files.writeString(temporary,content,StandardCharsets.UTF_8);
            try { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static String bounded(String value) { return value.length()<=24_000?value:value.substring(0,24_000)+"\n[结果已截断]"; }
    public static ArrayNode specifications() {
        ArrayNode specs=Json.array();
        add(specs,"list_directory","列出目录，path 可为 .",List.of("path"));
        add(specs,"search_text","按字面文本搜索，path 为目录",List.of("path","query"));
        add(specs,"read_file","读取 UTF-8 文本文件",List.of("path"));
        add(specs,"write_file","创建或覆盖 UTF-8 文件",List.of("path","content"));
        add(specs,"edit_file","精确替换唯一匹配片段",List.of("path","old_text","new_text"));
        add(specs,"execute_command","在任务根目录执行 Shell 命令；每次需要用户批准",List.of("command"));
        add(specs,"ask_user","需要用户信息时提问并等待回复",List.of("question"));
        return specs;
    }
    private static void add(ArrayNode specs,String name,String description,List<String> required) {
        ObjectNode properties=Json.object(); ArrayNode keys=Json.array();
        for(String key:required) {properties.set(key,Json.object().put("type","string"));keys.add(key);}
        ObjectNode parameters=Json.object().put("type","object").put("additionalProperties",false);
        parameters.set("properties",properties); parameters.set("required",keys);
        ObjectNode fn=Json.object().put("name",name).put("description",description);fn.set("parameters",parameters);
        ObjectNode spec=Json.object().put("type","function");spec.set("function",fn);specs.add(spec);
    }
}
