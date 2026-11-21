package um.feri.ipt.tids.project.sources;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeSources implements AutoCloseable {
    static final String FRANCE = """
        {"total_results":1,"results":[{"siren":"123456789","nom_complet":"Entreprise & <img src=x onerror=alert(1)> čšž",
        "etat_administratif":"A","siege":{"siret":"12345678900001","etat_administratif":"A"},
        "matching_etablissements":[{"siret":"12345678900002","etat_administratif":"F"}]}]}
        """;
    static String soap(String country, String vat, String valid) {
        return """
            <e:Envelope xmlns:e="http://schemas.xmlsoap.org/soap/envelope/"><e:Body>
            <r:checkVatResponse xmlns:r="urn:ec.europa.eu:taxud:vies:services:checkVat:types">
            <r:countryCode>%s</r:countryCode><r:vatNumber>%s</r:vatNumber><r:requestDate>2026-11-21+01:00</r:requestDate>
            <r:valid>%s</r:valid><r:name>Fixture &amp; &lt;img src=x&gt; čšž</r:name><r:address>Naslov\nMesto</r:address>
            </r:checkVatResponse></e:Body></e:Envelope>
            """.formatted(country,vat,valid);
    }
    static String fault(String code) {
        return "<e:Envelope xmlns:e=\"http://schemas.xmlsoap.org/soap/envelope/\"><e:Body><e:Fault>"
                + "<faultcode>e:Server</faultcode><faultstring>"+code+"</faultstring></e:Fault></e:Body></e:Envelope>";
    }
    final HttpServer server;
    final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger franceCalls=new AtomicInteger(), viesCalls=new AtomicInteger(), otherCalls=new AtomicInteger();
    volatile String franceBody, viesBody, franceQuery, soapRequest, soapAction, contentType;
    volatile int franceStatus, viesStatus;
    volatile boolean slowFrance;
    volatile CountDownLatch headers=new CountDownLatch(1), release=new CountDownLatch(0);
    FakeSources() {
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
            server.createContext("/france",exchange -> {
                franceCalls.incrementAndGet(); franceQuery=exchange.getRequestURI().getRawQuery();
                exchange.getResponseHeaders().set("Retry-After","7");
                exchange.getResponseHeaders().set("Location",uri("/other").toString());
                respond(exchange,franceStatus,franceBody,slowFrance);
            });
            server.createContext("/vies",exchange -> {
                viesCalls.incrementAndGet(); soapRequest=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                soapAction=exchange.getRequestHeaders().getFirst("SOAPAction"); contentType=exchange.getRequestHeaders().getFirst("Content-Type");
                respond(exchange,viesStatus,viesBody,false);
            });
            server.createContext("/other",exchange -> { otherCalls.incrementAndGet(); respond(exchange,200,"PRIVATE",false); });
            reset(); server.start();
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }
    URI uri(String path) { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+path); }
    void reset() {
        release.countDown(); franceBody=FRANCE; viesBody=soap("SI","100","true"); franceStatus=viesStatus=200;
        franceCalls.set(0); viesCalls.set(0); otherCalls.set(0); slowFrance=false;
        headers=new CountDownLatch(1); release=new CountDownLatch(0);
    }
    private void respond(HttpExchange exchange,int status,String body,boolean slow) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",exchange.getRequestURI().getPath().equals("/vies") ? "text/xml;charset=UTF-8" : "application/json;charset=UTF-8");
        try(exchange) {
            exchange.sendResponseHeaders(status,slow ? 0 : bytes.length); headers.countDown();
            if(slow) {
                exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
                try { release.await(10,TimeUnit.SECONDS); } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            exchange.getResponseBody().write(bytes);
        } catch(IOException disconnected) { /* Timeouts and cancelled browser requests are intentional fixtures. */ }
    }
    @Override public void close() { release.countDown(); server.stop(0); executor.close(); }
}
