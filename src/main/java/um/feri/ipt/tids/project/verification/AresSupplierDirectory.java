package um.feri.ipt.tids.project.verification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import um.feri.ipt.tids.project.grpc.InvoiceMapper;
import um.feri.ipt.tids.project.grpc.v1.*;
import um.feri.ipt.tids.project.parser.ares.*;

@Component
public class AresSupplierDirectory implements SupplierDirectory {
    private final Semaphore browsers = new Semaphore(2);
    private final String channel;
    public AresSupplierDirectory(@Value("${ivas.ares.browser-channel:}") String channel) {
        if (!channel.isEmpty() && !java.util.Set.of("chrome", "msedge").contains(channel))
            throw new IllegalArgumentException("ARES browser channel must be chrome or msedge.");
        this.channel = channel.isEmpty() ? null : channel;
    }
    @Override public SupplierLookupResult lookup(Supplier supplier, BooleanSupplier cancelled) {
        var result = SupplierLookupResult.newBuilder().setSource("ARES_WEB / HTML_DOM").setFetchedAt(InvoiceMapper.now());
        if (!supplier.getCountry().equals("CZ")) return result.setStatus(RegistryStatus.UNSUPPORTED)
                .setMessage("Registrsko preverjanje je trenutno podprto samo za CZ.").build();
        if (!supplier.hasRegistryId() || supplier.getRegistryId().isBlank()) return result.setStatus(RegistryStatus.MISSING_IDENTIFIER)
                .setMessage("Za ARES potrebujete izrecno naveden osemmestni IČO.").build();
        Ico.validate(supplier.getRegistryId());
        if (!browsers.tryAcquire()) throw io.grpc.Status.RESOURCE_EXHAUSTED
                .withDescription("Vsa mesta za preverjanje ARES so zasedena. Poskusite pozneje.").asRuntimeException();
        try {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            var raw = new AresScraper(Clock.systemUTC(), Duration.ofSeconds(30), false, channel)
                    .scrape(supplier.getRegistryId(), cancelled);
            if (cancelled.getAsBoolean()) throw new CancellationException();
            return mapResult(raw);
        } finally { browsers.release(); }
    }

    static SupplierLookupResult mapResult(AresResult raw) {
        var result = SupplierLookupResult.newBuilder().setSource("ARES_WEB / HTML_DOM");
        result.setFetchedAt(InvoiceMapper.now()).setSourceUrl(raw.metadata().sourceUrl());
        result.setStatus(switch (raw.status()) {
            case SUCCESS -> RegistryStatus.FOUND;
            case PARTIAL -> RegistryStatus.PARTIAL;
            case NOT_FOUND -> RegistryStatus.NOT_FOUND;
            case ERROR -> RegistryStatus.SOURCE_ERROR;
        });
        if (raw.company() != null) {
            var c = raw.company();
            var company = Supplier.newBuilder().setCountry("CZ");
            if (c.ico() != null) company.setRegistryId(c.ico());
            if (c.name() != null) company.setName(c.name());
            if (c.vatId() != null) company.setVatId(c.vatId());
            result.setSupplier(company);
        }
        result.setMessage(switch (raw.status()) {
            case SUCCESS -> "Registrski zapis ARES je pridobljen.";
            case PARTIAL -> "ARES je vrnil nepopolne podatke; manjkajočih podatkov ni mogoče potrditi.";
            case NOT_FOUND -> "ARES izrecno ne vrne zapisa za navedeni IČO.";
            case ERROR -> "Podatkov ARES trenutno ni mogoče pridobiti ali razbrati. Poskusite znova.";
        });
        return result.build();
    }
}
