package fr.deseille.social.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lecteur Spring Batch qui parcourt en profondeur l'arbre unist d'une source (Code ou
 * convention) en <strong>streaming</strong> Jackson : seul le {@code data} du nœud courant
 * est matérialisé en mémoire, jamais l'arbre entier (indispensable pour le fichier Code de
 * 56 Mo). Émet un {@link NoeudCorpus} par appel à {@link #read()}, dans l'ordre du document,
 * chaque nœud portant l'identifiant de son parent (résolu via une pile de sections).
 * <p>
 * Le streaming impose que {@code type} et {@code data} précèdent {@code children} dans chaque
 * nœud ; un document qui ne respecte pas cet ordre, ou qui est tronqué, fait échouer la lecture
 * plutôt que de produire des nœuds incomplets (et de fausses disparitions).
 * <p>
 * Certaines sections ajoutées par SocialGouv n'ont pas d'identifiant DILA (regroupements
 * « Textes Attachés », « Textes Salaires » des conventions) : elles reçoivent un identifiant
 * synthétique stable {@code <id du texte>:<titre normalisé>}, voir {@link #idSynthetique}.
 */
public class UnistCorpusReader implements ItemStreamReader<NoeudCorpus> {

    /** Taille des colonnes {@code source_id}. */
    private static final int LONGUEUR_MAX_ID = 64;

    private final CorpusDownloader downloader;
    private final ObjectMapper objectMapper;
    private final SourceCorpus source;

    private InputStream input;
    private JsonParser parser;
    private final Deque<Frame> pile = new ArrayDeque<>();
    private String texteSourceId;
    private final Set<String> idsSynthetiques = new HashSet<>();

    /** Identifiant de section à affecter comme parent aux enfants du nœud ouvert (null = racine). */
    private record Frame(String sectionSourceIdPourEnfants) {
    }

    public UnistCorpusReader(CorpusDownloader downloader, ObjectMapper objectMapper, SourceCorpus source) {
        this.downloader = downloader;
        this.objectMapper = objectMapper;
        this.source = source;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            Path chemin = downloader.recuperer(source);
            this.input = Files.newInputStream(chemin);
            this.parser = objectMapper.createParser(input);
            this.pile.clear();
            this.texteSourceId = null;
            this.idsSynthetiques.clear();
        } catch (IOException e) {
            throw new ItemStreamException("Ouverture de la source " + source.cle() + " impossible", e);
        }
    }

    @Override
    public NoeudCorpus read() throws IOException {
        while (true) {
            JsonToken t = parser.nextToken();
            if (t == null) {
                if (!pile.isEmpty()) {
                    throw new IllegalStateException("Source " + source.cle() + " tronquée : fin de fichier inattendue");
                }
                return null; // fin de fichier
            }
            if (t == JsonToken.START_OBJECT) {
                return lireNoeud();
            }
            if (t == JsonToken.END_ARRAY && !pile.isEmpty()) {
                pile.pop(); // fin du tableau children → on remonte d'un niveau
            }
            // END_OBJECT et scalaires résiduels : ignorés
        }
    }

    private NoeudCorpus lireNoeud() throws IOException {
        String type = null;
        JsonNode data = null;
        boolean aDesEnfants = false;

        while (true) {
            JsonToken t = parser.nextToken();
            if (t == null) {
                throw new IllegalStateException("Source " + source.cle() + " tronquée : nœud incomplet");
            }
            if (t == JsonToken.END_OBJECT) {
                break; // nœud sans tableau children (ex. article)
            }
            String champ = parser.currentName();
            JsonToken valeur = parser.nextToken();
            switch (champ) {
                case "type" -> type = parser.getText();
                case "data" -> data = parser.readValueAsTree();
                case "children" -> {
                    if (type == null || data == null) {
                        throw new IllegalStateException("Source " + source.cle()
                                + " : « type » et « data » doivent précéder « children » (type=" + type + ")");
                    }
                    aDesEnfants = true; // valeur == START_ARRAY, laissé ouvert
                }
                default -> {
                    if (valeur == JsonToken.START_OBJECT || valeur == JsonToken.START_ARRAY) {
                        parser.skipChildren();
                    }
                }
            }
            if (aDesEnfants) {
                break; // on s'arrête sur le tableau children, descente aux prochains read()
            }
        }

        if (type == null || data == null) {
            throw new IllegalStateException("Source " + source.cle() + " : nœud sans « type » ou « data » (type=" + type + ")");
        }
        if (estTexte(type)) {
            texteSourceId = texte(data, "id");
        } else if ("section".equals(type) && texte(data, "id") == null) {
            ((ObjectNode) data).put("id", idSynthetique(texte(data, "title")));
        }
        String parentSectionId = pile.isEmpty() ? null : pile.peek().sectionSourceIdPourEnfants();
        NoeudCorpus item = construire(type, data, parentSectionId);

        if (aDesEnfants) {
            // Les enfants de la racine (texte) ont un parent de section null ;
            // les enfants d'une section ont cette section pour parent.
            String idPourEnfants = estTexte(type) ? null : texte(data, "id");
            pile.push(new Frame(idPourEnfants));
        }
        return item;
    }

    private NoeudCorpus construire(String type, JsonNode data, String parentSectionId) {
        if (estTexte(type)) {
            return new NoeudCorpus.TexteItem(source, data);
        }
        return switch (type) {
            case "section" -> new NoeudCorpus.SectionItem(source, data, parentSectionId);
            case "article" -> new NoeudCorpus.ArticleItem(source, data, parentSectionId);
            default -> throw new IllegalStateException("Type de nœud unist inattendu : " + type);
        };
    }

    /**
     * Identifiant stable d'une section sans identifiant DILA : id du texte + titre normalisé
     * (sans accents, en minuscules, mots séparés par des tirets). Au-delà de 64 caractères, le
     * titre normalisé est tronqué et suffixé de 8 caractères d'empreinte du titre complet, pour
     * que deux titres longs de même début restent distincts.
     * <p>
     * L'identifiant dépend du titre : si la source corrige ce titre, la section est recréée sous
     * un nouvel identifiant (l'ancienne reste en base, les sections n'étant pas suivies en
     * disparition) et chaque article enfant est journalisé en {@code CHANGEMENT_METADONNEES}.
     * Faute d'identifiant DILA, le titre reste le repère le plus stable (l'ordre l'est moins).
     */
    String idSynthetique(String titre) {
        if (texteSourceId == null) {
            throw new IllegalStateException("Source " + source.cle()
                    + " : section sans « id » hors d'un texte, impossible de l'identifier");
        }
        if (titre == null || titre.isBlank()) {
            throw new IllegalStateException("Source " + source.cle()
                    + " : section sans « id » ni titre dans " + texteSourceId);
        }
        String slug = Normalizer.normalize(titre, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        String id = texteSourceId + ":" + slug;
        if (id.length() > LONGUEUR_MAX_ID) {
            String empreinte = empreinte(titre);
            id = id.substring(0, LONGUEUR_MAX_ID - empreinte.length() - 1) + "-" + empreinte;
        }
        if (!idsSynthetiques.add(id)) {
            throw new IllegalStateException("Source " + source.cle()
                    + " : plusieurs sections sans « id » donnent le même identifiant " + id);
        }
        return id;
    }

    /** 8 premiers caractères hexadécimaux du SHA-256 du titre brut. */
    private static String empreinte(String titre) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(titre.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static boolean estTexte(String type) {
        return "code".equals(type) || "convention collective".equals(type);
    }

    private static String texte(JsonNode data, String champ) {
        JsonNode n = data == null ? null : data.get(champ);
        return n == null || n.isNull() ? null : n.asText();
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // Pas de reprise en cours de fichier : la relance repose sur l'upsert idempotent.
    }

    @Override
    public void close() throws ItemStreamException {
        try {
            if (parser != null) {
                parser.close();
            }
            if (input != null) {
                input.close();
            }
        } catch (IOException e) {
            throw new ItemStreamException("Fermeture de la source " + source.cle() + " impossible", e);
        }
    }
}
