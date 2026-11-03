package um.feri.ipt.tids.project.advice;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InvoiceAdviceWebTests.FixtureConfiguration.class)
class InvoiceAdviceWebTests {
    private static final FakeAzure fake = new FakeAzure();
    private static final JsonMapper mapper = JsonMapper.builder().build();
    @LocalServerPort int port;
    @BeforeEach void reset() { fake.reset(); }
    @AfterAll static void close() { fake.close(); }

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean @Primary AzureOpenAiClient fixtureClient() {
            return new AzureOpenAiClient(HttpClient.newHttpClient(), fake.endpoint(), "fixture-key", "test-deployment",
                    Duration.ofSeconds(5), 4096);
        }
    }
    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/invoices/advice"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private String input(boolean consent) {
        return mapper.writeValueAsString(InvoiceAdviceTests.invoice(consent, "Kaj preveriti pri tem računu?", "305.00"));
    }

    @Test void servesFormAndConfigurationWithoutInferenceOrSecrets() throws Exception {
        for (String path : new String[]{"/invoice-advice.html", "/js/invoice-advice.js", "/css/invoice-advice.css",
                                        "/api/v1/invoices/advice/config"}) {
            var response = get(path);
            assertEquals(200, response.statusCode(), path);
            assertFalse(response.body().contains("fixture-key"));
        }
        assertTrue(mapper.readTree(get("/api/v1/invoices/advice/config").body()).path("configured").asBoolean());
        assertTrue(get("/").body().contains("/invoice-advice.html"));
        assertEquals(0, fake.calls.get());
    }

    @Test void returnsTypedJsonOnlyForValidatedConsentingRequest() throws Exception {
        var response = post(input(true));
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
        assertTrue(mapper.readTree(response.body()).path("localChecks").path("calculatedLineNetTotal").isString());
        var result = mapper.readValue(response.body(), InvoiceAdviceResponse.class);
        assertEquals("AZURE_OPENAI", result.metadata().provider());
        assertEquals("PASS", result.localChecks().checks().getFirst().status());
        assertFalse(result.advice().recommendedActions().isEmpty());
        assertFalse(response.body().contains("fixture-key"));
        assertEquals(1, fake.calls.get());
    }

    @Test void rejectsMissingConsentMalformedInputAndNullFieldsWithoutInference() throws Exception {
        for (String body : new String[]{input(false), "{", "{}", input(true).replace("2026-11-03", "not-a-date")}) {
            var response = post(body);
            assertEquals(400, response.statusCode(), response.body());
            assertFalse(response.body().contains("fixture-key"));
        }
        assertEquals(0, fake.calls.get());
    }

    @Test void exposesSanitizedProviderErrorsAndRejectsConcurrentRequest() throws Exception {
        fake.status = 401; fake.response = "secret fixture-key";
        var unauthorized = post(input(true));
        assertEquals(503, unauthorized.statusCode());
        assertFalse(unauthorized.body().contains("fixture-key"));
        fake.reset(); fake.delayMillis = 700;
        var first = CompletableFuture.supplyAsync(() -> {
            try { return post(input(true)); } catch (Exception e) { throw new RuntimeException(e); }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (fake.calls.get() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(429, post(input(true)).statusCode());
        assertEquals(200, first.get().statusCode());
        assertEquals(1, fake.calls.get());
    }

    @Test
    @EnabledIfSystemProperty(named = "advice.browser.tests", matches = "true")
    void browserSubmitsOnlyAfterConsentRendersSafeJsonAndRecoversFromFailure() throws Exception {
        try (var playwright = com.microsoft.playwright.Playwright.create();
             var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setChannel("msedge"))) {
            var page = browser.newPage(new com.microsoft.playwright.Browser.NewPageOptions().setViewportSize(1440, 1100));
            var errors = new java.util.ArrayList<String>();
            page.onPageError(errors::add);
            page.navigate("http://localhost:" + port + "/invoice-advice.html");
            page.waitForFunction("!document.getElementById('request-advice').disabled");
            assertEquals(0, fake.calls.get(), "Loading a page/config must not infer");
            page.locator("#sample").click();
            assertEquals(0, fake.calls.get(), "Sample insertion must not infer");
            page.locator("#request-advice").click();
            assertEquals(0, fake.calls.get(), "Consent is mandatory");
            page.locator("[name=consent]").check();
            page.locator("#request-advice").click();
            page.locator("#advice-results").waitFor();
            assertEquals(1, fake.calls.get());
            assertTrue(page.locator("#ai-summary").innerText().contains("registri niso preverjeni"));
            assertTrue(page.locator("#local-checks").innerText().contains("Ni preverjeno"));
            assertFalse(page.locator("[name=consent]").isChecked());
            var sent = mapper.readTree(fake.lastBody);
            assertTrue(sent.path("messages").get(1).path("content").asString().contains("DEMO-2026-001"));
            Files.createDirectories(Path.of("target/advice-preview"));
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of("target/advice-preview/desktop.png")).setFullPage(true));
            page.setViewportSize(390, 844);
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of("target/advice-preview/mobile.png")).setFullPage(true));
            page.locator("[name=question]").fill("Pojasni razliko med bruto in neto zneskom.");
            assertTrue(page.locator("#advice-results").isHidden(), "Edited inputs invalidate old results");
            fake.status = 429;
            page.locator("[name=consent]").check(); page.locator("#request-advice").click();
            page.waitForFunction("document.getElementById('advice-status').className === 'error'");
            assertFalse(page.locator("#request-advice").isDisabled());
            assertTrue(page.locator("#advice-results").isHidden());
            fake.status = 200;
            fake.response = FakeAzure.completion(FakeAzure.ANSWER.replace("Podatki so ročno vneseni.", "<img src=x onerror=alert(1)>"), "stop");
            page.locator("[name=consent]").check(); page.locator("#request-advice").click();
            page.locator("#advice-results").waitFor();
            assertTrue(page.locator("#ai-observations").innerText().contains("<img"));
            assertEquals(0, page.locator("#ai-observations img").count(), "AI text must never become HTML");
            assertEquals(3, fake.calls.get());
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }
}
