package um.feri.ipt.tids.project.invoices;

import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {
    private final InvoiceService service;
    public InvoiceController(InvoiceService service) { this.service = service; }
    public record Metadata(Long version, String note, HandlingStatus handlingStatus) { }
    @PostMapping
    public ResponseEntity<InvoiceService.Detail> create(@RequestBody InvoiceService.Create input) {
        var result = service.create(input);
        return ResponseEntity.created(URI.create("/api/v1/invoices/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping
    public ResponseEntity<InvoiceService.Listing> list(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(page, size));
    }
    @GetMapping("/{id}") public ResponseEntity<InvoiceService.Detail> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }
    @PatchMapping("/{id}") public ResponseEntity<InvoiceService.Detail> update(@PathVariable UUID id, @RequestBody Metadata input) {
        if (input == null) throw new IllegalArgumentException("Manjkajo podatki obravnave.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.update(id, input.version(), input.note(), input.handlingStatus()));
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception error) {
        return problem(400, "INVALID_ARGUMENT", error instanceof IllegalArgumentException
                ? error.getMessage() : "Zahtevek vsebuje neveljavne ali nepravilno oblikovane podatke.");
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException error) {
        return problem(error.getStatusCode().value(), error.getStatusCode().value() == 404 ? "NOT_FOUND" : "CONFLICT", error.getReason());
    }
    @ExceptionHandler(OptimisticLockingFailureException.class) public ResponseEntity<?> concurrent(Exception error) {
        return problem(409, "CONFLICT", "Račun je bil medtem spremenjen. Ponovno odprite podrobnosti in uskladite spremembe.");
    }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<?> database(Exception error) {
        return problem(503, "DATABASE_UNAVAILABLE", "Shramba računov trenutno ni dosegljiva. Poskusite znova.");
    }
    private ResponseEntity<?> problem(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code, "message", message));
    }
}
