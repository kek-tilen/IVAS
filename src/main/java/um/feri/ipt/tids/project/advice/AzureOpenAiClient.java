package um.feri.ipt.tids.project.advice;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static um.feri.ipt.tids.project.advice.InvoiceAdviceResponse.Advice;

/** One bounded call to an explicitly configured Azure deployment; no retries or fallback. */
@Component
public class AzureOpenAiClient {
    private final HttpClient http;
    private final URI endpoint;
    private final String key;
    private final String deployment;
    private final Duration timeout;
    private final int maxTokens;
    private final JsonMapper mapper = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final String instructions;
    private final JsonNode schema;

    @Autowired
    public AzureOpenAiClient(@Value("${ivas.azure-openai.enabled:false}") boolean enabled,
            @Value("${ivas.azure-openai.endpoint:}") String baseUrl,
            @Value("${ivas.azure-openai.api-key:}") String key,
            @Value("${ivas.azure-openai.deployment:}") String deployment,
            @Value("${ivas.azure-openai.timeout-seconds:60}") int seconds,
            @Value("${ivas.azure-openai.max-completion-tokens:4096}") int maxTokens) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                enabled ? azureEndpoint(baseUrl) : null, key, deployment, Duration.ofSeconds(seconds), maxTokens);
    }

    // Test seam: a local fake HTTP server, without weakening production HTTPS/host validation.
    AzureOpenAiClient(HttpClient http, URI endpoint, String key, String deployment, Duration timeout, int maxTokens) {
        this.http = http;
        this.endpoint = endpoint;
        this.key = key == null ? "" : key.trim();
        this.deployment = deployment == null ? "" : deployment.trim();
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(120)) > 0
                || maxTokens < 256 || maxTokens > 8192) throw new IllegalArgumentException("Invalid AI request limits");
        this.timeout = timeout;
        this.maxTokens = maxTokens;
        try (var prompt = new ClassPathResource("advice/system-prompt.txt").getInputStream();
             var json = new ClassPathResource("advice/response-schema.json").getInputStream()) {
            instructions = new String(prompt.readAllBytes(), StandardCharsets.UTF_8);
            schema = mapper.readTree(json);
        } catch (IOException e) { throw new IllegalStateException("Missing invoice advice resources", e); }
    }

    public boolean configured() {
        return endpoint != null && !key.isBlank() && !deployment.isBlank() && deployment.length() <= 200
                && key.chars().noneMatch(c -> c <= 32 || c >= 127);
    }
    public String deployment() { return deployment; }
    record Result(String model, Advice advice) { }

    Result complete(String userPrompt) {
        if (!configured()) throw new AdviceException(503, "AI_NOT_CONFIGURED", "AI pomoč ni nastavljena.");
        var payload = Map.of("model", deployment, "messages", List.of(
                Map.of("role", "developer", "content", instructions),
                Map.of("role", "user", "content", userPrompt)),
                "response_format", Map.of("type", "json_schema", "json_schema",
                        Map.of("name", "invoice_advice", "strict", true, "schema", schema)),
                "reasoning_effort", "low", "max_completion_tokens", maxTokens,
                "store", false, "stream", false);
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").header("api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
        var limitedBody = new LimitedBody();
        try {
            var response = send(request, limitedBody);
            if (response.statusCode() == 429) throw new AdviceException(429, "AI_RATE_LIMITED",
                    "Azure je omejil število zahtev. Počakajte in ročno poskusite znova.");
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new AdviceException(503, "AI_AUTH_FAILED", "Azure prijava ni uspela. Preverite strežniško konfiguracijo.");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new AdviceException(502, "AI_PROVIDER_ERROR", "Azure zahteve ni uspešno obdelal. Preverite deployment in konfiguracijo.");
            return parseCompletion(new String(response.body(), StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new AdviceException(504, "AI_TIMEOUT", "Azure ni odgovoril pravočasno. Zahteva ni samodejno ponovljena.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdviceException(503, "AI_INTERRUPTED", "AI zahteva je bila prekinjena.");
        } catch (IOException e) {
            if (limitedBody.oversized) throw invalidResponse();
            throw new AdviceException(502, "AI_CONNECTION_FAILED", "Povezava z Azure ni uspela. Poskusite znova ročno.");
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request, LimitedBody body) throws IOException, InterruptedException {
        var pending = http.sendAsync(request, info -> body);
        try {
            return pending.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pending.cancel(true);
            throw new HttpTimeoutException("AI request deadline");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("AI transport failed", e.getCause());
        }
    }
    /** Complete the response only after its bounded body; the outer deadline also covers body reads. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        volatile boolean oversized;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 65_536 - bytes.size()) {
                    oversized = true;
                    subscription.cancel();
                    body.completeExceptionally(new IOException("AI response size limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
    Result parseCompletion(String responseBody) {
        try {
            JsonNode response = mapper.readTree(responseBody);
            JsonNode choices = response.path("choices");
            if (!choices.isArray() || choices.size() != 1) throw invalidResponse();
            JsonNode choice = choices.get(0);
            JsonNode refusal = choice.path("message").path("refusal");
            if (!refusal.isMissingNode() && !refusal.isNull())
                throw new AdviceException(422, "AI_REFUSED", "Model je zavrnil zahtevo. Spremenite vprašanje in poskusite znova.");
            if (!"stop".equals(choice.path("finish_reason").asString())) throw invalidResponse();
            JsonNode content = choice.path("message").path("content");
            if (!content.isString()) throw invalidResponse();
            JsonNode answer = mapper.readTree(content.asString());
            if (answer == null || !answer.isObject() || answer.size() != 4) throw invalidResponse();
            for (String field : List.of("summary", "observations", "recommendedActions", "missingInformation"))
                if (!answer.has(field)) throw invalidResponse();
            requireString(answer.path("summary"), 1600);
            for (String field : List.of("observations", "recommendedActions", "missingInformation")) {
                JsonNode list = answer.path(field);
                if (!list.isArray() || list.size() > 8) throw invalidResponse();
                for (JsonNode item : list) requireString(item, 600);
            }
            Advice advice = mapper.treeToValue(answer, Advice.class);
            String model = response.path("model").isString() ? response.path("model").asString() : "not-reported";
            return new Result(model, advice);
        } catch (AdviceException e) { throw e; }
        catch (RuntimeException e) { throw invalidResponse(); }
    }

    private static void requireString(JsonNode node, int max) {
        if (!node.isString() || node.asString().isBlank() || node.asString().length() > max) throw invalidResponse();
    }
    private static AdviceException invalidResponse() {
        return new AdviceException(502, "AI_INVALID_RESPONSE",
                "Model ni vrnil popolnega odgovora v pričakovani strukturi JSON. Odgovor ni prikazan kot veljaven rezultat.");
    }
    private static URI azureEndpoint(String input) {
        try {
            URI uri = URI.create(input.trim());
            String host = uri.getHost();
            String path = uri.getPath().replaceAll("/+$", "");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !(host.endsWith(".openai.azure.com") || host.endsWith(".cognitiveservices.azure.com")
                         || host.endsWith(".services.ai.azure.com"))
                    || uri.getRawUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !(path.isEmpty() || path.equals("/openai/v1"))) return null;
            return URI.create("https://" + host + "/openai/v1/chat/completions");
        } catch (RuntimeException e) { return null; }
    }
}
