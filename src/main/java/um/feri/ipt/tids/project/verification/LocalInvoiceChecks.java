package um.feri.ipt.tids.project.verification;

import java.math.*;
import java.util.*;

/** Shared deterministic invoice checks, independent of Azure and both transport protocols. */
public final class LocalInvoiceChecks {
    private LocalInvoiceChecks() { }
    public record LocalCheck(String code, String status, String message) { }
    public record Evaluation(BigDecimal calculatedLineNetTotal, BigDecimal expectedGrossTotal, List<LocalCheck> checks) { }
    public static void validate(InvoiceData r) {
        if (r == null) invalid("Manjkajo podatki računa.");
        text(r.invoiceNumber(), 100, "Številka računa", true);
        text(r.supplierName(), 200, "Naziv dobavitelja", true);
        if (r.supplierCountry() == null || !Set.of(Locale.getISOCountries()).contains(r.supplierCountry()))
            invalid("Država dobavitelja mora biti veljavna dvočrkovna oznaka, npr. SI.");
        text(r.supplierVatId(), 40, "DDV identifikator", false);
        if (r.issueDate() == null || r.issueDate().getYear() < 1900 || r.issueDate().getYear() > 2100)
            invalid("Datum izdaje mora biti med letoma 1900 in 2100.");
        if (r.dueDate() != null && (r.dueDate().getYear() < 1900 || r.dueDate().getYear() > 2100))
            invalid("Datum zapadlosti mora biti med letoma 1900 in 2100.");
        text(r.supplierRegistryId(), 40, "Registrski identifikator", false);
        int digits = currencyDigits(r.currency());
        amount(r.netTotal(), digits, "Neto skupaj");
        amount(r.taxTotal(), digits, "Davek skupaj");
        amount(r.grossTotal(), digits, "Bruto skupaj");
        if (r.items() == null || r.items().isEmpty() || r.items().size() > 30)
            invalid("Račun mora vsebovati od 1 do 30 postavk.");
        for (var item : r.items()) {
            if (item == null) invalid("Postavka ne sme biti prazna.");
            text(item.description(), 200, "Opis postavke", true);
            amount(item.quantity(), 4, "Količina");
            if (item.quantity().signum() <= 0) invalid("Količina mora biti večja od nič.");
            amount(item.unitNetPrice(), 4, "Neto cena enote");
        }
    }

    public static Evaluation evaluate(InvoiceData r) {
        int digits = currencyDigits(r.currency());
        BigDecimal sum = r.items().stream()
                .map(i -> i.quantity().multiply(i.unitNetPrice()).setScale(digits, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(digits, RoundingMode.HALF_UP);
        BigDecimal expectedGross = r.netTotal().add(r.taxTotal()).setScale(digits, RoundingMode.HALF_UP);
        List<LocalCheck> checks = new ArrayList<>();
        checks.add(new LocalCheck("NET_TOTAL", sum.compareTo(r.netTotal()) == 0 ? "PASS" : "FAIL",
                "Vsota neto postavk (vsaka zaokrožena HALF_UP na najmanjšo enoto valute): "
                        + sum.toPlainString() + " " + r.currency() + "; navedeni neto: " + r.netTotal().toPlainString() + "."));
        checks.add(new LocalCheck("GROSS_TOTAL", expectedGross.compareTo(r.grossTotal()) == 0 ? "PASS" : "FAIL",
                "Neto + navedeni davek: " + expectedGross.toPlainString() + " " + r.currency()
                        + "; navedeni bruto: " + r.grossTotal().toPlainString() + "."));
        checks.add(new LocalCheck("DUE_DATE", r.dueDate() == null ? "UNKNOWN"
                : r.dueDate().isBefore(r.issueDate()) ? "FAIL" : "PASS",
                r.dueDate() == null ? "Datum zapadlosti ni vnesen." : "Primerjava zapadlosti z datumom izdaje."));
        return new Evaluation(sum, expectedGross, List.copyOf(checks));
    }

    private static void text(String value, int max, String field, boolean required) {
        if (required && (value == null || value.isBlank())) invalid(field + " je obvezno polje.");
        if (value != null && (value.length() > max || value.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\t')))
            invalid(field + " vsebuje nedovoljeno ali predolgo besedilo.");
    }
    private static void amount(BigDecimal n, int scale, String field) {
        if (n == null || n.signum() < 0 || n.scale() < -6 || n.scale() > scale
                || n.compareTo(new BigDecimal("1000000000")) > 0) invalid(field + " ima neveljavno vrednost ali natančnost.");
    }
    private static int currencyDigits(String currency) {
        try {
            if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException();
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            if (digits < 0 || digits > 3) throw new IllegalArgumentException();
            return digits;
        } catch (IllegalArgumentException e) { invalid("Valuta mora biti podprta oznaka ISO 4217, npr. EUR."); return 0; }
    }
    private static void invalid(String message) { throw new IllegalArgumentException(message); }
}
