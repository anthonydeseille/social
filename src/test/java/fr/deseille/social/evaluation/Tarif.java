package fr.deseille.social.evaluation;

import java.util.Locale;
import java.util.Map;

/**
 * Tarif d'un modèle, en dollars par million de jetons, pour chiffrer le coût réel d'une évaluation.
 * Déduit du modèle pour les modèles courants, ou forcé par propriétés système
 * ({@code <prefixe>.prix.entree} et {@code <prefixe>.prix.sortie}) ; inconnu sinon, et le rapport
 * l'affiche plutôt que d'annoncer un coût faux.
 */
record Tarif(String modele, double entree, double sortie) {

    /** Tarifs de la documentation Anthropic (octobre 2026) ; à mettre à jour s'ils changent. */
    private static final Map<String, double[]> CONNUS = Map.of(
            "claude-opus-5-5", new double[] {4.0, 20.0},
            "claude-sonnet-5-5", new double[] {2.0, 10.0},
            "claude-haiku-5-5", new double[] {0.10, 0.50});

    static Tarif pour(String modele) {
        double[] prix = CONNUS.get(modele);
        return prix == null ? new Tarif(modele, Double.NaN, Double.NaN) : new Tarif(modele, prix[0], prix[1]);
    }

    /** Tarif du modèle, sauf s'il est forcé par {@code -D<prefixe>.prix.entree/sortie}. */
    static Tarif pour(String modele, String prefixe) {
        String entree = System.getProperty(prefixe + ".prix.entree");
        String sortie = System.getProperty(prefixe + ".prix.sortie");
        if (entree != null && sortie != null) {
            return new Tarif(modele, Double.parseDouble(entree), Double.parseDouble(sortie));
        }
        return pour(modele);
    }

    /** Tarif de l'API Batch : moitié prix. */
    Tarif lots() {
        return new Tarif(modele + " (lots, moitié prix)", entree / 2, sortie / 2);
    }

    boolean connu() {
        return !Double.isNaN(entree) && !Double.isNaN(sortie);
    }

    /** Coût en dollars ; NaN si le tarif est inconnu. */
    double cout(long jetonsEntree, long jetonsSortie) {
        return (jetonsEntree * entree + jetonsSortie * sortie) / 1_000_000;
    }

    String libelle() {
        return connu()
                ? String.format(Locale.FRANCE, "%s à %.2f $ / %.2f $ par million de jetons", modele, entree, sortie)
                : modele + " (tarif inconnu : préciser -Devaluation.prix.entree et .sortie)";
    }

    static String dollars(double montant, String format) {
        return Double.isNaN(montant) ? "n/a" : String.format(Locale.FRANCE, format, montant);
    }
}
