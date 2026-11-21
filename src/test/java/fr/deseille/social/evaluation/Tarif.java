package fr.deseille.social.evaluation;

import fr.deseille.social.questions.AssistantClaude;
import java.util.Locale;

/**
 * Tarif d'un modèle, en dollars par million de jetons, pour chiffrer le coût réel d'une évaluation.
 * Déduit du modèle pour les modèles courants, ou forcé par propriétés système
 * ({@code <prefixe>.prix.entree} et {@code <prefixe>.prix.sortie}) ; inconnu sinon, et le rapport
 * l'affiche plutôt que d'annoncer un coût faux.
 */
record Tarif(String modele, double entree, double sortie) {

    /** Prix de l'application ({@link AssistantClaude#PRIX}), seule table à tenir à jour. */
    static Tarif pour(String modele) {
        return AssistantClaude.PRIX.find(modele).map(p -> new Tarif(modele, p.inputPerMillion(), p.outputPerMillion()))
                .orElse(new Tarif(modele, Double.NaN, Double.NaN));
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

    /**
     * Modèles différents pour la reformulation et la rédaction : pas de tarif unique, le coût de
     * chaque question vient du prix de chaque étape ({@code Resultat.cout}).
     */
    static Tarif parEtapes(String modele, String modeleReformulation) {
        return modele.equals(modeleReformulation) ? pour(modele, "evaluation")
                : new Tarif(modele + " + reformulation " + modeleReformulation, Double.NaN, Double.NaN);
    }

    /** Modèle local (Ollama) : pas de facturation, seulement le temps de calcul de la machine. */
    static Tarif local(String modele) {
        return new Tarif(modele + " (local, gratuit)", 0, 0);
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
        if (modele.contains(" + reformulation ")) {
            return modele + " (au prix de chaque étape)";
        }
        return connu()
                ? String.format(Locale.FRANCE, "%s à %.2f $ / %.2f $ par million de jetons", modele, entree, sortie)
                : modele + " (tarif inconnu : préciser -Devaluation.prix.entree et .sortie)";
    }

    static String dollars(double montant, String format) {
        return Double.isNaN(montant) ? "n/a" : String.format(Locale.FRANCE, format, montant);
    }
}
