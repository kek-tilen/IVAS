package um.feri.ipt.tids.project.sources;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static um.feri.ipt.tids.project.sources.SourceModels.*;

class ExternalSourceClientTests {
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"),ZoneOffset.UTC);
    FrenchCompanyClient france(FakeSources fake,SourceHttpClient http) { return new FrenchCompanyClient(http,fake.uri("/france"),CLOCK); }
    ViesClient vies(FakeSources fake,SourceHttpClient http) { return new ViesClient(http,fake.uri("/vies"),CLOCK); }

    @Test void franceUsesExactIdsAndCanFindANonHeadOfficeEstablishment() {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofSeconds(3))) {
            var client=france(fake,http);
            var company=client.lookup("123456789");
            assertEquals(RegistryStatus.FOUND,company.status()); assertEquals("12345678900001",company.company().siret());
            assertEquals(CLOCK.instant(),company.metadata().fetchedAt()); assertEquals(FrenchCompanyClient.SOURCE,company.metadata().source());
            assertTrue(fake.franceQuery.contains("q=123456789")); assertTrue(fake.franceQuery.contains("minimal=true"));
            assertTrue(fake.franceQuery.contains("include=siege%2Cmatching_etablissements"));
            var branch=client.lookup("12345678900002");
            assertEquals(RegistryStatus.FOUND,branch.status()); assertEquals("12345678900002",branch.company().siret());
            assertEquals("F",branch.company().establishmentStatus()); assertEquals("A",branch.company().companyStatus());
            fake.franceBody=FakeSources.FRANCE.replace("123456789","000000001");
            assertEquals("000000001",client.lookup("000000001").company().siren());
            assertEquals(3,fake.franceCalls.get(),"Every explicit call fetches fresh data");
        }
    }
    @Test void franceDistinguishesMissingPublicRecordsFromPartialData() {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofSeconds(3))) {
            var client=france(fake,http);
            fake.franceBody="{\"results\":[],\"total_results\":0}";
            var missing=client.lookup("123456789"); assertEquals(RegistryStatus.NOT_FOUND,missing.status()); assertNull(missing.company());
            fake.franceBody=FakeSources.FRANCE.replace("\"nom_complet\":\"Entreprise & <img src=x onerror=alert(1)> čšž\",","");
            var partial=client.lookup("123456789"); assertEquals(RegistryStatus.PARTIAL,partial.status()); assertNull(partial.company().name());
        }
    }
    @Test void franceRejectsAmbiguousMismatchedWronglyTypedAndMalformedResponses() {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofSeconds(3))) {
            var client=france(fake,http);
            for(String body:List.of("null","{}","not-json",FakeSources.FRANCE+" {}",
                    FakeSources.FRANCE.replace("\"total_results\":1","\"total_results\":2"),
                    FakeSources.FRANCE.replace("\"total_results\":1","\"total_results\":18446744073709551617"),
                    FakeSources.FRANCE.replace("\"siren\":\"123456789\"","\"siren\":123456789"),
                    FakeSources.FRANCE.replace("123456789","999999999"),
                    FakeSources.FRANCE.replace("\"results\":","\"results\":[],\"results\":"))) {
                fake.franceBody=body;
                assertEquals("SOURCE_INVALID_RESPONSE",assertThrows(SourceFailure.class,()->client.lookup("123456789")).body().code());
            }
            fake.franceBody=FakeSources.FRANCE;
            assertThrows(SourceFailure.class,()->client.lookup("12345678900003"),"Parent company alone does not confirm a SIRET");
        }
    }
    @Test void rejectsInvalidIdentifiersBeforeAnyExternalRequest() {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofSeconds(3))) {
            for(String id:Arrays.asList(null,"","La Poste","12345678","1234567890123","123456789?x=1"))
                assertThrows(IllegalArgumentException.class,()->france(fake,http).lookup(id));
            for(String[] input:List.of(new String[]{"GB","100"},new String[]{"ZZ","100"},new String[]{"SI","<x/>"},new String[]{"SI","1"},new String[]{"SI","100 200"}))
                assertThrows(IllegalArgumentException.class,()->vies(fake,http).check(input[0],input[1]));
            assertEquals(0,fake.franceCalls.get()); assertEquals(0,fake.viesCalls.get());
        }
    }
    @Test void mapsHttpFailuresDoesNotFollowRedirectsAndNeverRetriesOrLeaksProviderBodies() {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofSeconds(3))) {
            var client=france(fake,http);
            for(int status:List.of(302,400,404,429,500,503)) {
                fake.reset(); fake.franceStatus=status; fake.franceBody="PRIVATE provider failure";
                var error=assertThrows(SourceFailure.class,()->client.lookup("123456789"));
                assertEquals(status==429?429:503,error.status()); assertFalse(error.body().toString().contains("PRIVATE"));
                assertEquals(1,fake.franceCalls.get()); assertEquals(0,fake.otherCalls.get());
                assertEquals(FrenchCompanyClient.SOURCE,error.body().source()); assertNotNull(error.body().attemptedAt());
                if(status==429) assertEquals(7,error.body().retryAfterSeconds());
            }
            fake.reset(); fake.franceBody="x".repeat(SourceHttpClient.MAX_BODY+1);
            assertEquals("SOURCE_INVALID_RESPONSE",assertThrows(SourceFailure.class,()->client.lookup("123456789")).body().code());
        }
    }
    @Test void deadlineAlsoStopsABodyThatStallsAfterResponseHeaders() throws Exception {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofMillis(250))) {
            fake.slowFrance=true; fake.release=new CountDownLatch(1);
            long start=System.nanoTime();
            try {
                var error=assertThrows(SourceFailure.class,()->france(fake,http).lookup("123456789"));
                assertEquals("SOURCE_TIMEOUT",error.body().code()); assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2000);
                assertEquals(0,fake.headers.getCount(),"The deadline must cover reads after headers");
            } finally { fake.release.countDown(); }
        }
    }
    @Test void limitsConcurrentUpstreamRequestsWithoutQueueingMoreConnections() throws Exception {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofSeconds(3))) {
            fake.slowFrance=true; fake.release=new CountDownLatch(1);
            var calls=new ArrayList<CompletableFuture<FranceResult>>();
            for(int i=0;i<4;i++) calls.add(CompletableFuture.supplyAsync(()->france(fake,http).lookup("123456789")));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(fake.franceCalls.get()<4 && System.nanoTime()<deadline) Thread.sleep(10);
                assertEquals(4,fake.franceCalls.get());
                assertEquals("SOURCE_BUSY",assertThrows(SourceFailure.class,()->france(fake,http).lookup("123456789")).body().code());
                assertEquals(4,fake.franceCalls.get());
            } finally { fake.release.countDown(); for(var call:calls) call.get(3,TimeUnit.SECONDS); }
        }
    }
    @Test void sendsSoap11AndParsesNamespacesBooleansDatesAndEscapedText() {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofSeconds(3))) {
            var client=vies(fake,http); var result=client.check("si","100");
            assertEquals(VatStatus.VALID,result.status()); assertEquals(LocalDate.of(2026,11,21),result.requestDate());
            assertEquals("Fixture & <img src=x> čšž",result.name()); assertEquals("Naslov\nMesto",result.address());
            assertEquals("\"\"",fake.soapAction); assertEquals("text/xml; charset=UTF-8",fake.contentType);
            assertTrue(fake.soapRequest.contains("<v:countryCode>SI</v:countryCode>")); assertTrue(fake.soapRequest.contains(ViesClient.TYPES));
            for(String lexical:List.of("false","0","1")) {
                fake.viesBody=FakeSources.soap("SI","100",lexical);
                assertEquals(lexical.equals("1")?VatStatus.VALID:VatStatus.INVALID,client.check("SI","100").status());
            }
            fake.viesBody=FakeSources.soap("EL","100","true"); assertEquals("EL",client.check("GR","100").countryCode());
            fake.viesBody=FakeSources.soap("XI","100","true"); assertEquals("XI",client.check("XI","100").countryCode());
        }
    }
    @Test void mapsSoapFaultsIncludingHttp200FaultsWithoutClaimingAnInvalidVatNumber() {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofSeconds(3))) {
            var client=vies(fake,http);
            for(var item:Map.of("INVALID_INPUT",400,"MS_UNAVAILABLE",503,"SERVICE_UNAVAILABLE",503,"TIMEOUT",504,
                    "GLOBAL_MAX_CONCURRENT_REQ",429,"MS_MAX_CONCURRENT_REQ",429,"GLOBAL_MAX_CONCURRENT_REQ_TIME",429,
                    "MS_MAX_CONCURRENT_REQ_TIME",429,"VAT_BLOCKED",503).entrySet()) {
                fake.viesStatus=500; fake.viesBody=FakeSources.fault(item.getKey());
                var error=assertThrows(SourceFailure.class,()->client.check("SI","100"));
                assertEquals(item.getValue(),error.status()); assertEquals(item.getKey(),error.body().providerCode());
                assertFalse(error.body().toString().contains("status=INVALID"));
            }
            fake.viesStatus=200; fake.viesBody=FakeSources.fault("MS_UNAVAILABLE");
            assertEquals(503,assertThrows(SourceFailure.class,()->client.check("SI","100")).status());
            fake.viesBody=FakeSources.fault("PRIVATE unknown error");
            var unknown=assertThrows(SourceFailure.class,()->client.check("SI","100")); assertNull(unknown.body().providerCode());
            assertFalse(unknown.body().toString().contains("PRIVATE"));
        }
    }
    @Test void rejectsMismatchedDuplicateMissingAndSpoofedSoapValuesAndExternalEntities() {
        try(var fake=new FakeSources();var http=new SourceHttpClient(Duration.ofSeconds(3))) {
            var client=vies(fake,http); String valid=FakeSources.soap("SI","100","true");
            for(String body:List.of("not XML",valid.replace("<r:valid>true</r:valid>",""),
                    valid.replace("<r:valid>true</r:valid>","<r:valid>true</r:valid><r:valid>false</r:valid>"),
                    valid.replace("<r:valid>true</r:valid>","<r:valid>yes</r:valid>"),
                    valid.replace("<r:vatNumber>100", "<r:vatNumber>200"),valid.replace("<r:countryCode>SI","<r:countryCode>FR"),
                    valid.replace(ViesClient.TYPES,"urn:wrong"),valid.replace("2026-11-21+01:00","2026-02-31"),
                    "<!DOCTYPE e:Envelope [<!ENTITY xxe SYSTEM \""+fake.uri("/other")+"\">]>"+valid.replace("Fixture &amp;","&xxe;"))) {
                fake.viesBody=body;
                assertEquals("SOURCE_INVALID_RESPONSE",assertThrows(SourceFailure.class,()->client.check("SI","100")).body().code());
            }
            assertEquals(0,fake.otherCalls.get());
            fake.viesStatus=500; fake.viesBody=valid;
            assertEquals(503,assertThrows(SourceFailure.class,()->client.check("SI","100")).status());
        }
    }
}
