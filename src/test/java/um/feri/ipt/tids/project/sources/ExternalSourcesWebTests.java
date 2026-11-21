package um.feri.ipt.tids.project.sources;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExternalSourcesWebTests.Fixtures.class)
class ExternalSourcesWebTests {
    static final FakeSources fake=new FakeSources();
    static final HttpClient client=HttpClient.newHttpClient();
    static final JsonMapper mapper=JsonMapper.builder().build();
    @LocalServerPort int port;
    @TestConfiguration static class Fixtures {
        @Bean @Primary FrenchCompanyClient fixtureFrance(SourceHttpClient http) { return new FrenchCompanyClient(http,fake.uri("/france"),Clock.systemUTC()); }
        @Bean @Primary ViesClient fixtureVies(SourceHttpClient http) { return new ViesClient(http,fake.uri("/vies"),Clock.systemUTC()); }
    }
    @BeforeEach void reset() { fake.reset(); }
    @AfterEach void release() { fake.release.countDown(); }
    @AfterAll static void close() { fake.close(); }
    String url(String path) { return "http://localhost:"+port+path; }
    HttpResponse<String> post(String path,String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url("/api/v1/sources/"+path))).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void servesAssetsAndReturnsFreshTypedResultsWithMetadata() throws Exception {
        for(String path:List.of("/invoice-verification.html","/js/external-sources.js"))
            assertEquals(200,client.send(HttpRequest.newBuilder(URI.create(url(path))).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        for(int i=0;i<2;i++) {
            var response=post("france","{\"registryId\":\"123456789\"}"); assertEquals(200,response.statusCode());
            assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
            var result=mapper.readTree(response.body()); assertEquals("FOUND",result.path("status").asString());
            assertEquals(FrenchCompanyClient.SOURCE,result.path("metadata").path("source").asString()); assertFalse(result.path("metadata").path("fetchedAt").asString().isBlank());
        }
        assertEquals(2,fake.franceCalls.get());
        assertEquals("VALID",mapper.readTree(post("vies","{\"countryCode\":\"SI\",\"vatNumber\":\"100\"}").body()).path("status").asString());
    }
    @Test void validatesInputsAndCapsBodiesBeforeCallingProviders() throws Exception {
        for(String body:List.of("{}","null","{","{\"registryId\":\"x\"}")) assertEquals(400,post("france",body).statusCode());
        for(String body:List.of("{}","null","{","{\"countryCode\":\"GB\",\"vatNumber\":\"100\"}")) assertEquals(400,post("vies",body).statusCode());
        for(String source:List.of("france","vies")) assertEquals(413,post(source,"x".repeat(131073)).statusCode());
        var chunked=HttpRequest.newBuilder(URI.create(url("/api/v1/sources/france"))).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(new byte[131073]))).build();
        assertEquals(413,client.send(chunked,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0,fake.franceCalls.get()); assertEquals(0,fake.viesCalls.get());
    }
    @Test void sendsSafeErrorsAndRetryAfterInsteadOfNegativeBusinessResults() throws Exception {
        fake.franceStatus=429; fake.franceBody="PRIVATE";
        var limited=post("france","{\"registryId\":\"123456789\"}"); assertEquals(429,limited.statusCode());
        assertEquals("7",limited.headers().firstValue("retry-after").orElseThrow()); assertFalse(limited.body().contains("PRIVATE"));
        assertEquals("SOURCE_RATE_LIMITED",mapper.readTree(limited.body()).path("code").asString());
        fake.viesStatus=500; fake.viesBody=FakeSources.fault("MS_UNAVAILABLE");
        var unavailable=post("vies","{\"countryCode\":\"SI\",\"vatNumber\":\"100\"}"); assertEquals(503,unavailable.statusCode());
        assertFalse(mapper.readTree(unavailable.body()).has("status")); assertFalse(mapper.readTree(unavailable.body()).has("valid"));
        assertEquals("no-store",unavailable.headers().firstValue("cache-control").orElseThrow());
    }
    @Test @EnabledIfSystemProperty(named="sources.browser.tests",matches="true")
    void browserUsesExplicitActionsClearsStaleResultsAndDisplaysFailuresAndMobileLayout() throws Exception {
        try(var playwright=com.microsoft.playwright.Playwright.create();var browser=playwright.chromium().launch(
                new com.microsoft.playwright.BrowserType.LaunchOptions().setChannel("msedge"))) {
            var page=browser.newPage(new com.microsoft.playwright.Browser.NewPageOptions().setViewportSize(1440,1100));
            var errors=new ArrayList<String>(); page.onPageError(errors::add);
            page.navigate(url("/invoice-verification.html#external-sources")); assertEquals(0,fake.franceCalls.get()); assertEquals(0,fake.viesCalls.get());
            page.locator("#france-source-form [name=registryId]").fill("12345678900002"); page.locator("#france-source-form [type=submit]").click();
            page.locator("#france-source-result").waitFor(); assertTrue(page.locator("#france-source-result").innerText().contains("12345678900002"));
            assertEquals(0,page.locator("#france-source-result img").count()); assertTrue(page.locator("#france-source-result").innerText().contains("<img"));
            page.locator("#vies-source-form [name=countryCode]").fill("SI"); page.locator("#vies-source-form [name=vatNumber]").fill("100");
            page.locator("#vies-source-form [type=submit]").click(); page.locator("#vies-source-result").waitFor();
            assertTrue(page.locator("#vies-source-result").innerText().contains("veljavna v VIES")); assertEquals(0,page.locator("#vies-source-result img").count());
            Files.createDirectories(Path.of("target/sources-preview"));
            page.locator("#external-sources").screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions().setPath(Path.of("target/sources-preview/desktop.png")));
            page.setViewportSize(390,844); assertTrue((Boolean)page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
            page.locator("#external-sources").screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions().setPath(Path.of("target/sources-preview/mobile.png")));
            page.locator("#vies-source-form [name=vatNumber]").fill("200"); assertTrue(page.locator("#vies-source-result").isHidden());
            fake.viesStatus=500; fake.viesBody=FakeSources.fault("MS_UNAVAILABLE");
            page.locator("#vies-source-form [type=submit]").click(); page.waitForFunction("document.getElementById('vies-source-status').classList.contains('error')");
            assertTrue(page.locator("#vies-source-result").isHidden()); assertTrue(page.locator("#vies-source-status").innerText().contains("MS_UNAVAILABLE"));
            fake.franceStatus=503; page.locator("#france-source-form [type=submit]").click();
            page.waitForFunction("document.getElementById('france-source-status').classList.contains('error')"); assertTrue(page.locator("#france-source-result").isHidden());
            fake.franceStatus=200; page.locator("#france-source-form [type=submit]").click(); page.locator("#france-source-result").waitFor();
            fake.slowFrance=true; fake.release=new CountDownLatch(1);
            page.locator("#france-source-form [type=submit]").click(); page.locator("#france-source-cancel").click();
            page.waitForFunction("document.getElementById('france-source-status').textContent.includes('prekinjeno')");
            assertTrue(page.locator("#france-source-result").isHidden()); assertFalse(page.locator("#france-source-fields").isDisabled()); fake.release.countDown();
            assertTrue(errors.isEmpty(),errors.toString());
        }
    }
}
