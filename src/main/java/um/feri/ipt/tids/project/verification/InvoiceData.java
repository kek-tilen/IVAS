package um.feri.ipt.tids.project.verification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Transport-neutral subset of a positive invoice; no UBL or AI claims. */
public record InvoiceData(String invoiceNumber, String supplierName, String supplierCountry,
        String supplierVatId, String supplierRegistryId, LocalDate issueDate, LocalDate dueDate,
        String currency, List<Line> items, BigDecimal netTotal, BigDecimal taxTotal, BigDecimal grossTotal) {
    public record Line(String description, BigDecimal quantity, BigDecimal unitNetPrice) { }
}
