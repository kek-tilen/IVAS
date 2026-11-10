package um.feri.ipt.tids.project.grpc;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import um.feri.ipt.tids.project.grpc.v1.*;
import um.feri.ipt.tids.project.verification.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static um.feri.ipt.tids.project.grpc.InvoiceMapper.*;

@Component
public class InvoiceVerificationRpc extends InvoiceVerificationServiceGrpc.InvoiceVerificationServiceImplBase {
    public static final String SCOPE = "Osnovni pregled vnesenih podatkov računa in izbirni ARES (CZ). "
            + "UBL, VIES, francoski register in davčna skladnost niso preverjeni. To ni odobritev plačila.";
    private final SupplierDirectory directory;
    private final Semaphore active = new Semaphore(8);
    public InvoiceVerificationRpc(SupplierDirectory directory) { this.directory = directory; }

    @Override public void getCapabilities(GetCapabilitiesRequest request, StreamObserver<GetCapabilitiesResponse> out) {
        call(out, ctx -> {
            out.onNext(GetCapabilitiesResponse.newBuilder().addAllLocalChecks(List.of("NET_TOTAL", "GROSS_TOTAL", "DUE_DATE"))
                    .addRegistryCountries("CZ").setMaxItems(30).setMaxRequestBytes(131072).setScope(SCOPE).build());
        });
    }
    @Override public void validateInvoice(ValidateInvoiceRequest request, StreamObserver<ValidateInvoiceResponse> out) {
        call(out, ctx -> {
            var invoice = fromProto(request.getInvoice());
            LocalInvoiceChecks.validate(invoice);
            out.onNext(ValidateInvoiceResponse.newBuilder().setLocal(local(invoice)).build());
        });
    }
    @Override public void lookupSupplier(LookupSupplierRequest request, StreamObserver<LookupSupplierResponse> out) {
        call(out, ctx -> {
            validateSupplier(request.getSupplier());
            var result = directory.lookup(request.getSupplier(), ctx::isCancelled);
            if (!ctx.isCancelled()) out.onNext(LookupSupplierResponse.newBuilder().setResult(result).build());
        });
    }
    @Override public void verifyInvoice(VerifyInvoiceRequest request, StreamObserver<VerificationEvent> out) {
        call(out, ctx -> {
            var invoice = fromProto(request.getInvoice());
            LocalInvoiceChecks.validate(invoice);
            if (request.getCheckRegistry()) validateSupplier(request.getInvoice().getSupplier());
            var id = UUID.randomUUID().toString();
            int[] sequence = {0};
            Consumer<VerificationEvent.Builder> emit = event -> {
                if (ctx.isCancelled()) throw new CancellationException();
                out.onNext(event.setRequestId(id).setSequence(++sequence[0]).setOccurredAt(now()).build());
            };
            emit.accept(VerificationEvent.newBuilder().setStarted(VerificationStarted.newBuilder().setInvoiceNumber(invoice.invoiceNumber())));
            var local = local(invoice);
            emit.accept(VerificationEvent.newBuilder().setLocal(local));
            var checks = new ArrayList<>(local.getChecksList());
            SupplierLookupResult supplier = null;
            if (request.getCheckRegistry()) {
                emit.accept(VerificationEvent.newBuilder().setRegistryStarted(RegistryStarted.newBuilder().setSource("ARES_WEB / HTML_DOM")));
                supplier = directory.lookup(request.getInvoice().getSupplier(), ctx::isCancelled);
                emit.accept(VerificationEvent.newBuilder().setSupplier(supplier));
                checks.add(compareSupplier(request.getInvoice().getSupplier(), supplier));
            } else checks.add(check("SUPPLIER_REGISTRY", CheckStatus.UNKNOWN,
                    "Registrsko preverjanje ni bilo izbrano.", "ARES_WEB / HTML_DOM"));
            checks.add(check("VAT_ID", CheckStatus.UNKNOWN, "VIES v tem obsegu ni implementiran.", "VIES"));
            checks.add(check("TAX_COMPLIANCE", CheckStatus.UNKNOWN, "Davčna skladnost ni preverjena.", "NOT_IMPLEMENTED"));
            var assessment = checks.stream().anyMatch(c -> c.getStatus() == CheckStatus.FAIL)
                    ? Assessment.REVIEW_REQUIRED : Assessment.INCOMPLETE_VERIFICATION;
            var report = VerificationReport.newBuilder().setRequestId(id).setInvoiceNumber(invoice.invoiceNumber())
                    .setCompletedAt(now()).setLocal(local).addAllChecks(checks).setAssessment(assessment).setScope(SCOPE)
                    .setRecommendedAction(assessment == Assessment.REVIEW_REQUIRED
                            ? "Preglejte ugotovljena neskladja in pred nadaljnjo obravnavo razjasnite manjkajoča preverjanja."
                            : "Dopolnite manjkajoča preverjanja dobavitelja in davčne skladnosti. Končno odločitev sprejme uporabnik.");
            checks.stream().filter(c -> c.getStatus() != CheckStatus.PASS).forEach(c -> report.addFindings(c.getMessage()));
            if (supplier != null) report.setSupplier(supplier);
            emit.accept(VerificationEvent.newBuilder().setReport(report));
        });
    }
    private static void validateSupplier(Supplier s) {
        if (!Set.of(Locale.getISOCountries()).contains(s.getCountry()))
            throw new IllegalArgumentException("Država dobavitelja mora biti veljavna oznaka ISO.");
        if (s.getName().length() > 200 || s.getVatId().length() > 40 || s.getRegistryId().length() > 40)
            throw new IllegalArgumentException("Podatki dobavitelja so predolgi.");
        if (s.getCountry().equals("CZ") && s.hasRegistryId() && !s.getRegistryId().isBlank())
            um.feri.ipt.tids.project.parser.ares.Ico.validate(s.getRegistryId());
    }
    private static CheckResult compareSupplier(Supplier expected, SupplierLookupResult actual) {
        var status = switch (actual.getStatus()) {
            case NOT_FOUND -> CheckStatus.FAIL;
            case FOUND, PARTIAL -> {
                var company = actual.getSupplier();
                if (!company.hasRegistryId() || company.getName().isBlank()) yield CheckStatus.UNKNOWN;
                yield company.getRegistryId().equals(expected.getRegistryId())
                        && normalize(company.getName()).equals(normalize(expected.getName())) ? CheckStatus.PASS : CheckStatus.FAIL;
            }
            default -> CheckStatus.UNKNOWN;
        };
        String message = switch (status) {
            case PASS -> "IČO in naziv se ujemata z registrom ARES (normalizacija velikosti črk in presledkov).";
            case FAIL -> "ARES ne potrjuje ujemanja navedenega IČO in naziva dobavitelja. Potreben je pregled.";
            default -> actual.getMessage();
        };
        return check("SUPPLIER_REGISTRY", status, message, actual.getSource());
    }
    private static String normalize(String s) { return s.replaceAll("[\\s\\p{Z}]+", " ").strip().toLowerCase(Locale.ROOT); }

