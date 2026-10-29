package fr.deseille.social.calcul;

import fr.deseille.social.profil.ProfilSalarie;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Types du calcul de l'indemnité de licenciement. */
public final class Indemnite {

    private Indemnite() {
    }

    public enum Motif {
        LICENCIEMENT_PERSONNEL,
        LICENCIEMENT_ECONOMIQUE,
        FAUTE_GRAVE,
        FAUTE_LOURDE
    }

    /**
     * @param profil             profil du salarié (date d'entrée obligatoire ; statut et IDCC pour la convention)
     * @param dateNotification   date de notification du licenciement : la condition de 8 mois s'y apprécie
     * @param dateFinContrat     fin du contrat, préavis compris même non effectué : l'ancienneté de
     *                           calcul s'arrête là ; la date de notification si absente
     * @param salaireMensuelBrut salaire mensuel brut de référence, pour un montant en euros (facultatif)
     */
    public record Demande(@NotNull @Valid ProfilSalarie profil, @NotNull LocalDate dateNotification,
                          LocalDate dateFinContrat, @NotNull Motif motif, @Positive BigDecimal salaireMensuelBrut) {
    }

    /** Article sur lequel repose une règle, identifié de façon stable (numéro et texte). */
    public record Source(String num, String texteSourceId) {
    }

    /**
     * Un calcul (légal ou conventionnel).
     *
     * @param mois   indemnité en mois de salaire, arrondie au centième
     * @param detail étapes du calcul, lisibles par le salarié
     */
    public record Calcul(String regle, BigDecimal mois, List<String> detail, List<Source> sources) {
    }

    public enum Retenue { LEGALE, CONVENTIONNELLE, IDENTIQUES }

    /**
     * @param droit           le salarié a droit à une indemnité de licenciement
     * @param absenceDroit    raison de l'absence de droit, null sinon
     * @param conventionnelle null si la convention n'est pas renseignée, pas intégrée, ou si le statut manque
     * @param moisRetenus     montant retenu (le plus favorable), en mois de salaire
     * @param montantBrut     en euros si le salaire est fourni, null sinon
     * @param hypotheses      hypothèses du calcul, à garder en tête pour lire le résultat
     */
    public record Resultat(boolean droit, String absenceDroit, int anneesAnciennete, int moisAnciennete,
                           Calcul legale, Calcul conventionnelle, Retenue retenue, BigDecimal moisRetenus,
                           BigDecimal montantBrut, List<String> hypotheses, List<String> avertissements) {
    }
}
