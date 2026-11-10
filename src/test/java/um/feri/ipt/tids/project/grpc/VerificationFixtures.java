package um.feri.ipt.tids.project.grpc;

import um.feri.ipt.tids.project.grpc.v1.*;
import um.feri.ipt.tids.project.verification.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

final class VerificationFixtures {
    static InvoiceData data(String gross) {
        return new InvoiceData("DEMO-001", "Test supplier", "CZ", null, "00006947",
                LocalDate.of(2026,11,10), null, "EUR",
                List.of(new InvoiceData.Line("A", new BigDecimal("2"), new BigDecimal("100.00")),
                        new InvoiceData.Line("B", BigDecimal.ONE, new BigDecimal("50.00"))),
                new BigDecimal("250.00"), new BigDecimal("55.00"), new BigDecimal(gross));
    }
    static final class Directory implements SupplierDirectory {
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(0), cancelled = new CountDownLatch(1);
        volatile RegistryStatus status = RegistryStatus.FOUND;
        volatile String name = "Test supplier";
        volatile boolean fail = false;
        void reset() {
            release.countDown(); calls.set(0); entered = new CountDownLatch(1);
            release = new CountDownLatch(0); cancelled = new CountDownLatch(1);
            status = RegistryStatus.FOUND; name = "Test supplier"; fail = false;
        }
        @Override public SupplierLookupResult lookup(Supplier supplier, BooleanSupplier stopped) {
            calls.incrementAndGet(); entered.countDown();
            try {
                while (!release.await(20,TimeUnit.MILLISECONDS)) {
                    if (stopped.getAsBoolean()) throw new CancellationException();
                }
                if (stopped.getAsBoolean()) throw new CancellationException();
            } catch (InterruptedException | CancellationException e) {
                cancelled.countDown(); Thread.currentThread().interrupt(); throw new CancellationException();
            }
            if (fail) throw new IllegalStateException("PRIVATE fixture detail");
            var result = SupplierLookupResult.newBuilder().setStatus(status).setSource("ARES_WEB / HTML_DOM")
                    .setSourceUrl("https://ares.gov.cz/ekonomicke-subjekty?ico=" + supplier.getRegistryId())
                    .setFetchedAt(InvoiceMapper.now()).setMessage("Fixture registry result");
            if (status == RegistryStatus.FOUND || status == RegistryStatus.PARTIAL)
                result.setSupplier(Supplier.newBuilder().setName(name).setCountry("CZ").setRegistryId(supplier.getRegistryId()));
            return result.build();
        }
    }
}
