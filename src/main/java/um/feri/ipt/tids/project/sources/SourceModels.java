package um.feri.ipt.tids.project.sources;

import java.time.*;

/** Transport-neutral results; an unavailable source never becomes a negative business result. */
public final class SourceModels {
    private SourceModels() { }
    public enum RegistryStatus { FOUND, PARTIAL, NOT_FOUND }
    public enum VatStatus { VALID, INVALID }
    public record SourceInfo(String source, String sourceUrl, Instant fetchedAt) { }
    public record FrenchCompany(String siren, String siret, String name,
            String companyStatus, String establishmentStatus) { }
    public record FranceResult(RegistryStatus status, String requestedId, FrenchCompany company,
            String message, SourceInfo metadata) { }
    public record ViesResult(VatStatus status, String countryCode, String vatNumber, LocalDate requestDate,
            String name, String address, String message, SourceInfo metadata) { }
    public record Error(String code, String message, String source, String sourceUrl,
            Instant attemptedAt, Integer retryAfterSeconds, String providerCode) { }
}
