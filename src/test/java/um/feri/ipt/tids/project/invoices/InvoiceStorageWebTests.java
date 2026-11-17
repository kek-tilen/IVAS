package um.feri.ipt.tids.project.invoices;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoiceStorageWebTests {
    static final JsonMapper mapper = JsonMapper.builder().build();
    static final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @Autowired InvoiceRepository repository;
    final Set<UUID> created = new HashSet<>();
    String url(String path) { return "http://localhost:" + port + path; }
    HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create(url("/api/v1/invoices" + path))).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> send(String method, String path, String body) throws Exception {
        return client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }
    String payload(String number) {
        return mapper.writeValueAsString(Map.of("invoice", Map.ofEntries(
                Map.entry("invoiceNumber", number), Map.entry("supplierName", "Sintetični dobavitelj <img src=x onerror=alert(1)>"),
                Map.entry("supplierCountry", "CZ"), Map.entry("supplierRegistryId", "00006947"),
                Map.entry("issueDate", "2026-11-17"), Map.entry("currency", "EUR"),
                Map.entry("netTotal", "250.00"), Map.entry("taxTotal", "55.00"), Map.entry("grossTotal", "305.00"),
                Map.entry("items", List.of(Map.of("description", "Razvoj", "quantity", "2", "unitNetPrice", "100.00"),
                        Map.of("description", "Podpora", "quantity", "1", "unitNetPrice", "50.00")))), "note", "Opomba čšž"));
    }
    JsonNode create(String number) throws Exception {
        var response = send("POST", "", payload(number));
        assertEquals(201, response.statusCode(), response.body());
        var result = mapper.readTree(response.body()); created.add(UUID.fromString(result.path("id").asString()));
        assertEquals("/api/v1/invoices/" + result.path("id").asString(), response.headers().firstValue("Location").orElseThrow());
        return result;
    }
    @AfterEach void cleanup() { repository.deleteAllById(created); }

    @Test void storesExactAmountsIdentifiersOrderedLinesAndReturnsPersistentDetail() throws Exception {
        var saved = create("STORAGE-" + UUID.randomUUID());
        assertEquals("NEW", saved.path("handlingStatus").asString()); assertEquals(0, saved.path("version").asLong());
        var response = send("GET", "/" + saved.path("id").asString(), null);
        assertEquals(200, response.statusCode()); assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var actual = mapper.readTree(response.body());
        assertEquals("00006947", actual.path("invoice").path("supplierRegistryId").asString());
        assertEquals(0, new BigDecimal("305.00").compareTo(actual.path("invoice").path("grossTotal").decimalValue()));
        assertEquals("Razvoj", actual.path("invoice").path("items").get(0).path("description").asString());
        assertEquals("Podpora", actual.path("invoice").path("items").get(1).path("description").asString());
        assertEquals("Opomba čšž", actual.path("note").asString());
        assertFalse(actual.has("assessment"), "Saving must not claim that verification was performed");
    }
    @Test void pagesInvoicesAndRejectsInvalidPageArguments() throws Exception {
        for (int i=0; i<3; i++) create("PAGE-" + i);
        var result = mapper.readTree(send("GET", "?size=2", null).body());
        assertEquals(2, result.path("items").size()); assertTrue(result.path("totalElements").asLong() >= 3);
        var second = mapper.readTree(send("GET", "?page=1&size=2", null).body());
        var firstIds = new HashSet<String>(); result.path("items").forEach(item -> firstIds.add(item.path("id").asString()));
        second.path("items").forEach(item -> assertFalse(firstIds.contains(item.path("id").asString())));
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=x")) assertEquals(400, send("GET", query, null).statusCode());
    }
    @Test void updatesOnlyWorkflowMetadataAndRejectsStaleOrInvalidUpdates() throws Exception {
        var saved = create("METADATA"); String path = "/" + saved.path("id").asString();
        var baseline = mapper.readTree(send("GET",path,null).body()).path("invoice");
        var updated = send("PATCH", path, "{\"version\":0,\"note\":\"Pregledano čšž\",\"handlingStatus\":\"RESOLVED\"}");
        assertEquals(200, updated.statusCode(), updated.body()); var body = mapper.readTree(updated.body());
        assertEquals(1, body.path("version").asLong()); assertEquals("RESOLVED", body.path("handlingStatus").asString());
        assertEquals(409, send("PATCH", path, "{\"version\":0,\"note\":\"stale\",\"handlingStatus\":\"NEW\"}").statusCode());
        for (String invalid : List.of("{}", "{\"version\":1}", "{\"handlingStatus\":\"NEW\"}",
                "{\"version\":1,\"handlingStatus\":\"APPROVED\"}",
                mapper.writeValueAsString(Map.of("version",1,"handlingStatus","NEW","note","x".repeat(2001)))))
            assertEquals(400,send("PATCH",path,invalid).statusCode(),invalid.substring(0,Math.min(90,invalid.length())));
        var read = mapper.readTree(send("GET",path,null).body());
        assertEquals("Pregledano čšž",read.path("note").asString());
        assertEquals(baseline,read.path("invoice"));
    }
    @Test void partialMetadataUpdatesPreserveUnspecifiedValuesAndAnEmptyNoteClearsIt() throws Exception {
        var saved = create("PARTIAL"); String path = "/" + saved.path("id").asString();
        var stateOnly = mapper.readTree(send("PATCH",path,"{\"version\":0,\"handlingStatus\":\"IN_REVIEW\"}").body());
        assertEquals("Opomba čšž",stateOnly.path("note").asString());
        var noteOnly = mapper.readTree(send("PATCH",path,"{\"version\":1,\"note\":\"\"}").body());
        assertEquals("IN_REVIEW",noteOnly.path("handlingStatus").asString());
        assertEquals("",noteOnly.path("note").asString());
        assertEquals(2,noteOnly.path("version").asLong());
    }
    @Test void concurrentMetadataUpdatesCannotSilentlyOverwriteEachOther() throws Exception {
        var saved = create("CONCURRENT"); String path = "/" + saved.path("id").asString();
        var first = client.sendAsync(request("PATCH",path,"{\"version\":0,\"note\":\"first\",\"handlingStatus\":\"IN_REVIEW\"}"),HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request("PATCH",path,"{\"version\":0,\"note\":\"second\",\"handlingStatus\":\"RESOLVED\"}"),HttpResponse.BodyHandlers.ofString());
        assertEquals(List.of(200,409),List.of(first.get(10,TimeUnit.SECONDS).statusCode(),second.get(10,TimeUnit.SECONDS).statusCode()).stream().sorted().toList());
    }
    @Test void rejectsBadInputWithoutWritingPartialInvoicesAndLimitsBothBodyEncodings() throws Exception {
        long before = repository.count();
        for (String input : List.of("null","{}","{", payload("INVALID").replace("305.00","-1.00"),
                payload("INVALID").replace("250.00","250.001"), payload("INVALID").replace("\"CZ\"","\"XX\"")))
            assertEquals(400,send("POST","",input).statusCode(),input);
        assertEquals(before,repository.count());
        assertEquals(413,send("POST","","x".repeat(131073)).statusCode());
        var chunked = HttpRequest.newBuilder(URI.create(url("/api/v1/invoices"))).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[131073]))).build();
        assertEquals(413,client.send(chunked,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404,send("GET","/"+UUID.randomUUID(),null).statusCode());
        assertEquals(400,send("GET","/bad-id",null).statusCode());
    }
    @Test void servesInvoicePageAndAssets() throws Exception {
        for (String path : List.of("/invoices.html","/js/invoices.js","/css/invoices.css"))
            assertEquals(200,client.send(HttpRequest.newBuilder(URI.create(url(path))).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test @EnabledIfSystemProperty(named="invoices.browser.tests",matches="true")
    void browserCreatesReloadsEditsHandlesConflictsAndEscapesTextOnDesktopAndMobile() throws Exception {
        try (var playwright = com.microsoft.playwright.Playwright.create();
             var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setChannel("msedge"))) {
            var page = browser.newPage(new com.microsoft.playwright.Browser.NewPageOptions().setViewportSize(1440,1100));
            var errors = new ArrayList<String>(); page.onPageError(errors::add);
            page.navigate(url("/invoices.html")); page.waitForFunction("document.getElementById('invoice-status').textContent === ''");
            page.locator("#new-invoice").click(); page.locator("#sample-invoice").click();
            page.locator("#create-form [name=invoiceNumber]").fill("BROWSER-" + UUID.randomUUID());
            page.locator("#create-form [name=supplierName]").fill("<img src=x onerror=alert(1)> čšž");
            page.locator("#save-invoice").click(); page.locator("#detail-panel").waitFor();
            String id = page.url().split("#id=")[1]; created.add(UUID.fromString(id));
            assertEquals(0,page.locator("#detail-supplier img").count()); assertTrue(page.locator("#detail-supplier").innerText().contains("<img"));
            page.reload(); page.locator("#detail-panel").waitFor();
            page.locator("#metadata-form [name=note]").fill("Opomba ostane po ponovnem nalaganju čšž.");
            page.locator("#metadata-form [name=handlingStatus]").selectOption("IN_REVIEW"); page.locator("#save-metadata").click();
            page.waitForFunction("document.getElementById('invoice-status').textContent === 'Obravnava je shranjena.'");
            page.reload(); page.locator("#detail-panel").waitFor();
            assertEquals("IN_REVIEW",page.locator("#metadata-form [name=handlingStatus]").inputValue());
            assertTrue(page.locator("#metadata-form [name=note]").inputValue().contains("čšž"));
            var other = browser.newPage(); other.navigate(page.url()); other.locator("#detail-panel").waitFor();
            page.locator("#metadata-form [name=note]").fill("Sprememba v prvem zavihku"); page.locator("#save-metadata").click();
            page.waitForFunction("document.getElementById('invoice-status').textContent === 'Obravnava je shranjena.'");
            other.locator("#metadata-form [name=note]").fill("Neposlana opomba v drugem zavihku"); other.locator("#save-metadata").click();
            other.waitForFunction("document.getElementById('invoice-status').classList.contains('error')");
            assertEquals("Neposlana opomba v drugem zavihku",other.locator("#metadata-form [name=note]").inputValue()); other.close();
            Files.createDirectories(Path.of("target/invoices-preview"));
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of("target/invoices-preview/detail-desktop.png")).setFullPage(true));
            page.setViewportSize(390,844); assertTrue((Boolean)page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of("target/invoices-preview/detail-mobile.png")).setFullPage(true));
            page.locator("#detail-panel .back-link").click(); page.waitForFunction("document.getElementById('invoice-status').textContent === ''");
            assertTrue(page.locator("#invoice-rows").innerText().contains("<img")); assertEquals(0,page.locator("#invoice-rows img").count());
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of("target/invoices-preview/list-mobile.png")).setFullPage(true));
            page.locator("#new-invoice").click(); page.locator("#sample-invoice").click();
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of("target/invoices-preview/create-mobile.png")).setFullPage(true));
            assertTrue((Boolean)page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
            assertTrue(errors.isEmpty(),errors.toString());
        }
    }
}
