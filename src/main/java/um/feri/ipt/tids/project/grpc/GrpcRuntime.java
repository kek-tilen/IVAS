package um.feri.ipt.tids.project.grpc;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.net.InetSocketAddress;
import java.util.concurrent.*;

/** One loopback TCP server and a real generated-client channel in the existing Boot process. */
@Component
public class GrpcRuntime implements SmartLifecycle {
    private final InvoiceVerificationRpc service;
    private final int configuredPort;
    private Server server;
    private ManagedChannel channel;
    private ExecutorService executor;
    private volatile boolean running;
    public GrpcRuntime(InvoiceVerificationRpc service, @Value("${ivas.grpc.port:50051}") int port) {
        this.service = service;
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid gRPC port");
        this.configuredPort = port;
    }
    @Override public synchronized void start() {
        if (running) return;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", configuredPort))
                    .executor(executor).maxInboundMessageSize(131072).maxConnectionIdle(5, TimeUnit.MINUTES)
                    .addService(service).build().start();
            channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext()
                    .maxInboundMessageSize(262144).disableRetry().build();
            running = true;
        } catch (java.io.IOException | RuntimeException e) {
            if (server != null) server.shutdownNow();
            executor.shutdownNow();
            throw new IllegalStateException("Could not start local gRPC server", e);
        }
    }
    public ManagedChannel channel() {
        if (!running) throw Status.UNAVAILABLE.withDescription("gRPC strežnik ni zagnan.").asRuntimeException();
        return channel;
    }
    public int port() { return server.getPort(); }
    @Override public boolean isRunning() { return running; }
    @Override public synchronized void stop() {
        running = false;
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
        if (executor != null) executor.shutdownNow();
        try {
            if (channel != null) channel.awaitTermination(5, TimeUnit.SECONDS);
            if (server != null) server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
