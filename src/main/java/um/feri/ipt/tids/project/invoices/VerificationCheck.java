package um.feri.ipt.tids.project.invoices;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="verification_check")
public class VerificationCheck {
    @Id UUID id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="run_id", nullable=false) VerificationRun run;
    @Column(nullable=false, length=80) String code;
    @Column(nullable=false, length=20) String status;
    @Column(nullable=false, length=100) String source;
    @Column(columnDefinition="text") String sourceUrl;
    Instant fetchedAt;
    @Column(nullable=false, columnDefinition="text") String message;
    protected VerificationCheck() { }
}
