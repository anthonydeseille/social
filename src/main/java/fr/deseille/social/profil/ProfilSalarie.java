package fr.deseille.social.profil;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Set;

/**
 * Profil d'un salarié, fourni à chaque requête et jamais stocké côté serveur : il peut contenir
 * des données sensibles au sens du RGPD (grossesse, santé). Tous les champs sont facultatifs ;
 * plus le profil est complet, plus les droits identifiés sont précis.
 *
 * @param idcc          identifiant de la convention collective (sur la fiche de paie)
 * @param dateEntree    date d'entrée dans l'entreprise, pour le calcul de l'ancienneté
 * @param age           âge en années
 * @param statut        cadre ou non cadre
 * @param typeContrat   nature du contrat de travail
 * @param tempsPartiel  contrat à temps partiel ({@code false} si absent : Jackson 3 refuse par
 *                      défaut une valeur absente pour un {@code boolean} primitif)
 * @param situations    situations particulières ouvrant des droits ou protections spécifiques
 * @param dateReference date à laquelle les droits sont évalués (ex. date de fin de contrat) ;
 *                      aujourd'hui si absente
 */
public record ProfilSalarie(
        @Min(1) @Max(9999) Integer idcc,
        LocalDate dateEntree,
        @Min(14) @Max(99) Integer age,
        Statut statut,
        TypeContrat typeContrat,
        Boolean tempsPartiel,
        Set<Situation> situations,
        LocalDate dateReference) {

    public ProfilSalarie {
        tempsPartiel = Boolean.TRUE.equals(tempsPartiel);
        situations = situations == null ? Set.of() : Set.copyOf(situations);
    }

    public ProfilSalarie avecDateReference(LocalDate date) {
        return new ProfilSalarie(idcc, dateEntree, age, statut, typeContrat, tempsPartiel, situations, date);
    }

    public enum Statut { CADRE, NON_CADRE }

    public enum TypeContrat { CDI, CDD, INTERIM, APPRENTISSAGE, PROFESSIONNALISATION }

    public enum Situation {
        GROSSESSE,
        ARRET_MALADIE,
        ACCIDENT_DU_TRAVAIL,
        TRAVAILLEUR_HANDICAPE,
        /** Représentant du personnel ou mandat syndical : salarié protégé. */
        SALARIE_PROTEGE
    }
}
