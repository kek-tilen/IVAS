package um.feri.ipt.tids.project.sources;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sources")
public class ExternalSourcesController {
    private final FrenchCompanyClient france;
    private final ViesClient vies;
    public ExternalSourcesController(FrenchCompanyClient france, ViesClient vies) { this.france=france; this.vies=vies; }
    public record FranceInput(String registryId) { }
    public record ViesInput(String countryCode, String vatNumber) { }
    @PostMapping("/france") public ResponseEntity<SourceModels.FranceResult> france(@RequestBody FranceInput input) {
        if (input==null) throw new IllegalArgumentException("Manjka identifikator podjetja.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(france.lookup(input.registryId()));
    }
    @PostMapping("/vies") public ResponseEntity<SourceModels.ViesResult> vies(@RequestBody ViesInput input) {
        if (input==null) throw new IllegalArgumentException("Manjkajo podatki DDV številke.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vies.check(input.countryCode(),input.vatNumber()));
    }
    @ExceptionHandler(SourceFailure.class) public ResponseEntity<?> source(SourceFailure error) {
        var builder=ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore());
        if (error.retryAfterSeconds()!=null) builder.header("Retry-After",Integer.toString(error.retryAfterSeconds()));
        return builder.body(error.body());
    }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class}) public ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("code","INVALID_ARGUMENT",
                "message",error instanceof IllegalArgumentException ? error.getMessage() : "Zahtevek mora vsebovati pravilno oblikovan JSON."));
    }
}
