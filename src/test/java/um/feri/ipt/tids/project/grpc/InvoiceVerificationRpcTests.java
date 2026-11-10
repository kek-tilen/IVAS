package um.feri.ipt.tids.project.grpc;

import org.junit.jupiter.api.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import um.feri.ipt.tids.project.grpc.v1.*;
import um.feri.ipt.tids.project.verification.AresSupplierDirectory;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceVerificationRpcTests {
    VerificationFixtures.Directory directory;
    Server server;
    ManagedChannel channel;
    ExecutorService executor;
    InvoiceVerificationServiceGrpc.InvoiceVerificationServiceBlockingStub client;
    @BeforeEach void start() throws Exception {
        directory = new VerificationFixtures.Directory(); executor = Executors.newVirtualThreadPerTaskExecutor();
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).maxInboundMessageSize(131072)
                .executor(executor).addService(new InvoiceVerificationRpc(directory)).build().start();
        channel = NettyChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().disableRetry().build();
        client = InvoiceVerificationServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
    }
    @AfterEach void stop() throws Exception {
        directory.release.countDown(); channel.shutdownNow(); server.shutdownNow(); executor.shutdownNow();
        assertTrue(channel.awaitTermination(5,TimeUnit.SECONDS)); assertTrue(server.awaitTermination(5,TimeUnit.SECONDS));
    }
    Invoice invoice(String gross) { return InvoiceMapper.toProto(VerificationFixtures.data(gross)); }
    VerifyInvoiceRequest request(boolean registry) { return VerifyInvoiceRequest.newBuilder().setInvoice(invoice("305.00")).setCheckRegistry(registry).build(); }
    List<VerificationEvent> collect(VerifyInvoiceRequest request) {
        var result = new ArrayList<VerificationEvent>(); client.verifyInvoice(request).forEachRemaining(result::add); return result;
    }
    @Test void exposesExactlyFourMethodsAndCapabilitiesOverTcp() {
        assertEquals(4, InvoiceVerificationServiceGrpc.getServiceDescriptor().getMethods().size());
        var result = client.getCapabilities(GetCapabilitiesRequest.getDefaultInstance());
        assertEquals(List.of("CZ"),result.getRegistryCountriesList()); assertEquals(30,result.getMaxItems());
        assertFalse(result.getUblImportSupported()); assertEquals(0,directory.calls.get());
    }
    @Test void validatesDecimalsAndOptionalAbsenceWithoutAzureOrRegistry() {
        var result = client.validateInvoice(ValidateInvoiceRequest.newBuilder().setInvoice(invoice("300.00")).build()).getLocal();
        assertEquals("250.00",result.getCalculatedLineNetTotal()); assertEquals("305.00",result.getExpectedGrossTotal());
        assertEquals(CheckStatus.FAIL,result.getChecks(1).getStatus()); assertEquals(CheckStatus.UNKNOWN,result.getChecks(2).getStatus());
        assertFalse(invoice("305.00").hasDueDate()); assertEquals(0,directory.calls.get());
    }
    @Test void rejectsInvalidInputsBeforeCallingSupplierAndBoundsTransportSize() {
        var base = invoice("305.00");
        for (var broken : List.of(base.toBuilder().setGrossTotal("NaN").build(), base.toBuilder().setIssueDate("invalid").build(),
                base.toBuilder().setGrossTotal("305.001").build(), base.toBuilder().setSupplier(base.getSupplier().toBuilder().setCountry("ZZ")).build(),
                base.toBuilder().addAllItems(Collections.nCopies(31,base.getItems(0))).build())) {
            assertEquals(Status.Code.INVALID_ARGUMENT,assertThrows(StatusRuntimeException.class,() -> client.validateInvoice(
                    ValidateInvoiceRequest.newBuilder().setInvoice(broken).build())).getStatus().getCode());
        }
        assertEquals(Status.Code.RESOURCE_EXHAUSTED,assertThrows(StatusRuntimeException.class,() -> client.validateInvoice(
                ValidateInvoiceRequest.newBuilder().setInvoice(base.toBuilder().setInvoiceNumber("x".repeat(140000))).build())).getStatus().getCode());
        assertEquals(0,directory.calls.get());
    }
    @Test void looksUpSupplierAndDistinguishesSourceErrorsFromNotFound() {
        var request = LookupSupplierRequest.newBuilder().setSupplier(invoice("305.00").getSupplier()).build();
        assertEquals("00006947",client.lookupSupplier(request).getResult().getSupplier().getRegistryId());
        directory.status = RegistryStatus.SOURCE_ERROR;
        assertEquals(RegistryStatus.SOURCE_ERROR,client.lookupSupplier(request).getResult().getStatus());
        directory.status = RegistryStatus.NOT_FOUND;
        assertEquals(RegistryStatus.NOT_FOUND,client.lookupSupplier(request).getResult().getStatus());
        var adapter = new AresSupplierDirectory("");
        assertEquals(RegistryStatus.UNSUPPORTED,adapter.lookup(Supplier.newBuilder().setCountry("SI").build(),() -> false).getStatus());
        assertEquals(RegistryStatus.MISSING_IDENTIFIER,adapter.lookup(Supplier.newBuilder().setCountry("CZ").build(),() -> false).getStatus());
        assertThrows(IllegalArgumentException.class,() -> adapter.lookup(Supplier.newBuilder().setCountry("CZ").setRegistryId("6947").build(),() -> false));
    }
    @Test void streamsLocalResultsBeforeRegistryCompletesAndOrdersEvents() throws Exception {
        directory.release = new CountDownLatch(1);
        var events = new LinkedBlockingQueue<VerificationEvent>(); var finished = new CountDownLatch(1);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        InvoiceVerificationServiceGrpc.newStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS).verifyInvoice(request(true),new StreamObserver<>() {
            public void onNext(VerificationEvent e) { events.add(e); }
            public void onError(Throwable t) { error.set(t); finished.countDown(); }
            public void onCompleted() { finished.countDown(); }
        });
        assertTrue(directory.entered.await(3,TimeUnit.SECONDS));
        var first = events.poll(2,TimeUnit.SECONDS); var local = events.poll(2,TimeUnit.SECONDS);
        assertNotNull(first); assertTrue(first.hasStarted()); assertNotNull(local); assertTrue(local.hasLocal());
        assertEquals(1,first.getSequence()); assertEquals(2,local.getSequence());
        assertEquals(first.getRequestId(),local.getRequestId()); assertEquals(1,finished.getCount(),"Stream must remain open while registry is blocked");
        directory.release.countDown(); assertTrue(finished.await(3,TimeUnit.SECONDS)); assertNull(error.get());
        var rest = new ArrayList<VerificationEvent>(); events.drainTo(rest);
        assertEquals(List.of(3,4,5),rest.stream().map(VerificationEvent::getSequence).toList());
        assertTrue(rest.getLast().hasReport()); var report = rest.getLast().getReport();
        assertEquals(CheckStatus.PASS,report.getChecks(3).getStatus()); assertEquals(Assessment.INCOMPLETE_VERIFICATION,report.getAssessment());
    }
    @Test void reportsMismatchAndPreservesLocalChecksWhenRegistryFails() {
        directory.name = "Different supplier";
        var mismatch = collect(request(true)).getLast().getReport();
        assertEquals(Assessment.REVIEW_REQUIRED,mismatch.getAssessment());
        directory.status = RegistryStatus.SOURCE_ERROR;
        var unavailable = collect(request(true)).getLast().getReport();
        assertEquals(Assessment.INCOMPLETE_VERIFICATION,unavailable.getAssessment());
        assertEquals(CheckStatus.PASS,unavailable.getLocal().getChecks(0).getStatus());
        assertEquals(CheckStatus.UNKNOWN,unavailable.getChecks(3).getStatus());
        directory.status = RegistryStatus.PARTIAL; directory.name = "";
        assertEquals(CheckStatus.UNKNOWN,collect(request(true)).getLast().getReport().getChecks(3).getStatus());
        directory.status = RegistryStatus.NOT_FOUND;
        assertEquals(Assessment.REVIEW_REQUIRED,collect(request(true)).getLast().getReport().getAssessment());
        int calls = directory.calls.get();
        var localOnly = collect(request(false));
        assertEquals(3,localOnly.size()); assertEquals(calls,directory.calls.get());
        assertFalse(localOnly.getLast().getReport().hasSupplier());
    }
    @Test void propagatesCancellationToPendingRegistryWork() throws Exception {
        directory.release = new CountDownLatch(1);
        var context = Context.current().withCancellation();
        var terminal = new CountDownLatch(1);
        context.run(() -> InvoiceVerificationServiceGrpc.newStub(channel).verifyInvoice(request(true),new StreamObserver<>() {
            public void onNext(VerificationEvent e) { assertFalse(e.hasReport()); }
            public void onError(Throwable t) { terminal.countDown(); }
            public void onCompleted() { terminal.countDown(); }
        }));
        assertTrue(directory.entered.await(3,TimeUnit.SECONDS)); context.cancel(null);
        assertTrue(terminal.await(3,TimeUnit.SECONDS)); assertTrue(directory.cancelled.await(3,TimeUnit.SECONDS));
    }
    @Test void boundsConcurrentOperationsAndReleasesCapacity() throws Exception {
        directory.release = new CountDownLatch(1); directory.entered = new CountDownLatch(8);
        var finished = new CountDownLatch(8);
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        var request = LookupSupplierRequest.newBuilder().setSupplier(invoice("305.00").getSupplier()).build();
        var async = InvoiceVerificationServiceGrpc.newStub(channel).withDeadlineAfter(8,TimeUnit.SECONDS);
        for (int i=0;i<8;i++) async.lookupSupplier(request,new StreamObserver<>() {
            public void onNext(LookupSupplierResponse response) { }
            public void onError(Throwable t) { errors.add(t); finished.countDown(); }
            public void onCompleted() { finished.countDown(); }
        });
        assertTrue(directory.entered.await(3,TimeUnit.SECONDS));
        assertEquals(Status.Code.RESOURCE_EXHAUSTED,assertThrows(StatusRuntimeException.class,
                () -> client.getCapabilities(GetCapabilitiesRequest.getDefaultInstance())).getStatus().getCode());
        directory.release.countDown(); assertTrue(finished.await(3,TimeUnit.SECONDS)); assertTrue(errors.isEmpty(),errors.toString());
        assertEquals(30,client.getCapabilities(GetCapabilitiesRequest.getDefaultInstance()).getMaxItems());
    }

    @Test void enforcesDeadlineAndDoesNotLeakInternalErrors() throws Exception {
        directory.release = new CountDownLatch(1);
        var request = LookupSupplierRequest.newBuilder().setSupplier(invoice("305.00").getSupplier()).build();
        client.getCapabilities(GetCapabilitiesRequest.getDefaultInstance()); // Establish TCP before an in-flight timeout.
        var timed = client.withDeadlineAfter(1,TimeUnit.SECONDS);
        var failure = CompletableFuture.supplyAsync(() -> assertThrows(StatusRuntimeException.class,() -> timed.lookupSupplier(request)));
        assertTrue(directory.entered.await(3,TimeUnit.SECONDS), "Deadline test must reach the registry adapter");
        assertEquals(Status.Code.DEADLINE_EXCEEDED,failure.get(3,TimeUnit.SECONDS).getStatus().getCode());
        assertTrue(directory.cancelled.await(3,TimeUnit.SECONDS));
        directory.reset(); directory.fail = true;
        var error = assertThrows(StatusRuntimeException.class,() -> client.lookupSupplier(request));
        assertEquals(Status.Code.INTERNAL,error.getStatus().getCode()); assertFalse(error.getMessage().contains("PRIVATE"));
    }
}