    private <T> void call(StreamObserver<T> out, Consumer<Context> work) {
        Context ctx = Context.current();
        if (!active.tryAcquire()) {
            out.onError(Status.RESOURCE_EXHAUSTED.withDescription("Preveč sočasnih preverjanj. Poskusite pozneje.").asRuntimeException());
            return;
        }
        Thread worker = Thread.currentThread();
        Context.CancellationListener cancellation = ignored -> worker.interrupt();
        ctx.addListener(cancellation, Runnable::run);
        try {
            if (ctx.isCancelled()) return;
            work.accept(ctx);
            if (!ctx.isCancelled()) out.onCompleted();
        } catch (IllegalArgumentException e) {
            if (!ctx.isCancelled()) out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (StatusRuntimeException e) {
            if (!ctx.isCancelled()) out.onError(e);
        } catch (CancellationException e) {
            if (!ctx.isCancelled()) out.onError(Status.CANCELLED.withDescription("Preverjanje je prekinjeno.").asRuntimeException());
        } catch (RuntimeException e) {
            if (!ctx.isCancelled()) out.onError(Status.INTERNAL.withDescription("Preverjanja ni mogoče dokončati.").asRuntimeException());
        } finally {
            ctx.removeListener(cancellation);
            active.release();
            Thread.interrupted();
        }
    }
}
