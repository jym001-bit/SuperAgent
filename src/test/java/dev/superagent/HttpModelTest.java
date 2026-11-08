package dev.superagent;

import dev.superagent.model.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpModelTest {
    @TempDir Path temporary;
    private HttpServer server;
    private ExecutorService serverThreads;
    @AfterEach void stop(){if(server!=null)server.stop(0);if(serverThreads!=null)serverThreads.shutdownNow();}
    private String serve(HttpHandler handler)throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverThreads=Executors.newCachedThreadPool();server.setExecutor(serverThreads);
        server.createContext("/v1/chat/completions",handler);server.start();
        return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
    }
    private Config config(String base,String provider,Duration timeout) {
        return new Config(temporary,base,"mock","secret-test-key",provider,true,2,30,100000,Duration.ofSeconds(2),timeout);
    }
    private static void respond(HttpExchange x,int status,String text)throws java.io.IOException {
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);
        try(var output=x.getResponseBody()){output.write(bytes);}
    }
    @Test void sendsDeepSeekToolHistoryIncludingReasoningAndUsage()throws Exception {
        List<JsonNode> requests=new CopyOnWriteArrayList<>();AtomicInteger calls=new AtomicInteger();
        String base=serve(x->{
            assertEquals("Bearer secret-test-key",x.getRequestHeaders().getFirst("Authorization"));
            requests.add(Json.parse(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            if(calls.getAndIncrement()==0)
                respond(x,200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"opaque reasoning\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"x\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"total_tokens\":31}}");
            else respond(x,200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":12}}");
        });
        ModelClient client=new HttpModelClient(config(base,"deepseek",Duration.ofSeconds(2)));
        List<ObjectNode> messages=new ArrayList<>(List.of(Json.message("user","read")));
        var first=client.complete(messages,dev.superagent.tool.FileTools.specifications());assertEquals(31,first.tokens());
        messages.add(first.message());messages.add(Json.message("tool","text").put("tool_call_id","c1"));
        client.complete(messages,dev.superagent.tool.FileTools.specifications());
        assertEquals("enabled",requests.get(0).path("thinking").path("type").asText());
        assertEquals("opaque reasoning",requests.get(1).path("messages").path(1).path("reasoning_content").asText());
        assertEquals("c1",requests.get(1).path("messages").path(2).path("tool_call_id").asText());
    }
    @Test void standardProviderOmitsSpecialFieldsAndDoesNotRetryClientErrors()throws Exception {
        AtomicInteger calls=new AtomicInteger();AtomicReference<JsonNode> request=new AtomicReference<>();
        String base=serve(x->{calls.incrementAndGet();request.set(Json.parse(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));respond(x,400,"private server error");});
        ModelClient client=new HttpModelClient(config(base,"standard",Duration.ofSeconds(2)));
        dev.superagent.model.ModelUnavailable error=assertThrows(dev.superagent.model.ModelUnavailable.class,
                ()->client.complete(List.of(Json.message("user","hello")),null));
        assertTrue(error.getMessage().contains("400"));assertFalse(error.retryable());
        assertTrue(error.getMessage().contains("private server error"),"错误体要带出来，否则只能猜");
        assertFalse(request.get().has("tools"));assertFalse(request.get().has("thinking"));
        assertEquals(1,calls.get(),"4xx 是配置问题，重试只会白烧额度");
    }
    @Test void timeoutStopsRequestAndInterruptCancelsAnInFlightRequest()throws Exception {
        CountDownLatch entered=new CountDownLatch(1);
        String base=serve(x->{entered.countDown();try{Thread.sleep(10000);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{x.close();}});
        ModelClient client=new HttpModelClient(config(base,"standard",Duration.ofMillis(150)));
        dev.superagent.model.ModelUnavailable timeout=assertThrows(dev.superagent.model.ModelUnavailable.class,
                ()->client.complete(List.of(Json.message("user","timeout")),null));
        assertFalse(timeout.retryable(),"请求已发出，超时结果不明，不能自动重发");
        assertEquals(1,timeout.attempts());
        assertTrue(timeout.getCause() instanceof java.net.http.HttpTimeoutException);
        CountDownLatch canceled=new CountDownLatch(1);
        ModelClient longClient=new HttpModelClient(config(base,"standard",Duration.ofSeconds(30)));
        Thread caller=new Thread(()->{try{longClient.complete(List.of(Json.message("user","cancel")),null);}catch(InterruptedException e){canceled.countDown();}catch(Exception ignored){}});
        caller.start();assertTrue(entered.await(2,TimeUnit.SECONDS));Thread.sleep(100);caller.interrupt();
        assertTrue(canceled.await(2,TimeUnit.SECONDS));caller.join(2000);assertFalse(caller.isAlive());
    }
}
