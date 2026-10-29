package fr.deseille.social.calcul;

import fr.deseille.social.calcul.Bareme.Lecture;
import fr.deseille.social.calcul.Indemnite.Calcul;
import fr.deseille.social.calcul.Indemnite.Source;
import fr.deseille.social.profil.ProfilSalarie;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Indemnité conventionnelle Syntec (IDCC 1486), article 4.5 : ETAM 1/4 de mois par année jusqu'à
 * 10 ans puis 1/3 ; ingénieurs et cadres 1/4 en deçà de 2 ans puis 1/3 ; années incomplètes au
 * prorata ; mois de rémunération = 1/12 des 12 derniers mois, primes contractuelles comprises.
 * <p>
 * <b>Lecture à valider</b> : le texte dit « 1/3 de mois pour chaque année de présence » au-delà du
 * seuil, pour les deux catégories, sans préciser si le taux vaut pour toutes les années ou pour les
 * seules années au-delà. Lectures retenues en attendant (celles du jeu d'évaluation) : par tranche
 * pour les ETAM, toutes les années pour les cadres. Voir docs/regles-indemnite-licenciement.md.
 */
@Component
class BaremeSyntec implements BaremeConventionnel {

    static final String SYNTEC = "KALICONT000005635173";

    static final Bareme ETAM = new Bareme(Bareme.UN_QUART, Bareme.UN_TIERS, 10, Lecture.PAR_TRANCHE);
    static final Bareme INGENIEURS_CADRES = new Bareme(Bareme.UN_QUART, Bareme.UN_TIERS, 2, Lecture.TOUTES_LES_ANNEES);

    @Override
    public int idcc() {
        return 1486;
    }

    @Override
    public Optional<Indemnite.Evalue> calculer(ProfilSalarie profil, int moisComplets) {
        if (profil.statut() == null) {
            return Optional.empty();
        }
        boolean cadre = profil.statut() == ProfilSalarie.Statut.CADRE;
        Bareme.Resultat r = (cadre ? INGENIEURS_CADRES : ETAM).appliquer(moisComplets);
        List<String> detail = new ArrayList<>();
        detail.add(cadre ? "Ingénieurs et cadres : 1/4 de mois par année en deçà de 2 ans, 1/3 à partir de 2 ans"
                : "ETAM : 1/4 de mois par année jusqu'à 10 ans, 1/3 au-delà");
        detail.addAll(r.detail());
        return Optional.of(new Indemnite.Evalue(new Calcul("Convention Syntec, article 4.5",
                r.mois().setScale(2, RoundingMode.HALF_UP), detail,
                List.of(new Source("4.5", SYNTEC), new Source("3.7", SYNTEC))), r.mois()));
    }

    @Override
    public List<String> hypotheses() {
        return List.of("Syntec : le mois de rémunération est le douzième des 12 derniers mois précédant la "
                        + "notification, primes contractuelles comprises, hors majorations d'heures supplémentaires "
                        + "et indemnités de déplacement.",
                "Syntec : lecture du barème au-delà du seuil (par tranche pour les ETAM, toutes les années pour "
                        + "les cadres) en cours de validation juridique.");
    }
}
