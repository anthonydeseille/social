package fr.deseille.social.questions;

import static org.assertj.core.api.Assertions.assertThat;

import fr.deseille.social.calcul.Indemnite.Motif;
import fr.deseille.social.calcul.Indemnite.Resultat;
import fr.deseille.social.calcul.Indemnite.Retenue;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilSalarie.Statut;
import fr.deseille.social.profil.ProfilSalarie.TypeContrat;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Calculs déclenchés par une question, avec les vrais barèmes (Syntec compris). */
@SpringJUnitConfig(CalculsQuestionsTest.Barèmes.class)
class CalculsQuestionsTest {

    @Configuration
    @ComponentScan("fr.deseille.social.calcul")
    @Import(CalculsQuestions.class)
    static class Barèmes {
    }

    /** Profil de la question d'évaluation indemnite-licenciement-cadre-7ans. */
    private static final ProfilSalarie CADRE_SYNTEC = new ProfilSalarie(1486, LocalDate.of(2019, 10, 1), null,
            Statut.CADRE, null, TypeContrat.CDI, false, null, LocalDate.of(2026, 11, 1));

    @Autowired
    private CalculsQuestions calculs;

    private static ContexteProfil contexte(ProfilSalarie profil) {
        return new ContexteProfil(profil, null, List.of(), true, List.of());
    }

    @Test
    void question_sans_indemnite_de_licenciement_sans_calcul() {
        assertThat(calculs.pour("Combien dure ma période d'essai ?", contexte(CADRE_SYNTEC))).isEmpty();
        assertThat(calculs.pour("Ai-je droit à une indemnité de rupture conventionnelle ?", contexte(CADRE_SYNTEC)))
                .as("indemnité sans licenciement").isEmpty();
        assertThat(calculs.pour("Mon licenciement est-il valable ?", contexte(CADRE_SYNTEC)))
                .as("licenciement sans indemnité").isEmpty();
        assertThat(calculs.pour("Licencié pendant mon arrêt maladie, ai-je droit aux indemnités journalières ?",
                contexte(CADRE_SYNTEC))).as("autre indemnité nommée").isEmpty();
        assertThat(calculs.pour("Après mon licenciement, ai-je droit à une indemnité compensatrice de préavis ?",
                contexte(CADRE_SYNTEC))).as("autre indemnité nommée").isEmpty();
    }

    @Test
    void preavis_demande_a_cote_de_l_indemnite_n_exclut_pas_le_calcul() {
        assertThat(calculs.pour("Je suis employé (ETAM) chez un employeur Syntec depuis 6 mois et on me licencie. "
                + "Ai-je droit à une indemnité et à un préavis ?", contexte(CADRE_SYNTEC))).isPresent();
        assertThat(calculs.pour("Licencié, ai-je droit à une indemnité de préavis ?", contexte(CADRE_SYNTEC)))
                .as("indemnité de préavis nommée").isEmpty();
    }

    @Test
    void indemnite_pour_les_conges_ou_le_preavis_exclut_le_calcul() {
        assertThat(calculs.pour("Licencié, ai-je droit à une indemnité de congés payés ?", contexte(CADRE_SYNTEC)))
                .as("indemnité de congés nommée").isEmpty();
        assertThat(calculs.pour("Licencié, quelle indemnité pour mes congés payés non pris ?", contexte(CADRE_SYNTEC)))
                .as("indemnité pour les congés").isEmpty();
        assertThat(calculs.pour("Licencié, quelle indemnité pour le préavis non effectué ?", contexte(CADRE_SYNTEC)))
                .as("indemnité pour le préavis").isEmpty();
        assertThat(calculs.pour("Licencié, ai-je droit à une indemnité au titre du préavis ?", contexte(CADRE_SYNTEC)))
                .as("indemnité au titre du préavis").isEmpty();
    }

    @Test
    void indemnite_de_licenciement_nommee_calculee_meme_avec_une_autre_indemnite() {
        assertThat(calculs.pour("Indemnité de licenciement et indemnité de préavis : combien ?", contexte(CADRE_SYNTEC)))
                .isPresent();
        assertThat(calculs.pour("Quelle indemnité légale de licenciement ?", contexte(CADRE_SYNTEC))).isPresent();
    }

