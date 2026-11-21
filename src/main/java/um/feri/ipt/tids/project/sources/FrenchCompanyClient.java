package um.feri.ipt.tids.project.sources;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import static um.feri.ipt.tids.project.sources.SourceModels.*;
import static um.feri.ipt.tids.project.sources.SourceFailure.Kind.*;

@Component
public class FrenchCompanyClient {
    public static final String SOURCE = "FR_RECHERCHE_ENTREPRISES / REST_JSON";
    public static final URI ENDPOINT = URI.create("https://recherche-entreprises.api.gouv.fr/search");
    private final SourceHttpClient http;
    private final URI endpoint;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @Autowired public FrenchCompanyClient(SourceHttpClient http) { this(http, ENDPOINT, Clock.systemUTC()); }
    FrenchCompanyClient(SourceHttpClient http, URI endpoint, Clock clock) { this.http=http; this.endpoint=endpoint; this.clock=clock; }
    public FranceResult lookup(String input) {
        if (input == null || !input.matches("(?:[0-9]{9}|[0-9]{14})"))
            throw new IllegalArgumentException("Vnesite izrecni SIREN (9 števk) ali SIRET (14 števk), brez presledkov.");
        URI url = URI.create(endpoint + "?q=" + input + "&minimal=true&include=siege%2Cmatching_etablissements&per_page=1");
        try {
            var response = http.send(HttpRequest.newBuilder(url).header("Accept", "application/json").GET());
            if (response.statusCode() != 200) throw SourceFailure.http(response.statusCode(),response.headers().firstValue("Retry-After").orElse(null));
            return parse(response.body(),input,new SourceInfo(SOURCE,url.toString(),clock.instant()));
        } catch (SourceFailure error) { throw error.at(SOURCE,url,clock.instant()); }
    }
    private FranceResult parse(byte[] body, String id, SourceInfo info) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject() || !root.path("results").isArray()
                    || !root.path("total_results").isIntegralNumber() || !root.path("total_results").canConvertToLong()) throw invalid();
            var results = root.path("results"); long total = root.path("total_results").asLong();
            if (total == 0 && results.isEmpty()) return new FranceResult(RegistryStatus.NOT_FOUND,id,null,
                    "V javno dostopnem delu vira ni zapisa za ta identifikator. To ne dokazuje neobstoja podjetja.",info);
            if (total != 1 || results.size() != 1 || !results.get(0).isObject()) throw invalid();
            var company = results.get(0);
            String siren = text(company,"siren",9);
            if (siren == null || !siren.matches("[0-9]{9}") || !siren.equals(id.substring(0,9))) throw invalid();
            String name = text(company,"nom_complet",500);
            if (name == null) name=text(company,"nom_raison_sociale",500);
            String state = text(company,"etat_administratif",20);
            JsonNode site = company.path("siege");
            if (!site.isMissingNode() && !site.isNull() && !site.isObject()) throw invalid();
            if (id.length() == 14 && !id.equals(text(site,"siret",14))) {
                site=null;
                var matches = company.path("matching_etablissements");
                if (!matches.isArray()) throw invalid();
                for (var candidate : matches) {
                    if (!candidate.isObject()) throw invalid();
                    if (id.equals(text(candidate,"siret",14))) {
                        if (site != null) throw invalid();
                        site=candidate;
                    }
                }
                if (site == null) throw invalid();
            }
            String siret = text(site,"siret",14), siteState=text(site,"etat_administratif",20);
            if (siret != null && (!siret.matches("[0-9]{14}") || !siret.startsWith(siren))) throw invalid();
            boolean complete = name != null && ("A".equals(state) || "C".equals(state));
            if (id.length() == 14) complete &= "A".equals(siteState) || "F".equals(siteState);
            return new FranceResult(complete ? RegistryStatus.FOUND : RegistryStatus.PARTIAL,id,
                    new FrenchCompany(siren,siret,name,state,siteState),complete
                        ? "Javni registrski zapis s točnim identifikatorjem je pridobljen."
                        : "Identifikator se ujema, vendar vir ni vrnil vseh podatkov za celoten pregled.",info);
        } catch (SourceFailure error) { throw error; }
        catch (RuntimeException error) { throw invalid(); }
    }
    private static String text(JsonNode object, String field, int limit) {
        if (object == null) return null;
        var value=object.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString() || value.asString().length() > limit) throw invalid();
        String text=value.asString().strip(); return text.isEmpty() ? null : text;
    }
    private static SourceFailure invalid() { return new SourceFailure(INVALID_RESPONSE); }
}
