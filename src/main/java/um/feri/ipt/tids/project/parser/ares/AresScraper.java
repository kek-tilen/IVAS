package um.feri.ipt.tids.project.parser.ares;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import static um.feri.ipt.tids.project.parser.ares.AresResult.*;

/** A single isolated browser/context per lookup; safe to replace with another source in future IVAS work. */
public final class AresScraper {
    private final Clock clock;
    private final Duration timeout;
    private final boolean headed;
    private final String channel;

    public AresScraper() { this(Clock.systemUTC(), Duration.ofSeconds(30), false, null); }

    public AresScraper(Clock clock, Duration timeout, boolean headed, String channel) {
        this.clock = java.util.Objects.requireNonNull(clock);
        this.timeout = java.util.Objects.requireNonNull(timeout);
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Timeout must be at least one millisecond and at most five minutes.");
        }
        this.headed = headed;
        if (channel != null && !List.of("chrome", "msedge").contains(channel)) {
            throw new IllegalArgumentException("Browser channel must be chrome or msedge.");
        }
        this.channel = channel;
    }

    public AresResult scrape(String ico) { return scrape(ico, () -> false); }

    public AresResult scrape(String ico, java.util.function.BooleanSupplier cancelled) {
        Ico.validate(ico); // Before creating Playwright or launching a browser.
        String url = "https://ares.gov.cz/ekonomicke-subjekty?ico=" + ico;
        var time = clock.instant();
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(!headed).setChannel(channel));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions().setLocale("cs-CZ"))) {
            Page page = context.newPage();
            page.setDefaultTimeout(timeout.toMillis());
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            // Only the top-level document status is checked; no response body or ARES API is read.
            if (response != null && response.status() >= 400) {
                return create(ico, url, time, Status.ERROR, null,
                        List.of(new Problem("HTTP_ERROR", "ARES page returned HTTP " + response.status() + ".")));
            }
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            return new AresDomParser().awaitResult(page, ico, url, clock.instant(), timeout.toMillis(), cancelled);
        } catch (TimeoutError e) {
            return create(ico, url, clock.instant(), Status.ERROR, null,
                    List.of(new Problem("TIMEOUT", "Timed out waiting for the ARES page.")));
        } catch (RuntimeException e) {
            return create(ico, url, clock.instant(), Status.ERROR, null,
                    List.of(new Problem("BROWSER_ERROR", "Unable to load or read ARES: " + normalizeError(e))));
        }
    }

    private static String normalizeError(RuntimeException e) {
        String message = AresDomParser.normalize(e.getMessage());
        return message == null ? e.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }
}
