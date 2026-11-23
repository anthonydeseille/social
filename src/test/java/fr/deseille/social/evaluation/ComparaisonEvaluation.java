package fr.deseille.social.evaluation;

import io.github.anthonydeseille.llmeval4j.history.EvalHistory;
import io.github.anthonydeseille.llmeval4j.regression.Change;
import io.github.anthonydeseille.llmeval4j.regression.Comparison;
import io.github.anthonydeseille.llmeval4j.regression.Metric;
import io.github.anthonydeseille.llmeval4j.regression.RegressionCheck;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Comparaison d'une évaluation à la dernière ligne de {@code docs/evaluations/historique.csv} faite
 * avec le même modèle sur le même nombre de questions (sinon les écarts mesureraient le jeu, pas le
 * changement) : écart par indicateur et décision de blocage, pour la PR qui l'a lancée. Le mode
 * (lots ou direct) n'entre pas en compte : mêmes requêtes au modèle, seul le prix diffère. Le calcul
 * est celui de {@link RegressionCheck} (llmeval4j) ; cette classe en fixe les indicateurs et les seuils,
 * et rédige le commentaire de PR.
 * <p>
 * Seule une <b>baisse nette</b> bloque : sur 20 questions, une réponse qui perd un point clé fait
 * varier la qualité d'environ 2 points d'un passage à l'autre, sans aucun changement. Les seuils
 * laissent passer ce bruit :
 * <ul>
 *   <li>qualité : baisse de plus de {@value #SEUIL_POINTS} points ;</li>
 *   <li>erreurs à éviter commises : plus de {@value #SEUIL_ERREURS} de plus ;</li>
 *   <li>citations vérifiées : part en baisse de plus de {@value #SEUIL_VERIFIEES_POINTS} points
 *       (un extrait introuvable dans l'article est une faute, pas du bruit).</li>
 * </ul>
 * La recherche et les articles cités sont affichés sans bloquer : ils dépendent des requêtes que
 * Claude écrit à la reformulation, qui changent d'un lancement à l'autre avec les mêmes consignes
 * (6 points de recherche perdus ainsi le 23 novembre 2026, voir
 * {@code docs/evaluations/2026-11-23-calcul-dans-questions.md}). La recherche elle-même est contrôlée
 * sur des requêtes figées, dans chaque PR, par {@code RechercheEvaluationIT}.
 * <p>
 * Un indicateur absent d'un côté (juge non lancé, colonne vide) n'est pas comparé.
 */
final class ComparaisonEvaluation {

    static final int SEUIL_POINTS = 5;
    static final int SEUIL_ERREURS = 1;
    static final int SEUIL_VERIFIEES_POINTS = 2;

    private static final String RECHERCHE = "Recherche (articles attendus transmis)";
    private static final String ARTICLES_CITES = "Articles attendus cités";
    /** Indicateurs affichés sans seuil : ils varient avec la reformulation. */
    private static final Set<String> INDICATIFS = Set.of(RECHERCHE, ARTICLES_CITES);

    private static final RegressionCheck CONTROLE = RegressionCheck.builder()
            .sameValuesOf("modele", "questions")
            .metric(Metric.percentage("recherche_pct", RECHERCHE, Double.POSITIVE_INFINITY))
            .metric(Metric.percentage("articles_cites_pct", ARTICLES_CITES, Double.POSITIVE_INFINITY))
            .metric(Metric.percentage("qualite_pct", "Qualité (juge)", SEUIL_POINTS))
            .metric(Metric.lowerIsBetter("erreurs", "Erreurs à éviter commises", SEUIL_ERREURS))
            .metric(Metric.ratio("citations_verifiees", "citations_total", "Citations vérifiées", SEUIL_VERIFIEES_POINTS))
            .build();

    private ComparaisonEvaluation() {
    }

    /**
     * @param historique contenu de {@code historique.csv} (en-tête compris)
     * @param synthese   synthèse de l'évaluation ({@link RapportEvaluation#synthese})
     */
    static Comparison comparer(String historique, Map<String, Object> synthese) {
        return comparer(historique, synthese, null);
    }

    /**
     * @param modeleReference modèle de repli quand l'historique n'a pas encore le modèle évalué :
     *                        un nouveau modèle se compare alors au modèle actuel ; {@code null} sinon
     */
    static Comparison comparer(String historique, Map<String, Object> synthese, String modeleReference) {
        return CONTROLE.compare(EvalHistory.parse(historique), synthese,
                modeleReference == null ? Map.of() : Map.of("modele", modeleReference));
    }

    /** Commentaire de PR : tableau des écarts, référence et décision. */
    static String markdown(Comparison comparaison, Map<String, Object> synthese) {
        StringBuilder md = new StringBuilder("## Évaluation complète\n\n");
        md.append("Commit `").append(synthese.get("commit")).append("`, ").append(synthese.get("questions"))
                .append(" questions, ").append(synthese.get("modele")).append(" (").append(synthese.get("mode"))
                .append("), consignes : reformulation `").append(synthese.get("consignes_reformulation"))
                .append("`, rédaction `").append(synthese.get("consignes_redaction")).append("`, juge `")
                .append(Objects.requireNonNullElse(synthese.get("consignes_juge"), "-")).append("`.\n");
        Double cout = somme(synthese.get("cout_questions_usd"), synthese.get("cout_juge_usd"));
        if (cout != null) {
            md.append(String.format(Locale.FRANCE, "Coût : %.2f $.%n", cout));
        }
        md.append('\n');
        if (!comparaison.hasReference()) {
            md.append("Aucune évaluation de l'historique avec ce modèle et ce nombre de questions : pas de "
                    + "comparaison, pas de blocage.\n");
            return md.toString();
        }
        Map<String, String> ref = comparaison.reference();
        md.append(ref.get("modele").equals(String.valueOf(synthese.get("modele")))
                ? "Référence : dernière ligne de `historique.csv` avec ce modèle et ce nombre de questions, du "
                : "Référence : le modèle actuel, " + ref.get("modele") + " (le modèle évalué n'a pas encore d'historique), du ").append(ref.get("date"))
                .append(" (`").append(ref.get("commit")).append("`, ").append(ref.get("questions"))
                .append(" questions, consignes `").append(ref.get("consignes_reformulation")).append("` / `")
                .append(ref.get("consignes_redaction")).append("`).\n\n");
        md.append("| Indicateur | Référence | Maintenant | Écart | |\n|---|---|---|---|---|\n");
        for (Change c : comparaison.changes()) {
            md.append("| ").append(c.metric()).append(" | ").append(c.reference()).append(" | ").append(c.current())
                    .append(" | ").append(c.delta()).append(" | ")
                    .append(c.regression() ? "❌ baisse nette" : INDICATIFS.contains(c.metric()) ? "indicatif" : "✅")
                    .append(" |\n");
        }
        md.append('\n').append(comparaison.regression()
                ? "**Baisse nette : la PR est bloquée.** Si le compromis est voulu, l'expliquer dans la PR ; "
                + "la fusion reste possible en passant outre le contrôle."
                : "Pas de baisse nette (seuils : " + SEUIL_POINTS + " points de qualité, " + SEUIL_ERREURS
                + " erreur, " + SEUIL_VERIFIEES_POINTS + " points de citations vérifiées ; recherche et articles "
                + "cités indicatifs, car ils varient avec la reformulation).").append('\n');
        return md.toString();
    }

    private static Double somme(Object a, Object b) {
        Double x = nombre(a);
        Double y = nombre(b);
        return x == null && y == null ? null : (x == null ? 0 : x) + (y == null ? 0 : y);
    }

    private static Double nombre(Object valeur) {
        if (valeur == null || valeur.toString().isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(valeur.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
