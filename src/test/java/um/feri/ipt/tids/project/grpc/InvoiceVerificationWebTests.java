package um.feri.ipt.tids.project.grpc;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import um.feri.ipt.tids.project.verification.SupplierDirectory;
import um.feri.ipt.tids.project.grpc.v1.RegistryStatus;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InvoiceVerificationWebTests.Fixtures.class)
class InvoiceVerificationWebTests {
    static final VerificationFixtures.Directory directory = new VerificationFixtures.Directory();
    static final JsonMapper mapper = JsonMapper.builder().build();
    @LocalServerPort int port;
    @TestConfiguration static class Fixtures {
        @Bean @Primary SupplierDirectory fixtureDirectory() { return directory; }
    }
    @BeforeEach void reset() { directory.reset(); }
    @AfterEach void release() { directory.release.countDown(); }
    String url(String path) { return "http://localhost:"+port+path; }
    String input(boolean registry) { return mapper.writeValueAsString(new VerificationController.Input(VerificationFixtures.data("305.00"),registry)); }
    HttpRequest request(String path,String body) {
        return HttpRequest.newBuilder(URI.create(url("/api/v1/verification/"+path))).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> post(String path,String body) throws Exception { return HttpClient.newHttpClient().send(request(path,body),HttpResponse.BodyHandlers.ofString()); }
    @Test void servesAssetsCapabilitiesAndUsesRealRpcWithoutExternalCalls() throws Exception {
        for (String path : List.of("/invoice-verification.html","/js/invoice-verification.js","/css/invoice-verification.css","/api/v1/verification/capabilities")) {
            assertEquals(200,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url(path))).build(),HttpResponse.BodyHandlers.ofString()).statusCode(),path);
        }
        var response = post("local",mapper.writeValueAsString(Map.of("invoice",VerificationFixtures.data("305.00")))); assertEquals(200,response.statusCode());
        assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
        assertEquals("250.00",mapper.readTree(response.body()).path("local").path("calculatedLineNetTotal").asString());
        assertEquals(0,directory.calls.get());
        var result = post("supplier","{\"name\":\"Test supplier\",\"country\":\"CZ\",\"registryId\":\"00006947\"}");
        assertEquals(200,result.statusCode()); assertTrue(result.body().contains("00006947")); assertEquals(1,directory.calls.get());
    }
    @Test void rejectsMalformedMissingAndOversizedBodies() throws Exception {
        for (String body : List.of("{","{}","{\"invoice\":null}")) assertEquals(400,post("local",body).statusCode());
        var response = post("local","x".repeat(131073)); assertEquals(413,response.statusCode());
        assertEquals("REQUEST_TOO_LARGE",mapper.readTree(response.body()).path("code").asString());
        var chunked = HttpRequest.newBuilder(URI.create(url("/api/v1/verification/local"))).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[131073]))).build();
        assertEquals(413,HttpClient.newHttpClient().send(chunked,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400,post("supplier","{\"country\":\"CZ\",\"registryId\":\"12\"}").statusCode());
        assertEquals(0,directory.calls.get());
    }
    @Test void streamsEarlyLocalResultOverHttpBeforeRegistryResponse() throws Exception {
        directory.release = new CountDownLatch(1);
        var response = HttpClient.newHttpClient().send(request("stream",input(true)),HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200,response.statusCode()); assertTrue(response.headers().firstValue("content-type").orElseThrow().contains("text/event-stream"));
        assertTrue(directory.entered.await(3,TimeUnit.SECONDS));
        try (var reader = new BufferedReader(new InputStreamReader(response.body(),java.nio.charset.StandardCharsets.UTF_8))) {
            var prefix = CompletableFuture.supplyAsync(() -> {
                var text = new StringBuilder();
                try { String line; while ((line=reader.readLine()) != null) { text.append(line).append('\n'); if (line.contains("calculatedLineNetTotal")) return text.toString(); } }
                catch(IOException e) { throw new UncheckedIOException(e); }
                return text.toString();
            }).get(3,TimeUnit.SECONDS);
            assertTrue(prefix.contains("250.00")); assertFalse(prefix.contains("\"report\""));
            directory.release.countDown();
            String rest = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(rest.contains("\"report\"")); assertTrue(rest.contains("INCOMPLETE_VERIFICATION"));
        }
    }
    @Test void returnsTerminalSseErrorWithoutFakeFinalReport() throws Exception {
        directory.fail = true;
        var response = post("stream",input(true));
        assertEquals(200,response.statusCode()); assertTrue(response.body().contains("event:failure"));
        assertTrue(response.body().contains("INTERNAL")); assertFalse(response.body().contains("PRIVATE"));
        assertFalse(response.body().contains("\"report\""));
    }

    @Test @EnabledIfSystemProperty(named="verification.browser.tests",matches="true")
    void browserShowsStreamingEscapesTextCancelsAndSupportsMobile() throws Exception {
        try (var playwright = com.microsoft.playwright.Playwright.create();
             var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setChannel("msedge"))) {
            var page = browser.newPage(new com.microsoft.playwright.Browser.NewPageOptions().setViewportSize(1440,1100));
            var errors = new ArrayList<String>(); page.onPageError(errors::add);
            page.navigate(url("/invoice-verification.html")); page.waitForFunction("!document.getElementById('verification-fields').disabled");
            assertEquals(0,directory.calls.get()); page.locator("#sample").click(); assertEquals(0,directory.calls.get());
            page.locator("#validate").click();
            page.waitForFunction("!document.getElementById('verification-fields').disabled");
            assertFalse(page.locator("#json-panel").isHidden(), () -> page.locator("#verification-status").innerText()
                    + " INVALID=" + page.evaluate("[...document.querySelectorAll('input:invalid')].map(i=>[i.name,i.value,i.validationMessage])") + " ERRORS="+errors);
            assertTrue(page.locator("#local-totals").innerText().contains("250.00")); assertEquals(0,directory.calls.get());
            page.locator("[name=grossTotal]").fill("300.00"); assertTrue(page.locator("#verification-results").isHidden());
            page.locator("#verify").click(); page.locator("#report-panel").waitFor();
            assertTrue(page.locator("#assessment").innerText().contains("Potreben je pregled"));
            page.locator("#sample").click(); page.locator("[name=supplierCountry]").fill("CZ");
            page.locator("[name=supplierRegistryId]").fill("00006947");
            page.locator("[name=supplierName]").fill("<img src=x onerror=alert(1)>");
            directory.name = "<img src=x onerror=alert(1)>"; directory.release = new CountDownLatch(1);
            page.locator("[name=checkRegistry]").check(); page.locator("#verify").click();
            page.locator("#local-panel").waitFor(); assertTrue(page.locator("#report-panel").isHidden());
            assertTrue(directory.entered.await(3,TimeUnit.SECONDS)); directory.release.countDown();
            page.locator("#report-panel").waitFor(); assertEquals(0,page.locator("#supplier-result img").count());
            assertTrue(page.locator("#supplier-result").innerText().contains("<img"));
            Files.createDirectories(Path.of("target/verification-preview"));
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of("target/verification-preview/desktop.png")).setFullPage(true));
            page.setViewportSize(390,844); assertTrue((Boolean)page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of("target/verification-preview/mobile.png")).setFullPage(true));
            directory.reset(); directory.release = new CountDownLatch(1);
            page.locator("#verify").click(); page.locator("#local-panel").waitFor();
            assertTrue(directory.entered.await(3,TimeUnit.SECONDS)); page.locator("#cancel").click();
            page.waitForFunction("document.getElementById('verification-status').textContent.includes('prekinjeno')");
            assertTrue(page.locator("#report-panel").isHidden());
            assertFalse(page.locator("#verification-fields").isDisabled());
            assertTrue(directory.cancelled.await(5,TimeUnit.SECONDS), "Browser disconnect must cancel pending gRPC registry work");
            directory.release.countDown();
            page.locator("[name=checkRegistry]").uncheck(); page.locator("#verify").click(); page.locator("#report-panel").waitFor();
            assertTrue(errors.isEmpty(),errors.toString());
        }
    }
}
