package um.feri.ipt.tids.project.advice;

import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class AzureOpenAiClientTests {
    private AzureOpenAiClient client(FakeAzure fake) {
        return new AzureOpenAiClient(HttpClient.newHttpClient(), fake.endpoint(), "fixture-key", "my-deployment",
                Duration.ofSeconds(5), 4096);
    }

    @Test void sendsDynamicPromptSchemaBudgetAndDeserializesTypedAnswer() {
        try (var fake = new FakeAzure()) {
            var client = client(fake);
            var service = new InvoiceAdviceService(client);
            var result = service.advise(InvoiceAdviceTests.invoice(true, "Kaj preveriti pri DEMO-001?", "305.00"));
            assertEquals(1, fake.calls.get());
            assertEquals("POST", fake.lastMethod);
            assertEquals("fixture-key", fake.lastKey);
            var payload = JsonMapper.builder().build().readTree(fake.lastBody);
            assertEquals("my-deployment", payload.path("model").asString());
            assertEquals("json_schema", payload.path("response_format").path("type").asString());
            assertTrue(payload.path("response_format").path("json_schema").path("strict").asBoolean());
            assertFalse(payload.path("response_format").path("json_schema").path("schema").path("additionalProperties").asBoolean());
            assertEquals(4096, payload.path("max_completion_tokens").asInt());
            assertFalse(payload.path("store").asBoolean());
            assertEquals("developer", payload.path("messages").get(0).path("role").asString());
            assertTrue(payload.path("messages").get(1).path("content").asString().contains("DEMO-001"));
            assertEquals("gpt-5-mini-fixture", result.metadata().model());
            assertEquals("my-deployment", result.metadata().deployment());
            assertEquals(1, result.advice().recommendedActions().size());
            assertEquals("Lokalne vsote se ujemajo; registri niso preverjeni.", result.advice().summary());
        }
    }

    @Test void noConsentAndConfigurationInspectionNeverInvokeAzure() {
        try (var fake = new FakeAzure()) {
            var client = client(fake);
            assertTrue(client.configured());
            assertThrows(AdviceException.class, () -> new InvoiceAdviceService(client)
                    .advise(InvoiceAdviceTests.invoice(false, "Vprašanje", "305.00")));
            assertEquals(0, fake.calls.get());
        }
        var off = new AzureOpenAiClient(false, "", "", "", 60, 4096);
        assertFalse(off.configured());
        assertEquals(503, assertThrows(AdviceException.class, () -> new InvoiceAdviceService(off)
                .advise(InvoiceAdviceTests.invoice(true, "Vprašanje", "305.00"))).status());
    }

    @Test void rejectsNonAzureEndpointsAndProjectUrlsWithoutTransmittingCredentials() {
        for (String endpoint : new String[]{"http://ivasai.openai.azure.com", "https://example.org",
                "https://ivasai.openai.azure.com@evil.example", "https://ivasai.services.ai.azure.com/api/projects/IVAS-LLM",
                "https://ivasai.openai.azure.com/?key=bad"}) {
            assertFalse(new AzureOpenAiClient(true, endpoint, "key", "deployment", 60, 4096).configured(), endpoint);
        }
        assertFalse(new AzureOpenAiClient(true, "https://ivasai.openai.azure.com/", "not-a-key-ž", "deployment", 60, 4096).configured());
        assertTrue(new AzureOpenAiClient(true, "https://ivasai.openai.azure.com/openai/v1/",
                "fixture-key", "deployment", 60, 4096).configured());
    }

    @Test void rejectsMalformedSchemaWrongTypesExtraFieldsAndTruncatedResponses() {
        try (var fake = new FakeAzure()) {
            var client = client(fake);
            for (String answer : new String[]{"not JSON", "null", "[]", "{}",
                    FakeAzure.ANSWER.replace("\"summary\":", "\"unexpected\":"),
                    FakeAzure.ANSWER.replace("\"observations\":[\"Podatki so ročno vneseni.\"]", "\"observations\":[12]"),
                    FakeAzure.ANSWER.replace("\"summary\":\"Lokalne vsote se ujemajo; registri niso preverjeni.\"", "\"summary\":\"\"")}) {
                assertEquals("AI_INVALID_RESPONSE", assertThrows(AdviceException.class,
                        () -> client.parseCompletion(FakeAzure.completion(answer, "stop"))).code());
            }
            assertThrows(AdviceException.class, () -> client.parseCompletion(FakeAzure.completion(FakeAzure.ANSWER, "length")));
            assertEquals("AI_REFUSED", assertThrows(AdviceException.class, () -> client.parseCompletion(
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"no\"}}]}")).code());
        }
    }

    @Test void mapsProviderErrorsWithoutLeaksOrAutomaticRetries() {
        try (var fake = new FakeAzure()) {
            var client = client(fake);
            for (int code : new int[]{401, 403, 429, 500, 302}) {
                fake.reset(); fake.status = code; fake.response = "secret provider payload fixture-key";
                var error = assertThrows(AdviceException.class, () -> client.complete("test"));
                assertEquals(code == 429 ? 429 : code == 401 || code == 403 ? 503 : 502, error.status());
                assertFalse(error.getMessage().contains("fixture-key"));
                assertFalse(error.getMessage().contains("secret provider"));
                assertEquals(1, fake.calls.get(), "No retries or fallback");
            }
            fake.reset(); fake.response = "x".repeat(65_537);
            assertEquals("AI_INVALID_RESPONSE", assertThrows(AdviceException.class, () -> client.complete("test")).code());
        }
    }

    @Test void rejectsDuplicatePropertiesAndEnforcesDeadlineAfterHeaders() {
        try (var fake = new FakeAzure()) {
            var client = client(fake);
            String duplicate = FakeAzure.ANSWER.replace("\"summary\":", "\"summary\":\"earlier\",\"summary\":");
            assertThrows(AdviceException.class, () -> client.parseCompletion(FakeAzure.completion(duplicate, "stop")));
            String extra = FakeAzure.ANSWER.replace("\"summary\":", "\"extra\":true,\"summary\":");
            assertThrows(AdviceException.class, () -> client.parseCompletion(FakeAzure.completion(extra, "stop")));
            fake.delayBodyMillis = 700;
            var bounded = new AzureOpenAiClient(HttpClient.newHttpClient(), fake.endpoint(), "key", "model",
                    Duration.ofMillis(150), 4096);
            long start = System.nanoTime();
            assertEquals("AI_TIMEOUT", assertThrows(AdviceException.class, () -> bounded.complete("test")).code());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 650);
            assertEquals(1, fake.calls.get());
        }
    }
    @Test void timesOutWithoutRepeatingProviderRequest() {
        try (var fake = new FakeAzure()) {
            fake.delayMillis = 500;
            var client = new AzureOpenAiClient(HttpClient.newHttpClient(), fake.endpoint(), "key", "model",
                    Duration.ofMillis(100), 4096);
            assertEquals("AI_TIMEOUT", assertThrows(AdviceException.class, () -> client.complete("test")).code());
            assertEquals(1, fake.calls.get());
        }
    }
}
