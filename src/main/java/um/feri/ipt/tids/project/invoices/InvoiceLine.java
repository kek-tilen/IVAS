package um.feri.ipt.tids.project.invoices;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import um.feri.ipt.tids.project.verification.InvoiceData;

@Entity
@Table(name="invoice_line")
public class InvoiceLine {
    @Id UUID id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="invoice_id", nullable=false) InvoiceRecord invoice;
    @Column(name="line_position", nullable=false) int position;
    @Column(nullable=false, length=200) String description;
    @Column(nullable=false, precision=18, scale=4) BigDecimal quantity;
    @Column(nullable=false, precision=18, scale=4) BigDecimal unitNetPrice;
    protected InvoiceLine() { }
    InvoiceLine(InvoiceRecord invoice, int position, InvoiceData.Line line) {
        this.id = UUID.randomUUID(); this.invoice = invoice; this.position = position;
        this.description = line.description(); this.quantity = line.quantity(); this.unitNetPrice = line.unitNetPrice();
    }
    InvoiceData.Line data() { return new InvoiceData.Line(description, quantity, unitNetPrice); }
}
