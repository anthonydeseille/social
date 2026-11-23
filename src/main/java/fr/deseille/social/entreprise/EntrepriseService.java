package fr.deseille.social.entreprise;

import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conventions collectives d'une entreprise, cherchée par son nom ou son SIRET dans l'annuaire public
 * des entreprises (API Recherche d'entreprises de l'État, gratuite, sans clé), pour le salarié qui ne
 * connaît pas son IDCC. Un SIRET donne les conventions de cet établissement ; un nom ou un SIREN,
 * celles de l'entreprise. L'IDCC 9999 (« sans convention collective ») est écarté. Chaque convention
 * dit si elle est intégrée à l'application (texte disponible).
 * <p>
 * La recherche n'est ni stockée ni journalisée : seul l'IDCC choisi rejoint ensuite le profil.
 */
@Service
@Transactional(readOnly = true)
public class EntrepriseService {

    private static final Logger log = LoggerFactory.getLogger(EntrepriseService.class);
    private static final Pattern SIRET = Pattern.compile("\\d{14}");
    private static final String SANS_CONVENTION = "9999";
    static final int RESULTATS = 5;

    private final RestClient client;
    private final TexteRepository texteRepository;
    private final JsonMapper json = JsonMapper.builder().build();

    public EntrepriseService(TexteRepository texteRepository,
                             @Value("${droits.entreprises.base-url:https://recherche-entreprises.api.gouv.fr}") String baseUrl) {
        this(texteRepository, RestClient.builder().baseUrl(baseUrl).requestFactory(fabrique()));
    }

    EntrepriseService(TexteRepository texteRepository, RestClient.Builder builder) {
        this.texteRepository = texteRepository;
        this.client = builder.build();
    }

    private static JdkClientHttpRequestFactory fabrique() {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        f.setReadTimeout(Duration.ofSeconds(10));
        return f;
    }

    /** Entreprises qui correspondent à la recherche, avec leurs conventions (au plus {@value #RESULTATS}). */
    public List<Entreprise> chercher(String recherche) {
        String q = recherche.replaceAll("[\\s.]", "").matches("\\d{9}|\\d{14}")
                ? recherche.replaceAll("[\\s.]", "") : recherche.trim();
        String corps;
        try {
            corps = client.get().uri(u -> u.path("/search").queryParam("q", q).queryParam("per_page", RESULTATS)
                    .queryParam("page", 1).build()).retrieve().body(String.class);
        } catch (RestClientException e) {
            log.warn("Annuaire des entreprises indisponible ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "L'annuaire des entreprises ne répond pas pour le moment. Réessayez plus tard.");
        }
        boolean parSiret = SIRET.matcher(q).matches();
        List<Entreprise> entreprises = new ArrayList<>();
        for (JsonNode r : json.readTree(corps).path("results")) {
            JsonNode etablissement = r.path("siege");
            if (parSiret) {
                for (JsonNode e : r.path("matching_etablissements")) {
                    if (q.equals(e.path("siret").asString())) {
                        etablissement = e;
                    }
                }
            }
            Set<String> idcc = new LinkedHashSet<>();
            (parSiret ? etablissement.path("liste_idcc") : r.path("complements").path("liste_idcc"))
                    .forEach(i -> idcc.add(i.asString()));
            idcc.remove(SANS_CONVENTION);
            entreprises.add(new Entreprise(r.path("nom_complet").asString(), r.path("siren").asString(),
                    parSiret ? q : null, etablissement.path("libelle_commune").asString(null),
                    idcc.stream().map(this::convention).toList()));
        }
        return entreprises;
    }

    private Convention convention(String idcc) {
        int numero = Integer.parseInt(idcc);
        List<Texte> textes = texteRepository.findByIdcc(numero);
        return new Convention(numero, textes.isEmpty() ? null : textes.getFirst().getTitre(), !textes.isEmpty());
    }

    /**
     * @param siret   l'établissement cherché par son SIRET, {@code null} pour une recherche par nom
     * @param commune commune du siège, ou de l'établissement cherché par SIRET
     */
    public record Entreprise(String nom, String siren, String siret, String commune, List<Convention> conventions) {
    }

    /** @param integree le texte de la convention est disponible dans l'application ({@code titre} sinon vide) */
    public record Convention(int idcc, String titre, boolean integree) {
    }
}
