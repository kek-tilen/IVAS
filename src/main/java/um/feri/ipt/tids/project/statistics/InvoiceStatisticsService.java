package um.feri.ipt.tids.project.statistics;

import org.springframework.stereotype.Service;
import um.feri.ipt.tids.project.parser.pcaxis.PcAxisParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class InvoiceStatisticsService {
    public static final String SOURCE_URL = "https://pxweb.stat.si/SiStatData/Resources/PX/Databases/Data/2966321S.PX";
    private static final String YEAR = "LETO";
    private static final String STRUCTURE = "STRUKTURA RAČUNA, POSLANEGA V PREJŠNJEM LETU";
    private static final String SIZE = "VELIKOSTNI RAZRED PODJETJA";
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Statistics load() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(SOURCE_URL)).timeout(Duration.ofSeconds(25)).GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException("SURS HTTP " + response.statusCode());
        return summarize(new PcAxisParser().parse(response.body()), Instant.now());
    }

    Statistics summarize(PcAxisParser.Table table, Instant fetchedAt) {
        if (!table.matrix().equals("2966321S") || table.dimensions().size() != 3
                || !table.dimensions().containsAll(List.of(YEAR, STRUCTURE, SIZE))) {
            throw new IllegalArgumentException("Unexpected SURS table");
        }
        var years = table.codes().get(YEAR).stream().map(Integer::valueOf).sorted().toList();
        List<SizeClass> sizes = new ArrayList<>();
        for (int index = 0; index < table.codes().get(SIZE).size(); index++) {
            sizes.add(new SizeClass(table.codes().get(SIZE).get(index), table.labels().get(SIZE).get(index)));
        }
        List<Observation> observations = new ArrayList<>();
        for (SizeClass size : sizes) {
            for (int year : years) {
                Double total = count(table, year, size.code(), "STAT001");
                Double structured = count(table, year, size.code(), "STAT00847");
                Double share = percentage(structured, total);
                Double previousShare = years.contains(year - 1)
                        ? percentage(count(table, year - 1, size.code(), "STAT00847"),
                                     count(table, year - 1, size.code(), "STAT001")) : null;
                observations.add(new Observation(year, year - 1, size.code(), total, structured,
                        count(table, year, size.code(), "STAT00849"), count(table, year, size.code(), "STAT00851"),
                        share, share == null || previousShare == null ? null : share - previousShare));
            }
        }
        String updated = LocalDateTime.parse(table.lastUpdated(),
                DateTimeFormatter.ofPattern("yyyyMMdd HH:mm")).toString();
        return new Statistics(SOURCE_URL, updated, fetchedAt.toString(), years, sizes, observations);
    }

    private Double count(PcAxisParser.Table table, int year, String size, String structure) {
        Double value = table.value(Map.of(YEAR, Integer.toString(year), SIZE, size, STRUCTURE, structure));
        if (value != null && value < 0) throw new IllegalArgumentException("Negative enterprise count");
        return value;
    }

    static Double percentage(Double count, Double total) {
        return count == null || total == null || total <= 0 ? null : count / total * 100;
    }

    public record SizeClass(String code, String label) { }
    public record Observation(int year, int invoiceYear, String sizeCode, Double total, Double structured,
                              Double unstructured, Double paper, Double structuredShare, Double changePoints) { }
    public record Statistics(String sourceUrl, String sourceUpdatedAt, String fetchedAt, List<Integer> years,
                             List<SizeClass> sizeClasses, List<Observation> observations) { }
}
