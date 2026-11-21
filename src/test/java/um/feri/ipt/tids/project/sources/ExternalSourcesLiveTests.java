package um.feri.ipt.tids.project.sources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static um.feri.ipt.tids.project.sources.SourceModels.*;

/** Opt-in live tests: public French registry IDs and the official synthetic VIES test service. */
@EnabledIfSystemProperty(named="sources.live.tests",matches="true")
class ExternalSourcesLiveTests {
    @Test void retrievesPublicFrenchCompanyBySiren() {
        try(var http=new SourceHttpClient(Duration.ofSeconds(20))) {
            var result=new FrenchCompanyClient(http).lookup("356000000");
            assertEquals(RegistryStatus.FOUND,result.status()); assertEquals("356000000",result.company().siren());
            assertNotNull(result.company().name());
        }
    }
    @Test void retrievesPublicFrenchEstablishmentBySiret() {
        try(var http=new SourceHttpClient(Duration.ofSeconds(20))) {
            var result=new FrenchCompanyClient(http).lookup("35600000000048");
            assertEquals(RegistryStatus.FOUND,result.status()); assertEquals("35600000000048",result.company().siret());
        }
    }
    @Test void performsRealSoapExchangeWithOfficialSyntheticViesTestService() {
        try(var http=new SourceHttpClient(Duration.ofSeconds(20))) {
            var client=new ViesClient(http,URI.create("https://ec.europa.eu/taxation_customs/vies/test-services/checkVatTestService"),Clock.systemUTC());
            assertEquals(VatStatus.VALID,client.check("SI","100").status());
            assertEquals(VatStatus.INVALID,client.check("SI","200").status());
        }
    }
}
