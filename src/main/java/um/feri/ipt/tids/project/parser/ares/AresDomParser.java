package um.feri.ipt.tids.project.parser.ares;

import com.microsoft.playwright.Page;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static um.feri.ipt.tids.project.parser.ares.AresResult.*;

/** Reads visible DOM only. No response listeners, request clients or embedded application state. */
public final class AresDomParser {
    // Search-table headers verified against the rendered official ARES site; see docs/Naloga2_Spletni_Parser.md.
    private static final String SNAPSHOT = """
        () => {
          const visible = e => !!(e.getClientRects().length) && getComputedStyle(e).visibility !== 'hidden';
          const norm = s => (s || '').replace(/\u00a0/g, ' ').replace(/\\s+/g, ' ').trim();
          const labels = {'IČO':'ico','Obchodní název':'name','Obchodní jméno':'name','Obchodní firma':'name',
            'Název':'name','DIČ':'vatId','Sídlo':'registeredAddress','Adresa sídla':'registeredAddress',
            'Právní forma':'legalForm'};
          const key = s => labels[norm(s).replace(/:$/, '')];
          const records = [];
          let emptyResult = false;
          for (const table of document.querySelectorAll('table, mat-table, [role="table"]')) {
            if (!visible(table)) continue;
            const headers = [...table.querySelectorAll('th, mat-header-cell, [role="columnheader"]')].map(e => key(e.innerText));
            const root = table.closest('[data-pc-name="datatable"]');
            if (root && headers.includes('ico') && headers.includes('name')) {
              const emptyRow = table.querySelector('[data-pc-section="emptymessage"]');
              const counter = root.querySelector('[data-pc-name="paginator"] [data-pc-section="current"]');
              const loading = [...root.querySelectorAll('[data-pc-section="loadingoverlay"], [role="progressbar"], [aria-busy="true"]')].some(visible);
              const alert = [...document.querySelectorAll('[role="alert"]')].some(visible);
              emptyResult ||= !!(emptyRow && visible(emptyRow) && counter && visible(counter)
                && norm(counter.innerText) === 'Zobrazeno 0 až 0 z 0' && !loading && !alert);
            }
            for (const row of table.querySelectorAll('tr, mat-row, [role="row"]')) {
              if (!visible(row)) continue;
              const cells = [...row.querySelectorAll('td, mat-cell, [role="cell"]')];
              if (!cells.length) continue;
              const record = {};
              cells.forEach((c,i) => { if (headers[i] && visible(c)) record[headers[i]] = norm(c.innerText); });
              if (Object.values(record).some(Boolean)) records.push(record);
            }
          }
          const details = new Map();
          for (const label of document.querySelectorAll('dt, th, label, span, div')) {
            if (!visible(label)) continue;
            if (label.closest('thead')) continue;
            if (label.matches('span,div') && label.children.length) continue;
            const field = key(label.innerText);
            const value = label.nextElementSibling;
            if (field && value && visible(value) && !value.matches('input,select,textarea')) {
              const text = norm(value.innerText);
              const group = label.closest('dl, table, section, article, [role="group"]') || document.body;
              if (!details.has(group)) details.set(group, {});
              if (text) details.get(group)[field] = text;
            }
          }
          for (const detail of details.values()) if (detail.ico) records.push(detail);
          return { records, emptyResult, text: document.body.innerText };
        }
        """;

    public static String normalize(String value) {
        if (value == null) return null;
        String result = value.replaceAll("[\\s\\p{Z}]+", " ").trim();
        return result.isEmpty() ? null : result;
    }

    @SuppressWarnings("unchecked")
    public AresResult parse(Page page, String ico, String sourceUrl, Instant time) {
        Ico.validate(ico);
        Map<String, Object> snapshot = (Map<String, Object>) page.evaluate(SNAPSHOT);
        List<Map<String, String>> records = (List<Map<String, String>>) snapshot.get("records");
        List<Map<String, String>> matches = records.stream()
                .filter(r -> ico.equals(normalize(r.get("ico")))).toList();
        if (!matches.isEmpty()) {
            // Prefer the most complete record, but never combine unrelated rows or infer identifiers.
            Map<String, String> row = matches.stream().max(java.util.Comparator.comparingInt(
                    (Map<String, String> r) -> (normalize(r.get("name")) == null ? 0 : 100)
                            + (int) r.values().stream().filter(v -> normalize(v) != null).count())).orElseThrow();
            var company = new Company(normalize(row.get("ico")), normalize(row.get("name")),
                    normalize(row.get("vatId")), normalize(row.get("registeredAddress")), normalize(row.get("legalForm")));
            if (matches.stream().map(r -> normalize(r.get("name"))).filter(java.util.Objects::nonNull)
                    .distinct().count() > 1) {
                return create(ico, sourceUrl, time, Status.ERROR, null,
                        List.of(new Problem("AMBIGUOUS_RESULT", "Conflicting names for the requested IČO.")));
            }
            boolean complete = company.name() != null;
            return create(ico, sourceUrl, time, complete ? Status.SUCCESS : Status.PARTIAL, company,
                    complete ? List.of() : List.of(new Problem("MISSING_REQUIRED_FIELD", "Company name is missing.")));
        }
        String text = normalize((String) snapshot.get("text"));
        // Only explicit empty-result messages qualify; blank pages and errors do not.
        if (records.isEmpty() && (Boolean.TRUE.equals(snapshot.get("emptyResult"))
                || (text != null && (text.contains("Nebyly nalezeny žádné záznamy")
                    || text.contains("Nebyly nalezeny žádné ekonomické subjekty"))))) {
            return create(ico, sourceUrl, time, Status.NOT_FOUND, null,
                    List.of(new Problem("NOT_FOUND", "ARES explicitly reports no matching records.")));
        }
        return create(ico, sourceUrl, time, Status.ERROR, null,
                List.of(new Problem("UNRECOGNIZED_DOM", "No recognizable result for the requested IČO in the visible DOM.")));
    }

    public AresResult awaitResult(Page page, String ico, String url, Instant time, double timeout) {
        return awaitResult(page, ico, url, time, timeout, () -> false);
    }

    public AresResult awaitResult(Page page, String ico, String url, Instant time, double timeout,
                                  java.util.function.BooleanSupplier cancelled) {
        if (!Double.isFinite(timeout) || timeout <= 0) throw new IllegalArgumentException("Timeout must be positive and finite.");
        // Poll the same parser used for extraction; no fixed sleeps or network-idle dependency.
        var latest = new java.util.concurrent.atomic.AtomicReference<AresResult>();
        try {
            page.waitForCondition(() -> {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                AresResult result = parse(page, ico, url, time);
                latest.set(result);
                return result.status() == Status.SUCCESS || result.status() == Status.NOT_FOUND
                        || (result.status() == Status.ERROR
                            && !result.errors().getFirst().code().equals("UNRECOGNIZED_DOM"));
            }, new Page.WaitForConditionOptions().setTimeout(timeout));
        } catch (com.microsoft.playwright.TimeoutError e) {
            // Preserve an unrecognized DOM as a technical error, never NOT_FOUND.
            if (latest.get() == null) throw e;
        }
        return latest.get();
    }
}
