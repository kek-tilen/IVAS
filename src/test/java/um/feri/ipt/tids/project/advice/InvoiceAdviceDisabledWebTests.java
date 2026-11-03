package um.feri.ipt.tids.project.advice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ivas.azure-openai.enabled=false", "ivas.azure-openai.endpoint=", "ivas.azure-openai.api-key=", "ivas.azure-openai.deployment="})
class InvoiceAdviceDisabledWebTests {
    @LocalServerPort int port;
    @Test void missingConfigurationReturnsReadable503WithoutPreventingApplicationStartup() throws Exception {
        var http = HttpClient.newHttpClient();
        String base = "http://localhost:" + port;
        var config = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/invoices/advice/config")).build(), HttpResponse.BodyHandlers.ofString());
        var mapper = JsonMapper.builder().build();
        assertFalse(mapper.readTree(config.body()).path("configured").asBoolean());
        var result = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/invoices/advice"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        InvoiceAdviceTests.invoice(true, "Kaj preveriti?", "305.00")))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, result.statusCode());
        assertEquals("AI_NOT_CONFIGURED", mapper.readTree(result.body()).path("code").asString());
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test @EnabledIfSystemProperty(named = "advice.browser.tests", matches = "true")
    void browserExplainsMissingConfigurationAndNeverSendsAnInferenceRequest() {
        try (var playwright = com.microsoft.playwright.Playwright.create();
             var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setChannel("msedge"))) {
            var page = browser.newPage();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            page.onRequest(request -> { if (request.url().endsWith("/api/v1/invoices/advice")) calls.incrementAndGet(); });
            page.navigate("http://localhost:" + port + "/invoice-advice.html");
            page.waitForFunction("document.getElementById('ai-configuration').className === 'notice error'");
            assertTrue(page.locator("#request-advice").isDisabled());
            assertTrue(page.locator("#ai-configuration").innerText().contains("deployment"));
            page.locator("#sample").click();
            assertEquals("DEMO-2026-001", page.locator("[name=invoiceNumber]").inputValue());
            page.locator("[name=consent]").check();
            assertTrue(page.locator("#request-advice").isDisabled());
            assertEquals(0, calls.get());
            page.locator("[name=invoiceNumber]").focus();
            page.keyboard().press("Tab");
            assertEquals("supplierName", page.evaluate("document.activeElement.name"));
        }
    }
}
