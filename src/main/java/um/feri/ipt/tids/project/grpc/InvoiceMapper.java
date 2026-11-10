package um.feri.ipt.tids.project.grpc;

import um.feri.ipt.tids.project.grpc.v1.*;
import um.feri.ipt.tids.project.verification.*;
import java.math.BigDecimal;
import java.time.*;
import com.google.protobuf.Timestamp;

public final class InvoiceMapper {
    private InvoiceMapper() { }
    public static Timestamp now() {
        var now = Instant.now();
        return Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
    }
    public static InvoiceData fromProto(Invoice r) {
        if (r.getItemsCount() > 30) throw new IllegalArgumentException("Račun sme vsebovati največ 30 postavk.");
        try {
            var supplier = r.getSupplier();
            return new InvoiceData(r.getInvoiceNumber(), supplier.getName(), supplier.getCountry(),
                    supplier.hasVatId() ? supplier.getVatId() : null,
                    supplier.hasRegistryId() ? supplier.getRegistryId() : null,
                    LocalDate.parse(r.getIssueDate()), r.hasDueDate() ? LocalDate.parse(r.getDueDate()) : null,
                    r.getCurrency(), r.getItemsList().stream().map(i -> new InvoiceData.Line(i.getDescription(),
                            decimal(i.getQuantity()), decimal(i.getUnitNetPrice()))).toList(),
                    decimal(r.getNetTotal()), decimal(r.getTaxTotal()), decimal(r.getGrossTotal()));
        } catch (java.time.DateTimeException | NumberFormatException e) {
            throw new IllegalArgumentException("Datumi morajo biti ISO yyyy-MM-dd, zneski in količine pa decimalna števila.");
        }
    }
    private static BigDecimal decimal(String value) {
        if (value.length() > 40 || !value.matches("[0-9]+(?:\\.[0-9]+)?"))
            throw new NumberFormatException("Invalid plain decimal");
        return new BigDecimal(value);
    }
    public static Invoice toProto(InvoiceData r) {
        if (r == null) throw new IllegalArgumentException("Manjkajo podatki računa.");
        LocalInvoiceChecks.validate(r);
        var supplier = Supplier.newBuilder().setName(r.supplierName()).setCountry(r.supplierCountry());
        if (r.supplierVatId() != null && !r.supplierVatId().isBlank()) supplier.setVatId(r.supplierVatId());
        if (r.supplierRegistryId() != null && !r.supplierRegistryId().isBlank()) supplier.setRegistryId(r.supplierRegistryId());
        var result = Invoice.newBuilder().setInvoiceNumber(r.invoiceNumber()).setSupplier(supplier)
                .setIssueDate(r.issueDate().toString()).setCurrency(r.currency())
                .setNetTotal(r.netTotal().toPlainString()).setTaxTotal(r.taxTotal().toPlainString())
                .setGrossTotal(r.grossTotal().toPlainString());
        if (r.dueDate() != null) result.setDueDate(r.dueDate().toString());
        r.items().forEach(i -> result.addItems(InvoiceLine.newBuilder().setDescription(i.description())
                .setQuantity(i.quantity().toPlainString()).setUnitNetPrice(i.unitNetPrice().toPlainString())));
        return result.build();
    }
    public static LocalEvaluation local(InvoiceData invoice) {
        var evaluation = LocalInvoiceChecks.evaluate(invoice);
        var result = LocalEvaluation.newBuilder().setCalculatedLineNetTotal(evaluation.calculatedLineNetTotal().toPlainString())
                .setExpectedGrossTotal(evaluation.expectedGrossTotal().toPlainString());
        evaluation.checks().forEach(c -> result.addChecks(check(c.code(), CheckStatus.valueOf(c.status()), c.message(), "LOCAL")));
        return result.build();
    }
    public static CheckResult check(String code, CheckStatus status, String message, String source) {
        return CheckResult.newBuilder().setCode(code).setStatus(status).setMessage(message).setSource(source).build();
    }
}
