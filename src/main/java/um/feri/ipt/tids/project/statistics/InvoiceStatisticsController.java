package um.feri.ipt.tids.project.statistics;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/statistics/invoices")
public class InvoiceStatisticsController {
    private static final Logger log = LoggerFactory.getLogger(InvoiceStatisticsController.class);
    private final InvoiceStatisticsService service;

    public InvoiceStatisticsController(InvoiceStatisticsService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> statistics() {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.load());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable(exception);
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException | ArithmeticException exception) {
            return unavailable(exception);
        }
    }

    private ResponseEntity<?> unavailable(Exception exception) {
        log.warn("Could not load SURS invoice statistics", exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).cacheControl(CacheControl.noStore())
                .body(Map.of("message", "Podatkov SURS trenutno ni mogoče pridobiti ali obdelati. Poskusite znova."));
    }
}
