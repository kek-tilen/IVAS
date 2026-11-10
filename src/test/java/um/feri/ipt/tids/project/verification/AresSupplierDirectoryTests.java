package um.feri.ipt.tids.project.verification;

import org.junit.jupiter.api.Test;
import um.feri.ipt.tids.project.parser.ares.AresResult;
import um.feri.ipt.tids.project.grpc.v1.RegistryStatus;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AresSupplierDirectoryTests {
    private AresResult raw(AresResult.Status status, AresResult.Company company) {
        return new AresResult("1.0",new AresResult.Metadata("ARES_WEB","HTML_DOM",
                "https://ares.gov.cz/ekonomicke-subjekty?ico=00006947","2026-11-10T12:00:00Z"),
                new AresResult.Query("00006947"),status,company,List.of(),
                List.of(new AresResult.Problem("PRIVATE","PRIVATE technical error")));
    }
    @Test void mapsSuccessAndOptionalFieldsWithoutInventingIdentifiers() {
        var result = AresSupplierDirectory.mapResult(raw(AresResult.Status.SUCCESS,
                new AresResult.Company("00006947","Test supplier",null,null,null)));
        assertEquals(RegistryStatus.FOUND,result.getStatus()); assertEquals("00006947",result.getSupplier().getRegistryId());
        assertFalse(result.getSupplier().hasVatId()); assertTrue(result.getSource().contains("HTML_DOM"));
        assertTrue(result.hasFetchedAt());
        var partial = AresSupplierDirectory.mapResult(raw(AresResult.Status.PARTIAL,
                new AresResult.Company("00006947",null,null,null,null)));
        assertEquals(RegistryStatus.PARTIAL,partial.getStatus()); assertEquals("",partial.getSupplier().getName());
    }
    @Test void mapsNotFoundSeparatelyFromTechnicalErrorsAndSanitizesDetails() {
        var missing = AresSupplierDirectory.mapResult(raw(AresResult.Status.NOT_FOUND,null));
        var error = AresSupplierDirectory.mapResult(raw(AresResult.Status.ERROR,null));
        assertEquals(RegistryStatus.NOT_FOUND,missing.getStatus()); assertEquals(RegistryStatus.SOURCE_ERROR,error.getStatus());
        assertFalse(error.getMessage().contains("PRIVATE")); assertFalse(error.hasSupplier());
    }
}
