package um.feri.ipt.tids.project.advice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** User-entered invoice data, not an official UBL validation result. */
public record InvoiceAdviceRequest(String invoiceNumber, String supplierName, String supplierCountry,
        String supplierVatId, LocalDate issueDate, LocalDate dueDate, String currency,
        List<LineItem> items, BigDecimal netTotal, BigDecimal taxTotal, BigDecimal grossTotal,
        String question, String notes, boolean consent) {
    public record LineItem(String description, BigDecimal quantity, BigDecimal unitNetPrice) { }
}
