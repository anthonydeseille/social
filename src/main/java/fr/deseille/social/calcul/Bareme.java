package fr.deseille.social.calcul;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Barème « fraction de mois de salaire par année d'ancienneté » avec un seuil : un taux en deçà, un
 * autre au-delà. Les années incomplètes comptent au prorata des mois complets.
 * <p>
 * Deux lectures possibles d'un seuil, que les textes ne départagent pas toujours :
 * <ul>
 *   <li>{@link Lecture#PAR_TRANCHE} : premier taux jusqu'au seuil, second taux pour les années au-delà
 *       (barème légal, R1234-2) ;</li>
 *   <li>{@link Lecture#TOUTES_LES_ANNEES} : une fois le seuil atteint, le second taux s'applique à
 *       toutes les années.</li>
 * </ul>
 */
record Bareme(BigDecimal tauxAvant, BigDecimal tauxApres, int seuilAnnees, Lecture lecture) {

    enum Lecture { PAR_TRANCHE, TOUTES_LES_ANNEES }

    static final BigDecimal UN_QUART = new BigDecimal("0.25");
    static final BigDecimal UN_TIERS = BigDecimal.ONE.divide(new BigDecimal(3), MathContext.DECIMAL64);

    private static final int ECHELLE = 10;

    /** Ancienneté en années, fractionnaire (mois complets / 12). */
    static BigDecimal annees(int moisComplets) {
        return BigDecimal.valueOf(moisComplets).divide(BigDecimal.valueOf(12), ECHELLE, RoundingMode.HALF_UP);
    }

    /** Montant exact, en mois de salaire, et lignes de détail du calcul. */
    Resultat appliquer(int moisComplets) {
        BigDecimal annees = annees(moisComplets);
        BigDecimal seuil = BigDecimal.valueOf(seuilAnnees);
        List<String> detail = new ArrayList<>();
        BigDecimal mois;
        if (lecture == Lecture.TOUTES_LES_ANNEES) {
            // Seuil atteint (ancienneté égale ou supérieure) : second taux pour toutes les années
            BigDecimal taux = annees.compareTo(seuil) >= 0 ? tauxApres : tauxAvant;
            mois = taux.multiply(annees);
            detail.add(format(annees) + " année(s) × " + fraction(taux) + " de mois = " + format(mois) + " mois");
        } else if (annees.compareTo(seuil) <= 0) {
            // Par tranche, jusqu'au seuil inclus : premier taux seulement
            mois = tauxAvant.multiply(annees);
            detail.add(format(annees) + " année(s) × " + fraction(tauxAvant) + " de mois = " + format(mois) + " mois");
        } else {
            BigDecimal avant = tauxAvant.multiply(seuil);
            BigDecimal apres = tauxApres.multiply(annees.subtract(seuil));
            mois = avant.add(apres);
            detail.add(seuilAnnees + " premières années × " + fraction(tauxAvant) + " de mois = " + format(avant) + " mois");
            detail.add(format(annees.subtract(seuil)) + " année(s) au-delà × " + fraction(tauxApres) + " de mois = "
                    + format(apres) + " mois");
        }
        return new Resultat(mois.setScale(ECHELLE, RoundingMode.HALF_UP), detail);
    }

    record Resultat(BigDecimal mois, List<String> detail) {
    }

    static String format(BigDecimal valeur) {
        return String.format(Locale.FRANCE, "%.2f", valeur);
    }

    private static String fraction(BigDecimal taux) {
        if (taux.compareTo(UN_QUART) == 0) {
            return "1/4";
        }
        if (taux.compareTo(UN_TIERS) == 0) {
            return "1/3";
        }
        return format(taux);
    }
}
