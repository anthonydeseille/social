package fr.deseille.social.questions;

import fr.deseille.social.calcul.Indemnite.Calcul;
import fr.deseille.social.calcul.Indemnite.Resultat;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Calcul fait par le code pour une question, entre la recherche et la rédaction : le modèle reçoit le
 * résultat et l'explique, sans le refaire (les montants ne viennent jamais du modèle). Renvoyé tel quel
 * dans la réponse, pour que le client puisse l'afficher.
 *
 * @param intitule   ce qui est calculé, par exemple « indemnité de licenciement »
 * @param resultat   résultat du calculateur ; {@code null} si le calcul est impossible
 * @param impossible raison pour laquelle le calcul n'a pas pu être fait (donnée du profil manquante),
 *                   {@code null} sinon
 */
public record CalculQuestion(String intitule, Resultat resultat, String impossible) {

    /**
     * Bloc ajouté au message de rédaction, entre le profil et la question ; vide sans calcul.
     *
     * @param calcul calcul de la question, {@code null} s'il n'y en a pas
     */
    static String bloc(CalculQuestion calcul) {
        return calcul == null ? "" : "\n\n" + calcul.description();
    }

    /**
     * Avertissement de la réponse quand un calcul a été fait ; {@code null} sinon.
     *
     * @param calcul calcul de la question, {@code null} s'il n'y en a pas
     */
    static String avertissement(CalculQuestion calcul) {
        return calcul == null || calcul.resultat() == null ? null : "Les chiffres de l'" + calcul.intitule()
                + " viennent du calculateur de l'application, selon des règles en cours de validation juridique "
                + "(détail et hypothèses dans le calcul).";
    }

    /** Texte transmis au modèle avec le profil : résultat, détail, hypothèses et avertissements. */
    public String description() {
        List<String> lignes = new ArrayList<>();
        if (resultat == null) {
            lignes.add("Calcul de l'" + intitule + " par le calculateur de l'application : impossible (" + impossible
                    + "). Explique ce qu'il faudrait préciser, sans faire le calcul toi-même.");
            return String.join("\n", lignes);
        }
        lignes.add("Calcul de l'" + intitule + " par le calculateur de l'application (règles du Code du travail et de "
                + "la convention, calcul exact) : reprends ces chiffres tels quels, sans les recalculer, et explique-les "
                + "en citant les articles.");
        lignes.addAll(resultatEnLignes());
        if (!resultat.hypotheses().isEmpty()) {
            lignes.add("Hypothèses du calcul :");
            resultat.hypotheses().forEach(h -> lignes.add("- " + h));
        }
        if (!resultat.avertissements().isEmpty()) {
            lignes.add("Avertissements du calcul :");
            resultat.avertissements().forEach(a -> lignes.add("- " + a));
        }
        return String.join("\n", lignes);
    }

    /**
     * Chiffres que la réponse peut reprendre du calcul : ancienneté, détail, mois et montant, sans les
     * dates ni les seuils des hypothèses ; vide si le calcul est impossible.
     */
    String chiffres() {
        return resultat == null ? "" : String.join("\n", resultatEnLignes());
    }

    private List<String> resultatEnLignes() {
        List<String> lignes = new ArrayList<>();
        lignes.add("- Ancienneté retenue : " + resultat.anneesAnciennete() + " an(s) et " + resultat.moisAnciennete() + " mois");
        if (!resultat.droit()) {
            lignes.add("- Pas d'indemnité : " + resultat.absenceDroit());
        } else {
            calcul(lignes, resultat.legale());
            if (resultat.conventionnelle() != null) {
                calcul(lignes, resultat.conventionnelle());
            }
            lignes.add("- Montant retenu, le plus favorable (" + retenue() + ") : " + nombre(resultat.moisRetenus())
                    + " mois de salaire");
            if (resultat.montantBrut() != null) {
                lignes.add("- Soit " + nombre(resultat.montantBrut()) + " € brut");
            }
        }
        return lignes;
    }

    private static void calcul(List<String> lignes, Calcul calcul) {
        lignes.add("- " + calcul.regle() + " : " + nombre(calcul.mois()) + " mois de salaire");
        calcul.detail().forEach(d -> lignes.add("  · " + d));
    }

    private String retenue() {
        return switch (resultat.retenue()) {
            case LEGALE -> "indemnité légale";
            case CONVENTIONNELLE -> "indemnité conventionnelle";
            case IDENTIQUES -> "indemnités légale et conventionnelle identiques";
        };
    }

    /** Écriture française : « 2,36 ». */
    private static String nombre(BigDecimal n) {
        return n.toPlainString().replace('.', ',');
    }
}
