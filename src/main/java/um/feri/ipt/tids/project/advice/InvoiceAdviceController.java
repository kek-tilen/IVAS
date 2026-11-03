package um.feri.ipt.tids.project.advice;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/invoices/advice")
public class InvoiceAdviceController {
    private final InvoiceAdviceService service;
    public InvoiceAdviceController(InvoiceAdviceService service) { this.service = service; }

    @GetMapping("/config")
    public ResponseEntity<?> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "configured", service.configured(), "provider", "AZURE_OPENAI"));
    }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InvoiceAdviceResponse> advise(@RequestBody InvoiceAdviceRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.advise(request));
    }
    @ExceptionHandler(AdviceException.class)
    public ResponseEntity<?> problem(AdviceException e) {
        return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore())
                .body(Map.of("code", e.code(), "message", e.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedInput() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of(
                "code", "INVALID_JSON_INPUT", "message", "Preverite obliko datumov, zneskov in podatkov obrazca."));
    }
}
