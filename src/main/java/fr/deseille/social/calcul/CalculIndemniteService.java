package fr.deseille.social.calcul;

import fr.deseille.social.calcul.Bareme.Lecture;
import fr.deseille.social.calcul.Indemnite.Calcul;
import fr.deseille.social.calcul.Indemnite.Demande;
import fr.deseille.social.calcul.Indemnite.Motif;
import fr.deseille.social.calcul.Indemnite.Resultat;
import fr.deseille.social.calcul.Indemnite.Retenue;
import fr.deseille.social.calcul.Indemnite.Source;
import fr.deseille.social.profil.ProfilSalarie;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Calcul déterministe de l'indemnité de licenciement : légale (L1234-9, R1234-1, R1234-2) et, si la
 * convention du salarié est intégrée, conventionnelle ; le montant le plus favorable est retenu.
 * Les montants viennent toujours de ce calcul, jamais d'un modèle de langage, qui peut seulement
 * les expliquer.
 */
@Service
public class CalculIndemniteService {

    static final String CODE = "LEGITEXT000006072050";
    static final Bareme LEGAL = new Bareme(Bareme.UN_QUART, Bareme.UN_TIERS, 10, Lecture.PAR_TRANCHE);
    private static final int MOIS_MINIMUM = 8;

    private final Map<Integer, BaremeConventionnel> conventions;

    public CalculIndemniteService(List<BaremeConventionnel> conventions) {
        this.conventions = conventions.stream().collect(Collectors.toMap(BaremeConventionnel::idcc, Function.identity()));
    }

    public Resultat calculer(Demande demande) {
        ProfilSalarie profil = demande.profil();
        LocalDate entree = profil.dateEntree();
        LocalDate notification = demande.dateNotification();
        LocalDate fin = demande.dateFinContrat() != null ? demande.dateFinContrat() : notification;
        if (entree == null) {
            throw invalide("La date d'entrée dans l'entreprise est nécessaire au calcul.");
        }
        if (notification.isBefore(entree) || fin.isBefore(notification)) {
            throw invalide("Dates incohérentes : entrée ≤ notification ≤ fin du contrat attendu.");
        }

        Period anciennete = Period.between(entree, fin);
        List<String> hypotheses = new ArrayList<>(List.of(
                "Ancienneté comptée de la date d'entrée à la fin du contrat (préavis compris, même non effectué), "
                        + "en mois complets ; la condition de 8 mois s'apprécie à la date de notification.",
                "Sans période de suspension du contrat (arrêt maladie long, congé sans solde…), qui peut réduire "
                        + "l'ancienneté retenue.",
                "Hors licenciement pour inaptitude d'origine professionnelle (indemnité spéciale, doublée)."));
        List<String> avertissements = new ArrayList<>();

        String absenceDroit = absenceDroit(demande, profil, Period.between(entree, notification).toTotalMonths());
        if (profil.typeContrat() == null) {
            hypotheses.add("Contrat supposé à durée indéterminée (type de contrat non renseigné).");
        }
        if (absenceDroit != null) {
            return new Resultat(false, absenceDroit, anciennete.getYears(), anciennete.getMonths(), null, null,
                    null, BigDecimal.ZERO, null, hypotheses, avertissements);
        }

        int moisComplets = (int) anciennete.toTotalMonths();
        Calcul legale = legale(moisComplets);
        Calcul conventionnelle = conventionnelle(profil, moisComplets, hypotheses, avertissements);

        Retenue retenue = Retenue.LEGALE;
        BigDecimal mois = legale.mois();
        if (conventionnelle != null) {
            int comparaison = conventionnelle.mois().compareTo(legale.mois());
            retenue = comparaison > 0 ? Retenue.CONVENTIONNELLE : comparaison == 0 ? Retenue.IDENTIQUES : Retenue.LEGALE;
            mois = mois.max(conventionnelle.mois());
        }
        BigDecimal montant = null;
        if (demande.salaireMensuelBrut() != null) {
            montant = mois.multiply(demande.salaireMensuelBrut()).setScale(2, RoundingMode.HALF_UP);
            hypotheses.add("Montant en euros : indemnité en mois × salaire mensuel brut de référence fourni. Pour "
                    + "l'indemnité légale, la référence est la plus favorable entre la moyenne des 12 derniers mois "
                    + "et le tiers des 3 derniers mois (R1234-4).");
        }
        return new Resultat(true, null, anciennete.getYears(), anciennete.getMonths(), legale, conventionnelle,
                retenue, mois, montant, hypotheses, avertissements);
    }

    private static String absenceDroit(Demande demande, ProfilSalarie profil, long moisANotification) {
        if (demande.motif() == Motif.FAUTE_GRAVE || demande.motif() == Motif.FAUTE_LOURDE) {
            return "Pas d'indemnité de licenciement en cas de faute grave ou lourde (L1234-9).";
        }
        if (profil.typeContrat() == ProfilSalarie.TypeContrat.CDD || profil.typeContrat() == ProfilSalarie.TypeContrat.INTERIM) {
            return "L'indemnité de licenciement est réservée aux contrats à durée indéterminée (L1234-9) ; "
                    + "un CDD ouvre droit, sous conditions, à l'indemnité de fin de contrat.";
        }
        if (moisANotification < MOIS_MINIMUM) {
            return "Il faut au moins 8 mois d'ancienneté ininterrompue à la date de notification (L1234-9) ; "
                    + moisANotification + " mois complets ici.";
        }
        return null;
    }

    static Calcul legale(int moisComplets) {
        Bareme.Resultat r = LEGAL.appliquer(moisComplets);
        List<String> detail = new ArrayList<>();
        detail.add("Ancienneté : " + moisComplets + " mois complets, soit " + Bareme.format(Bareme.annees(moisComplets))
                + " années (les mois au-delà des années pleines comptent au prorata)");
        detail.addAll(r.detail());
        return new Calcul("Code du travail (minimum légal)", r.mois().setScale(2, RoundingMode.HALF_UP), detail,
                List.of(new Source("L1234-9", CODE), new Source("R1234-1", CODE), new Source("R1234-2", CODE),
                        new Source("R1234-4", CODE)));
    }

    private Calcul conventionnelle(ProfilSalarie profil, int moisComplets, List<String> hypotheses,
                                   List<String> avertissements) {
        if (profil.idcc() == null) {
            avertissements.add("Convention collective non renseignée : seule l'indemnité légale est calculée ; "
                    + "la convention peut prévoir plus.");
            return null;
        }
        BaremeConventionnel bareme = conventions.get(profil.idcc());
        if (bareme == null) {
            avertissements.add("Barème de la convention IDCC " + profil.idcc() + " non intégré : seule l'indemnité "
                    + "légale est calculée ; la convention peut prévoir plus.");
            return null;
        }
        var calcul = bareme.calculer(profil, moisComplets);
        if (calcul.isEmpty()) {
            avertissements.add("Statut (cadre ou non cadre) nécessaire pour l'indemnité conventionnelle : seule "
                    + "l'indemnité légale est calculée.");
            return null;
        }
        hypotheses.addAll(bareme.hypotheses());
        return calcul.get();
    }

    private static ResponseStatusException invalide(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
