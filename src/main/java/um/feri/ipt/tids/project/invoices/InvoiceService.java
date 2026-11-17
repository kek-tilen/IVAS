package um.feri.ipt.tids.project.invoices;

import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import um.feri.ipt.tids.project.verification.*;

@Service
@Transactional(readOnly=true)
public class InvoiceService {
    private final InvoiceRepository repository;
    public InvoiceService(InvoiceRepository repository) { this.repository = repository; }
    public record Create(InvoiceData invoice, String note, HandlingStatus handlingStatus) { }
    public record Summary(UUID id, String invoiceNumber, String supplierName, String supplierCountry,
            LocalDate issueDate, String currency, BigDecimal grossTotal, HandlingStatus handlingStatus, Instant createdAt) { }
    public record Detail(UUID id, long version, InvoiceData invoice, String note,
            HandlingStatus handlingStatus, Instant createdAt, Instant updatedAt) { }
    public record Listing(List<Summary> items, int page, int size, long totalElements, int totalPages) { }

    @Transactional
    public Detail create(Create request) {
        if (request == null) throw new IllegalArgumentException("Manjkajo podatki računa.");
        LocalInvoiceChecks.validate(request.invoice());
        var record = InvoiceRecord.create(request.invoice(), note(request.note()),
                request.handlingStatus() == null ? HandlingStatus.NEW : request.handlingStatus());
        return detail(repository.saveAndFlush(record));
    }
    public Listing list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Stran mora biti nenegativna, velikost pa med 1 in 100.");
        var records = repository.findAll(PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new Listing(records.stream().map(r -> new Summary(r.id, r.invoiceNumber, r.supplierName,
                r.supplierCountry, r.issueDate, r.currency, r.grossTotal, r.handlingStatus, r.createdAt)).toList(),
                page, size, records.getTotalElements(), records.getTotalPages());
    }
    public Detail get(UUID id) { return detail(find(id)); }
    @Transactional
    public Detail update(UUID id, Long version, String note, HandlingStatus status) {
        if (version == null || version < 0 || (status == null && note == null))
            throw new IllegalArgumentException("Različica in vsaj ena sprememba obravnave sta obvezni.");
        String checkedNote = note == null ? null : note(note);
        var record = find(id);
        if (!version.equals(record.version)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Račun je bil medtem spremenjen. Ponovno odprite podrobnosti in uskladite spremembe.");
        if (checkedNote != null) record.note = checkedNote;
        if (status != null) record.handlingStatus = status;
        record.updatedAt = Instant.now();
        repository.flush();
        return detail(record);
    }
    private InvoiceRecord find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Račun ne obstaja."));
    }
    private static String note(String note) {
        if (note == null) return "";
        if (note.length() > 2000 || note.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("Opomba ima največ 2000 znakov in ne sme vsebovati nedovoljenih znakov.");
        return note;
    }
    private static Detail detail(InvoiceRecord r) {
        return new Detail(r.id, r.version, r.data(), r.note, r.handlingStatus, r.createdAt, r.updatedAt);
    }
}