    @Test
    void indemnite_cadre_syntec_7_ans_calculee_par_le_code() {
        CalculQuestion calcul = calculs.pour("Je suis licencié pour un motif économique. Combien vais-je toucher "
                + "d'INDEMNITE ?", contexte(CADRE_SYNTEC)).orElseThrow();

        Resultat r = calcul.resultat();
        assertThat(r.droit()).isTrue();
        assertThat(r.retenue()).isEqualTo(Retenue.CONVENTIONNELLE);
        assertThat(r.moisRetenus()).isEqualByComparingTo(new BigDecimal("2.36"));
        assertThat(r.legale().mois()).isEqualByComparingTo(new BigDecimal("1.77"));
        assertThat(r.hypotheses().getFirst()).contains("date de référence du profil (2026-11-01)");
        assertThat(r.hypotheses()).as("motif lu dans la question, signalé").noneMatch(h -> h.startsWith("Licenciement supposé"))
                .contains("Motif lu dans la question : licenciement économique (même indemnité qu'un licenciement pour "
                        + "motif personnel).")
                .anyMatch(h -> h.contains("en mois de salaire"));
        assertThat(calcul.chiffres()).as("sans les dates des hypothèses").contains("2,36").doesNotContain("2026");
        assertThat(r.avertissements()).contains("Règles de calcul en cours de validation juridique.");

        assertThat(calcul.description()).contains("reprends ces chiffres tels quels", "2,36 mois de salaire",
                "1,77 mois de salaire", "indemnité conventionnelle");
    }

    @Test
    void motif_lu_dans_la_question_y_compris_une_faute_niee() {
        assertThat(CalculsQuestions.motif("licencie pour faute grave")).isEqualTo(Motif.FAUTE_GRAVE);
        assertThat(CalculsQuestions.motif("licenciement pour faute lourde")).isEqualTo(Motif.FAUTE_LOURDE);
        assertThat(CalculsQuestions.motif("licencie, sans faute grave")).isEqualTo(Motif.LICENCIEMENT_PERSONNEL);
        assertThat(CalculsQuestions.motif("licencie mais pas pour une faute grave"))
                .isEqualTo(Motif.LICENCIEMENT_PERSONNEL);
        assertThat(CalculsQuestions.motif("licencie, je n'ai pas commis de faute lourde"))
                .isEqualTo(Motif.LICENCIEMENT_PERSONNEL);
        assertThat(CalculsQuestions.motif("licencie sans preavis pour faute grave"))
                .as("« sans » porte sur le préavis").isEqualTo(Motif.FAUTE_GRAVE);
        assertThat(CalculsQuestions.motif("je n'ai pas ete paye, faute grave invoquee")).isEqualTo(Motif.FAUTE_GRAVE);
        assertThat(CalculsQuestions.motif("licenciement economique")).isEqualTo(Motif.LICENCIEMENT_ECONOMIQUE);
        assertThat(CalculsQuestions.motif("je suis licencie")).isEqualTo(Motif.LICENCIEMENT_PERSONNEL);
    }

    @Test
    void faute_grave_pas_d_indemnite() {
        CalculQuestion calcul = calculs.pour("Licencié pour faute grave, ai-je droit à une indemnité ?",
                contexte(CADRE_SYNTEC)).orElseThrow();

        assertThat(calcul.resultat().droit()).isFalse();
        assertThat(calcul.description()).contains("Pas d'indemnité : Pas d'indemnité de licenciement en cas de faute grave");
    }

    @Test
    void motif_non_precise_suppose_personnel() {
        CalculQuestion calcul = calculs.pour("Quelle indemnité de licenciement ?", contexte(CADRE_SYNTEC)).orElseThrow();

        assertThat(calcul.resultat().hypotheses()).anyMatch(h -> h.startsWith("Licenciement supposé pour motif personnel"));
    }

    @Test
    void sans_date_d_entree_calcul_impossible_explique_au_modele() {
        ProfilSalarie sansEntree = new ProfilSalarie(1486, null, null, Statut.CADRE, null, TypeContrat.CDI, false, null,
                LocalDate.of(2026, 11, 1));

        CalculQuestion calcul = calculs.pour("Quelle indemnité de licenciement ?", contexte(sansEntree)).orElseThrow();

        assertThat(calcul.resultat()).isNull();
        assertThat(calcul.description()).contains("impossible (la date d'entrée dans l'entreprise n'est pas renseignée "
                + "dans le profil). Explique", "sans faire le calcul toi-même");
        assertThat(calcul.chiffres()).isEmpty();
    }

