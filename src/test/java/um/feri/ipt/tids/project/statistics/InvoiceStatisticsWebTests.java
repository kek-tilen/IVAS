package um.feri.ipt.tids.project.statistics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import um.feri.ipt.tids.project.parser.pcaxis.PcAxisParser;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.io.IOException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InvoiceStatisticsWebTests.FixtureConfiguration.class)
class InvoiceStatisticsWebTests {

    @LocalServerPort int port;

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "statistics.browser.tests", matches = "true")
    void filtersRefreshesAndRecoversFromSourceFailureInBrowser() throws Exception {
        try (var playwright = com.microsoft.playwright.Playwright.create();
             var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setChannel("msedge"))) {
            var page = browser.newPage(new com.microsoft.playwright.Browser.NewPageOptions().setViewportSize(1440, 1100));
            var errors = new java.util.ArrayList<String>();
            page.onPageError(errors::add);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            page.onRequest(request -> { if (request.url().endsWith("/api/v1/statistics/invoices")) calls.incrementAndGet(); });
            page.navigate("http://localhost:" + port);
            page.locator("#results").waitFor();
            assertTrue(page.locator("#total").innerText().replace(".", "").replace("\u00a0", "").equals("9033"));
            assertEquals(2, ((Number) page.evaluate("Object.keys(Chart.instances).length")).intValue());
            page.locator("#year").selectOption("2021");
            page.locator("#size").selectOption("4");
            assertEquals("223", page.locator("#total").innerText());
            assertEquals("—", page.locator("#change").innerText());
            assertEquals(1, calls.get(), "Filtering must use the downloaded dataset");
            page.locator("#year").selectOption("2025");
            page.locator("#size").selectOption("1");
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/statistics-preview"));
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(java.nio.file.Path.of("target/statistics-preview/desktop.png")).setFullPage(true));
            page.setViewportSize(390, 844);
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(java.nio.file.Path.of("target/statistics-preview/mobile.png")).setFullPage(true));
            page.route("**/api/v1/statistics/invoices", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(502).setContentType("application/json").setBody("{}")));
            page.locator("#reload").click();
            page.waitForFunction("document.getElementById('status').className === 'error'");
            assertTrue(page.locator("#results").isHidden());
            assertFalse(page.locator("#reload").isDisabled());
            page.unroute("**/api/v1/statistics/invoices");
            page.locator("#reload").click();
            page.locator("#results").waitFor();
            assertEquals("2025", page.locator("#year").inputValue());
            assertEquals(3, calls.get());
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }


    @TestConfiguration
    static class FixtureConfiguration {
        @Bean @Primary
        InvoiceStatisticsService fixtureStatistics() {
            return new InvoiceStatisticsService() {
                @Override public Statistics load() throws IOException {
                    return summarize(new PcAxisParser().parse(InvoiceStatisticsTests.fixture()), Instant.EPOCH);
                }
            };
        }
    }

    @Test
    void servesRealHttpJsonAndLocalFrontendAssets() throws Exception {
        var client = HttpClient.newHttpClient();
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/statistics/invoices")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
        var json = JsonMapper.builder().build().readTree(response.body());
        assertEquals(20, json.path("observations").size());
        assertEquals(5, json.path("years").size());
        for (String path : new String[]{"/", "/js/statistics.js", "/css/statistics.css", "/vendor/chart.umd.min.js"}) {
            var asset = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, asset.statusCode(), path);
            assertFalse(asset.body().isBlank(), path);
        }
    }
}
