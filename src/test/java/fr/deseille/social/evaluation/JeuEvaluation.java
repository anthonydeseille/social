package fr.deseille.social.evaluation;

import fr.deseille.social.profil.ProfilSalarie;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jeu d'évaluation lu depuis {@code questions.yaml} (voir l'en-tête de ce fichier pour le format).
 */
record JeuEvaluation(int version, List<Question> questions) {

    /** Article qu'une réponse correcte doit citer. */
    record ArticleAttendu(String num, String texte, String sourceId) {
    }

    record Question(String id, String theme, boolean valide, String question, ProfilSalarie profil,
                    String reponseAttendue, List<String> pointsCles, List<ArticleAttendu> articlesAttendus,
                    List<String> erreursAEviter) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static JeuEvaluation lire(Path fichier) throws IOException {
        return lire(Files.readString(fichier, StandardCharsets.UTF_8));
    }

    static JeuEvaluation lireRessource(String ressource) throws IOException {
        try (InputStream in = JeuEvaluation.class.getResourceAsStream(ressource)) {
            if (in == null) {
                throw new IOException("Ressource introuvable : " + ressource);
            }
            return lire(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    static JeuEvaluation lire(String yaml) {
        try (Reader reader = new StringReader(yaml)) {
            Map<String, Object> racine = (Map<String, Object>) datesLocales(
                    new Yaml(new SafeConstructor(new LoaderOptions())).load(reader));
            List<Map<String, Object>> questions = (List<Map<String, Object>>) racine.get("questions");
            return new JeuEvaluation((Integer) racine.get("version"), questions.stream().map(JeuEvaluation::question).toList());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Question question(Map<String, Object> q) {
        List<Map<String, Object>> articles = (List<Map<String, Object>>) q.getOrDefault("articles_attendus", List.of());
        return new Question(
                (String) q.get("id"),
                (String) q.get("theme"),
                Boolean.TRUE.equals(q.get("valide")),
                (String) q.get("question"),
                JSON.convertValue(q.getOrDefault("profil", Map.of()), ProfilSalarie.class),
                (String) q.get("reponse_attendue"),
                (List<String>) q.getOrDefault("points_cles", List.of()),
                articles.stream().map(a -> new ArticleAttendu(String.valueOf(a.get("num")), (String) a.get("texte"),
                        (String) a.get("sourceId"))).toList(),
                (List<String>) q.getOrDefault("erreurs_a_eviter", List.of()));
    }

    /** SnakeYAML lit {@code 2026-11-01} comme un {@link Date} à minuit UTC : ramené en {@link LocalDate}. */
    @SuppressWarnings("unchecked")
    private static Object datesLocales(Object valeur) {
        if (valeur instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (valeur instanceof Map<?, ?> map) {
            Map<String, Object> copie = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> copie.put(k, datesLocales(v)));
            return copie;
        }
        if (valeur instanceof List<?> liste) {
            return liste.stream().map(JeuEvaluation::datesLocales).toList();
        }
        return valeur;
    }
}
