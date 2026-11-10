package um.feri.ipt.tids.project.grpc;

import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import um.feri.ipt.tids.project.grpc.v1.*;
import um.feri.ipt.tids.project.verification.InvoiceData;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Direct gRPC client: no Spring, web gateway, Azure or external call unless supplier/--ares is selected. */
public final class VerificationCli {
    private VerificationCli() { }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException(
                "Usage: capabilities | validate <invoice.json> | supplier <IČO> [name] | verify <invoice.json> [--ares]");
        int port = Integer.parseInt(System.getProperty("ivas.cli.port", "50051"));
        var channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().disableRetry()
                .maxInboundMessageSize(262144).build();
        try {
            var client = InvoiceVerificationServiceGrpc.newBlockingStub(channel).withDeadlineAfter(65, TimeUnit.SECONDS);
            switch (args[0]) {
                case "capabilities" -> System.out.println(VerificationController.json(client.getCapabilities(GetCapabilitiesRequest.getDefaultInstance())));
                case "supplier" -> {
                    if (args.length < 2) throw new IllegalArgumentException("supplier requires an eight-digit IČO");
                    System.out.println(VerificationController.json(client.lookupSupplier(LookupSupplierRequest.newBuilder()
                            .setSupplier(Supplier.newBuilder().setCountry("CZ").setRegistryId(args[1])
                                    .setName(args.length > 2 ? args[2] : "")).build())));
                }
                case "validate", "verify" -> {
                    if (args.length < 2) throw new IllegalArgumentException("An invoice JSON file is required");
                    byte[] bytes;
                    try (var input = Files.newInputStream(Path.of(args[1]))) { bytes = input.readNBytes(131073); }
                    if (bytes.length > 131072) throw new IllegalArgumentException("Invoice file exceeds 128 KiB");
                    var data = JsonMapper.builder().build().readValue(bytes, InvoiceData.class);
                    var invoice = InvoiceMapper.toProto(data);
                    if (args[0].equals("validate")) System.out.println(VerificationController.json(client.validateInvoice(
                            ValidateInvoiceRequest.newBuilder().setInvoice(invoice).build())));
                    else {
                        if (args.length > 3 || (args.length == 3 && !args[2].equals("--ares")))
                            throw new IllegalArgumentException("Only the explicit --ares option is supported");
                        var stream = client.verifyInvoice(VerifyInvoiceRequest.newBuilder().setInvoice(invoice)
                                .setCheckRegistry(args.length == 3).build());
                        while (stream.hasNext()) System.out.println(VerificationController.json(stream.next()));
                    }
                }
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            }
        } finally { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
}
