package um.feri.ipt.tids.project.grpc;

import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import io.grpc.*;
import io.grpc.stub.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import um.feri.ipt.tids.project.grpc.v1.*;
import um.feri.ipt.tids.project.verification.InvoiceData;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** The web boundary calls generated stubs over TCP; it never invokes the RPC implementation directly. */
@RestController
@RequestMapping("/api/v1/verification")
public class VerificationController {
    private final GrpcRuntime runtime;
    private final java.util.concurrent.ScheduledExecutorService heartbeats =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "verification-heartbeat"); thread.setDaemon(true); return thread;
            });
    @jakarta.annotation.PreDestroy void close() { heartbeats.shutdownNow(); }
    public VerificationController(GrpcRuntime runtime) { this.runtime = runtime; }
    public record Input(InvoiceData invoice, Boolean checkRegistry) { }
    public record SupplierInput(String name, String country, String vatId, String registryId) { }
    private InvoiceVerificationServiceGrpc.InvoiceVerificationServiceBlockingStub blocking() {
        return InvoiceVerificationServiceGrpc.newBlockingStub(runtime.channel()).withDeadlineAfter(65, TimeUnit.SECONDS);
    }
    static String json(MessageOrBuilder message) {
        try { return JsonFormat.printer().omittingInsignificantWhitespace().print(message); }
        catch (com.google.protobuf.InvalidProtocolBufferException e) { throw new IllegalStateException("Could not serialize response", e); }
    }
    private ResponseEntity<String> response(MessageOrBuilder message) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(json(message));
    }
    @GetMapping("/capabilities") public ResponseEntity<String> capabilities() {
        return response(blocking().withDeadlineAfter(5, TimeUnit.SECONDS).getCapabilities(GetCapabilitiesRequest.getDefaultInstance()));
    }
    @PostMapping("/local") public ResponseEntity<String> validate(@RequestBody Input input) {
        return response(blocking().withDeadlineAfter(5, TimeUnit.SECONDS).validateInvoice(
                ValidateInvoiceRequest.newBuilder().setInvoice(InvoiceMapper.toProto(input.invoice())).build()));
    }
    @PostMapping("/supplier") public ResponseEntity<String> supplier(@RequestBody SupplierInput input) {
        var supplier = Supplier.newBuilder().setName(input.name() == null ? "" : input.name())
                .setCountry(input.country() == null ? "" : input.country());
        if (input.registryId() != null) supplier.setRegistryId(input.registryId());
        if (input.vatId() != null) supplier.setVatId(input.vatId());
        return response(blocking().lookupSupplier(LookupSupplierRequest.newBuilder().setSupplier(supplier).build()));
    }
    @PostMapping(value="/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestBody Input input) {
        var request = VerifyInvoiceRequest.newBuilder().setInvoice(InvoiceMapper.toProto(input.invoice()))
                .setCheckRegistry(Boolean.TRUE.equals(input.checkRegistry())).build();
        var stub = InvoiceVerificationServiceGrpc.newStub(runtime.channel()).withDeadlineAfter(65, TimeUnit.SECONDS);
        var emitter = new SseEmitter(70000L);
        var call = new AtomicReference<ClientCallStreamObserver<VerifyInvoiceRequest>>();
        Runnable cancel = () -> {
            var current = call.get();
            if (current != null) current.cancel("Web request ended", null);
        };
        // A write detects a disconnected browser even while a registry lookup is still pending.
        var heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try { emitter.send(SseEmitter.event().comment("keepalive")); }
            catch (Exception e) { cancel.run(); emitter.completeWithError(e); }
        }, 1, 1, TimeUnit.SECONDS);
        emitter.onTimeout(cancel);
        emitter.onError(ignored -> cancel.run());
        emitter.onCompletion(() -> { heartbeat.cancel(false); cancel.run(); });
        stub.verifyInvoice(request, new ClientResponseObserver<VerifyInvoiceRequest, VerificationEvent>() {
                    @Override public void beforeStart(ClientCallStreamObserver<VerifyInvoiceRequest> stream) { call.set(stream); }
                    @Override public void onNext(VerificationEvent event) {
                        try {
                            emitter.send(SseEmitter.event().id(Integer.toString(event.getSequence()))
                                    .name("verification").data(json(event), MediaType.APPLICATION_JSON));
                        } catch (Exception e) { cancel.run(); emitter.completeWithError(e); }
                    }
                    @Override public void onError(Throwable failure) {
                        var status = Status.fromThrowable(failure);
                        try {
                            emitter.send(SseEmitter.event().name("failure").data(Map.of(
                                    "code", status.getCode().name(), "message", message(status)), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (Exception e) { emitter.completeWithError(e); }
                    }
                    @Override public void onCompleted() { emitter.complete(); }
                });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no").body(emitter);
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("code", "INVALID_ARGUMENT", "message", error instanceof HttpMessageNotReadableException
                        ? "Zahtevek mora vsebovati pravilno oblikovane podatke računa." : error.getMessage()));
    }
    @ExceptionHandler(StatusRuntimeException.class) public ResponseEntity<?> grpcError(StatusRuntimeException error) {
        Status status = error.getStatus();
        int code = switch (status.getCode()) {
            case INVALID_ARGUMENT -> 400;
            case RESOURCE_EXHAUSTED -> 429;
            case DEADLINE_EXCEEDED -> 504;
            case UNAVAILABLE -> 503;
            case CANCELLED -> 409;
            default -> 502;
        };
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore())
                .body(Map.of("code", status.getCode().name(), "message", message(status)));
    }
    private static String message(Status status) {
        return switch (status.getCode()) {
            case INVALID_ARGUMENT -> "Neveljavni podatki zahtevka. Preverite račun in osemmestni IČO za CZ.";
            case RESOURCE_EXHAUSTED -> "Preveč sočasnih preverjanj. Poskusite pozneje.";
            case DEADLINE_EXCEEDED -> "Čas preverjanja je potekel. Rezultat ni dokončen.";
            case CANCELLED -> "Preverjanje je prekinjeno.";
            case UNAVAILABLE -> "gRPC storitev trenutno ni dosegljiva.";
            default -> "Preverjanja ni mogoče dokončati. Poskusite znova.";
        };
    }
}
