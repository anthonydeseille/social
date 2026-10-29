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
 * Le fichier étant édité à la main, il est vérifié à la lecture, avant toute ingestion ou dépense :
 * un champ obligatoire manquant ou un identifiant en double donne un message qui situe l'erreur.
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
            if (!(datesLocales(new Yaml(new SafeConstructor(new LoaderOptions())).load(reader)) instanceof Map<?, ?> racine)) {
                throw invalide("le fichier doit contenir « version » et « questions »");
            }
            if (!(racine.get("version") instanceof Integer version)) {
                throw invalide("champ « version » manquant ou non numérique");
            }
            if (!(racine.get("questions") instanceof List<?> liste) || liste.isEmpty()) {
                throw invalide("liste « questions » manquante ou vide");
            }
            List<Question> questions = new java.util.ArrayList<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < liste.size(); i++) {
                if (!(liste.get(i) instanceof Map<?, ?> q)) {
                    throw invalide("question n° " + (i + 1) + " : ce n'est pas un objet");
                }
                Question question = question((Map<String, Object>) q, i + 1);
                if (!ids.add(question.id())) {
                    throw invalide("question n° " + (i + 1) + " : identifiant « " + question.id() + " » en double");
                }
                questions.add(question);
            }
            return new JeuEvaluation(version, List.copyOf(questions));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Question question(Map<String, Object> q, int numero) {
        String contexte = "question n° " + numero + (q.get("id") instanceof String id ? " (" + id + ")" : "");
        String id = texteObligatoire(q, "id", contexte);
        List<Map<String, Object>> articles = (List<Map<String, Object>>) q.getOrDefault("articles_attendus", List.of());
        for (int i = 0; i < articles.size(); i++) {
            texteObligatoire(articles.get(i), "sourceId", contexte + ", article attendu n° " + (i + 1));
        }
        ProfilSalarie profil;
        try {
            profil = JSON.convertValue(q.getOrDefault("profil", Map.of()), ProfilSalarie.class);
        } catch (RuntimeException e) {
            throw invalide(contexte + " : profil invalide (" + e.getMessage().lines().findFirst().orElse("") + ")");
        }
        return new Question(
                id,
                (String) q.get("theme"),
                Boolean.TRUE.equals(q.get("valide")),
                texteObligatoire(q, "question", contexte),
                profil,
                texteObligatoire(q, "reponse_attendue", contexte),
                (List<String>) q.getOrDefault("points_cles", List.of()),
                articles.stream().map(a -> new ArticleAttendu(String.valueOf(a.get("num")), (String) a.get("texte"),
                        (String) a.get("sourceId"))).toList(),
                (List<String>) q.getOrDefault("erreurs_a_eviter", List.of()));
    }

    private static String texteObligatoire(Map<String, Object> map, String champ, String contexte) {
        if (!(map.get(champ) instanceof String valeur) || valeur.isBlank()) {
            throw invalide(contexte + " : champ « " + champ + " » manquant");
        }
        return valeur;
    }

    private static IllegalArgumentException invalide(String detail) {
        return new IllegalArgumentException("Jeu d'évaluation invalide, " + detail);
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
