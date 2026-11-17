package um.feri.ipt.tids.project.invoices;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Persistence foundation only; stage 1 never invents a verification run. */
@Entity @Table(name="verification_run")
public class VerificationRun {
    @Id UUID id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="invoice_id", nullable=false) InvoiceRecord invoice;
    @Column(nullable=false) Instant startedAt;
    Instant completedAt;
    @Column(nullable=false, length=20) String state;
    @Column(length=40) String assessment;
    @Column(nullable=false, columnDefinition="text") String invoiceSnapshot;
    protected VerificationRun() { }
}
