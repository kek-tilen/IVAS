package um.feri.ipt.tids.project.invoices;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<InvoiceRecord, UUID> { }
