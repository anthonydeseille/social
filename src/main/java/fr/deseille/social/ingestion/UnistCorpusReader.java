package fr.deseille.social.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lecteur Spring Batch qui parcourt en profondeur l'arbre unist d'une source (Code ou
 * convention) en <strong>streaming</strong> Jackson : seul le {@code data} du nœud courant
 * est matérialisé en mémoire, jamais l'arbre entier (indispensable pour le fichier Code de
 * 56 Mo). Émet un {@link NoeudCorpus} par appel à {@link #read()}, dans l'ordre du document,
 * chaque nœud portant l'identifiant de son parent (résolu via une pile de sections).
 */
public class UnistCorpusReader implements ItemStreamReader<NoeudCorpus> {

    private final CorpusDownloader downloader;
    private final ObjectMapper objectMapper;
    private final SourceCorpus source;

    private InputStream input;
    private JsonParser parser;
    private final Deque<Frame> pile = new ArrayDeque<>();

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
        } catch (IOException e) {
            throw new ItemStreamException("Ouverture de la source " + source.cle() + " impossible", e);
        }
    }

    @Override
    public NoeudCorpus read() throws IOException {
        while (true) {
            JsonToken t = parser.nextToken();
            if (t == null) {
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
            if (t == JsonToken.END_OBJECT) {
                break; // nœud sans tableau children (ex. article)
            }
            String champ = parser.currentName();
            JsonToken valeur = parser.nextToken();
            switch (champ) {
                case "type" -> type = parser.getText();
                case "data" -> data = parser.readValueAsTree();
                case "children" -> aDesEnfants = true; // valeur == START_ARRAY, laissé ouvert
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
