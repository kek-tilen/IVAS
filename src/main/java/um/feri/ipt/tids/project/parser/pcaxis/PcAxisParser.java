package um.feri.ipt.tids.project.parser.pcaxis;

import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Pattern;

/** Reads the default language and dense DATA block of the SURS PC-Axis table. */
public class PcAxisParser {
    public Table parse(byte[] bytes) {
        String header = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        var codepage = Pattern.compile("CODEPAGE\\s*=\\s*\"([^\"]+)\"").matcher(header);
        if (!codepage.find()) throw new IllegalArgumentException("PCAXIS CODEPAGE is missing");
        String source = new String(bytes, Charset.forName(codepage.group(1)));
        Map<String, String> fields = readFields(source);
        List<String> dimensions = new ArrayList<>(strings(required(fields, "STUB")));
        dimensions.addAll(strings(required(fields, "HEADING")));
        if (new HashSet<>(dimensions).size() != dimensions.size()) {
            throw new IllegalArgumentException("Duplicate PCAXIS dimensions");
        }
        Map<String, List<String>> codes = new LinkedHashMap<>();
        Map<String, List<String>> labels = new LinkedHashMap<>();
        int expectedCount = 1;
        for (String dimension : dimensions) {
            var dimensionCodes = strings(required(fields, "CODES(\"" + dimension + "\")"));
            var dimensionLabels = strings(required(fields, "VALUES(\"" + dimension + "\")"));
            if (dimensionCodes.isEmpty() || dimensionCodes.size() != dimensionLabels.size()
                    || new HashSet<>(dimensionCodes).size() != dimensionCodes.size()) {
                throw new IllegalArgumentException("Invalid PCAXIS dimension: " + dimension);
            }
            codes.put(dimension, dimensionCodes);
            labels.put(dimension, dimensionLabels);
            expectedCount = Math.multiplyExact(expectedCount, dimensionCodes.size());
        }
        String data = required(fields, "DATA");
        List<Double> values = new ArrayList<>();
        var tokens = Pattern.compile("\"(?:[^\"]|\"\")*\"|[^\\s]+").matcher(data);
        while (tokens.find()) {
            String token = tokens.group();
            // Quoted SURS symbols (e.g. "N", "z", "-") represent unavailable observations.
            Double value = token.startsWith("\"") ? null : Double.valueOf(token);
            if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Invalid value");
            values.add(value);
        }
        if (values.size() != expectedCount) throw new IllegalArgumentException("PCAXIS DATA size mismatch");
        return new Table(singleString(fields, "MATRIX"), singleString(fields, "LAST-UPDATED"),
                List.copyOf(dimensions), codes, labels, values);
    }

    private Map<String, String> readFields(String source) {
        Map<String, String> fields = new LinkedHashMap<>();
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < source.length() && source.charAt(index + 1) == '"') index++;
                else quoted = !quoted;
            } else if (character == ';' && !quoted) {
                String statement = source.substring(start, index).trim();
                int separator = statement.indexOf('=');
                if (separator < 1) throw new IllegalArgumentException("Invalid PCAXIS statement");
                fields.put(statement.substring(0, separator).trim(), statement.substring(separator + 1).trim());
                start = index + 1;
            }
        }
        if (quoted || !source.substring(start).isBlank()) throw new IllegalArgumentException("Incomplete PCAXIS");
        return fields;
    }

    private String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) throw new IllegalArgumentException("Missing PCAXIS field: " + key);
        return value;
    }

    private String singleString(Map<String, String> fields, String key) {
        List<String> values = strings(required(fields, key));
        if (values.size() != 1) throw new IllegalArgumentException("Invalid PCAXIS field: " + key);
        return values.getFirst();
    }

    private List<String> strings(String source) {
        List<String> values = new ArrayList<>();
        var matcher = Pattern.compile("\"((?:[^\"]|\"\")*)\"").matcher(source);
        while (matcher.find()) values.add(matcher.group(1).replace("\"\"", "\""));
        return List.copyOf(values);
    }

    public record Table(String matrix, String lastUpdated, List<String> dimensions,
                        Map<String, List<String>> codes, Map<String, List<String>> labels, List<Double> values) {
        public Double value(Map<String, String> selection) {
            int offset = 0;
            for (String dimension : dimensions) {
                List<String> dimensionCodes = codes.get(dimension);
                int position = dimensionCodes.indexOf(selection.get(dimension));
                if (position < 0) throw new IllegalArgumentException("Unknown selection for " + dimension);
                offset = offset * dimensionCodes.size() + position;
            }
            return values.get(offset);
        }
    }
}
