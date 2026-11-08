package fr.deseille.social.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Comparaison d'une évaluation à la dernière ligne de {@code docs/evaluations/historique.csv} faite
 * avec le même modèle sur le même nombre de questions (sinon les écarts mesureraient le jeu, pas le
 * changement) : écart par indicateur et décision de blocage, pour la PR qui l'a lancée. Le mode
 * (lots ou direct) n'entre pas en compte : mêmes requêtes au modèle, seul le prix diffère.
 * <p>
 * Seule une <b>baisse nette</b> bloque : sur 20 questions, une réponse qui perd un point clé fait
 * varier la qualité d'environ 2 points d'un passage à l'autre, sans aucun changement. Les seuils
 * laissent passer ce bruit :
 * <ul>
 *   <li>recherche, articles cités, qualité : baisse de plus de {@value #SEUIL_POINTS} points ;</li>
 *   <li>erreurs à éviter commises : plus de {@value #SEUIL_ERREURS} de plus ;</li>
 *   <li>citations vérifiées : part en baisse de plus de {@value #SEUIL_VERIFIEES_POINTS} points
 *       (un extrait introuvable dans l'article est une faute, pas du bruit).</li>
 * </ul>
 * Un indicateur absent d'un côté (juge non lancé, colonne vide) n'est pas comparé.
 */
final class ComparaisonEvaluation {

    static final int SEUIL_POINTS = 5;
    static final int SEUIL_ERREURS = 1;
    static final int SEUIL_VERIFIEES_POINTS = 2;

    /**
     * @param reference ligne de l'historique comparée, {@code null} si aucune n'a le même modèle et le
     *                  même nombre de questions
     * @param ecarts    un écart par indicateur comparable, dans l'ordre d'affichage
     */
    record Resultat(Map<String, String> reference, List<Ecart> ecarts) {

        boolean bloquant() {
            return ecarts.stream().anyMatch(Ecart::bloquant);
        }
    }

    /** @param bloquant baisse nette, au-delà du seuil de l'indicateur */
    record Ecart(String indicateur, String avant, String apres, String ecart, boolean bloquant) {
    }

    private ComparaisonEvaluation() {
    }

    /**
     * @param historique contenu de {@code historique.csv} (en-tête compris)
     * @param synthese   synthèse de l'évaluation ({@link RapportEvaluation#synthese})
     */
    static Resultat comparer(String historique, Map<String, Object> synthese) {
        return comparer(historique, synthese, null);
    }

    /**
     * @param modeleReference modèle de repli quand l'historique n'a pas encore le modèle évalué :
     *                        un nouveau modèle se compare alors au modèle actuel ; {@code null} sinon
     */
    static Resultat comparer(String historique, Map<String, Object> synthese, String modeleReference) {
        List<Map<String, String>> lignes = lire(historique);
        String questions = String.valueOf(synthese.get("questions"));
        Map<String, String> reference = derniere(lignes, String.valueOf(synthese.get("modele")), questions);
        if (reference == null && modeleReference != null) {
            reference = derniere(lignes, modeleReference, questions);
        }
        if (reference == null) {
            return new Resultat(null, List.of());
        }
        List<Ecart> ecarts = new ArrayList<>();
        pourcentage(ecarts, "Recherche (articles attendus transmis)", reference, synthese, "recherche_pct");
        pourcentage(ecarts, "Articles attendus cités", reference, synthese, "articles_cites_pct");
        pourcentage(ecarts, "Qualité (juge)", reference, synthese, "qualite_pct");
        Double erreursAvant = nombre(reference.get("erreurs"));
        Double erreursApres = nombre(synthese.get("erreurs"));
        if (erreursAvant != null && erreursApres != null) {
            double ecart = erreursApres - erreursAvant;
            ecarts.add(new Ecart("Erreurs à éviter commises", entier(erreursAvant), entier(erreursApres),
                    signe(ecart, ""), ecart > SEUIL_ERREURS));
        }
        Double partAvant = part(reference.get("citations_verifiees"), reference.get("citations_total"));
        Double partApres = part(synthese.get("citations_verifiees"), synthese.get("citations_total"));
        if (partAvant != null && partApres != null) {
            // Arrondi au dixième : 196/200 contre 197/197 doit donner -2,0 et non -2,0000000000000018
            double ecart = Math.round(1000 * (partApres - partAvant)) / 10.0;
            ecarts.add(new Ecart("Citations vérifiées",
                    reference.get("citations_verifiees") + "/" + reference.get("citations_total"),
                    synthese.get("citations_verifiees") + "/" + synthese.get("citations_total"),
                    String.format(Locale.FRANCE, "%+.1f pts", ecart), ecart < -SEUIL_VERIFIEES_POINTS));
        }
        return new Resultat(reference, ecarts);
    }

    private static Map<String, String> derniere(List<Map<String, String>> lignes, String modele, String questions) {
        Map<String, String> reference = null;
        for (Map<String, String> ligne : lignes) {
            if (modele.equals(ligne.get("modele")) && questions.equals(ligne.get("questions"))) {
                reference = ligne;
            }
        }
        return reference;
    }

    /** Commentaire de PR : tableau des écarts, référence et décision. */
    static String markdown(Resultat resultat, Map<String, Object> synthese) {
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
        if (resultat.reference() == null) {
            md.append("Aucune évaluation de l'historique avec ce modèle et ce nombre de questions : pas de "
                    + "comparaison, pas de blocage.\n");
            return md.toString();
        }
        Map<String, String> ref = resultat.reference();
        md.append(ref.get("modele").equals(String.valueOf(synthese.get("modele")))
                ? "Référence : dernière ligne de `historique.csv` avec ce modèle et ce nombre de questions, du "
                : "Référence : le modèle actuel, " + ref.get("modele") + " (le modèle évalué n'a pas encore d'historique), du ").append(ref.get("date"))
                .append(" (`").append(ref.get("commit")).append("`, ").append(ref.get("questions"))
                .append(" questions, consignes `").append(ref.get("consignes_reformulation")).append("` / `")
                .append(ref.get("consignes_redaction")).append("`).\n\n");
        md.append("| Indicateur | Référence | Maintenant | Écart | |\n|---|---|---|---|---|\n");
        for (Ecart e : resultat.ecarts()) {
            md.append("| ").append(e.indicateur()).append(" | ").append(e.avant()).append(" | ").append(e.apres())
                    .append(" | ").append(e.ecart()).append(" | ").append(e.bloquant() ? "❌ baisse nette" : "✅")
                    .append(" |\n");
        }
        md.append('\n').append(resultat.bloquant()
                ? "**Baisse nette : la PR est bloquée.** Si le compromis est voulu, l'expliquer dans la PR ; "
                + "la fusion reste possible en passant outre le contrôle."
                : "Pas de baisse nette (seuils : " + SEUIL_POINTS + " points, " + SEUIL_ERREURS + " erreur, "
                + SEUIL_VERIFIEES_POINTS + " points de citations vérifiées).").append('\n');
        return md.toString();
    }

    /** Lignes du CSV, en tables nommées par l'en-tête ; gère les valeurs entre guillemets. */
    static List<Map<String, String>> lire(String csv) {
        List<List<String>> lignes = new ArrayList<>();
        for (String ligne : csv.split("\\R")) {
            if (!ligne.isBlank()) {
                lignes.add(champs(ligne));
            }
        }
        if (lignes.isEmpty()) {
            return List.of();
        }
        List<String> entete = lignes.getFirst();
        List<Map<String, String>> resultat = new ArrayList<>();
        for (List<String> valeurs : lignes.subList(1, lignes.size())) {
            Map<String, String> ligne = new LinkedHashMap<>();
            for (int i = 0; i < entete.size(); i++) {
                ligne.put(entete.get(i), i < valeurs.size() ? valeurs.get(i) : "");
            }
            resultat.add(ligne);
        }
        return resultat;
    }

    private static List<String> champs(String ligne) {
        List<String> champs = new ArrayList<>();
        StringBuilder courant = new StringBuilder();
        boolean guillemets = false;
        for (int i = 0; i < ligne.length(); i++) {
            char c = ligne.charAt(i);
            if (guillemets) {
                if (c == '"' && i + 1 < ligne.length() && ligne.charAt(i + 1) == '"') {
                    courant.append('"');
                    i++;
                } else if (c == '"') {
                    guillemets = false;
                } else {
                    courant.append(c);
                }
            } else if (c == '"') {
                guillemets = true;
            } else if (c == ',') {
                champs.add(courant.toString());
                courant.setLength(0);
            } else {
                courant.append(c);
            }
        }
        champs.add(courant.toString());
        return champs;
    }

    private static void pourcentage(List<Ecart> ecarts, String indicateur, Map<String, String> reference,
                                    Map<String, Object> synthese, String colonne) {
        Double avant = nombre(reference.get(colonne));
        Double apres = nombre(synthese.get(colonne));
        if (avant != null && apres != null) {
            double ecart = apres - avant;
            ecarts.add(new Ecart(indicateur, entier(avant) + " %", entier(apres) + " %", signe(ecart, " pts"),
                    ecart < -SEUIL_POINTS));
        }
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

    private static Double part(Object verifiees, Object total) {
        Double v = nombre(verifiees);
        Double t = nombre(total);
        return v == null || t == null || t == 0 ? null : v / t;
    }

    private static Double somme(Object a, Object b) {
        Double x = nombre(a);
        Double y = nombre(b);
        return x == null && y == null ? null : (x == null ? 0 : x) + (y == null ? 0 : y);
    }

    private static String entier(double valeur) {
        return String.valueOf(Math.round(valeur));
    }

    private static String signe(double ecart, String unite) {
        return (ecart > 0 ? "+" : "") + Math.round(ecart) + unite;
    }
}
