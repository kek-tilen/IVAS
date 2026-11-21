package um.feri.ipt.tids.project.sources;

import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static um.feri.ipt.tids.project.sources.SourceFailure.Kind.*;

/** One bounded HTTP exchange, no redirects or retries. The deadline includes the response body. */
@Component
public class SourceHttpClient implements AutoCloseable {
    static final int MAX_BODY = 262144;
    private final Duration timeout;
    private final HttpClient http;
    private final Semaphore active = new Semaphore(4);
    @Autowired public SourceHttpClient(@Value("${ivas.sources.timeout-seconds:20}") int seconds) {
        this(checkedTimeout(seconds));
    }
    private static Duration checkedTimeout(int seconds) {
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("Source timeout must be between 1 and 60 seconds.");
        return Duration.ofSeconds(seconds);
    }
    SourceHttpClient(Duration timeout) {
        this.timeout=timeout;
        this.http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    HttpResponse<byte[]> send(HttpRequest.Builder builder) {
        if (!active.tryAcquire()) throw new SourceFailure(BUSY).retryAfter(1);
        var body = new LimitedBody();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = builder.timeout(timeout).header("User-Agent", "IVAS educational project (https://github.com/kek-tilen/IVAS)").build();
            pending = http.sendAsync(request, info -> body);
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            if (pending != null) pending.cancel(true);
            throw new SourceFailure(TIMEOUT);
        } catch (InterruptedException error) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt(); throw new SourceFailure(INTERRUPTED);
        } catch (ExecutionException error) {
            if (body.oversized) throw new SourceFailure(INVALID_RESPONSE);
            if (error.getCause() instanceof HttpTimeoutException) throw new SourceFailure(TIMEOUT);
            throw new SourceFailure(UNAVAILABLE);
        } finally { active.release(); }
    }
    @jakarta.annotation.PreDestroy @Override public void close() { http.close(); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Flow.Subscription subscription;
        volatile boolean oversized;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BODY-bytes.size()) {
                    oversized=true; subscription.cancel(); result.completeExceptionally(new IOException("Source response limit")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
