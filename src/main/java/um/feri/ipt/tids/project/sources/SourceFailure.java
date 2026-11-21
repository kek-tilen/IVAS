package um.feri.ipt.tids.project.sources;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;

public class SourceFailure extends RuntimeException {
    public enum Kind {
        UNAVAILABLE(503, "SOURCE_UNAVAILABLE", "Zunanja storitev trenutno ni dosegljiva. Rezultata ni mogoče potrditi."),
        TIMEOUT(504, "SOURCE_TIMEOUT", "Zunanja storitev ni odgovorila pravočasno. Preverjanje ni dokončano."),
        INVALID_RESPONSE(502, "SOURCE_INVALID_RESPONSE", "Vir ni vrnil pričakovanega odgovora. Rezultata ni mogoče potrditi."),
        RATE_LIMITED(429, "SOURCE_RATE_LIMITED", "Vir je omejil število zahtev. Počakajte in poskusite znova ročno."),
        BUSY(429, "SOURCE_BUSY", "Preveč sočasnih poizvedb. Poskusite znova pozneje."),
        INVALID_INPUT(400, "VIES_INVALID_INPUT", "VIES je zavrnil vhodne podatke. To ni potrditev neveljavne DDV številke."),
        INTERRUPTED(503, "SOURCE_INTERRUPTED", "Poizvedba je bila prekinjena. Preverjanje ni dokončano.");
        final int status;
        final String code, message;
        Kind(int status, String code, String message) { this.status=status; this.code=code; this.message=message; }
    }
    private final Kind kind;
    private String source, sourceUrl, providerCode;
    private Instant attemptedAt;
    private Integer retryAfter;
    public SourceFailure(Kind kind) { super(kind.message); this.kind=kind; }
    SourceFailure at(String source, URI url, Instant attemptedAt) {
        this.source=source; this.sourceUrl=url.toString(); this.attemptedAt=attemptedAt; return this;
    }
    SourceFailure provider(String code) { this.providerCode=code; return this; }
    SourceFailure retryAfter(Integer seconds) { this.retryAfter=seconds; return this; }
    public int status() { return kind.status; }
    public SourceModels.Error body() {
        return new SourceModels.Error(kind.code, getMessage(), source, sourceUrl, attemptedAt, retryAfter, providerCode);
    }
    Integer retryAfterSeconds() { return retryAfter; }
    static SourceFailure http(int status, String retryAfter) {
        if (status == 429) return new SourceFailure(Kind.RATE_LIMITED).retryAfter(parseRetryAfter(retryAfter));
        return new SourceFailure(Kind.UNAVAILABLE);
    }
    private static Integer parseRetryAfter(String input) {
        if (input == null) return null;
        try {
            long seconds = input.matches("[0-9]{1,8}") ? Long.parseLong(input)
                    : Math.max(0, (Duration.between(Instant.now(), ZonedDateTime.parse(input,
                            DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()+999)/1000);
            return (int)Math.max(1, Math.min(86400, seconds));
        } catch (RuntimeException invalid) { return null; }
    }
}
