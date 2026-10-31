package um.feri.ipt.tids.project.statistics;

import org.junit.jupiter.api.Test;
import um.feri.ipt.tids.project.parser.pcaxis.PcAxisParser;
import java.nio.charset.Charset;
import java.time.Instant;
import java.io.IOException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceStatisticsTests {

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "statistics.live.tests", matches = "true")
    void downloadsAndProcessesOfficialPcAxisFile() throws Exception {
        var statistics = new InvoiceStatisticsService().load();
        assertFalse(statistics.observations().isEmpty());
        assertTrue(statistics.years().contains(2025));
        assertTrue(statistics.observations().stream().anyMatch(row -> row.structuredShare() != null));
    }

    private static final Charset ENCODING = Charset.forName("windows-1250");

    static byte[] fixture() throws IOException {
        try (var input = InvoiceStatisticsTests.class.getResourceAsStream("/pcaxis/2966321S.PX")) {
            return input.readAllBytes();
        }
    }

    private PcAxisParser.Table parseChanged(String original, String replacement) throws IOException {
        String source = new String(fixture(), ENCODING).replace(original, replacement);
        return new PcAxisParser().parse(source.getBytes(ENCODING));
    }

    @Test
    void readsOfficialEncodingDefaultLanguageAndDenseDimensionOrder() throws Exception {
        var table = new PcAxisParser().parse(fixture());
        assertEquals(180, table.values().size());
        assertEquals("STRUKTURA RAČUNA, POSLANEGA V PREJŠNJEM LETU", table.dimensions().get(1));
        assertEquals(9033, table.value(Map.of("LETO", "2025", table.dimensions().get(1), "STAT001",
                "VELIKOSTNI RAZRED PODJETJA", "1")));
        assertEquals(219, table.value(Map.of("LETO", "2025", table.dimensions().get(1), "STAT00847",
                "VELIKOSTNI RAZRED PODJETJA", "4")));
    }

    @Test
    void handlesQuotedSemicolonsEscapedQuotesAndMissingSymbols() throws Exception {
        var table = parseChanged("7712 6356", "\"N\" 6356");
        assertNull(table.values().getFirst());
        assertEquals(6356, table.values().get(1));
        var quoted = parseChanged("Število podjetij\";", "Število; \"\"podjetij\"\"\";");
        assertEquals(180, quoted.values().size());
    }

    @Test
    void rejectsIncompleteAndMismatchedData() {
        assertThrows(IllegalArgumentException.class, () -> parseChanged("7712 6356", "6356"));
        assertThrows(IllegalArgumentException.class, () -> parseChanged("7612 6208 1178 226;", "7612 6208 1178 226"));
        assertThrows(IllegalArgumentException.class, () -> parseChanged("7712 6356", "NaN 6356"));
        assertThrows(IllegalArgumentException.class, () -> parseChanged("CODEPAGE=", "REMOVED="));
        assertThrows(IllegalArgumentException.class, () -> parseChanged("MATRIX=\"2966321S\"", "MATRIX=2966321S"));
    }

    @Test
    void calculatesSharesAndYearOnYearPercentagePointsAgainstOfficialCounts() throws Exception {
        var result = new InvoiceStatisticsService().summarize(new PcAxisParser().parse(fixture()), Instant.EPOCH);
        assertEquals(20, result.observations().size());
        var latest = result.observations().stream().filter(row -> row.year() == 2025 && row.sizeCode().equals("1")).findFirst().orElseThrow();
        assertEquals(2024, latest.invoiceYear());
        assertEquals(9033, latest.total());
        assertEquals(5704, latest.structured());
        assertEquals(7613, latest.unstructured());
        assertEquals(7612, latest.paper());
        assertEquals(5704.0 / 9033 * 100, latest.structuredShare(), 0.000001);
        assertEquals(5704.0 / 9033 * 100 - 5584.0 / 8900 * 100, latest.changePoints(), 0.000001);
        assertNull(result.observations().getFirst().changePoints());
        assertEquals("2025-12-04T10:30", result.sourceUpdatedAt());
        assertEquals(Instant.EPOCH.toString(), result.fetchedAt());
    }

    @Test
    void doesNotConvertMissingOrZeroDenominatorsToZeroPercent() throws Exception {
        assertNull(InvoiceStatisticsService.percentage(10.0, 0.0));
        assertNull(InvoiceStatisticsService.percentage(null, 100.0));
        assertNull(InvoiceStatisticsService.percentage(10.0, null));
        assertEquals(0, InvoiceStatisticsService.percentage(0.0, 100.0));
        var result = new InvoiceStatisticsService().summarize(parseChanged("5704 4498", "\"N\" 4498"), Instant.EPOCH);
        var latest = result.observations().stream().filter(row -> row.year() == 2025 && row.sizeCode().equals("1")).findFirst().orElseThrow();
        assertNull(latest.structuredShare());
        assertNull(latest.changePoints());
    }

    @Test
    void returnsReadableGatewayErrorOnDownloadOrParsingFailure() {
        var unavailable = new InvoiceStatisticsService() {
            @Override public Statistics load() throws IOException { throw new IOException("offline"); }
        };
        assertEquals(502, new InvoiceStatisticsController(unavailable).statistics().getStatusCode().value());
        var changedSource = new InvoiceStatisticsService() {
            @Override public Statistics load() { throw new IllegalArgumentException("changed format"); }
        };
        assertEquals(502, new InvoiceStatisticsController(changedSource).statistics().getStatusCode().value());
    }
}
