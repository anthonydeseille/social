package fr.deseille.social.calcul;

import fr.deseille.social.profil.ProfilSalarie;
import java.util.List;
import java.util.Optional;

/**
 * Barème d'indemnité de licenciement d'une convention collective. Une implémentation par convention
 * intégrée ; le calculateur les retrouve par IDCC.
 */
interface BaremeConventionnel {

    int idcc();

    /**
     * Indemnité conventionnelle, ou vide si le profil ne permet pas de la calculer (statut manquant…).
     *
     * @param moisComplets ancienneté de calcul, en mois complets
     */
    Optional<Indemnite.Calcul> calculer(ProfilSalarie profil, int moisComplets);

    /** Hypothèses propres à la convention, à afficher avec le résultat. */
    List<String> hypotheses();
}
