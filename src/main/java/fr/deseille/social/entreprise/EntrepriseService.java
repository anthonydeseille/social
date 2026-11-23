package fr.deseille.social.entreprise;

import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
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
 * Pas de transaction autour de l'appel à l'annuaire (jusqu'à 15 s) : la connexion à la base n'est
 * prise que pour lire les titres des conventions, en une requête. Une réponse illisible de l'annuaire
 * donne un 503, comme une panne ; un IDCC illisible est ignoré.
 * <p>
 * La recherche n'est ni stockée ni journalisée : seul l'IDCC choisi rejoint ensuite le profil.
 */
@Service
public class EntrepriseService {

    private static final Logger log = LoggerFactory.getLogger(EntrepriseService.class);
    private static final Pattern SIRET = Pattern.compile("\\d{14}");
    private static final String SANS_CONVENTION = "9999";
    static final int RESULTATS = 5;

    private final RestClient client;
    private final TexteRepository texteRepository;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
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
        JsonNode resultats;
        try {
            resultats = json.readTree(corps == null ? "" : corps).path("results");
        } catch (RuntimeException e) {
            log.warn("Réponse illisible de l'annuaire des entreprises ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "L'annuaire des entreprises ne répond pas pour le moment. Réessayez plus tard.");
        }
        boolean parSiret = SIRET.matcher(q).matches();
        record Lu(JsonNode entreprise, JsonNode etablissement, List<Integer> idcc) {
        }
        List<Lu> lus = new ArrayList<>();
        for (JsonNode r : resultats) {
            JsonNode etablissement = null;
            if (parSiret) {
                for (JsonNode e : r.path("matching_etablissements")) {
                    if (q.equals(e.path("siret").asString())) {
                        etablissement = e;
                    }
                }
            }
            // Établissement cherché introuvable dans la réponse : conventions de l'entreprise, pas du siège
            JsonNode listeIdcc = etablissement != null ? etablissement.path("liste_idcc")
                    : r.path("complements").path("liste_idcc");
            Set<Integer> idcc = new LinkedHashSet<>();
            for (JsonNode i : listeIdcc) {
                String valeur = i.asString("").trim();
                if (valeur.matches("\\d{1,4}") && !valeur.equals(SANS_CONVENTION)) {
                    idcc.add(Integer.parseInt(valeur));
                }
            }
            lus.add(new Lu(r, etablissement, List.copyOf(idcc)));
        }
        Map<Integer, String> titres = titres(lus.stream().flatMap(l -> l.idcc().stream()).collect(Collectors.toSet()));
        return lus.stream().map(l -> new Entreprise(l.entreprise().path("nom_complet").asString(),
                        l.entreprise().path("siren").asString(), l.etablissement() != null ? q : null,
                        (l.etablissement() != null ? l.etablissement() : l.entreprise().path("siege"))
                                .path("libelle_commune").asString(null),
                        l.idcc().stream().map(i -> new Convention(i, titres.get(i), titres.containsKey(i))).toList()))
                .toList();
    }

    /** Titre de chaque convention intégrée, en une requête. */
    private Map<Integer, String> titres(Set<Integer> idcc) {
        if (idcc.isEmpty()) {
            return Map.of();
        }
        return texteRepository.findByIdccIn(idcc).stream()
                .collect(Collectors.toMap(Texte::getIdcc, Texte::getTitre, (a, b) -> a));
    }

    /**
     * @param siret   l'établissement cherché par son SIRET ; {@code null} pour une recherche par nom ou
     *                SIREN, ou si l'annuaire ne renvoie pas cet établissement (conventions de l'entreprise)
     * @param commune commune du siège, ou de l'établissement cherché par SIRET
     */
    public record Entreprise(String nom, String siren, String siret, String commune, List<Convention> conventions) {
    }

    /** @param integree le texte de la convention est disponible dans l'application ({@code titre} sinon vide) */
    public record Convention(int idcc, String titre, boolean integree) {
    }
}
