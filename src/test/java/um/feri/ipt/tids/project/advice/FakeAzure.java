package um.feri.ipt.tids.project.advice;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/** Local-only Azure protocol fixture. No request can reach an actual provider. */
final class FakeAzure implements AutoCloseable {
    static final String ANSWER = """
        {"summary":"Lokalne vsote se ujemajo; registri niso preverjeni.",
         "observations":["Podatki so ročno vneseni."],
         "recommendedActions":["Preverite dobavitelja v ustreznem registru."],
         "missingInformation":["Rezultat VIES ni na voljo."]}
        """;
    final AtomicInteger calls = new AtomicInteger();
    volatile String lastBody;
    volatile String lastKey;
    volatile String lastMethod;
    volatile int status = 200;
    volatile String response = completion(ANSWER, "stop");
    volatile long delayMillis;
    volatile long delayBodyMillis;
    private final HttpServer server;

    FakeAzure() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/openai/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastKey = exchange.getRequestHeaders().getFirst("api-key");
                lastMethod = exchange.getRequestMethod();
                try {
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    if (delayBodyMillis > 0) Thread.sleep(delayBodyMillis);
                    try (var output = exchange.getResponseBody()) { output.write(body); }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (IOException ignored) { /* A timeout test may close the socket first. */ }
                finally { exchange.close(); }
            });
            server.start();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    URI endpoint() { return URI.create("http://localhost:" + server.getAddress().getPort() + "/openai/v1/chat/completions"); }
    static String completion(String answer, String finish) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("model", "gpt-5-mini-fixture",
                "choices", java.util.List.of(Map.of("finish_reason", finish,
                        "message", Map.of("content", answer)))));
    }
    void reset() { calls.set(0); lastBody = null; status = 200; delayMillis = 0; delayBodyMillis = 0; response = completion(ANSWER, "stop"); }
    @Override public void close() { server.stop(0); }
}
