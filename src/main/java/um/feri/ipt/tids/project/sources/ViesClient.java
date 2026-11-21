package um.feri.ipt.tids.project.sources;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.*;
import javax.xml.XMLConstants;
import javax.xml.datatype.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static um.feri.ipt.tids.project.sources.SourceModels.*;
import static um.feri.ipt.tids.project.sources.SourceFailure.Kind.*;

/** SOAP 1.1 checkVat, with namespace-aware parsing and no external XML entities. */
@Component
public class ViesClient {
    public static final String SOURCE = "VIES / SOAP_XML";
    public static final URI ENDPOINT = URI.create("https://ec.europa.eu/taxation_customs/vies/services/checkVatService");
    static final String SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String TYPES = "urn:ec.europa.eu:taxud:vies:services:checkVat:types";
    static final Set<String> COUNTRIES = Set.of("AT","BE","BG","CY","CZ","DE","DK","EE","EL","ES","FI","FR",
            "HR","HU","IE","IT","LT","LU","LV","MT","NL","PL","PT","RO","SE","SI","SK","XI");
    private final SourceHttpClient http;
    private final URI endpoint;
    private final Clock clock;
    @Autowired public ViesClient(SourceHttpClient http) { this(http,ENDPOINT,Clock.systemUTC()); }
    ViesClient(SourceHttpClient http, URI endpoint, Clock clock) { this.http=http; this.endpoint=endpoint; this.clock=clock; }
    public ViesResult check(String countryInput, String vatInput) {
        String country=countryInput == null ? "" : countryInput.strip().toUpperCase(Locale.ROOT);
        if (country.equals("GR")) country="EL";
        String vat=vatInput == null ? "" : vatInput.strip().toUpperCase(Locale.ROOT);
        if (!COUNTRIES.contains(country) || !vat.matches("[0-9A-Z+*.]{2,12}"))
            throw new IllegalArgumentException("Izberite državo VIES (za Grčijo EL ali GR, za Severno Irsko XI) in DDV številko brez državne oznake in presledkov (2–12 znakov).");
        // Inputs are restricted to the WSDL character set, which excludes XML markup.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\""+SOAP+"\" xmlns:v=\""+TYPES+"\"><soap:Body><v:checkVat>"
                + "<v:countryCode>"+country+"</v:countryCode><v:vatNumber>"+vat+"</v:vatNumber>"
                + "</v:checkVat></soap:Body></soap:Envelope>";
        try {
            var response=http.send(HttpRequest.newBuilder(endpoint).header("Content-Type","text/xml; charset=UTF-8")
                    .header("SOAPAction","\"\"").header("Accept","text/xml")
                    .POST(HttpRequest.BodyPublishers.ofString(xml,StandardCharsets.UTF_8)));
            if (response.statusCode() != 200 && response.statusCode() != 500)
                throw SourceFailure.http(response.statusCode(),response.headers().firstValue("Retry-After").orElse(null));
            return parse(response.body(),country,vat,response.statusCode());
        } catch (SourceFailure error) { throw error.at(SOURCE,endpoint,clock.instant()); }
    }
    private ViesResult parse(byte[] xml, String country, String vat, int httpStatus) {
        try {
            var factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(SAXParseException error) throws SAXException { throw error; }
                @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
            });
            Element envelope=builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
            require(envelope,SOAP,"Envelope");
            Element body=child(envelope,SOAP,"Body",true);
            var payloads=elements(body); if (payloads.size()!=1) throw invalid();
            Element response=payloads.getFirst();
            if (SOAP.equals(response.getNamespaceURI()) && "Fault".equals(response.getLocalName())) {
                String code=value(response,null,"faultstring",true,200);
                throw fault(code);
            }
            if (httpStatus!=200) throw new SourceFailure(UNAVAILABLE);
            require(response,TYPES,"checkVatResponse");
            String actualCountry=value(response,TYPES,"countryCode",true,2);
            String actualVat=value(response,TYPES,"vatNumber",true,12);
            if (!country.equals(actualCountry) || !vat.equals(actualVat)) throw invalid();
            String valid=value(response,TYPES,"valid",true,5);
            if (!Set.of("true","false","1","0").contains(valid)) throw invalid();
            var calendar=DatatypeFactory.newInstance().newXMLGregorianCalendar(value(response,TYPES,"requestDate",true,40));
            if (!calendar.isValid() || !DatatypeConstants.DATE.equals(calendar.getXMLSchemaType())) throw invalid();
            LocalDate date=LocalDate.of(calendar.getYear(),calendar.getMonth(),calendar.getDay());
            boolean isValid=valid.equals("true") || valid.equals("1");
            return new ViesResult(isValid ? VatStatus.VALID : VatStatus.INVALID,country,vat,date,
                    value(response,TYPES,"name",false,2000),value(response,TYPES,"address",false,4000),isValid
                        ? "VIES potrjuje veljavnost navedene DDV številke na datum preverjanja."
                        : "VIES na datum preverjanja ne potrjuje veljavnosti navedene DDV številke.",
                    new SourceInfo(SOURCE,endpoint.toString(),clock.instant()));
        } catch (SourceFailure error) { throw error; }
        catch (Exception error) { throw invalid(); }
    }
    private static SourceFailure fault(String code) {
        return switch (code) {
            case "INVALID_INPUT", "INVALID_REQUESTER_INFO" -> new SourceFailure(INVALID_INPUT).provider(code);
            case "GLOBAL_MAX_CONCURRENT_REQ", "GLOBAL_MAX_CONCURRENT_REQ_TIME", "MS_MAX_CONCURRENT_REQ", "MS_MAX_CONCURRENT_REQ_TIME", "SERVER_BUSY" -> new SourceFailure(RATE_LIMITED).provider(code);
            case "TIMEOUT" -> new SourceFailure(TIMEOUT).provider(code);
            case "SERVICE_UNAVAILABLE", "MS_UNAVAILABLE", "IP_BLOCKED", "VAT_BLOCKED" -> new SourceFailure(UNAVAILABLE).provider(code);
            default -> new SourceFailure(UNAVAILABLE);
        };
    }
    private static void require(Element element, String namespace, String name) {
        if (!Objects.equals(namespace,element.getNamespaceURI()) || !name.equals(element.getLocalName())) throw invalid();
    }
    private static List<Element> elements(Element parent) {
        var result=new ArrayList<Element>();
        for (Node node=parent.getFirstChild(); node!=null; node=node.getNextSibling()) if (node instanceof Element element) result.add(element);
        return result;
    }
    private static Element child(Element parent, String namespace, String name, boolean required) {
        Element found=null;
        for (var element : elements(parent)) if (Objects.equals(namespace,element.getNamespaceURI()) && name.equals(element.getLocalName())) {
            if (found!=null) throw invalid(); found=element;
        }
        if (required && found==null) throw invalid(); return found;
    }
    private static String value(Element parent, String namespace, String name, boolean required, int limit) {
        var element=child(parent,namespace,name,required); if (element==null) return null;
        if (!elements(element).isEmpty() || element.getTextContent().length()>limit) throw invalid();
        String nil=element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,"nil");
        if (nil.equals("true") || nil.equals("1")) { if (required) throw invalid(); return null; }
        String text=element.getTextContent().strip();
        if (required && text.isEmpty()) throw invalid();
        return text.isEmpty() || (!required && text.equals("---")) ? null : text;
    }
    private static SourceFailure invalid() { return new SourceFailure(INVALID_RESPONSE); }
}