    @Test
    void salaire_mensuel_brut_lu_dans_la_question_donne_un_montant_en_euros() {
        CalculQuestion calcul = calculs.pour("Je suis cadre, je gagne 3 000 € brut par mois et je suis licencié pour "
                + "motif économique : quelle indemnité de licenciement ?", contexte(CADRE_SYNTEC)).orElseThrow();

        assertThat(calcul.resultat().montantBrut()).isEqualByComparingTo(new BigDecimal("7083.33"));
        assertThat(calcul.resultat().hypotheses()).anyMatch(h -> h.startsWith("Salaire mensuel brut de 3 000 € lu dans "
                + "la question")).noneMatch(h -> h.startsWith("Montant exprimé en mois"));
        assertThat(calcul.chiffres()).contains("Soit 7083,33 € brut");
    }

    @Test
    void salaire_lu_selon_le_contexte_du_montant() {
        assertThat(CalculsQuestions.salaire(CalculsQuestions.normaliser("Mon salaire est de 2 850,50 euros"))
                .mensuel()).isEqualByComparingTo("2850.50");
        assertThat(CalculsQuestions.salaire("je touche 2500€ par mois").mensuel()).isEqualByComparingTo("2500");
        assertThat(CalculsQuestions.salaire("payee 2.400 € brut").mensuel()).isEqualByComparingTo("2400");
        assertThat(CalculsQuestions.salaire("salaire de 42 000 € brut par an").mensuel())
                .as("annuel divisé par 12").isEqualByComparingTo("3500");
        assertThat(CalculsQuestions.salaire("salaire de 42 000 € brut par an").hypothese())
                .contains("salaire annuel divisé par 12");
        assertThat(CalculsQuestions.salaire("mon salaire annuel brut est de 36 000 €").mensuel())
                .as("annuel annoncé avant le montant").isEqualByComparingTo("3000");
        assertThat(CalculsQuestions.salaire("je gagne 2 500 € brut, nettement plus qu'avant").mensuel())
                .as("« nettement » n'est pas « net »").isEqualByComparingTo("2500");
        assertThat(CalculsQuestions.salaire("mon salaire est de 36 000 € par an").mensuel()).isEqualByComparingTo("3000");
        assertThat(CalculsQuestions.salaire("avant 30 000 € par an, puis je gagne 2 400 € brut").mensuel())
                .as("« par an » porte sur l'autre montant").isEqualByComparingTo("2400");
        assertThat(CalculsQuestions.salaire("ma prime est annuelle, je gagne 2 500 € brut").mensuel())
                .as("la virgule arrête « annuel »").isEqualByComparingTo("2500");
    }

    @Test
    void montant_qui_n_est_pas_un_salaire_brut_mensuel_ignore() {
        assertThat(CalculsQuestions.salaire("on me propose 10 000 € pour partir").mensuel()).as("sans mot de salaire").isNull();
        assertThat(CalculsQuestions.salaire("je touche une prime de 500 € brut").mensuel()).as("prime").isNull();
        assertThat(CalculsQuestions.salaire("je touche 10 000 € d'indemnite").mensuel()).as("indemnité").isNull();
        assertThat(CalculsQuestions.salaire("je gagne 2 000 € net").hypothese()).as("net")
                .contains("salaire net, le calcul part du brut");
        assertThat(CalculsQuestions.salaire("je gagne 2 000 € net").mensuel()).isNull();
        assertThat(CalculsQuestions.salaire("mon salaire net est de 2 000 €").mensuel()).as("net annoncé avant").isNull();
        assertThat(CalculsQuestions.salaire("je gagne 2 000 € brut, puis mon salaire est passe a 2 200 € brut")
                .hypothese()).as("deux salaires").contains("plusieurs salaires");
        assertThat(CalculsQuestions.salaire("je gagne 50 € brut de l'heure").mensuel()).as("hors bornes").isNull();
        assertThat(CalculsQuestions.salaire("quelle indemnite ?").hypothese())
                .isEqualTo("Montant exprimé en mois de salaire brut de référence : le salaire n'est pas donné dans la question.");
    }
}
