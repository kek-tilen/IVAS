package um.feri.ipt.tids.project.invoices;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import um.feri.ipt.tids.project.verification.InvoiceData;

@Entity
@Table(name = "invoice_record")
public class InvoiceRecord {
    @Id UUID id;
    @Version Long version;
    @Column(nullable=false, length=100) String invoiceNumber;
    @Column(nullable=false, length=200) String supplierName;
    @Column(nullable=false, length=2) String supplierCountry;
    @Column(length=40) String supplierVatId;
    @Column(length=40) String supplierRegistryId;
    @Column(nullable=false) LocalDate issueDate;
    LocalDate dueDate;
    @Column(nullable=false, length=3) String currency;
    @Column(nullable=false, precision=16, scale=3) BigDecimal netTotal;
    @Column(nullable=false, precision=16, scale=3) BigDecimal taxTotal;
    @Column(nullable=false, precision=16, scale=3) BigDecimal grossTotal;
    @Column(nullable=false, length=2000) String note;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) HandlingStatus handlingStatus;
    @Column(nullable=false, updatable=false) Instant createdAt;
    @Column(nullable=false) Instant updatedAt;
    @OneToMany(mappedBy="invoice", cascade=CascadeType.ALL, orphanRemoval=true)
    @OrderBy("position ASC") List<InvoiceLine> lines = new ArrayList<>();

    protected InvoiceRecord() { }
    static InvoiceRecord create(InvoiceData data, String note, HandlingStatus status) {
        var record = new InvoiceRecord();
        record.id = UUID.randomUUID();
        record.invoiceNumber = data.invoiceNumber(); record.supplierName = data.supplierName();
        record.supplierCountry = data.supplierCountry(); record.supplierVatId = data.supplierVatId();
        record.supplierRegistryId = data.supplierRegistryId(); record.issueDate = data.issueDate();
        record.dueDate = data.dueDate(); record.currency = data.currency();
        record.netTotal = data.netTotal(); record.taxTotal = data.taxTotal(); record.grossTotal = data.grossTotal();
        record.note = note; record.handlingStatus = status;
        record.createdAt = record.updatedAt = Instant.now();
        for (int i=0; i<data.items().size(); i++) record.lines.add(new InvoiceLine(record, i, data.items().get(i)));
        return record;
    }
    InvoiceData data() {
        return new InvoiceData(invoiceNumber, supplierName, supplierCountry, supplierVatId, supplierRegistryId,
                issueDate, dueDate, currency, lines.stream().map(InvoiceLine::data).toList(), netTotal, taxTotal, grossTotal);
    }
}
