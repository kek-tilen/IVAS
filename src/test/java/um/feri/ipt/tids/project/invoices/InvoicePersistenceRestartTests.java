package um.feri.ipt.tids.project.invoices;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import um.feri.ipt.tids.project.ProjectApplication;
import um.feri.ipt.tids.project.verification.InvoiceData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoicePersistenceRestartTests {
    @Test void invoiceAndWorkflowSurviveClosingAndReopeningTheApplication() {
        UUID id;
        try (var first = application()) {
            var service = first.getBean(InvoiceService.class);
            var data = new InvoiceData("RESTART-" + UUID.randomUUID(), "Sintetični dobavitelj", "CZ", null, "00006947",
                    LocalDate.of(2026,11,17), null, "EUR", List.of(new InvoiceData.Line("Postavka", BigDecimal.ONE, new BigDecimal("10.00"))),
                    new BigDecimal("10.00"), new BigDecimal("2.20"), new BigDecimal("12.20"));
            var saved = service.create(new InvoiceService.Create(data,"Pred ponovnim zagonom",HandlingStatus.NEW));
            id = saved.id(); service.update(id,saved.version(),"Shranjena opomba čšž",HandlingStatus.IN_REVIEW);
        }
        try (var second = application()) {
            var saved = second.getBean(InvoiceService.class).get(id);
            assertEquals("Shranjena opomba čšž", saved.note()); assertEquals(HandlingStatus.IN_REVIEW,saved.handlingStatus());
            assertEquals("00006947",saved.invoice().supplierRegistryId()); assertEquals(1,saved.invoice().items().size());
            assertEquals(0,new BigDecimal("12.20").compareTo(saved.invoice().grossTotal()));
            second.getBean(InvoiceRepository.class).deleteById(id);
        }
    }
    private org.springframework.context.ConfigurableApplicationContext application() {
        return new SpringApplicationBuilder(ProjectApplication.class).web(WebApplicationType.NONE)
                .run("--ivas.grpc.port=0", "--spring.main.banner-mode=off");
    }
}
