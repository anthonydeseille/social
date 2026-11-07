package fr.deseille.social.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Requêtes de reformulation figées par question ({@code src/test/resources/evaluation/requetes.yaml}) :
 * rejouées par {@link RechercheEvaluationIT} pour mesurer la recherche sans appel au modèle, donc
 * gratuitement et de façon déterministe. Toute évaluation complète en écrit une nouvelle version dans
 * {@code target/evaluation/} ; on la recopie quand les consignes de reformulation ou le modèle changent.
 *
 * @param origine provenance (modèle, consignes, date), recopiée dans le rapport
 */
record RequetesFigees(String origine, Map<String, List<String>> parQuestion) {

    static RequetesFigees lireRessource(String ressource) throws IOException {
        try (InputStream in = RequetesFigees.class.getResourceAsStream(ressource)) {
            if (in == null) {
                throw new IOException("Ressource introuvable : " + ressource);
            }
            return lire(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static RequetesFigees lire(String yaml) {
        if (!(new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml) instanceof Map<?, ?> racine)
                || !(racine.get("requetes") instanceof Map<?, ?> requetes)) {
            throw new IllegalArgumentException("Requêtes figées : « origine » et « requetes » attendus");
        }
        Map<String, List<String>> parQuestion = new LinkedHashMap<>();
        requetes.forEach((id, liste) -> {
            if (!(liste instanceof List<?> l) || l.isEmpty() || !l.stream().allMatch(r -> r instanceof String s && !s.isBlank())) {
                throw new IllegalArgumentException("Requêtes figées de « " + id + " » : liste de chaînes non vides attendue");
            }
            parQuestion.put(String.valueOf(id), l.stream().map(String.class::cast).toList());
        });
        return new RequetesFigees(String.valueOf(racine.get("origine")), parQuestion);
    }

    /** Fichier YAML, dans l'ordre des questions, prêt à remplacer {@code requetes.yaml}. */
    String yaml() {
        StringBuilder y = new StringBuilder("""
                # Requêtes de reformulation figées par question, rejouées par RechercheEvaluationIT pour
                # mesurer la recherche sans appel au modèle. Écrit par EvaluationIT (target/evaluation/) ;
                # à recopier ici quand les consignes de reformulation ou le modèle changent.
                """);
        y.append("origine: ").append(chaine(origine)).append("\nrequetes:\n");
        parQuestion.forEach((id, requetes) -> {
            List<String> echappees = new ArrayList<>();
            requetes.forEach(r -> echappees.add(chaine(r)));
            y.append("  ").append(id).append(": [").append(String.join(", ", echappees)).append("]\n");
        });
        return y.toString();
    }

    private static String chaine(String texte) {
        return "\"" + texte.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
